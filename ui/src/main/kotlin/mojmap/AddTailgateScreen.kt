package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** "Add Tailgate server": Name and Address fields, a status line, and Test / Add / Cancel. */
class AddTailgateScreen(private val parent: Screen) : Screen(Component.literal(AddServerForm.TITLE)) {
    val form = AddServerForm(Tailgate.get())
    private var name = ""
    private var address = ""
    lateinit var nameBox: EditBox
    lateinit var addressBox: EditBox
    lateinit var testButton: Button
    lateinit var addButton: Button

    private val left get() = width / 2 - 100

    override fun init() {
        nameBox = EditBox(font, left, 60, 200, 20, Component.literal(AddServerForm.NAME))
        nameBox.setMaxLength(64)
        nameBox.value = name
        nameBox.setResponder { name = it }
        addressBox = EditBox(font, left, 100, 200, 20, Component.literal(AddServerForm.ADDRESS))
        addressBox.setMaxLength(260)
        addressBox.value = address
        addressBox.setResponder { address = it }
        addRenderableWidget(nameBox)
        addRenderableWidget(addressBox)

        testButton = addRenderableWidget(button(AddServerForm.TEST, left, 154, 98) { test() })
        addButton = addRenderableWidget(button(AddServerForm.ADD, left + 102, 154, 98) { add() })
        addRenderableWidget(button(AddServerForm.CANCEL, left, 178, 200) { onClose() })
        setInitialFocus(addressBox)
    }

    fun test() = form.test(address)

    fun add() {
        val added = form.add(name, address) ?: return
        MultiplayerHooks.addEntry(added.name, added.localAddress)
        MultiplayerHooks.reopen(parent)
    }

    override fun tick() {
        super.tick()
        testButton.active = !form.testing
    }

    override fun onClose() = MultiplayerHooks.setScreen(parent)

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.centeredText(font, title, width / 2, 20, WHITE)
        graphics.text(font, AddServerForm.NAME, left, 50, LABEL)
        graphics.text(font, AddServerForm.ADDRESS, left, 90, LABEL)
        font.split(Component.literal(form.status), 300).forEachIndexed { i, line ->
            graphics.text(font, line, width / 2 - font.width(line) / 2, 126 + i * 10, OPAQUE or form.tone.color)
        }
    }

    private fun button(label: String, x: Int, y: Int, width: Int, onPress: () -> Unit): Button =
        Button.builder(Component.literal(label)) { onPress() }.bounds(x, y, width, 20).build()

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LABEL = 0xFFA0A0A0.toInt()
    }
}
