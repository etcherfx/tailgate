package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddServerFormTest {
    private val chars: (String) -> Int = { it.length }

    @Test
    fun `short text stays on one line`() {
        assertEquals(listOf("Testing host..."), AddServerForm.wrap("Testing host...", 40, chars))
    }

    @Test
    fun `long text wraps at word boundaries`() {
        assertEquals(
            listOf("Can't reach", "the host:", "connection", "refused"),
            AddServerForm.wrap("Can't reach the host: connection refused", 11, chars),
        )
    }

    @Test
    fun `a word wider than the line gets its own line`() {
        assertEquals(
            listOf("at", "averyveryverylongword", "end"),
            AddServerForm.wrap("at averyveryverylongword end", 5, chars),
        )
    }

    @Test
    fun `blank text has no lines`() {
        assertEquals(emptyList<String>(), AddServerForm.wrap("  ", 10, chars))
    }
}
