package io.github.etcherfx.tailgate.gradle

/** Loader metadata for the merged jar. Every loader reads only its own file and ignores the rest. */
object Metadata {
    private const val DESCRIPTION = "A Minecraft mod for joining a friend's Tailscale Funnel server without Tailscale or an account."
    private const val LICENSE = "LGPL-3.0-only"

    fun files(version: String): Map<String, String> {
        val modsToml = """
            |modLoader = "javafml"
            |loaderVersion = "[1,)"
            |license = "$LICENSE"
            |clientSideOnly = true
            |
            |[[mods]]
            |modId = "tailgate"
            |version = "$version"
            |displayName = "Tailgate"
            |description = '''$DESCRIPTION'''
            |displayTest = "IGNORE_ALL_VERSION"
            |""".trimMargin()
        return mapOf(
            // Forge and NeoForge load every mod jar as a resource pack and warn at startup without this.
            // One declaration covers every pack format: 26.x reads min/max_format, 1.20.2-1.21.8 read
            // supported_formats, older versions read pack_format (and don't range-check mod packs).
            "pack.mcmeta" to """
                |{
                |  "pack": {
                |    "description": "Tailgate",
                |    "pack_format": 15,
                |    "supported_formats": [15, 999],
                |    "min_format": 15,
                |    "max_format": 999
                |  }
                |}
                |""".trimMargin(),
            "fabric.mod.json" to """
                |{
                |  "schemaVersion": 1,
                |  "id": "tailgate",
                |  "version": "$version",
                |  "name": "Tailgate",
                |  "description": "$DESCRIPTION",
                |  "license": "$LICENSE",
                |  "environment": "client",
                |  "entrypoints": {
                |    "client": ["io.github.etcherfx.tailgate.entry.fabric.TailgateFabric"]
                |  },
                |  "mixins": ["tailgate.mixins.json"],
                |  "depends": {
                |    "fabricloader": ">=0.7.0",
                |    "minecraft": ">=1.14"
                |  }
                |}
                |""".trimMargin(),
            // Forge 1.13+ and NeoForge 1.20.1–1.20.4.
            "META-INF/mods.toml" to modsToml,
            // NeoForge 1.20.5+.
            "META-INF/neoforge.mods.toml" to modsToml,
            // Forge 1.7.10–1.12.2.
            "mcmod.info" to """
                |[{
                |  "modid": "tailgate",
                |  "name": "Tailgate",
                |  "description": "$DESCRIPTION",
                |  "version": "$version",
                |  "authorList": ["etcherfx"],
                |  "useDependencyInformation": false
                |}]
                |""".trimMargin(),
        )
    }
}
