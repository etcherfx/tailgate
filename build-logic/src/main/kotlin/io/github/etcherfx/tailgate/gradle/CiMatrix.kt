package io.github.etcherfx.tailgate.gradle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

/**
 * Prints the CI matrices as GitHub step outputs, from targets.toml:
 *
 *     ./gradlew ciMatrix [--all-runtime] --output "$GITHUB_OUTPUT" -Ptailgate.nodes=none
 *
 * `build=` splits the UI nodes into groups, one parallel build job each. `runtime=` lists the nodes
 * to launch with `runtimeTest`: by default a smoke set that covers each loader's newest release and
 * every boundary where the UI code changes (the `//? if` cutoffs and the [compile-as] nodes);
 * `--all-runtime` tests every node.
 */
abstract class CiMatrix : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetsFile: RegularFileProperty

    @get:Input
    @get:Option(option = "all-runtime", description = "Runtime-test every node instead of the smoke set.")
    abstract val allRuntime: Property<Boolean>

    @get:Input
    @get:Optional
    @get:Option(option = "output", description = "Appends the lines to this file (e.g. \$GITHUB_OUTPUT) instead of printing them.")
    abstract val output: Property<String>

    init {
        allRuntime.convention(false)
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val targets = Targets.read(targetsFile.get().asFile)
        val releases = LOADERS.associateWith { loader ->
            targets.filter { it.loader == loader }.map { it.mc }.sortedWith { a, b -> compareMc(b, a) }
        }
        val known = targets.map { it.node }.toSet()

        val build = buildJsonArray {
            for ((loader, versions) in releases) {
                versions.chunked(GROUP_SIZE.getValue(loader)).forEachIndexed { index, chunk ->
                    add(buildJsonObject {
                        put("name", if (chunk.size > 1) "$loader ${chunk.last()}-${chunk.first()}" else "$loader ${chunk.first()}")
                        put("id", "$loader-$index")
                        put("nodes", chunk.joinToString(",") { "$it-$loader" })
                    })
                }
            }
        }

        val unknown = SMOKE.filter { it !in known }
        if (unknown.isNotEmpty()) throw GradleException("smoke nodes missing from targets.toml: ${unknown.joinToString()}")
        val runtime = if (allRuntime.get()) {
            targets.sortedWith(compareBy<Target> { it.loader }.thenComparator { a, b -> compareMc(a.mc, b.mc) }).map { it.node }
        } else {
            SMOKE
        }

        val lines = "build=$build\nruntime=${JsonArray(runtime.map(::JsonPrimitive))}\n"
        if (output.isPresent) {
            File(output.get()).appendText(lines)
        } else {
            logger.quiet(lines.trimEnd())
        }
    }

    private companion object {
        val LOADERS = listOf("fabric", "forge", "neoforge")
        val GROUP_SIZE = mapOf("fabric" to 8, "forge" to 5, "neoforge" to 6)

        val SMOKE = listOf(
            // Fabric: 26.2 moved setScreen to Gui; 26.1 render extraction; 1.20.2 background and ServerData.Type;
            // 1.20 GuiGraphics; 1.19.4 focus; 1.19.3 Button.builder; 1.19 Component.literal; 1.17 static draws;
            // 1.16 PoseStack; 1.14.4 first Mojang mappings; 1.14 compiled as 1.14.4.
            "26.3-fabric", "26.1-fabric", "1.21.11-fabric", "1.20.2-fabric", "1.20.1-fabric", "1.19.4-fabric",
            "1.19.3-fabric", "1.19-fabric", "1.18.2-fabric", "1.17.1-fabric", "1.16.5-fabric", "1.15.2-fabric",
            "1.14.4-fabric", "1.14-fabric",
            // Forge: 1.21.6 EventBus 7; 1.19 ScreenEvent.Init; 1.18 InitScreenEvent; 1.16.1 and 1.14.2 compiled as
            // neighbours; 1.13.2 and older use the legacy screens.
            "26.3-forge", "26.1-forge", "1.21.6-forge", "1.21.5-forge", "1.20.1-forge", "1.19.2-forge",
            "1.18.2-forge", "1.17.1-forge", "1.16.5-forge", "1.16.1-forge", "1.14.4-forge", "1.14.2-forge",
            "1.13.2-forge", "1.12.2-forge", "1.10.2-forge", "1.8.9-forge", "1.7.10-forge",
            "26.3-neoforge", "26.1-neoforge", "1.21.1-neoforge", "1.20.2-neoforge",
        )
    }
}
