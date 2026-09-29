package io.github.etcherfx.tailgate.spi

import io.github.etcherfx.tailgate.core.Tailgate

/**
 * Implemented by each version-specific UI build as `io.github.etcherfx.tailgate.ui.<build>.TailgateUi`.
 * The dispatcher instantiates the build matching the running game and calls [install] once.
 */
interface UiBuild {
    /** Hooks the Multiplayer screen. [loader] is `fabric`, `forge` or `neoforge`. */
    fun install(tailgate: Tailgate, loader: String)
}
