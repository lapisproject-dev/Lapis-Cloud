package network.lapis.cloud.server.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.partg.PartgVersionFormat
import network.lapis.cloud.server.partg.PartgVersionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- the party report signal: shown only to BOARD and ADMIN of a political party, for the EARLIEST report year that is not internally
 * final, with a priority from the deadline. Digits and ISO dates in `params` only (the collector drops anything else), never an object id.
 * The frozen "now" is 2030-06-10, so the default report year is 2029 with the proposed deadline 2030-09-30.
 */
class PartyReportSignalsTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val party =
            SignalModuleFlags(
                auctionEnabled = true,
                conferenceEnabled = true,
                mcpEnabled = true,
                dunningEnabled = true,
                politicalParty = true,
            )
        val notParty = party.copy(politicalParty = false)
        val at = LocalDateTime(2030, 1, 1, 0, 0)

        fun keep(
            by: Uuid,
            year: Int,
            number: Int = 1,
            final: Boolean = false,
            scope: String = "ALL",
        ) = transaction {
            val counter = PartgVersionStore.lockCounter(year = year, scopeKey = scope, actor = by, now = at)
            val json = "{\"y\":$year,\"n\":$number}"
            val id =
                PartgVersionStore.insertVersion(
                    counter = counter,
                    number = number,
                    formatVersion = PartgVersionFormat.CURRENT,
                    contentJson = json,
                    contentHash = PartgVersionFormat.hash(json),
                    byteSize = json.length,
                    hasThirdPartyData = false,
                    comment = null,
                    actor = by,
                    now = at,
                )
            PartgVersionStore.bumpCounter(counter = counter, number = number, actor = by, now = at)
            if (final) PartgVersionStore.markFinal(id = id, actor = by, now = at)
        }

        fun deadline(
            by: Uuid,
            year: Int,
            date: LocalDate,
        ) = transaction { PartgVersionStore.upsertDeadline(year = year, deadline = date, actor = by, now = at) }

        fun signals(
            member: Uuid,
            role: AccountRole = AccountRole.BOARD,
            flags: SignalModuleFlags = party,
        ): List<DashboardSignalDto> =
            DashboardSignalCollector
                .collect(ctx = dashboardContext(memberId = member, role = role, flags = flags), sources = DashboardSources.all)
                .filter { it.moduleKey == DashboardModuleKeys.PARTG }

        test("no version, no confirmed deadline: one INFO card for last year with the proposal") {
            rolledBack {
                val me = DashboardFixtures.member()
                val card = signals(me).single()
                card.reasonKey shouldBe DashboardReasonKeys.PARTG_REPORT_DEADLINE
                card.count shouldBe 1
                card.objectId shouldBe null
                card.priority shouldBe DashboardPriority.INFO
                card.params shouldBe mapOf("year" to "2029", "statusCode" to "0", "confirmed" to "0", "deadline" to "2030-09-30")
            }
        }

        test("the priority follows the deadline: overdue is URGENT, up to 30 days ACTION, later INFO") {
            rolledBack {
                val me = DashboardFixtures.member()
                deadline(me, 2029, LocalDate(2030, 6, 1))
                signals(me).single().priority shouldBe DashboardPriority.URGENT
                deadline(me, 2029, LocalDate(2030, 7, 10))
                signals(me).single().priority shouldBe DashboardPriority.ACTION
                deadline(me, 2029, LocalDate(2030, 7, 11))
                signals(me).single().priority shouldBe DashboardPriority.INFO
                deadline(me, 2029, LocalDate(2030, 9, 8))
                signals(me).single().params["confirmed"] shouldBe "1"
            }
        }

        test("a confirmed deadline more than 90 days away makes no card; a kept version does not end the card") {
            rolledBack {
                val me = DashboardFixtures.member()
                deadline(me, 2029, LocalDate(2030, 12, 31))
                signals(me).shouldBeEmpty()
                keep(me, 2029)
                signals(me).shouldBeEmpty()
                deadline(me, 2029, LocalDate(2030, 8, 1))
                signals(me).single().params["statusCode"] shouldBe "1"
            }
        }

        test("an internally final year makes no card, whatever the deadline") {
            rolledBack {
                val me = DashboardFixtures.member()
                keep(me, 2029, final = true)
                deadline(me, 2029, LocalDate(2030, 6, 1))
                signals(me).shouldBeEmpty()
            }
        }

        test("the earliest year that is not final wins") {
            rolledBack {
                val me = DashboardFixtures.member()
                keep(me, 2027)
                keep(me, 2029, final = true)
                signals(me).single().params["year"] shouldBe "2027"
                keep(me, 2027, number = 2, final = true)
                signals(me).shouldBeEmpty()
            }
        }

        test("a version of a single unit does not count for the year status") {
            rolledBack {
                val me = DashboardFixtures.member()
                keep(me, 2029, scope = "FEDERAL", final = true)
                signals(me).single().params["statusCode"] shouldBe "0"
            }
        }

        test("only BOARD and ADMIN of a political party see it") {
            rolledBack {
                val me = DashboardFixtures.member()
                signals(me, role = AccountRole.ADMIN).size shouldBe 1
                signals(me, role = AccountRole.MEMBER).shouldBeEmpty()
                signals(me, role = AccountRole.TREASURER).shouldBeEmpty()
                signals(me, role = AccountRole.BOARD, flags = notParty).shouldBeEmpty()
            }
        }
    })

private fun <T> transaction(block: () -> T): T =
    org.jetbrains.exposed.v1.jdbc.transactions
        .transaction { block() }
