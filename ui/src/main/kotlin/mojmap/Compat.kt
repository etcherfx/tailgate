package io.github.etcherfx.tailgate.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
//? if >=1.19 {
import net.minecraft.network.chat.Component
//?} else if >=1.16 {
/*import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextComponent
*///?}

/** The Minecraft APIs that changed between 1.14.4 and today; everything else in the UI is shared. */
object Compat {
    //? if >=1.19 {
    fun text(value: String): Component = Component.literal(value)
    //?} else if >=1.16 {
    /*fun text(value: String): Component = TextComponent(value)
    *///?}

    fun button(label: String, x: Int, y: Int, width: Int, height: Int, onPress: () -> Unit): Button =
        //? if >=1.19.3 {
        Button.builder(text(label)) { onPress() }.bounds(x, y, width, height).build()
        //?} else if >=1.16 {
        /*Button(x, y, width, height, text(label)) { onPress() }
        *///?} else {
        /*Button(x, y, width, height, label) { onPress() }
        *///?}

    fun label(button: Button): String =
        //? if >=1.16 {
        button.message.string
        //?} else {
        /*button.message
        *///?}

    fun editBox(font: Font, x: Int, y: Int, width: Int, height: Int, label: String): EditBox =
        //? if >=1.16 {
        EditBox(font, x, y, width, height, text(label))
        //?} else {
        /*EditBox(font, x, y, width, height, label)
        *///?}

    fun setScreen(screen: Screen?) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen)
        //?} else {
        /*Minecraft.getInstance().setScreen(screen)
        *///?}
    }

    fun currentScreen(): Screen? =
        //? if >=26.2 {
        Minecraft.getInstance().gui.screen()
        //?} else {
        /*Minecraft.getInstance().screen
        *///?}

    /** Whether a loading overlay (resource or mod loading) still covers the screen. */
    fun loading(): Boolean =
        //? if >=26.2 {
        Minecraft.getInstance().gui.overlay() != null
        //?} else {
        /*Minecraft.getInstance().overlay != null
        *///?}

    fun serverData(name: String, address: String): ServerData =
        //? if >=1.20.2 {
        ServerData(name, address, ServerData.Type.OTHER)
        //?} else {
        /*ServerData(name, address, false)
        *///?}

    fun addServer(list: ServerList, data: ServerData) {
        //? if >=1.19 {
        list.add(data, false)
        //?} else {
        /*list.add(data)
        *///?}
    }
}
