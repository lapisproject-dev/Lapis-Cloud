package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import network.lapis.cloud.server.db.generated.PrivilegedActionRequestTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakePeerNotificationMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

internal const val PEER_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Shared test data and helpers of the admin-peer-protection specs (Welle V1.9.57). Members created here are ADMINs that
 * count as tenured (`role_changed_at` NULL) unless a test says otherwise. [isolateAdmins] sets every OTHER ADMIN account of
 * the database (the dev seed, leftovers of other specs) aside, so a test controls EXACTLY which administrators exist; call
 * [restoreAdmins] (or [cleanUp]) to put them back. Call [cleanUp] in `afterSpec`.
 */
internal class PeerFixture {
    val createdMemberIds = mutableListOf<Uuid>()
    private val setAside = linkedSetOf<Uuid>()
    private val hashedPassword: String by lazy { PasswordHasher.hash(PEER_PASSWORD) }

    fun member(
        role: AccountRole? = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
        roleChangedAt: LocalDateTime? = null,
        displayName: String = "Peer Testmitglied",
        email: String = "peer-${Uuid.random().toString().take(12)}@example.org",
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
                    it[AccountTable.roleChangedAt] = roleChangedAt
                    it[passwordHash] = hashedPassword
                }
            }
        }
        createdMemberIds += id
        return id
    }

    fun admin(
        roleChangedAt: LocalDateTime? = null,
        status: MemberStatus = MemberStatus.ACTIVE,
        displayName: String = "Admin Testmitglied",
    ): Uuid = member(role = AccountRole.ADMIN, roleChangedAt = roleChangedAt, status = status, displayName = displayName)

    /** The class-A "now" the fixture stamps a fresh `role_changed_at` with. */
    fun createdAtNow(): LocalDateTime =
        network.lapis.cloud.server.db.DbClock
            .nowLocalDateTime()

    fun actor(
        id: Uuid,
        role: AccountRole = AccountRole.ADMIN,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): CurrentMember = CurrentMember(memberId = id, role = role, status = status)

    /**
     * Demotes EVERY ADMIN account that is not in [keep] to BOARD, so the test controls exactly who may approve. ADMINs of other
     * specs (the dev seed) are remembered and put back by [restoreAdmins]; ADMINs this fixture created earlier simply stay BOARD.
     */
    fun isolateAdmins(keep: Set<Uuid> = emptySet()) {
        transaction {
            val all =
                AccountTable
                    .selectAll()
                    .where { AccountTable.role eq AccountRole.ADMIN }
                    .map { it[AccountTable.memberId] }
                    .filter { it !in keep }
            setAside += all.filter { it !in createdMemberIds }
            if (all.isNotEmpty()) AccountTable.update({ AccountTable.memberId inList all }) { it[role] = AccountRole.BOARD }
        }
    }

    fun restoreAdmins() {
        if (setAside.isEmpty()) return
        val toRestore = setAside.toList()
        transaction { AccountTable.update({ AccountTable.memberId inList toRestore }) { it[role] = AccountRole.ADMIN } }
        setAside.clear()
    }

    fun roleOf(id: Uuid): AccountRole? =
        transaction {
            AccountTable
                .selectAll()
                .where { AccountTable.memberId eq id }
                .singleOrNull()
                ?.get(AccountTable.role)
        }

    fun statusOf(id: Uuid): MemberStatus =
        transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] }

    fun passwordHashOf(id: Uuid): String? =
        transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.passwordHash] }

    fun roleChangedAtOf(id: Uuid): LocalDateTime? =
        transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.roleChangedAt] }

    fun requestRow(id: String): ResultRow =
        transaction { PrivilegedActionRequestTable.selectAll().where { PrivilegedActionRequestTable.id eq Uuid.parse(id) }.single() }

    fun statusOfRequest(id: String): String = requestRow(id)[PrivilegedActionRequestTable.status]

    fun requestsOf(targetId: Uuid): List<ResultRow> =
        transaction { PrivilegedActionRequestTable.selectAll().where { PrivilegedActionRequestTable.targetMemberId eq targetId }.toList() }

    fun openRequests(targetId: Uuid): Long =
        transaction {
            PrivilegedActionRequestTable
                .selectAll()
                .where { PrivilegedActionRequestTable.openTargetMemberId eq targetId }
                .count()
        }

    fun liveSessions(memberId: Uuid): Long =
        transaction { SessionTable.selectAll().where { (SessionTable.memberId eq memberId) and SessionTable.revokedAt.isNull() }.count() }

    fun auditJsonFor(memberId: Uuid): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { (AuditLogEntryTable.entityId eq memberId) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + " " + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
        }

    @Suppress("LongParameterList")
    fun service(
        mailer: FakePeerNotificationMailer = FakePeerNotificationMailer(),
        smtp: SmtpConfigState = configuredSmtp(),
        actorLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
        targetLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
    ): PrivilegedActionService =
        PrivilegedActionService(smtpConfigState = smtp, mailer = mailer, actorRateLimiter = actorLimiter, targetRateLimiter = targetLimiter)

    fun cleanUp() {
        restoreAdmins()
        if (createdMemberIds.isEmpty()) return
        transaction {
            PrivilegedActionRequestTable.deleteWhere {
                (targetMemberId inList createdMemberIds) or (actorMemberId inList createdMemberIds) or
                    (approverMemberId inList createdMemberIds)
            }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
            SessionTable.deleteWhere { memberId inList createdMemberIds }
            PasswordResetTokenTable.deleteWhere { memberId inList createdMemberIds }
            AccountTable.deleteWhere { memberId inList createdMemberIds }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
            MemberTable.deleteWhere { id inList createdMemberIds }
        }
    }
}
