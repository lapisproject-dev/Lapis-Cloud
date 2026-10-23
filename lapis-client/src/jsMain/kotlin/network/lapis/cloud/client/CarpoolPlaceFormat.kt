package network.lapis.cloud.client

/**
 * V1.9.186 "Mitfahrzentrale": display form of a free-text place of a carpool card -- `06217 MERSEBURG (SAALE), DEUTSCHLAND` reads
 * `Merseburg (Saale)`. Pure and DOM-free; it changes only what a card SHOWS. The stored text, the edit form and the contact-message
 * prefill keep the original, and the original goes into the `title` of the card.
 *
 * Steps (see `CarpoolScreenTest`):
 * 1. Trim; an empty input stays empty.
 * 2. Split at commas outside parentheses; drop a LAST segment that is "Deutschland" / "Germany" (any case, also with a trailing "(DE)").
 * 3. Drop a leading postal code (`D-`/`DE-` plus 4 or 5 digits, or 4 or 5 digits alone) of each segment; a segment that was only a code vanishes.
 * 4. A token that is entirely upper case (at least two letters, no digit) becomes title case, per hyphen/apostrophe part, also inside
 *    parentheses. Tokens with mixed case or with digits stay unchanged.
 * 5. An upper-case particle (am, an, im, in, ob, bei, vor, der, die, den, und, unter, auf) becomes lower case unless it is the first
 *    token of its segment ("AM" alone, as first token, reads "Am").
 * 6. Segments are joined with ", ". Nothing left: the trimmed original.
 *
 * Unicode-aware (Ä, Ö, Ü survive). It cannot restore "ß" (`STRASSE` reads `Strasse`) and never invents data.
 */
internal fun formatCarpoolPlace(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val segments = splitOutsideParentheses(trimmed).map { collapseWhitespace(it) }.filter { it.isNotEmpty() }.toMutableList()
    if (segments.isNotEmpty() && COUNTRY_SEGMENT.matches(segments.last())) segments.removeAt(segments.lastIndex)
    val formatted =
        segments
            .map { stripLeadingPostalCode(it) }
            .filter { it.isNotEmpty() && !POSTAL_CODE_ONLY.matches(it) }
            .map { formatSegment(it) }
    return formatted.joinToString(", ").ifEmpty { trimmed }
}

private val COUNTRY_SEGMENT = Regex("""(?:deutschland|germany)(?:\s*\(de\))?""", RegexOption.IGNORE_CASE)
private val POSTAL_CODE_ONLY = Regex("""(?:(?:D|DE)-)?\d{4,5}""", RegexOption.IGNORE_CASE)
private val LEADING_POSTAL_CODE = Regex("""^(?:(?:D|DE)-)?\d{4,5}\s+""", RegexOption.IGNORE_CASE)
private val PARTICLES = setOf("am", "an", "im", "in", "ob", "bei", "vor", "der", "die", "den", "und", "unter", "auf")
private val PART_DELIMITERS = setOf('-', '\'', '’', '.', '/')

private fun collapseWhitespace(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

private fun stripLeadingPostalCode(segment: String): String = segment.replace(LEADING_POSTAL_CODE, "").trim()

/** Splits at commas that are not inside parentheses; an unbalanced ")" never drives the depth below zero. */
private fun splitOutsideParentheses(text: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (c in text) {
        when {
            c == '(' -> depth++
            c == ')' && depth > 0 -> depth--
        }
        if (c == ',' && depth == 0) {
            parts += current.toString()
            current.clear()
        } else {
            current.append(c)
        }
    }
    parts += current.toString()
    return parts
}

private fun formatSegment(segment: String): String =
    segment
        .split(' ')
        .filter { it.isNotEmpty() }
        .mapIndexed { index, token -> formatToken(token, first = index == 0) }
        .joinToString(" ")

private fun formatToken(
    token: String,
    first: Boolean,
): String {
    val start = token.indexOfFirst { it.isLetterOrDigit() }
    if (start < 0) return token
    val end = token.indexOfLast { it.isLetterOrDigit() } + 1
    val core = token.substring(start, end)
    val wasAllUpper = isAllUpper(core)
    if (wasAllUpper && !first && core.lowercase() in PARTICLES) return token.substring(0, start) + core.lowercase() + token.substring(end)
    return token.substring(0, start) + formatCore(core) + token.substring(end)
}

/** Title-cases every all-upper part of [core] (parts are separated by hyphen, apostrophe, dot or slash, which stay). */
private fun formatCore(core: String): String {
    val out = StringBuilder()
    val part = StringBuilder()

    fun flush() {
        out.append(if (isAllUpper(part.toString())) titleCase(part.toString()) else part.toString())
        part.clear()
    }
    for (c in core) {
        if (c in PART_DELIMITERS) {
            flush()
            out.append(c)
        } else {
            part.append(c)
        }
    }
    flush()
    return out.toString()
}

/** At least two letters, all of them upper case, no digit anywhere. */
private fun isAllUpper(text: String): Boolean {
    if (text.any { it.isDigit() }) return false
    val letters = text.filter { it.isLetter() }
    return letters.length >= 2 && letters.all { it.isUpperCase() }
}

private fun titleCase(text: String): String {
    val lower = text.lowercase()
    val firstLetter = lower.indexOfFirst { it.isLetter() }
    if (firstLetter < 0) return lower
    return lower.substring(0, firstLetter) + lower[firstLetter].uppercaseChar() + lower.substring(firstLetter + 1)
}
