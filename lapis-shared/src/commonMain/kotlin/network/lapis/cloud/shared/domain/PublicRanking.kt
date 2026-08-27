package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- the two opt-in public leaderboards `GET
 * /transparenz` can show. Each kind is a SEPARATE, independently revocable consent -- see
 * `35-public-ranking-consent.kuml.kts` file header for why an LTR-holdings figure and a EUR
 * donation figure are never bundled into one opt-in.
 */
@Serializable
enum class PublicRankingKind { LTR_HOLDINGS, DONATIONS }

/** One row of the append-only `public_ranking_consent_event` log -- see that table's own KDoc. */
@Serializable
enum class PublicRankingConsentEventType { GRANTED, REVOKED }

/**
 * The versioned, two-layer DSGVO consent disclosure a member must see -- and echo back verbatim
 * (`version`/`sha256`) -- before `IDsgvoService.grantPublicRankingConsent` will record their
 * opt-in. Same two-layer shape `ConferenceGuestJoinInfoDto`'s own disclaimer fields already
 * establish (`network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer` KDoc "Two-layer
 * disclosure"): [headline] + [keyPoints] (always exactly two entries) render above the fold,
 * [text] (the full, composed wording) renders beneath in a permanently visible scroll box, never
 * behind a "Details anzeigen" link.
 */
@Serializable
data class PublicRankingConsentDisclaimerDto(
    val kind: PublicRankingKind,
    val version: String,
    val headline: String,
    val keyPoints: List<String>,
    val text: String,
    val sha256: String,
)

/**
 * The caller's OWN current consent state for one [kind] -- `IDsgvoService.getPublicRankingConsents`
 * never accepts a `memberId` parameter, it always resolves the caller (see that method's own KDoc)
 * -- there is no on-behalf-of read path here, not even for ADMIN.
 */
@Serializable
data class PublicRankingConsentStateDto(
    val kind: PublicRankingKind,
    /**
     * `true` iff the current (non-superseded) event for this [kind] is [PublicRankingConsentEventType.GRANTED]
     * AND its [grantedVersion] equals the disclaimer's CURRENT version. A stale grant under an
     * old, superseded wording is [effective] == `false` (see [supersededByNewVersion]) -- the
     * member must re-consent before their name appears again.
     */
    val effective: Boolean,
    val grantedAt: LocalDateTime?,
    val grantedVersion: String?,
    /**
     * `true` iff a [PublicRankingConsentEventType.GRANTED] event exists as the current row, but its
     * [grantedVersion] is no longer the disclaimer's current version -- a wording change silently
     * revoked this member's effective visibility, and the UI must say so rather than showing a plain
     * "not opted in" state that would misrepresent the member's actual prior choice.
     */
    val supersededByNewVersion: Boolean,
)
