package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.AdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.NoOpPeerNotificationMailer
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.mail.PeerExecutedEvent
import network.lapis.cloud.server.mail.PeerNotificationMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.member.MemberCardIssuance
import network.lapis.cloud.server.member.MemberRoleStatusMutations
import network.lapis.cloud.server.member.PeerNotifier
import network.lapis.cloud.server.member.TemporaryPasswordMutation
import network.lapis.cloud.server.routes.MEMBER_CARD_AUDIT_ISSUED
import network.lapis.cloud.server.routes.MEMBER_CARD_AUDIT_REISSUED
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.MemberVisibility
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.PeerDecision
import network.lapis.cloud.server.security.PeerGuard
import network.lapis.cloud.server.security.PeerPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.TemporaryPasswordGenerator
import network.lapis.cloud.server.security.forMemberUpdate
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.memberVisibility
import network.lapis.cloud.server.security.peerGuarded
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminPasswordAction
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditMarkers
import network.lapis.cloud.shared.domain.DeathDateRules
import network.lapis.cloud.shared.domain.DeathDateViolation
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAccessPreflightDto
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberAddressField
import network.lapis.cloud.shared.domain.MemberAddressRules
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberCardReissueResultDto
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberSelectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MemberStatusTransitions
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.PasswordResetMailResultDto
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerActionAuditFacts
import network.lapis.cloud.shared.domain.PeerAuditEvent
import network.lapis.cloud.shared.domain.TemporaryPasswordResultDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.MemberAlreadyHasAccountException
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
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
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
     * Welle V1.4.9 "Admin-Passwort-Reset" -- Weg 2 triggers the SAME token-mint-and-mail mechanism
     * as `/api/auth/password-reset/request`, so it reuses the SAME [PasswordResetMailer] instance
     * that endpoint uses (wired once in `Application.kt`), never a second one. No default value on
     * purpose, same discipline every other constructor parameter on this class already establishes.
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
     * Pattern: the per-target / per-actor limiter pair of `network.lapis.cloud.server.member.EmailChangeService`.
     */
    private val adminPasswordMailTargetRateLimiter: FederationInboxRateLimiter,
    /**
     * Welle V1.4.9 -- ACTOR-side cap (50/60min, key `"actor:<callerId>"`) for
     * [sendPasswordResetMailToMember] ONLY, deliberately more generous than the target-side cap
     * above. Pattern + reasoning: the actor-side cap of `network.lapis.cloud.server.member.EmailChangeService` (an operator resetting
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
    /**
     * Security fix (Review MAJOR, 2026-09) -- [reissueMemberCard] rotates the exact same bearer
     * credential as `POST /api/members/{id}/card.pdf` (both delegate to
     * [MemberCardIssuance.rotate]), but until this fix only the HTTP route consulted a rate
     * limiter; this RPC path had none at all. The SAME [FederationInboxRateLimiter] INSTANCE
     * `Application.kt` wires into `registerMemberCardRoutes` is passed here too -- a genuinely
     * SHARED budget, not a second instance with an identical cap, because the two entry points
     * mint the identical side effect (revoke-then-mint) against the identical target. Two separate
     * instances would let a caller double the effective rotation rate by alternating between the
     * route and this RPC call. Keyed identically to the route (`"member-card:<targetId>"`, subject-
     * keyed not caller-keyed -- see [network.lapis.cloud.server.routes.registerMemberCardRoutes]
     * KDoc "Rate limiting" for why). No default value on purpose, same discipline every other
     * rate-limiter constructor parameter on this class already establishes.
     */
    private val memberCardIssueRateLimiter: FederationInboxRateLimiter,
    /**
     * V1.9.35: budget (30 / 60 min per actor) of [getMemberAddressForAdministration]. MUST be a
     * singleton created in `Application.kt` -- `MemberService` is rebuilt per call, so a default
     * value would create a fresh limiter per call and limit nothing. No default on purpose.
     */
    private val memberAddressAdminReadRateLimiter: FederationInboxRateLimiter,
    /**
     * Review-fix (V1.9.13): default-constructs its own [RegionalChapterEnforcementConfig.load],
     * same "default value on purpose, existing call sites keep working unchanged" idiom
     * [RegistrationService]'s own `keycloakConfig`/`regionalChapterEnforcementConfig` constructor
     * parameters establish. See that class's own KDoc for why the hard chapter-selection
     * requirement defaults to off.
     */
    private val regionalChapterEnforcementConfig: RegionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(),
    /**
     * Welle V1.9.57 "Admin-Peer-Schutz" -- the receipts to the TARGET of an action by another administrator and to the other
     * administrators when a new one appears. Default [NoOpPeerNotificationMailer] on purpose (same "existing call sites keep
     * working unchanged" idiom as [regionalChapterEnforcementConfig]); `Application.kt` always passes the SMTP implementation.
     */
    peerNotificationMailer: PeerNotificationMailer = NoOpPeerNotificationMailer,
) : IMemberService {
    private val peerNotifier = PeerNotifier(mailer = peerNotificationMailer, smtpConfigState = smtpConfigState)

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

    /**
     * Welle V1.9.33: values are normalized (trim, blank -> null) and validated by
     * [MemberAddressRules] AFTER the authorization check (Forbidden first, so nobody can probe the
     * rules without permission). Rejections throw a value-free [ConflictException]. A value-free
     * audit entry ([MEMBER_ADDRESS_AUDIT_UPDATED]) is written in the same transaction, also when
     * nothing changed. No dedicated rate limiter: `MemberService` has no general write limiter.
     */
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
        val normStreet = MemberAddressRules.normalize(street)
        val normPostalCode = MemberAddressRules.normalize(postalCode)
        val normCity = MemberAddressRules.normalize(city)
        val normCountry = MemberAddressRules.normalize(country)
        val violated =
            MemberAddressRules.textViolation(field = MemberAddressField.STREET, normalized = normStreet) != null ||
                MemberAddressRules.textViolation(field = MemberAddressField.POSTAL_CODE, normalized = normPostalCode) != null ||
                MemberAddressRules.textViolation(field = MemberAddressField.CITY, normalized = normCity) != null ||
                MemberAddressRules.textViolation(field = MemberAddressField.COUNTRY, normalized = normCountry) != null
        if (violated) throw ConflictException("Invalid address data")
        var notifyTargetAdmin = false
        val now = nowLocalDateTime()
        val result =
            peerGuarded(actor = current) {
                transaction {
                    // Welle V1.9.57 "Admin-Peer-Schutz" -- the target's member row and (as part of the id-ordered union) its account
                    // row are locked, so the role the decision reads cannot change underneath: BOARD never writes an ADMIN's address,
                    // an ADMIN writing another ADMIN's data is allowed and the target is told.
                    if (targetId != current.memberId) {
                        val memberRow = PeerGuard.lockMember(targetId) ?: throw NotFoundException("Member $memberId not found")
                        val facts =
                            PeerGuard.lockFactsAfterMemberLock(
                                targetId = targetId,
                                memberRow = memberRow,
                                requesterId = current.memberId,
                            )
                        val decision =
                            PeerGuard.decideLocked(
                                actor = current,
                                targetId = targetId,
                                action = PeerAction.WRITE_PROTECTED_DATA,
                                mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                                facts = facts,
                            )
                        notifyTargetAdmin = (decision as? PeerDecision.Allow)?.notifyTarget == true
                    }
                    val updated =
                        MemberTable.update({ MemberTable.id eq targetId }) {
                            it[MemberTable.street] = normStreet
                            it[MemberTable.postalCode] = normPostalCode
                            it[MemberTable.city] = normCity
                            it[MemberTable.country] = normCountry
                        }
                    if (updated == 0) throw NotFoundException("Member $memberId not found")
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.MEMBER,
                        entityId = targetId,
                        action = AuditAction.UPDATE,
                        after = MEMBER_ADDRESS_AUDIT_UPDATED,
                        occurredAt = now,
                    )
                    (MemberTable innerJoin AccountTable)
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .single()
                        .toMemberDto()
                }
            }
        if (notifyTargetAdmin) peerNotifier.protectedDataChanged(targetId = targetId, actorId = current.memberId, occurredAt = now)
        return result
    }

    /**
     * V1.9.35 -- BOARD/ADMIN only. Order matters: role check first (before parse and lookup, so a
     * non-privileged caller learns nothing about which ids exist), then the actor rate limit, then
     * parse, then ONE transaction that reads the row and writes the value-free audit entry. If the
     * audit write fails the transaction rolls back and no data leaves. No field value and no member
     * id is logged.
     */
    override suspend fun getMemberAddressForAdministration(memberId: String): MemberAddressDataDto {
        val current = resolveCurrentMember(call)
        if (!current.isPrivileged) throw ForbiddenException()
        if (!memberAddressAdminReadRateLimiter.checkAndRecord("actor:${current.memberId}")) {
            throw ConflictException("Too many requests")
        }
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member not found") }
        return transaction {
            val row =
                MemberTable.selectAll().where { MemberTable.id eq targetId }.singleOrNull()
                    ?: throw NotFoundException("Member not found")
            if (row[MemberTable.anonymizedAt] != null) throw NotFoundException("Member not found")
            // Welle V1.9.57 "Admin-Peer-Schutz" -- BOARD does not see an ADMIN's address and beneficial-owner data: a marked,
            // value-free answer (no error, no field value, no read audit entry because nothing leaves). The rate budget above
            // was already spent. ADMIN callers and the person themselves are unaffected.
            val targetRole =
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq targetId }
                    .singleOrNull()
                    ?.get(AccountTable.role)
            val decision =
                PeerPolicy.decide(
                    actorRole = current.role,
                    actorId = current.memberId,
                    targetRole = targetRole,
                    targetId = targetId,
                    action = PeerAction.READ_PROTECTED_DATA,
                    eligibleApprovers = 0,
                    mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                )
            if (decision is PeerDecision.Mask) {
                return@transaction MemberAddressDataDto(
                    memberId = targetId.toString(),
                    displayName = row[MemberTable.displayName],
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    dateOfBirth = null,
                    nationality = null,
                    dateOfDeath = null,
                    protectedTarget = true,
                )
            }
            if (decision is PeerDecision.Deny) throw ForbiddenException()
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBER,
                entityId = targetId,
                action = AuditAction.UPDATE,
                after = MEMBER_ADDRESS_AUDIT_READ,
                occurredAt = nowLocalDateTime(),
            )
            MemberAddressDataDto(
                memberId = targetId.toString(),
                displayName = row[MemberTable.displayName],
                street = row[MemberTable.street],
                postalCode = row[MemberTable.postalCode],
                city = row[MemberTable.city],
                country = row[MemberTable.country],
                dateOfBirth = row[MemberTable.dateOfBirth],
                nationality = row[MemberTable.nationality],
                dateOfDeath = row[MemberTable.dateOfDeath],
            )
        }
    }

    /**
     * V1.9.36 -- picker projection for the volunteer-allowance declarations overview. The role check runs BEFORE any database
     * access; `memberVisibility` is deliberately not used (a chapter officer with role MEMBER is rejected). Anonymized members are
     * excluded (they would all be named alike). Minimal fields only; at most [MAX_MEMBER_SELECTION] rows.
     */
    override suspend fun listMembersForSelection(): List<MemberSelectionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*VOLUNTEER_ALLOWANCE_DECISION_ROLES)
        return transaction {
            MemberTable
                .select(MemberTable.id, MemberTable.displayName, MemberTable.status)
                .where { MemberTable.anonymizedAt.isNull() }
                .orderBy(MemberTable.displayName to SortOrder.ASC, MemberTable.id to SortOrder.ASC)
                .limit(MAX_MEMBER_SELECTION)
                .map {
                    MemberSelectionDto(
                        id = it[MemberTable.id].toString(),
                        displayName = it[MemberTable.displayName],
                        status = it[MemberTable.status],
                    )
                }
        }
    }

    /** Welle V1.9.33: same hardening as [updateMemberAddress]; the date of death is read `forUpdate` (TOCTOU). */
    override suspend fun updateMemberBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        if (targetId != current.memberId && !current.isPrivileged) throw ForbiddenException()
        val normNationality = MemberAddressRules.normalize(nationality)
        if (MemberAddressRules.textViolation(field = MemberAddressField.NATIONALITY, normalized = normNationality) != null) {
            throw ConflictException("Invalid beneficial owner data")
        }
        var notifyTargetAdmin = false
        val now = nowLocalDateTime()
        val result =
            peerGuarded(actor = current) {
                transaction {
                    val row =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq targetId }
                            .forMemberUpdate()
                            .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                    // Welle V1.9.57 "Admin-Peer-Schutz" -- see updateMemberAddress: the role is read under the member and account locks.
                    if (targetId != current.memberId) {
                        val facts = PeerGuard.lockFactsAfterMemberLock(targetId = targetId, memberRow = row, requesterId = current.memberId)
                        val decision =
                            PeerGuard.decideLocked(
                                actor = current,
                                targetId = targetId,
                                action = PeerAction.WRITE_PROTECTED_DATA,
                                mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                                facts = facts,
                            )
                        notifyTargetAdmin = (decision as? PeerDecision.Allow)?.notifyTarget == true
                    }
                    if (MemberAddressRules.birthDateViolation(
                            dateOfBirth = dateOfBirth,
                            dateOfDeath = row[MemberTable.dateOfDeath],
                            today = OrganizationTimeZone.dateOf(now),
                        ) !=
                        null
                    ) {
                        throw ConflictException("Invalid beneficial owner data")
                    }
                    MemberTable.update({ MemberTable.id eq targetId }) {
                        it[MemberTable.dateOfBirth] = dateOfBirth
                        it[MemberTable.nationality] = normNationality
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.MEMBER,
                        entityId = targetId,
                        action = AuditAction.UPDATE,
                        after = MEMBER_BENEFICIAL_OWNER_AUDIT_UPDATED,
                        occurredAt = now,
                    )
                    (MemberTable innerJoin AccountTable)
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .single()
                        .toMemberDto()
                }
            }
        if (notifyTargetAdmin) peerNotifier.protectedDataChanged(targetId = targetId, actorId = current.memberId, occurredAt = now)
        return result
    }

    // ── Welle V1.2.12 -- Mitgliederverwaltung: vollständige Bearbeitung + privilegiertes Roster ──

    override suspend fun listMembersForAdministration(query: MemberAdminQuery): MemberAdminPageDto {
        val current = resolveCurrentMember(call)

        val limit = query.limit.coerceIn(1, MemberAdminQuery.MAX_LIMIT)
        val offset = query.offset.coerceAtLeast(0)
        val searchTerm =
            query.search
                ?.take(MemberAdminQuery.MAX_SEARCH_LENGTH)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }

        // Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- validated up front, BEFORE the
        // transaction even opens, same "reject malformed input before touching the DB" posture
        // this method already applies to limit/search. Both filters only make sense for `All`
        // visibility (see below) -- validating them unconditionally here, regardless of the
        // caller's eventual visibility, means a chapter-scoped officer who happens to pass one
        // gets the SAME BadRequestException a BOARD caller would, not a silently-ignored value
        // followed by success (see interface KDoc "ignored").
        if (query.regionalChapterId != null && query.unassignedOnly) {
            throw BadRequestException("regionalChapterId and unassignedOnly are mutually exclusive")
        }
        val queriedChapterId =
            query.regionalChapterId?.let {
                runCatching { Uuid.parse(it) }.getOrElse { throw BadRequestException("regionalChapterId is not a valid id") }
            }

        return transaction {
            // Welle V1.9.13 -- see `network.lapis.cloud.server.security.memberVisibility` KDoc.
            // Evaluated FIRST, before any roster data is touched -- `None` throws before a single
            // row is read.
            val visibility = current.memberVisibility()
            if (visibility is MemberVisibility.None) throw ForbiddenException()
            val chapterScopeId = (visibility as? MemberVisibility.Chapter)?.chapterId

            // Plain function -- `eq`/`like`/`and`/`or`/`inList` are all top-level functions in this
            // pinned Exposed version (the interface-member overloads are deprecated in favor of these),
            // so this predicate builder needs no special receiver scope.
            fun predicate(includeStatusFilter: Boolean): Op<Boolean> {
                if (chapterScopeId != null) {
                    // Chapter-scoped officer: hard-fixed to their own chapter's ACTIVE,
                    // non-anonymized members. regionalChapterId/unassignedOnly are ignored (see
                    // interface KDoc), statuses is intersected with {ACTIVE} (an empty intersection
                    // yields Op.FALSE, i.e. a correctly-empty page with correct zero counters,
                    // rather than silently falling back to "no status filter at all").
                    var predicate: Op<Boolean> =
                        (MemberTable.regionalChapterId eq chapterScopeId) and
                            (MemberTable.status eq MemberStatus.ACTIVE) and
                            (MemberTable.anonymizedAt.isNull())
                    if (searchTerm != null) {
                        // Deliberately NOT externalReference here -- see
                        // `RegionalChapterVisibility` KDoc / Befund 7 (a match/no-match on that
                        // field would leak which PdV-CSV-Import person-number belongs to whom).
                        val pattern = containsPattern(searchTerm)
                        predicate =
                            predicate and
                            ((MemberTable.displayName.lowerCase() like pattern) or (MemberTable.email.lowerCase() like pattern))
                    }
                    if (includeStatusFilter && query.statuses.isNotEmpty() && MemberStatus.ACTIVE !in query.statuses) {
                        predicate = Op.FALSE
                    }
                    return predicate
                }

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
                if (queriedChapterId != null) predicate = predicate and (MemberTable.regionalChapterId eq queriedChapterId)
                if (query.unassignedOnly) predicate = predicate and (MemberTable.regionalChapterId.isNull())
                return predicate
            }

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
                    .map { it.toMemberAdminRowDto(includeFamilyDetails = current.isPrivileged, chapterScoped = chapterScopeId != null) }

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

    /**
     * Welle V1.9.56 "E-Mail-Änderung absichern" -- corrects the DISPLAY NAME only. The address is the login and
     * password-reset identity of a member, so it can no longer be rewritten here (a board member or administrator could
     * otherwise take over any account in two calls: set an address they control, request a password reset). [email] is
     * still part of the signature (wire compatibility) but is IGNORED: the stored address is never written here, and a
     * stale value from an admin list loaded before the member changed their own address must not make a pure name
     * correction fail. To change an address use `IMemberEmailChangeService` (owner with password, or a proposal the
     * owner accepts, or the emergency path with proof of ownership and a warning period).
     */
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

        val now = nowLocalDateTime()
        return transaction {
            val row =
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq targetId }
                    .forMemberUpdate()
                    .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
            if (row[MemberTable.anonymizedAt] != null) {
                throw ConflictException("Member has been anonymized and can no longer be edited")
            }

            // Peer-Schutz: a BOARD caller may not edit a fellow ADMIN/BOARD/TREASURER account --
            // same escalated-role boundary network.lapis.cloud.server.security.ESCALATED_ROLES
            // already draws for RegistrationService.createMemberDirect.
            val existingRole = currentAccountRole(targetId)
            if (existingRole != null && existingRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)

            val beforeSnapshot =
                MemberChangeSnapshot(
                    displayNameChanged = false,
                    emailChanged = false,
                    status = row[MemberTable.status],
                    role = existingRole,
                )
            val displayNameChanged = row[MemberTable.displayName] != trimmedName

            MemberTable.update({ MemberTable.id eq targetId }) {
                it[MemberTable.displayName] = trimmedName
            }

            val afterSnapshot = beforeSnapshot.copy(displayNameChanged = displayNameChanged)
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
        // Welle V1.9.57 -- notices go out AFTER the commit (never inside the transaction).
        var notifyTargetOfStatusChange = false
        val result =
            peerGuarded(actor = current) {
                transaction {
                    val row =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq targetId }
                            .forMemberUpdate()
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

                    // Welle V1.4.4.5 -- plausibility only matters when the target is DECEASED.
                    if (newStatus == MemberStatus.DECEASED) {
                        MemberRoleStatusMutations.requirePlausibleDeathDate(dateOfDeath = dateOfDeath, row = row, now = now)
                    }

                    // The id-ordered union of {target account} U {every ADMIN account}, locked in ONE query (Security fix
                    // 2026-08-27, LOW deadlock: every writer of a role or a login-blocking status contends for identical rows
                    // in identical order -- updateMemberRole, the peer protection and the approved-request execution included).
                    val facts = PeerGuard.lockFactsAfterMemberLock(targetId = targetId, memberRow = row, requesterId = current.memberId)
                    val existingRole = facts.targetRole
                    MemberRoleStatusMutations.requireAdminForEscalatedTarget(actor = current, existingRole = existingRole)

                    // Welle V1.9.57 "Admin-Peer-Schutz" -- against an ADMIN target a login-blocking status needs the approval of
                    // a second administrator (never executed directly); any other status change is allowed and notified.
                    var peerFacts: PeerActionAuditFacts? = null
                    if (existingRole == AccountRole.ADMIN) {
                        val action =
                            if (newStatus in MemberStatusSets.LOGIN_BLOCKED) PeerAction.SUSPEND else PeerAction.NON_BLOCKING_STATUS
                        val decision =
                            PeerGuard.decideLocked(
                                actor = current,
                                targetId = targetId,
                                action = action,
                                mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                                facts = facts,
                            )
                        notifyTargetOfStatusChange = (decision as? PeerDecision.Allow)?.notifyTarget == true
                        peerFacts = PeerActionAuditFacts(event = PeerAuditEvent.EXECUTED, action = action, targetRole = existingRole)
                    }

                    val outcome =
                        MemberRoleStatusMutations.applyStatusChangeLocked(
                            actor = current,
                            targetId = targetId,
                            newStatus = newStatus,
                            trimmedReason = trimmedReason,
                            dateOfDeath = dateOfDeath,
                            row = row,
                            existingRole = existingRole,
                            lockedAccountRows = facts.lockedAccountRows,
                            now = now,
                            regionalChapterEnforced = regionalChapterEnforcementConfig.enabled,
                            peerFacts = peerFacts,
                        )
                    revokeSessions = outcome.revokeSessions
                    loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
                }
            }
        // resolveCurrentMember does not itself re-check MemberStatusSets.LOGIN_BLOCKED per call --
        // AuthRoutes' login gate blocks a NEW login, but does nothing about a session that already
        // existed before this decision (same gap RegistrationService.rejectApplication's own KDoc
        // documents). Revocation is the only thing that actually ends it before the 8h TTL.
        if (revokeSessions) SessionStore.revokeAllForMember(memberId = targetId)
        if (notifyTargetOfStatusChange) {
            peerNotifier.targetExecuted(
                targetId = targetId,
                actorId = current.memberId,
                event = PeerExecutedEvent.STATUS_CHANGED,
                occurredAt = now,
            )
        }
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
                    .forMemberUpdate()
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

        val now = nowLocalDateTime()
        var promotedToAdmin = false
        val result =
            peerGuarded(actor = current) {
                transaction {
                    val memberRow =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq targetId }
                            .forMemberUpdate()
                            .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                    if (memberRow[MemberTable.anonymizedAt] != null) {
                        throw ConflictException("Member has been anonymized and can no longer be edited")
                    }
                    // Letzter-Admin-Schutz, race-safe: lock the target's account row AND every ADMIN account row in a SINGLE
                    // id-ordered query (the union lock every role/status writer shares, see PeerGuard) -- `.forUpdate()` then
                    // genuinely serializes two concurrent degradations of the last two ADMIN accounts against each other.
                    val facts =
                        PeerGuard.lockFactsAfterMemberLock(
                            targetId = targetId,
                            memberRow = memberRow,
                            requesterId = current.memberId,
                        )
                    val accountRow =
                        facts.lockedAccountRows.singleOrNull { it[AccountTable.memberId] == targetId }
                            ?: throw MemberHasNoAccountException()
                    val currentRole = accountRow[AccountTable.role]

                    // Idempotent no-op: DTO back, no update, no audit entry.
                    if (newRole ==
                        currentRole
                    ) {
                        return@transaction loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
                    }

                    // Welle V1.9.57 "Admin-Peer-Schutz" -- taking the ADMIN role away from an ADMIN needs the approval of a second
                    // administrator (never executed directly); making somebody ADMIN is allowed, every OTHER administrator is told.
                    var peerFacts: PeerActionAuditFacts? = null
                    if (currentRole == AccountRole.ADMIN && newRole != AccountRole.ADMIN) {
                        PeerGuard.decideLocked(
                            actor = current,
                            targetId = targetId,
                            action = PeerAction.DEMOTE,
                            mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                            facts = facts,
                        )
                    } else if (newRole == AccountRole.ADMIN) {
                        PeerGuard.decideLocked(
                            actor = current,
                            targetId = targetId,
                            action = PeerAction.PROMOTE_TO_ADMIN,
                            mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                            facts = facts,
                        )
                        promotedToAdmin = true
                        peerFacts =
                            PeerActionAuditFacts(
                                event = PeerAuditEvent.NOTIFIED_PROMOTION,
                                action = PeerAction.PROMOTE_TO_ADMIN,
                                targetRole = newRole,
                            )
                    }

                    MemberRoleStatusMutations.applyRoleChangeLocked(
                        actor = current,
                        targetId = targetId,
                        newRole = newRole,
                        currentRole = currentRole,
                        memberRow = memberRow,
                        lockedAccountRows = facts.lockedAccountRows,
                        now = now,
                        peerFacts = peerFacts,
                    )
                    // Deliberately NO SessionStore.revokeAllForMember here -- see interface KDoc
                    // "Deliberately does NOT invalidate the target's existing sessions".
                    loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged)
                }
            }
        if (promotedToAdmin) peerNotifier.newAdministrator(newAdminId = targetId, actorId = current.memberId, occurredAt = now)
        return result
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
        val result =
            peerGuarded(actor = current) {
                transaction {
                    val memberRow =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq targetId }
                            .forMemberUpdate()
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

                    // Layer 1 of the two-layer uniqueness guard. Welle V1.9.57: the account row is now locked as part of the id-ordered union
                    // {target account} U {every ADMIN account} (the lock order every role/status writer shares) instead of a lone single-row
                    // lock. The real serialization of two concurrent grants against the SAME member comes from the member lock above, and the
                    // uq_account_member_id backstop below closes the rest.
                    val facts =
                        PeerGuard.lockFactsAfterMemberLock(
                            targetId = targetId,
                            memberRow = memberRow,
                            requesterId = current.memberId,
                        )
                    if (facts.targetRole != null) throw MemberAlreadyHasAccountException()
                    // Granting the ADMIN role is allowed for an ADMIN (and tells every OTHER administrator); decided on the locked facts.
                    if (role == AccountRole.ADMIN) {
                        PeerGuard.decideLocked(
                            actor = current,
                            targetId = targetId,
                            action = PeerAction.PROMOTE_TO_ADMIN,
                            mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                            facts = facts,
                        )
                    }

                    // Welle V1.9.56 -- a still-open address change must not be interleaved with a first login grant: a proposal
                    // made while the member had no account (path B0) would otherwise decide where the NEXT password reset of the
                    // freshly created account goes. Resolve (withdraw / let expire) the change first.
                    if (MemberEmailChangeTable
                            .selectAll()
                            .where { (MemberEmailChangeTable.openMemberId eq targetId) }
                            .count() > 0
                    ) {
                        throw ConflictException("An e-mail address change is pending for this member -- resolve it before granting access")
                    }

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
                            it[roleChangedAt] = now
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
                    // Welle V1.9.57 -- granting the ADMIN role is allowed, every OTHER administrator is told (after the commit).
                    val afterSnapshot =
                        beforeSnapshot.copy(
                            role = role,
                            peerAction =
                                if (role == AccountRole.ADMIN) {
                                    PeerActionAuditFacts(
                                        event = PeerAuditEvent.NOTIFIED_PROMOTION,
                                        action = PeerAction.PROMOTE_TO_ADMIN,
                                        targetRole = role,
                                    )
                                } else {
                                    null
                                },
                        )
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
        if (role == AccountRole.ADMIN) peerNotifier.newAdministrator(newAdminId = targetId, actorId = current.memberId, occurredAt = now)
        return result
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
            peerGuarded(actor = current) {
                transaction {
                    val memberRow =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq targetId }
                            .forMemberUpdate()
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
                    // Welle V1.9.57 -- the account row is now locked as part of the id-ordered union {target account} U {every ADMIN
                    // account} (the lock order every role/status writer shares), no longer as a lone single-row lock: that single-row
                    // order was the one remaining inversion against updateMemberRole/updateMemberStatus.
                    val facts =
                        PeerGuard.lockFactsAfterMemberLock(
                            targetId = targetId,
                            memberRow = memberRow,
                            requesterId = current.memberId,
                        )
                    val accountRole = facts.targetRole ?: throw MemberHasNoAccountException()

                    // Welle V1.9.57 "Admin-Peer-Schutz" -- against an ADMIN target a temporary password is never set directly: it
                    // needs the approval of a second administrator (see PrivilegedActionService); the stale-dialog case lands here.
                    PeerGuard.decideLocked(
                        actor = current,
                        targetId = targetId,
                        action = PeerAction.TEMP_PASSWORD,
                        mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                        facts = facts,
                    )

                    val effectivePassword = newPassword ?: TemporaryPasswordGenerator.generate()
                    TemporaryPasswordMutation.applyLocked(
                        actor = current,
                        targetId = targetId,
                        memberRow = memberRow,
                        accountRole = accountRole,
                        effectivePassword = effectivePassword,
                        auditReason = trimmedReason,
                        now = now,
                        peerFacts = null,
                    )
                    Triple(
                        effectivePassword,
                        memberRow[MemberTable.email],
                        loadMemberAdminRow(id = targetId, includeFamilyDetails = current.isPrivileged),
                    )
                }
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
        var notifyTargetAdmin = false
        val targetEmail =
            peerGuarded(actor = current) {
                transaction {
                    val memberRow =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq targetId }
                            .forMemberUpdate()
                            .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
                    if (memberRow[MemberTable.anonymizedAt] != null) {
                        throw ConflictException("Member has been anonymized and can no longer be edited")
                    }
                    // Welle V1.9.57 -- union lock (see setTemporaryPasswordForMember) instead of a lone account-row lock.
                    val facts =
                        PeerGuard.lockFactsAfterMemberLock(
                            targetId = targetId,
                            memberRow = memberRow,
                            requesterId = current.memberId,
                        )
                    val accountRole = facts.targetRole ?: throw MemberHasNoAccountException()
                    // An administrator MAY trigger a reset mail for another administrator (the reset link only helps the mailbox
                    // owner); the target is told, and the audit entry names the actor. A temporary password is the guarded path.
                    val decision =
                        PeerGuard.decideLocked(
                            actor = current,
                            targetId = targetId,
                            action = PeerAction.RESET_MAIL,
                            mailConfigured = smtpConfigState is SmtpConfigState.Configured,
                            facts = facts,
                        )
                    notifyTargetAdmin = (decision as? PeerDecision.Allow)?.notifyTarget == true
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
                            role = accountRole,
                        )
                    val afterSnapshot =
                        beforeSnapshot.copy(
                            adminPasswordAction = AdminPasswordAction.RESET_MAIL_SENT,
                            peerAction =
                                if (accountRole == AccountRole.ADMIN) {
                                    PeerActionAuditFacts(
                                        event = PeerAuditEvent.EXECUTED,
                                        action = PeerAction.RESET_MAIL,
                                        targetRole = accountRole,
                                    )
                                } else {
                                    null
                                },
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
                    memberRow[MemberTable.email]
                }
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
        if (notifyTargetAdmin) peerNotifier.resetMailTriggered(targetId = targetId, actorId = current.memberId, occurredAt = now)
        return PasswordResetMailResultDto(delivery = MailDeliveryState.HANDED_TO_SMTP)
    }

    /**
     * Welle "Digitaler Mitgliedsausweis (PDF)" -- see [IMemberService.reissueMemberCard]. Thin by
     * design: every rule that matters (member-row lock, eligibility, revoke-then-mint, the
     * `member_number` allocation a first card triggers) lives in
     * [network.lapis.cloud.server.member.MemberCardIssuance], shared verbatim with the PDF download
     * route so the two can never drift into two different notions of "issue a card".
     *
     * **Security fix (Review MAJOR, 2026-09)** -- rate-limited against [memberCardIssueRateLimiter]
     * BEFORE `MemberCardIssuance.rotate` runs, using the exact same limiter instance and key
     * (`"member-card:<targetId>"`) as `POST /api/members/{id}/card.pdf`. Without this check this
     * RPC path could rotate the target's bearer credential an unbounded number of times, entirely
     * bypassing the download route's documented budget -- see [memberCardIssueRateLimiter] KDoc.
     *
     * The audit entry deliberately reuses [AuditEntityType.MEMBER] with a short literal marker
     * rather than introducing a new `AuditEntityType` -- a new enum constant would mean a schema/
     * model change (`14-audit-log.kuml.kts` + `AuditLogSchemaDriftTest` pin the constant list) for
     * a fact that is already about a member. The raw code is never recorded.
     */
    override suspend fun reissueMemberCard(memberId: String): MemberCardReissueResultDto {
        val current = resolveCurrentMember(call)
        val targetId = memberId.toMemberUuidOrThrow()
        if (targetId != current.memberId && !current.isPrivileged) throw ForbiddenException()
        if (!memberCardIssueRateLimiter.checkAndRecord("member-card:$targetId")) {
            throw ConflictException("Zu viele Ausweis-Anfragen -- bitte spaeter erneut versuchen.")
        }
        return transaction {
            val now = nowLocalDateTime()
            val issued = MemberCardIssuance.rotate(memberId = targetId, now = now)
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBER,
                entityId = targetId,
                action = AuditAction.UPDATE,
                after = if (issued.revoked) MEMBER_CARD_AUDIT_REISSUED else MEMBER_CARD_AUDIT_ISSUED,
                occurredAt = now,
            )
            MemberCardReissueResultDto(
                memberId = targetId.toString(),
                memberNumber = issued.card.memberNumber,
                issuedAt = now,
                previousCardRevoked = issued.revoked,
            )
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
                today = OrganizationTimeZone.dateOf(now),
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
        // Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- another PK-side, at-most-1:1
        // LEFT JOIN (regionalChapterId -> regional_chapter.id), same "row multiplication
        // structurally excluded" reasoning the KDoc above already gives for MembershipTierTable.
        .join(RegionalChapterTable, JoinType.LEFT, MemberTable.regionalChapterId, RegionalChapterTable.id)
        // Welle V1.9.20 -- two more at-most-1:1 LEFT JOINs (`uq_member_photo_member` and
        // `uq_member_public_bio_member` guarantee one row per member_id) that only feed the two
        // moderation-presence flags of the roster; never any content.
        .join(MemberPhotoTable, JoinType.LEFT, MemberTable.id, MemberPhotoTable.memberId)
        .join(MemberPublicBioTable, JoinType.LEFT, MemberTable.id, MemberPublicBioTable.memberId)

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
private fun ResultRow.toMemberAdminRowDto(
    includeFamilyDetails: Boolean,
    /**
     * Welle V1.9.13 -- `true` iff this row is being rendered for a chapter-scoped officer (see
     * `network.lapis.cloud.server.security.MemberVisibility.Chapter`). Nulls `role`/
     * `membershipTierId`/`membershipTierName`/`familyId`/`familyName`/`familyRole`/
     * `externalReference`/`dateOfDeath` -- see [IMemberService.listMembersForAdministration]'s own
     * KDoc "review fix (doc, stale since that wave)" for the full per-field rationale, and
     * `docs/architecture/regional-chapters.adoc` (Welle V1.9.14) for the visibility-boundary field
     * table. Deliberately does NOT null `regionalChapterId`/`regionalChapterName` -- see those
     * fields' own KDoc.
     */
    chapterScoped: Boolean = false,
): MemberAdminRowDto =
    MemberAdminRowDto(
        id = this[MemberTable.id].toString(),
        displayName = this[MemberTable.displayName],
        email = this[MemberTable.email],
        status = this[MemberTable.status],
        role = if (chapterScoped) null else this.getOrNull(AccountTable.role),
        joinedAt = this[MemberTable.joinedAt],
        externalReference = if (chapterScoped) null else this[MemberTable.externalReference],
        anonymized = this[MemberTable.anonymizedAt] != null,
        membershipTierId = if (chapterScoped) null else this[MemberTable.membershipTierId]?.toString(),
        membershipTierName = if (chapterScoped) null else this.getOrNull(MembershipTierTable.name),
        familyId = if (includeFamilyDetails && !chapterScoped) this.getOrNull(MemberFamilyLinkTable.familyId)?.toString() else null,
        familyName = if (includeFamilyDetails && !chapterScoped) this.getOrNull(MemberFamilyTable.name) else null,
        familyRole = if (includeFamilyDetails && !chapterScoped) this.getOrNull(MemberFamilyLinkTable.role) else null,
        dateOfDeath = if (chapterScoped) null else this[MemberTable.dateOfDeath],
        regionalChapterId = this[MemberTable.regionalChapterId]?.toString(),
        regionalChapterName = this.getOrNull(RegionalChapterTable.name),
        // Welle V1.9.20 -- presence flags only, for the BOARD/ADMIN moderation buttons. `includeFamilyDetails`
        // is, at every call site, exactly `current.isPrivileged` (BOARD/ADMIN) -- the same gate the
        // moderation RPCs themselves use; a TREASURER or chapter-scoped officer always gets `false`.
        hasPhoto = includeFamilyDetails && !chapterScoped && this.getOrNull(MemberPhotoTable.id) != null,
        hasPublicBio = includeFamilyDetails && !chapterScoped && this.getOrNull(MemberPublicBioTable.id) != null,
    )

private fun loadMemberAdminRow(
    id: Uuid,
    includeFamilyDetails: Boolean,
): MemberAdminRowDto =
    adminRosterSource
        .selectAll()
        .where { MemberTable.id eq id }
        .single()
        .toMemberAdminRowDto(includeFamilyDetails = includeFamilyDetails)

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
        regionalChapterId = this[MemberTable.regionalChapterId]?.toString(),
    )

/** Welle V1.9.36 -- upper bound of [MemberService.listMembersForSelection]. */
internal const val MAX_MEMBER_SELECTION = 5000

/** Welle V1.9.33 -- value-free audit markers for the self-service address / GwG edits. */
internal const val MEMBER_ADDRESS_AUDIT_UPDATED = AuditMarkers.MEMBER_ADDRESS_UPDATED
internal const val MEMBER_BENEFICIAL_OWNER_AUDIT_UPDATED = AuditMarkers.MEMBER_BENEFICIAL_OWNER_UPDATED

/** Welle V1.9.35 -- value-free marker for a BOARD/ADMIN read of another member's address / GwG data. */
internal const val MEMBER_ADDRESS_AUDIT_READ = AuditMarkers.MEMBER_ADDRESS_READ
