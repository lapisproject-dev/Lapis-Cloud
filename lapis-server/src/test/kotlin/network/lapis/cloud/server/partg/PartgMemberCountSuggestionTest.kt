package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.269 -- the member count suggestion counts the right people, only for the right year and unit, and is a plain number. */
class PartgMemberCountSuggestionTest :
    FunSpec({
        val db = TestDatabase.H2
        val fx = PartgFixtures()

        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val today = LocalDate(2030, 6, 15)

        fun assign(
            member: Uuid,
            chapter: Uuid,
        ) = transaction { MemberTable.update({ MemberTable.id eq member }) { it[regionalChapterId] = chapter } }

        fun anonymise(member: Uuid) =
            transaction { MemberTable.update({ MemberTable.id eq member }) { it[anonymizedAt] = LocalDateTime(2030, 1, 1, 0, 0) } }

        test(
            "a state unit counts its own active, non-anonymised members: guests, donors, friends, withdrawn and anonymised ones do not count",
        ) {
            val chapter = fx.chapter()
            val other = fx.chapter()
            listOf(
                MemberStatus.ACTIVE,
                MemberStatus.ACTIVE,
                MemberStatus.GUEST,
                MemberStatus.DONOR,
                MemberStatus.FRIEND,
                MemberStatus.WITHDRAWN,
            ).forEach { assign(fx.member(status = it, role = AccountRole.MEMBER), chapter) }
            assign(fx.member(role = AccountRole.MEMBER), other)
            val gone = fx.member(role = AccountRole.MEMBER)
            assign(gone, chapter)
            anonymise(gone)
            val suggestion =
                transaction {
                    PartgMemberCountSuggestion.suggest(
                        unit = PartgUnit.State(chapter.toString()),
                        year = 2030,
                        today = today,
                        currentYear = 2030,
                    )
                }
            suggestion?.count shouldBe 2L
            suggestion?.asOf shouldBe today
        }

        test("the federal unit counts every active member, a state's members are part of that number") {
            val chapter = fx.chapter()
            val before =
                transaction {
                    PartgMemberCountSuggestion
                        .suggest(
                            unit = PartgUnit.Federal,
                            year = 2030,
                            today = today,
                            currentYear = 2030,
                        )!!
                        .count
                }
            repeat(3) { assign(fx.member(role = AccountRole.MEMBER), chapter) }
            fx.member(role = AccountRole.MEMBER) // no chapter at all
            val after =
                transaction {
                    PartgMemberCountSuggestion
                        .suggest(
                            unit = PartgUnit.Federal,
                            year = 2030,
                            today = today,
                            currentYear = 2030,
                        )!!
                        .count
                }
            after - before shouldBe 4L
        }

        test("no suggestion for an earlier year, a later year or a subordinate summary") {
            val chapter = fx.chapter().toString()
            transaction {
                PartgMemberCountSuggestion.suggest(unit = PartgUnit.Federal, year = 2029, today = today, currentYear = 2030) shouldBe null
                PartgMemberCountSuggestion.suggest(unit = PartgUnit.Federal, year = 2031, today = today, currentYear = 2030) shouldBe null
                PartgMemberCountSuggestion.suggest(
                    unit = PartgUnit.Subordinate(chapter),
                    year = 2030,
                    today = today,
                    currentYear = 2030,
                ) shouldBe
                    null
                PartgMemberCountSuggestion
                    .suggest(
                        unit = PartgUnit.State(chapter),
                        year = 2030,
                        today = today,
                        currentYear = 2030,
                    )?.count shouldBe
                    0L
            }
        }
    })
