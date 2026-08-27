package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto

/**
 * Foundation stub — see [network.lapis.cloud.shared.domain.MemberStatus] KDoc. Provides just
 * enough member lookup for the V0.1.5 services (contributions/documents/communication) to
 * resolve display names and for the KVision shell to offer a "current member" picker in lieu
 * of real authentication (V0.1.2-V0.1.4).
 */
@RpcService
interface IMemberService {
    /**
     * V1.2.11 (PdV-CSV-Import, security fix): now requires an authenticated caller, same as every
     * other method on this interface. Historically this was deliberately callable WITHOUT
     * authentication — it was the bootstrap for a legacy "current member" picker used *before* an
     * `X-Member-Id` was chosen, back when that trusted header was the only auth mechanism this
     * codebase had (V0.1.2-V0.1.4). Real session-cookie auth (V0.7.1 Authentifizierung, V0.7.3
     * Basis-Mehrseiten-UI) replaced that picker everywhere in the client — every screen that calls
     * this method today (`MemberAdministrationScreen`, `CommitteesScreen`, `MeetingsScreen`,
     * `SepaMandatesScreen`, `LtrLedgerScreen`, and others) is already behind the app's own
     * `requireAuth`-tier routing, so gating this call server-side costs nothing functionally. What
     * it fixes: before this wave, an unauthenticated caller could enumerate id + displayName of
     * every ACTIVE member with a single unauthenticated HTTP request — harmless when the member
     * count was a handful of demo rows, but a real exposure of a political party's membership list
     * (Art. 9 Abs. 1 DSGVO special-category data) once V1.2.11's CSV import populates real member
     * rows. Still returns only id + displayName (never email/role — PII/authorization-relevant
     * fields); use [getCurrentMember] for the full [MemberDto].
     */
    suspend fun listMembers(): List<MemberSummaryDto>

    /** Resolves the caller's member context from the `X-Member-Id` request header stand-in. */
    suspend fun getCurrentMember(): MemberDto

    /**
     * V0.4.1: the only production write path for [MemberDto.street]/[MemberDto.postalCode]/
     * [MemberDto.city]/[MemberDto.country] -- without this, the postal address required by the
     * Beitragsrechnung/Spendenbescheinigung mailmerge templates (see `MailmergeRoutes`) could only
     * ever be populated via raw SQL. Self-or-privileged: a member may update their own address, and
     * ADMIN/BOARD may update any member's (e.g. when correcting an address on a donor's or fellow
     * member's behalf) -- same `isPrivileged` check `DocumentAccessLevel.BOARD_ONLY` already uses.
     * All four fields are nullable and passed together; passing `null` for a field clears it. Throws
     * [ForbiddenException] if the caller is neither the target member nor privileged, [NotFoundException]
     * if `memberId` does not resolve to an existing member.
     */
    suspend fun updateMemberAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto

    /**
     * V0.5.2: the only production write path for [MemberDto.dateOfBirth]/[MemberDto.nationality]
     * -- the two beneficial-owner fields a Transparenzregister (§20 GwG) entry requires beyond the
     * address fields [updateMemberAddress] already covers (see
     * `network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto`). Same self-or-privileged
     * authorization as [updateMemberAddress]. Both fields are nullable and passed together; passing
     * `null` for a field clears it. Throws [ForbiddenException] if the caller is neither the target
     * member nor privileged, [NotFoundException] if `memberId` does not resolve to an existing
     * member.
     */
    suspend fun updateMemberBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto

