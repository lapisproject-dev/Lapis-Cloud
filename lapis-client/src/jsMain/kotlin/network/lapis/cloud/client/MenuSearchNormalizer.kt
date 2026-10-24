package network.lapis.cloud.client

/**
 * V1.9.203 -- the folding of text for the menu search. Pure and DOM-free (unit-tested in `MenuSearchNormalizerTest`).
 *
 * Rules, applied to the query AND to every searched text alike (so both sides always meet in the same form):
 *  - lower case;
 *  - `ß` becomes `ss`, `ä`/`ö`/`ü` become `a`/`o`/`u`, and the transcriptions `ae`/`oe`/`ue` also become `a`/`o`/`u`
 *    (so "Beitraege" finds "Beiträge" and the other way round);
 *  - every character that is neither a letter nor a digit becomes a single space, runs of spaces collapse, the ends are trimmed.
 *
 * It works character by character on purpose: Kotlin/JS regexes with `\p{L}` need the unicode flag and are not worth the risk.
 * A new `StringBuilder` is created per call, nothing is shared.
 */
object MenuSearchNormalizer {
    /**
     * [text] is the folded string; for the normalized character `i`, `starts[i]` / `ends[i]` are the half-open range of the ORIGINAL text it
     * came from (`ß` -> two characters with the same range, `ae` -> one character spanning both original characters). The map is what lets
     * a highlight found in the folded text be drawn on the original text even though the lengths differ.
     */
    class Mapped(
        val text: String,
        val starts: IntArray,
        val ends: IntArray,
    )

    fun normalize(text: String): String = normalizeWithMap(text).text

    /** The distinct folded words of [query], in the order they were typed. */
    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()

    fun normalizeWithMap(text: String): Mapped {
        val out = StringBuilder()
        val starts = ArrayList<Int>(text.length)
        val ends = ArrayList<Int>(text.length)

        fun append(
            c: Char,
            start: Int,
            end: Int,
        ) {
            if (c == ' ') {
                if (out.isEmpty() || out.last() == ' ') return
            } else if (c == 'e' && out.isNotEmpty() && out.last() in TRANSCRIBED_VOWELS && ends.last() == start) {
                // the transcription "ae"/"oe"/"ue": the 'e' folds into the vowel before it, the vowel now spans both original characters
                ends[ends.size - 1] = end
                return
            }
            out.append(c)
            starts.add(start)
            ends.add(end)
        }

        for (i in text.indices) {
            val c = text[i].lowercaseChar()
            when {
                c == 'ß' -> {
                    append('s', i, i + 1)
                    append('s', i, i + 1)
                }
                c == 'ä' -> append('a', i, i + 1)
                c == 'ö' -> append('o', i, i + 1)
                c == 'ü' -> append('u', i, i + 1)
                c.isLetterOrDigit() -> append(c, i, i + 1)
                else -> append(' ', i, i + 1)
            }
        }
        // a trailing separator is not part of the folded text
        if (out.isNotEmpty() && out.last() == ' ') {
            out.setLength(out.length - 1)
            starts.removeAt(starts.size - 1)
            ends.removeAt(ends.size - 1)
        }
        return Mapped(out.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private val TRANSCRIBED_VOWELS = setOf('a', 'o', 'u')
}
