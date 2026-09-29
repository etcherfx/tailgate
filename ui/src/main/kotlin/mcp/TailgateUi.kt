package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.Tailgate
import io.github.etcherfx.tailgate.spi.UiBuild
import net.minecraft.client.gui.GuiButton
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.common.MinecraftForge
//? if >=1.13 {
import net.minecraftforge.eventbus.api.SubscribeEvent
//?} else if >=1.8 {
/*import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
*///?} else {
/*import cpw.mods.fml.common.eventhandler.SubscribeEvent
*///?}

/** Entry point the dispatcher loads for this Minecraft version (Forge 1.7.10 to 1.13.2). */
class TailgateUi : UiBuild {
    override fun install(tailgate: Tailgate, loader: String) {
        MinecraftForge.EVENT_BUS.register(Events)
        SelfTest.start()
    }

    /** Forge screen-event listeners; `gui`/`buttonList` are fields before 1.9 and getters after. */
    object Events {
        @SubscribeEvent
        fun onInitPre(event: GuiScreenEvent.InitGuiEvent.Pre) {
            if (MultiplayerHooks.isMultiplayer(event.gui)) MultiplayerHooks.beforeInit()
        }

        @SubscribeEvent
        fun onInitPost(event: GuiScreenEvent.InitGuiEvent.Post) {
            if (!MultiplayerHooks.isMultiplayer(event.gui)) return
            //? if >=1.13 {
            MultiplayerHooks.addButton(event.gui, event.buttonList) { event.addButton(it) }
            //?} else {
            /*// Forge 1.7.10 and 1.8 declare buttonList as a raw List.
            @Suppress("UNCHECKED_CAST")
            val buttons = event.buttonList as MutableList<GuiButton>
            MultiplayerHooks.addButton(event.gui, buttons) { buttons.add(it) }
            *///?}
        }

        /** Runs queued self-test steps on the render thread; fires every frame a screen is open. */
        @SubscribeEvent
        fun onDrawScreen(event: GuiScreenEvent.DrawScreenEvent.Post) = SelfTest.runQueued()
    }
}
