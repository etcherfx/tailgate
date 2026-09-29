# /// script
# requires-python = ">=3.10"
# ///
"""Runs the in-game Tailgate self-test for one Minecraft version and loader, unattended.

    uv run scripts/runtime-test.py 26.3-fabric --java 25=/path/to/jdk-25

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

Java: the version's own requirement is looked up from `--java N=<home>`, then the
`JAVA_HOME_<N>_X64`/`_AARCH64` variables GitHub's setup-java exports, then `JAVA_HOME`.
"""
import argparse
import glob
import json
import os
import platform
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
    javas = parse_javas(args.java)
    work = os.path.abspath(args.work)
    mcdir = os.path.join(work, "mc")

    version = install(work, mcdir, mc, loader, args.loader_version, javas)
    java = java_for(required_java(mcdir, version), javas)
    fixture_dir = os.path.join(ROOT, "core", "build", "selftest-fixture")
    make_dirs(work)
    fixture_log = open_log(os.path.join(work, "fixture.log"))
    fixture = start_fixture(fixture_dir, java_home(17, javas, at_least=True), fixture_log)
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
        try:
            urllib.request.urlretrieve(HMC_URL, hmc + ".part")
            os.replace(hmc + ".part", hmc)
        except OSError as e:
            abort(f"couldn't download HeadlessMC: {e}")
    hmc_java = java_for(17, javas, at_least=True)
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
    candidates = dict(javas)
    for key, value in os.environ.items():
        for suffix in ("_X64", "_AARCH64", "_ARM64"):
            if key.startswith("JAVA_HOME_") and key.endswith(suffix):
                n = key[len("JAVA_HOME_"):-len(suffix)].split("_")[0]
                if n.isdigit():
                    candidates.setdefault(int(n), value)
    home = candidates.get(major)
    if home is None and at_least:
        newer = [n for n in candidates if n >= major]
        home = candidates[max(newer)] if newer else None
    home = home or os.environ.get("JAVA_HOME")
    if not home:
        sys.exit(f"no JDK for Java {major}; pass --java {major}=<home>")
    return home


def java_exe(home):
    return fwd(os.path.join(home, "bin", "java.exe" if IS_WINDOWS else "java"))


# --- fixture ------------------------------------------------------------------------------------

def start_fixture(fixture_dir, jdk, log):
    """Starts `:core:selfTestFixture` with [jdk] as Gradle's JAVA_HOME; output goes to [log]."""
    remove_tree(fixture_dir)
    gradlew = os.path.join(ROOT, "gradlew.bat" if IS_WINDOWS else "gradlew")
    # gradle.properties pins org.gradle.java.home to a developer's JDK; point it at this machine's.
    command = [gradlew, "--quiet", f"-Dorg.gradle.java.home={fwd(jdk)}", ":core:selfTestFixture"]
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
    cp, seen = [], set()
    for data in versions:
        for lib in data.get("libraries", []):
            if not rules_ok(lib.get("rules")):
                continue
            parts = lib["name"].split(":")
            group, artifact, ver = parts[:3]
            classifier = parts[3] if len(parts) > 3 else None
            if classifier and not native_classifier_ok(classifier):
                continue
            key = (group, artifact, classifier)
            if key in seen:
                continue
            seen.add(key)
            # Pre-1.19 versions ship natives as a classifier the launcher extracts.
            native = lib.get("natives", {}).get(OS_NAME)
            if native:
                native = native.replace("${arch}", "64")
                info = lib.get("downloads", {}).get("classifiers", {}).get(native, {})
                path = os.path.join(libdir, info["path"]) if "path" in info else \
                    maven_path(libdir, group, artifact, ver, native)
                if os.path.exists(path):
                    extract_natives(path, natives, lib.get("extract", {}).get("exclude", []))
            download = lib.get("downloads", {}).get("artifact")
            if lib.get("downloads") and not download:
                continue
            path = os.path.join(libdir, download["path"]) if download and download.get("path") else \
                maven_path(libdir, group, artifact, ver, classifier)
            if os.path.exists(path):
                cp.append(path)
            else:
                print("missing library", path)
    client = os.path.join(mcdir, "versions", base["id"], base["id"] + ".jar")
    if not os.path.exists(client):
        client = os.path.join(mcdir, "versions", version, version + ".jar")
    cp.append(client)

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
