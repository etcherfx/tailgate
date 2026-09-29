package io.github.etcherfx.tailgate.entry

import io.github.etcherfx.tailgate.core.Json
import io.github.etcherfx.tailgate.core.Tailgate
import io.github.etcherfx.tailgate.core.TailgateLog
import io.github.etcherfx.tailgate.spi.UiBuild
import java.io.File
import java.nio.file.Path

/**
 * Shared by every loader entry point: works out which game is running, starts the forwarders,
 * and loads the UI build made for that exact Minecraft release and loader.
 */
object Dispatcher {
    const val FABRIC = "fabric"
    const val FORGE = "forge"
    const val NEOFORGE = "neoforge"
    const val VERSION = TailgateVersion.VERSION

    private const val UI_PACKAGE = "io.github.etcherfx.tailgate.ui"

    private val log: TailgateLog by lazy { Log4jLog.create() }

    private val index: Map<*, *> by lazy {
        val stream = Dispatcher::class.java.getResourceAsStream("/tailgate-targets.json")
            ?: error("tailgate-targets.json is missing from the Tailgate jar")
        stream.use { Json.parse(String(it.readBytes(), Charsets.UTF_8)) } as Map<*, *>
    }

    @Volatile
    private var booted = false

    /** Called from a loader's mod constructor or client initializer. Never throws. */
    @JvmStatic
    fun boot(loader: String) {
        synchronized(this) {
            if (booted) return
            booted = true
        }
        try {
            if (!isClient()) return
            val mc = minecraftVersion(loader)
            val tailgate = Tailgate.boot(gameDir(loader), log)
            val build = buildFor(loader, mc)
            if (build == null) {
                log.warn(
                    "No Tailgate UI for Minecraft $mc on $loader. Saved servers still forward, " +
                        "but there's no in-game screen to add new ones.",
                )
                return
            }
            log.info("Tailgate ${index["version"]} on $loader, Minecraft $mc (UI build $build)")
            val ui = Class.forName("$UI_PACKAGE.$build.TailgateUi", true, Dispatcher::class.java.classLoader)
                .getDeclaredConstructor().newInstance() as UiBuild
            ui.install(tailgate, loader)
        } catch (e: Throwable) {
            log.error("Tailgate failed to start", e)
        }
    }

    /** Mixins for the running Fabric game, relative to the mixin package. */
    @JvmStatic
    fun fabricMixins(): List<String> = try {
        if (!isClient()) {
            emptyList()
        } else {
            val build = buildFor(FABRIC, minecraftVersion(FABRIC))
            (index["mixins"] as? Map<*, *>)?.get(build).let { list -> (list as? List<*>)?.map { it as String } }.orEmpty()
        }
    } catch (e: Throwable) {
        log.error("Tailgate couldn't pick its mixins", e)
        emptyList()
    }

    private fun buildFor(loader: String, mc: String): String? =
        ((index["builds"] as? Map<*, *>)?.get(loader) as? Map<*, *>)?.get(mc) as? String

    /** True in the game client; Tailgate does nothing on dedicated servers. */
    @JvmStatic
    fun isClient(): Boolean {
        fabric { return it.getEnvironmentType().name == "CLIENT" }
        return try {
            Class.forName("net.minecraft.client.main.Main", false, Dispatcher::class.java.classLoader)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    private fun minecraftVersion(loader: String): String {
        if (loader == FABRIC) {
            fabric { loader -> return loader.getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString() }
        }
        val candidates = listOf(
            { call("net.minecraftforge.versions.mcp.MCPVersion", "getMCVersion") },
            { call(call("net.minecraftforge.fml.loading.FMLLoader", "versionInfo"), "mcVersion") },
            { call(call("net.neoforged.fml.loading.FMLLoader", "versionInfo"), "mcVersion") },
            { call(call(call("net.neoforged.fml.loading.FMLLoader", "getCurrent"), "getVersionInfo"), "mcVersion") },
            { call("net.neoforged.neoforge.internal.versions.neoform.NeoFormVersion", "getMCVersion") },
            { field("net.minecraftforge.common.ForgeVersion", "mcVersion") },
            { field("net.minecraftforge.fml.common.Loader", "MC_VERSION") },
            { field("cpw.mods.fml.common.Loader", "MC_VERSION") },
            { versionJson() },
        )
        for (candidate in candidates) {
            val version = try {
                candidate() as? String
            } catch (e: Throwable) {
                null
            }
            if (!version.isNullOrEmpty()) return version
        }
        error("Couldn't tell which Minecraft version is running")
    }

    private fun gameDir(loader: String): File {
        if (loader == FABRIC) fabric { return it.getGameDir().toFile() }
        val candidates = listOf(
            { (field("net.minecraftforge.fml.loading.FMLPaths", "GAMEDIR")?.let { call(it, "get") } as? Path)?.toFile() },
            { (field("net.neoforged.fml.loading.FMLPaths", "GAMEDIR")?.let { call(it, "get") } as? Path)?.toFile() },
            { (call(call("net.minecraftforge.fml.common.Loader", "instance"), "getConfigDir") as? File)?.parentFile },
            { (call(call("cpw.mods.fml.common.Loader", "instance"), "getConfigDir") as? File)?.parentFile },
        )
        for (candidate in candidates) {
            val dir = try {
                candidate()
            } catch (e: Throwable) {
                null
            }
            if (dir != null) return dir.absoluteFile
        }
        return File(".").absoluteFile
    }

    /** Reads `version.json` from the Minecraft jar (1.14+). */
    private fun versionJson(): String? {
        val main = Class.forName("net.minecraft.client.main.Main", false, Dispatcher::class.java.classLoader)
        val stream = main.getResourceAsStream("/version.json") ?: return null
        val json = stream.use { Json.parse(String(it.readBytes(), Charsets.UTF_8)) } as? Map<*, *>
        return json?.get("id") as? String
    }

    private inline fun fabric(block: (net.fabricmc.loader.api.FabricLoader) -> Unit) {
        val present = try {
            Class.forName("net.fabricmc.loader.api.FabricLoader", false, Dispatcher::class.java.classLoader)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        if (present) block(net.fabricmc.loader.api.FabricLoader.getInstance())
    }

    private fun call(target: Any?, method: String): Any? {
        if (target == null) return null
        val type = target as? Class<*> ?: target.javaClass
        val instance = if (target is Class<*>) null else target
        val m = type.getMethod(method)
        m.isAccessible = true
        return m.invoke(instance)
    }

    private fun call(className: String, method: String): Any? = call(loadClass(className), method)

    private fun field(className: String, name: String): Any? {
        val f = loadClass(className).getField(name)
        return f.get(null)
    }

    private fun loadClass(name: String): Class<*> = Class.forName(name, true, Dispatcher::class.java.classLoader)
}
