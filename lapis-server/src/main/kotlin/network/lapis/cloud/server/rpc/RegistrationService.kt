package network.lapis.cloud.server.rpc
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.NoOpFriendVerificationMailer
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.FriendTermsDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipAgreementDto
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val REGISTRATION_BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private val ESCALATED_ROLES = arrayOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)

/** [MemberTable.displayName] is `VARCHAR(200)` -- reject an overlong [FriendRegistrationInput.displayName] client-side AND server-side rather than letting Postgres throw. */
private const val MAX_DISPLAY_NAME_LENGTH = 200

/**
 * V0.7.2 Beitritts-/Registrierungs-Workflow -- see [IRegistrationService] KDoc for the full
 * fachlich model.
 *
 * **Silence-is-approval does NOT apply here, by deliberate, documented decision.**
 * [ICrowdfundingService][network.lapis.cloud.shared.rpc.ICrowdfundingService]'s
 * `submitProject`/[CrowdfundingWeightDecay] gives a crowdfunding project a 14-day auto-approval
 * clock if the board never explicitly decides. Membership admission is NOT given the same
 * treatment: admitting a new member into a private-law contract (Satzung + membership rights --
 * voting, LTR participation, data access) is a more consequential, harder-to-undo decision than
 * approving a crowdfunding listing, and this codebase's own concept explicitly frames admission
 * as requiring the board's actual will, not its silence. [approveApplication]/[rejectApplication]
 * therefore have NO auto-approval fallback: an [MemberStatus.APPLICATION] application stays pending
 * indefinitely until a BOARD/ADMIN account actually decides it.
 *
 * **Concurrency: row-lock + compare-and-swap, IDENTICAL contract to
 * [CrowdfundingService.approveProject]/`rejectProject`.** [requireApplicationRow] is called with
 * `forUpdate = true`, taking a row-level lock on the applicant BEFORE re-reading its status -- so
 * a second, concurrent board decision on the SAME applicant (one caller approving while another
 * rejects) blocks until the first commits, then re-reads the now-decided status and fails the
 * `APPLICATION` check instead of silently racing. The `UPDATE` itself is additionally a
 * compare-and-swap (`status eq APPLICATION` in the WHERE clause, checked-for-zero afterwards) as
 * defense in depth against the same lost-update.
 *
 * **Concurrent-duplicate-registration race (V0.13.1).** [registerApplication]/[registerFriend]'s
 * own `alreadyExists` pre-check (see each method's KDoc "account-enumeration hardening") is racy
 * under concurrency on its own, same "pre-check is racy, the DB-level UNIQUE is the real backstop"
 * shape [AccountingService.createLedgerAccount]/[PoliticianService.grantPoliticianStatus] already
 * establish for THEIR OWN first-write races: two simultaneous requests with the SAME email can both
 * observe `alreadyExists == false` before either commits, and the loser's `MemberTable.insert` then
 * violates the table's `UNIQUE(email)` constraint (`V1__baseline.sql` line 101) instead of the
 * pre-check catching it. Both methods catch the resulting [ExposedSQLException] around their own
 * insert sequence and treat it EXACTLY like the synchronous `alreadyExists` branch -- a silent
 * no-op, not a rethrown error -- because the winner of the race already created the account in full;
 * surfacing a 500 (or any different response) to the loser would both be a wrong error AND reopen
 * the very enumeration-hardening timing/response-shape guarantee this class's KDoc "account-
 * enumeration hardening" documents. No retry is needed (unlike
 * [PoliticianService.grantPoliticianStatus]'s idempotent-upsert retry) because there is nothing left
 * for the loser to converge to -- the winner's row already IS the final state. Each try block wraps
 * ALL of that path's inserts (not just the first), because a Postgres transaction is aborted after
 * the FIRST failing statement -- any further statement on the same connection would itself throw
 * (a different, unrelated error) rather than silently no-op, so the whole insert sequence must share
 * one catch, exactly mirroring how [ElectionService.castElectionBallot]'s own multi-insert
 * ballot-casting path wraps its whole insert sequence in one try block for the identical reason.
 */
