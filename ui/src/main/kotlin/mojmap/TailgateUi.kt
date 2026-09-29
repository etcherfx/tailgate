package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.Tailgate
import io.github.etcherfx.tailgate.spi.UiBuild
//? if neoforge {
/*import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
*///?} else if forge {
/*import net.minecraftforge.client.event.ScreenEvent
import java.util.function.Consumer
*///?}

/** Entry point the dispatcher loads for this Minecraft version and loader. */
class TailgateUi : UiBuild {
    override fun install(tailgate: Tailgate, loader: String) {
        //? if neoforge {
        /*NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Pre::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) MultiplayerHooks.beforeInit()
        }
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post::class.java) { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) event.addListener(MultiplayerHooks.button(event.screen))
        }
        *///?} else if forge {
        /*ScreenEvent.Init.Pre.BUS.addListener(Consumer { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) MultiplayerHooks.beforeInit()
        })
        ScreenEvent.Init.Post.BUS.addListener(Consumer { event ->
            if (MultiplayerHooks.isMultiplayer(event.screen)) event.addListener(MultiplayerHooks.button(event.screen))
        })
        *///?}
        // On Fabric, JoinMultiplayerScreenMixin calls the same hooks.
        SelfTest.start()
    }
}
