package io.github.etcherfx.tailgate.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import xyz.wagyourtail.unimined.api.UniminedExtension
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig

/**
 * Configures one Stonecutter node: a UI build for a single Minecraft release and loader.
 *
 * The node compiles the shared UI sources against that release with Unimined, remaps the
 * result to the loader's runtime names, and hands the jar to the root merge step.
 */
class TailgateNodePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val target = Targets.read(project.rootProject.file("targets.toml")).singleOrNull { it.node == project.name }
            ?: error("${project.path} is not listed in targets.toml")
        project.extensions.extraProperties["tailgateTarget"] = target

        project.pluginManager.apply("java")
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("xyz.wagyourtail.unimined")

        project.repositories.apply {
            mavenCentral()
            // Scope each loader repository to what it hosts: Gradle asks every repository for every
            // artifact, so one flaky host would otherwise fail unrelated lookups (e.g. Minecraft's
            // own artifacts, which Unimined provides).
            for ((name, url, groups) in REPOSITORIES) maven {
                this.name = name
                setUrl(url)
                content {
                    excludeGroup("net.minecraft")
                    if (groups != null) includeGroupByRegex(groups)
                }
            }
        }

        val unimined = project.extensions.getByType(UniminedExtension::class.java)
        unimined.minecraft { configureMinecraft(target) }

        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        project.dependencies.add("compileOnly", project.project(":core"))
        project.dependencies.add("compileOnly", libs.findLibrary("kotlin-stdlib").get())

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.release.set(8)
            options.compilerArgs.add("-Xlint:-options")
        }
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
            // Kotlin stamps the module name into every class; one name for all nodes lets the merge
            // step recognise nodes that compile to the same bytes and ship them once.
            compilerOptions.moduleName.set("tailgate-ui")
            compilerOptions.freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
        }

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        // UI sources live in one directory per mapping family; compile only this node's family.
        // Mixins exist only for Fabric; Forge and NeoForge hook screens through events.
        val main = sourceSets.getByName("main")
        val kotlinSources = main.extensions.getByName("kotlin") as SourceDirectorySet
        for (family in FAMILIES - target.family) {
            main.java.exclude("$family/**")
            kotlinSources.exclude("$family/**")
        }
        if (target.loader != "fabric") main.java.exclude("**/*Mixin.java")
        val mixinConfig = project.tasks.register("generateMixinConfig", GenerateMixinConfig::class.java) {
            classes.from(sourceSets.getByName("main").output.classesDirs)
            outputDir.set(project.layout.buildDirectory.dir("generated/tailgate-mixins"))
        }
        project.tasks.named("jar", Jar::class.java) {
            from(mixinConfig)
            archiveBaseName.set("tailgate-node")
            archiveVersion.set(target.node)
        }

        // Each remap loads a whole Minecraft classpath; running dozens at once exhausts the heap.
        val remapSlots = project.gradle.sharedServices.registerIfAbsent("tailgateRemapSlots", RemapSlots::class.java) {
            maxParallelUsages.set(REMAP_SLOTS)
        }
        project.tasks.named("remapJar") { usesService(remapSlots) }

        project.tasks.register("collectNode", Copy::class.java) {
            group = "build"
            description = "Copies this node's remapped jar into the root build for merging."
            from(project.tasks.named("remapJar"))
            into(project.rootProject.layout.buildDirectory.dir("nodes"))
            rename { "${target.node}.jar" }
        }
    }

    private fun MinecraftConfig.configureMinecraft(target: Target) {
        version(target.buildMc)
        mappings {
            val m = target.mappings
            when {
                m == "none" -> {}
                m == "mojmap" -> {
                    if (target.loader == "fabric") intermediary()
                    mojmap()
                }
                m.startsWith("mcp:") -> {
                    val (channel, version) = m.removePrefix("mcp:").split(':', limit = 2)
                    searge()
                    mcp(channel, version)
                }
                else -> error("Unknown mappings '$m' for ${target.node}")
            }
        }
        when (target.loader) {
            "fabric" -> fabric { loader(target.buildLoaderVersion) }
            // useToolchains = false runs Forge's setup tools on the build JVM instead of asking for
            // a JDK matching each release (8, 16, 21…), which only CI has.
            "forge" -> minecraftForge {
                loader(target.buildLoaderVersion)
                useToolchains = false
            }
            "neoforge" -> neoForge {
                loader("net.neoforged:neoforge:${target.buildLoaderVersion}:universal")
                useToolchains = false
            }
            else -> error("Unknown loader ${target.loader}")
        }
        // Runtime testing uses scripts/runtime-test.py, not Unimined's dev runs.
        runs { off = true }
        defaultRemapJar = true
    }

    private companion object {
        const val REMAP_SLOTS = 3
        val FAMILIES = setOf("mojmap", "mcp")

        /** Name, URL, and the group regex the repository is limited to (null: anything but Minecraft). */
        val REPOSITORIES = listOf(
            Triple("Fabric", "https://maven.fabricmc.net/", null),
            Triple("Forge", "https://maven.minecraftforge.net/", null),
            Triple("NeoForged", "https://maven.neoforged.net/releases/", "net\\.neoforged(\\..*)?"),
        )
    }
}

/** Limits how many nodes remap at once (see [TailgateNodePlugin]); holds no state. */
abstract class RemapSlots : BuildService<BuildServiceParameters.None>

/** Lists the node's compiled mixin classes in a mixin config so Unimined builds their refmap. */
abstract class GenerateMixinConfig : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val mixins = classes.files.flatMap { root ->
            val dir = root.resolve(MIXIN_PACKAGE.replace('.', '/'))
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".class") && '$' !in it.name }
                .map { it.name.removeSuffix(".class") }
        }.sorted()
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        if (mixins.isEmpty()) return
        out.resolve(MIXIN_CONFIG).writeText(
            buildString {
                append("{\n")
                append("  \"required\": true,\n")
                append("  \"package\": \"$MIXIN_PACKAGE\",\n")
                append("  \"compatibilityLevel\": \"JAVA_8\",\n")
                append("  \"refmap\": \"$MIXIN_REFMAP\",\n")
                append("  \"client\": [").append(mixins.joinToString(", ") { "\"$it\"" }).append("],\n")
                append("  \"injectors\": { \"defaultRequire\": 1 }\n")
                append("}\n")
            },
        )
    }

    companion object {
        const val MIXIN_PACKAGE = "io.github.etcherfx.tailgate.mixin"
        const val MIXIN_CONFIG = "tailgate.mixins.json"
        const val MIXIN_REFMAP = "tailgate.refmap.json"
    }
}
