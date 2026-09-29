package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.Tailgate
import io.github.etcherfx.tailgate.spi.UiBuild
//? if neoforge {
/*import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
*///?} else if forge && >=1.21.6 {
/*import net.minecraftforge.client.event.ScreenEvent
import java.util.function.Consumer
*///?} else if forge && >=1.18 {
/*import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.EventPriority
*///?} else if forge {
/*import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.EventPriority
*///?}

/** Entry point the dispatcher loads for this Minecraft version and loader. */
class TailgateUi : UiBuild {
    override fun install(tailgate: Tailgate, loader: String) {
        // Forge and NeoForge hook the Multiplayer screen through screen-init events; on Fabric,
        // JoinMultiplayerScreenMixin calls the same hooks.
        //? if neoforge {
        /*NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Pre::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) MultiplayerHooks.beforeInit()
        }
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) event.addListener(MultiplayerHooks.button(event.screen))
        }
        *///?} else if forge && >=1.21.6 {
        /*ScreenEvent.Init.Pre.BUS.addListener(Consumer { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) MultiplayerHooks.beforeInit()
        })
        ScreenEvent.Init.Post.BUS.addListener(Consumer { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) event.addListener(MultiplayerHooks.button(event.screen))
        })
        *///?} else if forge && >=1.19 {
        /*MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Init.Pre::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) MultiplayerHooks.beforeInit()
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Init.Post::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) event.addListener(MultiplayerHooks.button(event.screen))
        }
        *///?} else if forge && >=1.18 {
        /*MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.InitScreenEvent.Pre::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) MultiplayerHooks.beforeInit()
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.InitScreenEvent.Post::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) event.addListener(MultiplayerHooks.button(event.screen))
        }
        *///?} else if forge {
        /*MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, GuiScreenEvent.InitGuiEvent.Pre::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.gui)) MultiplayerHooks.beforeInit()
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, GuiScreenEvent.InitGuiEvent.Post::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.gui)) event.addWidget(MultiplayerHooks.button(event.gui))
        }
        *///?}
        SelfTest.start()
    }
}
