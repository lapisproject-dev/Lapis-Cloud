package network.lapis.cloud.server.theme

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Minimal JSON tree produced by [StrictJson]; numbers keep their literal text so `1` and `1.0` stay distinguishable. */
internal sealed interface JNode {
    data class Obj(
        val members: LinkedHashMap<String, JNode>,
    ) : JNode

    data class Arr(
        val items: List<JNode>,
    ) : JNode

    data class Str(
        val value: String,
    ) : JNode

    data class Num(
        val raw: String,
    ) : JNode

    data class Bool(
        val value: Boolean,
    ) : JNode

    data object Null : JNode
}

/**
 * RFC 8259 reader that is stricter than `kotlinx.serialization`'s `parseToJsonElement`: it rejects duplicate object keys (the
 * library silently keeps the last one), comments, trailing commas, text after the root value, and bounds depth, string length and
 * member count. Never throws: every failure is a [Result.Err] with a JSON path.
 */
internal object StrictJson {
    sealed interface Result {
        data class Ok(
            val value: JNode,
        ) : Result

        data class Err(
            val code: ThemeErrorCode,
            val path: String,
            val message: String,
        ) : Result
    }

    /** Strict UTF-8 decode (BOM stripped); `null` on malformed input. */
    fun decodeUtf8(bytes: ByteArray): String? =
        try {
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (text.isNotEmpty() && text[0].code == 0xFEFF) text.substring(1) else text
        } catch (_: CharacterCodingException) {
            null
        }

    fun parse(
        text: String,
        maxDepth: Int = 8,
        maxStringLength: Int = 1024,
        maxMembers: Int = 64,
    ): Result =
        try {
            val reader = Reader(s = text, maxDepth = maxDepth, maxStringLength = maxStringLength, maxMembers = maxMembers)
            val value = reader.readValue(path = "$", depth = 1)
            reader.skipWhitespace()
            if (!reader.atEnd()) reader.fail(message = "unexpected content after the root value", path = "$")
            Result.Ok(value)
        } catch (e: JsonFailure) {
            Result.Err(code = ThemeErrorCode.THEME_JSON_MALFORMED, path = e.path, message = e.message ?: "malformed JSON")
        }

    private class JsonFailure(
        val path: String,
        message: String,
    ) : RuntimeException(message, null, false, false)

    private class Reader(
        private val s: String,
        private val maxDepth: Int,
        private val maxStringLength: Int,
        private val maxMembers: Int,
    ) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun fail(
            message: String,
            path: String,
        ): Nothing = throw JsonFailure(path = path, message = message)

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun readValue(
            path: String,
            depth: Int,
        ): JNode {
            if (depth > maxDepth) fail(message = "nesting deeper than $maxDepth", path = path)
            skipWhitespace()
            if (atEnd()) fail(message = "unexpected end of input", path = path)
            return when (val c = s[i]) {
                '{' -> readObject(path = path, depth = depth)
                '[' -> readArray(path = path, depth = depth)
                '"' -> JNode.Str(readString(path))
                't' -> literal(word = "true", path = path, node = JNode.Bool(true))
                'f' -> literal(word = "false", path = path, node = JNode.Bool(false))
                'n' -> literal(word = "null", path = path, node = JNode.Null)
                else -> if (c == '-' || c in '0'..'9') readNumber(path) else fail(message = "unexpected character", path = path)
            }
        }

        private fun literal(
            word: String,
            path: String,
            node: JNode,
        ): JNode {
            if (!s.startsWith(word, i)) fail(message = "invalid literal", path = path)
            i += word.length
            return node
        }

        private fun readNumber(path: String): JNode {
            val start = i
            if (s[i] == '-') i++
            if (atEnd()) fail(message = "invalid number", path = path)
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (!atEnd() && s[i] in '0'..'9') i++
            } else {
                fail(message = "invalid number", path = path)
            }
            if (!atEnd() && s[i] == '.') {
                i++
                val digitsStart = i
                while (!atEnd() && s[i] in '0'..'9') i++
                if (i == digitsStart) fail(message = "invalid number", path = path)
            }
            if (!atEnd() && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!atEnd() && (s[i] == '+' || s[i] == '-')) i++
                val digitsStart = i
                while (!atEnd() && s[i] in '0'..'9') i++
                if (i == digitsStart) fail(message = "invalid number", path = path)
            }
            if (i - start > 32) fail(message = "number literal too long", path = path)
            return JNode.Num(s.substring(start, i))
        }

        private fun readString(path: String): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail(message = "unterminated string", path = path)
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail(message = "unterminated escape", path = path)
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail(message = "invalid unicode escape", path = path)
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail(
                                        message = "invalid unicode escape",
                                        path = path,
                                    )
                                }
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail(message = "invalid escape", path = path)
                        }
                    }
                    c < ' ' -> fail(message = "control character in string", path = path)
                    else -> sb.append(c)
                }
                if (sb.length > maxStringLength) fail(message = "string longer than $maxStringLength characters", path = path)
            }
        }

        private fun readObject(
            path: String,
            depth: Int,
        ): JNode {
            i++ // {
            val members = LinkedHashMap<String, JNode>()
            skipWhitespace()
            if (!atEnd() && s[i] == '}') {
                i++
                return JNode.Obj(members)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') fail(message = "expected a string key", path = path)
                val key = readString(path)
                val childPath = "$path.${displayKey(key)}"
                if (members.containsKey(key)) fail(message = "duplicate key '${displayKey(key)}'", path = childPath)
                if (members.size >= maxMembers) fail(message = "more than $maxMembers members in one object", path = path)
                skipWhitespace()
                if (atEnd() || s[i] != ':') fail(message = "expected ':'", path = childPath)
                i++
                members[key] = readValue(path = childPath, depth = depth + 1)
                skipWhitespace()
                if (atEnd()) fail(message = "unterminated object", path = path)
                when (s[i++]) {
                    ',' -> Unit
                    '}' -> return JNode.Obj(members)
                    else -> fail(message = "expected ',' or '}'", path = path)
                }
            }
        }

        private fun readArray(
            path: String,
            depth: Int,
        ): JNode {
            i++ // [
            val items = ArrayList<JNode>()
            skipWhitespace()
            if (!atEnd() && s[i] == ']') {
                i++
                return JNode.Arr(items)
            }
            while (true) {
                if (items.size >= maxMembers) fail(message = "more than $maxMembers items in one array", path = path)
                items += readValue(path = "$path[${items.size}]", depth = depth + 1)
                skipWhitespace()
                if (atEnd()) fail(message = "unterminated array", path = path)
                when (s[i++]) {
                    ',' -> Unit
                    ']' -> return JNode.Arr(items)
                    else -> fail(message = "expected ',' or ']'", path = path)
                }
            }
        }

        /** Keys end up in paths/messages: keep them short and printable. */
        private fun displayKey(key: String): String =
            key
                .take(64)
                .map { if (it < ' ' || it == '\u007F') '?' else it }
                .joinToString("")
    }
}
