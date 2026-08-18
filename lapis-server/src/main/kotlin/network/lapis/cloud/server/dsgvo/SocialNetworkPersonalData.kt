package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [SocialPostTable] (V1.1.1 Soziales Netzwerk). Blocker-Punkt #1 aus dem Implementierungsplan
 * (§ 6.1): `social_post` has TWO FKs to `member(id)` (`author_member_id`/`state_changed_by`) --
 * `PersonalDataCoverageTest`'s `information_schema` walk goes red the moment
 * `V4__social_network_core.sql` lands unless a contributor here covers the table, so this file is
 * a hard build gate, not an optional nicety.
 *
 * **Welle V1.1.1 scope**: [erase] is retain-with-reason for BOTH [ErasureMode] variants -- the
 * real Tombstone-Hard-Delete behaviour for [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] (replacing
 * `content` with a marker string, see the implementation plan § 4.11 "Weg A") requires the
 * `content_erased_at`/`content_erasure_note` columns, which are deliberately NOT part of this
 * wave's schema (see `32-social-network.kuml.kts` file header) -- Welle V1.1.5 upgrades this
 * contributor's [HARD_DELETE_WHERE_UNCONSTRAINED] branch once those columns exist. Until then, a
 * DSGVO erasure request against a member who has authored Social Posts is honestly reported as
 * "retained, not yet erasable via this domain" rather than silently claiming a deletion that
 * cannot structurally happen yet.
 */
object SocialNetworkPersonalData : PersonalDataContributor {
    override val sectionKey = "social_network"
    override val displayName = "Soziales Netzwerk"
    override val coveredTables = setOf(SocialPostTable)

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("postsAuthored") {
                SocialPostTable
                    .selectAll()
                    .where { SocialPostTable.authorMemberId eq memberId }
                    .forEach { row -> add(postSummaryJson(row)) }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val postCount = SocialPostTable.selectAll().where { SocialPostTable.authorMemberId eq memberId }.count()
        val reason =
            when (mode) {
                ErasureMode.ANONYMIZE ->
                    "Ein Post ist Teil der oeffentlichen/organisationsinternen Diskussions- und LTR-" +
                        "Buchungshistorie; der Autorenname loest nach der Anonymisierung der member-" +
                        "Zeile auf das Pseudonym-Platzhalter-Konto auf -- kein Feld wird geloescht."
                ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED ->
                    "Echtes Content-Tombstoning ist in dieser Welle (V1.1.1) noch nicht verfuegbar -- " +
                        "das dafuer noetige content_erased_at/content_erasure_note-Spaltenpaar landet " +
                        "erst mit Welle V1.1.5. Bis dahin bleibt der Inhalt unangetastet, ehrlich als " +
                        "'retained' berichtet statt eine Loeschung vorzutaeuschen, die strukturell " +
                        "noch nicht stattfinden kann."
            }
        return listOf(
            TableErasureOutcome(
                table = "social_post",
                rowsRetained = postCount.toInt(),
                retentionReason = reason,
            ),
        )
    }
}

private fun postSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[SocialPostTable.id].toString())
        put("content", row[SocialPostTable.content])
        put("visibility", row[SocialPostTable.visibility].name)
        put("state", row[SocialPostTable.state].name)
        put("initialWeightLtr", row[SocialPostTable.initialWeightLtr].toPlainString())
        put("publishedAt", row[SocialPostTable.publishedAt].toString())
    }
