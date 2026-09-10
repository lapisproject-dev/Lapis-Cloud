package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.AdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.payment.sepa.revokeMandatesForEndedMembership
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.TemporaryPasswordGenerator
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminPasswordAction
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeathDateRules
import network.lapis.cloud.shared.domain.DeathDateViolation
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAccessPreflightDto
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MemberStatusTransitions
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.PasswordResetMailResultDto
import network.lapis.cloud.shared.domain.TemporaryPasswordResultDto
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.LastAdminException
import network.lapis.cloud.shared.rpc.MemberAlreadyHasAccountException
import network.lapis.cloud.shared.rpc.MemberEmailInUseException
import network.lapis.cloud.shared.rpc.MemberEmailTooLongException
import network.lapis.cloud.shared.rpc.MemberHasNoAccountException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

class MemberService(
    private val call: ApplicationCall,
    /**
     * Review Runde 3 fix -- see [updateMemberCoreData]'s own "resend on address change" comment.
     * No default value on purpose, same discipline [RegistrationService]'s own
     * `friendVerificationMailer` constructor parameter KDoc documents: the compiler enforces the
     * wiring at every `MemberService(...)` call site instead of allowing a silent no-op fallback.
     */
    private val friendVerificationMailer: FriendVerificationMailer,
    /**
     * Security fix (2026-08-27, LOW) -- see [updateMemberCoreData]'s own "resend on address
     * change" comment. Without this, an ADMIN/BOARD caller looping this RPC against the SAME
     * FRIEND target with a caller-chosen address each time could mint unlimited outbound SMTP
     * sends through the organization's mail domain -- the only OTHER writer of this same
     * verification-token type, [RegistrationService.registerFriend], is guarded by three separate
     * limiters (see that class's own constructor KDoc); this call site had none. Reuses
     * [FederationInboxRateLimiter] (counts every send attempt, not just failures -- the right tool
     * here, same reasoning [RegistrationService]'s `friendSignupIpRateLimiter` KDoc documents),
     * checked/recorded under the TARGET member's key so repeatedly correcting the SAME FRIEND
     * cannot mint unlimited mails to them. No default value on purpose, same discipline
     * [friendVerificationMailer] above already establishes.
     *
     * **Security fix (2026-08-27, LOW, follow-up)** -- this used to be the ONLY limiter, checked
     * under BOTH the caller's and the target's key with an IDENTICAL cap. A legitimate BOARD
     * caller correcting many DIFFERENT FRIENDs' e-mail addresses in one sitting (e.g. after a CSV
     * import, see `MemberCsvImport`) hit the actor-side cap after a handful of corrections and
     * silently stopped minting verification mails for every subsequent target -- each of those
     * FRIENDs was left unverified with its OLD token already invalidated and NO path back to
     * verified for up to the window's duration, which can cost them
     * `requireLtrEligibleMembership`/`requireConferenceEligibleMembership` in the meantime (see
     * `updateMemberCoreData`'s own "irreversible state" comment). [memberCoreDataFriendMailActorRateLimiter]
     * now guards the actor key with its own, deliberately more generous cap -- abuse against a
     * SINGLE target is still capped by this property's tighter per-target limit regardless of how
     * generous the actor-side cap is.
     */
    private val memberCoreDataFriendMailRateLimiter: FederationInboxRateLimiter,
    /**
     * Security fix (2026-08-27, LOW, follow-up) -- see [memberCoreDataFriendMailRateLimiter]'s own
     * KDoc for why this needs to be a SEPARATE instance with a more generous cap rather than the
     * SAME instance/cap checked under a second key. No default value on purpose, same discipline
     * every other rate-limiter constructor parameter on this class already establishes.
     */
    private val memberCoreDataFriendMailActorRateLimiter: FederationInboxRateLimiter,
    /**
     * Welle V1.4.9 "Admin-Passwort-Reset" -- Weg 2 triggers the SAME token-mint-and-mail mechanism
     * as `/api/auth/password-reset/request`, so it reuses the SAME [PasswordResetMailer] instance
     * that endpoint uses (wired once in `Application.kt`), never a second one. No default value on
     * purpose, same discipline [friendVerificationMailer] already establishes.
     */
    private val passwordResetMailer: PasswordResetMailer,
    /** Welle V1.4.9 -- Weg 1's password-free security notice to the target member. */
    private val adminPasswordResetNotificationMailer: AdminPasswordResetNotificationMailer,
    /**
     * Welle V1.4.9 -- the ONLY reliable SMTP truth. [SmtpConfigState.NotConfigured] is the one
     * state read here; `SmtpPasswordResetMailer.send()`/`SmtpAdminPasswordResetNotificationMailer
     * .send()` themselves ALWAYS return `DeliveryStatus.SENT` regardless of whether SMTP is
     * actually configured (see [PasswordResetMailer.send] KDoc "Fire-and-forget"), and
     * [network.lapis.cloud.server.mail.MailDispatcher.enqueue] never throws either -- neither mailer
     * CAN give this honest answer on its own. Without this parameter, the UI would report
     * "successfully sent" for a mail that never reaches any inbox.
     */
    private val smtpConfigState: SmtpConfigState,
    /**
     * Welle V1.4.9 -- TARGET-side cap (3/60min, key `"member:<targetId>"`) for
     * [sendPasswordResetMailToMember]'s reset-link mail (Weg 2). Since the security-fix split below,
     * this pool is consumed ONLY by Weg 2 -- Weg 1's security notice draws from its own
     * [adminPasswordNotificationTargetRateLimiter] instead (do not let these two names/KDocs drift
     * back into implying a shared budget; that was the exact bug the split fixed). Deliberately NOT
     * the IP+email limiter `network.lapis.cloud.server.routes.AuthRoutes` uses for
     * `/api/auth/password-reset/request` -- this is an authenticated path that limiter is never
     * consulted on, and reusing it would let a shared operator IP block genuine self-service resets.
     * Pattern: [memberCoreDataFriendMailRateLimiter].
     */
    private val adminPasswordMailTargetRateLimiter: FederationInboxRateLimiter,
    /**
     * Welle V1.4.9 -- ACTOR-side cap (50/60min, key `"actor:<callerId>"`) for
     * [sendPasswordResetMailToMember] ONLY, deliberately more generous than the target-side cap
     * above. Pattern + reasoning: [memberCoreDataFriendMailActorRateLimiter] (an operator resetting
     * many DIFFERENT members' access after a data incident must not go silent after five cases; the
     * target-side cap above remains the actual anti-abuse protection).
     *
     * Security fix (Welle V1.4.9 review round, MINOR, residual/round 2) -- Weg 1's
     * [notifyMemberOfAdminPasswordReset] no longer consults this pool at all (it used to, under the
     * same `"actor:<id>"` key). That sharing was itself still exploitable even after round 1's
     * target-side split: [sendPasswordResetMailToMember]'s own actor-side check runs BEFORE its
     * existence check, so a rogue admin could burn all 50 slots here for free against
     * well-formed-but-nonexistent member ids, then find the transparency notice for a REAL
     * [setTemporaryPasswordForMember] call silently `RATE_LIMITED` with no trace of the setup. See
     * [notifyMemberOfAdminPasswordReset]'s own body comment for the full scenario and why removing
     * the check there (rather than adding yet another dedicated pool) is safe.
     */
    private val adminPasswordMailActorRateLimiter: FederationInboxRateLimiter,
    /**
     * Security fix (Welle V1.4.9 review round, MINOR) -- SEPARATE target-side pool for Weg 1's
     * password-free security notice ([notifyMemberOfAdminPasswordReset]), no longer sharing
     * [adminPasswordMailTargetRateLimiter]'s 3/60min budget with Weg 2's reset-link mail
     * ([sendPasswordResetMailToMember]). That shared budget let either an innocent support flow
     * (three "resend the link" attempts before falling back to Weg 1) or a rogue ADMIN
     * (deliberately burning the target's quota first) silence the ONE real-time signal a member
     * has that their account was administratively touched -- the notice would come back
     * `RATE_LIMITED` while the password change itself always still commits regardless. A
     * dedicated pool for the notice means a target's OWN outstanding reset-link requests can never
     * consume the budget that protects their ability to be warned. This is now the ONLY rate-limit
     * check [notifyMemberOfAdminPasswordReset] performs -- see that function's own comment for why
     * there is deliberately no actor-side check alongside it. No default value on purpose, same
     * discipline every other rate-limiter constructor parameter on this class already establishes.
     */
    private val adminPasswordNotificationTargetRateLimiter: FederationInboxRateLimiter,
) : IMemberService {
    // V1.2.11 (PdV-CSV-Import, security fix): now requires an authenticated caller -- see
    // IMemberService.listMembers KDoc for the full rationale. Only id + displayName are selected,
    // so email and role (PII / authorization-relevant) never leave the server for this call
    // regardless.
    //
    // V0.7.2: tightened to ACTIVE only -- was previously unfiltered (every member regardless of
    // status). Once self-registration (IRegistrationService.registerApplication) starts producing
    // real APPLICATION/REJECTED/WITHDRAWN rows, an unfiltered picker would list a not-yet-approved
    // applicant's, a rejected applicant's, or a departed former member's display name -- actively
    // wrong for a member picker, and for a political party, a real exposure (listing who applied/
    // was rejected/left).
    override suspend fun listMembers(): List<MemberSummaryDto> {
        resolveCurrentMember(call)
        // V1.3.1 "API-Fundament, lesend" -- delegates to MemberReads (see that object's KDoc), which
        // now also backs `/api/v1/members`; byte-identical behavior for this RPC call site.
        return transaction { MemberReads.listActiveMembers() }
    }

    override suspend fun getCurrentMember(): MemberDto {
        val current = resolveCurrentMember(call)
        return transaction {
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq current.memberId }
                .single()
                .toMemberDto()
        }
    }

    override suspend fun updateMemberAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        if (targetId != current.memberId && !current.isPrivileged) throw ForbiddenException()
        return transaction {
            val updated =
                MemberTable.update({ MemberTable.id eq targetId }) {
                    it[MemberTable.street] = street
                    it[MemberTable.postalCode] = postalCode
                    it[MemberTable.city] = city
                    it[MemberTable.country] = country
                }
            if (updated == 0) throw NotFoundException("Member $memberId not found")
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq targetId }
                .single()
                .toMemberDto()
        }
    }

    override suspend fun updateMemberBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        if (targetId != current.memberId && !current.isPrivileged) throw ForbiddenException()
        return transaction {
            val updated =
                MemberTable.update({ MemberTable.id eq targetId }) {
                    it[MemberTable.dateOfBirth] = dateOfBirth
                    it[MemberTable.nationality] = nationality
                }
            if (updated == 0) throw NotFoundException("Member $memberId not found")
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq targetId }
                .single()
                .toMemberDto()
        }
    }

    // ── Welle V1.2.12 -- Mitgliederverwaltung: vollständige Bearbeitung + privilegiertes Roster ──

    override suspend fun listMembersForAdministration(query: MemberAdminQuery): MemberAdminPageDto {
        val current = resolveCurrentMember(call)
        // Welle V1.4.4.4 review fix (MAJOR finding): widened from `!current.isPrivileged`
        // (BOARD/ADMIN) to also admit TREASURER -- see interface KDoc. `requireRole` (not
        // `isPrivileged`) precisely because this is now a THREE-role, not a two-role, gate.
        current.requireRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

        val limit = query.limit.coerceIn(1, MemberAdminQuery.MAX_LIMIT)
        val offset = query.offset.coerceAtLeast(0)
        val searchTerm =
            query.search
                ?.take(MemberAdminQuery.MAX_SEARCH_LENGTH)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }

        // Plain function -- `eq`/`like`/`and`/`or`/`inList` are all top-level functions in this
        // pinned Exposed version (the interface-member overloads are deprecated in favor of these),
        // so this predicate builder needs no special receiver scope.
        fun predicate(includeStatusFilter: Boolean): Op<Boolean> {
            var predicate: Op<Boolean> = Op.TRUE
            if (searchTerm != null) {
                val pattern = containsPattern(searchTerm)
                predicate =
                    predicate and
                    (
                        (MemberTable.displayName.lowerCase() like pattern) or
                            (MemberTable.email.lowerCase() like pattern) or
                            (MemberTable.externalReference.lowerCase() like pattern)
                    )
            }
            if (includeStatusFilter && query.statuses.isNotEmpty()) {
                predicate = predicate and (MemberTable.status inList query.statuses)
            }
            return predicate
        }

        return transaction {
            // Deterministic pagination requires a stable, two-column sort -- name/joinedAt alone is
            // not unique (two members can share a display name or a joined date), so a row could
            // otherwise be skipped or duplicated across page boundaries. id is always unique.
            val orderColumns: Array<Pair<Expression<*>, SortOrder>> =
                when (query.sort) {
                    MemberAdminSort.NAME_ASC -> arrayOf(MemberTable.displayName to SortOrder.ASC, MemberTable.id to SortOrder.ASC)
                    MemberAdminSort.NAME_DESC -> arrayOf(MemberTable.displayName to SortOrder.DESC, MemberTable.id to SortOrder.ASC)
                    MemberAdminSort.JOINED_DESC -> arrayOf(MemberTable.joinedAt to SortOrder.DESC, MemberTable.id to SortOrder.ASC)
                    MemberAdminSort.JOINED_ASC -> arrayOf(MemberTable.joinedAt to SortOrder.ASC, MemberTable.id to SortOrder.ASC)
                }

            val rows =
                adminRosterSource
                    .selectAll()
                    .where { predicate(true) }
                    .orderBy(*orderColumns)
                    .limit(limit)
                    .offset(offset.toLong())
                    .map { it.toMemberAdminRowDto(includeFamilyDetails = current.isPrivileged) }

            val totalCount =
                adminRosterSource
                    .selectAll()
                    .where { predicate(true) }
                    .count()
                    .toInt()

            // Search-only (not status-filtered) so every chip's number reflects the current SEARCH,
            // not its own selection -- see MemberAdminPageDto.statusCounts KDoc. Plain Kotlin
            // tally over an id+status projection rather than a SQL GROUP BY -- at membership-roster
            // scale (hundreds, not millions, of rows) this is simpler and no less correct, and
            // avoids introducing an unproven SQL-aggregate idiom into this codebase for a single
            // call site.
            val statusCounts =
                MemberTable
                    .select(MemberTable.status)
                    .where { predicate(false) }
                    .map { it[MemberTable.status] }
                    .groupingBy { it }
                    .eachCount()

            MemberAdminPageDto(rows = rows, totalCount = totalCount, statusCounts = statusCounts, limit = limit, offset = offset)
        }
    }

    override suspend fun updateMemberCoreData(
        memberId: String,
        displayName: String,
        email: String,
    ): MemberAdminRowDto {
        val current = resolveCurrentMember(call)
        if (!current.isPrivileged) throw ForbiddenException()
        val targetId = memberId.toMemberUuidOrThrow()

        val trimmedName = displayName.trim()
        if (trimmedName.isBlank()) throw ConflictException("displayName must not be blank")
        if (trimmedName.length > MEMBER_DISPLAY_NAME_MAX_LENGTH) {
            throw ConflictException("displayName must be at most $MEMBER_DISPLAY_NAME_MAX_LENGTH characters")
        }
        val normalizedEmail = email.trim().lowercase()
        if (!isValidMailboxAddress(normalizedEmail)) throw ConflictException("email is not a valid mailbox address")
        // MemberTable.email is VARCHAR(320) (V1__baseline.sql line 127) -- reject an overlong but
        // otherwise well-formed address client-side/server-side here, same reasoning
        // MEMBER_DISPLAY_NAME_MAX_LENGTH above already applies to displayName. Without this, an overlong
        // address passes isValidMailboxAddress (which checks syntax, not length) and the pre-check
        // below (which only tests for a DUPLICATE), then hits the column-length constraint inside
        // the try block further down -- which today reports that as "email already in use" even
        // though no address is actually duplicated.
        // Review Runde 3: a dedicated exception type, not ConflictException -- see
        // MemberEmailTooLongException's own KDoc for why (the generic client-side conflict toast
        // was actively misleading for a length problem, "refresh the view" fixes nothing here).
        if (normalizedEmail.length > MEMBER_EMAIL_MAX_LENGTH) throw MemberEmailTooLongException()

        val now = nowLocalDateTime()
        var emailChanged = false
        var targetStatus: MemberStatus? = null
        val result =
            transaction {
                val row =
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .forUpdate()
                        .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                if (row[MemberTable.anonymizedAt] != null) {
                    throw ConflictException("Member has been anonymized and can no longer be edited")
                }

                // Peer-Schutz: a BOARD caller may not edit a fellow ADMIN/BOARD/TREASURER account --
                // same escalated-role boundary network.lapis.cloud.server.security.ESCALATED_ROLES
                // already draws for RegistrationService.createMemberDirect.
                val existingRole = currentAccountRole(targetId)
                if (existingRole != null && existingRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)

                val alreadyUsedByAnother =
                    MemberTable
                        .selectAll()
                        .where { (MemberTable.email.lowerCase() eq normalizedEmail) and (MemberTable.id neq targetId) }
                        .count() > 0
                if (alreadyUsedByAnother) throw MemberEmailInUseException()

                val beforeSnapshot =
                    MemberChangeSnapshot(
                        displayNameChanged = false,
                        emailChanged = false,
                        status = row[MemberTable.status],
                        role = existingRole,
                    )
                val displayNameChanged = row[MemberTable.displayName] != trimmedName
                emailChanged = row[MemberTable.email] != normalizedEmail
                targetStatus = row[MemberTable.status]

                try {
                    MemberTable.update({ MemberTable.id eq targetId }) {
                        it[MemberTable.displayName] = trimmedName
                        it[MemberTable.email] = normalizedEmail
                        // An ADMIN/BOARD-driven correction changes WHICH mailbox this member is
                        // reachable at -- any prior FRIEND self-registration verification of the
                        // OLD address says nothing about ownership of the NEW one, so it must not
                        // keep counting. Only touched when the address actually changed (a bare
                        // name correction leaves emailVerifiedAt untouched, same "only when it
                        // actually changed" guard SessionStore.revokeAllForMember below applies).
                        if (emailChanged) it[emailVerifiedAt] = null
                    }
                } catch (e: ExposedSQLException) {
                    // Race backstop -- same two-layer uniqueness idiom
                    // RegistrationService.registerApplication/registerFriend already establish for
                    // MemberTable's UNIQUE(email): the pre-check above is racy under concurrency on
                    // its own, the DB constraint is the real backstop.
                    //
                    // Security fix (2026-08-27, INFO) -- logged BEFORE converting: this branch used
                    // to swallow `e` entirely, so any OTHER `ExposedSQLException` here (a future
                    // CHECK constraint, a deadlock abort, a connection error mid-statement) would
                    // present to the caller as the same misleading "email already in use" toast
                    // (see MemberAdminGuard's handler) while leaving zero trace in the server logs.
                    // The exception class name alone is logged (no message/stacktrace) -- carries no
                    // PII (the attempted email/name never appear in the class name).
                    logger.warn { "MemberTable.update failed in updateMemberCoreData: ${e::class.simpleName}" }
                    throw MemberEmailInUseException()
                }

                val afterSnapshot =
                    beforeSnapshot.copy(displayNameChanged = displayNameChanged, emailChanged = emailChanged)
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MEMBER,
                    entityId = targetId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                    after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                    occurredAt = now,
                )
                loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
            }
        // The email IS the login identifier -- revoke every live session only when it actually
        // changed. A bare name correction has no such consequence. Runs AFTER commit, same
        // placement RegistrationService.leaveMembership/rejectApplication already establish.
        if (emailChanged) {
            SessionStore.revokeAllForMember(memberId = targetId)
            // A verification token minted for the OLD address must not go on verifying the NEW
            // one -- see emailVerifiedAt-reset comment above. Same "AFTER commit" placement as the
            // session revocation right above; harmless no-op when no such token exists.
            FriendEmailVerificationTokenStore.invalidateAllForMember(memberId = targetId)
            // Review Runde 3 fix -- "irreversible state" finding: invalidating the old token above
            // (and the emailVerifiedAt reset inside the transaction) correctly replaces "stale
            // verification" with "no verification", but WITHOUT this, there was no path left back
            // to "verified" -- FriendEmailVerificationTokenStore.createToken was only ever called
            // from RegistrationService.registerFriend's own one-time self-registration flow, never
            // again afterwards, so a FRIEND account corrected by an ADMIN/BOARD would be
            // PERMANENTLY unable to satisfy requireLtrEligibleMembership/
            // requireConferenceEligibleMembership once LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION is
            // enabled -- fixable only by a direct DB write. Only for MemberStatus.FRIEND, mirroring
            // exactly the ONE status those two guards actually gate on emailVerifiedAt (see
            // MembershipGuards.kt) -- sending an unsolicited "please confirm your email" mail to an
            // ACTIVE member, whose membership was never conditioned on this token in the first
            // place, would just be confusing. Same runCatching-around-a-mail-send discipline
            // RegistrationService.registerFriend's own send already establishes: a misbehaving
            // mailer must never turn a successful, already-committed core-data correction into a
            // failed RPC call.
            if (targetStatus == MemberStatus.FRIEND) {
                // Security fix (2026-08-27, LOW) -- rate-limited under BOTH the caller's and the
                // target's key before minting/sending anything, see
                // [memberCoreDataFriendMailRateLimiter] KDoc for why. Both checkAndRecord calls
                // run unconditionally (no short-circuit) so cycling either side alone cannot dodge
                // the other side's cap. A rate-limited attempt is a silent no-op from the caller's
                // perspective, same "must never turn a successful, already-committed core-data
                // correction into a failed RPC call" posture the mail-failure branch below already
                // establishes -- the core-data edit itself already committed.
                //
                // Security fix (2026-08-27, LOW, follow-up) -- actor and target are now checked
                // against TWO SEPARATE limiter instances (see [memberCoreDataFriendMailActorRateLimiter]
                // KDoc), not the same shared instance/cap under two keys. A single shared cap made a
                // legitimate BOARD caller correcting many DIFFERENT FRIENDs in one sitting silently
                // stop minting verification mails after a handful of corrections -- the actor-side
                // cap is deliberately more generous, the target-side cap stays tight (the actual
                // anti-abuse protection against spamming ONE target).
                val actorAllowed = memberCoreDataFriendMailActorRateLimiter.checkAndRecord("actor:${current.memberId}")
                val targetAllowed = memberCoreDataFriendMailRateLimiter.checkAndRecord("target:$targetId")
                if (actorAllowed && targetAllowed) {
                    val rawToken = FriendEmailVerificationTokenStore.createToken(targetId)
                    runCatching { friendVerificationMailer.send(email = normalizedEmail, rawToken = rawToken) }
                        .onFailure { e -> logger.error { "friendVerificationMailer.send threw: ${e::class.simpleName}" } }
                } else {
                    logger.warn { "updateMemberCoreData friend-verification mail suppressed by rate limiter (target=$targetId)" }
                }
            }
        }
        return result
    }

    override suspend fun updateMemberStatus(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
        dateOfDeath: LocalDate?,
    ): MemberAdminRowDto {
        val current = resolveCurrentMember(call)
        if (!current.isPrivileged) throw ForbiddenException()
        val targetId = memberId.toMemberUuidOrThrow()
        // Always forbidden, regardless of role/direction -- a privileged self-status-change must
        // never be a self-service action. Checked BEFORE the reason/transition validation so a
        // self-targeting call never leaks which transitions would otherwise have been legal.
        if (targetId == current.memberId) throw ForbiddenException()

        val trimmedReason = reason.trim()
        if (trimmedReason.length < MIN_REASON_LENGTH || trimmedReason.length > MAX_REASON_LENGTH) {
            throw ConflictException("A reason is required ($MIN_REASON_LENGTH-$MAX_REASON_LENGTH characters)")
        }
        // Welle V1.4.4.5 -- a date of death only makes sense together with the DECEASED target.
        if (dateOfDeath != null && newStatus != MemberStatus.DECEASED) {
            throw ConflictException("A date of death may only be recorded together with status DECEASED")
        }

        val now = nowLocalDateTime()
        var revokeSessions = false
        val result =
            transaction {
                val row =
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .forUpdate()
                        .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                if (row[MemberTable.anonymizedAt] != null) {
                    throw ConflictException("Member has been anonymized and can no longer be edited")
                }
                val fromStatus = row[MemberTable.status]

                // Idempotent no-op: DTO back, no update, no audit entry, no side effect -- a call
                // repeated with the SAME target status must have no additional consequence.
                if (newStatus == fromStatus) {
                    return@transaction loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
                }

                val allowedTargets = MemberStatusTransitions.allowedTargets(fromStatus)
                if (newStatus !in allowedTargets) {
                    throw ConflictException("Transition from $fromStatus to $newStatus is not allowed")
                }
                // Leaving DECEASED is a data correction, not a lifecycle event -- ADMIN-exclusive.
                if (MemberStatusTransitions.requiresAdmin(fromStatus)) current.requireRole(AccountRole.ADMIN)

                // Welle V1.4.4.5 -- plausibility only matters when the target is DECEASED; a null
                // dateOfDeath is always fine (DeathDateRules.violation returns null for it too).
                if (newStatus == MemberStatus.DECEASED) {
                    requirePlausibleDeathDate(dateOfDeath = dateOfDeath, row = row, now = now)
                }

                // Security fix (2026-08-27, LOW deadlock) -- existingRole is now read from the SAME
                // id-ordered union-of-{target account} ∪ {every ADMIN account} `.forUpdate()` query
                // used below for the Letzter-Admin-Schutz check, instead of a separate single-row
                // `currentAccountRole` lock acquired beforehand. Locking Account rows in two DIFFERENT
                // orders across this method (a bare single-row lock here) and updateMemberRole (an
                // id-ordered union lock there) is a genuine lock-order inversion: two ADMINs
                // concurrently calling updateMemberStatus/updateMemberRole on each other could
                // deadlock under Postgres (T1 holds Account[Y] via the single-row lock, waits for
                // Account[X] as part of T2's ordered union; T2 holds Account[X], waits for Account[Y]
                // as part of T1's OWN ordered union once it reaches the Letzter-Admin-Schutz check
                // below -- SQLSTATE 40P01, a raw 500 instead of LastAdminException). Acquiring the
                // union query unconditionally -- exactly mirroring updateMemberRole -- makes both
                // methods contend for identical rows in identical order, which is what actually
                // prevents the deadlock (the two ADMIN/BOARD-facing endpoints share a handful of
                // ADMIN accounts at most, so locking the whole ADMIN set on every status change is
                // cheap).
                val lockedAccountRows =
                    AccountTable
                        .selectAll()
                        .where { (AccountTable.memberId eq targetId) or (AccountTable.role eq AccountRole.ADMIN) }
                        .orderBy(AccountTable.id)
                        .forUpdate()
                        .toList()
                val existingRole =
                    lockedAccountRows.singleOrNull { it[AccountTable.memberId] == targetId }?.get(AccountTable.role)
                if (existingRole != null && existingRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)

                // Letzter-Admin-Schutz, race-safe (Security fix 2026-08-27, MEDIUM) -- a status that
                // blocks login (MemberStatusSets.LOGIN_BLOCKED) revokes an ADMIN's admin capability
                // exactly as effectively as updateMemberRole's role downgrade does, but this method
                // had NO equivalent guard: two ADMINs concurrently WITHDRAWING each other each locked
                // only their OWN target member row (disjoint rows -- no serialization), so both could
                // commit and leave zero ADMIN accounts. `lockedAccountRows` above already holds the
                // union of {target account} ∪ {every ADMIN account} in ONE id-ordered `.forUpdate()`
                // lock (same rows both concurrent callers contend for, in the same order -- no
                // deadlock, genuine serialization under READ COMMITTED); re-read the other admins'
                // CURRENT member status (now safely serialized after that lock) to see whether at
                // least one non-blocked ADMIN would remain.
                if (existingRole == AccountRole.ADMIN && newStatus in MemberStatusSets.LOGIN_BLOCKED) {
                    val otherAdminMemberIds =
                        lockedAccountRows
                            .filter { it[AccountTable.role] == AccountRole.ADMIN && it[AccountTable.memberId] != targetId }
                            .map { it[AccountTable.memberId] }
                    val remainingNonBlockedAdmins =
                        if (otherAdminMemberIds.isEmpty()) {
                            0L
                        } else {
                            MemberTable
                                .selectAll()
                                .where {
                                    (MemberTable.id inList otherAdminMemberIds) and
                                        (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED)
                                }.count()
                        }
                    if (remainingNonBlockedAdmins == 0L) throw LastAdminException()
                }

                // Welle V1.4.4.5 -- § 38 BGB: the membership already ended with the death; this
                // write only records that fact. Clearing date_of_death when LEAVING DECEASED must
                // happen in the SAME update, otherwise chk_member_date_of_death_requires_status
                // (V24) fires and turns this into a raw 500.
                val previousDateOfDeath = row[MemberTable.dateOfDeath]
                MemberTable.update({ MemberTable.id eq targetId }) {
                    it[status] = newStatus
                    if (newStatus == MemberStatus.DECEASED) {
                        it[MemberTable.dateOfDeath] = dateOfDeath
                    } else if (fromStatus == MemberStatus.DECEASED) {
                        it[MemberTable.dateOfDeath] = null
                    }
                }
                val newDateOfDeath = if (newStatus == MemberStatus.DECEASED) dateOfDeath else null

                // Welle V1.3.2 "Webhooks" (ausgehend), D8/S24 -- fires ONLY on a genuine transition
                // INTO ACTIVE (the no-op guard above already returned early for newStatus ==
                // fromStatus, so this is never a redundant re-confirmation of an already-ACTIVE
                // member). GET /api/v1/members/{id} hard-filters on ACTIVE, so this is exactly the
                // moment this member becomes visible on that endpoint.
                if (newStatus == MemberStatus.ACTIVE) {
                    WebhookEventPublisher.publish(eventType = WebhookEventType.MEMBER_CREATED, entityId = targetId, occurredAt = now)
                }

                // Same shared side-effect ordering RegistrationService.leaveMembership/
                // rejectApplication already establish: committee/mandate cleanup INSIDE this
                // transaction, session revocation AFTER commit (see below).
                if (newStatus in MemberStatusSets.MEMBERSHIP_ENDED) {
                    endAllOpenCommitteeMembershipsForMember(memberId = targetId, until = now.date, current = current)
                    revokeMandatesForEndedMembership(
                        memberId = targetId,
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        now = now,
                    )
                }
                revokeSessions = newStatus in MemberStatusSets.LOGIN_BLOCKED

                val beforeSnapshot =
                    MemberChangeSnapshot(
                        displayNameChanged = false,
                        emailChanged = false,
                        status = fromStatus,
                        role = existingRole,
                    )
                val afterSnapshot =
                    beforeSnapshot.copy(
                        status = newStatus,
                        reason = trimmedReason,
                        dateOfDeathChanged = newDateOfDeath != previousDateOfDeath,
                    )
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MEMBER,
                    entityId = targetId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                    after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                    occurredAt = now,
                )
                loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
            }
        // resolveCurrentMember does not itself re-check MemberStatusSets.LOGIN_BLOCKED per call --
        // AuthRoutes' login gate blocks a NEW login, but does nothing about a session that already
        // existed before this decision (same gap RegistrationService.rejectApplication's own KDoc
        // documents). Revocation is the only thing that actually ends it before the 8h TTL.
        if (revokeSessions) SessionStore.revokeAllForMember(memberId = targetId)
        return result
    }

    // Welle V1.4.4.5 -- ADMIN-exclusive correction of an already-recorded date of death, see
    // IMemberService KDoc for why this is a SEPARATE method from updateMemberStatus (whose
    // newStatus == from no-op clause is a promised, tested idempotence guarantee this call must
    // not break). Deliberately NO ESCALATED_ROLES peer-check and NO Letzter-Admin-Schutz -- the
    // status itself never changes here, unlike updateMemberStatus/updateMemberRole; this is a
    // conscious omission, not a gap, and is called out explicitly for the security audit.
    override suspend fun correctDateOfDeath(
        memberId: String,
        dateOfDeath: LocalDate?,
        reason: String,
    ): MemberAdminRowDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        if (targetId == current.memberId) throw ForbiddenException()

        val trimmedReason = reason.trim()
        if (trimmedReason.length < MIN_REASON_LENGTH || trimmedReason.length > MAX_REASON_LENGTH) {
            throw ConflictException("A reason is required ($MIN_REASON_LENGTH-$MAX_REASON_LENGTH characters)")
        }

        val now = nowLocalDateTime()
        return transaction {
            val row =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq targetId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
            if (row[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer be edited")
            }
            if (row[MemberTable.status] != MemberStatus.DECEASED) {
                throw ConflictException("A date of death can only be corrected for a DECEASED member")
            }
            requirePlausibleDeathDate(dateOfDeath = dateOfDeath, row = row, now = now)

            val previous = row[MemberTable.dateOfDeath]
            if (previous == dateOfDeath) {
                // Idempotent: the same value again is a no-op -- no update, no audit entry.
                return@transaction loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
            }

            MemberTable.update({ MemberTable.id eq targetId }) { it[MemberTable.dateOfDeath] = dateOfDeath }

            val existingRole =
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq targetId }
                    .singleOrNull()
                    ?.get(AccountTable.role)
            val beforeSnapshot =
                MemberChangeSnapshot(
                    displayNameChanged = false,
                    emailChanged = false,
                    status = MemberStatus.DECEASED,
                    role = existingRole,
                )
            val afterSnapshot = beforeSnapshot.copy(reason = trimmedReason, dateOfDeathChanged = true)
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBER,
                entityId = targetId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                occurredAt = now,
            )
            loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
        }
    }

    override suspend fun updateMemberRole(
        memberId: String,
        newRole: AccountRole,
    ): MemberAdminRowDto {
        val current = resolveCurrentMember(call)
        // ADMIN-exclusive for EVERY role change, including a downgrade -- see interface KDoc for
        // why this is stricter than RegistrationService.createMemberDirect's escalated-role-only
        // gate. Checked unconditionally, before self/existence checks.
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        if (targetId == current.memberId) throw ForbiddenException()

        return transaction {
            val memberRow =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq targetId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
            if (memberRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer be edited")
            }
            // Letzter-Admin-Schutz, race-safe: lock the target's account row AND every ADMIN
            // account row in a SINGLE id-ordered query, instead of locking the target row first
            // and the ADMIN set afterwards. Two concurrent transactions that each lock their own
            // target row before the ordered ADMIN-set query can request that shared row set in
            // opposite orders (T1: target(B) then {A,B} ordered; T2: target(A) then {A,B}
            // ordered) -- a fixed order on ONE of the two queries does not prevent that, only a
            // single query locking the union in id order does (see this method's own plan KDoc
            // "Letzter-Admin-Schutz"). `.forUpdate()` then genuinely serializes two concurrent
            // degradations of the last two ADMIN accounts against each other (a bare count() would
            // not, under READ COMMITTED).
            val lockedAccountRows =
                AccountTable
                    .selectAll()
                    .where { (AccountTable.memberId eq targetId) or (AccountTable.role eq AccountRole.ADMIN) }
                    .orderBy(AccountTable.id)
                    .forUpdate()
                    .toList()
            val accountRow =
                lockedAccountRows.singleOrNull { it[AccountTable.memberId] == targetId }
                    ?: throw MemberHasNoAccountException()
            val currentRole = accountRow[AccountTable.role]

            // Idempotent no-op: DTO back, no update, no audit entry.
            if (newRole == currentRole) return@transaction loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)

            if (newRole != AccountRole.ADMIN) {
                // Security fix (2026-08-27, MEDIUM) -- the invariant is "at least one ADMIN with a
                // non-LOGIN_BLOCKED member status remains", the SAME standard updateMemberStatus's
                // own Letzter-Admin-Schutz enforces (see that method's KDoc) -- NOT merely "a second
                // ADMIN *account* exists". The old `adminAccountRows.size == 1` check counted ADMIN
                // accounts blind to member.status: two ADMINs X/Y, both ACTIVE -- X withdraws Y via
                // updateMemberStatus (leaves X as the sole non-blocked ADMIN, correctly allowed), then
                // Y (still logged in, session revocation is async and resolveCurrentMember does not
                // re-check status per call) demotes X here. `adminAccountRows` = {X, Y}, size 2 -- the
                // old check let this through, leaving X=MEMBER and Y=ADMIN-but-WITHDRAWN: zero
                // login-capable ADMIN accounts, recoverable only via direct DB access. Excluding the
                // TARGET from the "other admins" set (it is about to lose ADMIN regardless of its own
                // status) and re-reading their CURRENT member status closes that gap.
                val otherAdminMemberIds =
                    lockedAccountRows
                        .filter { it[AccountTable.role] == AccountRole.ADMIN && it[AccountTable.memberId] != targetId }
                        .map { it[AccountTable.memberId] }
                val remainingNonBlockedAdmins =
                    if (otherAdminMemberIds.isEmpty()) {
                        0L
                    } else {
                        MemberTable
                            .selectAll()
                            .where {
                                (MemberTable.id inList otherAdminMemberIds) and
                                    (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED)
                            }.count()
                    }
                if (remainingNonBlockedAdmins == 0L) throw LastAdminException()
            }

            AccountTable.update({ AccountTable.memberId eq targetId }) { it[role] = newRole }

            val beforeSnapshot =
                MemberChangeSnapshot(
                    displayNameChanged = false,
                    emailChanged = false,
                    status = memberRow[MemberTable.status],
                    role = currentRole,
                )
            val afterSnapshot = beforeSnapshot.copy(role = newRole)
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBER,
                entityId = targetId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                occurredAt = nowLocalDateTime(),
            )
            // Deliberately NO SessionStore.revokeAllForMember here -- see interface KDoc
            // "Deliberately does NOT invalidate the target's existing sessions".
            loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
        }
    }

    override suspend fun grantMemberAccount(
        memberId: String,
        temporaryPassword: String,
        role: AccountRole,
    ): MemberAdminRowDto {
        val current = resolveCurrentMember(call)
        // ADMIN-exclusive, unconditional, before any existence/state check -- see interface KDoc.
        // Granting ACCESS AT ALL is structurally an initial role assignment, so this mirrors
        // updateMemberRole's gate exactly, not RegistrationService.createMemberDirect's weaker
        // escalated-role-only one.
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        // Deliberately NO self-target check -- see interface KDoc: the caller authenticated with an
        // account row, so a self-target necessarily lands in MemberAlreadyHasAccountException below.

        val now = nowLocalDateTime()
        return transaction {
            val memberRow =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq targetId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
            // Load-bearing, NOT copy-paste consistency with the three V1.2.12 RPCs:
            // FoundationPersonalData.erase HARD-DELETES the account row on an Art. 17 erasure, so an
            // anonymized member is indistinguishable from a CSV import by `role == null` alone.
            // Without this check, this RPC would be the one and only way to hand a DSGVO-erased
            // person a working login again.
            if (memberRow[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer be edited")
            }
            // The ONLY blocked status -- see interface KDoc for why DONOR/WITHDRAWN/REJECTED are
            // deliberately allowed (LOGIN_BLOCKED stays the single login policy and keeps such an
            // account inert) and why DECEASED is not (/api/auth/password-reset/request does not
            // consult LOGIN_BLOCKED, so the account would make a deceased member's mailbox a valid
            // password-reset recipient).
            if (memberRow[MemberTable.status] == MemberStatus.DECEASED) {
                throw ConflictException("Cannot grant a login account to a deceased member")
            }

            // Against the address AS STORED, never a client-supplied one -- the client does not send
            // an e-mail on this call at all, and must not be able to weaken this check by sending a
            // different one. Same PasswordPolicy call RegistrationService.createMemberDirect uses.
            PasswordPolicy.validate(newPassword = temporaryPassword, email = memberRow[MemberTable.email])

            // Layer 1 of the two-layer uniqueness guard. `.forUpdate()` on a row set that is normally
            // EMPTY locks nothing -- the real serialization for two concurrent grants against the
            // SAME member already comes from the MemberTable `.forUpdate()` above (both callers
            // contend for that one row), and the uq_account_member_id backstop below closes the rest.
            //
            // This method acquires at most ONE account-row lock and never asks for a second, so it
            // cannot participate in the member/account wait cycle updateMemberRole/updateMemberStatus
            // close with their id-ordered union lock -- the deliberately narrow single-row lock is
            // correct here, not an oversight.
            val existingAccount =
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq targetId }
                    .forUpdate()
                    .singleOrNull()
            if (existingAccount != null) throw MemberAlreadyHasAccountException()

            // bcrypt (PasswordHasher.hash, ~250ms at BCRYPT_COST=12) runs INSIDE the transaction on
            // purpose: PasswordPolicy.validate needs the member's e-mail, which is only known after
            // the row read above, and hoisting the hash out would cost a second query for no benefit
            // at this call's frequency (one ADMIN action, not a login path). No enumeration-timing
            // concern applies -- the caller is an authenticated ADMIN who already sees the roster.
            try {
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = targetId
                    it[AccountTable.role] = role
                    it[passwordHash] = PasswordHasher.hash(temporaryPassword)
                    // oidcSubject/oidcIssuer stay null -- a password account, not a federated one.
                }
            } catch (e: ExposedSQLException) {
                // Layer 2: uq_account_member_id (V1__baseline.sql) is the real backstop; the
                // pre-check above is racy on its own. Same idiom (and same "log the class name only,
                // never the message/stacktrace -- no PII" discipline) updateMemberCoreData's own
                // e-mail-uniqueness backstop already establishes.
                logger.warn { "AccountTable.insert failed in grantMemberAccount: ${e::class.simpleName}" }
                throw MemberAlreadyHasAccountException()
            }

            val beforeSnapshot =
                MemberChangeSnapshot(
                    displayNameChanged = false,
                    emailChanged = false,
                    status = memberRow[MemberTable.status],
                    role = null,
                )
            val afterSnapshot = beforeSnapshot.copy(role = role)
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBER,
                entityId = targetId,
                // CREATE, not UPDATE -- this is the ONE writer of MEMBER/CREATE. It makes "who gave
                // this person access, and with which role" a sentence in the GoBD chain that no
                // updateMemberRole entry can imitate (see interface KDoc). No new AuditEntityType:
                // an ACCOUNT literal would cost a Flyway CHECK migration, another in-place edit of
                // V1__baseline.sql and a flywayRepair on BOTH production instances for zero analytic
                // gain -- the entity under administration is the member.
                action = AuditAction.CREATE,
                before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                occurredAt = now,
            )
            // Deliberately NO SessionStore.revokeAllForMember -- there is no session to revoke for an
            // account that did not exist a moment ago. Stated explicitly so no reviewer "adds the
            // missing revocation" by analogy with updateMemberStatus.
            loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
        }
    }

    // ── Welle V1.4.4.4 "Familienmitgliedschaften" ──────────────────────────────────────────────

    override suspend fun updateMemberMembershipTier(
        memberId: String,
        membershipTierId: String?,
        reason: String,
    ): MemberAdminRowDto {
        val current = resolveCurrentMember(call)
        // Rollen-Asymmetrie, checked BEFORE any existence/state/reason validation -- see interface
        // KDoc. Assigning a REAL tier creates a payment obligation (TREASURER/ADMIN); removing one
        // only requires the lighter-weight isPrivileged (BOARD/ADMIN).
        if (membershipTierId != null) {
            current.requireRole(AccountRole.TREASURER, AccountRole.ADMIN)
        } else if (!current.isPrivileged) {
            throw ForbiddenException()
        }
        val targetId = memberId.toMemberUuidOrThrow()
        // Security fix (MAJOR, self-target) -- always forbidden, regardless of role/direction, same
        // "a privileged self-*-change must never be a self-service action" posture updateMemberStatus
        // (line ~462) and updateMemberRole already enforce for THEIR own write paths. Without this, a
        // BOARD caller could null their OWN membership_tier_id (membershipTierId == null only needs
        // isPrivileged, not TREASURER/ADMIN) and silently escape their own next contribution run.
        if (targetId == current.memberId) throw ForbiddenException()
        val tierUuid =
            membershipTierId?.let {
                runCatching { Uuid.parse(it) }.getOrElse { throw BadRequestException("Invalid MembershipTier id: $it") }
            }

        val trimmedReason = reason.trim()
        if (trimmedReason.length < MIN_REASON_LENGTH || trimmedReason.length > MAX_REASON_LENGTH) {
            throw ConflictException("A reason is required ($MIN_REASON_LENGTH-$MAX_REASON_LENGTH characters)")
        }

        val now = nowLocalDateTime()
        return transaction {
            // Security fix (MAJOR, Peer-Schutz) -- same ESCALATED_ROLES boundary
            // updateMemberCoreData/updateMemberStatus already draw: a BOARD (or TREASURER) caller may
            // not change a fellow ADMIN/BOARD/TREASURER account's tier either, only ADMIN may. Read
            // with the SAME `.forUpdate()`-locked helper those two methods use, so this can never
            // observe a stale pre-escalation role racing a concurrent updateMemberRole commit.
            val existingRole = currentAccountRole(targetId)
            if (existingRole != null && existingRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)
            MembershipTierAssignment.apply(
                targetMemberId = targetId,
                newTierId = tierUuid,
                actor = current,
                reason = trimmedReason,
                familyId = null,
                now = now,
            )
            loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
        }
    }

    // ── Welle V1.4.9 "Admin-Passwort-Reset" ─────────────────────────────────────────────────────

    override suspend fun getMemberAccessPreflight(memberId: String): MemberAccessPreflightDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        // Deliberately NO self-target block -- a pure read with no side effect, over data the
        // caller already sees elsewhere on the roster; the client never even offers this dialog for
        // the caller's own row (see network.lapis.cloud.client.canResetPasswordOf).
        val exists = transaction { MemberTable.selectAll().where { MemberTable.id eq targetId }.count() > 0 }
        if (!exists) throw NotFoundException("Member $memberId not found")
        return MemberAccessPreflightDto(
            mailDelivery =
                if (smtpConfigState is SmtpConfigState.NotConfigured) {
                    MailDeliveryState.NOT_CONFIGURED
                } else {
                    MailDeliveryState.HANDED_TO_SMTP
                },
            activeSessionCount = SessionStore.countActiveForMember(memberId = targetId),
        )
    }

    override suspend fun setTemporaryPasswordForMember(
        memberId: String,
        newPassword: String?,
        reason: String,
    ): TemporaryPasswordResultDto {
        val current = resolveCurrentMember(call)
        // ADMIN-exclusive, unconditional, before any existence/state check -- same posture
        // updateMemberRole/grantMemberAccount already establish for granting/changing access.
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        // Always forbidden -- the caller's own path is IAuthService.changePassword.
        if (targetId == current.memberId) throw ForbiddenException()

        val trimmedReason = reason.trim()
        if (trimmedReason.length < MIN_REASON_LENGTH || trimmedReason.length > MAX_REASON_LENGTH) {
            throw ConflictException("A reason is required ($MIN_REASON_LENGTH-$MAX_REASON_LENGTH characters)")
        }

        val now = nowLocalDateTime()
        val (effectivePassword, targetEmail, row) =
            transaction {
                val memberRow =
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .forUpdate()
                        .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                if (memberRow[MemberTable.anonymizedAt] != null) {
                    throw ConflictException("Member has been anonymized and can no longer be edited")
                }
                // The ONLY blocked status -- mirrors grantMemberAccount's own DECEASED exclusion
                // exactly: the security notice below would otherwise land in what is, in practice, a
                // relative's mailbox. DONOR/WITHDRAWN/REJECTED remain allowed -- LOGIN_BLOCKED stays
                // the single, central login policy and keeps such an account inert regardless.
                if (memberRow[MemberTable.status] == MemberStatus.DECEASED) {
                    throw ConflictException("Cannot reset the password of a deceased member")
                }
                // Exactly ONE account-row lock, same "narrow single-row lock is correct here, not an
                // oversight" reasoning grantMemberAccount's own KDoc gives for its identical shape --
                // this method never asks for a second lock, so it cannot join the id-ordered union-
                // lock wait cycle updateMemberRole/updateMemberStatus close against EACH OTHER.
                val accountRow =
                    AccountTable
                        .selectAll()
                        .where { AccountTable.memberId eq targetId }
                        .forUpdate()
                        .singleOrNull() ?: throw MemberHasNoAccountException()

                val effectivePassword = newPassword ?: TemporaryPasswordGenerator.generate()
                // Against the address AS STORED, never a client-supplied one -- this call does not
                // accept an e-mail parameter at all. Same PasswordPolicy call grantMemberAccount uses.
                PasswordPolicy.validate(newPassword = effectivePassword, email = memberRow[MemberTable.email])

                AccountTable.update({ AccountTable.memberId eq targetId }) {
                    it[passwordHash] = PasswordHasher.hash(effectivePassword)
                }

                val beforeSnapshot =
                    MemberChangeSnapshot(
                        displayNameChanged = false,
                        emailChanged = false,
                        status = memberRow[MemberTable.status],
                        role = accountRow[AccountTable.role],
                    )
                val afterSnapshot =
                    beforeSnapshot.copy(
                        reason = trimmedReason,
                        adminPasswordAction = AdminPasswordAction.TEMPORARY_PASSWORD_SET,
                    )
                // LAST sperrende Operation dieser Transaktion (Deadlock-Vertrag, AuditLogRecorder KDoc).
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MEMBER,
                    entityId = targetId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                    after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                    occurredAt = now,
                )
                Triple(
                    effectivePassword,
                    memberRow[MemberTable.email],
                    loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged),
                )
            }
        // AFTER commit -- SessionStore writes its own transaction, same placement discipline
        // updateMemberCoreData/updateMemberStatus already establish for session revocation.
        val revokedCount = SessionStore.revokeAllForMember(memberId = targetId)
        // Security fix (Welle V1.4.9 review round, MAJOR) -- an outstanding reset token minted
        // earlier (e.g. via sendPasswordResetMailToMember, Weg 2) used to survive this call
        // entirely: SessionStore.revokeAllForMember above only kills LIVE SESSIONS, not a
        // still-valid bearer token for taking a NEW session over. Placed immediately alongside the
        // session revocation, same "the whole point of this call is this account is compromised"
        // posture the interface KDoc documents -- see PasswordResetTokenStore.invalidateAllForMember
        // KDoc for the full attack scenario this closes.
        PasswordResetTokenStore.invalidateAllForMember(memberId = targetId)
        val notified =
            notifyMemberOfAdminPasswordReset(
                targetId = targetId,
                email = targetEmail,
                occurredAt = now,
            )
        return TemporaryPasswordResultDto(
            member = row,
            // The ONE and ONLY moment this value is ever visible -- non-null iff the caller left
            // newPassword null (server-generated); never stored, logged, or retrievable again.
            generatedPassword = if (newPassword == null) effectivePassword else null,
            revokedSessionCount = revokedCount,
            memberNotified = notified,
        )
    }

    /**
     * Welle V1.4.9 -- Weg 1's password-free security notice, sent AFTER commit (see
     * [PasswordResetTokenStore.createToken] KDoc "nested transaction {} joins" for why store/mailer
     * calls in this codebase consistently run after the enclosing transaction has already
     * committed). **Never ergebnisrelevant** -- the password change already committed by the time
     * this runs; a rate-limited or failed notice never turns a successful password reset into a
     * failed RPC call.
     */
    private fun notifyMemberOfAdminPasswordReset(
        targetId: Uuid,
        email: String,
        occurredAt: LocalDateTime,
    ): MailDeliveryState {
        if (smtpConfigState is SmtpConfigState.NotConfigured) return MailDeliveryState.NOT_CONFIGURED
        // Security fix (Welle V1.4.9 review round, MINOR) -- target-side check uses
        // adminPasswordNotificationTargetRateLimiter, a DEDICATED pool separate from
        // adminPasswordMailTargetRateLimiter (which sendPasswordResetMailToMember alone consumes
        // below). See that property's own KDoc for why sharing one pool between the two mails let
        // either side starve the notice.
        //
        // Security fix (Welle V1.4.9 review round, MINOR, residual/round 2) -- NO actor-side check
        // here at all anymore. It used to consult the SHARED adminPasswordMailActorRateLimiter
        // (the same "actor:<adminId>" pool sendPasswordResetMailToMember below also draws from),
        // which stayed silently exhaustible: a rogue admin could burn all 50 actor-side slots for
        // free by calling sendPasswordResetMailToMember with well-formed but NON-EXISTENT member
        // ids (that call's own checkAndRecord runs before its NotFoundException, see the comment
        // there), leaving zero slots by the time a REAL setTemporaryPasswordForMember call needed
        // one for its transparency notice -- silently suppressing the one real-time signal a victim
        // has that their account was touched, while the password change itself still committed.
        // Dropping the actor check here closes that without reintroducing a shared resource: this
        // function has exactly one caller (setTemporaryPasswordForMember), which is not a "cheap"
        // action an attacker can spam for free the way sendPasswordResetMailToMember's failure path
        // is -- every call already writes a password hash change plus a hash-chained audit entry
        // for a REAL member row, so it cannot be used to pre-exhaust anything at zero cost. The
        // dedicated target-side pool below remains the actual anti-abuse cap, per victim.
        val targetAllowed = adminPasswordNotificationTargetRateLimiter.checkAndRecord("member:$targetId")
        if (!targetAllowed) {
            logger.warn { "admin-password-reset notice suppressed by rate limiter (target=$targetId)" }
            return MailDeliveryState.RATE_LIMITED
        }
        runCatching { adminPasswordResetNotificationMailer.send(email = email, occurredAt = occurredAt) }
            .onFailure { e -> logger.error { "adminPasswordResetNotificationMailer.send threw: ${e::class.simpleName}" } }
        return MailDeliveryState.HANDED_TO_SMTP
    }

    override suspend fun sendPasswordResetMailToMember(memberId: String): PasswordResetMailResultDto {
        val current = resolveCurrentMember(call)
        // ADMIN-exclusive, unconditional -- same gate setTemporaryPasswordForMember applies.
        current.requireRole(AccountRole.ADMIN)
        val targetId = memberId.toMemberUuidOrThrow()
        if (targetId == current.memberId) throw ForbiddenException()

        // Security fix (Welle V1.4.9 review round, MINOR) -- the SMTP-config check and both
        // checkAndRecord calls used to run INSIDE the transaction { } below, alongside the
        // hash-chained AuditLogRecorder insert. Exposed retries that whole block up to
        // `maxAttempts` times on a transient SQLException, and checkAndRecord's own side effect
        // is a plain in-memory counter update entirely unrelated to the SQL transaction it used
        // to sit inside -- a retry silently double-charged both rate-limit budgets for what the
        // caller experiences as a single request. Moved out and now run exactly once, before the
        // transaction even opens -- same "guard clause runs before any transaction { }"
        // placement every other checkAndRecord call site in this codebase already establishes
        // (e.g. ConferenceBreakoutService.requireWithinRate's call sites, always the first
        // statements of their function). No token, no audit entry -- nothing happened, so nothing
        // is recorded. Both checkAndRecord calls still run unconditionally (no short-circuit), so
        // cycling either side alone cannot dodge the other side's cap. NOTE: this also means the
        // actor-side slot below is consumed even when targetId turns out not to exist (the existence
        // check only happens inside the transaction further down) -- that is why
        // notifyMemberOfAdminPasswordReset (Weg 1) deliberately no longer shares this actor pool; see
        // its own comment for the exhaustion scenario that sharing enabled.
        if (smtpConfigState is SmtpConfigState.NotConfigured) {
            return PasswordResetMailResultDto(delivery = MailDeliveryState.NOT_CONFIGURED)
        }
        val actorAllowed = adminPasswordMailActorRateLimiter.checkAndRecord("actor:${current.memberId}")
        val targetAllowed = adminPasswordMailTargetRateLimiter.checkAndRecord("member:$targetId")
        if (!actorAllowed || !targetAllowed) {
            logger.warn { "admin-password-reset-mail suppressed by rate limiter (target=$targetId)" }
            return PasswordResetMailResultDto(delivery = MailDeliveryState.RATE_LIMITED)
        }

        val now = nowLocalDateTime()
        val targetEmail =
            transaction {
                val memberRow =
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .forUpdate()
                        .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                if (memberRow[MemberTable.anonymizedAt] != null) {
                    throw ConflictException("Member has been anonymized and can no longer be edited")
                }
                val accountRow =
                    AccountTable
                        .selectAll()
                        .where { AccountTable.memberId eq targetId }
                        .forUpdate()
                        .singleOrNull() ?: throw MemberHasNoAccountException()
                // Deliberate ASYMMETRY with the unauthenticated self-service endpoint
                // (/api/auth/password-reset/request), which does NOT consult LOGIN_BLOCKED at all --
                // see interface KDoc. This ADMIN-facing call owes the operator an honest outcome
                // instead of a token minted for an account a reset link can never actually unlock.
                if (memberRow[MemberTable.status] in MemberStatusSets.LOGIN_BLOCKED) {
                    throw ConflictException("Login is blocked for this member's status -- a reset link would be ineffective")
                }

                val beforeSnapshot =
                    MemberChangeSnapshot(
                        displayNameChanged = false,
                        emailChanged = false,
                        status = memberRow[MemberTable.status],
                        role = accountRow[AccountTable.role],
                    )
                val afterSnapshot = beforeSnapshot.copy(adminPasswordAction = AdminPasswordAction.RESET_MAIL_SENT)
                // LAST sperrende Operation dieser Transaktion (Deadlock-Vertrag, AuditLogRecorder KDoc).
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MEMBER,
                    entityId = targetId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
                    after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
                    occurredAt = now,
                )
                memberRow[MemberTable.email]
            }
        // AFTER commit -- PasswordResetTokenStore.createToken opens its OWN transaction {}, which
        // in Exposed JOINS an already-open one, including its 1%-purgeExpired -- a swallowed
        // exception there could abort the surrounding transaction under Postgres and take the
        // audit insert above down with it. Same "after commit" placement
        // updateMemberCoreData/updateMemberStatus already establish for their own store/mailer
        // calls.
        //
        // Security fix (Welle V1.4.9 review round, MINOR) -- createToken() now shares the SAME
        // runCatching as the mailer.send() right below it, instead of running unguarded. The
        // audit entry above already committed claiming RESET_MAIL_SENT (GoBD: hash-chained,
        // unlöschbar) by the time this runs; letting createToken() throw uncaught would hand the
        // admin an uncaught-exception 500 while that committed entry permanently asserts a reset
        // mail was triggered -- neither token nor mail would actually exist. Same "must never let
        // a post-commit side effect turn an already-committed, already-true fact into a confusing
        // failure" posture the mail-failure branch already establishes for send() alone.
        runCatching {
            val rawToken = PasswordResetTokenStore.createToken(targetId)
            passwordResetMailer.send(email = targetEmail, rawToken = rawToken)
        }.onFailure { e -> logger.error { "password-reset-mail token creation/send threw: ${e::class.simpleName}" } }
        return PasswordResetMailResultDto(delivery = MailDeliveryState.HANDED_TO_SMTP)
    }

    // Security fix (2026-08-27, LOW TOCTOU) -- `.forUpdate()` added: without it, this read raced
    // updateMemberRole's own `.forUpdate()`-locked AccountTable write under READ COMMITTED --
    // updateMemberCoreData/updateMemberStatus could observe a stale (pre-escalation) role, pass the
    // peer-protection check, and then mutate a target that becomes ADMIN/BOARD/TREASURER by the time
    // either transaction commits. `.forUpdate()` here blocks until any concurrent updateMemberRole
    // transaction touching this same account row has committed, so the read is always the row's
    // truly-current role, not a snapshot racing an in-flight write.
    private fun currentAccountRole(memberId: Uuid): AccountRole? =
        AccountTable
            .selectAll()
            .where { AccountTable.memberId eq memberId }
            .forUpdate()
            .singleOrNull()
            ?.get(AccountTable.role)

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    // Welle V1.4.4.5 -- shared by updateMemberStatus (ACTIVE->DECEASED) and correctDateOfDeath.
    private fun requirePlausibleDeathDate(
        dateOfDeath: LocalDate?,
        row: ResultRow,
        now: LocalDateTime,
    ) {
        when (
            DeathDateRules.violation(
                dateOfDeath = dateOfDeath,
                dateOfBirth = row[MemberTable.dateOfBirth],
                today = now.date,
            )
        ) {
            DeathDateViolation.IN_FUTURE -> throw ConflictException("A date of death cannot be in the future")
            DeathDateViolation.BEFORE_BIRTH -> throw ConflictException("A date of death cannot precede the date of birth")
            null -> Unit
        }
    }
}

