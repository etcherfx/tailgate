package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.api.EnvType;

public interface FabricLoader {
    static FabricLoader getInstance() {
        throw new UnsupportedOperationException("stub");
    }

    Optional<ModContainer> getModContainer(String id);

    EnvType getEnvironmentType();

    Path getGameDir();
}
