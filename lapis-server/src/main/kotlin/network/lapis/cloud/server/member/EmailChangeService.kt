package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.EmailChangeMailer
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.mail.maskEmailForLogging
import network.lapis.cloud.server.rpc.MEMBER_EMAIL_MAX_LENGTH
import network.lapis.cloud.server.rpc.toMemberDto
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.EmailChangeAuditEvent
import network.lapis.cloud.shared.domain.EmailChangeAuditFacts
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangeLinkActor
import network.lapis.cloud.shared.domain.EmailChangePendingDto
import network.lapis.cloud.shared.domain.EmailChangeStatus
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OwnEmailChangeResultDto
import network.lapis.cloud.shared.domain.OwnPendingEmailChangeDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.EmailChangeAlreadyCurrentException
import network.lapis.cloud.shared.rpc.EmailChangeMailUnavailableException
import network.lapis.cloud.shared.rpc.EmailChangeNotAllowedException
import network.lapis.cloud.shared.rpc.EmailChangePendingNotFoundException
import network.lapis.cloud.shared.rpc.EmailChangeRateLimitedException
import network.lapis.cloud.shared.rpc.EmailChangeRepeatMismatchException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.InvalidPasswordException
import network.lapis.cloud.shared.rpc.MemberEmailInUseException
import network.lapis.cloud.shared.rpc.MemberEmailTooLongException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Result of a link action (`/api/auth/email-change/confirm|revoke`). [OK] = done (the change was applied or rejected),
 * [CONFIRMED_PENDING] = the new address is confirmed but the change takes effect only after the warning period. [INVALID] is the single answer for wrong, used, expired and foreign tokens. */
internal enum class LinkResult { OK, CONFIRMED_PENDING, INVALID, WRONG_PASSWORD, RATE_LIMITED, ADDRESS_UNAVAILABLE }

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the domain logic of every address change of an existing member. Shared by
 * the RPC facade (`MemberEmailChangeService`), the unauthenticated link routes (`AuthRoutes`) and the poller
 * ([EmailChangePoller]); no RPC dependency of its own.
 *
 * `member.email` stays the only login and password-reset identity; a pending change redirects neither (login, reset and
 * the Keycloak link all read `member.email`). A third party never changes it immediately: see
 * `docs/architecture/member-email-change.adoc` for the path table (A self, B proposal with password, B0 proposal without
 * password, C emergency, D reject/withdraw) and the threat model.
 *
 * **Layering of every write**: validate -> authorize -> (mail configured?) -> password check OUTSIDE the transaction
 * (bcrypt never runs under a row lock) -> `transaction { lock member; re-check; write; audit }` -> AFTER the commit:
 * mails, session and token invalidation (no external effect inside a transaction). Raw tokens are local variables
 * between insert and mail hand-off and never reach a log, an audit snapshot or a return value.
 *
 * **Lock order** is always `member` (target) then `member_email_change`; the account row is READ without a lock while
 * the member lock is held (every account/role writer takes that member lock first, so the role cannot change
 * underneath), and `AuditLogRecorder.record` is the last lock-taking call.
 */
