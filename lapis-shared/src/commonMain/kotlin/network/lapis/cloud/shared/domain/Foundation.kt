package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Foundation stub (see CLAUDE.md "Vorab-Befund"): V0.1.2-V0.1.4 (Mitglieder-Stammdaten,
 * Beitritts-/Austrittsworkflow, Auth/Session) do not exist yet. [MemberStatus] and
 * [AccountRole] are modelled here only as granularly as V0.1.5 (Beitraege, Dokumente,
 * Kommunikation) needs them as foreign keys / authorization checks. A real member
 * management wave replaces this stub without breaking the foreign keys defined against it.
 *
 * V0.7.2 Beitritts-/Registrierungs-Workflow delivers the actual admission/exit lifecycle this
 * stub always anticipated (see `network.lapis.cloud.shared.rpc.IRegistrationService`):
 * [APPLICATION] -> [ACTIVE] (board-approved) or [APPLICATION] -> [REJECTED] (board-rejected,
 * retained with a reason -- never silently reused as [WITHDRAWN], which means something
 * structurally different: "left after having been admitted"), and [ACTIVE] -> [WITHDRAWN]
 * (member-initiated self-service exit, no board approval needed -- "Eintritt und Austritt sind
 * ausschliesslich Willenserklaerungen der Vertragspartner"). [GUEST] is a separate, larger
 * pre-membership guest-identity concept (see the V0.6.4 Politiker-Profile guest-rating-basket
 * scope cut and the OIDC federation wave), not a target for the Austritt transition.
 *
 * This wave (V0.11.0) renamed every literal from German to English (`ANTRAG`->`APPLICATION`,
 * `AKTIV`->`ACTIVE`, `GAST`->`GUEST`, `AUSGETRETEN`->`WITHDRAWN`, `ABGELEHNT`->`REJECTED`, see
 * Flyway `V3__member_status_english_and_friend.sql`) and added [FRIEND]: a self-registerable,
 * board-approval-free, identity-unverified NON-membership account. Scope today is video
 * conferencing ONLY (see [MemberStatusSets.CONFERENCE_ELIGIBLE]) -- no Beitragspflicht, no
 * governance/accounting/LTR rights, no membership document access. Upgradeable to [APPLICATION]
 * via `IRegistrationService.applyForMembership`; see [MemberDto.friendSince].
 */
@Serializable
enum class MemberStatus { APPLICATION, ACTIVE, GUEST, WITHDRAWN, REJECTED, FRIEND }

@Serializable
enum class AccountRole { MEMBER, BOARD, TREASURER, ADMIN }

/**
 * The ONE place a "which statuses may do X" question is answered. Every authorization gate in
 * this codebase resolves through a named set here rather than an inline `== ACTIVE` / `!isGuest`
 * comparison, so widening a capability for a future status is a one-line edit in this object
 * plus a test, never a hunt through N call sites. Deliberately in `lapis-shared` (not server-only)
 * so a client can render the same distinction without re-deriving it.
 */
object MemberStatusSets {
    /** Full contractual membership of THIS organization (Satzung/Beitrag/Governance/LTR). */
    val ORGANIZATION_MEMBER: Set<MemberStatus> = setOf(MemberStatus.ACTIVE)

    /**
     * Authenticated, but NOT a member of this organization. The positive counterpart of the
     * historical `isGuest` denylist -- every "members only" gate tests [ORGANIZATION_MEMBER]
     * instead of this set directly.
     */
    val NON_MEMBER: Set<MemberStatus> = setOf(MemberStatus.GUEST, MemberStatus.FRIEND)

    /** May enter a conference room. THE extension point for widening FRIEND later. */
    val CONFERENCE_ELIGIBLE: Set<MemberStatus> =
        setOf(MemberStatus.ACTIVE, MemberStatus.GUEST, MemberStatus.FRIEND)

    /**
     * Politician-rating basket (V0.6.4 guest basket), deliberately EXCLUDES [MemberStatus.FRIEND]
     * -- an unverified, self-registered name must not move a public trust metric.
     */
    val POLITICIAN_RATER: Set<MemberStatus> = setOf(MemberStatus.ACTIVE, MemberStatus.GUEST)

    /** May not log in at all (`AuthRoutes` gate). */
    val LOGIN_BLOCKED: Set<MemberStatus> = setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED)
}

/**
 * [street]/[postalCode]/[city]/[country] (V0.4.1) are a minimal, single, nullable postal address
 * -- needed by the Serienbrief/PDF engine (Beitragsrechnung/Spendenbescheinigung/Einladung all
 * mail-merge a member's postal address) and reused as-is by V0.4.2's later postal (Letterxpress)
 * dispatch. All default to `null` so existing call sites stay source-compatible. Not every member
 * has provided an address yet, and an email-only member may never need one.
 *
 * [dateOfBirth]/[nationality] (V0.5.2) are the two beneficial-owner fields a Transparenzregister
 * (§20 GwG) entry requires beyond name/residence (already covered by the address fields above) --
 * see `network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto`. Both default to `null` for the
 * same source-compatibility reason as the address fields; not every member is a board member.
 *
 * [reviewedById]/[reviewedAt]/[rejectionReason] (V0.7.2) are the board's own admission-decision
 * metadata -- set by `IRegistrationService.approveApplication`/`rejectApplication` (same shape as
 * `CrowdfundingProjectDto`'s own reviewedBy/reviewedAt/rejectionReason fields). All three stay
 * `null` for a member who was created directly (`IRegistrationService.createMemberDirect`, no
 * approval step) or who has not yet been decided ([MemberStatus.APPLICATION]).
 *
 * [friendSince] (V0.11.0) is set once, on [MemberStatus.FRIEND] self-registration, and NEVER
 * cleared afterwards -- including after `applyForMembership` flips the row to [MemberStatus
 * .APPLICATION]. Its ONE job: it tells `rejectApplication` to fall back to [MemberStatus.FRIEND]
 * rather than [MemberStatus.REJECTED] for a friend-originated application, so a declined membership
 * application does not also destroy the friend account. It does NOT keep conference access alive
 * during the pending application -- [MemberStatusSets.CONFERENCE_ELIGIBLE] checks the row's CURRENT
 * `status` only, and `status` is [MemberStatus.APPLICATION] (not in that set) for the whole time the
 * application is pending, so a friend-originated applicant temporarily loses conference access
 * exactly like any other applicant, regaining it only if/when the board approves (-> [MemberStatus
 * .ACTIVE]) or rejects back to [MemberStatus.FRIEND] via the `friendSince` fallback above. `null`
 * for every member who never went through friend self-registration.
 */
@Serializable
data class MemberDto(
    val id: String,
    val displayName: String,
    val email: String,
    val status: MemberStatus,
    val joinedAt: LocalDate,
    val role: AccountRole,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val dateOfBirth: LocalDate? = null,
    val nationality: String? = null,
    val reviewedById: String? = null,
    val reviewedAt: LocalDateTime? = null,
    val rejectionReason: String? = null,
    val friendSince: LocalDate? = null,
)

/**
 * Reduced projection of [MemberDto] for the unauthenticated "current member" picker
 * (see [network.lapis.cloud.shared.rpc.IMemberService.listMembers]). Deliberately excludes
 * [MemberDto.email] and [MemberDto.role] — those are PII / authorization-relevant fields that
 * must not be readable by a caller who hasn't authenticated yet.
 */
@Serializable
data class MemberSummaryDto(
    val id: String,
    val displayName: String,
)
