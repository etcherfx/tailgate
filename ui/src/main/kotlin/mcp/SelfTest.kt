package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.SelfTestReport
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.multiplayer.ServerList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Drives the real screens once the main menu is up: opens Multiplayer, finds the Tailgate
 * button, opens the Add screen, runs Test against the fixture, and adds the server.
 *
 * Steps that touch screens run on the render thread through [runQueued], which Forge's
 * draw-screen event calls every frame (1.7.10 has no public task queue).
 */
object SelfTest {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    fun start() {
        val address = SelfTestReport.address ?: return
        val thread = Thread({ run(address) }, "Tailgate self-test")
        thread.isDaemon = true
        thread.start()
    }

    fun runQueued() {
        while (true) (queue.poll() ?: return).run()
    }

    private fun run(address: String) {
        val tailgate = Tailgate.get()
        val report = SelfTestReport(tailgate)
        try {
            waitFor(300) { Compat.minecraft() != null }
            // Any other screen that stays up (a loader warning, an error) needs a click, so fail instead of hanging.
            waitFor(120, { "stuck on ${Compat.currentScreen()?.javaClass?.name} instead of the main menu" }) {
                Compat.currentScreen() is GuiMainMenu
            }
            val multiplayer = onMain {
                val screen = GuiMultiplayer(Compat.currentScreen() ?: GuiMainMenu())
                Compat.setScreen(screen)
                screen
            }
            val hasButton = onMain {
                MultiplayerHooks.lastButtons.orEmpty().any { it.displayString == AddServerForm.BUTTON } &&
                    Compat.currentScreen() === multiplayer
            }
            if (hasButton) report.pass("button") else report.fail("button", "no Tailgate button on the Multiplayer screen")

            val add = onMain {
                MultiplayerHooks.open(multiplayer)
                Compat.currentScreen() as? AddTailgateScreen
            }
            if (add == null) {
                report.fail("screen", "the Add screen didn't open")
                return
            }
            // Two more trips through the main thread let the Add screen draw at least one frame,
            // so a broken render call fails here instead of in front of a player.
            repeat(2) { onMain { } }
            report.pass("screen")

            onMain {
                add.nameField.text = "Tailgate self-test"
                add.addressField.text = address
                add.test()
            }
            waitFor(30) { !add.form.testing }
            if (add.form.tone == AddServerForm.Tone.OK) report.pass("test") else report.fail("test", add.form.status)

            onMain { add.add() }
            val entry = onMain {
                val list = ServerList(Compat.minecraft())
                list.loadServerList()
                (0 until list.countServers()).map { list.getServerData(it) }.firstOrNull { it.serverName == "Tailgate self-test" }
            }
            val server = tailgate.servers().firstOrNull { it.name == "Tailgate self-test" }
            if (entry != null && server != null && entry.serverIP == server.localAddress) {
                report.pass("entry")
            } else {
                report.fail("entry", "server-list entry ${entry?.serverIP} doesn't match forwarder ${server?.localAddress}")
            }
        } catch (e: Exception) {
            report.fail("run", e.toString())
        } finally {
            report.write()
            if (SelfTestReport.exitWhenDone) {
                val mc = Compat.minecraft()
                if (mc != null) mc.shutdown() else Runtime.getRuntime().halt(1)
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = CompletableFuture<T>()
        queue.add(
            Runnable {
                try {
                    result.complete(block())
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            },
        )
        return result.get(30, TimeUnit.SECONDS)
    }

    private fun waitFor(seconds: Long, describe: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw IllegalStateException("timed out after ${seconds}s ${describe()}".trim())
            Thread.sleep(100)
        }
    }
}
