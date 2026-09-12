package network.lapis.cloud.server.dsgvo

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Same root `network.lapis.cloud.server.Application.module` computes for `documentStorageRoot`
 * (`LAPIS_DOCUMENT_STORAGE_ROOT`, default `build/document-storage`) -- re-derived here rather than
 * threaded through [MemberPersonalDataContributor.eraseMember]'s signature (which no contributor
 * in this codebase receives a storage root through -- see [DocumentPersonalData] KDoc for why file
 * bytes are, by convention, this framework's one deliberate exception to "erase everything"). A
 * DRAFT travel-expense report has no organizational retention interest at all (unlike a document,
 * which might be the organization's only copy of a governance record) -- so unlike
 * [DocumentPersonalData], this contributor DOES remove the receipt files themselves for the
 * DRAFT/never-submitted-WITHDRAWN case.
 */
private val receiptStorageRoot: File
    get() = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- owns
 * [TravelExpenseReportTable]/[TravelExpenseLineTable]/[TravelExpenseReceiptTable] (three FKs on
 * `member` -- `subject_member_id`/`requested_by`/`decided_by` -- so `PersonalDataCoverageTest`
 * requires exactly this registration; the fourth FK, `travel_expense_receipt.uploaded_by`, is
 * covered too).
 *
 * **Erasure behaviour, by [network.lapis.cloud.shared.domain.TravelExpenseReportStatus]**:
 * - `DRAFT`, `WITHDRAWN` and `REJECTED` (regardless of whether the report was ever submitted):
 *   fully deletable. Lines, receipt rows AND receipt files are removed.
 * - `REQUESTED`, `APPROVED` and `EXECUTED`: `purpose` and every line's `description` are redacted
 *   to `"[gelöscht]"` (the columns are `NOT NULL`); `total_amount`, `travel_from`/`travel_to`,
 *   status, line kinds, amounts, `decision_note`, and the booking reference ALL REMAIN.
 *   `retentionReason`: Art. 5(2) DSGVO Rechenschaftspflicht + §147 Abs. 1 Nr. 4 AO (10 Jahre) -- a
 *   posted reimbursement IS a Buchungsbeleg; that and how much the organization paid a member
 *   must stay reconstructable for a Kassenprüfung. The free-text purpose/description is redacted,
 *   every amount/date/status value and the receipt metadata remain.
 * - **Receipt FILES for `REQUESTED`/`APPROVED`/`EXECUTED` are deliberately NOT removed** -- ONLY
 *   `EXECUTED` (`posted_journal_entry_id != null`, `chk_ter_posted_entry_state`) is actually a
 *   Buchungsbeleg (§147 AO); `REQUESTED`/`APPROVED` are still pending a final BOARD/ADMIN decision
 *   at the moment of erasure, so their files are left in place rather than destroyed out from
 *   under an in-flight decision. No automatic redaction deadline exists for this domain (unlike
 *   `ContributionReliefRedaction`'s 12-month sweep) -- there is no Art. 9 free text here, and
 *   §147 AO requires 10 years' retention regardless.
 * - **Security-Audit fix (2026-09-12, MAJOR "DSGVO-Ueberaufbewahrung")**: the discriminator used
 *   to be "was this report ever SUBMITTED" (`neverSubmitted`), which put `REQUESTED`→`REJECTED`
 *   and `REQUESTED`→`WITHDRAWN` into the redact-only bucket even though BOTH are guaranteed by
 *   `chk_ter_posted_entry_state` to have `posted_journal_entry_id IS NULL` -- i.e. no
 *   `journal_entry`, no Buchungsbeleg, EVER existed for them. The §147-AO retention argument this
 *   class's own `retentionReason` makes does not apply to a report that was never booked, so
 *   redacting only the free text while leaving hotel/travel-scan receipt FILES on disk forever was
 *   an over-retention bug, not a deliberate design choice. The correct discriminator is "was this
 *   report ever BOOKED" (`status == EXECUTED`), independent of whether it was ever submitted --
 *   `WITHDRAWN`/`REJECTED` are both [network.lapis.cloud.shared.domain.TravelExpenseReportStatusSets
 *   .TERMINAL] and can never reach `EXECUTED` from here on, so they are now always fully
 *   deletable, matching `DRAFT`. (In the normal, non-erasure workflow this same gap is additionally
 *   closed by `TravelExpenseService.withdrawReport`/`decideReport` themselves reclaiming receipt
 *   FILE bytes -- not the DB rows -- the moment a report transitions to `WITHDRAWN`/`REJECTED`; by
 *   the time erasure runs here, those files are typically already gone and this deletion is a
 *   no-op cleanup of the DB rows plus a defensive backstop for any report that predates that fix or
 *   whose file cleanup at transition time failed.)
 *
 * **Three roles, one table** (subject/requestedBy/decidedBy) -- same "row-counting, not naive
 * double-counting" discipline [ContributionReliefPersonalData] already establishes for its own
 * three-role shape: `total` uses a single three-way `OR`, never a sum of three counts. Just like
 * that contributor, **only the SUBJECT role ever triggers deletion/redaction** -- a report matched
 * only via `requestedBy` (e.g. a BOARD member using `createDraft` on someone else's behalf) or
 * `decidedBy` is counted in `total` but left completely untouched when erasing the requester/
 * decider: `purpose`/every line's `description` describe the SUBJECT's own trip, not the other
 * two roles, and hard-deleting a still-open DRAFT out from under its actual subject just because
 * the person who typed it in for them got erased would itself be a data-loss bug (review MAJOR
 * fix -- Fehlerszenario A/B).
 */
object TravelExpensePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "travelExpenses"
    override val displayName = "Reisekostenabrechnungen"
    override val coveredTables = setOf(TravelExpenseReportTable, TravelExpenseLineTable, TravelExpenseReceiptTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("travelExpenseReports") {
                TravelExpenseReportTable
                    .selectAll()
                    .where {
                        (TravelExpenseReportTable.subjectMemberId eq memberId) or
                            (TravelExpenseReportTable.requestedBy eq memberId) or
                            (TravelExpenseReportTable.decidedBy eq memberId)
                    }.forEach { row ->
                        val reportId = row[TravelExpenseReportTable.id]
                        val lines = TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq reportId }.toList()
                        add(
                            buildJsonObject {
                                put("id", reportId.toString())
                                put("subjectRoleSubject", row[TravelExpenseReportTable.subjectMemberId] == memberId)
                                put("subjectRoleRequestedBy", row[TravelExpenseReportTable.requestedBy] == memberId)
                                put("subjectRoleDecidedBy", row[TravelExpenseReportTable.decidedBy] == memberId)
                                put("status", row[TravelExpenseReportTable.status].name)
                                put("purpose", row[TravelExpenseReportTable.purpose])
                                put("travelFrom", row[TravelExpenseReportTable.travelFrom].toString())
                                put("travelTo", row[TravelExpenseReportTable.travelTo].toString())
                                put("totalAmount", row[TravelExpenseReportTable.totalAmount].toPlainString())
                                put("lineCount", lines.size)
                                put("decisionNote", row[TravelExpenseReportTable.decisionNote])
                                put("submittedAt", row[TravelExpenseReportTable.submittedAt]?.toString())
                                put("decidedAt", row[TravelExpenseReportTable.decidedAt]?.toString())
                                put("executedAt", row[TravelExpenseReportTable.executedAt]?.toString())
                                // Nie die Dateibytes -- nur Metadaten, siehe Klassen-KDoc.
                                putJsonArray("receipts") {
                                    val lineIds = lines.map { it[TravelExpenseLineTable.id] }
                                    TravelExpenseReceiptTable
                                        .selectAll()
                                        .where { TravelExpenseReceiptTable.lineId inList lineIds }
                                        .forEach { receiptRow ->
                                            add(
                                                buildJsonObject {
                                                    put("originalFilename", receiptRow[TravelExpenseReceiptTable.originalFilename])
                                                    put("mimeType", receiptRow[TravelExpenseReceiptTable.mimeType])
                                                    put("sizeBytes", receiptRow[TravelExpenseReceiptTable.sizeBytes])
                                                    put("uploadedAt", receiptRow[TravelExpenseReceiptTable.uploadedAt].toString())
                                                },
                                            )
                                        }
                                }
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val reportRows =
            TravelExpenseReportTable
                .selectAll()
                .where {
                    (TravelExpenseReportTable.subjectMemberId eq memberId) or
                        (TravelExpenseReportTable.requestedBy eq memberId) or
                        (TravelExpenseReportTable.decidedBy eq memberId)
                }.toList()
        val total = reportRows.size

        var reportsDeleted = 0
        var reportsRedacted = 0
        var linesDeleted = 0
        var receiptsDeleted = 0

        // Review MAJOR fix: only the SUBJECT role ever triggers deletion/redaction here -- same
        // discipline [ContributionReliefPersonalData] already establishes ("Only the SUBJECT role
        // ever triggers the reason_text nulling"). `purpose`/every line's `description` are
        // fachlich about the SUBJECT's own trip, not about whoever merely requested a draft on
        // their behalf (createDraft explicitly supports requesting for someone else) or decided
        // it. A row matched only via requestedBy/decidedBy is left completely untouched: erasing
        // the requester/decider must never hard-delete or redact another member's own report.
        reportRows.filter { it[TravelExpenseReportTable.subjectMemberId] == memberId }.forEach { row ->
            val reportId = row[TravelExpenseReportTable.id]
            val status = row[TravelExpenseReportTable.status]
            // Security-Audit fix (2026-09-12, MAJOR "DSGVO-Ueberaufbewahrung"): the discriminator is
            // "was this report ever BOOKED" (status == EXECUTED), not "was it ever SUBMITTED" -- see
            // class KDoc "Security-Audit fix" for the full Fehlerszenario this replaces. WITHDRAWN
            // and REJECTED are both TravelExpenseReportStatusSets.TERMINAL and, per
            // chk_ter_posted_entry_state, always have posted_journal_entry_id == null: no booking
            // ever existed, so they are fully deletable exactly like a never-submitted DRAFT --
            // regardless of whether submittedAt is set.
            val neverBooked =
                status in TravelExpenseReportStatusSets.EDITABLE ||
                    status == TravelExpenseReportStatus.WITHDRAWN ||
                    status == TravelExpenseReportStatus.REJECTED
            if (neverBooked) {
                val lineIds =
                    TravelExpenseLineTable
                        .selectAll()
                        .where {
                            TravelExpenseLineTable.reportId eq reportId
                        }.map { it[TravelExpenseLineTable.id] }
                if (lineIds.isNotEmpty()) {
                    val receiptStorageKeys =
                        TravelExpenseReceiptTable
                            .selectAll()
                            .where { TravelExpenseReceiptTable.lineId inList lineIds }
                            .map { it[TravelExpenseReceiptTable.storageKey] }
                    receiptStorageKeys.forEach { storageKey -> deleteReceiptFile(storageKey) }
                    receiptsDeleted += TravelExpenseReceiptTable.deleteWhere { TravelExpenseReceiptTable.lineId inList lineIds }
                    linesDeleted += TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId eq reportId }
                }
                reportsDeleted += TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id eq reportId }
            } else {
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq reportId }) {
                    it[purpose] = REDACTED_PLACEHOLDER
                }
                TravelExpenseLineTable.update({ TravelExpenseLineTable.reportId eq reportId }) {
                    it[description] = REDACTED_PLACEHOLDER
                }
                reportsRedacted++
            }
        }

        return listOf(
            TableErasureOutcome(
                table = "travel_expense_report",
                rowsAnonymized = reportsRedacted,
                rowsDeleted = reportsDeleted,
                rowsRetained = total - reportsDeleted,
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht + §147 Abs. 1 Nr. 4 AO (10 Jahre) -- eine gebuchte " +
                        "Reisekostenerstattung ist ein Buchungsbeleg; dass und in welcher Höhe die Organisation an ein " +
                        "Mitglied ausgezahlt hat, muss für eine Kassenprüfung nachvollziehbar bleiben. Der freitextliche " +
                        "Reisezweck/die Zeilenbeschreibung wird genullt, jeder Betrag-/Datums-/Statuswert und die " +
                        "Beleg-Metadaten bleiben. Nur für EXECUTED ist das tatsächlich ein Buchungsbeleg -- DRAFT, " +
                        "WITHDRAWN und REJECTED wurden NIE gebucht (chk_ter_posted_entry_state: posted_journal_entry_id " +
                        "IS NULL) und werden deshalb vollständig gelöscht, unabhängig davon, ob sie je eingereicht " +
                        "waren. Beleg-Dateien für REQUESTED/APPROVED bleiben unangetastet, solange die Entscheidung " +
                        "noch aussteht; kein automatischer Redaktionslauf wie bei den Beitragsvergünstigungen (kein " +
                        "Art.-9-Freitext hier).",
            ),
            TableErasureOutcome(table = "travel_expense_line", rowsDeleted = linesDeleted),
            TableErasureOutcome(table = "travel_expense_receipt", rowsDeleted = receiptsDeleted),
        )
    }
}

private const val REDACTED_PLACEHOLDER = "[gelöscht]"

private fun deleteReceiptFile(storageKey: String) {
    runCatching {
        val file = receiptStorageRoot.resolve(storageKey)
        if (file.exists()) file.delete()
    }.onFailure { e ->
        logger.warn(e) { "TravelExpensePersonalData: failed to delete receipt file for storageKey=$storageKey (DB row is authoritative)" }
    }
}
