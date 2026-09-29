package io.github.etcherfx.tailgate.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Checks the merged jar loads on every supported loader and Java: Java 8 bytecode only, no
 * unrelocated Kotlin or multi-release entries, no annotation-driven registration, and a UI build
 * for every target in `targets.toml`.
 */
abstract class VerifyJar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetsFile: RegularFileProperty

    /** When false (a partial `-Ptailgate.nodes` build), missing targets are warnings, not errors. */
    @get:Input
    abstract val requireAllTargets: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val entries = HashMap<String, ByteArray>()
        MergeJar.readJar(jar.get().asFile) { name, bytes -> entries[name] = bytes }

        val modClasses = HashMap<String, MutableList<String>>()
        for ((name, bytes) in entries) {
            when {
                name.startsWith("META-INF/versions/") -> problems += "multi-release entry $name"
                name.endsWith("module-info.class") -> problems += "module descriptor $name"
                name.startsWith(Packages.KOTLIN) -> problems += "unrelocated Kotlin class $name"
                name.endsWith(".class") -> {
                    val major = MergeJar.classMajorVersion(bytes)
                    if (major > JAVA_8) problems += "$name is class version $major (Java 8 is $JAVA_8)"
                    for (annotation in annotationsOf(bytes)) {
                        if (annotation in FORBIDDEN) problems += "$name uses ${annotation.trim('L', ';')}"
                        if (annotation in MOD_ANNOTATIONS) modClasses.getOrPut(annotation) { mutableListOf() } += name
                    }
                }
            }
        }
        for ((annotation, classes) in modClasses) {
            if (classes.size != 1) problems += "${classes.size} classes carry $annotation: $classes"
        }

        val index = entries["tailgate-targets.json"]?.let { JsonSlurper().parse(it) as Map<*, *> }
        if (index == null) {
            problems += "tailgate-targets.json is missing"
        } else {
            val builds = index["builds"] as Map<*, *>
            val missing = mutableListOf<String>()
            for (target in Targets.read(targetsFile.get().asFile)) {
                val build = (builds[target.loader] as? Map<*, *>)?.get(target.mc) as? String
                when {
                    build == null -> missing += target.node
                    "${Packages.UI}$build/TailgateUi.class" !in entries -> problems += "${target.node} maps to missing build $build"
                }
            }
            for ((build, mixins) in index["mixins"] as Map<*, *>) {
                for (mixin in mixins as List<*>) {
                    val path = Packages.MIXIN + (mixin as String).replace('.', '/') + ".class"
                    if (path !in entries) problems += "mixin $mixin of $build is missing"
                }
            }
            if (missing.isNotEmpty()) {
                val message = "${missing.size} targets have no UI build: ${missing.joinToString()}"
                if (requireAllTargets.get()) problems += message else logger.warn("Tailgate: $message (partial build)")
            }
        }

        report.get().asFile.writeText(if (problems.isEmpty()) "ok\n" else problems.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException("${jar.get().asFile.name} failed verification:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("Tailgate: ${jar.get().asFile.name} verified (${entries.size} entries)")
    }

    private fun annotationsOf(bytes: ByteArray): Set<String> {
        val found = HashSet<String>()
        val collect = { descriptor: String?, _: Boolean -> descriptor?.let { found += it }; null as AnnotationVisitor? }
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean) = collect(descriptor, visible)

                override fun visitField(access: Int, name: String?, descriptor: String?, signature: String?, value: Any?) =
                    object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String?, visible: Boolean) = collect(descriptor, visible)
                    }

                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?) =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String?, visible: Boolean) = collect(descriptor, visible)
                    }
            },
            ClassReader.SKIP_CODE,
        )
        return found
    }

    private companion object {
        const val JAVA_8 = 52

        val MOD_ANNOTATIONS = setOf(
            "Lnet/minecraftforge/fml/common/Mod;",
            "Lcpw/mods/fml/common/Mod;",
            "Lnet/neoforged/fml/common/Mod;",
        )

        /** Annotations a loader acts on by scanning the jar, which would load other versions' UI classes. */
        val FORBIDDEN = setOf(
            "Lnet/minecraftforge/fml/common/Mod\$EventBusSubscriber;",
            "Lnet/minecraftforge/fml/common/Mod\$EventHandler;",
            "Lnet/minecraftforge/fml/common/Mod\$Instance;",
            "Lnet/minecraftforge/fml/common/SidedProxy;",
            "Lnet/minecraftforge/fml/common/EventBusSubscriber;",
            "Lnet/minecraftforge/registries/ObjectHolder;",
            "Lnet/minecraftforge/fml/common/registry/GameRegistry\$ObjectHolder;",
            "Lcpw/mods/fml/common/Mod\$EventHandler;",
            "Lcpw/mods/fml/common/Mod\$Instance;",
            "Lcpw/mods/fml/common/SidedProxy;",
            "Lcpw/mods/fml/common/registry/GameRegistry\$ObjectHolder;",
            "Lnet/neoforged/fml/common/EventBusSubscriber;",
            "Lnet/neoforged/fml/common/Mod\$EventBusSubscriber;",
            "Lnet/neoforged/neoforge/registries/ObjectHolder;",
        )
    }
}
