package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostReportTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.rpc.SocialContentTombstone
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns [SocialPostTable] (V1.1.1 Soziales Netzwerk), [SocialPostBoostTable] (Welle V1.1.2) and,
 * since Welle V1.1.5, [SocialPostReportTable] too. Blocker-Punkt #1 aus dem Implementierungsplan
 * (§ 6.1): `social_post` has TWO FKs to `member(id)` (`author_member_id`/`state_changed_by`),
 * `social_post_boost.member_id` is a THIRD, `social_post_report`'s `reporter_member_id`/
 * `decided_by` are a FOURTH/FIFTH -- `PersonalDataCoverageTest`'s `information_schema` walk goes
 * red the moment the corresponding migrations land unless a contributor here covers every one of
 * them, so this file is a hard build gate, not an optional nicety. `social_post_erasure` is
 * deliberately NOT covered here -- see [PersonalDataRegistry.noPersonalDataAllowlist] "Manages the
 * erasure process itself", the exact posture `erasure_request` already has for the mitglieds-
 * bezogenen Pfad.
 *
 * **Welle V1.1.5**: [erase]'s [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] branch upgrades from
 * "retain-with-reason" (Welle V1.1.1/V1.1.2, the columns did not exist yet) to REAL Tombstoning --
 * see the branch's own KDoc below. [ErasureMode.ANONYMIZE] bleibt UNVERAENDERT retain-with-reason
 * (der Autorname loest nach Anonymisierung der `member`-Zeile auf das Platzhalter-Konto auf).
 * `social_post_report` ist in beiden Modi retain-with-reason -- eine Meldung ist ein
 * Verfahrensdatensatz zu einer gesetzlichen Pflicht (DSA Art. 16), dessen Aufbewahrung eigene
 * Rechtsgrundlage hat.
 */
object SocialNetworkPersonalData : PersonalDataContributor {
    override val sectionKey = "social_network"
    override val displayName = "Soziales Netzwerk"
    override val coveredTables = setOf(SocialPostTable, SocialPostBoostTable, SocialPostReportTable)

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
            putJsonArray("reportsFiled") {
                SocialPostReportTable
                    .selectAll()
                    .where { SocialPostReportTable.reporterMemberId eq memberId }
                    .forEach { row -> add(reportSummaryJson(row)) }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val postOutcome = eraseSocialPosts(memberId = memberId, mode = mode)
        val boostCount = SocialPostBoostTable.selectAll().where { SocialPostBoostTable.memberId eq memberId }.count()
        val boostReason =
            "Ein Boost ist Teil der LTR-Buchungshistorie (GoBD-Aufbewahrung, dieselbe Haltung wie " +
                "jeder andere ltr_ledger_entry-Eintrag) -- der Boost-Betrag/-Zeitpunkt selbst bleibt " +
                "als Eigentumsnachweis-Historie unangetastet, unabhaengig vom Content-Tombstoning " +
                "des zugehoerigen Posts."
        val reportCount = SocialPostReportTable.selectAll().where { SocialPostReportTable.reporterMemberId eq memberId }.count()
        val reportReason =
            "Eine Meldung ist ein Verfahrensdatensatz zu einer gesetzlichen Pflicht (DSA Art. 16) " +
                "-- ihre Aufbewahrung hat eine eigene Rechtsgrundlage, unabhaengig vom Schicksal des " +
                "gemeldeten Beitrags."
        return listOf(
            postOutcome,
            TableErasureOutcome(
                table = "social_post_boost",
                rowsRetained = boostCount.toInt(),
                retentionReason = boostReason,
            ),
            TableErasureOutcome(
                table = "social_post_report",
                rowsRetained = reportCount.toInt(),
                retentionReason = reportReason,
            ),
        )
    }