/**
 * `member.display_name` is `VARCHAR(200)` -- reject an overlong name before Postgres would.
 * Not `private` (Review Runde 3 dedup): [network.lapis.cloud.server.bootstrap.MemberCsvImport]'s
 * own `FIELD_MAX_LENGTHS["display_name"]` mirrors the SAME column limit for the SAME reason: two
 * literal `200`s drifting apart silently if the column were ever resized is worse than one shared
 * constant used from both call sites.
 */
const val MEMBER_DISPLAY_NAME_MAX_LENGTH = 200

/**
 * [MemberTable.email] is `VARCHAR(320)` (V1__baseline.sql line 127) -- see
 * [MemberService.updateMemberCoreData]'s own length check for why this needs a dedicated
 * pre-check, not just [isValidMailboxAddress]'s syntax check. Not `private` (Review Runde 3
 * dedup): [network.lapis.cloud.server.bootstrap.MemberCsvImport]'s own
 * `FIELD_MAX_LENGTHS["email"]` mirrors the SAME column limit, same reasoning as
 * [MEMBER_DISPLAY_NAME_MAX_LENGTH]'s own KDoc.
 */
const val MEMBER_EMAIL_MAX_LENGTH = 320
private const val MIN_REASON_LENGTH = 3
private const val MAX_REASON_LENGTH = 1000

