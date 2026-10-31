package network.lapis.cloud.server.activism

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val T = LocalDateTime(2026, 11, 5, 10, 0, 0)

private fun values(title: String) =
    ActivismActionValues(
        title = title,
        description = null,
        goalText = null,
        location = null,
        startsAt = null,
        endsAt = null,
        kind = ActivismKind.POSTERING,
        formAppointment = false,
        formProgress = true,
        formSignal = true,
        goalQuantity = null,
        unit = null,
        confirmContributions = false,
        ownNotice = null,
    )

/**
 * V1.9.246 -- the activism store on the migrated V133 schema, on H2 and on PostgreSQL: the permanent caps under the action lock (races),
 * the unique pair of a signal under a savepoint, the deterministic order of lists, the two-phase reorder and the cascades.
 */
abstract class ActivismStoreScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val members = mutableListOf<Uuid>()

        fun member(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Aktionen-Test $id"
                    it[email] = "activism-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            members += id
            return id
        }

        fun action(
            title: String = "Aktion",
            createdBy: Uuid,
            at: LocalDateTime = T,
        ): Uuid {
            val id = Uuid.random()
            transaction { ActivismStore.insertAction(id = id, values = values(title), createdBy = createdBy, now = at) }
            return id
        }

        fun wipe() {
            wipeActivism()
        }

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { wipe() }
        afterSpec {
            try {
                wipe()
                transaction {
                    AccountTable.deleteWhere { AccountTable.memberId inList members }
                    MemberTable.deleteWhere { MemberTable.id inList members }
                }
            } finally {
                db.deactivate()
            }
        }

        test("40 parallel contribution reports of one member end at exactly the cap of 30") {
            val creator = member()
            val helper = member()
            val actionId = action(createdBy = creator)
            val results =
                runBlocking {
                    (1..40)
                        .map {
                            async(Dispatchers.IO) {
                                transaction {
                                    ActivismStore.insertContributionCapped(
                                        actionId = actionId,
                                        memberId = helper,
                                        quantity = 1,
                                        area = null,
                                        note = null,
                                        now = T,
                                        since = LocalDateTime(2026, 11, 4, 10, 0, 0),
                                        cap = 30,
                                    )
                                }
                            }
                        }.awaitAll()
                }
            results.count { it != null } shouldBe 30
            transaction { ActivismContributionTable.selectAll().where { ActivismContributionTable.actionId eq actionId }.count() } shouldBe
                30L
        }

        test("parallel reports of one member for one action leave exactly one OPEN row") {
            val creator = member()
            val reporter = member()
            val actionId = action(createdBy = creator)
            val outcomes =
                runBlocking {
                    (1..10)
                        .map {
                            async(Dispatchers.IO) {
                                transaction {
                                    ActivismStore.insertReportLimited(
                                        actionId = actionId,
                                        reporter = reporter,
                                        reason = "Ein ausreichend langer Grund",
                                        now = T,
                                        since = LocalDateTime(2026, 11, 4, 10, 0, 0),
                                        maxPerDay = 5,
                                    )
                                }
                            }
                        }.awaitAll()
                }
            outcomes.count { it == ActivismStore.ReportOutcome.OPENED } shouldBe 1
            outcomes.count { it == ActivismStore.ReportOutcome.ALREADY_OPEN } shouldBe 9
        }

        test("parallel signals of one member are one row, and no unique violation poisons a transaction") {
            val creator = member()
            val signaler = member()
            val actionId = action(createdBy = creator)
            runBlocking {
                (1..10)
                    .map {
                        async(
                            Dispatchers.IO,
                        ) { transaction { ActivismSignalStore.set(actionId = actionId, memberId = signaler) } }
                    }.awaitAll()
            }
            transaction { ActivismSignalTable.selectAll().where { ActivismSignalTable.actionId eq actionId }.count() } shouldBe 1L
            transaction { ActivismSignalStore.countOf(actionId) } shouldBe 1
            transaction { ActivismSignalStore.clear(actionId = actionId, memberId = signaler) }
            transaction { ActivismSignalStore.countOf(actionId) } shouldBe 0
        }

        test("parallel replacements of the responsible set (each under the action lock) stay within 1..10 and never collide") {
            val creator = member()
            val pool = (1..12).map { member() }
            val actionId = action(createdBy = creator)
            runBlocking {
                (1..6)
                    .map { n ->
                        async(Dispatchers.IO) {
                            transaction {
                                ActivismStore.getAction(id = actionId, lock = true)
                                ActivismStore.replaceResponsibles(actionId = actionId, memberIds = pool.shuffled().take(n).toSet())
                            }
                        }
                    }.awaitAll()
            }
            val count = transaction { ActivismResponsibleTable.selectAll().where { ActivismResponsibleTable.actionId eq actionId }.count() }
            (count in 1L..10L) shouldBe true
        }

        test("lists are ordered deterministically: newest first, ties by id") {
            val creator = member()
            val ids = (1..5).map { action(title = "Gleichzeitig $it", createdBy = creator) }
            val first =
                transaction {
                    ActivismStore
                        .listActions(
                            statuses = listOf(network.lapis.cloud.shared.domain.ActivismStatus.IDEA),
                        ).map { it[ActivismActionTable.id] }
                }
            val second =
                transaction {
                    ActivismStore
                        .listActions(
                            statuses = listOf(network.lapis.cloud.shared.domain.ActivismStatus.IDEA),
                        ).map { it[ActivismActionTable.id] }
                }
            first shouldContainExactly second
            first shouldContainExactly ids.sortedBy { it.toString() }
            val newer = action(title = "Neuer", createdBy = creator, at = LocalDateTime(2026, 11, 6, 9, 0, 0))
            transaction {
                ActivismStore
                    .listActions(
                        statuses = listOf(network.lapis.cloud.shared.domain.ActivismStatus.IDEA),
                    ).first()[ActivismActionTable.id]
            } shouldBe newer
        }

        test("moving a task renumbers the checklist in two phases without a unique violation") {
            val creator = member()
            val actionId = action(createdBy = creator)
            val tasks =
                transaction {
                    (1..4).map {
                        ActivismStore.insertTask(
                            actionId = actionId,
                            text = "Aufgabe $it",
                            assignee = null,
                            dueOn = null,
                        )
                    }
                }
            transaction { ActivismStore.moveTask(actionId = actionId, taskId = tasks[3], newPosition = 1) }
            transaction { ActivismStore.listTasks(actionId).map { it[ActivismTaskTable.id] } } shouldContainExactly
                listOf(tasks[3], tasks[0], tasks[1], tasks[2])
            transaction { ActivismStore.listTasks(actionId).map { it[ActivismTaskTable.position] } } shouldContainExactly listOf(1, 2, 3, 4)
            transaction { ActivismStore.moveTask(actionId = actionId, taskId = tasks[3], newPosition = 99) }
            transaction { ActivismStore.listTasks(actionId).map { it[ActivismTaskTable.id] } } shouldContainExactly tasks
        }

        test("moving a task works when positions have grown above the temporary range") {
            val creator = member()
            val actionId = action(createdBy = creator)
            val tasks =
                transaction {
                    (1..3).map { ActivismStore.insertTask(actionId = actionId, text = "Aufgabe $it", assignee = null, dueOn = null) }
                }
            transaction {
                ActivismTaskTable.update({ ActivismTaskTable.id eq tasks[2] }) { it[ActivismTaskTable.position] = 1001 }
            }
            transaction { ActivismStore.moveTask(actionId = actionId, taskId = tasks[0], newPosition = 2) }
            transaction { ActivismStore.listTasks(actionId).map { it[ActivismTaskTable.id] } } shouldContainExactly
                listOf(tasks[1], tasks[0], tasks[2])
            transaction { ActivismStore.listTasks(actionId).map { it[ActivismTaskTable.position] } } shouldContainExactly listOf(1, 2, 3)
        }

        test("deleting an action cascades to its children") {
            val creator = member()
            val helper = member()
            val actionId = action(createdBy = creator)
            transaction {
                ActivismStore.insertTask(actionId = actionId, text = "Aufgabe", assignee = null, dueOn = null)
                ActivismStore.insertTextBlock(actionId = actionId, title = "Titel", body = "Text")
                ActivismStore.replaceResponsibles(actionId = actionId, memberIds = setOf(creator))
                ActivismStore.insertContribution(actionId = actionId, memberId = helper, quantity = 3, area = null, note = null, now = T)
                ActivismSignalStore.set(actionId = actionId, memberId = helper)
                ActivismStore.insertReport(actionId = actionId, reporter = helper, reason = "Ein ausreichend langer Grund", now = T)
            }
            transaction { ActivismActionTable.deleteWhere { ActivismActionTable.id eq actionId } }
            transaction {
                ActivismTaskTable.selectAll().count() shouldBe 0L
                ActivismResponsibleTable.selectAll().count() shouldBe 0L
                ActivismContributionTable.selectAll().count() shouldBe 0L
                ActivismSignalTable.selectAll().count() shouldBe 0L
            }
        }
    })
