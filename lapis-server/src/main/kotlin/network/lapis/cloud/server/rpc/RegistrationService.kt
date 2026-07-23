package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val REGISTRATION_BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private val ESCALATED_ROLES = arrayOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)

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
 * therefore have NO auto-approval fallback: an [MemberStatus.ANTRAG] application stays pending
 * indefinitely until a BOARD/ADMIN account actually decides it.
 *
 * **Concurrency: row-lock + compare-and-swap, IDENTICAL contract to
 * [CrowdfundingService.approveProject]/`rejectProject`.** [requireApplicationRow] is called with
 * `forUpdate = true`, taking a row-level lock on the applicant BEFORE re-reading its status -- so
 * a second, concurrent board decision on the SAME applicant (one caller approving while another
 * rejects) blocks until the first commits, then re-reads the now-decided status and fails the
 * `ANTRAG` check instead of silently racing. The `UPDATE` itself is additionally a
 * compare-and-swap (`status eq ANTRAG` in the WHERE clause, checked-for-zero afterwards) as
 * defense in depth against the same lost-update.
 */
class RegistrationService(
    private val call: ApplicationCall,
    private val registrationRateLimiter: LoginRateLimiter,
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
        val ipKey = "ip:${call.request.local.remoteHost}"
        if (!registrationRateLimiter.checkAllowed(emailKey) || !registrationRateLimiter.checkAllowed(ipKey)) {
            throw ConflictException("Too many registration attempts -- try again later")
        }
        registrationRateLimiter.recordFailure(emailKey)
        registrationRateLimiter.recordFailure(ipKey)

        if (!MembershipAgreementDisclaimer.matches(input.agreementVersion, input.agreementSha256)) {
            throw ConflictException(
                "agreementVersion/agreementSha256 do not match the current MembershipAgreementDisclaimer -- " +
                    "call getMembershipAgreement again and submit its CURRENT version/sha256 unmodified",
            )
        }
        if (input.displayName.isBlank()) throw ConflictException("displayName must not be blank")
        PasswordPolicy.validate(input.password, normalizedEmail)

        val now = nowLocalDateTime()
        transaction {
            val alreadyExists = MemberTable.selectAll().where { MemberTable.email.lowerCase() eq normalizedEmail }.count() > 0
            // See class/interface KDoc "account-enumeration hardening" -- silent no-op, identical
            // response either way.
            if (alreadyExists) return@transaction

            val memberId = Uuid.random()
            MemberTable.insert {
                it[id] = memberId
                it[displayName] = input.displayName
                it[email] = normalizedEmail
                it[status] = MemberStatus.ANTRAG
                it[joinedAt] = now.date
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[id] = Uuid.random()
                it[AccountTable.memberId] = memberId
                it[role] = AccountRole.MEMBER
                it[passwordHash] = PasswordHasher.hash(input.password)
            }
            MembershipAgreementAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[MembershipAgreementAcknowledgmentTable.memberId] = memberId
                it[acknowledgedAt] = now
                it[agreementVersion] = input.agreementVersion
                it[agreementSha256] = input.agreementSha256
            }
        }
    }

    override suspend fun listPendingApplications(): List<MemberDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*REGISTRATION_BOARD_ROLES)
        return transaction {
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.status eq MemberStatus.ANTRAG }
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
            requireApplicationRow(targetId, forUpdate = true)
            val updated =
                MemberTable.update({
                    (MemberTable.id eq targetId) and (MemberTable.status eq MemberStatus.ANTRAG)
                }) {
                    it[status] = MemberStatus.AKTIV
                    it[reviewedBy] = current.memberId
                    it[reviewedAt] = now
                }
            if (updated == 0) {
                throw ConflictException("Application $memberId was concurrently decided -- retry")
            }
            loadMember(targetId)
        }
    }

    override suspend fun rejectApplication(
        memberId: String,
        reason: String,
    ): MemberDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*REGISTRATION_BOARD_ROLES)
        if (reason.isBlank()) throw ConflictException("rejectApplication requires a non-blank reason")
        val targetId = memberId.toMemberUuidOrThrow()
        val now = nowLocalDateTime()
        return transaction {
            requireApplicationRow(targetId, forUpdate = true)
            val updated =
                MemberTable.update({
                    (MemberTable.id eq targetId) and (MemberTable.status eq MemberStatus.ANTRAG)
                }) {
                    it[status] = MemberStatus.ABGELEHNT
                    it[rejectionReason] = reason
                    it[reviewedBy] = current.memberId
                    it[reviewedAt] = now
                }
            if (updated == 0) {
                throw ConflictException("Application $memberId was concurrently decided -- retry")
            }
            loadMember(targetId)
        }
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
        PasswordPolicy.validate(input.temporaryPassword, normalizedEmail)

        val now = nowLocalDateTime()
        return transaction {
            val alreadyExists = MemberTable.selectAll().where { MemberTable.email.lowerCase() eq normalizedEmail }.count() > 0
            if (alreadyExists) throw ConflictException("A member with this email already exists")

            val memberId = Uuid.random()
            MemberTable.insert {
                it[id] = memberId
                it[displayName] = input.displayName
                it[email] = normalizedEmail
                it[status] = MemberStatus.AKTIV
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
     * [AuthService.changePassword]) -- once AUSGETRETEN, the former member must not remain
     * logged in anywhere.
     */
    override suspend fun leaveMembership(): MemberDto {
        val current = resolveCurrentMember(call)
        val result =
            transaction {
                val updated =
                    MemberTable.update({
                        (MemberTable.id eq current.memberId) and (MemberTable.status eq MemberStatus.AKTIV)
                    }) {
                        it[status] = MemberStatus.AUSGETRETEN
                    }
                if (updated == 0) {
                    throw ConflictException("Not an active member -- already left, never approved, or rejected")
                }
                loadMember(current.memberId)
            }
        SessionStore.revokeAllForMember(current.memberId)
        return result
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    /** [forUpdate] takes a `SELECT ... FOR UPDATE` row lock on this member before returning it -- required by [approveApplication]/[rejectApplication], see class KDoc "Concurrency". */
    private fun requireApplicationRow(
        id: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow {
        val query = MemberTable.selectAll().where { MemberTable.id eq id }
        val row = (if (forUpdate) query.forUpdate() else query).singleOrNull() ?: throw NotFoundException("Member $id not found")
        if (row[MemberTable.status] != MemberStatus.ANTRAG) {
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

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
}