/**
 * LEFT JOIN, deliberately not `innerJoin` -- an `innerJoin` would silently exclude every one of
 * the 407 `MemberCsvImport`-created rows that have no `account` at all (see [MemberAdminRowDto
 * .role] KDoc). [ResultRow.toMemberAdminRowDto] below uses `getOrNull` on the joined columns for
 * exactly the same reason -- `row[AccountTable.role]` would throw for those rows.
 *
 * Welle V1.4.4.4 "Familienmitgliedschaften" added THREE further LEFT JOINs, every one explicit
 * (`join(table, JoinType.LEFT, onColumn, otherColumn)`, never `innerJoin`/implicit inference) --
 * `MemberFamilyLinkTable` carries TWO FKs to `MemberTable` (`member_id`, `linked_by`), the same
 * shape `MemberHonorService.honorMemberJoin`'s own KDoc documents as fatal for Exposed's implicit
 * join inference. **Row multiplication is structurally excluded**, so `.count()` in
 * `listMembersForAdministration` stays correct: `uq_member_family_link_member` guarantees at most
 * one `member_family_link` row per `member_id`, and the two further joins (`family_id` ->
 * `member_family.id`, `membership_tier_id` -> `membership_tier.id`) are both PK-side joins, each
 * matching at most one row by construction. Every added join is therefore, at most, 1:1.
 */
