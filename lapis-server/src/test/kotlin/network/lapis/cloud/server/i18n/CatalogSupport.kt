package network.lapis.cloud.server.i18n

import java.io.File

/** Shared file access and parsing of the client i18n tests (`AllClientMessagesCatalogTest`, `I18nGlossaryConsistencyTest`). */
internal val CATALOG_LANGUAGES = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

internal val CLIENT_KOTLIN_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

internal val CATALOG_DIR: File =
    File("../lapis-client/src/jsMain/resources/modules/i18n")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

internal val GLOSSARY_FILE: File =
    File("../docs/architecture/i18n-glossary.adoc").let { if (it.exists()) it else File("docs/architecture/i18n-glossary.adoc") }

internal fun clientKotlinFiles(): List<File> = CLIENT_KOTLIN_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

/** Kotlin source with comment lines blanked (a KDoc line quoting `tr("...")` is not a call). */
internal fun withoutCommentLines(text: String): String =
    text.lines().joinToString("\n") { line ->
        if (line.trimStart().let {
                it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
            }
        ) {
            ""
        } else {
            line
        }
    }

/** Reads the string literal starting at the opening quote [pos]: the raw (still escaped) content and the position after the closing quote. */
internal fun readStringLiteral(
    text: String,
    pos: Int,
): Pair<String, Int> {
    var i = pos + 1
    val raw = StringBuilder()
    while (i < text.length && text[i] != '"') {
        if (text[i] == '\\' && i + 1 < text.length) {
            raw.append(text[i]).append(text[i + 1])
            i += 2
        } else {
            raw.append(text[i])
            i++
        }
    }
    return raw.toString() to (i + 1)
}

/** Unescapes a Kotlin string literal body, character by character (`\n \t \" \\ \$ \' \uXXXX`). */
internal fun unescapeKotlin(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) {
            out.append(c)
            i++
            continue
        }
        when (val next = raw[i + 1]) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'u' -> {
                out.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                i += 4
            }
            else -> out.append(next)
        }
        i += 2
    }
    return out.toString()
}

/** A `"a" + "b" + OTHER_CONSTANT` chain starting at [start]: the literal/identifier parts and the position after the chain. */
private fun readChain(
    text: String,
    start: Int,
): Pair<List<Pair<Boolean, String>>, Int>? {
    var pos = start
    val parts = mutableListOf<Pair<Boolean, String>>() // (isLiteral, value)
    while (true) {
        while (pos < text.length && text[pos].isWhitespace()) pos++
        if (pos >= text.length) return null
        if (text[pos] == '"') {
            if (text.startsWith("\"\"\"", pos)) return null
            val (raw, next) = readStringLiteral(text = text, pos = pos)
            parts += true to unescapeKotlin(raw)
            pos = next
        } else {
            val match = Regex("[A-Z][A-Z0-9_]*").matchAt(text, pos) ?: return null
            val end = pos + match.value.length
            if (end < text.length && (text[end] == '.' || text[end] == '(')) return null
            parts += false to match.value
            pos = end
        }
        var look = pos
        while (look < text.length && text[look].isWhitespace()) look++
        if (look < text.length && text[look] == '+') {
            pos = look + 1
            continue
        }
        return parts to pos
    }
}

/** `val NAME = "..." + OTHER + "..."` constants of the client, evaluated (constants that refer to constants are resolved). */
internal fun clientStringConstants(): Map<String, String> {
    val expressions = mutableMapOf<String, List<Pair<Boolean, String>>>()
    val declaration = Regex("""\bval\s+([A-Z][A-Z0-9_]*)\s*(?::\s*String)?\s*=\s*(?=["A-Z])""")
    clientKotlinFiles().forEach { file ->
        val text = withoutCommentLines(file.readText())
        declaration.findAll(text).forEach { match ->
            readChain(text = text, start = match.range.last + 1)?.first?.let { expressions[match.groupValues[1]] = it }
        }
    }
    val values = mutableMapOf<String, String>()

    fun evaluate(
        name: String,
        seen: Set<String>,
    ): String? {
        values[name]?.let { return it }
        val parts = expressions[name] ?: return null
        if (name in seen) return null
        val builder = StringBuilder()
        for ((isLiteral, value) in parts) {
            builder.append(if (isLiteral) value else evaluate(value, seen + name) ?: return null)
        }
        return builder.toString().also { values[name] = it }
    }
    expressions.keys.forEach { evaluate(it, emptySet()) }
    return values
}

internal class ExtractedMessage(
    val file: String,
    val line: Int,
    val msgid: String,
)

/**
 * Every `tr("...")`/`gettext("...")` of [text] whose first argument is a string literal (with `+` concatenation) or a named constant
 * ([constants]); a comment line never counts.
 */
internal fun extractMessages(
    fileName: String,
    text: String,
    constants: Map<String, String>,
): List<ExtractedMessage> {
    val code = withoutCommentLines(text)
    val result = mutableListOf<ExtractedMessage>()
    val call = Regex("""\b(?:tr|gettext)\(""")
    for (match in call.findAll(code)) {
        var pos = match.range.last + 1
        while (pos < code.length && code[pos].isWhitespace()) pos++
        if (pos >= code.length) continue
        val line = code.substring(0, pos).count { it == '\n' } + 1
        if (code[pos] == '"') {
            val chain = readChain(text = code, start = pos)?.first ?: continue
            val builder = StringBuilder()
            var resolvable = true
            for ((isLiteral, value) in chain) {
                if (isLiteral) {
                    builder.append(value)
                } else {
                    val resolved = constants[value]
                    if (resolved == null) resolvable = false else builder.append(resolved)
                }
            }
            if (resolvable) result += ExtractedMessage(file = fileName, line = line, msgid = builder.toString())
        } else if (code[pos].isUpperCase()) {
            val name = Regex("[A-Z][A-Z0-9_]*").matchAt(code, pos)?.value ?: continue
            val after = code.getOrNull(pos + name.length)
            if (after == ',' ||
                after == ')' ||
                after == ' '
            ) {
                constants[name]?.let { result += ExtractedMessage(file = fileName, line = line, msgid = it) }
            }
        }
    }
    return result
}

/** msgid -> msgstr of a `.po`/`.pot` file (wrapped entries joined, escapes resolved; the header entry has the empty msgid). */
internal fun parseCatalog(file: File): Map<String, String> {
    val entries = mutableMapOf<String, String>()
    var id: StringBuilder? = null
    var str: StringBuilder? = null
    var mode = 0

    fun flush() {
        val currentId = id
        val currentStr = str
        if (currentId != null && currentStr != null) entries[unescapeKotlin(currentId.toString())] = unescapeKotlin(currentStr.toString())
    }
    file.forEachLine { rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith("msgid \"") -> {
                flush()
                id = StringBuilder(line.removePrefix("msgid \"").removeSuffix("\""))
                str = null
                mode = 1
            }
            line.startsWith("msgstr \"") -> {
                str = StringBuilder(line.removePrefix("msgstr \"").removeSuffix("\""))
                mode = 2
            }
            line.startsWith("\"") && line.endsWith("\"") && line.length >= 2 -> {
                val body = line.substring(1, line.length - 1)
                if (mode == 1) {
                    id?.append(body)
                } else if (mode == 2) {
                    str?.append(body)
                }
            }
            else -> mode = 0
        }
    }
    flush()
    return entries
}