    /**
     * Welle V1.2.12 -- the privileged member roster read. BOARD/ADMIN only (`isPrivileged`), never
     * reachable by a plain MEMBER -- unlike [listMembers], this returns email/role/anonymization
     * state, real PII a picker must never expose. Server-side pagination/search/status-filter
     * (see [MemberAdminQuery]) -- with 407 CSV-imported rows (`MemberCsvImport`, V1.2.11) plus every
     * organically created member, shipping the full roster to the client and filtering there does
     * not scale and would defeat the whole point of a searchable admin view.
     *
     * [MemberAdminQuery.limit]/[MemberAdminQuery.offset]/[MemberAdminQuery.search] are re-clamped
     * server-side ([MemberAdminQuery.MAX_LIMIT]/[MemberAdminQuery.MAX_SEARCH_LENGTH]) -- never
     * trust a client-supplied limit/offset/search length directly into a query. Throws
     * [ForbiddenException] if the caller is not privileged.
     */
    suspend fun listMembersForAdministration(query: MemberAdminQuery): MemberAdminPageDto

    /**
     * Welle V1.2.12 -- the ADMIN/BOARD editor's "Stammdaten" section: name + email, the two fields
     * every member row always has regardless of whether it has a login `account` (see
     * [MemberAdminRowDto.role] KDoc). BOARD/ADMIN only (`isPrivileged`) -- unlike
     * [updateMemberAddress]/[updateMemberBeneficialOwnerData], this is never self-service; a plain
     * member edits their own name/email nowhere in this codebase today.
     *
     * `displayName` is trimmed, rejected if blank or over the `VARCHAR(200)` column width.
     * `email` is trimmed/lowercased, rejected if malformed, over the `VARCHAR(320)` column width
     * (throws [MemberEmailTooLongException] -- see that exception's own KDoc for why a distinct
     * TYPE, not a message, is the only way a client can tell a length problem apart from a
     * duplicate-address conflict) or already used by a DIFFERENT member -- throws
     * [MemberEmailInUseException] for that case specifically (never a generic [ConflictException] --
     * same "distinct type" reasoning). Sessions are revoked
     * ([network.lapis.cloud.server.security.SessionStore.revokeAllForMember] on the server side) if
     * and only if the email actually changed -- the email is the login identifier; a bare name
     * correction has no such consequence. When the email DOES change, `emailVerifiedAt` is also
     * reset to `null` (a prior FRIEND self-registration verification of the OLD address says nothing
     * about ownership of the NEW one) and any outstanding email-verification token for this member
     * is invalidated -- and, ONLY when the target's status is [MemberStatus.FRIEND], a FRESH
     * verification token is minted and emailed to the NEW address (the one status
     * `MembershipGuards` actually gates on `emailVerifiedAt`; this call therefore has an OUTBOUND
     * EMAIL SIDE EFFECT for a FRIEND target's core-data correction). Throws [ForbiddenException] if
     * the caller is not privileged, [NotFoundException] if `memberId` does not resolve,
     * [ConflictException] if the target member has been DSGVO-anonymized.
     */
    suspend fun updateMemberCoreData(
        memberId: String,
        displayName: String,
        email: String,
    ): MemberAdminRowDto

