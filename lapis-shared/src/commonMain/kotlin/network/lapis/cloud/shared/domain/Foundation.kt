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
 *
 * V1.2.11 (PdV-CSV-Import): [DONOR] and [DECEASED] are two source-CRM statuses this codebase had
 * no prior equivalent for, added to admit a one-time bulk import of the PdV's legacy membership
 * database (`MemberCsvImport`) without inventing a status this codebase's own admission workflow
 * would ever produce. Both are appended at the END of the literal list (not inserted alphabetically
 * or semantically) -- this codebase's convention for extending an already-CHECK-constrained,
 * already-serialized enum without disturbing any existing ordinal-sensitive code (see V9's own note
 * for `AuditEntityType`). Neither is in ANY [MemberStatusSets] capability set except
 * [MemberStatusSets.LOGIN_BLOCKED] -- see that set's own KDoc.
 *
 * [DONOR] = Spender/Förderer: a financial supporter, NOT a member, no Beitragspflicht. Deliberately
 * NOT the same concept as the accounting module's `external_donor` entity (§25 PartG booking-side
 * donor identity, see `10-accounting.kuml.kts`) -- this is a MEMBERSHIP STATUS on a `member` row
 * (imported from the CRM export as a bulk contact list), that is a booking entity tied to actual
 * ledger postings. The two are never linked and must not be confused.
 *
 * [DECEASED] = verstorben: a terminal status, login-blocked, imported as-is from the source CRM's
 * own "verstorben" literal. There is no transition path INTO this status from this codebase's own
 * admission/exit workflow (`IRegistrationService`) -- it exists purely to admit already-deceased
 * historical CRM rows without silently dropping them or misrepresenting them as [WITHDRAWN].
 */
