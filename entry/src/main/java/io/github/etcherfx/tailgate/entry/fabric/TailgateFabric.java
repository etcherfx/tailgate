package io.github.etcherfx.tailgate.entry.fabric;

import io.github.etcherfx.tailgate.entry.Dispatcher;
import net.fabricmc.api.ClientModInitializer;

/** Fabric and Quilt client entry point. */
public final class TailgateFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Dispatcher.boot(Dispatcher.FABRIC);
    }
}
