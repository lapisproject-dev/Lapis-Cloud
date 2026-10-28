package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.governance.session.ProtocolSnapshotV1
import network.lapis.cloud.server.governance.session.SnapshotItem
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolResolutionDto
import network.lapis.cloud.shared.domain.ResolutionStatus
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** One confirmation as printed on the last page; [confirmedAt] is the stored UTC stamp (class A). */
internal data class PdfConfirmation(
    val role: ProtocolConfirmationRole,
    val name: String,
    val confirmedAt: LocalDateTime,
)

/** The bounds of a generated Protokoll PDF (bounded work for content people write). */
internal object ProtocolPdfLimits {
    const val MAX_PAGES = 200
    const val MAX_BYTES = 10 * 1024 * 1024
    val MAX_DURATION = 20.seconds
}

/**
 * V1.9.240 -- renders a finalised [ProtocolSnapshotV1] as a PDF. Reads ONLY the snapshot (no live row, no ballot, no voter): what the PDF shows is
 * exactly what the hash covers. Plain text everywhere -- nothing is interpreted. The last page ("Digital bestätigt von") lists the
 * confirmations and the full hash in Courier; it is left out for the page-limit PROBE at finalisation ([includeConfirmationPage] = false with one
 * page less allowed).
 *
 * The PDF has no encryption, no form, no JavaScript, no open action and no external resource (see [LetterPdfBuilder]); it is saved whole, so a
 * later PAdES signature can be appended as an incremental update without touching these bytes. Characters outside Latin-1 are written as `?`.
 */
internal object MeetingProtocolPdfGenerator {
    fun generate(
        snapshot: ProtocolSnapshotV1,
        contentSha256: String,
        confirmations: List<PdfConfirmation>,
        organizationName: String,
        organizationAddressLines: List<String>,
        zone: TimeZone,
        includeConfirmationPage: Boolean,
        lang: String = "de",
    ): ByteArray {
        val maxPages = if (includeConfirmationPage) ProtocolPdfLimits.MAX_PAGES else ProtocolPdfLimits.MAX_PAGES - 1
        val builder =
            LetterPdfBuilder(
                maxPages = maxPages,
                deadline = Instant.now().plusMillis(ProtocolPdfLimits.MAX_DURATION.inWholeMilliseconds),
                maxBytes = ProtocolPdfLimits.MAX_BYTES,
            )
        // A limit hit mid-way must not leave the PDDocument open until the GC (this runs inside the finalise transaction as the page probe).
        return try {
            render(
                builder = builder,
                snapshot = snapshot,
                contentSha256 = contentSha256,
                confirmations = confirmations,
                organizationName = organizationName,
                organizationAddressLines = organizationAddressLines,
                zone = zone,
                includeConfirmationPage = includeConfirmationPage,
                lang = lang,
            )
        } catch (e: Throwable) {
            builder.closeQuietly()
            throw e
        }
    }