class RegistrationService(
    private val call: ApplicationCall,
    private val registrationRateLimiter: LoginRateLimiter,
    /**
     * V0.11.0 FRIEND self-registration -- a SEPARATE [LoginRateLimiter] instance from
     * [registrationRateLimiter] on purpose: friend-signup spam must never exhaust the membership-
     * application budget (or vice versa). Failure-window limiter, same shape/idiom as
     * [registrationRateLimiter].
     */
    private val friendRegistrationRateLimiter: LoginRateLimiter,
    /**
     * V0.11.0 -- hard per-IP request-rate cap on top of [friendRegistrationRateLimiter].
     * [FederationInboxRateLimiter] counts EVERY request (not just failures) and is the right tool
     * for an open, unauthenticated, spam-prone endpoint -- same reasoning
     * `POST /federation/oidc/register` already applies.
     */
    private val friendSignupIpRateLimiter: FederationInboxRateLimiter,
    private val friendRegistrationConfig: FriendRegistrationConfig = FriendRegistrationConfig.load(),
    private val friendVerificationMailer: FriendVerificationMailer = NoOpFriendVerificationMailer(),
) : IRegistrationService {
    override suspend fun getMembershipAgreement(): MembershipAgreementDto =
        MembershipAgreementDto(
            version = MembershipAgreementDisclaimer.VERSION,
            text = MembershipAgreementDisclaimer.TEXT,
            sha256 = MembershipAgreementDisclaimer.SHA256,
        )

    /**
     * Account-enumeration hardening (a deliberate extension beyond what login/password-reset
     * strictly require, see [IRegistrationService.registerApplication] KDoc): a duplicate email
     * gets the IDENTICAL success response as a genuinely new application, no row created, no
     * distinguishing error. Rate-limited by BOTH normalized email and client IP, same
     * `checkAllowed`/`recordFailure` pattern
     * [network.lapis.cloud.server.routes.registerAuthRoutes]'s login endpoint already establishes
     * (reusing the same [LoginRateLimiter] class, a fresh instance for this endpoint).
     */
    override suspend fun registerApplication(input: RegistrationInput) {
        val normalizedEmail = input.email.trim().lowercase()
        val emailKey = "email:$normalizedEmail"
        val ipKey = "ip:${call.request.origin.remoteHost}"
        if (!registrationRateLimiter.checkAllowed(emailKey) || !registrationRateLimiter.checkAllowed(ipKey)) {
            throw ConflictException("Too many registration attempts -- try again later")
        }
        registrationRateLimiter.recordFailure(emailKey)
        registrationRateLimiter.recordFailure(ipKey)

        if (!MembershipAgreementDisclaimer.matches(version = input.agreementVersion, sha256 = input.agreementSha256)) {
            throw ConflictException(
                "agreementVersion/agreementSha256 do not match the current MembershipAgreementDisclaimer -- " +
                    "call getMembershipAgreement again and submit its CURRENT version/sha256 unmodified",
            )
        }
        if (input.displayName.isBlank()) throw ConflictException("displayName must not be blank")
        PasswordPolicy.validate(newPassword = input.password, email = normalizedEmail)

        val now = nowLocalDateTime()
        // Timing-oracle fix (same shape as the FRIEND-wave security-audit F1 finding, see
        // registerFriend KDoc): hash the password BEFORE the transaction, unconditionally, instead
        // of inside the "new member" branch below. bcrypt (PasswordHasher.hash, ~250ms at
        // BCRYPT_COST) previously ran ONLY on the newly-created-application path -- the duplicate-
        // email no-op below returned after a single, sub-millisecond SELECT COUNT. Status code and
        // body are identical either way, but the ~250ms/~1ms response-time gap was itself a side
        // channel that let an attacker enumerate this political party's membership-applicant roster
        // via latency alone, without ever looking at the response body.
        val passwordHash = PasswordHasher.hash(input.password)
        transaction {
            val alreadyExists = MemberTable.selectAll().where { MemberTable.email.lowerCase() eq normalizedEmail }.count() > 0
            // See class/interface KDoc "account-enumeration hardening" -- silent no-op, identical
            // response either way.
            if (alreadyExists) return@transaction

            val memberId = Uuid.random()
            try {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = input.displayName
                    it[email] = normalizedEmail
                    it[status] = MemberStatus.APPLICATION
                    it[joinedAt] = now.date
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                    it[AccountTable.passwordHash] = passwordHash
                }
                MembershipAgreementAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[MembershipAgreementAcknowledgmentTable.memberId] = memberId
                    it[acknowledgedAt] = now
                    it[agreementVersion] = input.agreementVersion
                    it[agreementSha256] = input.agreementSha256
                }
            } catch (e: ExposedSQLException) {
                // Concurrent-duplicate-registration race -- see class KDoc "Concurrent-duplicate-
                // registration race" for the full reasoning. The ONLY constraint that can plausibly
                // fire for a freshly-`Uuid.random()`-minted memberId inserted first into MemberTable
                // (whose sole UNIQUE constraint besides its own primary key is `email`) and then
                // referenced-but-never-duplicated by AccountTable/MembershipAgreementAcknowledgmentTable
                // is MemberTable's `UNIQUE(email)` -- so, per this codebase's established "narrowly-
                // scoped try block, no further discrimination" idiom (see
                // AccountingService.createLedgerAccount/PoliticianService.grantPoliticianStatus/
                // ElectionService.castElectionBallot), a caught violation here always means "someone
                // else won the race for this email". Silent no-op, NOT a rethrow -- same response as
                // the synchronous alreadyExists branch above.
            }
        }
    }

    override suspend fun listPendingApplications(): List<MemberDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*REGISTRATION_BOARD_ROLES)
        return transaction {
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.status eq MemberStatus.APPLICATION }
                .orderBy(MemberTable.joinedAt)
                .map { it.toMemberDto() }
        }
    }

    override suspend fun approveApplication(memberId: String): MemberDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*REGISTRATION_BOARD_ROLES)
        val targetId = memberId.toMemberUuidOrThrow()
        val now = nowLocalDateTime()
        return transaction {
            requireApplicationRow(id = targetId, forUpdate = true)
            val updated =
                MemberTable.update({
                    (MemberTable.id eq targetId) and (MemberTable.status eq MemberStatus.APPLICATION)
                }) {
                    it[status] = MemberStatus.ACTIVE
                    it[reviewedBy] = current.memberId
                    it[reviewedAt] = now
                }
            if (updated == 0) {
                throw ConflictException("Application $memberId was concurrently decided -- retry")
            }
            loadMember(targetId)
        }
    }

    /**
     * BOARD/ADMIN-only rejection of an [MemberStatus.APPLICATION] applicant -- see
     * [IRegistrationService.rejectApplication] KDoc. Every live session the applicant already
     * established (while still APPLICATION) is revoked after the transaction commits, mirroring
     * [leaveMembership]'s "revoke after commit, outside the lock" placement -- once REJECTED, the
     * applicant must not remain logged in anywhere. Closes the session-hygiene gap the V0.7.2
     * ANTRAG-membership-gate audit (commit 5082d55) found and deliberately deferred; complementary
     * to, not a replacement for, `AuthRoutes.kt`'s login gate, which independently blocks a NEW login
     * for a REJECTED account but does nothing about a session that already existed before this
     * decision.
     *
     * V0.11.0: a friend-originated application (`MemberTable.friendSince != null`) falls back to
     * [MemberStatus.FRIEND] rather than [MemberStatus.REJECTED] -- see [MemberDto.friendSince] KDoc
     * "load-bearing". Rejecting a plain [MemberStatus.APPLICATION] that never had a friend account
     * (`friendSince == null`) is unaffected and still lands on [MemberStatus.REJECTED] exactly as
     * before. Sessions are still revoked either way -- see class KDoc.
     *
     * V0.13.1 "stale roster" fix: also ends every open [network.lapis.cloud.server.db.generated
     * .CommitteeMembershipTable] row the applicant holds -- see [endAllOpenCommitteeMembershipsForMember]
     * KDoc. Runs for BOTH fallback branches (REJECTED and the FRIEND fallback above) -- an
     * APPLICATION applicant is not expected to already hold a Committee seat, but the cleanup is
     * applied defensively either way, same posture the FRIEND-fallback branch itself already takes
     * toward an unusual prior state.
     */
    override suspend fun rejectApplication(
        memberId: String,
        reason: String,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*REGISTRATION_BOARD_ROLES)
        if (reason.isBlank()) throw ConflictException("rejectApplication requires a non-blank reason")
        val targetId = memberId.toMemberUuidOrThrow()
        val now = nowLocalDateTime()
        val result =
            transaction {
                val applicationRow = requireApplicationRow(id = targetId, forUpdate = true)
                val fallbackStatus =
                    if (applicationRow[MemberTable.friendSince] != null) MemberStatus.FRIEND else MemberStatus.REJECTED
                val updated =
                    MemberTable.update({
                        (MemberTable.id eq targetId) and (MemberTable.status eq MemberStatus.APPLICATION)
                    }) {
                        it[status] = fallbackStatus
                        it[rejectionReason] = reason
                        it[reviewedBy] = current.memberId
                        it[reviewedAt] = now
                    }
                if (updated == 0) {
                    throw ConflictException("Application $memberId was concurrently decided -- retry")
                }
                // See KDoc "stale roster" fix -- same transaction as the status flip above, so a
                // rejected applicant can never be observed still seated in a Committee.
                endAllOpenCommitteeMembershipsForMember(memberId = targetId, until = now.date, current = current)
                loadMember(targetId)
            }
        SessionStore.revokeAllForMember(memberId = targetId)
        return result
    }

    override suspend fun createMemberDirect(input: AdminCreateMemberInput): MemberDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*REGISTRATION_BOARD_ROLES)
        // ADMIN_ONLY for escalated roles -- same distinction
        // network.lapis.cloud.server.security.canAccessDocumentAtLevel already makes for
        // DocumentAccessLevel.ADMIN_ONLY vs. BOARD_ONLY, applied here to close an obvious
        // privilege-escalation path (a BOARD account minting a new ADMIN account).
        if (input.role in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)
        if (input.displayName.isBlank()) throw ConflictException("displayName must not be blank")
        val normalizedEmail = input.email.trim().lowercase()
        PasswordPolicy.validate(newPassword = input.temporaryPassword, email = normalizedEmail)

        val now = nowLocalDateTime()
        return transaction {
            val alreadyExists = MemberTable.selectAll().where { MemberTable.email.lowerCase() eq normalizedEmail }.count() > 0
            if (alreadyExists) throw ConflictException("A member with this email already exists")

            val memberId = Uuid.random()
            MemberTable.insert {
                it[id] = memberId
                it[displayName] = input.displayName
                it[email] = normalizedEmail
                it[status] = MemberStatus.ACTIVE
                it[joinedAt] = now.date
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[id] = Uuid.random()
                it[AccountTable.memberId] = memberId
                it[role] = input.role
                it[passwordHash] = PasswordHasher.hash(input.temporaryPassword)
            }
            loadMember(memberId)
        }
    }

    /**
     * Member-initiated, no board approval -- see [IRegistrationService.leaveMembership] KDoc.
     * Every one of the caller's live sessions is revoked (not just OTHER sessions, unlike
     * [AuthService.changePassword]) -- once WITHDRAWN, the former member must not remain
     * logged in anywhere.
     *
     * V0.13.1 "stale roster" fix: also ends every open [network.lapis.cloud.server.db.generated
     * .CommitteeMembershipTable] row the leaving member holds -- see
     * [endAllOpenCommitteeMembershipsForMember] KDoc. Without this, a member who left via this
     * self-service path kept showing up as an active Committee member in
     * [network.lapis.cloud.server.rpc.GovernanceService.listCommitteeMembers]`(activeOnly=true)`
     * despite no longer being a member at all.
     */
    override suspend fun leaveMembership(): MemberDto {
        val current = resolveCurrentMember(call)
        val now = nowLocalDateTime()
        val result =
            transaction {
                val updated =
                    MemberTable.update({
                        (MemberTable.id eq current.memberId) and (MemberTable.status eq MemberStatus.ACTIVE)
                    }) {
                        it[status] = MemberStatus.WITHDRAWN
                    }
                if (updated == 0) {
                    throw ConflictException("Not an active member -- already left, never approved, or rejected")
                }
                // See KDoc "stale roster" fix -- same transaction as the status flip above, so a
                // withdrawn member can never be observed still seated in a Committee.
                endAllOpenCommitteeMembershipsForMember(memberId = current.memberId, until = now.date, current = current)
                loadMember(current.memberId)
            }
        SessionStore.revokeAllForMember(memberId = current.memberId)
        return result
    }

    override suspend fun getFriendTerms(): FriendTermsDto =
        FriendTermsDto(
            version = FriendTermsDisclaimer.VERSION,
            text = FriendTermsDisclaimer.TEXT,
            sha256 = FriendTermsDisclaimer.SHA256,
        )

    /**
     * V0.11.0 FRIEND self-registration -- see [IRegistrationService.registerFriend] KDoc. Mirrors
     * [registerApplication]'s rate-limiting/enumeration-hardening shape closely, on its OWN limiter
     * instances (see constructor KDoc), plus a global account cap ([FriendRegistrationConfig
     * .maxFriendAccounts]) [registerApplication] has no equivalent of (an applicant needs a board
     * to act; a friend needs nothing but this endpoint, so the unbounded-growth risk is real here in
     * a way it structurally isn't there).
     */
    override suspend fun registerFriend(input: FriendRegistrationInput) {
        // 1. Hard per-IP request-rate cap FIRST -- cheapest possible rejection for the highest-
        // volume abuse shape (see FederationInboxRateLimiter KDoc "counts EVERY request").
        val ipKey = "ip:${call.request.origin.remoteHost}"
        if (!friendSignupIpRateLimiter.checkAndRecord(ipKey)) {
            throw ConflictException("Too many registration attempts -- try again later")
        }

        val normalizedEmail = input.email.trim().lowercase()
        val emailKey = "email:$normalizedEmail"
        // 2. Failure-window limiter -- unconditional record idiom, same as registerApplication.
        if (!friendRegistrationRateLimiter.checkAllowed(emailKey) || !friendRegistrationRateLimiter.checkAllowed(ipKey)) {
            throw ConflictException("Too many registration attempts -- try again later")
        }
        friendRegistrationRateLimiter.recordFailure(emailKey)
        friendRegistrationRateLimiter.recordFailure(ipKey)

        if (!FriendTermsDisclaimer.matches(version = input.termsVersion, sha256 = input.termsSha256)) {
            throw ConflictException(
                "termsVersion/termsSha256 do not match the current FriendTermsDisclaimer -- " +
                    "call getFriendTerms again and submit its CURRENT version/sha256 unmodified",
            )
        }
        if (input.displayName.isBlank()) throw ConflictException("displayName must not be blank")
        if (input.displayName.length > MAX_DISPLAY_NAME_LENGTH) {
            throw ConflictException("displayName must be at most $MAX_DISPLAY_NAME_LENGTH characters")
        }
        PasswordPolicy.validate(newPassword = input.password, email = normalizedEmail)

        val now = nowLocalDateTime()
        // Security-audit F1 fix: hash the password BEFORE the transaction, unconditionally, instead
        // of inside the "new member" branch below. bcrypt (PasswordHasher.hash, ~250ms at
        // BCRYPT_COST) previously ran ONLY on the newly-created-account path -- the duplicate-email
        // no-op below returned after a single, sub-millisecond SELECT COUNT. Status code and body are
        // identical either way, but the ~250ms/~1ms response-time gap was itself a side channel that
        // let an attacker enumerate this political party's FRIEND roster via latency alone, without
        // ever looking at the response body. Computing the hash here means BOTH paths -- duplicate
        // and new -- now pay the same dominant bcrypt cost before the transaction even starts.
        val passwordHash = PasswordHasher.hash(input.password)
        var newMemberId: Uuid? = null
        transaction {
            // Security-audit F2 fix: the global FRIEND-account cap is now checked BEFORE the
            // duplicate-email check (previously the reverse order). With duplicate-first, once the
            // cap was reached an existing email kept returning the silent-success no-op while a new
            // email started throwing ConflictException -- a binary, timing-independent oracle letting
            // an attacker probe "does email X already have a FRIEND account?" by first exhausting the
            // cap with throwaway registrations. Checking the cap first makes the ConflictException
            // fire identically for a duplicate OR a brand-new email once the cap is reached, closing
            // that oracle. Checked INSIDE the transaction so a burst of concurrent registrations near
            // the cap cannot all pass the check and all insert (a benign, self-correcting overshoot by
            // at most `concurrent request count` is acceptable for this soft cap; a hard guarantee
            // would need a locking counter row, not worth the complexity for a DoS-bound, not a
            // correctness-bound, limit).
            val currentFriendCount = MemberTable.selectAll().where { MemberTable.status eq MemberStatus.FRIEND }.count()
            if (currentFriendCount >= friendRegistrationConfig.maxFriendAccounts) {
                throw ConflictException("Too many FRIEND accounts already registered -- try again later")
            }

            // See class/interface KDoc "account-enumeration hardening" -- silent no-op, identical
            // response either way, same posture as registerApplication.
            val alreadyExists = MemberTable.selectAll().where { MemberTable.email.lowerCase() eq normalizedEmail }.count() > 0
            if (alreadyExists) return@transaction

            val memberId = Uuid.random()
            try {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = input.displayName
                    it[email] = normalizedEmail
                    it[status] = MemberStatus.FRIEND
                    it[joinedAt] = now.date
                    it[membershipTierId] = null
                    it[friendSince] = now.date
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                    it[AccountTable.passwordHash] = passwordHash
                }
                // Deliberately NOT MembershipAgreementAcknowledgmentTable -- a FRIEND has not accepted
                // the Satzung, see FriendTermsAcknowledgmentTable KDoc (23-registration.kuml.kts).
                FriendTermsAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[FriendTermsAcknowledgmentTable.memberId] = memberId
                    it[acknowledgedAt] = now
                    it[termsVersion] = input.termsVersion
                    it[termsSha256] = input.termsSha256
                }
                newMemberId = memberId
            } catch (e: ExposedSQLException) {
                // Concurrent-duplicate-registration race -- see class KDoc "Concurrent-duplicate-
                // registration race". Same reasoning as registerApplication's own catch: the only
                // constraint that can fire here is MemberTable's UNIQUE(email), silent no-op, NOT a
                // rethrow. newMemberId stays null, so the email-verification send below is correctly
                // skipped -- identical to the synchronous alreadyExists branch's behavior (see that
                // branch's own "createdMemberId != null" gate below).
            }
        }

        // Email verification (B6): only if a NEW row was actually created (never for the silent
        // duplicate-email no-op above -- sending a verification token there would leak, via a
        // side channel, that the email already belongs to an existing member, defeating the
        // enumeration hardening this whole method otherwise achieves).
        val createdMemberId = newMemberId
        if (createdMemberId != null) {
            val rawToken = FriendEmailVerificationTokenStore.createToken(createdMemberId)
            friendVerificationMailer.send(email = normalizedEmail, rawToken = rawToken)
        }
    }

    /**
     * V0.11.0 -- the CALLER's own `FRIEND -> APPLICATION` upgrade, see
     * [IRegistrationService.applyForMembership] KDoc. Sessions are deliberately NOT revoked (unlike
     * [rejectApplication]/[leaveMembership]) -- this is an upward transition by the member's own
     * will; forcing a re-login mid-conference would be user-hostile, and every gate re-reads status
     * per call anyway.
     */
    override suspend fun applyForMembership(
        agreementVersion: String,
        agreementSha256: String,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        if (!MembershipAgreementDisclaimer.matches(version = agreementVersion, sha256 = agreementSha256)) {
            throw ConflictException(
                "agreementVersion/agreementSha256 do not match the current MembershipAgreementDisclaimer -- " +
                    "call getMembershipAgreement again and submit its CURRENT version/sha256 unmodified",
            )
        }
        val now = nowLocalDateTime()
        return transaction {
            MemberTable
                .selectAll()
                .where { MemberTable.id eq current.memberId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Member ${current.memberId} not found")
            val updated =
                MemberTable.update({
                    (MemberTable.id eq current.memberId) and (MemberTable.status eq MemberStatus.FRIEND)
                }) {
                    it[status] = MemberStatus.APPLICATION
                }
            if (updated == 0) {
                throw ConflictException("Not a FRIEND account -- already applied, or already a member")
            }
            MembershipAgreementAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[MembershipAgreementAcknowledgmentTable.memberId] = current.memberId
                it[acknowledgedAt] = now
                it[MembershipAgreementAcknowledgmentTable.agreementVersion] = agreementVersion
                it[MembershipAgreementAcknowledgmentTable.agreementSha256] = agreementSha256
            }
            loadMember(current.memberId)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    /** [forUpdate] takes a `SELECT ... FOR UPDATE` row lock on this member before returning it -- required by [approveApplication]/[rejectApplication], see class KDoc "Concurrency". */
    private fun requireApplicationRow(
        id: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow {
        val query = MemberTable.selectAll().where { MemberTable.id eq id }
        val row = (if (forUpdate) query.forUpdate() else query).singleOrNull() ?: throw NotFoundException("Member $id not found")
        if (row[MemberTable.status] != MemberStatus.APPLICATION) {
            throw ConflictException("Member $id is not a pending application (status=${row[MemberTable.status]})")
        }
        return row
    }

    private fun loadMember(id: Uuid): MemberDto =
        (MemberTable innerJoin AccountTable)
            .selectAll()
            .where { MemberTable.id eq id }
            .single()
            .toMemberDto()

    private fun String.toMemberUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()
}
