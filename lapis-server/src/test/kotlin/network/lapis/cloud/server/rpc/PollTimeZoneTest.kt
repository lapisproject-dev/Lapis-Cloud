package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.PollStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * V1.9.38 -- the two clocks of the poll lifecycle. `poll.closes_at` is a class-B wall-clock typed in by the creator in the
 * ORGANIZATION zone, so "has the deadline passed?" compares it with the organization-zone wall-clock, not with the UTC system
 * stamp. Before the fix a poll with the deadline "20:00" closed at 20:00 UTC, i.e. one or two hours late in Germany.
 */
class PollTimeZoneTest :
    FunSpec({
        val data = PollTestData()
        val deadline = LocalDateTime(2026, 7, 1, 20, 0, 0)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            data.deleteAllPolls()
            TimeTestSupport.resetOrganizationZone()
        }
        afterSpec {
            data.deleteAllPolls()
            data.cleanUp()
            TimeTestSupport.resetOrganizationZone()
        }

        /** Creates a poll through the service (a deadline a day ahead of the real clock), then moves its deadline to [wall]. */
        suspend fun PollApp.pollWithDeadline(
            creator: Uuid,
            wall: LocalDateTime,
        ): String {
            val zone = OrganizationTimeZone.current()
            val realWall =
                ServerClock
                    .nowIn(zone)
                    .toInstant(zone)
                    .plus(1.days)
                    .toLocalDateTime(zone)
            val poll = call(member = creator) { createPoll(pollInput(closesAt = realWall)) }
            transaction {
                // created_at moves back too: a CHECK requires closes_at > created_at, and `wall` lies before today.
                PollTable.update({ PollTable.id eq Uuid.parse(poll.id) }) {
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[closesAt] = wall
                }
            }
            return poll.id
        }

        test("Berlin, summer: a poll closing 20:00 is OPEN at 17:59:59Z and CLOSED from 18:00:00Z") {
            pollTestApplication {
                val chair = data.chair()
                val id = pollWithDeadline(chair, deadline)
                TimeTestSupport.withServerClock(instant = "2026-07-01T17:59:59Z") {
                    call(member = chair) { getPoll(id) }.status shouldBe PollStatus.OPEN
                }
                TimeTestSupport.withServerClock(instant = "2026-07-01T18:00:00Z") {
                    call(member = chair) { getPoll(id) }.status shouldBe PollStatus.CLOSED
                }
            }
        }

        test("Berlin, winter: the same wall-clock 20:00 closes at 19:00Z") {
            pollTestApplication {
                val chair = data.chair()
                val id = pollWithDeadline(chair, LocalDateTime(2026, 12, 1, 20, 0, 0))
                TimeTestSupport.withServerClock(instant = "2026-12-01T18:59:59Z") {
                    call(member = chair) { getPoll(id) }.status shouldBe PollStatus.OPEN
                }
                TimeTestSupport.withServerClock(instant = "2026-12-01T19:00:00Z") {
                    call(member = chair) { getPoll(id) }.status shouldBe PollStatus.CLOSED
                }
            }
        }

        test("the open/closed filter of listPolls agrees with the status of getPoll") {
            pollTestApplication {
                val chair = data.chair()
                val id = pollWithDeadline(chair, deadline)
                TimeTestSupport.withServerClock(instant = "2026-07-01T17:59:59Z") {
                    call(member = chair) { listPolls(status = PollStatus.OPEN, limit = 50, offset = 0) }.map { it.id } shouldBe listOf(id)
                    call(member = chair) { listPolls(status = PollStatus.CLOSED, limit = 50, offset = 0) }.map { it.id } shouldBe
                        emptyList()
                }
                TimeTestSupport.withServerClock(instant = "2026-07-01T18:00:00Z") {
                    call(member = chair) { listPolls(status = PollStatus.OPEN, limit = 50, offset = 0) }.map { it.id } shouldBe emptyList()
                    call(member = chair) { listPolls(status = PollStatus.CLOSED, limit = 50, offset = 0) }.map { it.id } shouldBe listOf(id)
                }
            }
        }

        test("another organization zone closes at ITS 20:00 (Tbilisi, UTC+4: 16:00Z)") {
            TimeTestSupport.setOrganizationZone("Asia/Tbilisi")
            pollTestApplication {
                val chair = data.chair()
                val id = pollWithDeadline(chair, deadline)
                TimeTestSupport.withServerClock(instant = "2026-07-01T15:59:59Z") {
                    call(member = chair) { getPoll(id) }.status shouldBe PollStatus.OPEN
                }
                TimeTestSupport.withServerClock(instant = "2026-07-01T16:00:00Z") {
                    call(member = chair) { getPoll(id) }.status shouldBe PollStatus.CLOSED
                }
            }
        }

        test("a manual close reports closedAt as an organization wall-clock (A stamp converted), and the deadline as stored (B)") {
            pollTestApplication {
                val chair = data.chair()
                val zone = OrganizationTimeZone.current()
                val realWall =
                    ServerClock
                        .nowIn(zone)
                        .toInstant(zone)
                        .plus(2.days)
                        .toLocalDateTime(zone)
                val poll = call(member = chair) { createPoll(pollInput(closesAt = realWall)) }
                poll.closesAt shouldBe realWall.let { it }
                val closed = call(member = chair) { closePoll(poll.id) }
                val storedUtc =
                    transaction { PollTable.selectAll().where { PollTable.id eq Uuid.parse(poll.id) }.single()[PollTable.closedAt]!! }
                closed.closedAt shouldBe storedUtc.toInstant(TimeZone.UTC).toLocalDateTime(zone)
                closed.closesAt shouldBe realWall
            }
        }
    })
