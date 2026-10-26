package network.lapis.cloud.server.dashboard

import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** The statement with every `?` replaced by the literal of its argument, so it can be handed to `EXPLAIN` as it is. */
@Suppress("UNCHECKED_CAST")
private fun expanded(
    context: StatementContext,
    transaction: Transaction,
): String {
    val arguments = context.args.iterator()
    val out = StringBuilder()
    context.sql(transaction).forEach { ch ->
        if (ch == '?' && arguments.hasNext()) {
            val (type, value) = arguments.next()
            out.append((type as IColumnType<Any?>).valueToString(value))
        } else {
            out.append(ch)
        }
    }
    return out.toString()
}

/**
 * V1.9.224 -- the index claim of `docs/features/dashboard.adoc`, proven instead of asserted: every statement the signal sources and
 * the sidebar
 * counters issue is captured, then explained on PostgreSQL with sequential scans switched OFF (`SET LOCAL enable_seqscan = off`). On tables
 * this small the planner always picks a sequential scan, so the plain plan would prove nothing; with the switch off a sequential scan
 * can only
 * appear when NO index can serve the predicate. A migration (V121) is justified by a failure of THIS test, never by a guess.
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DashboardQueryPlanPostgresTest :
    FunSpec({
        val db = TestDatabase.Postgres()
        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec { db.deactivate() }

        /** The tables that grow with the membership or with time; small lookup tables (committee, organization_settings) may be scanned. */
        val guarded =
            setOf(
                "auction",
                "auction_bid",
                "tender",
                "tender_bid",
                "tender_award",
                "election",
                "election_eligible_voter",
                "election_participation",
                "election_ballot",
                "systemic_consensus",
                "systemic_consensus_eligible_voter",
                "systemic_consensus_participation",
                "systemic_consensus_ballot",
                "poll",
                "poll_participation",
                "vote",
                "vote_ballot",
                "vote_eligible_voter",
                "meeting",
                "committee_membership",
                "crowdfunding_project",
                "event",
                "event_registration",
                "contribution",
                "travel_expense_report",
                "mcp_post_draft",
                "member",
                "conference_room",
                "conference_participation",
                "encounter_space",
            )

        test("no signal source and no sidebar counter needs a sequential scan on a growing table") {
            val statements = mutableListOf<String>()
            val plans = mutableListOf<Pair<String, List<String>>>()
            rolledBack {
                val g = DashboardFixtures.governance()
                val me = DashboardFixtures.member(role = AccountRole.ADMIN)
                val other = DashboardFixtures.member()
                // every source must get far enough to issue ALL its statements
                val running = DashboardFixtures.auction(seller = other, endsAt = LocalDateTime(2030, 6, 11, 12, 0))
                DashboardFixtures.bid(auctionId = running, bidder = me, max = "5.00")
                DashboardFixtures.bid(auctionId = running, bidder = other, max = "9.00")
                val own = DashboardFixtures.auction(seller = me, endsAt = LocalDateTime(2030, 6, 20, 12, 0))
                DashboardFixtures.bid(auctionId = own, bidder = other, max = "3.00")
                DashboardFixtures.auction(
                    seller = other,
                    endsAt = LocalDateTime(2030, 6, 8, 12, 0),
                    status = AuctionStatus.SETTLED,
                    winner = me,
                    settledAt = LocalDateTime(2030, 6, 8, 12, 0),
                )
                val tender = DashboardFixtures.tender(g = g, deadline = LocalDateTime(2030, 6, 12, 12, 0))
                val decided = DashboardFixtures.tender(g = g, deadline = LocalDateTime(2030, 6, 5, 12, 0), status = TenderStatus.AWARDED)
                val bid = DashboardFixtures.tenderBid(tenderId = decided, bidder = me, status = TenderBidStatus.AWARDED)
                DashboardFixtures.tenderAward(tenderId = decided, bidId = bid, by = g.submitter, at = LocalDateTime(2030, 6, 8, 12, 0))
                DashboardFixtures.tenderBid(tenderId = tender, bidder = other)
                DashboardFixtures.election(g = g, secret = true, eligible = listOf(me))
                DashboardFixtures.election(g = g, secret = false, eligible = listOf(me))
                DashboardFixtures.consensus(g = g, eligible = listOf(me))
                DashboardFixtures.poll(creator = other, closesAt = LocalDateTime(2030, 6, 12, 12, 0))
                DashboardFixtures.vote(g = g, eligible = listOf(me))
                val (committee, _) = DashboardFixtures.committee(meetingAt = LocalDateTime(2030, 6, 14, 12, 0))
                DashboardFixtures.seat(committeeId = committee, memberId = me)
                DashboardFixtures.crowdfunding(
                    submitter = me,
                    status = CrowdfundingProjectStatus.APPROVED,
                    reviewedAt = LocalDateTime(2030, 6, 9, 0, 0),
                )
                val event = DashboardFixtures.event(creator = other, startsAt = LocalDateTime(2030, 6, 15, 18, 0))
                DashboardFixtures.registration(eventId = event, memberId = me)
                DashboardFixtures.contribution(memberId = me, status = ContributionStatus.OVERDUE)
                DashboardFixtures.contribution(memberId = me, status = ContributionStatus.OPEN)
                DashboardFixtures.travel(
                    memberId = me,
                    status = TravelExpenseReportStatus.APPROVED,
                    decidedAt = LocalDateTime(2030, 6, 9, 0, 0),
                )
                DashboardFixtures.aiDraft(memberId = me)
                DashboardFixtures.member(status = MemberStatus.APPLICATION)
                val room = DashboardFixtures.room(creator = other)
                DashboardFixtures.present(roomId = room, memberId = other)
                val space = DashboardFixtures.space(creator = other, guests = true)
                DashboardFixtures.room(creator = other, encounterSpaceId = space)

                val tx = TransactionManager.current()
                tx.addLogger(
                    object : SqlLogger {
                        override fun log(
                            context: StatementContext,
                            transaction: Transaction,
                        ) {
                            val sql = expanded(context = context, transaction = transaction)
                            if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) statements += sql
                        }
                    },
                )
                val ctx = dashboardContext(memberId = me, role = AccountRole.ADMIN)
                DashboardSources.all.forEach { it.collect(ctx) }
                NavCountsQuery.count(status = MemberStatus.ACTIVE, conferenceEnabled = true)
                NavCountsQuery.count(status = MemberStatus.GUEST, conferenceEnabled = true)

                tx.exec("SET LOCAL enable_seqscan = off")
                statements.distinct().forEach { sql ->
                    val lines = mutableListOf<String>()
                    tx.exec("EXPLAIN $sql", explicitStatementType = StatementType.SELECT) { rs ->
                        while (rs.next()) lines += rs.getString(1)
                    }
                    plans += sql to lines
                }
            }
            (statements.size >= 25) shouldBe true
            val offenders =
                plans.flatMap { (sql, lines) ->
                    lines
                        .mapNotNull { Regex("""Seq Scan on (\w+)""").find(it)?.groupValues?.get(1) }
                        .filter { it in guarded }
                        .map { table -> "$table  <-  ${sql.take(200)}" }
                }
            val clue = "sequential scan although they are switched off (add an index in V121):\n"
            withClue(clue + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
            // the guard against a vacuous pass: the plans really did use indexes
            (plans.flatMap { it.second }.any { "Index" in it }) shouldBe true
        }
    })
