package io.github.etcherfx.tailgate.core

import java.io.IOException

/**
 * Version-independent state behind the "Add Tailgate server" screen. Each UI build only draws
 * widgets, forwards button presses here, and shows [status].
 */
class AddServerForm(private val tailgate: Tailgate) {
    enum class Tone(val color: Int) {
        INFO(0xA0A0A0),
        BUSY(0xFFFF55),
        OK(0x55FF55),
        ERROR(0xFF5555),
    }

    class Added(val name: String, val localAddress: String)

    @Volatile
    var status: String = "Enter the address your host sent you."
        private set

    @Volatile
    var tone: Tone = Tone.INFO
        private set

    @Volatile
    var testing: Boolean = false
        private set

    /** Bumped on every status change so screens can refresh labels cheaply. */
    @Volatile
    var revision: Int = 0
        private set

    fun test(address: String) {
        val parsed = parse(address) ?: return
        testing = true
        show(Tone.BUSY, "Testing ${parsed}...")
        tailgate.test(parsed) { result ->
            testing = false
            show(if (result.ok) Tone.OK else Tone.ERROR, result.message)
        }
    }

    /** Saves the server and starts its forwarder; returns the server-list entry to add, or null. */
    fun add(name: String, address: String): Added? {
        val parsed = parse(address) ?: return null
        val displayName = name.trim().ifEmpty { parsed.host.substringBefore('.') }
        return try {
            val server = tailgate.add(displayName, parsed)
            Added(server.name, server.localAddress)
        } catch (e: IOException) {
            tailgate.log.error("Couldn't add $parsed", e)
            show(Tone.ERROR, "Couldn't save the server: ${e.message}")
            null
        }
    }

    private fun parse(address: String): FunnelAddress? = try {
        FunnelAddress.parse(address)
    } catch (e: FunnelAddress.InvalidException) {
        show(Tone.ERROR, e.message ?: "Invalid address")
        null
    }

    private fun show(tone: Tone, message: String) {
        this.tone = tone
        this.status = message
        revision++
    }

    companion object {
        const val TITLE = "Add Tailgate server"
        const val BUTTON = "Tailgate"
        const val NAME = "Name"
        const val ADDRESS = "Address"
        const val TEST = "Test"
        const val ADD = "Add"
        const val CANCEL = "Cancel"
    }
}
