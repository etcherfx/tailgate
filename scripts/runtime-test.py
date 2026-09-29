# /// script
# requires-python = ">=3.10"
# ///
"""Runs the in-game Tailgate self-test for one Minecraft version and loader, unattended.

    uv run scripts/runtime-test.py 26.3-fabric

What it does:
  1. Installs Minecraft and the loader with HeadlessMC (downloaded on first use).
  2. Starts the TLS fixture (`gradlew :core:selfTestFixture`) the self-test connects to.
  3. Launches the game directly with the merged jar as its only mod and
     `-Dtailgate.selftest=<fixture> -Dtailgate.selftest.exit=true`.
  4. Reads `tailgate-selftest.txt` and exits 0 on pass, 1 on fail or timeout.

Nothing waits for a human: the self-test fails and quits the game when a screen other than
the title screen stays up (a loader warning, an error), and a hard timeout kills the whole
process tree. Launching bypasses HeadlessMC's `-lwjgl` mode, whose ASM can't read the Java 27
classes in LWJGL 3.4.3 (Minecraft 26.x), so the game needs a display: on Linux CI wrap this
script in `xvfb-run`; elsewhere a window opens briefly.

Java: each major version's JDK comes from `--java N=<home>`, then the
`JAVA_HOME_<N>_X64`/`_AARCH64` variables GitHub's setup-java exports, then JDKs found in
standard install locations (~/.jdks, ~/.gradle/jdks, SDKMAN, /usr/lib/jvm, macOS's
JavaVirtualMachines, Program Files on Windows), then `JAVA_HOME`. A version runs on the JDK
it requires, or else the nearest newer one; Java 8 has no substitute, since old Forge breaks
on 9+.
"""
import argparse
import concurrent.futures
import glob
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
import zipfile
from typing import NoReturn

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HMC_VERSION = "2.10.0"
HMC_URL = f"https://github.com/headlesshq/headlessmc/releases/download/{HMC_VERSION}/headlessmc-launcher-{HMC_VERSION}.jar"
LOADER_IDS = {"fabric": "fabric-loader", "forge": "forge", "neoforge": "neoforge"}
IS_WINDOWS = os.name == "nt"
OS_NAME = "windows" if IS_WINDOWS else "osx" if sys.platform == "darwin" else "linux"
IS_ARM = platform.machine().lower() in ("arm64", "aarch64")
# The Gradle client (for the fixture) and HeadlessMC run on the newest JDK at least this new.
TOOL_JAVA = 17


