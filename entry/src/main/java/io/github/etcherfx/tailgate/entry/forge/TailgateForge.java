package io.github.etcherfx.tailgate.entry.forge;

import io.github.etcherfx.tailgate.entry.Dispatcher;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge 1.8 to 26.3 entry point (and NeoForge 1.20.1, which is still Forge). Forge 1.13+ reads
 * {@code value}; Forge 1.8 to 1.12 reads {@code modid}; each ignores the key it doesn't know.
 */
@Mod(
    value = "tailgate",
    modid = "tailgate",
    name = "Tailgate",
    version = Dispatcher.VERSION,
    acceptableRemoteVersions = "*",
    clientSideOnly = true
)
public final class TailgateForge {
    public TailgateForge() {
        Dispatcher.boot(Dispatcher.FORGE);
    }
}
