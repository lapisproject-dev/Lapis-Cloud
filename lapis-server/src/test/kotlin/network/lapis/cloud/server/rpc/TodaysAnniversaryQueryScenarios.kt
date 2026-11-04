package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.276 -- the SQL pre-filter of the dashboard card "Heute" (`loadTodaysCandidates`) on H2 AND PostgreSQL: for ordinary days and for
 * the leap-day edge (28 February of a non-leap year, 29 February of a leap year, 1 March) the candidate set must equal a full scan
 * filtered with [AnniversaryCalendar.nextOccurrence] == today -- the same answer on both databases.
 */
abstract class TodaysAnniversaryQueryScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdIds = mutableListOf<Uuid>()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)
        afterSpec {
            transaction { if (createdIds.isNotEmpty()) MemberTable.deleteWhere { MemberTable.id inList createdIds } }
            db.deactivate()
        }

        fun member(
            dateOfBirth: LocalDate?,
            joinedAt: LocalDate,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Scenario $id"
                    it[email] = "today-scenario-$id@example.org"
                    it[MemberTable.status] = status
                    it[MemberTable.joinedAt] = joinedAt
                    it[MemberTable.dateOfBirth] = dateOfBirth
                }
            }
            createdIds += id
            return id
        }

        /** The reference answer: every eligible member, filtered in memory by the calendar. */
        fun expectedIds(today: LocalDate): Set<Uuid> =
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.status inList MemberStatusSets.ANNIVERSARY_ELIGIBLE }
                    .filter { it[MemberTable.anonymizedAt] == null }
                    .filter { row ->
                        val dob = row[MemberTable.dateOfBirth]
                        (dob != null && AnniversaryCalendar.nextOccurrence(anniversary = dob, today = today) == today) ||
                            AnniversaryCalendar.nextOccurrence(anniversary = row[MemberTable.joinedAt], today = today) == today
                    }.map { it[MemberTable.id] }
                    .toSet()
            }

        beforeSpec {
            // dates of birth / join dates around the leap-day edge plus an ordinary day, plus excluded and non-matching rows
            member(LocalDate(2000, 2, 29), LocalDate(2024, 6, 15))
            member(LocalDate(2000, 2, 28), LocalDate(2024, 6, 15))
            member(LocalDate(2000, 3, 1), LocalDate(2024, 6, 15))
            member(null, LocalDate(2016, 2, 29))
            member(null, LocalDate(2021, 2, 28))
            member(null, LocalDate(2021, 3, 1))
            member(LocalDate(1990, 9, 17), LocalDate(2021, 9, 17))
            member(LocalDate(1990, 9, 18), LocalDate(2021, 9, 18))
            member(LocalDate(1990, 9, 17), LocalDate(2021, 9, 17), status = MemberStatus.FRIEND)
        }

        listOf(
            LocalDate(2031, 9, 17),
            LocalDate(2031, 2, 28),
            LocalDate(2032, 2, 28),
            LocalDate(2032, 2, 29),
            LocalDate(2031, 3, 1),
            LocalDate(2032, 3, 1),
            LocalDate(2031, 12, 31),
        ).forEach { day ->
            test("loadTodaysCandidates($day) equals the full scan filtered by the calendar") {
                val candidates = transaction { loadTodaysCandidates(day) }
                val candidateIds = candidates.map { it.id }.toSet()
                // the pre-filter may only be a superset-free equal here: nothing it returns is outside the reference answer ...
                candidateIds shouldBe expectedIds(day)
                // ... and it never hands over a member that is not eligible
                candidates.all { it.status in MemberStatusSets.ANNIVERSARY_ELIGIBLE } shouldBe true
            }
        }
    })
