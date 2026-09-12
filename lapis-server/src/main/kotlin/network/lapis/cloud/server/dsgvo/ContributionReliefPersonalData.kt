package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- owns [ContributionReliefRequestTable] (K-2: three FKs
 * on `member` -- `subject_member_id`/`requested_by`/`decided_by` -- so `PersonalDataCoverageTest`
 * requires exactly this registration).
 *
 * **Art. 9 DSGVO -- `reason_text` is always nulled on erasure, unconditionally**, regardless of
 * [ErasureMode]: it may carry special-category data (health, see
 * `network.lapis.cloud.server.rpc.ContributionReliefService` KDoc "Art. 9 DSGVO") authored by the
 * SUBJECT about themselves -- there is no third party's interest in retaining it the way
 * [CommunicationPersonalData]'s `direct_message` body has a recipient's interest. `kind`/`status`/
 * `decision_note`/every date/amount/id field is RETAINED regardless of role or mode: that the
 * organization received a relief request, of what kind, and how the board decided is an
 * organizational/accountability record under Art. 5(2) DSGVO (Rechenschaftspflicht) -- §147 AO: a
 * board-authored waiver/reduction decision must remain reconstructable for a Kassenprüfung even
 * after the subject's own data has otherwise been anonymized elsewhere. Same "retain the fact, not
 * necessarily every word" posture [MemberHonorPersonalData]/[BoardMembershipPersonalData] already
 * take.
 *
 * **Three roles, one table** (subject/requestedBy/decidedBy) -- same "row-counting, not naive
 * double-counting" discipline [MemberHonorPersonalData] already establishes for its own two-role
 * shape, extended to three: `total` uses a single three-way `OR`, never
 * `subjectCount + requestedByCount + decidedByCount`, to avoid double-counting an overlapping row.
 * Only the SUBJECT role ever triggers the `reason_text` nulling (the field is fachlich about the
 * subject, not the requester/decider) -- `anonymizedCount` is therefore always `<= total`.
 */
object ContributionReliefPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "contributionRelief"
    override val displayName = "Beitragsvergünstigungen"
    override val coveredTables = setOf(ContributionReliefRequestTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("contributionReliefRequests") {
                ContributionReliefRequestTable
                    .selectAll()
                    .where {
                        (ContributionReliefRequestTable.subjectMemberId eq memberId) or
                            (ContributionReliefRequestTable.requestedBy eq memberId) or
                            (ContributionReliefRequestTable.decidedBy eq memberId)
                    }.forEach { row ->
                        val isSubject = row[ContributionReliefRequestTable.subjectMemberId] == memberId
                        add(
                            buildJsonObject {
                                put("id", row[ContributionReliefRequestTable.id].toString())
                                put("subjectRoleSubject", isSubject)
                                put("subjectRoleRequestedBy", row[ContributionReliefRequestTable.requestedBy] == memberId)
                                put("subjectRoleDecidedBy", row[ContributionReliefRequestTable.decidedBy] == memberId)
                                put("kind", row[ContributionReliefRequestTable.kind].name)
                                put("status", row[ContributionReliefRequestTable.status].name)
                                put("reasonCategory", row[ContributionReliefRequestTable.reasonCategory].name)
                                // Art. 9 DSGVO free text -- same field-level redaction
                                // ContributionReliefService.toDto applies over the RPC surface
                                // (`viewer.isPrivileged || viewer.memberId == subjectId`): a self-
                                // service export must never hand the exporting member a THIRD
                                // party's health disclosure just because that member once decided
                                // or requested on someone else's behalf while privileged. Only the
                                // request's own SUBJECT ever sees it here.
                                put("reasonText", if (isSubject) row[ContributionReliefRequestTable.reasonText] else null)
                                put("requestedAt", row[ContributionReliefRequestTable.requestedAt].toString())
                                put("decisionNote", row[ContributionReliefRequestTable.decisionNote])
                                put("decidedAt", row[ContributionReliefRequestTable.decidedAt]?.toString())
                                put("executedAt", row[ContributionReliefRequestTable.executedAt]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total =
            ContributionReliefRequestTable
                .selectAll()
                .where {
                    (ContributionReliefRequestTable.subjectMemberId eq memberId) or
                        (ContributionReliefRequestTable.requestedBy eq memberId) or
                        (ContributionReliefRequestTable.decidedBy eq memberId)
                }.count()
                .toInt()
        val anonymizedCount =
            ContributionReliefRequestTable.update({
                (ContributionReliefRequestTable.subjectMemberId eq memberId) and
                    ContributionReliefRequestTable.reasonText.isNotNull()
            }) {
                it[reasonText] = null
                it[reasonRedactedAt] = DbClock.nowLocalDateTime()
            }
        return listOf(
            TableErasureOutcome(
                table = "contribution_relief_request",
                rowsAnonymized = anonymizedCount,
                rowsRetained = total - anonymizedCount,
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht + §147 AO -- dass und wie die Organisation " +
                        "über eine Beitragsvergünstigung entschieden hat, ist ein organisatorischer " +
                        "Vorgang, der für eine Kassenprüfung nachvollziehbar bleiben muss. Der " +
                        "freitextliche, potenziell Art.-9-relevante Antragsgrund (reason_text) wird " +
                        "unconditional (unabhängig vom ErasureMode) genullt; Art, Status, " +
                        "Entscheidungsnotiz und alle Datums-/Betragsfelder bleiben erhalten.",
            ),
        )
    }
}