@Serializable
enum class MemberStatus { APPLICATION, ACTIVE, GUEST, WITHDRAWN, REJECTED, FRIEND, DONOR, DECEASED }

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
     * Darf LTR halten, empfangen und im SOZIALEN NETZ ausgeben (Beitrags-/Kommentar-Einsatz und
     * Boost) sowie das eigene LTR-Konto einsehen. Bewusst NICHT "darf LTR generell ausgeben":
     * Abstimmungs-Einsätze (`GovernanceService.castVoteBallot`), Crowdfunding-Projektgewichtung,
     * Systemisches Konsensieren, Wahlen und der Auktionsmarkt bleiben [ORGANIZATION_MEMBER]-exklusiv
     * -- siehe Vault "Meritokratisches System und Libertaler", Grundprinzip "Gäste dürfen handeln,
     * nicht ausgeben", das für das Posten gezielt und ausschliesslich dafür aufgehoben wird
     * (Vault "Soziales Netzwerk" § "FRIEND-Erweiterung -- neues Capability-Set").
     *
     * [MemberStatus.GUEST] ist bewusst NICHT enthalten, obwohl GUEST und [MemberStatus.FRIEND] sonst
     * gemeinsam [NON_MEMBER] bilden und von `SocialVisibility.readableByCondition` identisch behandelt
     * werden: eine föderierte OIDC-Gastidentität führt ihr LTR-Konto auf ihrem HEIMATSERVER, nicht
     * hier -- ein hiesiges Guthaben für sie zu führen wäre eine zweite, nicht abgleichbare
     * Kontoführung derselben Person. Ein GUEST darf im sozialen Netz also LESEN, aber nicht POSTEN.
     * Diese Asymmetrie ist gewollt und durch einen eigenen Test gepinnt.
     *
     * [MemberStatus.APPLICATION] ist ebenfalls nicht enthalten -- ein Beitrittsantragsteller darf sich
     * einloggen, um seinen Antragsstand zu sehen, aber nichts wirtschaftlich Bindendes tun (siehe den
     * "ANTRAG membership-gate audit"-Kommentar in `PeerTransferService.transferLtr`). Das gilt auch
     * für einen FRIEND, der gerade `applyForMembership` ausgelöst hat: seine Zeile steht für die Dauer
     * des Antrags auf APPLICATION, er verliert das Posting-Recht also vorübergehend, exakt wie er nach
     * [CONFERENCE_ELIGIBLE] auch den Konferenzzugang verliert (siehe [MemberDto.friendSince] KDoc).
     *
     * **Akzeptiertes Restrisiko (Welle V1.1.4, Entscheidungspunkt E-C)**: ein FRIEND-Konto ist
     * kostenlos, selbstregistriert und OHNE E-Mail-Verifikation nutzbar. `FriendRegistrationConfig
     * .requireEmailVerification` (`LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION`, Default `false`) IST
     * ein Verifikationszwang-Schalter und wird -- seit Security-Audit-Runde 1, F1 (2026-08-19) --
     * auch von `MembershipGuards.requireLtrEligibleMembership` ausgewertet, exakt wie schon vorher
     * von `requireConferenceEligibleMembership`; siehe dessen KDoc für das gemeinsame Muster. Das
     * verbleibende Restrisiko besteht also NICHT mehr darin, dass es diesen Schalter für die
     * LTR-Fläche gar nicht gäbe, sondern einzig darin, dass er standardmäßig `false` bleibt, weil
     * kein echter SMTP-Transport existiert -- sobald ein Mailer existiert und der Schalter auf
     * `true` gestellt wird, greift die Sperre auf BEIDEN Flächen (Konferenz UND LTR/soziales Netz)
     * gemeinsam. Die einzige mengenmäßige Bremse bis dahin ist der globale Registrierungs-Deckel
     * `LAPIS_FRIEND_MAX_ACCOUNTS` (Default 500, siehe `FriendRegistrationConfig`/
     * `RegistrationService.registerFriend`) -- er begrenzt sowohl die Zahl potenzieller
     * LTR-Ausgeber als auch, in Kombination mit dem in [SocialReadPipeline]s `toDtos`-KDoc
     * dokumentierten Scraping-Vektor (Autorengewicht-Sichtbarkeit für `LTR_ELIGIBLE`-Betrachter),
     * die Zahl möglicher Abfrager.
     *
     * **Akzeptiertes Restrisiko (Welle V1.1.4, Entscheidungspunkt E-D, Security-Audit-Runde 1
     * F2, Nutzerentscheidung 2026-08-19)**: `SocialPostVisibility.PUBLIC` bleibt für
     * [MemberStatus.FRIEND] offen (siehe `SocialNetworkService.requireVisibilityAllowedFor`, die
     * nur `MEMBERS_ONLY` für [NON_MEMBER] sperrt). Damit kann ein ACTIVE-Mitglied per minimaler Peer-Transfer-
     * Überweisung (0,01 LTR, der Mindest-Einsatz, siehe `PeerTransferService`) einem identitäts-
     * ungeprüften FRIEND-Konto de facto die Fähigkeit verschaffen, auf der öffentlichen,
     * suchmaschinen-indexierten Domain zu veröffentlichen -- eine reale Autoritätsdelegation ohne
     * jede Identitätsprüfung auf der Empfängerseite. Dies ist eine bewusst akzeptierte
     * Produktentscheidung (analog zum bereits akzeptierten Scraping-Vektor oben), NICHT ein
     * Code-Fehler -- sie entsteht strukturell aus der Kombination "FRIEND darf PUBLIC posten" +
     * "jedes ACTIVE-Mitglied darf LTR an jedes LTR_ELIGIBLE-Ziel überweisen" und wäre nur durch
     * eine zusätzliche, hier bewusst nicht gezogene Einschränkung schließbar.
     */
    val LTR_ELIGIBLE: Set<MemberStatus> = setOf(MemberStatus.ACTIVE, MemberStatus.FRIEND)

    /**
     * Politician-rating basket (V0.6.4 guest basket), deliberately EXCLUDES [MemberStatus.FRIEND]
     * -- an unverified, self-registered name must not move a public trust metric.
     */
    val POLITICIAN_RATER: Set<MemberStatus> = setOf(MemberStatus.ACTIVE, MemberStatus.GUEST)

    /**
     * May not log in at all (`AuthRoutes` gate).
     *
     * V1.2.11 (PdV-CSV-Import) added [MemberStatus.DECEASED] and [MemberStatus.DONOR] here:
     * [MemberStatus.DECEASED] is terminal by definition -- a deceased person can never authenticate.
     * [MemberStatus.DONOR] is blocked as a deliberate default, not a technical necessity: the import
     * tool never creates an `account` row for a DONOR (see `MemberCsvImport` KDoc), so login is
     * already structurally impossible today -- but SHOULD a later donor-self-service portal ever
     * grant one an account, the block here means that capability must be switched on deliberately by
     * widening this set, not discovered as an accidental side effect of an account merely existing.
     */
    val LOGIN_BLOCKED: Set<MemberStatus> =
        setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR)

    /**
     * A membership that has definitively ended -- self-initiated exit ([MemberStatus.WITHDRAWN]),
     * board-rejected application ([MemberStatus.REJECTED]), or death ([MemberStatus.DECEASED]).
     * Deliberately EXCLUDES [MemberStatus.DONOR]: a donor was never an [ORGANIZATION_MEMBER] in the
     * first place (see [SepaService]'s own contribution-eligibility check, which already gates on
     * [ORGANIZATION_MEMBER] alone), so there is nothing membership-shaped to have "ended" for one.
     *
     * The one current consumer is `SepaBatchPoller.runPhaseB`'s defense-in-depth mandate revocation:
     * the actual debit-generation path is a positive allowlist ([ORGANIZATION_MEMBER]), so this set
     * is not what stops a DECEASED member from being charged -- it is what stops an ACTIVE SEPA
     * mandate and any already-GENERATED pain.008 file from being left authorizing a debit for a
     * membership that will never be charged anyway (see that function's own KDoc "Security Round 2"
     * and CLAUDE.md's V1.2.11 finding on the pre-[DECEASED] version of this list). Added here, in
     * [MemberStatusSets], instead of left as a poller-local literal, so a FUTURE terminal status
     * gets this defense-in-depth revocation for free instead of requiring every hardcoded call site
     * to be found and re-widened by hand.
     */
    val MEMBERSHIP_ENDED: Set<MemberStatus> =
        setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED)
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
 * Reduced projection of [MemberDto] for member-picker UI (committee/meeting/ledger/etc. member
 * selectors, see [network.lapis.cloud.shared.rpc.IMemberService.listMembers]). Requires an
 * authenticated caller since V1.2.11 (PdV-CSV-Import, see that method's own KDoc for why), but
 * still deliberately excludes [MemberDto.email] and [MemberDto.role] — those remain PII/
 * authorization-relevant fields no picker-shaped call needs to expose, authenticated or not.
 */
@Serializable
data class MemberSummaryDto(
    val id: String,
    val displayName: String,
)