    /**
     * [ErasureMode.ANONYMIZE]: unveraendert retain-with-reason (Welle V1.1.1-Verhalten).
     *
     * [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] (Welle V1.1.5, NEU): tombstoned jeden Post
     * dieses Mitglieds, der noch NICHT getombstonet ist (`contentErasedAt.isNull()` im `WHERE` --
     * "erster Schreiber gewinnt", siehe [SocialContentTombstone] KDoc "Erster Schreiber gewinnt").
     * `content` wird mit [SocialContentTombstone.ON_AUTHOR_REQUEST] ueberschrieben (nicht
     * `.ON_POST_REQUEST` -- Entscheidungspunkt E-A unterscheidet nach Anlass, dies ist der
     * mitglieds-weite Loeschantrag, nicht der post-bezogene `executeContentErasure`-Pfad). Das
     * Ergebnis wird als `rowsAnonymized`, NICHT `rowsDeleted` gemeldet -- die Zeile ueberlebt (nur
     * `content`/`content_erased_at`/`content_erasure_note` aendern sich), das ist die ehrliche
     * Zaehlung. `id`/`parent_id`/`root_id`/`depth`/`initial_weight_ltr`/`published_at`/`state`
     * bleiben UNVERAENDERT -- ein Cascade-Write auf Kind-Posts existiert bewusst nicht (K2-Analogie).
     *
     * Läuft in der Transaktion des Aufrufers ([network.lapis.cloud.server.rpc.DsgvoService
     * .executeErasure]) -- **kein** `AuditLogRecorder.record`-Aufruf hier (der
     * `dsgvo_audit_log`-Eintrag `ERASURE_EXECUTED` entsteht bereits in `DsgvoService`; ein
     * zusaetzlicher GoBD-Chain-Lock mitten im Contributor-Walk wuerde den Deadlock-Vertrag von
     * `AuditLogRecorder` verletzen).
     */
    private fun eraseSocialPosts(
        memberId: Uuid,
        mode: ErasureMode,
    ): TableErasureOutcome {
        val postCount = SocialPostTable.selectAll().where { SocialPostTable.authorMemberId eq memberId }.count()
        return when (mode) {
            ErasureMode.ANONYMIZE ->
                TableErasureOutcome(
                    table = "social_post",
                    rowsRetained = postCount.toInt(),
                    retentionReason =
                        "Ein Post ist Teil der oeffentlichen/organisationsinternen Diskussions- und LTR-" +
                            "Buchungshistorie; der Autorenname loest nach der Anonymisierung der member-" +
                            "Zeile auf das Pseudonym-Platzhalter-Konto auf -- kein Feld wird geloescht.",
                )
            ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED -> {
                val now = DbClock.nowLocalDateTime()
                val tombstoned =
                    SocialPostTable.update({
                        (SocialPostTable.authorMemberId eq memberId) and SocialPostTable.contentErasedAt.isNull()
                    }) {
                        it[content] = SocialContentTombstone.ON_AUTHOR_REQUEST
                        it[contentErasedAt] = now
                        it[contentErasureNote] = "Mitglieds-weiter Loeschantrag, Art. 17 DSGVO"
                    }
                val alreadyTombstoned = postCount.toInt() - tombstoned
                TableErasureOutcome(
                    table = "social_post",
                    rowsAnonymized = tombstoned,
                    rowsRetained = alreadyTombstoned,
                    retentionReason =
                        if (alreadyTombstoned > 0) {
                            "$alreadyTombstoned Beitrag/Beitraege waren bereits per post-bezogenem Art.-17-Antrag " +
                                "getombstonet (erster Schreiber gewinnt) -- der dortige Marker bleibt unveraendert."
                        } else {
                            null
                        },
                )
            }
        }
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

private fun reportSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[SocialPostReportTable.id].toString())
        put("postId", row[SocialPostReportTable.postId].toString())
        put("category", row[SocialPostReportTable.category].name)
        put("status", row[SocialPostReportTable.status].name)
        put("reportedAt", row[SocialPostReportTable.reportedAt].toString())
    }
