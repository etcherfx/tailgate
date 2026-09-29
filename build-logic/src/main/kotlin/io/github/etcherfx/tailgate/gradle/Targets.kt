package io.github.etcherfx.tailgate.gradle

import org.tomlj.Toml
import java.io.File
import java.io.Serializable

/**
 * A Minecraft release a loader publishes builds for; every target becomes one UI node.
 *
 * The node is normally compiled against [mc] itself. A `[compile-as]` entry in targets.toml
 * compiles it against a neighbouring release instead ([buildMc], [buildLoaderVersion]) when
 * the release itself can't be set up; the jar still serves [mc].
 */
data class Target(
    val mc: String,
    val loader: String,
    val loaderVersion: String,
    /** `none`, `mojmap` or `mcp:<channel>:<version>`, for [buildMc]. */
    val mappings: String,
    val buildMc: String = mc,
    val buildLoaderVersion: String = loaderVersion,
) : Serializable {
    val node: String get() = "$mc-$loader"

    /** Which UI source directory this node compiles: `mojmap` or `mcp`. */
    val family: String
        get() = when {
            mappings == "none" || mappings == "mojmap" -> "mojmap"
            else -> mappings.substringBefore(':')
        }

    /** Package segment the merge step relocates this node's classes into. */
    val packageId: String get() = nodePackageId(node)

    val java: Int
        get() = when {
            buildAtLeast("26.1") -> 25
            buildAtLeast("1.20.5") -> 21
            buildAtLeast("1.18") -> 17
            buildAtLeast("1.17") -> 16
            else -> 8
        }

    fun buildAtLeast(other: String): Boolean = compareMc(buildMc, other) >= 0
}

fun nodePackageId(node: String): String = "v" + node.replace('.', '_').replace('-', '_')

fun compareMc(a: String, b: String): Int {
    val x = a.split('.').map { it.toInt() }
    val y = b.split('.').map { it.toInt() }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

object Targets {
    const val ACTIVE_NODE = "26.3-fabric"

    fun read(file: File): List<Target> {
        val toml = Toml.parse(file.toPath())
        require(!toml.hasErrors()) { "${file.name}: ${toml.errors().joinToString()}" }

        val mcp = toml.getTable("mappings.mcp")
        fun mappings(mc: String, loader: String): String = when {
            compareMc(mc, "26.1") >= 0 -> "none"
            compareMc(mc, "1.14.4") >= 0 -> "mojmap"
            loader == "forge" -> "mcp:" + requireNotNull(mcp?.getString(listOf(mc))) { "no MCP mappings for $mc" }
            else -> error("$mc-$loader has no mappings; compile it as a newer release in [compile-as]")
        }

        // (mc, loader) -> loader version, for every release a loader publishes.
        val releases = mutableListOf<Triple<String, String, String>>()
        val fabricLoader = requireNotNull(toml.getString("fabric.loader")) { "fabric.loader missing" }
        val fabric = requireNotNull(toml.getArray("fabric.versions")) { "fabric.versions missing" }
        for (i in 0 until fabric.size()) releases += Triple(fabric.getString(i), "fabric", fabricLoader)
        for (loader in listOf("forge", "neoforge")) {
            val table = requireNotNull(toml.getTable(loader)) { "[$loader] missing" }
            for (mc in table.keySet()) releases += Triple(mc, loader, table.getString(listOf(mc))!!)
        }
        val byNode = releases.associateBy { (mc, loader) -> "$mc-$loader" }

        val compileAs = toml.getTable("compile-as")
        val targets = releases.map { (mc, loader, loaderVersion) ->
            val node = "$mc-$loader"
            val hostNode = compileAs?.getString(listOf(node))
            if (hostNode == null) {
                Target(mc, loader, loaderVersion, mappings(mc, loader))
            } else {
                val (hostMc, hostLoader, hostVersion) = byNode[hostNode] ?: error("[compile-as] $node: unknown node $hostNode")
                require(hostLoader == loader) { "[compile-as] $node must build with its own loader" }
                require(hostNode !in compileAs.keySet()) { "[compile-as] $node: $hostNode is itself compiled as another node" }
                Target(mc, loader, loaderVersion, mappings(hostMc, loader), buildMc = hostMc, buildLoaderVersion = hostVersion)
            }
        }
        compileAs?.keySet()?.forEach { require(it in byNode) { "[compile-as] names unknown node $it" } }
        return targets.sortedWith(compareBy<Target> { it.loader }.thenComparator { a, b -> compareMc(b.mc, a.mc) })
    }

    /**
     * Resolves `-Ptailgate.nodes`: `all` or a comma-separated node list (`none` never gets here).
     * The active node is always included so Stonecutter can resolve it.
     */
    fun select(all: List<Target>, spec: String?): List<Target> {
        val wanted = when (spec?.trim()) {
            null, "" -> setOf(ACTIVE_NODE)
            "all" -> return all
            else -> spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() + ACTIVE_NODE
        }
        val unknown = wanted - all.map { it.node }.toSet()
        require(unknown.isEmpty()) { "Unknown Tailgate nodes: ${unknown.joinToString()}" }
        return all.filter { it.node in wanted }
    }
}