internal class EmailChangeService(
    private val smtpConfigState: SmtpConfigState,
    private val keycloakEnabled: Boolean,
    private val mailer: EmailChangeMailer,
    private val friendVerificationMailer: FriendVerificationMailer,
    /** Per-target cap on proposals (singleton; `EmailChangeService` is rebuilt per RPC call). */
    private val proposalTargetRateLimiter: FederationInboxRateLimiter,
    /** Per-initiator cap on proposals (singleton). */
    private val proposalActorRateLimiter: FederationInboxRateLimiter,
    /** Failed password attempts per member and per change (singleton, 5 per 15 minutes by default). */
    private val passwordAttemptRateLimiter: LoginRateLimiter,
    /** Per-FRIEND cap on re-sent verification mails after a path-A change (singleton). */
    private val friendMailTargetRateLimiter: FederationInboxRateLimiter,
    /** Per-actor cap, see [friendMailTargetRateLimiter] (singleton). */
    private val friendMailActorRateLimiter: FederationInboxRateLimiter,
    /**
     * Wrong passwords per proposal link that do NOT decay: the window outlives the proposal, so the fifth wrong password
     * burns the change however slowly the guesses were spread out (the per-window [passwordAttemptRateLimiter] alone
     * would allow ~20 guesses per hour for the whole seven days).
     */
    private val linkBurnLimiter: LoginRateLimiter = LoginRateLimiter(maxFailures = 5, window = EmailChangeStore.PROPOSAL_TTL + 1.days),
) {
    private val mailConfigured: Boolean get() = smtpConfigState is SmtpConfigState.Configured

    private val mailDeliveryState: MailDeliveryState
        get() = if (mailConfigured) MailDeliveryState.HANDED_TO_SMTP else MailDeliveryState.NOT_CONFIGURED

    // ------------------------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------------------------

    fun capability(actor: CurrentMember): EmailChangeCapabilityDto {
        val own =
            transaction {
                val member = MemberTable.selectAll().where { MemberTable.id eq actor.memberId }.singleOrNull()
                member != null && ownChangeAllowed(member = member, facts = accountFacts(actor.memberId))
            }
        return EmailChangeCapabilityDto(mailDelivery = mailDeliveryState, ownChangeAvailable = own)
    }

    fun ownPending(actor: CurrentMember): OwnPendingEmailChangeDto? {
        val now = nowUtc()
        return transaction {
            EmailChangeStore.openChangeUnexpired(memberId = actor.memberId, now = now)?.let { row ->
                val kind = EmailChangeKind.valueOf(row[MemberEmailChangeTable.kind])
                OwnPendingEmailChangeDto(
                    changeId = row[MemberEmailChangeTable.id].toString(),
                    newEmail = row[MemberEmailChangeTable.pendingEmail],
                    kind = kind,
                    expiresAt = row[MemberEmailChangeTable.expiresAt],
                    effectiveAt = row[MemberEmailChangeTable.effectiveAt],
                    requiresPassword = kind == EmailChangeKind.PROPOSAL,
                )
            }
        }
    }

    fun pendingForAdministration(
        actor: CurrentMember,
        targetIdRaw: String,
    ): EmailChangePendingDto? {
        requireInitiatorRole(actor = actor, override = false)
        val targetId = parseMemberId(targetIdRaw)
        if (targetId == actor.memberId) return null
        val now = nowUtc()
        return transaction {
            val targetRole = accountFacts(targetId).role
            // A BOARD caller does not see (and cannot act on) a change of a BOARD/TREASURER/ADMIN account.
            if (targetRole != null && targetRole in ESCALATED_ROLES && actor.role != AccountRole.ADMIN) return@transaction null
            EmailChangeStore.openChangeUnexpired(memberId = targetId, now = now)?.toPendingDto(actor)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Path A -- the owner changes their own address with the password
    // ------------------------------------------------------------------------------------------------------------

    fun changeOwn(
        actor: CurrentMember,
        currentPassword: String,
        newEmail: String,
        newEmailRepeat: String,
        ownRawSessionToken: String?,
    ): OwnEmailChangeResultDto {
        val normalized = validateNewEmail(newEmail = newEmail, newEmailRepeat = newEmailRepeat)
        val before =
            transaction {
                val member = MemberTable.selectAll().where { MemberTable.id eq actor.memberId }.singleOrNull()
                if (member == null) null else member to accountFacts(actor.memberId)
            } ?: throw NotFoundException("Member not found")
        if (!ownChangeAllowed(member = before.first, facts = before.second)) throw EmailChangeNotAllowedException()

        checkPassword(memberId = actor.memberId, password = currentPassword, storedHash = before.second.passwordHash)

        val now = nowUtc()
        val done =
            transaction {
                val member = EmailChangeStore.lockMember(actor.memberId) ?: throw NotFoundException("Member not found")
                val facts = accountFacts(actor.memberId)
                // The password was verified against the hash read before the lock -- a concurrent password change
                // in between means the proof no longer holds.
                if (facts.passwordHash != before.second.passwordHash ||
                    !ownChangeAllowed(member = member, facts = facts)
                ) {
                    throw InvalidPasswordException()
                }
                val oldEmail = member[MemberTable.email]
                if (oldEmail.lowercase() == normalized) throw EmailChangeAlreadyCurrentException()
                if (usedByAnotherMember(normalized = normalized, memberId = actor.memberId)) throw MemberEmailInUseException()

                val superseded = EmailChangeStore.supersedeOpenLocked(memberId = actor.memberId, now = now)
                val changeId = EmailChangeStore.insertAppliedSelf(memberId = actor.memberId, pendingEmail = normalized, now = now)
                if (EmailChangeStore.applyLocked(
                        memberId = actor.memberId,
                        changeId = changeId,
                        newEmail = normalized,
                        verified = false,
                        now = now,
                    ) ==
                    ApplyOutcome.Duplicate
                ) {
                    throw MemberEmailInUseException()
                }
                // The audit entries come LAST: AuditLogRecorder takes the chain lock and must be the final lock-taking step (the
                // update of member.email above can wait on another transaction's unique-index entry; holding the chain lock while
                // waiting for it would be a deadlock with that transaction's own audit write).
                recordSuperseded(
                    superseded = superseded,
                    memberId = actor.memberId,
                    member = member,
                    role = facts.role,
                    actor = actor,
                    now = now,
                )
                recordAudit(
                    actor = actor,
                    targetId = actor.memberId,
                    status = member[MemberTable.status],
                    role = facts.role,
                    facts =
                        EmailChangeAuditFacts(
                            event = EmailChangeAuditEvent.APPLIED,
                            kind = EmailChangeKind.SELF,
                            changeId = changeId.toString(),
                        ),
                    emailChanged = true,
                    reason = null,
                    now = now,
                )
                oldEmail to member[MemberTable.status]
            }
        val (oldEmail, status) = done

        invalidateAfterAddressChange(memberId = actor.memberId, exceptRawToken = ownRawSessionToken)
        if (status == MemberStatus.FRIEND &&
            mailConfigured
        ) {
            sendFriendVerification(actorId = actor.memberId, targetId = actor.memberId, email = normalized)
        }
        val notified =
            if (mailConfigured) {
                runCatching { mailer.sendSelfChangeInfo(email = oldEmail, maskedNewEmail = maskEmailForLogging(normalized)) }
                    .onFailure { e -> logger.error { "emailChangeMailer.sendSelfChangeInfo threw: ${e::class.simpleName}" } }
                MailDeliveryState.HANDED_TO_SMTP
            } else {
                MailDeliveryState.NOT_CONFIGURED
            }
        return OwnEmailChangeResultDto(oldAddressNotified = notified)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Paths B / B0 / C -- a third party starts a change
    // ------------------------------------------------------------------------------------------------------------

    fun propose(
        actor: CurrentMember,
        targetIdRaw: String,
        newEmail: String,
        newEmailRepeat: String,
    ): EmailChangePendingDto =
        createThirdPartyChange(
            actor = actor,
            targetIdRaw = targetIdRaw,
            newEmail = newEmail,
            newEmailRepeat = newEmailRepeat,
            override = false,
            reasonRaw = null,
        )

    fun requestOverride(
        actor: CurrentMember,
        targetIdRaw: String,
        newEmail: String,
        newEmailRepeat: String,
        reason: String,
    ): EmailChangePendingDto =
        createThirdPartyChange(
            actor = actor,
            targetIdRaw = targetIdRaw,
            newEmail = newEmail,
            newEmailRepeat = newEmailRepeat,
            override = true,
            reasonRaw = reason,
        )

    private fun createThirdPartyChange(
        actor: CurrentMember,
        targetIdRaw: String,
        newEmail: String,
        newEmailRepeat: String,
        override: Boolean,
        reasonRaw: String?,
    ): EmailChangePendingDto {
        requireInitiatorRole(actor = actor, override = override)
        val targetId = parseMemberId(targetIdRaw)
        if (targetId == actor.memberId) throw EmailChangeNotAllowedException()
        val normalized = validateNewEmail(newEmail = newEmail, newEmailRepeat = newEmailRepeat)
        val reason =
            if (override) {
                val trimmed = (reasonRaw ?: "").trim()
                if (trimmed.length < OVERRIDE_REASON_MIN_LENGTH || trimmed.length > OVERRIDE_REASON_MAX_LENGTH) {
                    throw BadRequestException("A reason is required ($OVERRIDE_REASON_MIN_LENGTH-$OVERRIDE_REASON_MAX_LENGTH characters)")
                }
                trimmed
            } else {
                null
            }
        // Without a working mail transport there is no warning to the old address -- no third-party path is safe then.
        if (!mailConfigured) throw EmailChangeMailUnavailableException()
        val now = nowUtc()
        val confirmRaw = EmailChangeTokens.newRawToken()
        val revokeRaw = EmailChangeTokens.newRawToken()
        val created =
            transaction {
                val member = EmailChangeStore.lockMember(targetId) ?: throw NotFoundException("Member $targetIdRaw not found")
                requireEligibleTarget(member)
                val facts = accountFacts(targetId)
                // Peer protection, re-read UNDER the member lock (a concurrent role change needs that lock too).
                if (facts.role != null && facts.role in ESCALATED_ROLES && actor.role != AccountRole.ADMIN) throw ForbiddenException()
                val oldEmail = member[MemberTable.email]
                if (oldEmail.lowercase() == normalized) throw EmailChangeAlreadyCurrentException()
                if (usedByAnotherMember(normalized = normalized, memberId = targetId)) throw MemberEmailInUseException()

                // The budgets are consumed only now, after authorization and eligibility passed: a caller who may not touch this
                // target must not be able to burn its proposal budget. Both counters run unconditionally (no short-circuit), so
                // cycling either side alone cannot dodge the other; throwing here rolls the transaction back (nothing written yet).
                val actorAllowed = proposalActorRateLimiter.checkAndRecord("actor:${actor.memberId}")
                val targetAllowed = proposalTargetRateLimiter.checkAndRecord("target:$targetId")
                if (!actorAllowed || !targetAllowed) throw EmailChangeRateLimitedException()

                val superseded = EmailChangeStore.supersedeOpenLocked(memberId = targetId, now = now)
                val kind =
                    when {
                        override -> EmailChangeKind.ADMIN_OVERRIDE
                        passwordRequired(facts) -> EmailChangeKind.PROPOSAL
                        else -> EmailChangeKind.PROPOSAL_NO_ACCOUNT
                    }
                val effectiveAt =
                    if (kind ==
                        EmailChangeKind.PROPOSAL
                    ) {
                        null
                    } else {
                        EmailChangeStore.plus(at = now, duration = EmailChangeStore.OVERRIDE_DELAY)
                    }
                val expiresAt =
                    EmailChangeStore.plus(
                        at = now,
                        duration = if (kind == EmailChangeKind.PROPOSAL) EmailChangeStore.PROPOSAL_TTL else EmailChangeStore.OVERRIDE_TTL,
                    )
                val changeId =
                    EmailChangeStore.insertPending(
                        memberId = targetId,
                        pendingEmail = normalized,
                        kind = kind,
                        requestedBy = actor.memberId,
                        reason = reason,
                        confirmHash = EmailChangeTokens.hash(confirmRaw),
                        revokeHash = EmailChangeTokens.hash(revokeRaw),
                        now = now,
                        expiresAt = expiresAt,
                        effectiveAt = effectiveAt,
                    )
                recordSuperseded(superseded = superseded, memberId = targetId, member = member, role = facts.role, actor = actor, now = now)
                recordAudit(
                    actor = actor,
                    targetId = targetId,
                    status = member[MemberTable.status],
                    role = facts.role,
                    facts = EmailChangeAuditFacts(event = EmailChangeAuditEvent.REQUESTED, kind = kind, changeId = changeId.toString()),
                    emailChanged = false,
                    reason = reason,
                    now = now,
                )
                Created(changeId = changeId, kind = kind, oldEmail = oldEmail, expiresAt = expiresAt, effectiveAt = effectiveAt)
            }

        // The warning to the OLD address is the only safety net of the third-party paths, so it goes out FIRST and must be
        // actually accepted by the mail queue. If it was not (queue saturated, mailer threw), the change is withdrawn and the
        // confirm token never leaves the server -- a change nobody was warned about must never become confirmable.
        val warningStatus =
            runCatching {
                mailer.sendWarningToOldAddress(
                    email = created.oldEmail,
                    rawRevokeToken = revokeRaw,
                    kind = created.kind,
                    maskedNewEmail = maskEmailForLogging(normalized),
                    effectiveAt = created.effectiveAt,
                )
            }.onFailure { e -> logger.error { "emailChangeMailer.sendWarningToOldAddress threw: ${e::class.simpleName}" } }
                .getOrNull()
        if (warningStatus != DeliveryStatus.SENT) {
            withdrawUnwarnedChange(actor = actor, targetId = targetId, changeId = created.changeId)
            throw EmailChangeMailUnavailableException()
        }
        runCatching {
            mailer.sendConfirmToNewAddress(
                email = normalized,
                rawToken = confirmRaw,
                kind = created.kind,
                effectiveAt = created.effectiveAt,
            )
        }.onFailure { e -> logger.error { "emailChangeMailer.sendConfirmToNewAddress threw: ${e::class.simpleName}" } }

        return EmailChangePendingDto(
            changeId = created.changeId.toString(),
            newEmailMasked = maskEmailForLogging(normalized),
            kind = created.kind,
            expiresAt = created.expiresAt,
            effectiveAt = created.effectiveAt,
            newEmailConfirmed = false,
        )
    }

    /** Ends a freshly created change whose warning mail to the old address could not be handed off (see [createThirdPartyChange]). */
    private fun withdrawUnwarnedChange(
        actor: CurrentMember,
        targetId: Uuid,
        changeId: Uuid,
    ) {
        val now = nowUtc()
        runCatching {
            transaction {
                val member = EmailChangeStore.lockMember(targetId) ?: return@transaction
                val change = EmailChangeStore.changeByIdLocked(changeId) ?: return@transaction
                if (change[MemberEmailChangeTable.status] != EmailChangeStatus.PENDING.name) return@transaction
                finish(
                    member = member,
                    change = change,
                    status = EmailChangeStatus.WITHDRAWN,
                    event = EmailChangeAuditEvent.WITHDRAWN,
                    actor = actor,
                    linkActor = null,
                    now = now,
                )
            }
        }.onFailure { e -> logger.error { "withdrawing unwarned email change failed: ${e::class.simpleName}" } }
    }

    private class Created(
        val changeId: Uuid,
        val kind: EmailChangeKind,
        val oldEmail: String,
        val expiresAt: LocalDateTime,
        val effectiveAt: LocalDateTime?,
    )

    // ------------------------------------------------------------------------------------------------------------
    // The owner answers a change
    // ------------------------------------------------------------------------------------------------------------

    fun acceptOwn(
        actor: CurrentMember,
        changeIdRaw: String,
        currentPassword: String,
        ownRawSessionToken: String?,
    ): MemberDto {
        val changeId = parseChangeId(changeIdRaw)
        val passwordHash =
            transaction {
                val change = EmailChangeStore.changeById(changeId)
                if (change == null || change[MemberEmailChangeTable.memberId] != actor.memberId) throw EmailChangePendingNotFoundException()
                if (change[MemberEmailChangeTable.kind] != EmailChangeKind.PROPOSAL.name) throw EmailChangePendingNotFoundException()
                accountFacts(actor.memberId).passwordHash
            }
        checkPassword(memberId = actor.memberId, password = currentPassword, storedHash = passwordHash)

        val now = nowUtc()
        val report =
            transaction {
                val member = EmailChangeStore.lockMember(actor.memberId) ?: throw EmailChangePendingNotFoundException()
                val change = EmailChangeStore.changeByIdLocked(changeId) ?: throw EmailChangePendingNotFoundException()
                requireOpenChangeOf(change = change, memberId = actor.memberId, now = now)
                if (change[MemberEmailChangeTable.kind] != EmailChangeKind.PROPOSAL.name) throw EmailChangePendingNotFoundException()
                if (accountFacts(actor.memberId).passwordHash != passwordHash) throw InvalidPasswordException()
                // Not verified: accepting inside a session proves no ownership of the new address.
                applyChangeLocked(member = member, change = change, verified = false, now = now, actor = actor, linkActor = null)
            }
        when (report.result) {
            ApplyResult.APPLIED -> Unit
            ApplyResult.CONFLICT -> throw MemberEmailInUseException()
            ApplyResult.DISCARDED -> throw EmailChangePendingNotFoundException()
        }
        afterApplied(report = report, exceptRawToken = ownRawSessionToken, verified = false, actorMemberId = actor.memberId)
        return transaction {
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq actor.memberId }
                .single()
                .toMemberDto()
        }
    }

    fun declineOwn(
        actor: CurrentMember,
        changeIdRaw: String,
    ) {
        val changeId = parseChangeId(changeIdRaw)
        val now = nowUtc()
        transaction {
            val member = EmailChangeStore.lockMember(actor.memberId) ?: throw EmailChangePendingNotFoundException()
            val change = EmailChangeStore.changeByIdLocked(changeId) ?: throw EmailChangePendingNotFoundException()
            requireOpenChangeOf(change = change, memberId = actor.memberId, now = now)
            finish(
                member = member,
                change = change,
                status = EmailChangeStatus.REVOKED,
                event = EmailChangeAuditEvent.REVOKED,
                actor = actor,
                linkActor = null,
                now = now,
            )
        }
    }

    /** The initiator or an ADMIN withdraws the open change. */
    fun withdraw(
        actor: CurrentMember,
        changeIdRaw: String,
    ) {
        if (actor.role != AccountRole.ADMIN && actor.role != AccountRole.BOARD) throw ForbiddenException()
        val changeId = parseChangeId(changeIdRaw)
        val now = nowUtc()
        transaction {
            val memberId =
                EmailChangeStore.changeById(changeId)?.get(MemberEmailChangeTable.memberId) ?: throw EmailChangePendingNotFoundException()
            val member = EmailChangeStore.lockMember(memberId) ?: throw EmailChangePendingNotFoundException()
            val change = EmailChangeStore.changeByIdLocked(changeId) ?: throw EmailChangePendingNotFoundException()
            if (change[MemberEmailChangeTable.status] != EmailChangeStatus.PENDING.name) throw EmailChangePendingNotFoundException()
            val isInitiator = change[MemberEmailChangeTable.requestedBy] == actor.memberId
            if (!isInitiator && actor.role != AccountRole.ADMIN) throw EmailChangePendingNotFoundException()
            finish(
                member = member,
                change = change,
                status = EmailChangeStatus.WITHDRAWN,
                event = EmailChangeAuditEvent.WITHDRAWN,
                actor = actor,
                linkActor = null,
                now = now,
            )
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Link actions (unauthenticated)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Link to the NEW address. [EmailChangeKind.PROPOSAL]: [password] is mandatory and proves the owner; the other kinds
     * must not carry one -- the link proves ownership of the new address, and the change takes effect when the warning
     * period has elapsed (immediately when it already has).
     */
    fun confirmByLink(
        rawToken: String,
        password: String?,
    ): LinkResult {
        val now = nowUtc()
        val hash = EmailChangeTokens.hash(rawToken)
        val peeked = transaction { EmailChangeStore.findByConfirmHash(hash = hash, now = now) }
        if (peeked == null) {
            // Same work for an unknown token as for a known one, so the response time does not tell them apart.
            if (password != null) PasswordHasher.verify(rawPassword = password, storedHash = null)
            return LinkResult.INVALID
        }
        val changeId = peeked[MemberEmailChangeTable.id]
        val memberId = peeked[MemberEmailChangeTable.memberId]
        val kind = EmailChangeKind.valueOf(peeked[MemberEmailChangeTable.kind])

        var verifiedPasswordHash: String? = null
        if (kind == EmailChangeKind.PROPOSAL) {
            if (password.isNullOrEmpty()) return LinkResult.INVALID
            val key = "change:$changeId"
            val memberKey = "member:$memberId"
            // Attempts are RESERVED atomically before the slow verify (check-then-record let N parallel requests all pass the
            // check); a reserved attempt that fails verification simply stays counted, a successful one clears the window keys.
            if (!linkBurnLimiter.tryAcquire(key)) {
                invalidateExhausted(changeId = changeId, memberId = memberId)
                return LinkResult.RATE_LIMITED
            }
            if (!passwordAttemptRateLimiter.tryAcquire(key)) {
                return LinkResult.RATE_LIMITED
            }
            if (!passwordAttemptRateLimiter.tryAcquire(memberKey)) {
                passwordAttemptRateLimiter.release(key)
                return LinkResult.RATE_LIMITED
            }
            val storedHash = transaction { accountFacts(memberId).passwordHash }
            if (!PasswordHasher.verify(rawPassword = password, storedHash = storedHash)) {
                // The fifth wrong password burns the change right away (no sixth attempt needed).
                if (!linkBurnLimiter.checkAllowed(key)) invalidateExhausted(changeId = changeId, memberId = memberId)
                return LinkResult.WRONG_PASSWORD
            }
            passwordAttemptRateLimiter.reset(key)
            passwordAttemptRateLimiter.reset(memberKey)
            verifiedPasswordHash = storedHash
        } else if (password != null) {
            return LinkResult.INVALID
        }

        val report: LinkReport? =
            transaction {
                val member = EmailChangeStore.lockMember(memberId) ?: return@transaction null
                val change = EmailChangeStore.changeByIdLocked(changeId) ?: return@transaction null
                if (!isOpenAndUnexpired(change = change, now = now) ||
                    !EmailChangeTokens.matches(storedHash = change[MemberEmailChangeTable.confirmTokenHash], raw = rawToken)
                ) {
                    return@transaction null
                }
                if (kind == EmailChangeKind.PROPOSAL) {
                    if (accountFacts(memberId).passwordHash != verifiedPasswordHash) return@transaction LinkReport.WrongPassword
                    return@transaction LinkReport.Applied(
                        applyChangeLocked(
                            member = member,
                            change = change,
                            verified = true,
                            now = now,
                            actor = null,
                            linkActor = EmailChangeLinkActor.NEW_ADDRESS,
                        ),
                    )
                }
                val firstConfirmation = change[MemberEmailChangeTable.newEmailConfirmedAt] == null
                if (firstConfirmation) EmailChangeStore.markNewAddressConfirmedLocked(changeId = changeId, now = now)
                // The audit entry "new address confirmed" is written AFTER the (possible) update of member.email and still BEFORE the
                // entry of the application itself: AuditLogRecorder takes the chain lock and must be the last lock-taking step.
                val confirmedAudit = {
                    if (firstConfirmation) {
                        recordAudit(
                            actor = null,
                            targetId = memberId,
                            status = member[MemberTable.status],
                            role = accountFacts(memberId).role,
                            facts =
                                EmailChangeAuditFacts(
                                    event = EmailChangeAuditEvent.NEW_ADDRESS_CONFIRMED,
                                    kind = kind,
                                    changeId = changeId.toString(),
                                    linkActor = EmailChangeLinkActor.NEW_ADDRESS,
                                ),
                            emailChanged = false,
                            reason = null,
                            now = now,
                        )
                    }
                }
                val effectiveAt = change[MemberEmailChangeTable.effectiveAt]
                if (effectiveAt != null && effectiveAt <= now) {
                    LinkReport.Applied(
                        applyChangeLocked(
                            member = member,
                            change = change,
                            verified = true,
                            now = now,
                            actor = null,
                            linkActor = EmailChangeLinkActor.NEW_ADDRESS,
                            beforeAudit = confirmedAudit,
                        ),
                    )
                } else {
                    confirmedAudit()
                    LinkReport.Confirmed
                }
            }
        return when (report) {
            null -> LinkResult.INVALID
            LinkReport.WrongPassword -> LinkResult.WRONG_PASSWORD
            LinkReport.Confirmed -> LinkResult.CONFIRMED_PENDING
            is LinkReport.Applied ->
                when (report.report.result) {
                    ApplyResult.APPLIED -> {
                        afterApplied(report = report.report, exceptRawToken = null, verified = true, actorMemberId = null)
                        LinkResult.OK
                    }
                    ApplyResult.CONFLICT -> LinkResult.ADDRESS_UNAVAILABLE
                    ApplyResult.DISCARDED -> LinkResult.INVALID
                }
        }
    }

    private sealed interface LinkReport {
        data object WrongPassword : LinkReport

        data object Confirmed : LinkReport

        class Applied(
            val report: ApplyReport,
        ) : LinkReport
    }

    /** Link to the OLD address -- rejects the change without any sign-in. */
    fun revokeByLink(rawToken: String): LinkResult {
        val now = nowUtc()
        val hash = EmailChangeTokens.hash(rawToken)
        val peeked = transaction { EmailChangeStore.findByRevokeHash(hash = hash, now = now) } ?: return LinkResult.INVALID
        val changeId = peeked[MemberEmailChangeTable.id]
        val memberId = peeked[MemberEmailChangeTable.memberId]
        val done =
            transaction {
                val member = EmailChangeStore.lockMember(memberId) ?: return@transaction false
                val change = EmailChangeStore.changeByIdLocked(changeId) ?: return@transaction false
                if (!isOpenAndUnexpired(change = change, now = now) ||
                    !EmailChangeTokens.matches(storedHash = change[MemberEmailChangeTable.revokeTokenHash], raw = rawToken)
                ) {
                    return@transaction false
                }
                finish(
                    member = member,
                    change = change,
                    status = EmailChangeStatus.REVOKED,
                    event = EmailChangeAuditEvent.REVOKED,
                    actor = null,
                    linkActor = EmailChangeLinkActor.OLD_ADDRESS,
                    now = now,
                )
                true
            }
        return if (done) LinkResult.OK else LinkResult.INVALID
    }

    // ------------------------------------------------------------------------------------------------------------
    // Poller
    // ------------------------------------------------------------------------------------------------------------

    /** Expires overdue changes, applies confirmed path B0/C changes whose warning period elapsed, purges old resolved rows. */
    fun runDue(now: LocalDateTime): Int {
        var touched = 0
        transaction { EmailChangeStore.expiredPendingIds(now) }.forEach { changeId ->
            runCatching {
                transaction {
                    val memberId = EmailChangeStore.changeById(changeId)?.get(MemberEmailChangeTable.memberId) ?: return@transaction false
                    val member = EmailChangeStore.lockMember(memberId) ?: return@transaction false
                    val change = EmailChangeStore.changeByIdLocked(changeId) ?: return@transaction false
                    if (change[MemberEmailChangeTable.status] != EmailChangeStatus.PENDING.name ||
                        change[MemberEmailChangeTable.expiresAt] > now
                    ) {
                        return@transaction false
                    }
                    finish(
                        member = member,
                        change = change,
                        status = EmailChangeStatus.EXPIRED,
                        event = EmailChangeAuditEvent.EXPIRED,
                        actor = null,
                        linkActor = null,
                        now = now,
                    )
                    true
                }
            }.onSuccess { if (it) touched++ }
                .onFailure { e -> logger.error { "email change expiry failed (changeId=$changeId): ${e::class.simpleName}" } }
        }
        transaction { EmailChangeStore.applicableIds(now) }.forEach { changeId ->
            runCatching {
                val report =
                    transaction {
                        val memberId =
                            EmailChangeStore.changeById(changeId)?.get(MemberEmailChangeTable.memberId) ?: return@transaction null
                        val member = EmailChangeStore.lockMember(memberId) ?: return@transaction null
                        val change = EmailChangeStore.changeByIdLocked(changeId) ?: return@transaction null
                        val confirmed = change[MemberEmailChangeTable.newEmailConfirmedAt] != null
                        val effective = change[MemberEmailChangeTable.effectiveAt]
                        if (!isOpenAndUnexpired(change = change, now = now) ||
                            !confirmed ||
                            effective == null ||
                            effective > now
                        ) {
                            return@transaction null
                        }
                        applyChangeLocked(member = member, change = change, verified = true, now = now, actor = null, linkActor = null)
                    }
                if (report != null) {
                    touched++
                    if (report.result ==
                        ApplyResult.APPLIED
                    ) {
                        afterApplied(report = report, exceptRawToken = null, verified = true, actorMemberId = null)
                    }
                }
            }.onFailure { e -> logger.error { "email change apply failed (changeId=$changeId): ${e::class.simpleName}" } }
        }
        runCatching { transaction { EmailChangeStore.purgeResolved(now) } }
            .onSuccess { purged -> if (purged > 0) logger.info { "email change retention: deleted $purged resolved row(s)" } }
            .onFailure { e -> logger.error { "email change retention failed: ${e::class.simpleName}" } }
        return touched
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shared building blocks (all `...Locked` helpers run inside the caller's transaction, member row locked)
    // ------------------------------------------------------------------------------------------------------------

    private enum class ApplyResult { APPLIED, CONFLICT, DISCARDED }

    private class ApplyReport(
        val result: ApplyResult,
        val memberId: Uuid,
        val kind: EmailChangeKind,
        val oldEmail: String,
        val newEmail: String,
        val status: MemberStatus,
    )

    /**
     * Applies [change] to [member] (member row locked, change row locked and verified open). A change whose initiator has
     * since lost the right to make it, or whose target became ineligible, is discarded (WITHDRAWN) instead of applied.
     */
    private fun applyChangeLocked(
        member: ResultRow,
        change: ResultRow,
        verified: Boolean,
        now: LocalDateTime,
        actor: CurrentMember?,
        linkActor: EmailChangeLinkActor?,
        /** Audit entries that must precede this one in the chain (written AFTER the row updates, see below). */
        beforeAudit: () -> Unit = {},
    ): ApplyReport {
        val memberId = member[MemberTable.id]
        val changeId = change[MemberEmailChangeTable.id]
        val kind = EmailChangeKind.valueOf(change[MemberEmailChangeTable.kind])
        val facts = accountFacts(memberId)
        val oldEmail = member[MemberTable.email]
        val newEmail = change[MemberEmailChangeTable.pendingEmail]
        val status = member[MemberTable.status]

        fun report(result: ApplyResult) =
            ApplyReport(result = result, memberId = memberId, kind = kind, oldEmail = oldEmail, newEmail = newEmail, status = status)

        if (!initiatorStillEntitled(change = change, kind = kind, targetRole = facts.role) || !isEligibleTarget(member)) {
            EmailChangeStore.resolveLocked(changeId = changeId, status = EmailChangeStatus.WITHDRAWN, now = now)
            beforeAudit()
            recordAudit(
                actor = actor,
                targetId = memberId,
                status = status,
                role = facts.role,
                facts =
                    EmailChangeAuditFacts(
                        event = EmailChangeAuditEvent.WITHDRAWN,
                        kind = kind,
                        changeId = changeId.toString(),
                        linkActor = linkActor,
                    ),
                emailChanged = false,
                reason = null,
                now = now,
            )
            return report(ApplyResult.DISCARDED)
        }
        return when (
            EmailChangeStore.applyLocked(
                memberId = memberId,
                changeId = changeId,
                newEmail = newEmail,
                verified = verified,
                now = now,
            )
        ) {
            ApplyOutcome.Applied -> {
                beforeAudit()
                recordAudit(
                    actor = actor,
                    targetId = memberId,
                    status = status,
                    role = facts.role,
                    facts =
                        EmailChangeAuditFacts(
                            event = EmailChangeAuditEvent.APPLIED,
                            kind = kind,
                            changeId = changeId.toString(),
                            linkActor = linkActor,
                        ),
                    emailChanged = true,
                    reason = null,
                    now = now,
                )
                report(ApplyResult.APPLIED)
            }
            ApplyOutcome.Duplicate -> {
                beforeAudit()
                recordAudit(
                    actor = actor,
                    targetId = memberId,
                    status = status,
                    role = facts.role,
                    facts =
                        EmailChangeAuditFacts(
                            event = EmailChangeAuditEvent.CONFLICT,
                            kind = kind,
                            changeId = changeId.toString(),
                            linkActor = linkActor,
                        ),
                    emailChanged = false,
                    reason = null,
                    now = now,
                )
                report(ApplyResult.CONFLICT)
            }
        }
    }

    /** Effects of an applied change, strictly AFTER the commit. */
    private fun afterApplied(
        report: ApplyReport,
        exceptRawToken: String?,
        verified: Boolean,
        actorMemberId: Uuid?,
    ) {
        invalidateAfterAddressChange(memberId = report.memberId, exceptRawToken = exceptRawToken)
        if (!verified && report.status == MemberStatus.FRIEND && mailConfigured) {
            sendFriendVerification(actorId = actorMemberId ?: report.memberId, targetId = report.memberId, email = report.newEmail)
        }
        // The owner accepting a proposal themselves needs no notice; every third-party-driven effective change does.
        if (report.kind != EmailChangeKind.PROPOSAL && mailConfigured) {
            runCatching { mailer.sendAppliedInfo(email = report.oldEmail, maskedNewEmail = maskEmailForLogging(report.newEmail)) }
                .onFailure { e -> logger.error { "emailChangeMailer.sendAppliedInfo threw: ${e::class.simpleName}" } }
        }
    }

    private fun finish(
        member: ResultRow,
        change: ResultRow,
        status: EmailChangeStatus,
        event: EmailChangeAuditEvent,
        actor: CurrentMember?,
        linkActor: EmailChangeLinkActor?,
        now: LocalDateTime,
    ) {
        val memberId = member[MemberTable.id]
        val changeId = change[MemberEmailChangeTable.id]
        EmailChangeStore.resolveLocked(changeId = changeId, status = status, now = now)
        recordAudit(
            actor = actor,
            targetId = memberId,
            status = member[MemberTable.status],
            role = accountFacts(memberId).role,
            facts =
                EmailChangeAuditFacts(
                    event = event,
                    kind = EmailChangeKind.valueOf(change[MemberEmailChangeTable.kind]),
                    changeId = changeId.toString(),
                    linkActor = linkActor,
                ),
            emailChanged = false,
            reason = null,
            now = now,
        )
    }

    /** Audit entry for a change that [EmailChangeStore.supersedeOpenLocked] ended; a no-op when nothing was open. Call it LAST in the transaction. */
    private fun recordSuperseded(
        superseded: ResultRow?,
        memberId: Uuid,
        member: ResultRow,
        role: AccountRole?,
        actor: CurrentMember?,
        now: LocalDateTime,
    ) {
        if (superseded == null) return
        recordAudit(
            actor = actor,
            targetId = memberId,
            status = member[MemberTable.status],
            role = role,
            facts =
                EmailChangeAuditFacts(
                    event = EmailChangeAuditEvent.SUPERSEDED,
                    kind = EmailChangeKind.valueOf(superseded[MemberEmailChangeTable.kind]),
                    changeId = superseded[MemberEmailChangeTable.id].toString(),
                ),
            emailChanged = false,
            reason = null,
            now = now,
        )
    }

    /** Five wrong passwords on one link burn the token. */
    private fun invalidateExhausted(
        changeId: Uuid,
        memberId: Uuid,
    ) {
        val now = nowUtc()
        runCatching {
            transaction {
                val member = EmailChangeStore.lockMember(memberId) ?: return@transaction
                val change = EmailChangeStore.changeByIdLocked(changeId) ?: return@transaction
                if (change[MemberEmailChangeTable.status] != EmailChangeStatus.PENDING.name) return@transaction
                finish(
                    member = member,
                    change = change,
                    status = EmailChangeStatus.EXPIRED,
                    event = EmailChangeAuditEvent.EXPIRED,
                    actor = null,
                    linkActor = EmailChangeLinkActor.NEW_ADDRESS,
                    now = now,
                )
            }
        }.onFailure { e -> logger.error { "email change token invalidation failed (changeId=$changeId): ${e::class.simpleName}" } }
    }

    private fun invalidateAfterAddressChange(
        memberId: Uuid,
        exceptRawToken: String?,
    ) {
        // The address IS the login identifier: every other session ends, and a token minted for the OLD address must
        // not go on resetting a password or verifying the NEW address.
        SessionStore.revokeAllForMember(memberId = memberId, exceptRawToken = exceptRawToken)
        PasswordResetTokenStore.invalidateAllForMember(memberId = memberId)
        FriendEmailVerificationTokenStore.invalidateAllForMember(memberId = memberId)
    }

    private fun sendFriendVerification(
        actorId: Uuid,
        targetId: Uuid,
        email: String,
    ) {
        val actorAllowed = friendMailActorRateLimiter.checkAndRecord("actor:$actorId")
        val targetAllowed = friendMailTargetRateLimiter.checkAndRecord("target:$targetId")
        if (!actorAllowed || !targetAllowed) {
            logger.warn { "email change friend-verification mail suppressed by rate limiter (target=$targetId)" }
            return
        }
        val rawToken = FriendEmailVerificationTokenStore.createToken(targetId)
        runCatching { friendVerificationMailer.send(email = email, rawToken = rawToken) }
            .onFailure { e -> logger.error { "friendVerificationMailer.send threw: ${e::class.simpleName}" } }
    }

    /** Password proof with a per-member cap on failures (bcrypt, never under a row lock). */
    private fun checkPassword(
        memberId: Uuid,
        password: String,
        storedHash: String?,
    ) {
        val key = "member:$memberId"
        if (!passwordAttemptRateLimiter.checkAllowed(key)) throw EmailChangeRateLimitedException()
        if (!PasswordHasher.verify(rawPassword = password, storedHash = storedHash)) {
            passwordAttemptRateLimiter.recordFailure(key)
            throw InvalidPasswordException()
        }
        passwordAttemptRateLimiter.reset(key)
    }

    private class AccountFacts(
        val role: AccountRole?,
        val passwordHash: String?,
    )

    private fun accountFacts(memberId: Uuid): AccountFacts {
        val row = AccountTable.selectAll().where { AccountTable.memberId eq memberId }.singleOrNull()
        return AccountFacts(role = row?.get(AccountTable.role), passwordHash = row?.get(AccountTable.passwordHash))
    }

    /** `true` when the account has a local password that the owner can use as proof (not managed by the identity provider). */
    private fun passwordRequired(facts: AccountFacts): Boolean =
        facts.passwordHash != null && !(keycloakEnabled && facts.role != AccountRole.ADMIN)

    private fun ownChangeAllowed(
        member: ResultRow,
        facts: AccountFacts,
    ): Boolean = member[MemberTable.status] != MemberStatus.GUEST && member[MemberTable.anonymizedAt] == null && passwordRequired(facts)

    private fun isEligibleTarget(member: ResultRow): Boolean =
        member[MemberTable.anonymizedAt] == null &&
            member[MemberTable.status] != MemberStatus.GUEST &&
            member[MemberTable.status] != MemberStatus.DECEASED

    private fun requireEligibleTarget(member: ResultRow) {
        if (!isEligibleTarget(member)) throw EmailChangeNotAllowedException()
    }

    /** The initiator of a third-party change may still make it (role re-read at apply time, see F8: otherwise the change is discarded). */
    private fun initiatorStillEntitled(
        change: ResultRow,
        kind: EmailChangeKind,
        targetRole: AccountRole?,
    ): Boolean {
        if (kind == EmailChangeKind.SELF) return true
        val initiator = change[MemberEmailChangeTable.requestedBy] ?: return false
        val initiatorRole = accountFacts(initiator).role ?: return false
        if (kind == EmailChangeKind.ADMIN_OVERRIDE) return initiatorRole == AccountRole.ADMIN
        if (initiatorRole != AccountRole.ADMIN && initiatorRole != AccountRole.BOARD) return false
        return !(targetRole != null && targetRole in ESCALATED_ROLES && initiatorRole != AccountRole.ADMIN)
    }

    private fun requireInitiatorRole(
        actor: CurrentMember,
        override: Boolean,
    ) {
        val allowed = if (override) actor.role == AccountRole.ADMIN else actor.role == AccountRole.ADMIN || actor.role == AccountRole.BOARD
        if (!allowed) throw ForbiddenException()
    }

    private fun usedByAnotherMember(
        normalized: String,
        memberId: Uuid,
    ): Boolean =
        MemberTable
            .selectAll()
            .where { (MemberTable.email.lowerCase() eq normalized) and (MemberTable.id neq memberId) }
            .count() > 0

    private fun isOpenAndUnexpired(
        change: ResultRow,
        now: LocalDateTime,
    ): Boolean = change[MemberEmailChangeTable.status] == EmailChangeStatus.PENDING.name && change[MemberEmailChangeTable.expiresAt] > now

    /** The change must be an open, unexpired change OF [memberId] -- "missing", "foreign" and "finished" are one answer. */
    private fun requireOpenChangeOf(
        change: ResultRow,
        memberId: Uuid,
        now: LocalDateTime,
    ) {
        if (change[MemberEmailChangeTable.memberId] != memberId || !isOpenAndUnexpired(change = change, now = now)) {
            throw EmailChangePendingNotFoundException()
        }
    }

    private fun validateNewEmail(
        newEmail: String,
        newEmailRepeat: String,
    ): String {
        val normalized = newEmail.trim().lowercase()
        if (!isValidMailboxAddress(normalized)) throw BadRequestException("email is not a valid mailbox address")
        if (normalized.length > MEMBER_EMAIL_MAX_LENGTH) throw MemberEmailTooLongException()
        if (normalized != newEmailRepeat.trim().lowercase()) throw EmailChangeRepeatMismatchException()
        return normalized
    }

    private fun parseMemberId(raw: String): Uuid =
        runCatching {
            Uuid.parse(raw)
        }.getOrElse { throw NotFoundException("Member $raw not found") }

    private fun parseChangeId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw EmailChangePendingNotFoundException() }

    private fun ResultRow.toPendingDto(actor: CurrentMember): EmailChangePendingDto =
        EmailChangePendingDto(
            changeId = this[MemberEmailChangeTable.id].toString(),
            newEmailMasked = maskEmailForLogging(this[MemberEmailChangeTable.pendingEmail]),
            kind = EmailChangeKind.valueOf(this[MemberEmailChangeTable.kind]),
            expiresAt = this[MemberEmailChangeTable.expiresAt],
            effectiveAt = this[MemberEmailChangeTable.effectiveAt],
            newEmailConfirmed = this[MemberEmailChangeTable.newEmailConfirmedAt] != null,
            withdrawable = actor.role == AccountRole.ADMIN || this[MemberEmailChangeTable.requestedBy] == actor.memberId,
        )

    /** One MEMBER/UPDATE audit entry. Carries NEVER an address -- see [EmailChangeAuditFacts]. */
    private fun recordAudit(
        actor: CurrentMember?,
        targetId: Uuid,
        status: MemberStatus,
        role: AccountRole?,
        facts: EmailChangeAuditFacts,
        emailChanged: Boolean,
        reason: String?,
        now: LocalDateTime,
    ) {
        val before = MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = status, role = role)
        val after = before.copy(emailChanged = emailChanged, reason = reason, emailChange = facts)
        AuditLogRecorder.record(
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            entityType = AuditEntityType.MEMBER,
            entityId = targetId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), after),
            occurredAt = now,
        )
    }

    private fun nowUtc(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)

    private companion object {
        const val OVERRIDE_REASON_MIN_LENGTH = 10
        const val OVERRIDE_REASON_MAX_LENGTH = 500
    }
}
