package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingConsentStateDto
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- the append-only write/read seam over
 * `public_ranking_consent_event`. Every method here runs inside the CALLER's already-open
 * `transaction {}` (same "simple-transaction style" contract [network.lapis.cloud.server.economy
 * .LtrBalanceProvider] documents) -- this object never opens its own.
 *
 * ## Concurrency: exactly one current row per (member_id, ranking_kind)
 *
 * H2 in PostgreSQL-compatibility mode (this codebase's test path, see `DatabaseConfig.kt`) does
 * not support a partial UNIQUE index, so "exactly one current (`superseded_at IS NULL`) row per
 * `(member_id, ranking_kind)`" cannot be a schema-level constraint. [grant]/[revoke] both start by
 * taking a row lock on [MemberTable] for the acting member (`SELECT ... FOR UPDATE`, the exact
 * [network.lapis.cloud.server.economy.LtrBalanceProvider.lockForDebit] idiom) BEFORE reading or
 * writing anything in [PublicRankingConsentEventTable] -- serializing concurrent writers against
 * the SAME member so two overlapping `grant` calls can never both observe "no current row" and
 * both insert one, which would otherwise leave TWO current rows and make a subsequent `revoke`
 * miss one of them (a real data-protection bug: the member would stay visible after believing they
 * had revoked). The caller MUST already hold that lock before calling [grant]/[revoke] -- both are
 * only ever invoked from `DsgvoService`, which resolves+locks the member first.
 */
internal object PublicRankingConsentStore {
    /**
     * The caller's current state for both [PublicRankingKind]s. A [PublicRankingConsentStateDto]
     * is [PublicRankingConsentStateDto.effective] iff the current row is
     * [PublicRankingConsentEventType.GRANTED] AND its `consent_version` equals
     * [PublicRankingConsentDisclaimer.of]'s CURRENT version for that kind -- see
     * [PublicRankingConsentDisclaimerDto] KDoc "supersededByNewVersion" for why a stale grant is
     * `effective == false`.
     */
    fun currentState(memberId: Uuid): List<PublicRankingConsentStateDto> =
        PublicRankingKind.entries.map { kind -> stateOf(memberId = memberId, kind = kind) }

    private fun stateOf(
        memberId: Uuid,
        kind: PublicRankingKind,
    ): PublicRankingConsentStateDto {
        val row =
            PublicRankingConsentEventTable
                .selectAll()
                .where {
                    (PublicRankingConsentEventTable.memberId eq memberId) and
                        (PublicRankingConsentEventTable.rankingKind eq kind) and
                        PublicRankingConsentEventTable.supersededAt.isNull()
                }.singleOrNull()
        if (row == null || row[PublicRankingConsentEventTable.eventType] != PublicRankingConsentEventType.GRANTED) {
            return PublicRankingConsentStateDto(
                kind = kind,
                effective = false,
                grantedAt = null,
                grantedVersion = null,
                supersededByNewVersion = false,
            )
        }
        val grantedVersion = row[PublicRankingConsentEventTable.consentVersion]
        val currentVersion = PublicRankingConsentDisclaimer.of(kind).version
        val effective = grantedVersion == currentVersion
        return PublicRankingConsentStateDto(
            kind = kind,
            effective = effective,
            grantedAt = row[PublicRankingConsentEventTable.occurredAt],
            grantedVersion = grantedVersion,
            supersededByNewVersion = !effective,
        )
    }

    /**
     * Records a new opt-in for [kind]. Supersedes EVERY existing current row for
     * `(memberId, kind)` (plural in the `UPDATE`'s `WHERE` clause -- see class KDoc "Concurrency"
     * for why this must never be narrowed to "the one current row") before inserting the new
     * [PublicRankingConsentEventType.GRANTED] row.
     *
     * Security-Fix (Review): row-level idempotent, not just effect-level idempotent. A caller
     * repeatedly granting while ALREADY effectively granted under the SAME [version]/[sha256]
     * writes NOTHING -- returns early before touching [PublicRankingConsentEventTable] at all.
     * Without this short-circuit, a member sitting at the caller-side rate limiter's ceiling
     * (`consentRateLimiter`, `DsgvoService`, 10/min) could append ~14.400 rows/day to this
     * append-only table without ever changing the member's visible state, which also inflates
     * [network.lapis.cloud.server.dsgvo.PublicRankingConsentPersonalData.export] (unpaginated,
     * one JSON object per row) without bound. A version/wording CHANGE (or a fresh grant after a
     * revoke) still writes a new row as before -- only the exact-same-version repeat is skipped.
     */
    fun grant(
        memberId: Uuid,
        kind: PublicRankingKind,
        version: String,
        sha256: String,
        now: LocalDateTime,
    ) {
        val current = stateOf(memberId = memberId, kind = kind)
        if (current.effective && current.grantedVersion == version) return
        supersedeCurrentRows(memberId = memberId, kind = kind, now = now)
        PublicRankingConsentEventTable.insert {
            it[id] = Uuid.random()
            it[PublicRankingConsentEventTable.memberId] = memberId
            it[rankingKind] = kind
            it[eventType] = PublicRankingConsentEventType.GRANTED
            it[occurredAt] = now
            it[supersededAt] = null
            it[consentVersion] = version
            it[consentSha256] = sha256
        }
    }

    /**
     * Revokes [kind] for [memberId]. A silent, idempotent no-op (writes nothing) if there is no
     * CURRENT [PublicRankingConsentEventType.GRANTED] row -- a member who was never opted in, or
     * who is already revoked, gets no new row from a repeated revoke.
     */
    fun revoke(
        memberId: Uuid,
        kind: PublicRankingKind,
        now: LocalDateTime,
    ) {
        val currentlyGranted =
            PublicRankingConsentEventTable
                .select(PublicRankingConsentEventTable.id)
                .where {
                    (PublicRankingConsentEventTable.memberId eq memberId) and
                        (PublicRankingConsentEventTable.rankingKind eq kind) and
                        PublicRankingConsentEventTable.supersededAt.isNull() and
                        (PublicRankingConsentEventTable.eventType eq PublicRankingConsentEventType.GRANTED)
                }.firstOrNull() != null
        if (!currentlyGranted) return
        supersedeCurrentRows(memberId = memberId, kind = kind, now = now)
        PublicRankingConsentEventTable.insert {
            it[id] = Uuid.random()
            it[PublicRankingConsentEventTable.memberId] = memberId
            it[rankingKind] = kind
            it[eventType] = PublicRankingConsentEventType.REVOKED
            it[occurredAt] = now
            it[supersededAt] = null
            // A REVOKED row carries the version/hash of the disclaimer at revoke time -- purely
            // informational (no "revoke disclaimer" text exists), kept non-null because the column
            // itself is NOT NULL (see 35-public-ranking-consent.kuml.kts).
            it[consentVersion] = PublicRankingConsentDisclaimer.of(kind).version
            it[consentSha256] = PublicRankingConsentDisclaimer.of(kind).sha256
        }
    }

    private fun supersedeCurrentRows(
        memberId: Uuid,
        kind: PublicRankingKind,
        now: LocalDateTime,
    ) {
        PublicRankingConsentEventTable.update({
            (PublicRankingConsentEventTable.memberId eq memberId) and
                (PublicRankingConsentEventTable.rankingKind eq kind) and
                PublicRankingConsentEventTable.supersededAt.isNull()
        }) {
            it[supersededAt] = now
        }
    }

    /**
     * `Op<Boolean>` for the public read path's JOIN condition (`PublicTransparencyReader`) --
     * current row, [PublicRankingConsentEventType.GRANTED], AND under the disclaimer's CURRENT
     * version for [kind]. Never widened to "any GRANTED row ever" -- a stale grant under a
     * superseded wording must not surface the member.
     */
    fun effectiveGrantCondition(kind: PublicRankingKind): Op<Boolean> =
        (PublicRankingConsentEventTable.rankingKind eq kind) and
            (PublicRankingConsentEventTable.eventType eq PublicRankingConsentEventType.GRANTED) and
            PublicRankingConsentEventTable.supersededAt.isNull() and
            (PublicRankingConsentEventTable.consentVersion eq PublicRankingConsentDisclaimer.of(kind).version)

    /**
     * Count of members with an EFFECTIVE grant for [kind] (see [effectiveGrantCondition]) who are
     * ALSO `ACTIVE` and not anonymized -- the exact same filter the ranking queries themselves
     * apply (D11's minimum-cohort guard must count the same population the ranking would actually
     * show, never a superset). Independent of amount/balance -- a member with a zero balance still
     * counts towards the cohort.
     */
    fun effectiveCohortSize(kind: PublicRankingKind): Long =
        (PublicRankingConsentEventTable innerJoin MemberTable)
            .select(PublicRankingConsentEventTable.memberId)
            .where {
                effectiveGrantCondition(kind) and
                    MemberTable.anonymizedAt.isNull() and
                    (MemberTable.status eq MemberStatus.ACTIVE)
            }.count()
}
