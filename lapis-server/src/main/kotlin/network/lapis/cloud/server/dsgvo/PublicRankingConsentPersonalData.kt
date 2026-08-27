package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Security-Fix (Review, Runde 2): caps [PublicRankingConsentPersonalData.export] at this many
 * newest rows -- mirrors `TrustAnchorEventStore`'s (`network.lapis.cloud.server.federation`) own
 * `MAX_LISTED_EVENTS`/`.limit(...)` idiom for capping an unbounded-growth append-only log. See
 * [PublicRankingConsentPersonalData.export] KDoc for why this bound is needed on the EXPORT side
 * regardless of how tightly the write path itself is throttled.
 */
private const val MAX_EXPORTED_EVENTS = 2_000

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- owns [PublicRankingConsentEventTable] alone.
 *
 * **Erased, never retained** -- a deliberate departure from
 * [ConferencePersonalData]'s "retain-with-reason" treatment of the structurally similar
 * `conference_guest_consent_acknowledgment` table. That table is the accountability proof that a
 * PAST, already-completed act of processing someone else's audio/video was lawful (Art. 5(2)/7(1)
 * DSGVO) -- erasing it would destroy the very record that justifies something that already
 * happened. This table's only purpose is the OPPOSITE: it gates an ONGOING public disclosure of
 * the subject's OWN data. Once the member themselves is gone (erased), there is nothing left for
 * this consent to gate, and no accountability interest in keeping it -- so it is hard-deleted, not
 * anonymized (`rowsDeleted`, never `rowsAnonymized`/`rowsRetained`).
 */
object PublicRankingConsentPersonalData : PersonalDataContributor {
    override val sectionKey = "publicRankingConsent"
    override val displayName = "Öffentliche Ranglisten -- Einwilligungen"
    override val coveredTables = setOf(PublicRankingConsentEventTable)

    /**
     * Security-Fix (Review, Runde 2): capped at [MAX_EXPORTED_EVENTS] newest rows, `truncated`
     * flagged in the JSON when the cap was hit. Without this, `GET /api/dsgvo/members/{id}/export`
     * (unrate-limited, callable by the subject OR any ADMIN, see `DsgvoRoutes.kt`) would
     * materialize every row this member has ever caused in the append-only
     * `public_ranking_consent_event` table into one `JsonObject`. A member alternating
     * [network.lapis.cloud.shared.rpc.IDsgvoService.grantPublicRankingConsent]/
     * [network.lapis.cloud.shared.rpc.IDsgvoService.revokePublicRankingConsent] can drive that
     * table's row count up at roughly 20 rows/min (see `PublicRankingConsentStore` KDoc
     * "Concurrency" and `DsgvoService.consentRateLimiter`/`consentRevokeRateLimiter`) -- this cap
     * keeps the EXPORT side bounded regardless of how many rows the write side ever accumulates,
     * so table growth can never translate into unbounded heap/serialization pressure on this
     * route. This is a defensive cap against abuse, not a claim that Art. 15/20 completeness never
     * matters -- a legitimate member's real consent history never approaches 2000 rows.
     */
    override fun export(memberId: Uuid) =
        buildJsonObject {
            val rows =
                PublicRankingConsentEventTable
                    .selectAll()
                    .where { PublicRankingConsentEventTable.memberId eq memberId }
                    .orderBy(PublicRankingConsentEventTable.occurredAt, SortOrder.DESC)
                    .limit(MAX_EXPORTED_EVENTS + 1)
                    .toList()
            put("truncated", rows.size > MAX_EXPORTED_EVENTS)
            putJsonArray("events") {
                rows.take(MAX_EXPORTED_EVENTS).forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[PublicRankingConsentEventTable.id].toString())
                            put("rankingKind", row[PublicRankingConsentEventTable.rankingKind].name)
                            put("eventType", row[PublicRankingConsentEventTable.eventType].name)
                            put("occurredAt", row[PublicRankingConsentEventTable.occurredAt].toString())
                            put("supersededAt", row[PublicRankingConsentEventTable.supersededAt]?.toString())
                            put("consentVersion", row[PublicRankingConsentEventTable.consentVersion])
                            put("consentSha256", row[PublicRankingConsentEventTable.consentSha256])
                        },
                    )
                }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = PublicRankingConsentEventTable.deleteWhere { PublicRankingConsentEventTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(
                table = "public_ranking_consent_event",
                rowsDeleted = deleted,
                retentionReason = null,
            ),
        )
    }
}