def main():
    p = argparse.ArgumentParser(description="Runs the in-game Tailgate self-test for one Minecraft version and loader.")
    p.add_argument("node", help="<minecraft version>-<loader>, e.g. 26.3-fabric")
    p.add_argument("--jar", help="merged jar (default: build/libs/tailgate-*.jar)")
    p.add_argument("--work", default=os.environ.get("TAILGATE_RUNTIME_DIR", os.path.join(ROOT, "build", "runtime-test")),
                   help="cache and run directory (default: build/runtime-test or $TAILGATE_RUNTIME_DIR)")
    p.add_argument("--java", action="append", default=[], metavar="N=HOME", help="JDK home for Java N (repeatable)")
    p.add_argument("--loader-version", help="pin the loader version (default: HeadlessMC's latest)")
    p.add_argument("--timeout", type=int, default=600, help="seconds before the game is killed (default 600)")
    args = p.parse_args()

    mc, _, loader = args.node.rpartition("-")
    if loader not in LOADER_IDS or not mc:
        sys.exit(f"node must look like <version>-<fabric|forge|neoforge>, got {args.node}")
    jar = args.jar or newest(glob.glob(os.path.join(ROOT, "build", "libs", "tailgate-*.jar")))
    if not jar:
        sys.exit("no merged jar; run ./gradlew mergeJar first or pass --jar")
    javas = find_javas(args.java)
    work = os.path.abspath(args.work)
    mcdir = os.path.join(work, "mc")

    version = install(work, mcdir, mc, loader, args.loader_version, javas)
    java = java_for(required_java(mcdir, version), javas)
    fixture_dir = os.path.join(ROOT, "core", "build", "selftest-fixture")
    make_dirs(work)
    fixture_log = open_log(os.path.join(work, "fixture.log"))
    fixture = start_fixture(fixture_dir, java_home(TOOL_JAVA, javas, at_least=True), fixture_log)
    try:
        address = wait_for_file(os.path.join(fixture_dir, "address.txt"), 300, fixture).strip()
        gamedir = prepare_gamedir(os.path.join(work, "run", args.node), jar)
        jvm = [f"-Dtailgate.selftest={address}", "-Dtailgate.selftest.exit=true",
               "-Djavax.net.ssl.trustStore=" + os.path.join(fixture_dir, "truststore.jks"),
               "-Djavax.net.ssl.trustStorePassword=changeit"]
        log = os.path.join(gamedir, "game.log")
        code = launch(mcdir, version, gamedir, java, jvm, log, args.timeout)
    finally:
        kill_tree(fixture)
        fixture_log.close()

    report = os.path.join(gamedir, "tailgate-selftest.txt")
    text = read_text(report) if os.path.exists(report) else ""
    print(f"== {args.node} ({version}), game exit {code}")
    print(text or "no tailgate-selftest.txt written")
    if "result=pass" in text:
        return 0
    print(f"-- last lines of {log}")
    print("\n".join(read_text(log).splitlines()[-60:]))
    return 1


def newest(paths):
    return max(paths, key=os.path.getmtime) if paths else None


# --- installing ---------------------------------------------------------------------------------

def install(work, mcdir, mc, loader, loader_version, javas):
    """Installs the version with HeadlessMC unless present; returns its version id."""
    existing = find_version(mcdir, mc, loader, loader_version)
    if existing:
        return existing
    hmc = os.path.join(work, f"headlessmc-launcher-{HMC_VERSION}.jar")
    if not os.path.exists(hmc):
        make_dirs(work)
        print(f"Downloading HeadlessMC {HMC_VERSION}")
        if not fetch(HMC_URL, hmc):
            abort("couldn't download HeadlessMC")
    hmc_java = java_for(TOOL_JAVA, javas, at_least=True)
    write_text(os.path.join(work, "HeadlessMC", "config.properties"), "\n".join([
        f"hmc.java.versions={';'.join(sorted(set(java_exe(h) for h in javas.values())) or [hmc_java])}",
        f"hmc.mcdir={fwd(mcdir)}",
        f"hmc.gamedir={fwd(os.path.join(work, 'run', 'hmc'))}",
        "hmc.offline=true",
        "hmc.assets.dummy=true",
        "hmc.exit.on.failed.command=true",
        "hmc.rethrow.launch.exceptions=true",
    ]) + "\n")
    command = [loader, mc]
    if loader_version:
        command += ["--uid", loader_version]
    print(f"Installing {' '.join(command)} with HeadlessMC")
    try:
        subprocess.check_call([hmc_java, "-jar", hmc, "--command", *command], cwd=work)
    except (OSError, subprocess.CalledProcessError) as e:
        abort(f"HeadlessMC couldn't install {' '.join(command)}: {e}")
    installed = find_version(mcdir, mc, loader, loader_version)
    if not installed:
        sys.exit(f"HeadlessMC didn't install a {loader} version for {mc}")
    return installed


def find_version(mcdir, mc, loader, loader_version):
    matches = []
    for path in glob.glob(os.path.join(mcdir, "versions", "*", "*.json")):
        vid = os.path.basename(os.path.dirname(path))
        if not os.path.basename(path) == vid + ".json":
            continue
        data = read_json(path)
        lower = vid.lower()
        if data.get("inheritsFrom") != mc or LOADER_IDS[loader] not in lower:
            continue
        if loader == "forge" and "neoforge" in lower:
            continue
        if loader_version and loader_version not in vid:
            continue
        matches.append(path)
    newest_json = newest(matches)
    return os.path.basename(os.path.dirname(newest_json)) if newest_json else None


