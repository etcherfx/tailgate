package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ServerList

/** Glue between the vanilla Multiplayer screen and Tailgate; called from mixins or loader events. */
object MultiplayerHooks {
    /**
     * Runs before the Multiplayer screen loads the server list: rewrites entries whose forwarder
     * moved to a new port, and drops forwarders whose entries were deleted.
     */
    @JvmStatic
    fun beforeInit() {
        val tailgate = Tailgate.getOrNull() ?: return
        try {
            val list = ServerList(Minecraft.getInstance())
            list.load()
            val entries = (0 until list.size()).map { list.get(it) }
            val rewrites = tailgate.reconcile(entries.map { it.ip })
            if (rewrites.isNotEmpty()) {
                for (entry in entries) rewrites[entry.ip]?.let { entry.ip = it }
                list.save()
            }
        } catch (e: Exception) {
            tailgate.log.error("Couldn't sync Tailgate servers with the server list", e)
        }
    }

    /** The "Tailgate" button for the Multiplayer screen's top-right corner. */
    @JvmStatic
    fun button(screen: Screen): Button =
        Compat.button(AddServerForm.BUTTON, screen.width - 80, 6, 74, 20) { open(screen) }

    @JvmStatic
    fun isMultiplayer(screen: Any?): Boolean = screen is JoinMultiplayerScreen

    fun open(parent: Screen) {
        setScreen(AddTailgateScreen(parent))
    }

    /** Adds a vanilla server-list entry pointing at a forwarder. */
    fun addEntry(name: String, address: String) {
        val list = ServerList(Minecraft.getInstance())
        list.load()
        Compat.addServer(list, Compat.serverData(name, address))
        list.save()
    }

    /**
     * Returns to a fresh Multiplayer screen so it reloads the server list; older versions only
     * load it once per screen instance.
     */
    fun reopen(parent: Screen) {
        if (parent !is JoinMultiplayerScreen) return setScreen(parent)
        val lastScreen = JoinMultiplayerScreen::class.java.declaredFields
            .firstOrNull { it.type == Screen::class.java }
            ?.also { it.isAccessible = true }
            ?.get(parent) as Screen?
        setScreen(JoinMultiplayerScreen(lastScreen ?: TitleScreen()))
    }

    fun setScreen(screen: Screen?) = Compat.setScreen(screen)
}
