package io.github.etcherfx.tailgate.gradle

import org.tomlj.Toml
import java.io.File
import java.io.Serializable

/** A Minecraft release a loader publishes builds for; every target becomes one UI node. */
data class Target(
    val mc: String,
    val loader: String,
    val loaderVersion: String,
    /** `none`, `mojmap`, `mcp:<channel>:<version>` or `yarn:<build>`. */
    val mappings: String,
) : Serializable {
    val node: String get() = "$mc-$loader"

    /** Which UI source directory this node compiles: `mojmap`, `mcp` or `yarn`. */
    val family: String
        get() = when {
            mappings == "none" || mappings == "mojmap" -> "mojmap"
            else -> mappings.substringBefore(':')
        }

    /** Package segment the merge step relocates this node's classes into. */
    val packageId: String get() = nodePackageId(node)

    val java: Int
        get() = when {
            mcAtLeast("26.1") -> 25
            mcAtLeast("1.20.5") -> 21
            mcAtLeast("1.18") -> 17
            mcAtLeast("1.17") -> 16
            else -> 8
        }

    fun mcAtLeast(other: String): Boolean = compareMc(mc, other) >= 0
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

        val yarn = toml.getTable("mappings.yarn")
        val mcp = toml.getTable("mappings.mcp")
        fun mappings(mc: String, loader: String): String = when {
            compareMc(mc, "26.1") >= 0 -> "none"
            compareMc(mc, "1.14.4") >= 0 -> "mojmap"
            loader == "fabric" -> "yarn:" + requireNotNull(yarn?.getLong(listOf(mc))) { "no Yarn build for $mc" }
            else -> "mcp:" + requireNotNull(mcp?.getString(listOf(mc))) { "no MCP mappings for $mc" }
        }

        val targets = mutableListOf<Target>()
        val fabricLoader = requireNotNull(toml.getString("fabric.loader")) { "fabric.loader missing" }
        val fabric = requireNotNull(toml.getArray("fabric.versions")) { "fabric.versions missing" }
        for (i in 0 until fabric.size()) {
            val mc = fabric.getString(i)
            targets += Target(mc, "fabric", fabricLoader, mappings(mc, "fabric"))
        }
        for (loader in listOf("forge", "neoforge")) {
            val table = requireNotNull(toml.getTable(loader)) { "[$loader] missing" }
            for (mc in table.keySet()) {
                targets += Target(mc, loader, table.getString(listOf(mc))!!, mappings(mc, loader))
            }
        }
        return targets.sortedWith(compareBy<Target> { it.loader }.thenComparator { a, b -> compareMc(b.mc, a.mc) })
    }

    /**
     * Resolves `-Ptailgate.nodes`: `all`, `none`, or a comma-separated node list.
     * The active node is always included so Stonecutter can resolve it.
     */
    fun select(all: List<Target>, spec: String?): List<Target> {
        val wanted = when (spec?.trim()) {
            null, "" -> setOf(ACTIVE_NODE)
            "all" -> return all
            "none" -> setOf(ACTIVE_NODE)
            else -> spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() + ACTIVE_NODE
        }
        val unknown = wanted - all.map { it.node }.toSet()
        require(unknown.isEmpty()) { "Unknown Tailgate nodes: ${unknown.joinToString()}" }
        return all.filter { it.node in wanted }
    }
}
