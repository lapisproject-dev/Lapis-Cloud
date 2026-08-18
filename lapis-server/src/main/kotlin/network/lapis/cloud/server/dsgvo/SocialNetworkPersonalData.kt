package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [SocialPostTable] (V1.1.1 Soziales Netzwerk) and, since Welle V1.1.2, [SocialPostBoostTable]
 * too. Blocker-Punkt #1 aus dem Implementierungsplan (§ 6.1): `social_post` has TWO FKs to
 * `member(id)` (`author_member_id`/`state_changed_by`), and `social_post_boost.member_id` is a
 * THIRD -- `PersonalDataCoverageTest`'s `information_schema` walk goes red the moment
 * `V4__social_network_core.sql`/`V5__social_post_boost.sql` land unless a contributor here covers
 * both tables, so this file is a hard build gate, not an optional nicety.
 *
 * **Welle V1.1.1/V1.1.2 scope**: [erase] is retain-with-reason for BOTH [ErasureMode] variants for
 * BOTH tables -- the real Tombstone-Hard-Delete behaviour for
 * [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] (replacing `content` with a marker string, see the
 * implementation plan § 4.11 "Weg A") requires the `content_erased_at`/`content_erasure_note`
 * columns, which are deliberately NOT part of this wave's schema (see `32-social-network.kuml.kts`
 * file header) -- Welle V1.1.5 upgrades this contributor's [HARD_DELETE_WHERE_UNCONSTRAINED] branch
 * once those columns exist. A boost is additionally part of the immutable LTR-Buchungshistorie
 * (GoBD-Aufbewahrung, same posture as [LtrPersonalData]) -- deleting/anonymizing the boost row
 * itself would corrupt that ledger's own retained-with-reason story, so a boost is retained
 * regardless of [mode], same as every other LTR ledger-adjacent row in this codebase. Until Welle
 * V1.1.5 lands, a DSGVO erasure request against a member who has authored Social Posts or cast
 * Boosts is honestly reported as "retained, not yet erasable via this domain" rather than silently
 * claiming a deletion that cannot structurally happen yet.
 */
object SocialNetworkPersonalData : PersonalDataContributor {
    override val sectionKey = "social_network"
    override val displayName = "Soziales Netzwerk"
    override val coveredTables = setOf(SocialPostTable, SocialPostBoostTable)

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("postsAuthored") {
                SocialPostTable
                    .selectAll()
                    .where { SocialPostTable.authorMemberId eq memberId }
                    .forEach { row -> add(postSummaryJson(row)) }
            }
            putJsonArray("boostsGiven") {
                SocialPostBoostTable
                    .selectAll()
                    .where { SocialPostBoostTable.memberId eq memberId }
                    .forEach { row -> add(boostSummaryJson(row)) }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val postCount = SocialPostTable.selectAll().where { SocialPostTable.authorMemberId eq memberId }.count()
        val boostCount = SocialPostBoostTable.selectAll().where { SocialPostBoostTable.memberId eq memberId }.count()
        val postReason =
            when (mode) {
                ErasureMode.ANONYMIZE ->
                    "Ein Post ist Teil der oeffentlichen/organisationsinternen Diskussions- und LTR-" +
                        "Buchungshistorie; der Autorenname loest nach der Anonymisierung der member-" +
                        "Zeile auf das Pseudonym-Platzhalter-Konto auf -- kein Feld wird geloescht."
                ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED ->
                    "Echtes Content-Tombstoning ist in dieser Welle (V1.1.1/V1.1.2) noch nicht " +
                        "verfuegbar -- das dafuer noetige content_erased_at/content_erasure_note-" +
                        "Spaltenpaar landet erst mit Welle V1.1.5. Bis dahin bleibt der Inhalt " +
                        "unangetastet, ehrlich als 'retained' berichtet statt eine Loeschung " +
                        "vorzutaeuschen, die strukturell noch nicht stattfinden kann."
            }
        val boostReason =
            "Ein Boost ist Teil der LTR-Buchungshistorie (GoBD-Aufbewahrung, dieselbe Haltung wie " +
                "jeder andere ltr_ledger_entry-Eintrag) -- das Tombstoning des zugehoerigen " +
                "Post-Inhalts folgt in Welle V1.1.5, der Boost-Betrag/-Zeitpunkt selbst bleibt als " +
                "Eigentumsnachweis-Historie unangetastet."
        return listOf(
            TableErasureOutcome(
                table = "social_post",
                rowsRetained = postCount.toInt(),
                retentionReason = postReason,
            ),
            TableErasureOutcome(
                table = "social_post_boost",
                rowsRetained = boostCount.toInt(),
                retentionReason = boostReason,
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

private fun boostSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[SocialPostBoostTable.id].toString())
        put("postId", row[SocialPostBoostTable.postId].toString())
        put("amountLtr", row[SocialPostBoostTable.amountLtr].toPlainString())
        put("boostedAt", row[SocialPostBoostTable.boostedAt].toString())
    }
