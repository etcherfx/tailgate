package io.github.etcherfx.tailgate.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Builds the single Tailgate jar: core and entry classes, every node's UI build relocated into
 * its own package, a minimized relocated Kotlin stdlib, and metadata for every loader.
 */
abstract class MergeJar : DefaultTask() {
    /** Jars whose classes ship as-is (apart from Kotlin relocation): core and entry. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseJars: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kotlinStdlib: ConfigurableFileCollection

    /** `<node>.jar` for each built node. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nodeJars: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    /** License texts of bundled third-party code, copied under `META-INF/licenses/`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyLicenses: DirectoryProperty

    @get:Input
    abstract val modVersion: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    private class Node(val target: Target, val classes: Map<String, ByteArray>, val mixins: List<String>, val refmap: Map<*, *>?)

    @TaskAction
    fun merge() {
        val entries = sortedMapOf<String, ByteArray>()
        val kotlinRefs = HashSet<String>()
        val shadow = Relocator(mapOf(Packages.KOTLIN to Packages.SHADOW_KOTLIN))

        for (jar in baseJars.files) {
            readJar(jar) { name, bytes ->
                when {
                    name.startsWith("META-INF/") -> {}
                    name.endsWith(".class") -> {
                        val (relocated, refs) = shadow.relocate(bytes)
                        kotlinRefs += refs
                        entries[name] = relocated
                    }
                    else -> entries[name] = bytes
                }
            }
        }

        val targets = Targets.read(targetsFile.get().asFile)
        val nodes = targets.mapNotNull { target ->
            val jar = nodeJars.get().asFile.resolve("${target.node}.jar")
            if (jar.isFile) readNode(target, jar) else null
        }

        // Nodes whose relocated output is byte-identical share one copy.
        val builds = sortedMapOf<String, MutableMap<String, String>>()
        val mixinsByBuild = sortedMapOf<String, List<String>>()
        val refmaps = mutableListOf<Map<*, *>>()
        val representatives = HashMap<String, String>()
        for (node in nodes) {
            val fingerprint = fingerprint(node)
            val build = representatives.getOrPut(fingerprint) {
                val id = node.target.packageId
                val relocator = nodeRelocator(id)
                for ((name, bytes) in node.classes) {
                    val (relocated, refs) = relocator.relocate(bytes)
                    kotlinRefs += refs
                    entries[relocator.mapName(name.removeSuffix(".class")) + ".class"] = relocated
                }
                if (node.mixins.isNotEmpty()) mixinsByBuild[id] = node.mixins.map { "$id.$it" }
                node.refmap?.let { refmaps += relocateRefmap(it, relocator) }
                id
            }
            builds.getOrPut(node.target.loader) { TreeMap(Comparator<String> { a, b -> compareMc(b, a) }) }[node.target.mc] = build
        }
        logger.lifecycle("Tailgate: ${nodes.size} nodes merged into ${representatives.size} distinct UI builds")

        copyKotlin(entries, kotlinRefs)

        val version = modVersion.get()
        entries["tailgate-targets.json"] = json(
            mapOf("version" to version, "builds" to builds, "mixins" to mixinsByBuild),
        )
        entries["tailgate.mixins.json"] = json(
            mapOf(
                "required" to true,
                "minVersion" to "0.7.11",
                "package" to Packages.MIXIN.removeSuffix("/").replace('/', '.'),
                "plugin" to "io.github.etcherfx.tailgate.entry.fabric.TailgateMixinPlugin",
                "compatibilityLevel" to "JAVA_8",
                "refmap" to "tailgate.refmap.json",
                "client" to emptyList<String>(),
                "injectors" to mapOf("defaultRequire" to 1),
            ),
        )
        entries["tailgate.refmap.json"] = json(mergeRefmaps(refmaps))
        for ((name, text) in Metadata.files(version)) entries[name] = text.toByteArray()
        val license = licenseFile.get().asFile.readBytes()
        entries["META-INF/LICENSE_tailgate"] = license
        // The Kotlin stdlib jar carries no license files, so the shaded classes' licenses come from the repo.
        val licenses = thirdPartyLicenses.get().asFile
        licenses.walk().filter { it.isFile }.forEach {
            entries["META-INF/licenses/" + it.relativeTo(licenses).invariantSeparatorsPath] = it.readBytes()
        }
        entries["META-INF/MANIFEST.MF"] = (
            "Manifest-Version: 1.0\r\n" +
                "Implementation-Title: Tailgate\r\n" +
                "Implementation-Version: $version\r\n\r\n"
            ).toByteArray()

        writeJar(outputJar.get().asFile, entries)
    }

    private fun readNode(target: Target, jar: File): Node {
        val classes = sortedMapOf<String, ByteArray>()
        var mixins = emptyList<String>()
        var refmap: Map<*, *>? = null
        readJar(jar) { name, bytes ->
            when {
                name.endsWith(".class") && (name.startsWith(Packages.UI) || name.startsWith(Packages.MIXIN)) -> classes[name] = bytes
                name == GenerateMixinConfig.MIXIN_CONFIG -> {
                    val config = JsonSlurper().parse(bytes) as Map<*, *>
                    mixins = (config["client"] as List<*>).map { it as String }
                }
                name == GenerateMixinConfig.MIXIN_REFMAP -> refmap = JsonSlurper().parse(bytes) as Map<*, *>
                name.endsWith(".class") -> error("${jar.name} has a class outside the UI packages: $name")
            }
        }
        require(classes.keys.any { it == Packages.UI + "TailgateUi.class" }) { "${jar.name} has no TailgateUi" }
        return Node(target, classes, mixins, refmap)
    }

    private fun nodeRelocator(id: String) = Relocator(
        linkedMapOf(
            Packages.UI to "${Packages.UI}$id/",
            Packages.MIXIN to "${Packages.MIXIN}$id/",
            Packages.KOTLIN to Packages.SHADOW_KOTLIN,
        ),
    )

    private fun fingerprint(node: Node): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val relocator = nodeRelocator("__node__")
        for ((name, bytes) in node.classes) {
            digest.update(name.toByteArray())
            digest.update(relocator.relocate(bytes).first)
        }
        digest.update(node.mixins.joinToString().toByteArray())
        digest.update(JsonOutput.toJson(relocateRefmap(node.refmap ?: emptyMap<Any, Any>(), relocator)).toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Refmaps key their entries by mixin class; move those keys with the classes. */
    private fun relocateRefmap(refmap: Map<*, *>, relocator: Relocator): Map<String, Any?> {
        fun relocateKeys(map: Map<*, *>?) = map.orEmpty().entries.associate { (k, v) -> relocator.mapName(k as String) to v }
        val data = (refmap["data"] as? Map<*, *>).orEmpty().entries.associate { (ns, m) -> ns as String to relocateKeys(m as Map<*, *>) }
        return mapOf("mappings" to relocateKeys(refmap["mappings"] as? Map<*, *>), "data" to data)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeRefmaps(refmaps: List<Map<*, *>>): Map<String, Any> {
        val mappings = sortedMapOf<String, Any?>()
        val data = sortedMapOf<String, MutableMap<String, Any?>>()
        for (refmap in refmaps) {
            mappings.putAll(refmap["mappings"] as Map<String, Any?>)
            for ((ns, m) in refmap["data"] as Map<String, Map<String, Any?>>) data.getOrPut(ns) { sortedMapOf() }.putAll(m)
        }
        return mapOf("mappings" to mappings, "data" to data)
    }

    /** Copies the Kotlin stdlib classes reachable from our code, relocated into the shadow package. */
    private fun copyKotlin(entries: MutableMap<String, ByteArray>, roots: Set<String>) {
        val stdlib = HashMap<String, ByteArray>()
        for (jar in kotlinStdlib.files) {
            readJar(jar) { name, bytes ->
                if (name.startsWith(Packages.KOTLIN) && name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    stdlib[name.removeSuffix(".class")] = bytes
                }
            }
        }
        val relocator = Relocator(mapOf(Packages.KOTLIN to Packages.SHADOW_KOTLIN))
        val queue = ArrayDeque(roots.filter { it in stdlib })
        val done = HashSet<String>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!done.add(name)) continue
            val (relocated, refs) = relocator.relocate(stdlib.getValue(name))
            entries[relocator.mapName(name) + ".class"] = relocated
            for (ref in refs) {
                // Array descriptors show up as names too; reduce them to their element class.
                val element = ref.trimStart('[').removePrefix("L").removeSuffix(";")
                if (element in stdlib && element !in done) queue += element
            }
        }
        logger.lifecycle("Tailgate: shaded ${done.size} of ${stdlib.size} Kotlin stdlib classes")
    }

    private fun json(value: Any): ByteArray = JsonOutput.prettyPrint(JsonOutput.toJson(value)).toByteArray()

    companion object {
        fun readJar(jar: File, visit: (String, ByteArray) -> Unit) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    visit(entry.name, zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        }

        /** Writes a reproducible jar: sorted entries, fixed timestamps, manifest first. */
        fun writeJar(file: File, entries: Map<String, ByteArray>) {
            file.parentFile.mkdirs()
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                val names = entries.keys.sortedWith(compareBy<String> { it != "META-INF/MANIFEST.MF" }.thenBy { it })
                val dirs = HashSet<String>()
                for (name in names) {
                    var slash = name.indexOf('/')
                    while (slash >= 0) {
                        val dir = name.substring(0, slash + 1)
                        if (dirs.add(dir)) zip.putNextEntry(ZipEntry(dir).apply { timeLocal = EPOCH })
                        slash = name.indexOf('/', slash + 1)
                    }
                    zip.putNextEntry(ZipEntry(name).apply { timeLocal = EPOCH })
                    zip.write(entries.getValue(name))
                    zip.closeEntry()
                }
            }
        }

        /** A fixed timestamp so the jar is reproducible. */
        private val EPOCH: LocalDateTime = LocalDateTime.of(1980, 2, 1, 0, 0)

        fun classMajorVersion(bytes: ByteArray): Int = ClassReader(bytes).readUnsignedShort(6)
    }
}
