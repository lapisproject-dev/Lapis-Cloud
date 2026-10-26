package network.lapis.cloud.server.dashboard

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NavCountsDto

private fun counts(
    status: MemberStatus = MemberStatus.ACTIVE,
    conferenceEnabled: Boolean = true,
): NavCountsDto = NavCountsQuery.count(status = status, conferenceEnabled = conferenceEnabled)

/**
 * V1.9.224 -- the numbers behind "(aktiv/gesamt)". Database-wide counts, so every scenario compares a BEFORE and an AFTER inside one
 * rolled-back
 * transaction (the shared database may hold rooms of other specs). "Total" = open rooms, "active" = open rooms with somebody present.
 */
abstract class NavCountsQueryScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec { db.deactivate() }

        test(
            "conference: open rooms count, ended/event/encounter rooms do not; a room is active once however many are present",
        ) {
            rolledBack {
                val creator = DashboardFixtures.member()
                val a = DashboardFixtures.member()
                val b = DashboardFixtures.member()
                val before = counts()
                val busy = DashboardFixtures.room(creator = creator)
                DashboardFixtures.present(roomId = busy, memberId = a)
                DashboardFixtures.present(roomId = busy, memberId = b)
                DashboardFixtures.present(roomId = busy, memberId = creator, left = true)
                DashboardFixtures.room(creator = creator) // open, nobody present
                val emptyAfterLeaving = DashboardFixtures.room(creator = creator)
                DashboardFixtures.present(roomId = emptyAfterLeaving, memberId = a, left = true)
                val ended = DashboardFixtures.room(creator = creator, ended = true)
                DashboardFixtures.present(roomId = ended, memberId = a)
                val eventRoom = DashboardFixtures.room(creator = creator)
                DashboardFixtures.asEventRoom(roomId = eventRoom, creator = creator)
                DashboardFixtures.present(roomId = eventRoom, memberId = a)
                val space = DashboardFixtures.space(creator = creator)
                val session = DashboardFixtures.room(creator = creator, encounterSpaceId = space)
                DashboardFixtures.present(roomId = session, memberId = a)
                val after = counts()
                after.conferenceTotal - before.conferenceTotal shouldBe 3
                after.conferenceActive - before.conferenceActive shouldBe 1
            }
        }

        test("encounter spaces: archived ones are not counted, active = an open session exists, an ended session does not make it active") {
            rolledBack {
                val creator = DashboardFixtures.member()
                val before = counts()
                val running = DashboardFixtures.space(creator = creator)
                DashboardFixtures.room(creator = creator, encounterSpaceId = running)
                val over = DashboardFixtures.space(creator = creator)
                DashboardFixtures.room(creator = creator, encounterSpaceId = over, ended = true)
                DashboardFixtures.space(creator = creator)
                DashboardFixtures.space(creator = creator, archived = true)
                val after = counts()
                after.encounterTotal - before.encounterTotal shouldBe 3
                after.encounterActive - before.encounterActive shouldBe 1
            }
        }

        test("a guest counts only the spaces that admit non-members and never sees a conference") {
            rolledBack {
                val creator = DashboardFixtures.member()
                val before = counts(status = MemberStatus.GUEST)
                val open = DashboardFixtures.space(creator = creator, guests = true)
                DashboardFixtures.room(creator = creator, encounterSpaceId = open)
                DashboardFixtures.space(creator = creator, guests = false)
                DashboardFixtures.room(creator = creator)
                val after = counts(status = MemberStatus.GUEST)
                after.encounterTotal - before.encounterTotal shouldBe 1
                after.encounterActive - before.encounterActive shouldBe 1
                after.conferenceTotal shouldBe 0
                after.conferenceActive shouldBe 0
                counts(status = MemberStatus.FRIEND).conferenceTotal shouldBe 0
            }
        }

        test("switched off or not eligible: 0/0") {
            rolledBack {
                val creator = DashboardFixtures.member()
                DashboardFixtures.room(creator = creator)
                DashboardFixtures.space(creator = creator)
                counts(conferenceEnabled = false).conferenceTotal shouldBe 0
                val applicant = counts(status = MemberStatus.APPLICATION)
                applicant shouldBe NavCountsDto(conferenceActive = 0, conferenceTotal = 0, encounterActive = 0, encounterTotal = 0)
                counts(status = MemberStatus.WITHDRAWN) shouldBe
                    NavCountsDto(conferenceActive = 0, conferenceTotal = 0, encounterActive = 0, encounterTotal = 0)
                // a friend with an unverified address (instance requires it): the service passes spacesAllowed = false
                val unverified = NavCountsQuery.count(status = MemberStatus.FRIEND, conferenceEnabled = true, spacesAllowed = false)
                unverified shouldBe NavCountsDto(conferenceActive = 0, conferenceTotal = 0, encounterActive = 0, encounterTotal = 0)
            }
        }
    })

class NavCountsQueryTest : NavCountsQueryScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class NavCountsQueryPostgresTest : NavCountsQueryScenarios(TestDatabase.Postgres())
