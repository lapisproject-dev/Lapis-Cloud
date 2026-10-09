package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.82 "Veranstaltungs-Feed, Archiv und Import" -- outcome of one entry of an import payload in the dry run.
 * [SKIP_SLUG_EXISTS] entries are never updated, only skipped; any [ERROR] entry blocks the whole commit.
 */
@Serializable
enum class EventImportRowStatus { CREATE, SKIP_SLUG_EXISTS, ERROR }

/**
 * One row of the import preview. [reasons] holds fixed German error texts (never echoing payload content, only entry index and field
 * name), [hints] non-blocking remarks (missing end time, HTML-like characters). [startsAt]/[endsAt] are naive organization wall-clock
 * values exactly as typed in the payload (class B), never converted.
 */
@Serializable
data class EventImportPreviewRowDto(
    val index: Int,
    val slug: String?,
    val title: String?,
    val status: EventImportRowStatus,
    val reasons: List<String>,
    val hints: List<String>,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
)

/**
 * Dry-run result. [payloadSha256] is a checksum over the UTF-8 bytes of the exact payload string; the commit call must send the
 * same string plus this hash (protection against an accidental edit between preview and commit, NOT a security boundary).
 */
@Serializable
data class EventImportPreviewDto(
    val rows: List<EventImportPreviewRowDto>,
    val createCount: Int,
    val skipCount: Int,
    val errorCount: Int,
    val payloadSha256: String,
    val timeZoneId: String,
)

@Serializable
data class EventImportResultDto(
    val created: Int,
    val skipped: Int,
    val skippedSlugs: List<String>,
)
