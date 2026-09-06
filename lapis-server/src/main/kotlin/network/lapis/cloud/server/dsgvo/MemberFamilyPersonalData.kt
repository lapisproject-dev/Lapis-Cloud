package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- owns [MemberFamilyTable]/
 * [MemberFamilyLinkTable], which together carry a member in THREE roles: `member_family_link
 * .member_id` (the roster member -- payer or dependent), `member_family.created_by` (who created
 * the family), and `member_family_link.linked_by` (who entered a particular link).
 *
 * **Hard DELETE, not the `member_honor` retain-and-null pattern**: a `member_family_link` row is
 * a statement about a RELATIONSHIP between TWO people (the household grouping), not an
 * organizational fact about the organization's own actions the way "the organization awarded
 * member X an honor on date Y" is. Retaining it would preserve exactly the relationship
 * information of a THIRD PARTY (the other family members) that nobody but the erased person's own
 * data subject request ever consented to disclose. And it carries no accounting weight of its own
 * to retain FOR: no money moves through this table -- every `contribution` row belongs to the
 * member it was generated for regardless of family membership (`membership_tier_id` is not
 * historized anywhere; "why did Y have no invoice in 2024" is answered by the board's decision to
 * null the tier, and from Welle V1.4.4.4 onward by the
 * [network.lapis.cloud.shared.domain.MemberMembershipTierSnapshot] audit entry
 * `network.lapis.cloud.server.rpc.MembershipTierAssignment.apply` writes -- never by this row).
 *
 * **Erasure cascades to a resulting fully-linkless family, never to one with remaining links**: a
 * family that loses its LAST link becomes pure orphan data with no further purpose and is deleted
 * too. A family that still has other members after this erasure is left standing, exactly as
 * a board member would see it through [network.lapis.cloud.shared.rpc.IMemberFamilyService] --
 * possibly now payerless, which is a visible, actionable state (see that interface's
 * `removeFamilyMember` KDoc), never silently hidden data debris.
 *
 * **`created_by`/`linked_by` roles are RETAINED, never deleted** -- same accountability posture
 * every other `created_by`/`recorded_by` column in this codebase takes (see [MemberHonorPersonalData]
 * KDoc): the row itself (for a family the erased person did not belong to) stays, only the
 * membership-role facts about the erased person themselves are removed.
 *
 * **Row-counting, not naive double-counting** -- `member_id`, `linked_by`, and `created_by` (on
 * the OWNING family) can all name the SAME person on the SAME row/family. [export] filters with a
 * single `OR` across all three roles, per table, never as a summed count -- same discipline
 * [MemberHonorPersonalData] KDoc already establishes for its own two-role overlap case.
 */
object MemberFamilyPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberFamily"
    override val displayName = "Familienmitgliedschaft"
    override val coveredTables = setOf(MemberFamilyTable, MemberFamilyLinkTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("memberFamilyLinks") {
                MemberFamilyLinkTable
                    .selectAll()
                    .where { (MemberFamilyLinkTable.memberId eq memberId) or (MemberFamilyLinkTable.linkedBy eq memberId) }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MemberFamilyLinkTable.id].toString())
                                put("familyId", row[MemberFamilyLinkTable.familyId].toString())
                                put("subjectRoleMember", row[MemberFamilyLinkTable.memberId] == memberId)
                                put("subjectRoleLinkedBy", row[MemberFamilyLinkTable.linkedBy] == memberId)
                                put("role", row[MemberFamilyLinkTable.role].name)
                                put("linkedAt", row[MemberFamilyLinkTable.linkedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("memberFamiliesCreated") {
                MemberFamilyTable
                    .selectAll()
                    .where { MemberFamilyTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MemberFamilyTable.id].toString())
                                put("name", row[MemberFamilyTable.name])
                                put("createdAt", row[MemberFamilyTable.createdAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val ownLinkRows = MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.memberId eq memberId }.toList()
        val touchedFamilyIds = ownLinkRows.map { it[MemberFamilyLinkTable.familyId] }.toSet()

        val linkRowsDeleted = MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.memberId eq memberId }

        // Nur Familien loeschen, die durch diese Erasure vollstaendig linklos geworden sind -- eine
        // Familie mit verbleibenden Mitgliedern bleibt bestehen, ggf. zahlerlos (siehe Datei-KDoc
        // "Erasure cascades to a resulting fully-linkless family, never to one with remaining links").
        var familyRowsDeleted = 0
        if (touchedFamilyIds.isNotEmpty()) {
            val stillLinkedFamilyIds =
                MemberFamilyLinkTable
                    .selectAll()
                    .where { MemberFamilyLinkTable.familyId inList touchedFamilyIds.toList() }
                    .map { it[MemberFamilyLinkTable.familyId] }
                    .toSet()
            val orphanedFamilyIds = touchedFamilyIds - stillLinkedFamilyIds
            if (orphanedFamilyIds.isNotEmpty()) {
                familyRowsDeleted = MemberFamilyTable.deleteWhere { MemberFamilyTable.id inList orphanedFamilyIds.toList() }
            }
        }

        val linkedByRetained =
            MemberFamilyLinkTable
                .selectAll()
                .where { MemberFamilyLinkTable.linkedBy eq memberId }
                .count()
                .toInt()
        val createdByRetained =
            MemberFamilyTable
                .selectAll()
                .where { MemberFamilyTable.createdBy eq memberId }
                .count()
                .toInt()

        val outcomes = mutableListOf<TableErasureOutcome>()
        outcomes +=
            TableErasureOutcome(
                table = "member_family_link",
                rowsDeleted = linkRowsDeleted,
                rowsRetained = linkedByRetained,
                retentionReason =
                    if (linkedByRetained > 0) {
                        "Art. 5(2) DSGVO Rechenschaftspflicht -- wer einen Familien-Link erfasst hat (linked_by), " +
                            "bleibt als organisatorischer Vorgang nachvollziehbar; dies ist keine eigene Aussage " +
                            "über die erfasste Person."
                    } else {
                        null
                    },
            )
        outcomes +=
            TableErasureOutcome(
                table = "member_family",
                rowsDeleted = familyRowsDeleted,
                rowsRetained = createdByRetained,
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht (created_by) UND fortbestehende Familien mit " +
                        "verbleibenden Mitgliedern -- eine Familienmitgliedschafts-Verknüpfung ist eine Aussage " +
                        "über ZWEI Personen; die eigene Verknüpfung wird hart gelöscht, eine dadurch vollständig " +
                        "linklos gewordene Familie wird mitgelöscht, eine Familie mit verbleibenden Mitgliedern " +
                        "bleibt bestehen (ggf. zahlerlos). Über die Verknüpfung selbst fließt kein Cent -- " +
                        "kein Beitrag wird durch diese Löschung berührt.",
            )
        return outcomes
    }
}
