package io.github.etcherfx.tailgate.entry.forge;

import cpw.mods.fml.common.Mod;
import io.github.etcherfx.tailgate.entry.Dispatcher;

/** Forge 1.7.10 entry point. */
@Mod(modid = "tailgate", name = "Tailgate", version = Dispatcher.VERSION, acceptableRemoteVersions = "*")
public final class TailgateLegacyForge {
    public TailgateLegacyForge() {
        Dispatcher.boot(Dispatcher.FORGE);
    }
}
