package io.github.etcherfx.tailgate.ui

import io.github.etcherfx.tailgate.core.AddServerForm
import io.github.etcherfx.tailgate.core.Tailgate
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else if >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics
*///?} else if >=1.17 {
/*import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiComponent
*///?} else if >=1.16 {
/*import com.mojang.blaze3d.vertex.PoseStack
*///?}
//? if >=1.16 {
import net.minecraft.network.chat.Component
//?} else {
/*import net.minecraft.network.chat.TextComponent
*///?}

/** "Add Tailgate server": Name and Address fields, a status line, and Test / Add / Cancel. */
class AddTailgateScreen(private val parent: Screen) : Screen(title()) {
    val form = AddServerForm(Tailgate.get())
    private var name = ""
    private var address = ""
    lateinit var nameBox: EditBox
    lateinit var addressBox: EditBox
    lateinit var testButton: Button
    lateinit var addButton: Button

    private val left get() = width / 2 - 100

    override fun init() {
        nameBox = Compat.editBox(font, left, 60, 200, 20, AddServerForm.NAME)
        nameBox.setMaxLength(64)
        nameBox.value = name
        nameBox.setResponder { name = it }
        addressBox = Compat.editBox(font, left, 100, 200, 20, AddServerForm.ADDRESS)
        addressBox.setMaxLength(260)
        addressBox.value = address
        addressBox.setResponder { address = it }
        add(nameBox)
        add(addressBox)

        testButton = Compat.button(AddServerForm.TEST, left, 154, 98, 20) { test() }
        addButton = Compat.button(AddServerForm.ADD, left + 102, 154, 98, 20) { add() }
        add(testButton)
        add(addButton)
        add(Compat.button(AddServerForm.CANCEL, left, 178, 200, 20) { onClose() })
        //? if >=1.19.4 {
        setInitialFocus(addressBox)
        //?} else {
        /*setFocused(addressBox)
        addressBox.setFocus(true)
        *///?}
    }

    fun test() = form.test(address)

    fun add() {
        val added = form.add(name, address) ?: return
        MultiplayerHooks.addEntry(added.name, added.localAddress)
        MultiplayerHooks.reopen(parent)
    }

    override fun tick() {
        super.tick()
        //? if <1.20.2 {
        /*nameBox.tick()
        addressBox.tick()
        *///?}
        testButton.active = !form.testing
    }

    override fun onClose() = Compat.setScreen(parent)

    //? if >=26.1 {
    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        draw { text, x, y, color -> graphics.text(font, text, x, y, color) }
    }
    //?} else if >=1.20.2 {
    /*override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        draw { text, x, y, color -> graphics.drawString(font, text, x, y, color) }
    }
    *///?} else if >=1.20 {
    /*override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, delta)
        draw { text, x, y, color -> graphics.drawString(font, text, x, y, color) }
    }
    *///?} else if >=1.17 {
    /*override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(poseStack)
        super.render(poseStack, mouseX, mouseY, delta)
        draw { text, x, y, color -> GuiComponent.drawString(poseStack, font, text, x, y, color) }
    }
    *///?} else if >=1.16 {
    /*override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(poseStack)
        super.render(poseStack, mouseX, mouseY, delta)
        // Not drawString: it turned static in 1.16.2, and 1.16.1 runs this build too.
        draw { text, x, y, color -> font.drawShadow(poseStack, text, x.toFloat(), y.toFloat(), color) }
    }
    *///?} else {
    /*override fun render(mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground()
        super.render(mouseX, mouseY, delta)
        draw { text, x, y, color -> drawString(font, text, x, y, color) }
    }
    *///?}

    /** Draws the title, field labels and status with [put], the era's plain-string text call. */
    private fun draw(put: (String, Int, Int, Int) -> Unit) {
        fun centered(text: String, y: Int, color: Int) = put(text, width / 2 - font.width(text) / 2, y, color)
        centered(AddServerForm.TITLE, 20, WHITE)
        put(AddServerForm.NAME, left, 50, LABEL)
        put(AddServerForm.ADDRESS, left, 90, LABEL)
        form.statusLines(300) { font.width(it) }.forEachIndexed { i, line ->
            centered(line, 126 + i * 10, OPAQUE or form.tone.color)
        }
    }

    private fun add(widget: AbstractWidget) {
        //? if >=1.17 {
        addRenderableWidget(widget)
        //?} else {
        /*addButton(widget)
        *///?}
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LABEL = 0xFFA0A0A0.toInt()

        //? if >=1.16 {
        fun title(): Component = Compat.text(AddServerForm.TITLE)
        //?} else {
        /*fun title() = TextComponent(AddServerForm.TITLE)
        *///?}
    }
}
