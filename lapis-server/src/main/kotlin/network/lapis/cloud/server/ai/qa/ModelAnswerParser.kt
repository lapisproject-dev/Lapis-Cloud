package network.lapis.cloud.server.ai.qa

internal sealed interface ParsedModelAnswer {
    /** [sources] are the 1-based passage ids the model named; may be empty (treated as "no evidence" downstream). */
    data class Cited(
        val summary: String,
        val sources: List<Int>,
    ) : ParsedModelAnswer

    data object NoEvidence : ParsedModelAnswer
}

/**
 * Parses the model's answer: a short summary followed by an optional last line `QUELLEN: 1,3`.
 * Robust against deviation -- a missing or number-free sources line yields [ParsedModelAnswer.Cited]
 * with no sources, and the pipeline treats that exactly like "no evidence". The model's text is
 * never trusted for anything but the summary and the passage ids.
 */
internal object ModelAnswerParser {
    private val SOURCES_LINE = Regex("^\\s*QUELLEN\\s*:\\s*(.*)$", setOf(RegexOption.IGNORE_CASE))
    private val NUMBER = Regex("\\d{1,4}")

    fun parse(raw: String): ParsedModelAnswer {
        val text = raw.trim()
        val compact = text.filter { it.isLetter() || it == '_' }.uppercase()
        if (compact.startsWith(StatuteQaPrompt.NO_EVIDENCE_TOKEN)) return ParsedModelAnswer.NoEvidence

        val lines = text.lines()
        val sourcesIndex = lines.indexOfLast { SOURCES_LINE.matches(it) }
        val summary: String
        val sources: List<Int>
        if (sourcesIndex >= 0) {
            summary = lines.take(sourcesIndex).joinToString(separator = "\n").trim()
            val rest =
                SOURCES_LINE
                    .matchEntire(lines[sourcesIndex])
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            sources =
                NUMBER
                    .findAll(rest)
                    .mapNotNull { it.value.toIntOrNull() }
                    .distinct()
                    .toList()
        } else {
            summary = text
            sources = emptyList()
        }
        return if (summary.isBlank()) ParsedModelAnswer.NoEvidence else ParsedModelAnswer.Cited(summary = summary, sources = sources)
    }
}
