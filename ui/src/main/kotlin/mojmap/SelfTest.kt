package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.SelfTestReport
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ServerList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Drives the real screens once the title screen is up: opens Multiplayer, finds the Tailgate
 * button, opens the Add screen, runs Test against the fixture, and adds the server.
 */
object SelfTest {
    fun start() {
        val address = SelfTestReport.address ?: return
        val thread = Thread({ run(address) }, "Tailgate self-test")
        thread.isDaemon = true
        thread.start()
    }

    private fun run(address: String) {
        val tailgate = Tailgate.get()
        val report = SelfTestReport(tailgate)
        val mc = Minecraft.getInstance()
        try {
            waitFor(120) { onMain { currentScreen() != null } }
            val multiplayer = onMain {
                val screen = JoinMultiplayerScreen(currentScreen() ?: TitleScreen())
                MultiplayerHooks.setScreen(screen)
                screen
            }
            val hasButton = onMain {
                multiplayer.children().any { it is Button && it.message.string == AddServerForm.BUTTON }
            }
            if (hasButton) report.pass("button") else report.fail("button", "no Tailgate button on the Multiplayer screen")

            val add = onMain {
                MultiplayerHooks.open(multiplayer)
                currentScreen() as? AddTailgateScreen
            }
            if (add == null) {
                report.fail("screen", "the Add screen didn't open")
                return
            }
            report.pass("screen")

            onMain {
                add.nameBox.value = "Tailgate self-test"
                add.addressBox.value = address
                add.test()
            }
            waitFor(30) { !add.form.testing }
            if (add.form.tone == AddServerForm.Tone.OK) report.pass("test") else report.fail("test", add.form.status)

            onMain { add.add() }
            val entry = onMain {
                val list = ServerList(mc)
                list.load()
                (0 until list.size()).map { list.get(it) }.firstOrNull { it.name == "Tailgate self-test" }
            }
            val server = tailgate.servers().firstOrNull { it.name == "Tailgate self-test" }
            if (entry != null && server != null && entry.ip == server.localAddress) {
                report.pass("entry")
            } else {
                report.fail("entry", "server-list entry ${entry?.ip} doesn't match forwarder ${server?.localAddress}")
            }
        } catch (e: Exception) {
            report.fail("run", e.toString())
        } finally {
            report.write(mc.gameDirectory)
        }
    }

    private fun currentScreen(): Screen? = Minecraft.getInstance().gui.screen()

    private fun <T> onMain(block: () -> T): T {
        val result = CompletableFuture<T>()
        Minecraft.getInstance().execute {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.get(30, TimeUnit.SECONDS)
    }

    private fun waitFor(seconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw IllegalStateException("timed out after ${seconds}s")
            Thread.sleep(100)
        }
    }
}
