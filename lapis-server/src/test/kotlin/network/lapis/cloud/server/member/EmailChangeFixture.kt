package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeEmailChangeMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.SmtpConfig
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EmailChangeStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

internal const val EC_PASSWORD = "a-genuinely-strong-password-1"

internal val EC_SEED_ADMIN: CurrentMember =
    CurrentMember(memberId = Uuid.parse("00000000-0000-0000-0000-000000000001"), role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
internal val EC_SEED_BOARD: CurrentMember =
    CurrentMember(memberId = Uuid.parse("00000000-0000-0000-0000-000000000002"), role = AccountRole.BOARD, status = MemberStatus.ACTIVE)
internal val EC_SEED_TREASURER: CurrentMember =
    CurrentMember(memberId = Uuid.parse("00000000-0000-0000-0000-000000000003"), role = AccountRole.TREASURER, status = MemberStatus.ACTIVE)
internal val EC_SEED_MEMBER: CurrentMember =
    CurrentMember(memberId = Uuid.parse("00000000-0000-0000-0000-000000000004"), role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)

internal fun configuredSmtp(): SmtpConfigState.Configured {
    val env =
        mapOf(
            SmtpConfig.ENV_HOST to "mail.example.invalid",
            SmtpConfig.ENV_USERNAME to "no_reply@example.org",
            SmtpConfig.ENV_PASSWORD to "s3cr3t",
            SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
            SmtpConfig.ENV_FROM_NAME to "EmailChangeTest",
        )
    return SmtpConfig.load(env::get) as SmtpConfigState.Configured
}

/** Shared test data and helpers of the address-change specs (Welle V1.9.56). Call [cleanUp] in `afterSpec`. */
internal class EmailChangeFixture {
    val createdMemberIds = mutableListOf<Uuid>()
    private val hashedPassword: String by lazy { PasswordHasher.hash(EC_PASSWORD) }

    fun member(
        email: String = "ec-${Uuid.random().toString().take(12)}@example.org",
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        withPassword: Boolean = true,
        displayName: String = "EmailChange Testmitglied",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[MemberTable.email] = email
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
            }
            if (role != null) {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                    it[AccountTable.passwordHash] = if (withPassword) hashedPassword else null
                }
            }
        }
        createdMemberIds += id
        return id
    }

    fun actor(
        id: Uuid,
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): CurrentMember = CurrentMember(memberId = id, role = role, status = status)

    /** A fresh BOARD (or other role) member that acts as an initiator. */
    fun initiator(role: AccountRole = AccountRole.BOARD): CurrentMember = actor(id = member(role = role), role = role)

    fun emailOf(id: Uuid): String = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.email] }

    fun verifiedAtOf(id: Uuid): LocalDateTime? =
        transaction {
            MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.emailVerifiedAt]
        }

    fun rowsOf(memberId: Uuid): List<ResultRow> =
        transaction { MemberEmailChangeTable.selectAll().where { MemberEmailChangeTable.memberId eq memberId }.toList() }

    fun row(changeId: String): ResultRow =
        transaction { MemberEmailChangeTable.selectAll().where { MemberEmailChangeTable.id eq Uuid.parse(changeId) }.single() }

    fun statusOf(changeId: String): String = row(changeId)[MemberEmailChangeTable.status]

    fun openCount(memberId: Uuid): Long =
        transaction {
            MemberEmailChangeTable
                .selectAll()
                .where {
                    (MemberEmailChangeTable.memberId eq memberId) and (MemberEmailChangeTable.status eq EmailChangeStatus.PENDING.name)
                }.count()
        }

    fun liveSessions(memberId: Uuid): Long =
        transaction { SessionTable.selectAll().where { (SessionTable.memberId eq memberId) and SessionTable.revokedAt.isNull() }.count() }

    /** Pretend [by] has passed: moves the change's own clocks back so the real "now" lies [by] after creation. */
    fun age(
        changeId: String,
        by: Duration,
    ) {
        val id = Uuid.parse(changeId)

        fun LocalDateTime.back(): LocalDateTime = (toInstant(TimeZone.UTC) - by).toLocalDateTime(TimeZone.UTC)
        transaction {
            val r = MemberEmailChangeTable.selectAll().where { MemberEmailChangeTable.id eq id }.single()
            MemberEmailChangeTable.update({ MemberEmailChangeTable.id eq id }) {
                it[createdAt] = r[createdAt].back()
                it[expiresAt] = r[expiresAt].back()
                it[effectiveAt] = r[effectiveAt]?.back()
            }
        }
    }

    /** Pretend a RESOLVED change finished [by] ago (retention tests). */
    fun ageResolved(
        changeId: String,
        by: Duration,
    ) {
        val id = Uuid.parse(changeId)
        transaction {
            val r = MemberEmailChangeTable.selectAll().where { MemberEmailChangeTable.id eq id }.single()
            val resolved = requireNotNull(r[MemberEmailChangeTable.resolvedAt])
            MemberEmailChangeTable.update({ MemberEmailChangeTable.id eq id }) {
                it[resolvedAt] = (resolved.toInstant(TimeZone.UTC) - by).toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    fun auditJsonFor(memberId: Uuid): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { (AuditLogEntryTable.entityId eq memberId) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + " " + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
        }

    fun now(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)

    fun nowPlus(duration: Duration): LocalDateTime = (now().toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

    @Suppress("LongParameterList")
    fun service(
        smtp: SmtpConfigState = configuredSmtp(),
        keycloak: Boolean = false,
        mailer: FakeEmailChangeMailer = FakeEmailChangeMailer(),
        friendMailer: FriendVerificationMailer = FakeFriendVerificationMailer(),
        proposalTarget: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
        proposalActor: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
        passwordAttempts: LoginRateLimiter = LoginRateLimiter(),
        friendMailTarget: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
        friendMailActor: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
        linkBurn: LoginRateLimiter? = null,
    ): EmailChangeService =
        EmailChangeService(
            smtpConfigState = smtp,
            keycloakEnabled = keycloak,
            mailer = mailer,
            friendVerificationMailer = friendMailer,
            proposalTargetRateLimiter = proposalTarget,
            proposalActorRateLimiter = proposalActor,
            passwordAttemptRateLimiter = passwordAttempts,
            friendMailTargetRateLimiter = friendMailTarget,
            friendMailActorRateLimiter = friendMailActor,
            linkBurnLimiter = linkBurn ?: LoginRateLimiter(maxFailures = 5, window = EmailChangeStore.PROPOSAL_TTL + 1.days),
        )

    fun cleanUp() {
        if (createdMemberIds.isEmpty()) return
        transaction {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
            MemberEmailChangeTable.update({ MemberEmailChangeTable.requestedBy inList createdMemberIds }) { it[requestedBy] = null }
            MemberEmailChangeTable.deleteWhere { memberId inList createdMemberIds }
            SessionTable.deleteWhere { memberId inList createdMemberIds }
            FriendEmailVerificationTokenTable.deleteWhere { memberId inList createdMemberIds }
            PasswordResetTokenTable.deleteWhere { memberId inList createdMemberIds }
            AccountTable.deleteWhere { memberId inList createdMemberIds }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
            MemberTable.deleteWhere { id inList createdMemberIds }
        }
    }
}