# --- java ---------------------------------------------------------------------------------------

def required_java(mcdir, version):
    for data in chain(mcdir, version):
        if "javaVersion" in data:
            return data["javaVersion"]["majorVersion"]
    return 8


def java_for(major, javas, at_least=False):
    return java_exe(java_home(major, javas, at_least))


def java_home(major, javas, at_least=False):
    """The JDK for Java [major]: the newest installed one when [at_least], else the exact version
    or the nearest newer one. Java 8 must match exactly."""
    newer = sorted(n for n in javas if n >= major)
    if at_least and newer:
        return javas[newer[-1]]
    if major in javas:
        return javas[major]
    if major == 8 or not newer:
        abort(f"no JDK for Java {major}; pass --java {major}=<home>")
    print(f"No JDK for Java {major}; using Java {newer[0]}")
    return javas[newer[0]]


def find_javas(specs):
    """Maps each major version to a JDK home, from --java, setup-java's variables, standard
    install locations and JAVA_HOME, in that order."""
    javas = parse_javas(specs)
    for key, value in os.environ.items():
        for suffix in ("_X64", "_AARCH64", "_ARM64"):
            if key.startswith("JAVA_HOME_") and key.endswith(suffix):
                n = key[len("JAVA_HOME_"):-len(suffix)].split("_")[0]
                if n.isdigit():
                    javas.setdefault(int(n), value)
    found = {}
    for home in installed_jdks():
        version = jdk_version(home)
        if version and (version[0] not in found or version > found[version[0]][0]):
            found[version[0]] = (version, home)
    for major, (_, home) in found.items():
        javas.setdefault(major, home)
    home = os.environ.get("JAVA_HOME")
    version = jdk_version(home) if home else None
    if version:
        javas.setdefault(version[0], home)
    return javas


def installed_jdks():
    """JDK homes in the usual install locations; missing locations are skipped."""
    home = os.path.expanduser("~")
    roots = [os.path.join(home, ".jdks"), os.path.join(home, ".gradle", "jdks"),
             os.path.join(home, ".sdkman", "candidates", "java"), "/usr/lib/jvm",
             "/Library/Java/JavaVirtualMachines"]
    if IS_WINDOWS:
        program_files = os.environ.get("ProgramFiles", "C:/Program Files")
        roots += [os.path.join(program_files, vendor)
                  for vendor in ("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto")]
    for root in roots:
        try:
            children = sorted(os.listdir(root))
        except OSError:
            continue
        for child in children:
            # macOS bundles keep the JDK in Contents/Home.
            for path in (os.path.join(root, child), os.path.join(root, child, "Contents", "Home")):
                if jdk_version(path):
                    yield path