private val adminRosterSource: ColumnSet =
    MemberTable
        .join(AccountTable, JoinType.LEFT, MemberTable.id, AccountTable.memberId)
        .join(MemberFamilyLinkTable, JoinType.LEFT, MemberTable.id, MemberFamilyLinkTable.memberId)
        .join(MemberFamilyTable, JoinType.LEFT, MemberFamilyLinkTable.familyId, MemberFamilyTable.id)
        .join(MembershipTierTable, JoinType.LEFT, MemberTable.membershipTierId, MembershipTierTable.id)

/**
 * Review fix (Welle V1.4.4.4, MEDIUM finding): [includeFamilyDetails] gates the THREE
 * family-membership fields, not just their presence in the SQL join -- `IMemberFamilyService`
 * (`MemberFamilyService.FAMILY_ROLES`) restricts every dedicated family endpoint to BOARD/ADMIN,
 * so a TREASURER caller (the one role [network.lapis.cloud.server.rpc.MemberService
 * .listMembersForAdministration] and [network.lapis.cloud.server.rpc.MemberService
 * .updateMemberMembershipTier] admit besides BOARD/ADMIN) must not receive who-lives-with-whom
 * data through this DTO either -- that would let a TREASURER learn family composition for the
 * ENTIRE roster via a route the dedicated family endpoints deny outright with 403. Every call site
 * passes `current.isPrivileged` (BOARD/ADMIN) explicitly rather than defaulting to `true`, so a
 * future TREASURER-admitting call site cannot forget this gate by omission. Regression-tested in
 * [MemberAdministrationTest] (TREASURER vs. ADMIN, both via the roster read and via
 * [network.lapis.cloud.server.rpc.MemberService.updateMemberMembershipTier]'s own returned row) --
 * without those tests, reverting [includeFamilyDetails] to always-`true` left `./gradlew clean
 * check` fully green.
 *
 * This gate does NOT make the who-lives-with-whom link unreachable for a TREASURER in general: it
 * remains derivable via [network.lapis.cloud.server.rpc.AuditLogService.listAuditLog] (TREASURER
 * is one of that service's own read roles), whose `afterSnapshot` for a family-driven tier change
 * still carries `familyId` (`MembershipTierAssignment.apply`'s
 * `network.lapis.cloud.shared.domain.MemberMembershipTierSnapshot`). Closing that residual path is
 * out of scope for this fix -- see the CHANGELOG entry for Welle V1.4.4.4's review fixes.
 */
