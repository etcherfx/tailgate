package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.multiplayer.ServerList
//? if <1.13 {
/*import net.minecraft.client.Minecraft
*///?}

/** Glue between the vanilla Multiplayer screen and Tailgate; called from Forge screen events. */
object MultiplayerHooks {
    /** The button list the last Multiplayer screen was given a Tailgate button in (for the self-test). */
    @Volatile
    var lastButtons: List<GuiButton>? = null
        private set

    /**
     * Runs before the Multiplayer screen loads the server list: rewrites entries whose forwarder
     * moved to a new port, and drops forwarders whose entries were deleted.
     */
    fun beforeInit() {
        val tailgate = Tailgate.getOrNull() ?: return
        try {
            val list = ServerList(Compat.minecraft())
            list.loadServerList()
            val entries = (0 until list.countServers()).map { list.getServerData(it) }
            val rewrites = tailgate.reconcile(entries.map { it.serverIP })
            if (rewrites.isNotEmpty()) {
                for (entry in entries) rewrites[entry.serverIP]?.let { entry.serverIP = it }
                list.saveServerList()
            }
        } catch (e: Exception) {
            tailgate.log.error("Couldn't sync Tailgate servers with the server list", e)
        }
    }

    /** Adds the "Tailgate" button to the Multiplayer screen's top-right corner. */
    fun addButton(screen: GuiScreen, buttons: MutableList<GuiButton>, add: (GuiButton) -> Unit) {
        add(TailgateButton(screen))
        lastButtons = buttons
    }

    fun isMultiplayer(screen: Any?): Boolean = screen is GuiMultiplayer

    fun open(parent: GuiScreen) = Compat.setScreen(AddTailgateScreen(parent))

    /** Adds a vanilla server-list entry pointing at a forwarder. */
    fun addEntry(name: String, address: String) {
        val list = ServerList(Compat.minecraft())
        list.loadServerList()
        list.addServerData(Compat.serverData(name, address))
        list.saveServerList()
    }

    /** Returns to a fresh Multiplayer screen, which reloads the server list. */
    fun reopen(parent: GuiScreen) {
        if (parent !is GuiMultiplayer) return Compat.setScreen(parent)
        val lastScreen = GuiMultiplayer::class.java.declaredFields
            .firstOrNull { it.type == GuiScreen::class.java }
            ?.also { it.isAccessible = true }
            ?.get(parent) as GuiScreen?
        Compat.setScreen(GuiMultiplayer(lastScreen ?: GuiMainMenu()))
    }

    private class TailgateButton(private val screen: GuiScreen) :
        GuiButton(BUTTON_ID, screen.width - 80, 6, 74, 20, AddServerForm.BUTTON) {
        //? if >=1.13 {
        override fun onClick(mouseX: Double, mouseY: Double) = open(screen)
        //?} else {
        /*override fun mousePressed(mc: Minecraft, mouseX: Int, mouseY: Int): Boolean {
            val hit = super.mousePressed(mc, mouseX, mouseY)
            if (hit) open(screen)
            return hit
        }
        *///?}
    }

    /** Outside the ids GuiMultiplayer uses, so its actionPerformed ignores the button. */
    private const val BUTTON_ID = 0x7a11
}