    private fun render(
        builder: LetterPdfBuilder,
        snapshot: ProtocolSnapshotV1,
        contentSha256: String,
        confirmations: List<PdfConfirmation>,
        organizationName: String,
        organizationAddressLines: List<String>,
        zone: TimeZone,
        includeConfirmationPage: Boolean,
        lang: String,
    ): ByteArray {
        fun t(
            key: String,
            vararg args: String,
        ) = ProtocolPdfTexts.text(key = key, lang = lang, args = args)

        val header = snapshot.header
        builder.info(title = "${t(ProtocolPdfTexts.TITLE)} ${header.committeeName}")
        builder.letterhead(orgName = organizationName, orgAddressLines = organizationAddressLines)
        builder.heading("${t(ProtocolPdfTexts.TITLE)}: ${header.committeeName}")
        builder.paragraph(header.title)
        builder.paragraph("${t(ProtocolPdfTexts.VERSION)} ${snapshot.versionNo}")
        builder.paragraph("${t(ProtocolPdfTexts.DATE)}: ${formatGermanDate(header.scheduledAt.date)} ${hhmm(header.scheduledAt)}")
        header.location?.takeIf { it.isNotBlank() }?.let { builder.paragraph("${t(ProtocolPdfTexts.LOCATION)}: $it") }
        header.chairName?.let { builder.paragraph("${t(ProtocolPdfTexts.CHAIR)}: $it") }
        header.minuteTakerName?.let { builder.paragraph("${t(ProtocolPdfTexts.MINUTE_TAKER)}: $it") }

        builder.subHeading(t(ProtocolPdfTexts.ATTENDANCE))
        val a = snapshot.attendance
        builder.paragraph("${t(ProtocolPdfTexts.PRESENT)}: ${names(list = a.present.map { it.displayName }, lang = lang)}")
        builder.paragraph("${t(ProtocolPdfTexts.EXCUSED)}: ${names(list = a.excused.map { it.displayName }, lang = lang)}")
        builder.paragraph("${t(ProtocolPdfTexts.ABSENT)}: ${names(list = a.absent.map { it.displayName }, lang = lang)}")
        builder.paragraph(
            "${t(ProtocolPdfTexts.REPRESENTED)}: " +
                names(list = a.represented.map { "${it.representedName} (${it.representativeName.orEmpty()})" }, lang = lang),
        )
        builder.paragraph("${t(ProtocolPdfTexts.GUESTS)}: ${names(list = a.guests.map { it.displayName }, lang = lang)}")
        snapshot.quorum?.let {
            val overridden = if (it.overridden) ", ${t(ProtocolPdfTexts.QUORUM_OVERRIDDEN)}" else ""
            builder.paragraph("${t(ProtocolPdfTexts.QUORUM)}: ${it.present} / ${it.required} / ${it.eligible}$overridden")
        }

        snapshot.items.forEach { item -> renderItem(builder = builder, item = item, lang = lang) }
        if (snapshot.unassignedResolutions.isNotEmpty()) {
            builder.gap()
            builder.subHeading(t(ProtocolPdfTexts.UNASSIGNED))
            snapshot.unassignedResolutions.forEach { renderResolution(builder = builder, resolution = it, lang = lang) }
        }
        snapshot.correctionNote?.let {
            builder.gap()
            builder.subHeading(t(ProtocolPdfTexts.CORRECTION_HEADING))
            builder.paragraph(it)
        }

        if (includeConfirmationPage) {
            builder.newPage()
            builder.heading(t(ProtocolPdfTexts.CONFIRMED_BY))
            renderConfirmations(
                builder = builder,
                confirmations = confirmations,
                minutesRequired = snapshot.minutesRequired,
                zone = zone,
                lang = lang,
            )
            builder.gap()
            builder.paragraph(t(ProtocolPdfTexts.CHECKSUM))
            contentSha256.chunked(HASH_LINE).forEach { builder.monospaceLine(it) }
            builder.gap()
            builder.paragraph(t(ProtocolPdfTexts.DISCLAIMER))
        }
        return builder.toByteArray()
    }

    private fun renderItem(
        builder: LetterPdfBuilder,
        item: SnapshotItem,
        lang: String,
    ) {
        builder.gap()
        builder.subHeading("${ProtocolPdfTexts.text(key = ProtocolPdfTexts.ITEM, lang = lang)} ${item.position}: ${item.title}")
        item.approval?.let {
            val result =
                when (it.result) {
                    ProtocolApprovalResult.APPROVED -> ProtocolPdfTexts.APPROVAL_APPROVED
                    ProtocolApprovalResult.APPROVED_WITH_CORRECTIONS -> ProtocolPdfTexts.APPROVAL_CORRECTIONS
                    ProtocolApprovalResult.NOT_APPROVED -> ProtocolPdfTexts.APPROVAL_REJECTED
                    null -> null
                }
            val line = ProtocolPdfTexts.text(key = ProtocolPdfTexts.APPROVAL, lang = lang, formatGermanDate(it.sourceMeetingDate))
            builder.paragraph(if (result == null) line else "$line: ${ProtocolPdfTexts.text(key = result, lang = lang)}")
        }
        builder.paragraph(item.body.ifBlank { ProtocolPdfTexts.text(key = ProtocolPdfTexts.NO_COURSE, lang = lang) })
        item.resolutions.forEach { renderResolution(builder = builder, resolution = it, lang = lang) }
        if (item.tasks.isNotEmpty()) {
            builder.paragraph(ProtocolPdfTexts.text(key = ProtocolPdfTexts.TASKS, lang = lang) + ":")
            item.tasks.forEach { task ->
                val due =
                    task.dueDate
                        ?.let {
                            ", " +
                                ProtocolPdfTexts.text(
                                    key = ProtocolPdfTexts.TASK_DUE,
                                    lang = lang,
                                    formatGermanDate(it),
                                )
                        }.orEmpty()
                builder.paragraph("- ${task.assigneeName}: ${task.description}$due")
            }
        }
    }

