package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiTextField
//? if <1.13 {
/*import org.lwjgl.input.Keyboard
*///?}

/** "Add Tailgate server": Name and Address fields, a status line, and Test / Add / Cancel. */
class AddTailgateScreen(private val parent: GuiScreen) : GuiScreen() {
    val form = AddServerForm(Tailgate.get())
    lateinit var nameField: GuiTextField
    lateinit var addressField: GuiTextField
    private lateinit var testButton: GuiButton

    private val left get() = width / 2 - 100

    private val font: FontRenderer
        //? if >=1.11 {
        get() = fontRenderer
        //?} else {
        /*get() = fontRendererObj
        *///?}

    override fun initGui() {
        //? if <1.13 {
        /*Keyboard.enableRepeatEvents(true)
        *///?}
        val name = if (::nameField.isInitialized) nameField.text else ""
        val address = if (::addressField.isInitialized) addressField.text else ""
        nameField = textField(0, 60, 64, name)
        addressField = textField(1, 100, 260, address)
        addressField.isFocused = true

        testButton = button(TEST, left, 154, 98, AddServerForm.TEST)
        button(ADD, left + 102, 154, 98, AddServerForm.ADD)
        button(CANCEL, left, 178, 200, AddServerForm.CANCEL)
        //? if >=1.13 {
        children.add(nameField)
        children.add(addressField)
        focusOn(addressField)
        //?}
    }

    fun test() = form.test(addressField.text)

    fun add() {
        val added = form.add(nameField.text, addressField.text) ?: return
        MultiplayerHooks.addEntry(added.name, added.localAddress)
        MultiplayerHooks.reopen(parent)
    }

    private fun back() = Compat.setScreen(parent)

    private fun pressed(id: Int) {
        when (id) {
            TEST -> test()
            ADD -> add()
            CANCEL -> back()
        }
    }

    //? if >=1.13 {
    override fun tick() {
        nameField.tick()
        addressField.tick()
        testButton.enabled = !form.testing
    }

    override fun close() = back()

    override fun render(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawDefaultBackground()
        draw()
        nameField.drawTextField(mouseX, mouseY, partialTicks)
        addressField.drawTextField(mouseX, mouseY, partialTicks)
        super.render(mouseX, mouseY, partialTicks)
    }
    //?} else {
    /*override fun updateScreen() {
        nameField.updateCursorCounter()
        addressField.updateCursorCounter()
        testButton.enabled = !form.testing
    }

    override fun onGuiClosed() = Keyboard.enableRepeatEvents(false)

    override fun actionPerformed(button: GuiButton) = pressed(button.id)

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        when (keyCode) {
            Keyboard.KEY_ESCAPE -> back()
            Keyboard.KEY_TAB -> {
                val toName = addressField.isFocused
                nameField.isFocused = toName
                addressField.isFocused = !toName
            }
            Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> add()
            else -> {
                nameField.textboxKeyTyped(typedChar, keyCode)
                addressField.textboxKeyTyped(typedChar, keyCode)
            }
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)
        nameField.mouseClicked(mouseX, mouseY, mouseButton)
        addressField.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawDefaultBackground()
        draw()
        nameField.drawTextBox()
        addressField.drawTextBox()
        super.drawScreen(mouseX, mouseY, partialTicks)
    }
    *///?}

    /** Draws the title, field labels and status. */
    private fun draw() {
        drawCenteredString(font, AddServerForm.TITLE, width / 2, 20, WHITE)
        drawString(font, AddServerForm.NAME, left, 50, LABEL)
        drawString(font, AddServerForm.ADDRESS, left, 90, LABEL)
        form.statusLines(300) { font.getStringWidth(it) }.forEachIndexed { i, line ->
            drawCenteredString(font, line, width / 2, 126 + i * 10, form.tone.color)
        }
    }

    private fun textField(id: Int, y: Int, maxLength: Int, text: String): GuiTextField {
        //? if >=1.8 {
        val field = GuiTextField(id, font, left, y, 200, 20)
        //?} else {
        /*val field = GuiTextField(font, left, y, 200, 20)
        *///?}
        field.maxStringLength = maxLength
        field.text = text
        return field
    }

    private fun button(id: Int, x: Int, y: Int, width: Int, label: String): GuiButton {
        //? if >=1.13 {
        val button = object : GuiButton(id, x, y, width, 20, label) {
            override fun onClick(mouseX: Double, mouseY: Double) = pressed(id)
        }
        addButton(button)
        //?} else {
        /*val button = GuiButton(id, x, y, width, 20, label)
        buttonList.add(button)
        *///?}
        return button
    }

    private companion object {
        const val TEST = 1
        const val ADD = 2
        const val CANCEL = 3
        const val WHITE = 0xFFFFFF
        const val LABEL = 0xA0A0A0
    }
}
