package network.lapis.cloud.server.partg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.partg.report.PartgReportPdfRenderer
import network.lapis.cloud.server.partg.report.PartgReportTexts
import network.lapis.cloud.server.partg.report.PartgReportXlsxWriter
import network.lapis.cloud.server.pdf.formatGermanDate
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- the ONE place a KEPT version is read, checked and rendered; used by the board's export route and by the auditor's routes, so a
 * file taken by either carries the same footer and the same name. A version is never recomputed: the stored text is hashed again and must equal
 * both stored checksums, then it is decoded and rendered OUTSIDE any transaction.
 */
internal object PartgStoredVersionRenderer {
    /** A kept version whose stored text still hashes to its checksum. */
    class Verified(
        val meta: PartgVersionStore.VersionMeta,
        val json: String,
    )

    /** What a render reports for the audit log: counters only. */
    class Rendered(
        val bytes: ByteArray,
        val unitCount: Int,
        val missingCount: Int,
        val donorRowCount: Int,
        val findingCount: Int,
        val sponsorRowCount: Int,
        val youthOrgRowCount: Int,
        val enterpriseRowCount: Int,
    )

    /** ONE read transaction. [NotFoundException] for an unknown id, [PartyReportVersionIntegrityException] when the text no longer matches its checksum. */
    fun readVerified(versionId: Uuid): Verified {
        val stored =
            transaction {
                val meta = PartgVersionStore.versionMeta(versionId)
                val content = meta?.let { PartgVersionStore.content(versionId) }
                if (meta == null || content == null) null else Triple(meta, content.first, content.second)
            } ?: throw NotFoundException("Version not found")
        val (meta, json, storedHash) = stored
        if (PartgVersionFormat.hash(json) != storedHash || storedHash != meta.contentHash) throw PartyReportVersionIntegrityException()
        return Verified(meta = meta, json = json)
    }

    /** `rechenschaftsbericht-<year>-version-<n>.<ext>`: fixed, nothing user-supplied. */
    fun fileName(
        meta: PartgVersionStore.VersionMeta,
        extension: String,
    ): String = "rechenschaftsbericht-${meta.year}-version-${meta.versionNumber}.$extension"

    /** PDF or XLSX of [verified]; throws what the renderers throw (`PdfLimitExceeded`, `PartgReportTooLargeException`, an integrity error). */
    suspend fun render(
        verified: Verified,
        format: PartgReportFormat,
    ): Rendered {
        val meta = verified.meta
        val footer =
            PartgReportTexts.versionFooter(
                versionNumber = meta.versionNumber,
                keptOn = formatGermanDate(OrganizationTimeZone.wallNowOf(meta.frozenAt).date),
                hash12 = meta.contentHash.take(HASH_PREFIX_CHARS),
                final = meta.finalizedAt != null,
            )
        return withContext(Dispatchers.Default) {
            val document = PartgVersionFormat.decode(text = verified.json, formatVersion = meta.formatVersion)
            val bytes =
                when (format) {
                    PartgReportFormat.PDF -> PartgReportPdfRenderer().render(document = document, footer = footer)
                    PartgReportFormat.XLSX -> PartgReportXlsxWriter.write(document = document, footer = footer)
                    PartgReportFormat.CSV -> throw IllegalStateException("CSV is not a report format")
                }
            Rendered(
                bytes = bytes,
                unitCount = document.units.size,
                missingCount = document.completeness.units.sumOf { it.missingCurrent.size },
                donorRowCount = document.annex.donors.size,
                findingCount = document.findings.size,
                sponsorRowCount = document.annex.sponsors.size,
                youthOrgRowCount = document.annex.youthOrgs.size,
                enterpriseRowCount = document.annex.enterprises.size,
            )
        }
    }

    private const val HASH_PREFIX_CHARS = 12
}
