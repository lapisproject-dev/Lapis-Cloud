package network.lapis.cloud.server.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.298 review fix -- legacy DONOR accounts that kept an administrative role are downgraded once at startup. */
class DonorRoleDowngradeTest :
    FunSpec({
        val created = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.entityId inList created }) { it[actorMemberId] = null }
                created.forEach { id ->
                    SessionTable.deleteWhere { SessionTable.memberId eq id }
                    AccountTable.deleteWhere { AccountTable.memberId eq id }
                    MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId eq id }
                    MemberTable.deleteWhere { MemberTable.id eq id }
                }
            }
        }

        fun seed(
            status: MemberStatus,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Legacy Donor Test"
                    it[email] = "legacy-donor-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            created += id
            return id
        }

        fun roleOf(id: Uuid) = transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.role] }

        fun auditCount(id: Uuid) = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq id }.count() }

        test(
            "a seeded DONOR with ADMIN, TREASURER and BOARD is downgraded to MEMBER with an audit entry; an ACTIVE admin and a donor MEMBER are untouched",
        ) {
            val donorAdmin = seed(MemberStatus.DONOR, AccountRole.ADMIN)
            val donorTreasurer = seed(MemberStatus.DONOR, AccountRole.TREASURER)
            val donorBoard = seed(MemberStatus.DONOR, AccountRole.BOARD)
            val donorMember = seed(MemberStatus.DONOR, AccountRole.MEMBER)
            val activeAdmin = seed(MemberStatus.ACTIVE, AccountRole.ADMIN)

            DonorRoleDowngrade.run() shouldBe 3

            roleOf(donorAdmin) shouldBe AccountRole.MEMBER
            roleOf(donorTreasurer) shouldBe AccountRole.MEMBER
            roleOf(donorBoard) shouldBe AccountRole.MEMBER
            roleOf(donorMember) shouldBe AccountRole.MEMBER
            roleOf(activeAdmin) shouldBe AccountRole.ADMIN
            auditCount(donorAdmin) shouldBe 1L
            auditCount(donorMember) shouldBe 0L
            auditCount(activeAdmin) shouldBe 0L

            // idempotent
            DonorRoleDowngrade.run() shouldBe 0
            auditCount(donorAdmin) shouldBe 1L
        }
    })