private fun ResultRow.toMemberAdminRowDto(includeFamilyDetails: Boolean): MemberAdminRowDto =
    MemberAdminRowDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        role = this.getOrNull(AccountTable.role),
        joinedAt = this[MemberTable.joinedAt],
        externalReference = this[MemberTable.externalReference],
        anonymized = this[MemberTable.anonymizedAt] != null,
        membershipTierId = this[MemberTable.membershipTierId]?.toString(),
        membershipTierName = this.getOrNull(MembershipTierTable.name),
        familyId = if (includeFamilyDetails) this.getOrNull(MemberFamilyLinkTable.familyId)?.toString() else null,
        familyName = if (includeFamilyDetails) this.getOrNull(MemberFamilyTable.name) else null,
        familyRole = if (includeFamilyDetails) this.getOrNull(MemberFamilyLinkTable.role) else null,
        dateOfDeath = this[MemberTable.dateOfDeath],
    )

private fun loadMemberAdminRow(
    id: Uuid,
    includeFamilyDetails: Boolean,
): MemberAdminRowDto =
    adminRosterSource
        .selectAll()
        .where { MemberTable.id eq id }
        .single()
        .toMemberAdminRowDto(includeFamilyDetails)

/**
 * `%`/`_` in the raw search text are LIKE metacharacters -- without escaping, a single `%` turns
 * every search into a full-table match on all rows (not a SQL-injection vector, Exposed
 * parameterizes the value, but a correctness/DoS sleeve at scale). Uses Exposed's own
 * [LikePattern.ofLiteral] (dialect-aware escaping) rather than a hand-rolled replace chain, then
 * wraps the escaped literal in unescaped `%` wildcards for a "contains" match.
 */
private fun containsPattern(term: String): LikePattern {
    val escaped = LikePattern.ofLiteral(term)
    return LikePattern("%${escaped.pattern}%", escaped.escapeChar)
}

private fun String.toMemberUuidOrThrow(): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Member $this not found") }

fun ResultRow.toMemberDto(): MemberDto =
    MemberDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        joinedAt = this[MemberTable.joinedAt],
        role = this[AccountTable.role],
        street = this[MemberTable.street],
        postalCode = this[MemberTable.postalCode],
        city = this[MemberTable.city],
        country = this[MemberTable.country],
        dateOfBirth = this[MemberTable.dateOfBirth],
        nationality = this[MemberTable.nationality],
        reviewedById = this[MemberTable.reviewedBy]?.toString(),
        reviewedAt = this[MemberTable.reviewedAt],
        rejectionReason = this[MemberTable.rejectionReason],
        friendSince = this[MemberTable.friendSince],
        dateOfDeath = this[MemberTable.dateOfDeath],
    )
