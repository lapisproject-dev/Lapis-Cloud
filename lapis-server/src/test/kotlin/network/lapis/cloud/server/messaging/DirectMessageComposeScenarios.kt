package network.lapis.cloud.server.messaging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.DirectMessageBlockTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import kotlin.uuid.Uuid

/**
 * V1.9.237 "Neue Nachricht" -- the parts that only a real PostgreSQL proves, on H2 and on PostgreSQL: the migrated V128 schema (column default,
 * constraints), the deterministic order of the name search and of the group list (ASCII names only: H2 and PostgreSQL collate umlauts and case
 * differently), the group recipients and a concurrent double block (no `25P02` -- the insert runs under a savepoint).
 */
abstract class DirectMessageComposeScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            try {
                transaction {
                    DirectMessageBlockTable.deleteWhere { (blockerMemberId inList memberIds) or (blockedMemberId inList memberIds) }
                    DirectMessageTable.deleteWhere { (senderId inList memberIds) or (recipientId inList memberIds) }
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
                    CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                    MemberTable.deleteWhere { MemberTable.id inList memberIds }
                }
            } finally {
                db.deactivate()
            }
        }

        fun member(
            name: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = name
                    it[email] = "dm-compose-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            memberIds += id
            return id
        }

        test("V128: a new member is discoverable by default, a block pair is unique and never a self-block") {
            val a = member("Scenario Alpha")
            val b = member("Scenario Beta")
            transaction { MemberTable.selectAll().where { MemberTable.id eq a }.single()[MemberTable.dmDiscoverable] } shouldBe true
            val now = DbClock.nowLocalDateTime()
            transaction { DirectMessageBlockStore.block(blocker = a, blocked = b, now = now) }
            // the store is idempotent; the raw insert of the same pair and a self-block violate the constraints
            transaction { DirectMessageBlockStore.block(blocker = a, blocked = b, now = now) }
            transaction { DirectMessageBlockTable.selectAll().where { DirectMessageBlockTable.blockerMemberId eq a }.count() } shouldBe 1

            fun rawInsert(
                blocker: Uuid,
                blocked: Uuid,
            ) = runCatching {
                transaction {
                    DirectMessageBlockTable.insert {
                        it[id] = Uuid.random()
                        it[blockerMemberId] = blocker
                        it[blockedMemberId] = blocked
                        it[createdAt] = now
                    }
                }
            }
            (rawInsert(a, b).exceptionOrNull() != null) shouldBe true
            (rawInsert(a, a).exceptionOrNull() != null) shouldBe true
        }

        test("the name search and the group list have a deterministic order: name, then id") {
            val caller = member("Scenario Caller")
            val twins = List(3) { member("Scenario Zwilling") }
            val search = transaction { DirectMessageRecipientSearch.search(callerId = caller, rawQuery = "scenario zwilling") }
            search.map { it.memberId } shouldContainExactly twins.map { it.toString() }.sorted()
            val names = listOf("Scenario Gruppe B", "Scenario Gruppe A", "Scenario Gruppe C")
            val groups =
                names.map { n ->
                    val id = Uuid.random()
                    transaction {
                        CommitteeTable.insert {
                            it[CommitteeTable.id] = id
                            it[name] = n
                            it[type] = CommitteeType.WORKING_GROUP
                            it[description] = "x"
                            it[active] = true
                            it[quorumPercent] = 50
                            it[createdAt] = DbClock.nowLocalDateTime()
                        }
                        CommitteeMembershipTable.insert {
                            it[CommitteeMembershipTable.id] = Uuid.random()
                            it[committeeId] = id
                            it[memberId] = caller
                            it[role] = CommitteeRole.MEMBER
                            it[since] = LocalDate(2026, 1, 1)
                            it[until] = null
                        }
                    }
                    committeeIds += id
                    id
                }
            val current =
                network.lapis.cloud.server.security
                    .CurrentMember(memberId = caller, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
            val listed =
                transaction {
                    DirectMessageGroups
                        .addressable(current = current, today = LocalDate(2026, 6, 1))
                        .map { it[CommitteeTable.name] }
                        .filter { it.startsWith("Scenario Gruppe") }
                }
            listed shouldContainExactly listOf("Scenario Gruppe A", "Scenario Gruppe B", "Scenario Gruppe C")
            groups.size shouldBe 3
        }

        test("a concurrent double block leaves exactly one row and no aborted transaction") {
            val blocker = member("Scenario Blocker")
            val blocked = member("Scenario Gesperrt")
            val outcomes =
                coroutineScope {
                    (1..8)
                        .map {
                            async(Dispatchers.IO) {
                                runCatching {
                                    transaction {
                                        DirectMessageBlockStore.block(
                                            blocker = blocker,
                                            blocked = blocked,
                                            now = DbClock.nowLocalDateTime(),
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                }
            outcomes
                .mapNotNull {
                    it.exceptionOrNull()
                }.map { (it as? SQLException)?.sqlState ?: it::class.simpleName } shouldContainExactly
                emptyList()
            transaction {
                DirectMessageBlockTable.selectAll().where { DirectMessageBlockTable.blockerMemberId eq blocker }.count()
            } shouldBe 1
            transaction { DirectMessageBlockStore.isBlockedEitherWay(a = blocker, b = blocked) } shouldBe true
        }
    })
