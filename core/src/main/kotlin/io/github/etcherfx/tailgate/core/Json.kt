package io.github.etcherfx.tailgate.core

/**
 * Minimal JSON codec so the mod doesn't depend on whichever Gson a Minecraft version ships.
 *
 * Values map to `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`, `Boolean` and `null`.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw parser.error("trailing data")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value, null, 0) }.toString()

    fun writePretty(value: Any?): String = StringBuilder().also { write(it, value, "  ", 0) }.append('\n').toString()

    private fun write(out: StringBuilder, value: Any?, indent: String?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                require(!d.isNaN() && !d.isInfinite()) { "JSON can't represent $d" }
                out.append(value.toString())
            }
            is Map<*, *> -> writeContainer(out, '{', '}', value.entries, indent, depth) { entry ->
                writeString(out, entry.key as String)
                out.append(if (indent == null) ":" else ": ")
                write(out, entry.value, indent, depth + 1)
            }
            is Iterable<*> -> writeContainer(out, '[', ']', value, indent, depth) { write(out, it, indent, depth + 1) }
            else -> throw IllegalArgumentException("Can't write ${value.javaClass.name} as JSON")
        }
    }

    private inline fun <T> writeContainer(
        out: StringBuilder,
        open: Char,
        close: Char,
        items: Iterable<T>,
        indent: String?,
        depth: Int,
        writeItem: (T) -> Unit,
    ) {
        out.append(open)
        var first = true
        for (item in items) {
            if (!first) out.append(',')
            first = false
            if (indent != null) newline(out, indent, depth + 1)
            writeItem(item)
        }
        if (!first && indent != null) newline(out, indent, depth)
        out.append(close)
    }

    private fun newline(out: StringBuilder, indent: String, depth: Int) {
        out.append('\n')
        for (i in 0 until depth) out.append(indent)
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0

        fun atEnd() = i >= s.length

        fun error(what: String) = ParseException("Invalid JSON at offset $i: $what")

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun readValue(): Any? {
            if (atEnd()) throw error("unexpected end")
            return when (val c = s[i]) {
                '{' -> nested { readObject() }
                '[' -> nested { readArray() }
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw error("unexpected '$c'")
            }
        }

        private inline fun <T> nested(read: () -> T): T {
            if (++depth > 64) throw error("nested too deeply")
            try {
                return read()
            } finally {
                depth--
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw error("expected $word")
            i += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw error("expected a key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[i] != ':') throw error("expected ':'")
                i++
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw error("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> {
                        i--
                        throw error("expected ',' or '}'")
                    }
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue())
                skipWhitespace()
                if (atEnd()) throw error("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> {
                        i--
                        throw error("expected ',' or ']'")
                    }
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (atEnd()) throw error("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw error("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw error("bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw error("bad unicode escape"))
                                i += 4
                            }
                            else -> throw error("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw error("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: throw error("bad number '$text'")
        }
    }
}
