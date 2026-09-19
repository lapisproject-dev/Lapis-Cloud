package network.lapis.cloud.server.ai.qa

import network.lapis.cloud.server.ai.retrieval.RetrievedChunk
import network.lapis.cloud.shared.domain.AiCitationDto

/**
 * Turns the passage ids a model named into citations -- **exclusively from stored records**.
 *
 * Only ids that denote one of the [chunks] actually handed to the model (1-based position) are
 * accepted; everything else (an id the model invented, 0, a negative number) is dropped. The
 * citation's title, version, locator and excerpt are read from the chunk record and never from
 * model text, so a fabricated reference is not merely rejected -- it cannot be expressed.
 */
internal object CitationValidator {
    fun validate(
        sources: List<Int>,
        chunks: List<RetrievedChunk>,
        maxCitations: Int,
        maxExcerptChars: Int,
    ): List<AiCitationDto> =
        sources
            .filter { it in 1..chunks.size }
            .distinct()
            .take(maxCitations)
            .map { id -> toCitation(chunk = chunks[id - 1], maxExcerptChars = maxExcerptChars) }

    private fun toCitation(
        chunk: RetrievedChunk,
        maxExcerptChars: Int,
    ): AiCitationDto {
        val flattened = chunk.text.replace(Regex("\\s+"), " ").trim()
        val excerpt = if (flattened.length > maxExcerptChars) flattened.take(maxExcerptChars).trimEnd() + "…" else flattened
        return AiCitationDto(
            documentTitle = chunk.documentTitle,
            versionNumber = chunk.versionNumber,
            locator = chunk.sectionLabel ?: chunk.pageNumber?.let { "Seite $it" }.orEmpty(),
            excerpt = excerpt,
        )
    }
}