    /**
     * Welle V1.2.12 -- the ADMIN/BOARD editor's "Status" section, the ONLY write path for the
     * administratively managed status quadrant `network.lapis.cloud.shared.domain
     * .MemberStatusTransitions.ADMINISTRATIVELY_MANAGED` (ACTIVE/WITHDRAWN/DONOR/DECEASED --
     * exactly the four statuses `MemberCsvImport`'s 407 rows and every organic member can be in).
     * BOARD/ADMIN only, with two further restrictions: a self-status-change is always
     * [ForbiddenException] (structurally the same posture as [updateMemberRole]'s self-block --
     * status escalation/de-escalation of one's OWN row must never be a privileged self-service
     * action), and leaving DECEASED (a data-correction, not a lifecycle event -- see
     * `network.lapis.cloud.shared.domain.MemberStatusTransitions.requiresAdmin`) requires ADMIN
     * specifically, not just BOARD.
     *
     * [reason] is always required (3-1000 characters, trimmed) and is recorded ONLY in the audit
     * trail's `after` snapshot (`network.lapis.cloud.shared.domain.MemberChangeSnapshot.reason`)
     * -- never in `member.rejection_reason`, a column that belongs to a structurally different
     * event (see that field's own KDoc).
     *
     * Side effects, all in the same transaction as the status flip, run BEFORE the audit entry and
     * mirror `network.lapis.cloud.server.rpc.RegistrationService.leaveMembership`'s established
     * shape exactly:
     * - a target status in `MemberStatusSets.LOGIN_BLOCKED` revokes every live session for the
     *   member AFTER commit (`SessionStore.resolveCurrentMember` does not itself re-check
     *   `LOGIN_BLOCKED` per call -- session revocation is the only thing that actually ends an
     *   already-established session before its 8h TTL expires);
     * - a target status in `MemberStatusSets.MEMBERSHIP_ENDED` (WITHDRAWN/DECEASED) additionally
     *   ends every open committee seat and revokes every ACTIVE SEPA mandate (the SAME shared
     *   function `network.lapis.cloud.server.payment.sepa.SepaBatchPoller` itself calls, see
     *   `network.lapis.cloud.server.payment.sepa.MembershipEndedMandateRevocation` KDoc -- there is
     *   deliberately no second implementation and no time window between this status change and
     *   the poller's own next tick in which a mandate could be used inconsistently with the new
     *   status).
     *
     * A no-op call (`newStatus == from`) returns the current row unchanged -- no audit entry, no
     * session revocation, no mandate/committee side effect; an idempotent call must never have a
     * side effect just because it was called again. Throws [ForbiddenException] if the caller is
     * not privileged, targets themselves, or (leaving DECEASED) is not ADMIN; [NotFoundException]
     * if `memberId` does not resolve; [ConflictException] if the transition is not in
     * `MemberStatusTransitions.allowedTargets(from)`, `reason` is blank/too long, or the target
     * member has been DSGVO-anonymized.
     */
    suspend fun updateMemberStatus(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
    ): MemberAdminRowDto

    /**
     * Welle V1.2.12 -- the ADMIN/BOARD editor's "Rolle" section. **ADMIN-exclusive for EVERY role
     * change, including a downgrade** -- stricter than
     * `network.lapis.cloud.shared.rpc.IRegistrationService.createMemberDirect`, which only gates
     * the escalated-role grant (ADMIN_ONLY only when creating a BOARD/TREASURER/ADMIN account,
     * because a brand-new account cannot take anything away). Here a BOARD caller could otherwise
     * degrade an existing ADMIN's role -- rights REMOVAL is exactly as privileged an event as
     * rights GRANT, so `current.requireRole(AccountRole.ADMIN)` applies unconditionally, before any
     * other check.
     *
     * A self-role-change is always [ForbiddenException] (an ADMIN cannot demote or re-confirm
     * their own role through this call). Throws [MemberHasNoAccountException] -- never a plain
     * [ConflictException], same reasoning as [MemberEmailInUseException]'s own KDoc -- if the
     * target member has no `account` row at all (see [MemberAdminRowDto.role] KDoc); throws
     * [LastAdminException] if this change would remove the last remaining ADMIN account (race-safe
     * against two ADMINs concurrently demoting each other -- see the server implementation's own
     * KDoc "Letzter-Admin-Schutz" for the exact race this closes).
     *
     * **Deliberately does NOT invalidate the target's existing sessions.**
     * `network.lapis.cloud.server.security.SessionStore.resolve()` re-reads the member's CURRENT
     * role from the database on every single request (no caching at all) -- a role change is
     * therefore visible to the affected account on its very next request, with no re-login
     * required. This is not an oversight; do not "fix" it by adding a revocation call here.
     *
     * A no-op call (`newRole == currentRole`) returns the current row unchanged -- no audit entry.
     * Throws [ForbiddenException] if the caller is not ADMIN or targets themselves,
     * [NotFoundException] if `memberId` does not resolve, [ConflictException] if the target member
     * has been DSGVO-anonymized.
     */
    suspend fun updateMemberRole(
        memberId: String,
        newRole: AccountRole,
    ): MemberAdminRowDto

