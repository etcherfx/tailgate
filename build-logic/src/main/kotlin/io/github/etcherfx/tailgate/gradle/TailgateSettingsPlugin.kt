package io.github.etcherfx.tailgate.gradle

import dev.kikugie.stonecutter.settings.StonecutterSettingsExtension
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Registers the Tailgate modules and one Stonecutter node per selected target.
 *
 * `-Ptailgate.nodes=all` configures every target in `targets.toml`; a comma-separated list
 * configures just those nodes. By default only the active node is configured, because each
 * node downloads and remaps its own Minecraft and loader.
 */
class TailgateSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.pluginManager.apply("dev.kikugie.stonecutter")

        val all = Targets.read(settings.rootDir.resolve("targets.toml"))
        val selected = Targets.select(all, settings.providers.gradleProperty("tailgate.nodes").orNull)

        settings.include("core", "stubs", "entry", "ui")

        settings.extensions.getByType(StonecutterSettingsExtension::class.java).create(":ui") {
            for (target in selected) version(target.node, target.mc)
            vcsVersion.set(Targets.ACTIVE_NODE)
        }
    }
}
