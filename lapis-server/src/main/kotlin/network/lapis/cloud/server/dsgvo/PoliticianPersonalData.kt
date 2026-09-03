package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PoliticianWeightSnapshotTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [PoliticianProfileTable] (V0.6.4 Politiker-Profile und Politiker-Ranking) plus the two
 * tables that hang off it: [PoliticianReactionTable] (Like/Dislike Korb input) and
 * [PoliticianWeightSnapshotTable] (manually-triggered monthly weight history). Same three-table
 * shape [CrowdfundingPersonalData] already establishes for its own project/reaction/distribution
 * trio.
 *
 * [PoliticianProfileTable] alone has THREE member FKs (`member_id`, `granted_by_member_id`,
 * `revoked_by_member_id`) -- all three are checked in [export]/[erase], same "actor plus
 * subject(s)" shape [PeerTransferPersonalData]/[AuditLogPersonalData] already establish.
 *
 * Retain-with-reason across the board, same precedent as [CrowdfundingPersonalData]: a politician
 * profile is the organizational record of who was granted/revoked politician status and by whom,
 * a Like/Dislike rating is part of other members' shared trust-weight computation, and a weight
 * snapshot is an auditable historical record -- none are purely personal data, and every FK
 * pointer resolves to the now-anonymized [network.lapis.cloud.server.db.generated.MemberTable] row
 * post-erasure. Note that [network.lapis.cloud.server.rpc.PoliticianService.revokePoliticianStatus]
 * already deletes every reaction/snapshot row for a politician's OWN profile as an ordinary
 * business-rule side effect ("Bewertungsstatistik wird geloescht") -- that is unrelated to, and
 * runs independently of, DSGVO erasure of a RATER's or ADMIN-actor's own member row.
 */
object PoliticianPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "politician"
    override val displayName = "Politiker-Profile und Politiker-Ranking"
    override val coveredTables =
        setOf(
            PoliticianProfileTable,
            PoliticianReactionTable,
            PoliticianWeightSnapshotTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("politicianProfilesHeld") {
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.memberId eq memberId }
                    .forEach { row -> add(profileSummaryJson(row)) }
            }
            putJsonArray("politicianStatusGranted") {
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.grantedByMemberId eq memberId }
                    .forEach { row -> add(profileSummaryJson(row)) }
            }
            putJsonArray("politicianStatusRevoked") {
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.revokedByMemberId eq memberId }
                    .forEach { row -> add(profileSummaryJson(row)) }
            }
            putJsonArray("ratingsCast") {
                PoliticianReactionTable
                    .selectAll()
                    .where { PoliticianReactionTable.raterMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[PoliticianReactionTable.id].toString())
                                put("politicianProfileId", row[PoliticianReactionTable.politicianProfileId].toString())
                                put("value", row[PoliticianReactionTable.reactionValue].name)
                                put("castAt", row[PoliticianReactionTable.castAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("weightSnapshotsComputed") {
                PoliticianWeightSnapshotTable
                    .selectAll()
                    .where { PoliticianWeightSnapshotTable.computedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[PoliticianWeightSnapshotTable.id].toString())
                                put("politicianProfileId", row[PoliticianWeightSnapshotTable.politicianProfileId].toString())
                                put("periodMonth", row[PoliticianWeightSnapshotTable.periodMonth].toString())
                                put("computedAt", row[PoliticianWeightSnapshotTable.computedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val profileCondition =
            (PoliticianProfileTable.memberId eq memberId) or
                (PoliticianProfileTable.grantedByMemberId eq memberId) or
                (PoliticianProfileTable.revokedByMemberId eq memberId)
        val profileCount = PoliticianProfileTable.selectAll().where { profileCondition }.count()

        val reactionCount = PoliticianReactionTable.selectAll().where { PoliticianReactionTable.raterMemberId eq memberId }.count()

        val snapshotCount =
            PoliticianWeightSnapshotTable.selectAll().where { PoliticianWeightSnapshotTable.computedByMemberId eq memberId }.count()

        return listOf(
            TableErasureOutcome(
                table = "politician_profile",
                rowsRetained = profileCount.toInt(),
                retentionReason =
                    "Who was granted/revoked politician status, by whom, and their mandate text are " +
                        "organizational governance records -- no field is erased.",
            ),
            TableErasureOutcome(
                table = "politician_reaction",
                rowsRetained = reactionCount.toInt(),
                retentionReason =
                    "A Like/Dislike is part of the shared, auditable basis for other members' " +
                        "politician trust-weight computation -- erasing it would silently change a " +
                        "past ranking's basis.",
            ),
            TableErasureOutcome(
                table = "politician_weight_snapshot",
                rowsRetained = snapshotCount.toInt(),
                retentionReason = "Who triggered a monthly weight snapshot is part of its own auditable historical record.",
            ),
        )
    }
}

private fun profileSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[PoliticianProfileTable.id].toString())
        put("memberId", row[PoliticianProfileTable.memberId].toString())
        put("status", row[PoliticianProfileTable.status].name)
        put("grantedAt", row[PoliticianProfileTable.grantedAt].toString())
    }