def jdk_version(home):
    """The version of the JDK at [home] as a tuple with the major version first, e.g. (8, 0, 504)
    for "1.8.0_504"; None when [home] isn't a JDK."""
    if not os.path.isfile(java_exe(home)):
        return None
    try:
        with open(os.path.join(home, "release"), encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return None
    for line in lines:
        if line.startswith("JAVA_VERSION="):
            parts = tuple(int(n) for n in re.findall(r"\d+", line))
            if parts[:1] == (1,):
                parts = parts[1:]
            return parts or None
    return None


def java_exe(home):
    return fwd(os.path.join(home, "bin", "java.exe" if IS_WINDOWS else "java"))


# --- fixture ------------------------------------------------------------------------------------

def start_fixture(fixture_dir, jdk, log):
    """Starts `:core:selfTestFixture` with [jdk] as the Gradle client's JAVA_HOME; output goes to
    [log]. The daemon picks its own JDK from gradle/gradle-daemon-jvm.properties."""
    remove_tree(fixture_dir)
    gradlew = os.path.join(ROOT, "gradlew.bat" if IS_WINDOWS else "gradlew")
    command = [gradlew, "--quiet", ":core:selfTestFixture"]
    return spawn(command, ROOT, log, dict(os.environ, JAVA_HOME=jdk))


def wait_for_file(path, seconds, process):
    deadline = time.time() + seconds
    while not os.path.exists(path):
        if process.poll() is not None:
            sys.exit(f"the self-test fixture exited with {process.returncode}; see fixture.log")
        if time.time() > deadline:
            sys.exit(f"the self-test fixture didn't start within {seconds}s")
        time.sleep(0.5)
    time.sleep(0.2)  # let the fixture finish writing the file
    return read_text(path)


# --- launching ----------------------------------------------------------------------------------

def prepare_gamedir(gamedir, jar):
    remove_tree(gamedir)
    mods = os.path.join(gamedir, "mods")
    make_dirs(mods)
    try:
        shutil.copy(jar, mods)
    except OSError as e:
        abort(f"can't copy {jar} into {mods}: {e}")
    # Skip the first-launch accessibility screen; keep ticking while unfocused.
    write_text(os.path.join(gamedir, "options.txt"), "onboardAccessibility:false\npauseOnLostFocus:false\n")
    return gamedir


def chain(mcdir, version):
    out = []
    while version:
        data = read_json(os.path.join(mcdir, "versions", version, version + ".json"))
        out.append(data)
        version = data.get("inheritsFrom")
    return out


def rules_ok(rules):
    """Evaluates a version JSON rule list for this machine; the last matching rule wins."""
    if not rules:
        return True
    ok = False
    for rule in rules:
        if rule.get("features"):
            continue
        os_rule = rule.get("os", {})
        if os_rule.get("name") not in (None, OS_NAME) or not arch_matches(os_rule.get("arch")):
            continue
        ok = rule["action"] == "allow"
    return ok


def arch_matches(arch):
    if arch is None:
        return True
    if arch.lower() in ("arm64", "aarch64"):
        return IS_ARM
    return arch.lower() != "x86" and not IS_ARM


def native_classifier_ok(classifier):
    if "natives" not in classifier:
        return True
    names = {"windows": ("windows",), "linux": ("linux",), "osx": ("osx", "macos")}[OS_NAME]
    if not any(n in classifier for n in names):
        return False
    arm = "arm64" in classifier or "aarch64" in classifier
    return arm == IS_ARM and not classifier.endswith("-x86")


def launch(mcdir, version, gamedir, java, extra_jvm, log, timeout):
    versions = chain(mcdir, version)
    base = versions[-1]
    libdir = os.path.join(mcdir, "libraries")
    natives = os.path.join(gamedir, "natives")
    make_dirs(natives)
    # HeadlessMC downloads libraries when it launches, not when it installs, so fetch them here.
    cp, natives_jars, downloads, seen = [], [], {}, set()
    for data in versions:
        for lib in data.get("libraries", []):
            if not rules_ok(lib.get("rules")):
                continue
            parts = lib["name"].split(":")
            group, artifact, ver = parts[:3]
            classifier = parts[3] if len(parts) > 3 else None
            if classifier and not native_classifier_ok(classifier):
                continue
            # 1.14–1.18 list a library's natives as a second entry with the same name.
            key = (group, artifact, classifier, "natives" in lib)
            if key in seen:
                continue
            seen.add(key)
            # Pre-1.19 versions ship natives as a classifier the launcher extracts.
            native = lib.get("natives", {}).get(OS_NAME)
            if native:
                native = native.replace("${arch}", "64")
                info = lib.get("downloads", {}).get("classifiers", {}).get(native, {})
                path = library_file(libdir, lib, info, group, artifact, ver, native, downloads)
                natives_jars.append((path, lib.get("extract", {}).get("exclude", [])))
            download = lib.get("downloads", {}).get("artifact")
            if lib.get("downloads") and not download:
                continue
            cp.append(library_file(libdir, lib, download or {}, group, artifact, ver, classifier, downloads))
    # Like Mojang's launcher, run the client jar under the launched version's name: Forge 1.17+
    # keeps it off the module path by that name (-DignoreList=...,${version_name}.jar).
    base_client = os.path.join(mcdir, "versions", base["id"], base["id"] + ".jar")
    client = os.path.join(mcdir, "versions", version, version + ".jar")
    client_download = base.get("downloads", {}).get("client", {})
    if not os.path.exists(client) and not os.path.exists(base_client) and client_download.get("url"):
        downloads[base_client] = (client_download["url"], client_download.get("sha1"))

    if downloads:
        print(f"Downloading {len(downloads)} libraries")
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            list(pool.map(lambda item: fetch(item[1][0], item[0], item[1][1]), downloads.items()))
    if not os.path.exists(client):
        if not os.path.exists(base_client):
            abort(f"no client jar for {base['id']}")
        copy_file(base_client, client)
    cp.append(client)
    for path in [p for p in cp if not os.path.exists(p)]:
        print("missing library", path)
        cp.remove(path)
    for path, excludes in natives_jars:
        if os.path.exists(path):
            extract_natives(path, natives, excludes)

    assets = os.path.join(mcdir, "assets")
    index = os.path.join(assets, "indexes", base.get("assets", "legacy") + ".json")
    if not os.path.exists(index):
        write_text(index, '{"objects":{}}')
    subs = {
        "library_directory": libdir, "classpath_separator": os.pathsep, "version_name": version,
        "natives_directory": natives, "launcher_name": "tailgate-runtime-test", "launcher_version": "1",
        "classpath": os.pathsep.join(cp), "auth_player_name": "TailgateTest", "game_directory": gamedir,
        "assets_root": assets, "game_assets": assets, "assets_index_name": base.get("assets", "legacy"),
        "auth_uuid": "00000000000000000000000000000001", "auth_access_token": "0", "auth_session": "0",
        "user_type": "legacy", "version_type": "release", "clientid": "", "auth_xuid": "", "user_properties": "{}",
    }

    def expand(value):
        for k, v in subs.items():
            value = value.replace("${" + k + "}", v)
        return value

    def arguments(kind):
        out = []
        for data in reversed(versions):
            for a in data.get("arguments", {}).get(kind, []):
                if isinstance(a, str):
                    out.append(expand(a))
                elif rules_ok(a.get("rules")) and not any(r.get("features") for r in a.get("rules", [])):
                    out += [expand(v) for v in (a["value"] if isinstance(a["value"], list) else [a["value"]])]
        return out

    jvm = arguments("jvm") or [f"-Djava.library.path={natives}", "-cp", os.pathsep.join(cp)]
    game = arguments("game")
    if not game:
        legacy = next(d["minecraftArguments"] for d in versions if "minecraftArguments" in d)
        game = [expand(a) for a in legacy.split()]
    main_class = next(d["mainClass"] for d in versions if "mainClass" in d)
    command = [java, "-Xmx2G", *extra_jvm, *jvm, main_class, *game]
    print(f"Launching {version} with {java} (timeout {timeout}s)")
    out = open_log(log)
    try:
        process = spawn(command, gamedir, out)
        try:
            return process.wait(timeout)
        except subprocess.TimeoutExpired:
            print(f"timed out after {timeout}s; killing the game")
            kill_tree(process)
            return None
    finally:
        out.close()


def maven_path(libdir, group, artifact, ver, classifier):
    name = f"{artifact}-{ver}" + (f"-{classifier}" if classifier else "") + ".jar"
    return os.path.join(libdir, *group.split("."), artifact, ver, name)


def library_file(libdir, lib, info, group, artifact, ver, classifier, downloads):
    """Returns where a library lives, queueing it in [downloads] if it's missing and has a source."""
    path = os.path.join(libdir, info["path"]) if info.get("path") else \
        maven_path(libdir, group, artifact, ver, classifier)
    if os.path.exists(path):
        return path
    url = info.get("url")
    if url is None and not lib.get("downloads"):
        # Old version files name only a Maven repository (Mojang's when absent).
        base = lib.get("url", "https://libraries.minecraft.net/").rstrip("/") + "/"
        url = base + os.path.relpath(path, libdir).replace(os.sep, "/")
    if url:
        downloads[path] = (url, info.get("sha1"))
    return path


def fetch(url, path, sha1=None, attempts=3):
    """Downloads [url] to [path], checking [sha1] when given; returns whether it succeeded."""
    make_dirs(os.path.dirname(path))
    part = path + ".part"
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as response, open(part, "wb") as out:
                shutil.copyfileobj(response, out)
            if sha1:
                with open(part, "rb") as f:
                    # Mojang's version files only publish SHA-1; this catches truncated downloads.
                    if hashlib.new("sha1", f.read(), usedforsecurity=False).hexdigest() != sha1:
                        raise OSError("checksum mismatch")
            os.replace(part, path)
            return True
        except OSError as e:  # URLError and HTTPError are OSErrors
            if getattr(e, "code", None) == 404 or attempt == attempts - 1:
                print(f"couldn't download {url}: {e}")
                return False
            time.sleep(2 ** attempt)
    return False


def extract_natives(jar, target, excludes):
    try:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith("/") or any(name.startswith(e) for e in excludes + ["META-INF/"]):
                    continue
                dest = os.path.join(target, name)
                if not os.path.exists(dest):
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(dest, "wb") as f:
                        f.write(z.read(name))
    except (OSError, zipfile.BadZipFile) as e:
        abort(f"can't extract natives from {jar}: {e}")


# --- processes ----------------------------------------------------------------------------------

def spawn(command, cwd, stdout, env=None):
    """Starts [command] in its own process group, so kill_tree can take its children with it."""
    try:
        if sys.platform == "win32":
            return subprocess.Popen(command, cwd=cwd, stdout=stdout, stderr=subprocess.STDOUT, env=env,
                                    creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        return subprocess.Popen(command, cwd=cwd, stdout=stdout, stderr=subprocess.STDOUT, env=env,
                                start_new_session=True)
    except OSError as e:
        abort(f"can't start {command[0]}: {e}")


def kill_tree(process):
    if process.poll() is not None:
        return
    if sys.platform == "win32":
        subprocess.call(["taskkill", "/T", "/F", "/PID", str(process.pid)],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.wait()


def fwd(path):
    return path.replace("\\", "/")


# --- files --------------------------------------------------------------------------------------
# File operations go through these, so a failure aborts the run naming the path.

def abort(message) -> NoReturn:
    sys.exit(f"runtime test aborted: {message}")


def parse_javas(specs):
    javas = {}
    for spec in specs:
        major, _, home = spec.partition("=")
        if not major.isdigit() or not home:
            abort(f"--java takes N=<JDK home>, got {spec}")
        javas[int(major)] = home
    return javas


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError as e:
        abort(f"can't read {path}: {e}")


def read_json(path):
    try:
        return json.loads(read_text(path))
    except ValueError as e:
        abort(f"{path} isn't valid JSON: {e}")


def write_text(path, text):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    except OSError as e:
        abort(f"can't write {path}: {e}")


def copy_file(source, target):
    try:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copyfile(source, target)
    except OSError as e:
        abort(f"can't copy {source} to {target}: {e}")


def open_log(path):
    try:
        return open(path, "w", encoding="utf-8")
    except OSError as e:
        abort(f"can't write {path}: {e}")


def make_dirs(path):
    try:
        os.makedirs(path, exist_ok=True)
    except OSError as e:
        abort(f"can't create {path}: {e}")


def remove_tree(path):
    try:
        shutil.rmtree(path)
    except FileNotFoundError:
        pass
    except OSError as e:
        abort(f"can't delete {path}: {e}")


if __name__ == "__main__":
    sys.exit(main())
