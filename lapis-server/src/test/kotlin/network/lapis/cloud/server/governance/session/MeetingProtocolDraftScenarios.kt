package network.lapis.cloud.server.governance.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * V1.9.239 -- the parts of the Protokoll draft that only the real engine proves, on H2 and on PostgreSQL: V129 is repeatable on the migrated
 * schema, the constraints (one draft per Sitzung, one block per item, phase and version CHECKs) hold, a unique violation inside a savepoint
 * leaves the transaction usable, two simultaneous first saves have exactly one winner, and every list has a fixed order.
 */
abstract class MeetingProtocolDraftScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = SessionFixture()
        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanup()
            db.deactivate()
        }

        fun migrationStatements(): List<String> {
            val text =
                MeetingProtocolDraftScenarios::class.java
                    .getResourceAsStream("/db/migration/V129__meeting_protocol_draft.sql")!!
                    .bufferedReader()
                    .readText()
            return text
                .lines()
                .filterNot { it.trim().startsWith("--") }
                .joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        test("V129 runs a second time on the migrated schema without an error and without changing a row") {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            p.saveBlock(
                current = s.lead,
                meetingId = s.meetingId.toString(),
                agendaItemId = s.agenda[0].toString(),
                expectedVersion = 0,
                body = "bleibt",
            )
            transaction { migrationStatements().forEach { exec(it) } }
            p.get(current = s.lead, meetingId = s.meetingId.toString()).items[0].body shouldBe "bleibt"
        }

        test("one draft per Sitzung; a unique violation inside a savepoint leaves the transaction usable (no aborted-transaction state)") {
            val s = fx.scene()
            runBlocking { s.openedProtocol() }
            val now = LocalDateTime(2026, 3, 1, 10, 0)

            fun insertDraft() =
                ProtocolDraftTable.insert {
                    it[id] = Uuid.random()
                    it[meetingId] = s.meetingId
                    it[phase] = ProtocolDraftPhase.DRAFT
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            transaction {
                insertDraft()
                val second =
                    try {
                        withSavepoint(name = "second_draft") { insertDraft() }
                        false
                    } catch (e: ExposedSQLException) {
                        e.isUniqueViolation()
                    }
                second shouldBe true
                // the transaction still works after the failed statement
                ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq s.meetingId }.count() shouldBe 1
            }
        }

        test("the CHECKs refuse an unknown phase and a negative version; one block per item") {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            val meeting = s.meetingId.toString()
            p.saveBlock(current = s.lead, meetingId = meeting, agendaItemId = s.agenda[0].toString(), expectedVersion = 0, body = "x")
            val draftId =
                transaction {
                    ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq s.meetingId }.single()[ProtocolDraftTable.id]
                }
            shouldThrow<ExposedSQLException> {
                transaction { exec("UPDATE protocol_draft SET phase = 'XX' WHERE id = '$draftId'") }
            }
            shouldThrow<ExposedSQLException> {
                transaction { exec("UPDATE protocol_draft_block SET version = -1 WHERE draft_id = '$draftId'") }
            }
            shouldThrow<ExposedSQLException> {
                transaction {
                    ProtocolDraftBlockTable.insert {
                        it[id] = Uuid.random()
                        it[ProtocolDraftBlockTable.draftId] = draftId
                        it[agendaItemId] = s.agenda[0]
                        it[body] = "doppelt"
                        it[version] = 1
                        it[updatedAt] = LocalDateTime(2026, 3, 1, 10, 0)
                    }
                }
            }
        }

        test("two simultaneous first saves: exactly one wins, the other is a stale answer, one block exists") {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    listOf(s.lead, s.minutes)
                        .map { who ->
                            pool.submit<Boolean> {
                                barrier.await(10, TimeUnit.SECONDS)
                                p
                                    .saveBlock(
                                        current = who,
                                        meetingId = s.meetingId.toString(),
                                        agendaItemId = s.agenda[0].toString(),
                                        expectedVersion = 0,
                                        body = "von ${who.memberId}",
                                    ).saved
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                results.count { it } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
            protocolDraftCount(s.meetingId) shouldBe 1
            protocolBlockCount(s.meetingId) shouldBe 1
        }

        test("two simultaneous first tasks create one draft and both tasks") {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                listOf(s.lead, s.minutes)
                    .map { who ->
                        pool.submit {
                            barrier.await(10, TimeUnit.SECONDS)
                            p.addTask(
                                current = who,
                                meetingId = s.meetingId.toString(),
                                input =
                                    ProtocolTaskInput(
                                        agendaItemId = s.agenda[0].toString(),
                                        assigneeMemberId = s.peers[0].memberId.toString(),
                                        description = "MSX ${who.memberId}",
                                        dueDate = null,
                                    ),
                            )
                        }
                    }.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            protocolDraftCount(s.meetingId) shouldBe 1
            protocolTaskCount(s.meetingId) shouldBe 2
        }

        test("lists have a fixed order: equal names sort by id, run after run") {
            val s = fx.scene(peers = 0)
            val twinA = fx.newMember(name = "MSX Zwilling")
            val twinB = fx.newMember(name = "MSX Zwilling")
            fx.seat(member = twinA, committee = s.committee)
            fx.seat(member = twinB, committee = s.committee)
            val p = runBlocking { s.openedProtocol() }
            val first = p.get(current = s.lead, meetingId = s.meetingId.toString()).assignableMembers.map { it.memberId }
            val second = p.get(current = s.lead, meetingId = s.meetingId.toString()).assignableMembers.map { it.memberId }
            first shouldBe second
            val twins = first.filter { it in setOf(twinA.memberId.toString(), twinB.memberId.toString()) }
            twins shouldBe twins.sorted()
        }

        test("a locked draft refuses a write on the real engine too") {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            p.saveBlock(
                current = s.lead,
                meetingId = s.meetingId.toString(),
                agendaItemId = s.agenda[0].toString(),
                expectedVersion = 0,
                body = "x",
            )
            lockProtocolDraft(s.meetingId)
            shouldThrow<ConflictException> {
                p.saveBlock(
                    current = s.lead,
                    meetingId = s.meetingId.toString(),
                    agendaItemId = s.agenda[0].toString(),
                    expectedVersion = 1,
                    body = "y",
                )
            }
        }
    })
