package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.payment.sepa.revokeMandatesForEndedMembership
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
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
        return transaction {
            MemberTable
                .select(MemberTable.id, MemberTable.displayName)
                .where { MemberTable.status eq MemberStatus.ACTIVE }
                .map {
                    MemberSummaryDto(
                        id = it[MemberTable.id].toString(),
                        displayName = it[MemberTable.displayName],
                    )
                }
        }
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
        if (!current.isPrivileged) throw ForbiddenException()

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
                    .map { it.toMemberAdminRowDto() }

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
                loadMemberAdminRow(targetId)
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
                if (newStatus == fromStatus) return@transaction loadMemberAdminRow(targetId)

                val allowedTargets = MemberStatusTransitions.allowedTargets(fromStatus)
                if (newStatus !in allowedTargets) {
                    throw ConflictException("Transition from $fromStatus to $newStatus is not allowed")
                }
                // Leaving DECEASED is a data correction, not a lifecycle event -- ADMIN-exclusive.
                if (MemberStatusTransitions.requiresAdmin(fromStatus)) current.requireRole(AccountRole.ADMIN)

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

                MemberTable.update({ MemberTable.id eq targetId }) { it[status] = newStatus }

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
                val afterSnapshot = beforeSnapshot.copy(status = newStatus, reason = trimmedReason)
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
                loadMemberAdminRow(targetId)
            }
        // resolveCurrentMember does not itself re-check MemberStatusSets.LOGIN_BLOCKED per call --
        // AuthRoutes' login gate blocks a NEW login, but does nothing about a session that already
        // existed before this decision (same gap RegistrationService.rejectApplication's own KDoc
        // documents). Revocation is the only thing that actually ends it before the 8h TTL.
        if (revokeSessions) SessionStore.revokeAllForMember(memberId = targetId)
        return result
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
            if (newRole == currentRole) return@transaction loadMemberAdminRow(targetId)

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
            loadMemberAdminRow(targetId)
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
            loadMemberAdminRow(targetId)
        }
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
 */
private val adminRosterSource: ColumnSet =
    MemberTable.join(AccountTable, JoinType.LEFT, MemberTable.id, AccountTable.memberId)

private fun ResultRow.toMemberAdminRowDto(): MemberAdminRowDto =
    MemberAdminRowDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        role = this.getOrNull(AccountTable.role),
        joinedAt = this[MemberTable.joinedAt],
        externalReference = this[MemberTable.externalReference],
        anonymized = this[MemberTable.anonymizedAt] != null,
    )

private fun loadMemberAdminRow(id: Uuid): MemberAdminRowDto =
    adminRosterSource
        .selectAll()
        .where { MemberTable.id eq id }
        .single()
        .toMemberAdminRowDto()

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
    )
