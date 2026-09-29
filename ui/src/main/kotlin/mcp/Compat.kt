package io.github.etcherfx.tailgate.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.multiplayer.ServerData

/** The Minecraft APIs that changed between 1.7.10 and 1.13.2; everything else is shared. */
object Compat {
    fun minecraft(): Minecraft? =
        //? if >=1.13 {
        Minecraft.getInstance()
        //?} else {
        /*Minecraft.getMinecraft()
        *///?}

    fun setScreen(screen: GuiScreen?) {
        minecraft()!!.displayGuiScreen(screen)
    }

    fun currentScreen(): GuiScreen? = minecraft()?.currentScreen

    fun serverData(name: String, address: String): ServerData =
        //? if >=1.8.8 {
        ServerData(name, address, false)
        //?} else {
        /*ServerData(name, address)
        *///?}
}