    private fun renderResolution(
        builder: LetterPdfBuilder,
        resolution: ProtocolResolutionDto,
        lang: String,
    ) {
        fun t(
            key: String,
            vararg args: String,
        ) = ProtocolPdfTexts.text(key = key, lang = lang, args = args)
        val status =
            when (resolution.status) {
                ResolutionStatus.ADOPTED -> ProtocolPdfTexts.STATUS_ADOPTED
                ResolutionStatus.REJECTED -> ProtocolPdfTexts.STATUS_REJECTED
                ResolutionStatus.POSTPONED -> ProtocolPdfTexts.STATUS_POSTPONED
            }
        builder.paragraph("${t(ProtocolPdfTexts.RESOLUTION)} ${resolution.number}: ${resolution.title} (${t(status)})")
        builder.paragraph(resolution.text)
        // Secret ballot: counts only, and only when the k-anonymity filter of the draft let them through. No person is ever tied to a vote.
        val figures =
            if (resolution.figuresWithheld) {
                t(ProtocolPdfTexts.FIGURES_WITHHELD)
            } else {
                t(ProtocolPdfTexts.FIGURES, resolution.yes.toString(), resolution.no.toString(), resolution.abstain.toString())
            }
        val secret = if (resolution.secretBallot) " (${t(ProtocolPdfTexts.SECRET_BALLOT)})" else ""
        builder.paragraph(figures + secret)
    }

    private fun renderConfirmations(
        builder: LetterPdfBuilder,
        confirmations: List<PdfConfirmation>,
        minutesRequired: Boolean,
        zone: TimeZone,
        lang: String,
    ) {
        // One line per person: somebody who confirmed in both roles gets one line "as chair and minute taker".
        val byPerson = confirmations.groupBy { it.name to it.confirmedAt }
        byPerson.forEach { (key, rows) ->
            val roles = rows.map { it.role }.toSet()
            val roleText =
                when {
                    roles.size > 1 -> ProtocolPdfTexts.text(key = ProtocolPdfTexts.ROLE_BOTH, lang = lang)
                    ProtocolConfirmationRole.LEAD in roles -> ProtocolPdfTexts.text(key = ProtocolPdfTexts.ROLE_LEAD, lang = lang)
                    else -> ProtocolPdfTexts.text(key = ProtocolPdfTexts.ROLE_MINUTES, lang = lang)
                }
            val wall = ServerClock.systemToWall(utc = key.second, orgZone = zone)
            builder.paragraph("${key.first} ($roleText), ${formatGermanDate(wall.date)} ${hhmm(wall)}")
        }
        if (!minutesRequired) builder.paragraph(ProtocolPdfTexts.text(key = ProtocolPdfTexts.MINUTES_VACANT, lang = lang))
    }

    private fun names(
        list: List<String>,
        lang: String,
    ): String = if (list.isEmpty()) ProtocolPdfTexts.text(key = ProtocolPdfTexts.NONE, lang = lang) else list.joinToString("; ")

    private fun hhmm(at: LocalDateTime): String = "%02d:%02d".format(at.hour, at.minute)

    private const val HASH_LINE = 64
}
