package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.RoomPollCloseReason
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.304 -- the tables and CHECKs of `V153__conference_room_poll.sql` on H2 AND PostgreSQL with the same assertions: the chain V152 -> V153,
 * both lifecycle paths, the unique open poll per room, the guest weight rule, the audit literal, and a second run of the file. Every refused
 * statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the transaction).
 */
abstract class ConferenceRoomPollMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = RoomPollFixtures()
        val at = LocalDateTime(2026, 11, 1, 10, 0, 0)

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest { fx.wipePolls() }
        afterSpec {
            try {
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun refused(block: () -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun insertPoll(
            room: PollRoom,
            status: RoomPollStatus = RoomPollStatus.OPEN,
            openRoomId: Uuid? = if (status == RoomPollStatus.OPEN) room.id else null,
            closedAt: LocalDateTime? = if (status == RoomPollStatus.CLOSED) at else null,
            reason: RoomPollCloseReason? = if (status == RoomPollStatus.CLOSED) RoomPollCloseReason.MANUAL else null,
            question: String = "Eine Frage?",
            snapshot: Int = 0,
            version: Long = 1L,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomPollTable.insert {
                    it[ConferenceRoomPollTable.id] = id
                    it[roomId] = room.id
                    it[ConferenceRoomPollTable.openRoomId] = openRoomId
                    it[ConferenceRoomPollTable.question] = question
                    it[countingMode] = RoomPollCountingMode.BOTH
                    it[ConferenceRoomPollTable.status] = status
                    it[startedAt] = at
                    it[ConferenceRoomPollTable.closedAt] = closedAt
                    it[closeReason] = reason
                    it[stateVersion] = version
                    it[snapshotAnswerCount] = snapshot
                }
            }
            return id
        }

        fun insertOption(
            poll: Uuid,
            position: Int,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomPollOptionTable.insert {
                    it[ConferenceRoomPollOptionTable.id] = id
                    it[pollId] = poll
                    it[ConferenceRoomPollOptionTable.position] = position
                    it[text] = "Option $position"
                }
            }
            return id
        }

        fun insertAnswer(
            poll: Uuid,
            option: Uuid,
            group: RoomPollVoterGroup,
            weight: String,
        ) = transaction {
            ConferenceRoomPollAnswerTable.insert {
                it[id] = Uuid.random()
                it[pollId] = poll
                it[optionId] = option
                it[voterGroup] = group
                it[weightLtr] = BigDecimal(weight)
            }
        }

        fun room(): PollRoom = fx.room(creator = fx.member())

        test("both lifecycle paths: OPEN needs open_room_id = room_id and no end; CLOSED needs the end, the reason and no open_room_id") {
            val r = room()
            insertPoll(r, RoomPollStatus.CLOSED)
            insertPoll(r, RoomPollStatus.OPEN)
            val r2 = room()
            refused { insertPoll(r2, RoomPollStatus.OPEN, openRoomId = null) } shouldBe true
            refused { insertPoll(r2, RoomPollStatus.OPEN, openRoomId = Uuid.random()) } shouldBe true
            refused { insertPoll(r2, RoomPollStatus.OPEN, closedAt = at) } shouldBe true
            refused { insertPoll(r2, RoomPollStatus.OPEN, reason = RoomPollCloseReason.MANUAL) } shouldBe true
            refused { insertPoll(r2, RoomPollStatus.CLOSED, openRoomId = r2.id) } shouldBe true
            refused { insertPoll(r2, RoomPollStatus.CLOSED, closedAt = null) } shouldBe true
            refused { insertPoll(r2, RoomPollStatus.CLOSED, reason = null) } shouldBe true
        }

        test("at most one OPEN poll per room (unique open_room_id), any number of closed ones, one open poll per other room") {
            val r = room()
            insertPoll(r, RoomPollStatus.OPEN)
            refused { insertPoll(r, RoomPollStatus.OPEN) } shouldBe true
            insertPoll(r, RoomPollStatus.CLOSED)
            insertPoll(r, RoomPollStatus.CLOSED)
            insertPoll(room(), RoomPollStatus.OPEN)
        }

        test("question 3..300 characters, state version at least 1, snapshot a multiple of five") {
            val r = room()
            insertPoll(r, RoomPollStatus.CLOSED, question = "abc")
            insertPoll(r, RoomPollStatus.CLOSED, question = "x".repeat(300))
            refused { insertPoll(r, RoomPollStatus.CLOSED, question = "ab") } shouldBe true
            refused { insertPoll(r, RoomPollStatus.CLOSED, question = "x".repeat(301)) } shouldBe true
            refused { insertPoll(r, RoomPollStatus.CLOSED, question = "   ") } shouldBe true
            refused { insertPoll(r, RoomPollStatus.CLOSED, version = 0L) } shouldBe true
            insertPoll(r, RoomPollStatus.CLOSED, snapshot = 10)
            refused { insertPoll(r, RoomPollStatus.CLOSED, snapshot = 7) } shouldBe true
            refused { insertPoll(r, RoomPollStatus.CLOSED, snapshot = -5) } shouldBe true
        }

        test("options: position 0..9, unique per poll") {
            val poll = insertPoll(room(), RoomPollStatus.OPEN)
            insertOption(poll, 0)
            insertOption(poll, 9)
            refused { insertOption(poll, 0) } shouldBe true
            refused { insertOption(poll, 10) } shouldBe true
            refused { insertOption(poll, -1) } shouldBe true
        }

        test("an answer by a guest or friend can carry no weight; a member's weight is not negative; the group is one of two") {
            val poll = insertPoll(room(), RoomPollStatus.OPEN)
            val option = insertOption(poll, 0)
            insertAnswer(poll, option, RoomPollVoterGroup.MEMBERS, "12.50")
            insertAnswer(poll, option, RoomPollVoterGroup.MEMBERS, "0.00")
            insertAnswer(poll, option, RoomPollVoterGroup.GUESTS_AND_FRIENDS, "0.00")
            refused { insertAnswer(poll, option, RoomPollVoterGroup.GUESTS_AND_FRIENDS, "0.01") } shouldBe true
            refused { insertAnswer(poll, option, RoomPollVoterGroup.MEMBERS, "-0.01") } shouldBe true
        }

        test("one marker per person and poll") {
            val member = fx.member()
            val poll = insertPoll(room(), RoomPollStatus.OPEN)
            transaction {
                ConferenceRoomPollVotedTable.insert {
                    it[id] = Uuid.random()
                    it[pollId] = poll
                    it[memberId] = member
                }
            }
            refused {
                ConferenceRoomPollVotedTable.insert {
                    it[id] = Uuid.random()
                    it[pollId] = poll
                    it[memberId] = member
                }
            } shouldBe true
        }

        test("the audit log accepts the new entity type") {
            val poll = insertPoll(room(), RoomPollStatus.OPEN)
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.CONFERENCE_ROOM_POLL,
                    entityId = poll,
                    action = AuditAction.CREATE,
                    after = "{}",
                )
            }
        }

        test("the migration is repeatable and keeps every rule in force") {
            val sql =
                checkNotNull(
                    ConferenceRoomPollMigrationScenarios::class.java.classLoader.getResourceAsStream(
                        "db/migration/V153__conference_room_poll.sql",
                    ),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 30) shouldBe true
            // Re-running the audit CHECK exactly as written in V153 would drop the entity types of LATER waves from the shared test schema and make
            // every spec that runs afterwards fail on its audit row (and PartgReportR4MigrationScenarios compare a stale list with the enum). Replay
            // it with the current type list; the rules of this file are asserted below.
            val currentTypes = AuditEntityType.entries.joinToString(", ") { "'${it.name}'" }
            transaction {
                statements.forEach { statement ->
                    if (statement.contains("chk_audit_log_entry_entity_type") && statement.contains("ADD CONSTRAINT")) {
                        exec(
                            "ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type " +
                                "CHECK (entity_type IN ($currentTypes))",
                        )
                    } else {
                        exec(statement)
                    }
                }
            }
            val r = room()
            insertPoll(r, RoomPollStatus.OPEN)
            refused { insertPoll(r, RoomPollStatus.OPEN) } shouldBe true
            refused { insertPoll(room(), RoomPollStatus.CLOSED, question = "ab") } shouldBe true
        }
    })
