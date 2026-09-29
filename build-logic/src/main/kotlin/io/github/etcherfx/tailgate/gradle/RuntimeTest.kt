package io.github.etcherfx.tailgate.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Runs the in-game Tailgate self-test for one Minecraft release and loader, unattended:
 *
 *     ./gradlew runtimeTest --node 26.3-fabric -Ptailgate.nodes=none
 *
 * 1. Installs Minecraft and the loader with HeadlessMC (downloaded on first use).
 * 2. Starts the TLS fixture (core's `SelfTestFixture`) the self-test connects to.
 * 3. Launches the game directly with the merged jar as its only mod and
 *    `-Dtailgate.selftest=<fixture> -Dtailgate.selftest.exit=true`.
 * 4. Reads `tailgate-selftest.txt` and fails the task unless it reports a pass.
 *
 * Nothing waits for a human: the self-test fails and quits the game when a screen other than the
 * title screen stays up (a loader warning, an error), and a hard timeout kills the whole process
 * tree. Launching bypasses HeadlessMC's `-lwjgl` mode, whose ASM can't read the Java 27 classes in
 * LWJGL 3.4.3 (Minecraft 26.x), so the game needs a display: on Linux CI run Gradle under
 * `xvfb-run`; elsewhere a window opens briefly.
 *
 * The game runs on the smallest of Java 8, 17, 21 and 25 that its release supports, from Gradle's
 * toolchains; HeadlessMC and the fixture run on Gradle's own JVM. The task doesn't build the jar,
 * since a rebuild would replace a multi-node jar with one holding only the active node.
 */
abstract class RuntimeTest : DefaultTask() {
    @get:Input
    @get:Option(option = "node", description = "The release to launch, as <minecraft version>-<loader>, e.g. 26.3-fabric.")
    abstract val node: Property<String>

    @get:Input
    @get:Option(option = "timeout", description = "Seconds before the game is killed (default 600).")
    abstract val timeoutSeconds: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "loader-version", description = "Pins the loader version (default: HeadlessMC's latest).")
    abstract val loaderVersion: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "jar", description = "The merged jar (default: the newest build/libs/tailgate-*.jar).")
    abstract val jar: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetsFile: RegularFileProperty

    /** Core's test runtime classpath, which holds `SelfTestFixture`. */
    @get:Classpath
    abstract val fixtureClasspath: ConfigurableFileCollection

    /** Resolves a relative `--jar`. */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Internal
    abstract val libsDir: DirectoryProperty

    /** Caches HeadlessMC, Minecraft and the libraries, and holds each run's game directory. */
    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Inject
    protected abstract val javaToolchains: JavaToolchainService

    @get:Inject
    protected abstract val execOperations: ExecOperations

    init {
        timeoutSeconds.convention("600")
        doNotTrackState("Launches Minecraft; every run is a new test.")
    }

    @TaskAction
    fun run() {
        val node = node.get()
        val mc = node.substringBeforeLast('-', "")
        val loader = node.substringAfterLast('-')
        if (loader !in LOADER_IDS || mc.isEmpty()) {
            throw GradleException("--node must look like <version>-<fabric|forge|neoforge>, got $node")
        }
        val target = Targets.read(targetsFile.get().asFile).find { it.node == node }
            ?: throw GradleException("unknown node $node: targets.toml doesn't list it")
        val seconds = timeoutSeconds.get().toLongOrNull()?.takeIf { it > 0 }
            ?: throw GradleException("--timeout takes a number of seconds, got ${timeoutSeconds.get()}")
        val jar = findJar()

        val major = GAME_JAVAS.first { it >= target.java }
        if (major != target.java) logger.lifecycle("Minecraft $mc asks for Java ${target.java}; using Java $major")
        val java = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(major)) }
            .get().executablePath.asFile.path
        val toolJava = File(System.getProperty("java.home"), "bin/" + if (IS_WINDOWS) "java.exe" else "java").path

        val work = workDir.get().asFile
        val mcdir = work.resolve("mc")
        val version = install(work, mcdir, mc, loader, toolJava, java)
        val required = chain(mcdir, version).firstNotNullOfOrNull {
            (it.obj("javaVersion")?.get("majorVersion") as? JsonPrimitive)?.intOrNull
        } ?: 8
        if (required > major || (required == 8 && major > 8)) {
            throw GradleException("$version asks for Java $required, but runtimeTest picked Java $major; update Target.java")
        }

        val fixtureDir = work.resolve("fixture")
        deleteTree(fixtureDir)
        val fixture = start(
            listOf(toolJava, "-cp", fixtureClasspath.asPath, FIXTURE_MAIN, fixtureDir.path),
            work, work.resolve("fixture.log"),
        )
        val gamedir: File
        val log: File
        val code: Int?
        try {
            val address = waitForFile(fixtureDir.resolve("address.txt"), FIXTURE_TIMEOUT, fixture).trim()
            gamedir = prepareGamedir(work.resolve("run/$node"), jar)
            val jvm = listOf(
                "-Dtailgate.selftest=$address", "-Dtailgate.selftest.exit=true",
                "-Djavax.net.ssl.trustStore=" + fixtureDir.resolve("truststore.jks").path,
                "-Djavax.net.ssl.trustStorePassword=changeit",
            )
            log = gamedir.resolve("game.log")
            code = launch(mcdir, version, gamedir, java, jvm, log, seconds)
        } finally {
            killTree(fixture)
        }

        val report = gamedir.resolve("tailgate-selftest.txt")
        val text = if (report.exists()) report.readText() else ""
        logger.lifecycle("== $node ($version), game exit ${code ?: "none (timed out)"}")
        logger.lifecycle(text.trimEnd().ifEmpty { "no tailgate-selftest.txt written" })
        if ("result=pass" in text) return
        logger.lifecycle("-- last lines of $log")
        if (log.exists()) logger.lifecycle(log.readLines().takeLast(60).joinToString("\n"))
        throw GradleException("the in-game self-test didn't pass for $node")
    }

    private fun findJar(): File {
        jar.orNull?.let { path ->
            val file = rootDirectory.get().asFile.resolve(path)
            if (!file.isFile) throw GradleException("--jar $path: no such file")
            return file
        }
        return libsDir.get().asFile.listFiles { f -> f.name.startsWith("tailgate-") && f.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("no merged jar; run ./gradlew mergeJar first or pass --jar")
    }

    // --- installing -----------------------------------------------------------------------------

    /** Installs the version with HeadlessMC unless present; returns its version id. */
    private fun install(work: File, mcdir: File, mc: String, loader: String, toolJava: String, java: String): String {
        findVersion(mcdir, mc, loader)?.let { return it }
        val hmc = work.resolve("headlessmc-launcher-$HMC_VERSION.jar")
        if (!hmc.exists()) {
            logger.lifecycle("Downloading HeadlessMC $HMC_VERSION")
            if (!fetch(httpClient(), HMC_URL, hmc, null)) throw GradleException("couldn't download HeadlessMC")
        }
        writeText(
            work.resolve("HeadlessMC/config.properties"),
            listOf(
                "hmc.java.versions=${listOf(toolJava, java).map(::fwd).distinct().sorted().joinToString(";")}",
                "hmc.mcdir=${fwd(mcdir.path)}",
                "hmc.gamedir=${fwd(work.resolve("run/hmc").path)}",
                "hmc.offline=true",
                "hmc.assets.dummy=true",
                "hmc.exit.on.failed.command=true",
                "hmc.rethrow.launch.exceptions=true",
            ).joinToString("\n", postfix = "\n"),
        )
        val command = listOf(loader, mc) + (loaderVersion.orNull?.let { listOf("--uid", it) } ?: emptyList())
        logger.lifecycle("Installing ${command.joinToString(" ")} with HeadlessMC")
        try {
            execOperations.exec {
                commandLine(listOf(toolJava, "-jar", hmc.path, "--command") + command)
                workingDir = work
            }
        } catch (e: Exception) {
            throw GradleException("HeadlessMC couldn't install ${command.joinToString(" ")}", e)
        }
        return findVersion(mcdir, mc, loader) ?: throw GradleException("HeadlessMC didn't install a $loader version for $mc")
    }

    private fun findVersion(mcdir: File, mc: String, loader: String): String? {
        val pinned = loaderVersion.orNull
        return mcdir.resolve("versions").listFiles().orEmpty()
            .map { it.resolve(it.name + ".json") }
            .filter { json ->
                if (!json.isFile) return@filter false
                val vid = json.parentFile.name
                val lower = vid.lowercase()
                readJson(json).str("inheritsFrom") == mc && LOADER_IDS.getValue(loader) in lower &&
                    !(loader == "forge" && "neoforge" in lower) && (pinned == null || pinned in vid)
            }
            .maxByOrNull { it.lastModified() }
            ?.parentFile?.name
    }

    // --- fixture --------------------------------------------------------------------------------

    private fun waitForFile(file: File, seconds: Long, process: Process): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!file.exists()) {
            if (!process.isAlive) throw GradleException("the self-test fixture exited with ${process.exitValue()}; see fixture.log")
            if (System.nanoTime() > deadline) throw GradleException("the self-test fixture didn't start within ${seconds}s")
            Thread.sleep(500)
        }
        Thread.sleep(200) // let the fixture finish writing the file
        return file.readText()
    }

    // --- launching ------------------------------------------------------------------------------

    private fun prepareGamedir(gamedir: File, jar: File): File {
        deleteTree(gamedir)
        val mods = gamedir.resolve("mods")
        mods.mkdirs()
        jar.copyTo(mods.resolve(jar.name))
        // Skip the first-launch accessibility screen; keep ticking while unfocused.
        writeText(gamedir.resolve("options.txt"), "onboardAccessibility:false\npauseOnLostFocus:false\n")
        return gamedir
    }

    private fun launch(mcdir: File, version: String, gamedir: File, java: String, extraJvm: List<String>, log: File, seconds: Long): Int? {
        val versions = chain(mcdir, version)
        val base = versions.last()
        val baseId = base.str("id")!!
        val libdir = mcdir.resolve("libraries")
        val natives = gamedir.resolve("natives")
        natives.mkdirs()
        // HeadlessMC downloads libraries when it launches, not when it installs, so fetch them here.
        val cp = mutableListOf<File>()
        val nativeJars = mutableListOf<Pair<File, List<String>>>()
        val downloads = LinkedHashMap<File, Pair<String, String?>>()
        val seen = mutableSetOf<List<Any?>>()
        for (data in versions) {
            for (lib in data.arr("libraries").orEmpty().map { it as JsonObject }) {
                if (!rulesOk(lib.arr("rules"))) continue
                val parts = lib.str("name")!!.split(':')
                val (group, artifact, ver) = parts
                val classifier = parts.getOrNull(3)
                if (classifier != null && !nativeClassifierOk(classifier)) continue
                // 1.14–1.18 list a library's natives as a second entry with the same name.
                if (!seen.add(listOf(group, artifact, classifier, "natives" in lib))) continue
                val libDownloads = lib.obj("downloads")
                // Pre-1.19 versions ship natives as a classifier the launcher extracts.
                val native = lib.obj("natives")?.str(OS_NAME)?.replace("\${arch}", "64")
                if (native != null) {
                    val info = libDownloads?.obj("classifiers")?.obj(native) ?: JsonObject(emptyMap())
                    val path = libraryFile(libdir, lib, info, group, artifact, ver, native, downloads)
                    val excludes = lib.obj("extract")?.arr("exclude").orEmpty().map { (it as JsonPrimitive).content }
                    nativeJars += path to excludes
                }
                val download = libDownloads?.obj("artifact")
                if (!libDownloads.isNullOrEmpty() && download == null) continue
                cp += libraryFile(libdir, lib, download ?: JsonObject(emptyMap()), group, artifact, ver, classifier, downloads)
            }
        }
        // Like Mojang's launcher, run the client jar under the launched version's name: Forge 1.17+
        // keeps it off the module path by that name (-DignoreList=...,${version_name}.jar).
        val baseClient = mcdir.resolve("versions/$baseId/$baseId.jar")
        val client = mcdir.resolve("versions/$version/$version.jar")
        val clientDownload = base.obj("downloads")?.obj("client")
        val clientUrl = clientDownload?.str("url")
        if (!client.exists() && !baseClient.exists() && !clientUrl.isNullOrEmpty()) {
            downloads[baseClient] = clientUrl to clientDownload.str("sha1")
        }

        if (downloads.isNotEmpty()) {
            logger.lifecycle("Downloading ${downloads.size} libraries")
            val http = httpClient()
            val pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS)
            try {
                pool.invokeAll(downloads.map { (file, source) -> Callable { fetch(http, source.first, file, source.second) } })
                    .forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }
        if (!client.exists()) {
            if (!baseClient.exists()) throw GradleException("no client jar for $baseId")
            baseClient.copyTo(client)
        }
        cp += client
        cp.removeAll { file -> !file.exists().also { if (!it) logger.lifecycle("missing library $file") } }
        for ((path, excludes) in nativeJars) {
            if (path.exists()) extractNatives(path, natives, excludes)
        }

        val assets = mcdir.resolve("assets")
        val assetIndex = base.str("assets") ?: "legacy"
        val index = assets.resolve("indexes/$assetIndex.json")
        if (!index.exists()) writeText(index, """{"objects":{}}""")
        val classpath = cp.joinToString(File.pathSeparator) { it.path }
        val subs = mapOf(
            "library_directory" to libdir.path, "classpath_separator" to File.pathSeparator, "version_name" to version,
            "natives_directory" to natives.path, "launcher_name" to "tailgate-runtime-test", "launcher_version" to "1",
            "classpath" to classpath, "auth_player_name" to "TailgateTest", "game_directory" to gamedir.path,
            "assets_root" to assets.path, "game_assets" to assets.path, "assets_index_name" to assetIndex,
            "auth_uuid" to "00000000000000000000000000000001", "auth_access_token" to "0", "auth_session" to "0",
            "user_type" to "legacy", "version_type" to "release", "clientid" to "", "auth_xuid" to "", "user_properties" to "{}",
        )

        fun expand(value: String): String = subs.entries.fold(value) { acc, (k, v) -> acc.replace("\${$k}", v) }

        fun arguments(kind: String): List<String> = versions.reversed().flatMap { data ->
            data.obj("arguments")?.arr(kind).orEmpty().flatMap { a ->
                when {
                    a is JsonPrimitive -> listOf(expand(a.content))
                    a !is JsonObject -> emptyList()
                    !rulesOk(a.arr("rules")) || a.arr("rules").orEmpty().any { hasFeatures(it as JsonObject) } -> emptyList()
                    else -> when (val value = a["value"]) {
                        is JsonArray -> value.map { expand((it as JsonPrimitive).content) }
                        is JsonPrimitive -> listOf(expand(value.content))
                        else -> emptyList()
                    }
                }
            }
        }

        val jvm = arguments("jvm").ifEmpty { listOf("-Djava.library.path=${natives.path}", "-cp", classpath) }
        val game = arguments("game").ifEmpty {
            val legacy = versions.firstNotNullOf { it.str("minecraftArguments") }
            legacy.split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::expand)
        }
        val mainClass = versions.firstNotNullOf { it.str("mainClass") }
        val command = listOf(java, "-Xmx2G") + extraJvm + jvm + mainClass + game
        logger.lifecycle("Launching $version with $java (timeout ${seconds}s)")
        val process = start(command, gamedir, log)
        try {
            if (process.waitFor(seconds, TimeUnit.SECONDS)) return process.exitValue()
            logger.lifecycle("timed out after ${seconds}s; killing the game")
            return null
        } finally {
            killTree(process)
        }
    }

    private fun libraryFile(
        libdir: File, lib: JsonObject, info: JsonObject, group: String, artifact: String, ver: String, classifier: String?,
        downloads: MutableMap<File, Pair<String, String?>>,
    ): File {
        val path = info.str("path")?.takeIf { it.isNotEmpty() }?.let { libdir.resolve(it) }
            ?: libdir.resolve(group.replace('.', '/') + "/$artifact/$ver/$artifact-$ver" + (classifier?.let { "-$it" } ?: "") + ".jar")
        if (path.exists()) return path
        var url = info.str("url")
        if (url == null && lib.obj("downloads").isNullOrEmpty()) {
            // Old version files name only a Maven repository (Mojang's when absent).
            val repo = (lib.str("url") ?: "https://libraries.minecraft.net/").trimEnd('/') + "/"
            url = repo + path.relativeTo(libdir).invariantSeparatorsPath
        }
        if (!url.isNullOrEmpty()) downloads[path] = url to info.str("sha1")
        return path
    }

    /** Downloads [url] to [file], checking [sha1] when given; returns whether it succeeded. */
    private fun fetch(http: HttpClient, url: String, file: File, sha1: String?): Boolean {
        file.parentFile.mkdirs()
        val part = File(file.path + ".part")
        for (attempt in 0 until DOWNLOAD_ATTEMPTS) {
            try {
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).build()
                val status = http.send(request, HttpResponse.BodyHandlers.ofFile(part.toPath())).statusCode()
                if (status == 404) {
                    part.delete()
                    logger.warn("couldn't download $url: HTTP 404")
                    return false
                }
                if (status != 200) throw IOException("HTTP $status")
                // Mojang's version files only publish SHA-1; this catches truncated downloads.
                if (sha1 != null && sha1(part) != sha1) throw IOException("checksum mismatch")
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return true
            } catch (e: IOException) {
                part.delete()
                if (attempt == DOWNLOAD_ATTEMPTS - 1) {
                    logger.warn("couldn't download $url: $e")
                    return false
                }
                Thread.sleep(1000L shl attempt)
            } catch (e: IllegalArgumentException) {
                logger.warn("couldn't download $url: $e")
                return false
            }
        }
        return false
    }

    private fun extractNatives(jar: File, target: File, excludes: List<String>) {
        try {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || (excludes + "META-INF/").any { name.startsWith(it) }) continue
                    val dest = target.resolve(name)
                    if (!dest.exists()) {
                        dest.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
        } catch (e: IOException) {
            throw GradleException("can't extract natives from $jar", e)
        }
    }

    // --- processes and files --------------------------------------------------------------------

    private fun start(command: List<String>, dir: File, log: File): Process =
        try {
            ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(log).start()
        } catch (e: IOException) {
            throw GradleException("can't start ${command.first()}", e)
        }

    /** Kills [process] and everything it started, on every OS. */
    private fun killTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor()
    }

    private fun chain(mcdir: File, version: String): List<JsonObject> =
        generateSequence(readJson(mcdir.resolve("versions/$version/$version.json"))) { data ->
            data.str("inheritsFrom")?.let { readJson(mcdir.resolve("versions/$it/$it.json")) }
        }.toList()

    private fun readJson(file: File): JsonObject =
        try {
            Json.parseToJsonElement(file.readText()) as JsonObject
        } catch (e: Exception) {
            throw GradleException("$file isn't a valid version file", e)
        }

    private fun writeText(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun deleteTree(dir: File) {
        if (dir.exists() && !dir.deleteRecursively()) throw GradleException("can't delete $dir")
    }

    private companion object {
        const val HMC_VERSION = "2.10.0"
        const val HMC_URL = "https://github.com/headlesshq/headlessmc/releases/download/$HMC_VERSION/headlessmc-launcher-$HMC_VERSION.jar"
        const val FIXTURE_MAIN = "io.github.etcherfx.tailgate.core.SelfTestFixture"
        const val FIXTURE_TIMEOUT = 300L
        const val DOWNLOAD_THREADS = 8
        const val DOWNLOAD_ATTEMPTS = 3
        val LOADER_IDS = mapOf("fabric" to "fabric-loader", "forge" to "forge", "neoforge" to "neoforge")

        /** The JDKs the game may run on; Java 8 has no substitute, since old Forge breaks on 9+. */
        val GAME_JAVAS = listOf(8, 17, 21, 25)

        val IS_WINDOWS = System.getProperty("os.name").lowercase().startsWith("windows")
        val OS_NAME = when {
            IS_WINDOWS -> "windows"
            System.getProperty("os.name").lowercase().startsWith("mac") -> "osx"
            else -> "linux"
        }
        val IS_ARM = System.getProperty("os.arch").lowercase() in setOf("arm64", "aarch64")

        fun httpClient(): HttpClient =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(60)).build()

        fun sha1(file: File): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun fwd(path: String): String = path.replace('\\', '/')

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

        fun hasFeatures(rule: JsonObject): Boolean = !rule.obj("features").isNullOrEmpty()

        /** Evaluates a version JSON rule list for this machine; the last matching rule wins. */
        fun rulesOk(rules: JsonArray?): Boolean {
            if (rules.isNullOrEmpty()) return true
            var ok = false
            for (rule in rules.map { it as JsonObject }) {
                if (hasFeatures(rule)) continue
                val os = rule.obj("os")
                val name = os?.str("name")
                if ((name != null && name != OS_NAME) || !archMatches(os?.str("arch"))) continue
                ok = rule.str("action") == "allow"
            }
            return ok
        }

        fun archMatches(arch: String?): Boolean = when (arch?.lowercase()) {
            null -> true
            "arm64", "aarch64" -> IS_ARM
            else -> arch.lowercase() != "x86" && !IS_ARM
        }

        fun nativeClassifierOk(classifier: String): Boolean {
            if ("natives" !in classifier) return true
            val names = mapOf("windows" to listOf("windows"), "linux" to listOf("linux"), "osx" to listOf("osx", "macos")).getValue(OS_NAME)
            if (names.none { it in classifier }) return false
            val arm = "arm64" in classifier || "aarch64" in classifier
            return arm == IS_ARM && !classifier.endsWith("-x86")
        }
    }
}
