package network.lapis.cloud.server.governance.meetingevent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.activeRecordingRole
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.MeetingAlreadyLinkedException
import network.lapis.cloud.shared.rpc.MeetingEventVisibilityReservedException
import network.lapis.cloud.shared.rpc.MeetingNotAnnounceableException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.227 -- the Sitzung <-> Veranstaltung link against the real database engine: the unique index and the SQLSTATE 23505 mapping, the FK with
 * `ON DELETE SET NULL`, two simultaneous announcements of the same Sitzung (the Sitzung row lock serializes them: exactly one event), and a
 * deterministic order of the linkable Sitzungen when several share a start. The migration chain V1..V123 runs in `EventMeetingLinkMigrationPostgresTest`
 * and in the lane template itself.
 */
abstract class MeetingEventLinkScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec { db.deactivate() }

        val at = LocalDateTime(2030, 5, 4, 19, 0)

        fun ops() =
            MeetingEventLinkOperations(conferencesEnabled = {
                false
            }, rateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes))

        fun board(): CurrentMember {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MEL Board"
                    it[email] = "mel-pg-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.BOARD
                }
            }
            return CurrentMember(memberId = id, role = AccountRole.BOARD, status = MemberStatus.ACTIVE)
        }

        fun committee(): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[name] = "MEL-Test-${id.toString().take(8)}"
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "x"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[membershipMode] = CommitteeMembershipMode.MANUAL
                    it[ruleJson] = null
                }
            }
            return id
        }

        fun meeting(
            committee: Uuid,
            id: Uuid = Uuid.random(),
        ): Uuid {
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[committeeId] = committee
                    it[title] = "S"
                    it[scheduledAt] = at
                    it[location] = "Saal"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            return id
        }

        fun plainEvent(creator: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                EventStore.insertEvent(
                    id = id,
                    slug = "mel-pg-${id.toString().take(12)}",
                    title = "MEL-Test-Ereignis",
                    description = "d",
                    locationText = "Saal",
                    onlineUrl = null,
                    startsAt = at,
                    endsAt = LocalDateTime(2030, 5, 4, 21, 0),
                    capacity = null,
                    feeAmount = BigDecimal.ZERO,
                    feeCurrency = "EUR",
                    visibility = EventVisibility.MEMBERS_ONLY,
                    registrationClosesAt = null,
                    createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                    createdBy = creator,
                    status = EventStatus.PUBLISHED,
                )
            }
            return id
        }

        test("the unique index refuses a second event for one Sitzung and the failure is a unique violation (SQLSTATE 23505)") {
            val actor = board()
            val m = meeting(committee())
            val e1 = plainEvent(actor.memberId)
            val e2 = plainEvent(actor.memberId)
            transaction { EventTable.update({ EventTable.id eq e1 }) { it[meetingId] = m } }
            val failure = runCatching { transaction { EventTable.update({ EventTable.id eq e2 }) { it[meetingId] = m } } }.exceptionOrNull()
            (failure is ExposedSQLException) shouldBe true
            (failure as ExposedSQLException).isUniqueViolation() shouldBe true
        }

        test("ON DELETE SET NULL: a purged Sitzung leaves the event standing without a link") {
            val actor = board()
            val m = meeting(committee())
            val e = plainEvent(actor.memberId)
            transaction { EventTable.update({ EventTable.id eq e }) { it[meetingId] = m } }
            transaction { MeetingTable.deleteWhere { MeetingTable.id eq m } }
            transaction { EventTable.selectAll().where { EventTable.id eq e }.single()[EventTable.meetingId] } shouldBe null
        }

        test("two simultaneous announcements of one Sitzung create exactly one event") {
            val actor = board()
            val m = meeting(committee())
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val outcomes =
                    (0 until 2)
                        .map {
                            pool.submit<Throwable?> {
                                barrier.await(30, TimeUnit.SECONDS)
                                runCatching { ops().announce(current = actor, meetingId = m) }.exceptionOrNull()
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                outcomes.count { it == null } shouldBe 1
                outcomes.filterNotNull().single().let { (it is MeetingAlreadyLinkedException) shouldBe true }
                transaction { EventTable.selectAll().where { EventTable.meetingId eq m }.count() } shouldBe 1L
            } finally {
                pool.shutdownNow()
            }
        }

        // ---- V1.9.229: the officer path on the real engine ----

        fun seat(
            member: Uuid,
            committee: Uuid,
            role: CommitteeRole,
        ) = transaction {
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[CommitteeMembershipTable.role] = role
                it[since] = LocalDate(2020, 1, 1)
                it[until] = null
                it[committeeId] = committee
                it[memberId] = member
            }
        }

        fun plainMember(): CurrentMember {
            val b = board()
            transaction { AccountTable.update({ AccountTable.memberId eq b.memberId }) { it[role] = AccountRole.MEMBER } }
            return CurrentMember(memberId = b.memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
        }

        test("officer: activeRecordingRole with two offices is the most senior one, every run") {
            val c = committee()
            val officer = plainMember()
            seat(officer.memberId, c, CommitteeRole.SECRETARY)
            seat(officer.memberId, c, CommitteeRole.CHAIR)
            seat(officer.memberId, c, CommitteeRole.MEMBER)
            repeat(5) { officer.activeRecordingRole(c) shouldBe CommitteeRole.CHAIR }
        }

        test("officer: lockForOfficer grants the own committee's linked event and answers NotFound for every other case") {
            val admin = board()
            val own = committee()
            val foreign = committee()
            val officer = plainMember()
            seat(officer.memberId, own, CommitteeRole.CHAIR)
            val ownMeeting = meeting(own)
            val foreignMeeting = meeting(foreign)
            val ownEvent = plainEvent(admin.memberId)
            val foreignEvent = plainEvent(admin.memberId)
            val unlinkedEvent = plainEvent(admin.memberId)
            transaction {
                MeetingEventLinkStore.setLink(eventId = ownEvent, meetingId = ownMeeting)
                MeetingEventLinkStore.setLink(eventId = foreignEvent, meetingId = foreignMeeting)
            }
            val granted = transaction { MeetingEventOfficerAccess.lockForOfficer(current = officer, eventId = ownEvent) }
            granted.grant.officerRole shouldBe CommitteeRole.CHAIR
            granted.grant.meetingId shouldBe ownMeeting
            val messages =
                listOf(foreignEvent, unlinkedEvent, Uuid.random()).map { id ->
                    val failure =
                        runCatching {
                            transaction {
                                MeetingEventOfficerAccess.lockForOfficer(
                                    current = officer,
                                    eventId = id,
                                )
                            }
                        }.exceptionOrNull()
                    (failure is NotFoundException) shouldBe true
                    failure!!.message!!.replace(id.toString(), "ID")
                }
            messages.toSet().size shouldBe 1
        }

        test("officer: aligning is allowed for the own Sitzung's members-only event and refused for a public one; linking is refused") {
            val admin = board()
            val c = committee()
            val officer = plainMember()
            seat(officer.memberId, c, CommitteeRole.CHAIR)
            val m = meeting(c, id = Uuid.random())
            val e = plainEvent(admin.memberId)
            ops().link(current = admin, meetingId = m, eventId = e)
            val linkAttempt =
                runCatching {
                    ops().link(current = officer, meetingId = meeting(c), eventId = plainEvent(admin.memberId))
                }.exceptionOrNull()
            (linkAttempt is ForbiddenException) shouldBe true
            transaction { EventTable.update({ EventTable.id eq e }) { it[visibility] = EventVisibility.PUBLIC } }
            val alignPublic = runCatching { ops().align(current = officer, meetingId = m, eventId = e) }.exceptionOrNull()
            (alignPublic is MeetingEventVisibilityReservedException || alignPublic is MeetingNotAnnounceableException) shouldBe true
        }

        test("listLinkable orders Sitzungen with the same start deterministically (id ascending)") {
            val actor = board()
            val c = committee()
            val ids = (0 until 6).map { meeting(c) }
            val e = plainEvent(actor.memberId)
            val listed = ops().listLinkable(current = actor, eventId = e).map { Uuid.parse(it.id) }.filter { it in ids }
            listed shouldBe ids.sortedBy { it.toString() }
        }
    })

/** The H2 run (normal `test` task). */
class MeetingEventLinkConcurrencyTest : MeetingEventLinkScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MeetingEventLinkPostgresTest : MeetingEventLinkScenarios(TestDatabase.Postgres())
