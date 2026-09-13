package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG) -- owns
 * [VolunteerAllowancePaymentTable]/[VolunteerAllowanceSelfDeclarationTable] (four FKs on `member`
 * across both tables -- `subject_member_id`/`requested_by`/`decided_by`/`cap_acknowledged_by` on
 * the payment, `member_id`/`recorded_by` on the declaration).
 *
 * **Erasure behaviour, by [VolunteerAllowancePaymentStatus] -- discriminator is "was this payment
 * ever BOOKED" (`status == EXECUTED`), NOT "was it ever submitted"** -- learning directly from the
 * V1.4.11 "DSGVO-Überaufbewahrung" security finding this codebase already documents
 * ([TravelExpensePersonalData] KDoc): `chk_vap_posted_entry_state` guarantees every non-EXECUTED
 * status has `posted_journal_entry_id IS NULL`, i.e. no Buchungsbeleg ever existed for it.
 * - `DRAFT`, `REQUESTED`, `APPROVED`, `REJECTED`, `WITHDRAWN`: fully deletable.
 * - `EXECUTED`: `activity_description` is redacted to `"[gelöscht]"` (NOT NULL column);
 *   `decision_note` is RETAINED, not nulled -- `chk_vap_decided_needs_note` requires it non-null
 *   for every EXECUTED row, and it is the board's own text about ITS decision, not the subject's
 *   own free text (same treatment [TravelExpensePersonalData] already gives its own
 *   `decision_note` column). Amount, date, category, status, all three snapshots, and the booking
 *   reference REMAIN too. `retentionReason`: Art. 5(2) DSGVO Rechenschaftspflicht + §147 Abs. 1
 *   Nr. 4 AO (10 Jahre) -- a booked allowance payment IS a Buchungsbeleg.
 *
 * **Only the SUBJECT role ever triggers deletion/redaction** -- same "row-counting, not naive
 * double-counting, three/four roles one table" discipline [TravelExpensePersonalData]/
 * [ContributionReliefPersonalData] already establish: `total` uses a single four-way `OR`, and a
 * payment matched only via `requestedBy`/`decidedBy`/`capAcknowledgedBy` is counted but left
 * untouched -- `activityDescription`/`decisionNote` describe the SUBJECT's own activity, not the
 * other three roles'.
 *
 * **Self-declaration rows**: deleted UNLESS an `EXECUTED` payment of the same member/category/year
 * still exists, in which case the declaration row remains as the (freitext-freie) evidence for
 * that payment's legal basis -- it carries no free text at all, only category/year/source/date.
 *
 * **No periodic redaction sweep** (unlike `ContributionReliefRedaction`'s 12-month run) -- there is
 * no Art. 9 free text in this domain, and §147 AO requires 10 years' retention regardless, same
 * reasoning [TravelExpensePersonalData] already gives for its own domain.
 */
object VolunteerAllowancePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "volunteerAllowances"
    override val displayName = "Ehrenamtspauschalen"
    override val coveredTables = setOf(VolunteerAllowancePaymentTable, VolunteerAllowanceSelfDeclarationTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("volunteerAllowancePayments") {
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where {
                        (VolunteerAllowancePaymentTable.subjectMemberId eq memberId) or
                            (VolunteerAllowancePaymentTable.requestedBy eq memberId) or
                            (VolunteerAllowancePaymentTable.decidedBy eq memberId) or
                            (VolunteerAllowancePaymentTable.capAcknowledgedBy eq memberId)
                    }.forEach { row ->
                        // Security-Fund (INFORMATIONAL DSGVO Art. 15, vorbestehende Hauskonvention --
                        // identisches Muster TravelExpensePersonalData.exportMember): activityDescription
                        // is the SUBJECT's own free text about their own activity, decisionNote is a
                        // board member's free text about the SUBJECT (not about the requester/decider/
                        // cap-acknowledger themselves) -- a row matched only via requestedBy/decidedBy/
                        // capAcknowledgedBy must therefore never surface either field in THAT member's
                        // own Art. 15 export, even though the row itself is correctly included (matching
                        // the class KDoc's own framing: "activityDescription/decisionNote describe the
                        // SUBJECT's own activity, not the other three roles'").
                        val subjectRoleSubject = row[VolunteerAllowancePaymentTable.subjectMemberId] == memberId
                        add(
                            buildJsonObject {
                                put("id", row[VolunteerAllowancePaymentTable.id].toString())
                                put("subjectRoleSubject", subjectRoleSubject)
                                put("subjectRoleRequestedBy", row[VolunteerAllowancePaymentTable.requestedBy] == memberId)
                                put("subjectRoleDecidedBy", row[VolunteerAllowancePaymentTable.decidedBy] == memberId)
                                put("subjectRoleCapAcknowledgedBy", row[VolunteerAllowancePaymentTable.capAcknowledgedBy] == memberId)
                                put("category", row[VolunteerAllowancePaymentTable.category].name)
                                put("status", row[VolunteerAllowancePaymentTable.status].name)
                                put("amount", row[VolunteerAllowancePaymentTable.amount].toPlainString())
                                put(
                                    "activityDescription",
                                    if (subjectRoleSubject) row[VolunteerAllowancePaymentTable.activityDescription] else null,
                                )
                                put("paymentDate", row[VolunteerAllowancePaymentTable.paymentDate].toString())
                                put(
                                    "decisionNote",
                                    if (subjectRoleSubject) row[VolunteerAllowancePaymentTable.decisionNote] else null,
                                )
                                put("submittedAt", row[VolunteerAllowancePaymentTable.submittedAt]?.toString())
                                put("decidedAt", row[VolunteerAllowancePaymentTable.decidedAt]?.toString())
                                put("executedAt", row[VolunteerAllowancePaymentTable.executedAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("volunteerAllowanceDeclarations") {
                VolunteerAllowanceSelfDeclarationTable
                    .selectAll()
                    .where {
                        (VolunteerAllowanceSelfDeclarationTable.memberId eq memberId) or
                            (VolunteerAllowanceSelfDeclarationTable.recordedBy eq memberId)
                    }.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[VolunteerAllowanceSelfDeclarationTable.id].toString())
                                put("subjectRoleMember", row[VolunteerAllowanceSelfDeclarationTable.memberId] == memberId)
                                put("subjectRoleRecordedBy", row[VolunteerAllowanceSelfDeclarationTable.recordedBy] == memberId)
                                put("category", row[VolunteerAllowanceSelfDeclarationTable.category].name)
                                put("calendarYear", row[VolunteerAllowanceSelfDeclarationTable.calendarYear])
                                put("source", row[VolunteerAllowanceSelfDeclarationTable.declarationSource].name)
                                put("declaredAt", row[VolunteerAllowanceSelfDeclarationTable.declaredAt].toString())
                                put("signedOn", row[VolunteerAllowanceSelfDeclarationTable.signedOn]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val paymentRows =
            VolunteerAllowancePaymentTable
                .selectAll()
                .where {
                    (VolunteerAllowancePaymentTable.subjectMemberId eq memberId) or
                        (VolunteerAllowancePaymentTable.requestedBy eq memberId) or
                        (VolunteerAllowancePaymentTable.decidedBy eq memberId) or
                        (VolunteerAllowancePaymentTable.capAcknowledgedBy eq memberId)
                }.toList()
        val totalPayments = paymentRows.size

        var paymentsDeleted = 0
        var paymentsRedacted = 0

        // Only the SUBJECT role ever triggers deletion/redaction -- see class KDoc.
        paymentRows.filter { it[VolunteerAllowancePaymentTable.subjectMemberId] == memberId }.forEach { row ->
            val paymentId = row[VolunteerAllowancePaymentTable.id]
            val status = row[VolunteerAllowancePaymentTable.status]
            if (status == VolunteerAllowancePaymentStatus.EXECUTED) {
                // decision_note is RETAINED, not nulled -- chk_vap_decided_needs_note requires it
                // non-null for every EXECUTED row, and it is the board's own text about its
                // decision, not the subject's personal activity description (same "retain the
                // decision note, redact only the subject's own free text" treatment
                // TravelExpensePersonalData already establishes for its own decision_note column).
                VolunteerAllowancePaymentTable.update({ VolunteerAllowancePaymentTable.id eq paymentId }) {
                    it[activityDescription] = REDACTED_PLACEHOLDER
                }
                paymentsRedacted++
            } else {
                paymentsDeleted += VolunteerAllowancePaymentTable.deleteWhere { VolunteerAllowancePaymentTable.id eq paymentId }
            }
        }

        val declarationRows =
            VolunteerAllowanceSelfDeclarationTable
                .selectAll()
                .where {
                    (VolunteerAllowanceSelfDeclarationTable.memberId eq memberId) or
                        (VolunteerAllowanceSelfDeclarationTable.recordedBy eq memberId)
                }.toList()
        val totalDeclarations = declarationRows.size
        var declarationsDeleted = 0
        declarationRows.filter { it[VolunteerAllowanceSelfDeclarationTable.memberId] == memberId }.forEach { row ->
            val declarationId = row[VolunteerAllowanceSelfDeclarationTable.id]
            val category = row[VolunteerAllowanceSelfDeclarationTable.category]
            val year = row[VolunteerAllowanceSelfDeclarationTable.calendarYear]
            val stillNeeded =
                VolunteerAllowancePaymentTable
                    .selectAll()
                    .where {
                        (VolunteerAllowancePaymentTable.subjectMemberId eq memberId) and
                            (VolunteerAllowancePaymentTable.category eq category) and
                            (VolunteerAllowancePaymentTable.status eq VolunteerAllowancePaymentStatus.EXECUTED)
                    }.any { it[VolunteerAllowancePaymentTable.paymentDate].year == year }
            if (!stillNeeded) {
                declarationsDeleted +=
                    VolunteerAllowanceSelfDeclarationTable.deleteWhere { VolunteerAllowanceSelfDeclarationTable.id eq declarationId }
            }
        }

        return listOf(
            TableErasureOutcome(
                table = "volunteer_allowance_payment",
                rowsAnonymized = paymentsRedacted,
                rowsDeleted = paymentsDeleted,
                rowsRetained = totalPayments - paymentsDeleted,
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht + §147 Abs. 1 Nr. 4 AO (10 Jahre) -- eine gebuchte " +
                        "Ehrenamts-/Übungsleiterpauschale ist ein Buchungsbeleg; dass und in welcher Höhe die " +
                        "Organisation an ein Mitglied ausgezahlt hat, muss für eine Kassenprüfung nachvollziehbar " +
                        "bleiben. Nur EXECUTED wurde tatsächlich gebucht (chk_vap_posted_entry_state: " +
                        "posted_journal_entry_id IS NULL für jeden anderen Status) und wird deshalb NICHT " +
                        "gelöscht: der freitextliche Tätigkeitsbeschrieb wird auf \"$REDACTED_PLACEHOLDER\" " +
                        "gesetzt (die Spalte ist NOT NULL, kann also nicht genullt werden), jeder Betrag-/" +
                        "Datums-/Statuswert, alle drei Deckel-Snapshots und die Buchungsreferenz bleiben " +
                        "unverändert erhalten. Jeder andere Status (noch nicht bzw. nicht mehr gebucht) wird " +
                        "vollständig gelöscht.",
            ),
            TableErasureOutcome(
                table = "volunteer_allowance_self_declaration",
                rowsDeleted = declarationsDeleted,
                rowsRetained = totalDeclarations - declarationsDeleted,
                retentionReason =
                    "Bleibt stehen, solange noch eine EXECUTED-Zahlung derselben Person/Kategorie/desselben Jahres " +
                        "existiert -- die Zeile ist deren Rechtsgrundlage und enthält ohnehin keinen Freitext.",
            ),
        )
    }
}

private const val REDACTED_PLACEHOLDER = "[gelöscht]"