    /**
     * Welle V1.2.13 -- the ADMIN/BOARD editor's "Konto anlegen" section: creates a LOGIN ACCOUNT for
     * an ALREADY EXISTING member row. Pure `account` insert -- never a `member` insert, never a
     * `member` update (status, joinedAt, displayName, email all stay untouched).
     *
     * **Closes a structural gap**, not a convenience: before this wave there was literally NO path
     * from "member row exists, no account" to "member row exists, has account". Every one of the 407
     * rows `network.lapis.cloud.server.bootstrap.MemberCsvImport` (V1.2.11) created is in that state.
     * [IRegistrationService.createMemberDirect] cannot be used -- it ALWAYS inserts a NEW member and
     * rejects an already-taken e-mail with a conflict; `registerApplication`/`registerFriend` are
     * silent no-ops on an already-taken address by deliberate anti-enumeration design. The only
     * remaining route was a manual `INSERT INTO account` against the production database.
     *
     * **ADMIN-exclusive, unconditionally, checked before any existence/state check** -- the same
     * posture and the same reasoning as [updateMemberRole] (see its KDoc): granting ACCESS AT ALL is
     * structurally an initial role assignment with the identical consequence, so the weaker
     * escalated-role-only gate `IRegistrationService.createMemberDirect` applies to a BRAND-NEW
     * account is deliberately NOT reused here.
     *
     * [temporaryPassword] is validated by `network.lapis.cloud.server.security.PasswordPolicy.validate`
     * against the member's e-mail **as stored in the database**, never against a client-supplied
     * address (throws [WeakPasswordException]). It is **not e-mailed anywhere** -- the operator hands
     * it over personally and the member changes it via `IAuthService.changePassword`. An invitation /
     * set-your-own-password link is deliberately out of scope for this wave (it needs delivery
     * diagnostics the enumeration-hardened `/api/auth/password-reset/request` endpoint deliberately
     * does not provide).
     *
     * [role] is chosen at creation time, in this same call -- a two-step "create a MEMBER account,
     * then change its role" would be a mode with no user-visible justification. The audit trail keeps
     * the two facts separable regardless: the entry is `action = CREATE` (never `UPDATE`) with
     * `before.role = null` -> `after.role = <role>`, a sentence no [updateMemberRole] entry can imitate.
     *
     * Rejected with [ConflictException] if the target member is DSGVO-anonymized (an anonymized member
     * has NO account row -- `FoundationPersonalData.erase` hard-deletes it -- so without this check
     * this RPC would be the one way to give an erased person a working login again) or has
     * [MemberStatus.DECEASED] status. **DECEASED is the only status blocked**; DONOR/WITHDRAWN/
     * REJECTED are deliberately allowed -- `MemberStatusSets.LOGIN_BLOCKED` remains the single,
     * central login policy and keeps such an account entirely inert until an administrative status
     * change (e.g. DONOR -> ACTIVE, see `MemberStatusTransitions.ADMINISTRATIVELY_MANAGED`) makes it
     * usable. DECEASED is excluded because `/api/auth/password-reset/request` does NOT consult
     * `LOGIN_BLOCKED` (it only requires an `account` row for the address): an account would make a
     * deceased person's mailbox -- in practice often a relative's -- a valid recipient of
     * password-reset mail. Correcting an erroneously recorded death is the documented route and is
     * available to ADMIN in the very same editor dialog, one section higher.
     *
     * Throws [MemberAlreadyHasAccountException] if an `account` row already exists for this member
     * (including when an ADMIN targets their own member id -- the caller by definition has one),
     * [ForbiddenException] if the caller is not ADMIN, [NotFoundException] if `memberId` does not
     * resolve. Deliberately does NOT revoke sessions -- there is no session to revoke for an account
     * that did not exist a moment ago.
     */
    suspend fun grantMemberAccount(
        memberId: String,
        temporaryPassword: String,
        role: AccountRole,
    ): MemberAdminRowDto
}
