package io.github.etcherfx.tailgate.entry.neoforge;

import io.github.etcherfx.tailgate.entry.Dispatcher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** NeoForge 1.20.2+ entry point. */
@Mod(value = "tailgate", dist = Dist.CLIENT)
public final class TailgateNeoForge {
    public TailgateNeoForge() {
        Dispatcher.boot(Dispatcher.NEOFORGE);
    }
}
