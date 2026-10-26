package network.lapis.cloud.server.dashboard

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.dashboard.DashboardFixtures.SECRET
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import kotlin.uuid.Uuid

private fun List<DashboardSignalDto>.reason(key: String): DashboardSignalDto? = firstOrNull { it.reasonKey == key }

private fun at(
    day: Int,
    hour: Int = 12,
): LocalDateTime = LocalDateTime(2030, 6, day, hour, 0)

private fun collect(ctx: SignalContext): List<DashboardSignalDto> =
    DashboardSignalCollector.collect(ctx = ctx, sources = DashboardSources.all)

/**
 * V1.9.224 -- the signal sources against a real schema (H2 and PostgreSQL). Every scenario runs inside a transaction that is rolled
 * back, so
 * nothing needs cleaning up. The frozen "now" is 2030-06-10 12:00. Positive and negative cases per source; the secrets of other people are
 * recognisable strings that must never show up in the serialized signals.
 */
abstract class DashboardSignalScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec { db.deactivate() }

        context("auctions") {
            test("outbid: the proxy tie goes to the earlier bid, the 24 hour edge decides between 300 and 200") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val rival = DashboardFixtures.member()
                    val seller = DashboardFixtures.member()
                    val tied = DashboardFixtures.auction(seller = seller, endsAt = at(day = 12))
                    DashboardFixtures.bid(auctionId = tied, bidder = rival, max = "10.00", at = at(day = 9))
                    DashboardFixtures.bid(auctionId = tied, bidder = me, max = "10.00", at = at(day = 10))
                    val outbid = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.AUCTION_OUTBID)!!
                    outbid.count shouldBe 1
                    outbid.priority shouldBe DashboardPriority.ACTION
                    outbid.objectId shouldBe tied.toString()
                    outbid.deadline shouldBe "2030-06-12T12:00:00"

                    // 24 hours exactly = urgent; a second later = not
                    val edge = DashboardFixtures.auction(seller = seller, endsAt = at(day = 11))
                    DashboardFixtures.bid(auctionId = edge, bidder = rival, max = "10.00", at = at(day = 9))
                    DashboardFixtures.bid(auctionId = edge, bidder = me, max = "5.00", at = at(day = 10))
                    val both = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.AUCTION_OUTBID)!!
                    both.count shouldBe 2
                    both.priority shouldBe DashboardPriority.URGENT
                    both.objectId shouldBe null
                    both.deadline shouldBe "2030-06-11T12:00:00"
                }
            }

            test(
                "a deadline of a class A column is shown as the organization wall clock, a class B column untouched (zone Europe/Berlin)",
            ) {
                rolledBack {
                    val berlin = TimeZone.of("Europe/Berlin")
                    val me = DashboardFixtures.member()
                    val rival = DashboardFixtures.member()
                    val auction = DashboardFixtures.auction(seller = rival, endsAt = at(day = 12))
                    DashboardFixtures.bid(auctionId = auction, bidder = rival, max = "9.00", at = at(day = 9))
                    DashboardFixtures.bid(auctionId = auction, bidder = me, max = "1.00", at = at(day = 10))
                    val g = DashboardFixtures.governance()
                    // wall clock of the tender: nowWall is 14:00 in Berlin (summer time), so 13:00 on the 12th is 47 hours ahead -> urgent
                    DashboardFixtures.tender(g = g, deadline = LocalDateTime(2030, 6, 12, 13, 0))
                    val signals = collect(dashboardContext(memberId = me, zone = berlin))
                    signals.reason(DashboardReasonKeys.AUCTION_OUTBID)!!.deadline shouldBe "2030-06-12T14:00:00"
                    val tender = signals.reason(DashboardReasonKeys.TENDER_DEADLINE_SOON)!!
                    tender.deadline shouldBe "2030-06-12T13:00:00"
                    tender.priority shouldBe DashboardPriority.URGENT
                }
            }

            test("leading is not outbid; ending soon only while I lead and it ends within 24 hours") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val rival = DashboardFixtures.member()
                    val seller = DashboardFixtures.member()
                    val soon = DashboardFixtures.auction(seller = seller, endsAt = at(day = 11))
                    val later = DashboardFixtures.auction(seller = seller, endsAt = at(day = 20))
                    listOf(soon, later).forEach {
                        DashboardFixtures.bid(auctionId = it, bidder = rival, max = "5.00", at = at(day = 9))
                        DashboardFixtures.bid(auctionId = it, bidder = me, max = "9.00", at = at(day = 10))
                    }
                    val signals = collect(dashboardContext(memberId = me))
                    signals.reason(DashboardReasonKeys.AUCTION_OUTBID) shouldBe null
                    val ending = signals.reason(DashboardReasonKeys.AUCTION_ENDING_SOON)!!
                    ending.count shouldBe 1
                    ending.objectId shouldBe soon.toString()
                }
            }

            test("own auction with bids carries the number of bids only for one auction") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val rival = DashboardFixtures.member()
                    val mine = DashboardFixtures.auction(seller = me, endsAt = at(day = 20))
                    DashboardFixtures.auction(seller = me, endsAt = at(day = 21))
                    DashboardFixtures.bid(auctionId = mine, bidder = rival, max = "5.00")
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.AUCTION_OWN_HAS_BIDS)!!
                    signal.count shouldBe 1
                    signal.params shouldBe mapOf("bids" to "1")
                    DashboardFixtures.bid(auctionId = mine, bidder = DashboardFixtures.member(), max = "6.00")
                    collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.AUCTION_OWN_HAS_BIDS)!!.params shouldBe
                        mapOf("bids" to "2")
                }
            }

            test("won and sold show for seven days, not for eight") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val other = DashboardFixtures.member()
                    val recent = LocalDateTime(2030, 6, 4, 12, 0) // 6 days
                    val old = LocalDateTime(2030, 6, 2, 11, 0) // 8 days
                    DashboardFixtures.auction(
                        seller = other,
                        endsAt = recent,
                        status = AuctionStatus.SETTLED,
                        winner = me,
                        settledAt = recent,
                    )
                    DashboardFixtures.auction(
                        seller = me,
                        endsAt = recent,
                        status = AuctionStatus.SETTLED,
                        winner = other,
                        settledAt = recent,
                    )
                    val signals = collect(dashboardContext(memberId = me))
                    signals.reason(DashboardReasonKeys.AUCTION_WON)!!.count shouldBe 1
                    signals.reason(DashboardReasonKeys.AUCTION_SOLD)!!.count shouldBe 1
                    val oldMember = DashboardFixtures.member()
                    DashboardFixtures.auction(
                        seller = other,
                        endsAt = old,
                        status = AuctionStatus.SETTLED,
                        winner = oldMember,
                        settledAt = old,
                    )
                    collect(dashboardContext(memberId = oldMember)).shouldBeEmpty()
                }
            }

            test("nothing when the instance switch is off, for a friend, and nothing about other people's auctions") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val friend = DashboardFixtures.member(status = MemberStatus.FRIEND)
                    val rival = DashboardFixtures.member()
                    val seller = DashboardFixtures.member()
                    val auction = DashboardFixtures.auction(seller = seller, endsAt = at(day = 12))
                    DashboardFixtures.bid(auctionId = auction, bidder = me, max = "5.00")
                    DashboardFixtures.bid(auctionId = auction, bidder = rival, max = "9.00")
                    val off = SignalModuleFlags(auctionEnabled = false, conferenceEnabled = true, mcpEnabled = true, dunningEnabled = true)
                    collect(dashboardContext(memberId = me, flags = off)).shouldBeEmpty()
                    collect(dashboardContext(memberId = friend, status = MemberStatus.FRIEND)).shouldBeEmpty()
                    // somebody who never bid sees nothing, although the auction is outbid for me
                    collect(dashboardContext(memberId = DashboardFixtures.member())).shouldBeEmpty()
                }
            }

            test("an auction that ended but is still stored OPEN is not outbid") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val rival = DashboardFixtures.member()
                    val auction = DashboardFixtures.auction(seller = rival, endsAt = at(day = 9))
                    DashboardFixtures.bid(auctionId = auction, bidder = me, max = "1.00")
                    DashboardFixtures.bid(auctionId = auction, bidder = rival, max = "9.00")
                    collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.AUCTION_OUTBID) shouldBe null
                }
            }
        }

        context("tenders") {
            test("deadline soon: 7 days yes, 8 days no, 48 hours is urgent, an own bid silences it, a withdrawn bid does not") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val me = DashboardFixtures.member()
                    val near = DashboardFixtures.tender(g = g, deadline = at(day = 17))
                    DashboardFixtures.tender(g = g, deadline = at(day = 18, hour = 13)) // 8 days + 1 hour
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.TENDER_DEADLINE_SOON)!!
                    signal.count shouldBe 1
                    signal.priority shouldBe DashboardPriority.ACTION
                    signal.objectId shouldBe near.toString()
                    val urgent = DashboardFixtures.tender(g = g, deadline = at(day = 12))
                    collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.TENDER_DEADLINE_SOON)!!.priority shouldBe
                        DashboardPriority.URGENT
                    DashboardFixtures.tenderBid(tenderId = urgent, bidder = me)
                    DashboardFixtures.tenderBid(tenderId = near, bidder = me, status = TenderBidStatus.WITHDRAWN)
                    val remaining = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.TENDER_DEADLINE_SOON)!!
                    remaining.count shouldBe 1
                    remaining.priority shouldBe DashboardPriority.ACTION
                }
            }

            test("a draft or a closed tender gives nothing; a guest has no tender menu entry and gets nothing; a friend may bid on LTR") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val friend = DashboardFixtures.member(status = MemberStatus.FRIEND)
                    DashboardFixtures.tender(g = g, deadline = at(day = 12), status = TenderStatus.DRAFT)
                    DashboardFixtures.tender(g = g, deadline = at(day = 12), status = TenderStatus.CLOSED)
                    collect(dashboardContext(memberId = friend, status = MemberStatus.FRIEND)).shouldBeEmpty()
                    val open = DashboardFixtures.tender(g = g, deadline = at(day = 12))
                    collect(dashboardContext(memberId = friend, status = MemberStatus.FRIEND))
                        .reason(DashboardReasonKeys.TENDER_DEADLINE_SOON)!!
                        .objectId shouldBe open.toString()
                    val guest = DashboardFixtures.member(status = MemberStatus.GUEST)
                    collect(dashboardContext(memberId = guest, status = MemberStatus.GUEST)).shouldBeEmpty()
                }
            }

            test("own bid decided within 14 days") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val me = DashboardFixtures.member()
                    val won = DashboardFixtures.tender(g = g, deadline = at(day = 5), status = TenderStatus.AWARDED)
                    val lost = DashboardFixtures.tender(g = g, deadline = at(day = 5), status = TenderStatus.AWARDED)
                    val bid = DashboardFixtures.tenderBid(tenderId = won, bidder = me, status = TenderBidStatus.AWARDED)
                    DashboardFixtures.tenderAward(tenderId = won, bidId = bid, by = g.submitter, at = at(day = 8))
                    val winner = DashboardFixtures.member()
                    val winnerBid = DashboardFixtures.tenderBid(tenderId = lost, bidder = winner, status = TenderBidStatus.AWARDED)
                    DashboardFixtures.tenderBid(tenderId = lost, bidder = me, status = TenderBidStatus.REJECTED)
                    DashboardFixtures.tenderAward(
                        tenderId = lost,
                        bidId = winnerBid,
                        by = g.submitter,
                        at = LocalDateTime(2030, 5, 20, 12, 0),
                    )
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.TENDER_OWN_BID_DECIDED)!!
                    signal.count shouldBe 1 // the 21 day old decision is out
                    signal.objectId shouldBe won.toString()
                }
            }
        }

        context("elections, consensus, polls, votes") {
            test("election: eligible and not voted yes; voted (secret) no; not in the electorate no; closed no") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val me = DashboardFixtures.member()
                    val outsider = DashboardFixtures.member()
                    val open = DashboardFixtures.election(g = g, secret = true, eligible = listOf(me))
                    DashboardFixtures.election(g = g, secret = true, eligible = listOf(me), voted = listOf(me))
                    DashboardFixtures.election(g = g, secret = false, eligible = listOf(outsider))
                    DashboardFixtures.election(g = g, secret = true, status = network.lapis.cloud.shared.domain.ElectionStatus.CLOSED)
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.ELECTION_NOT_VOTED)!!
                    signal.count shouldBe 1
                    signal.priority shouldBe DashboardPriority.ACTION
                    signal.deadline shouldBe null
                    signal.params shouldBe emptyMap()
                    signal.objectId shouldBe open.toString()
                    collect(dashboardContext(memberId = DashboardFixtures.member())).shouldBeEmpty()
                }
            }

            test("consensus: eligible in the current round and not rated") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val me = DashboardFixtures.member()
                    DashboardFixtures.consensus(g = g, eligible = listOf(me))
                    DashboardFixtures.consensus(g = g, eligible = emptyList())
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.CONSENSUS_NOT_VOTED)!!
                    signal.count shouldBe 1
                    signal.priority shouldBe DashboardPriority.ACTION
                }
            }

            test("poll: urgent within 48 hours with the date, no deadline is plain, answered and closed give nothing") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val creator = DashboardFixtures.member()
                    DashboardFixtures.poll(creator = creator, closesAt = null)
                    var signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.SURVEY_NOT_ANSWERED)!!
                    signal.priority shouldBe DashboardPriority.ACTION
                    signal.params shouldBe emptyMap()
                    val urgent = DashboardFixtures.poll(creator = creator, closesAt = at(day = 12))
                    signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.SURVEY_NOT_ANSWERED)!!
                    signal.count shouldBe 2
                    signal.priority shouldBe DashboardPriority.URGENT
                    signal.params shouldBe mapOf("date" to "2030-06-12")
                    signal.deadline shouldBe "2030-06-12T12:00:00"
                    DashboardFixtures.pollAnswered(pollId = urgent, memberId = me)
                    DashboardFixtures.poll(creator = creator, closesAt = at(day = 9)) // deadline passed -> effectively closed
                    DashboardFixtures.poll(creator = creator, closesAt = null, status = PollStatus.CLOSED)
                    signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.SURVEY_NOT_ANSWERED)!!
                    signal.count shouldBe 1
                    signal.priority shouldBe DashboardPriority.ACTION
                }
            }

            test("meritocratic vote: eligible and no ballot yes, ballot cast no, outside the frozen electorate no") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val me = DashboardFixtures.member()
                    DashboardFixtures.vote(g = g, eligible = listOf(me))
                    val cast = DashboardFixtures.vote(g = g, eligible = listOf(me))
                    DashboardFixtures.voteBallot(voteId = cast, memberId = me)
                    DashboardFixtures.vote(g = g, eligible = listOf(DashboardFixtures.member()))
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.MOTION_VOTE_OPEN)!!
                    signal.count shouldBe 1
                    signal.objectId.shouldNotBeNullAndNotEmpty()
                }
            }
        }

        context("meetings") {
            test("own committee within seven days, not eight, not planned-only, not a foreign committee, not an ended seat") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val (mine, nearMeeting) = DashboardFixtures.committee(meetingAt = at(day = 15))
                    DashboardFixtures.seat(committeeId = mine, memberId = me)
                    DashboardFixtures.meeting(committeeId = mine, at = at(day = 18, hour = 13)) // 8 days + 1 hour
                    DashboardFixtures.meeting(
                        committeeId = mine,
                        at = at(day = 11),
                        status = network.lapis.cloud.shared.domain.MeetingStatus.CANCELLED,
                    )
                    val (foreign, _) = DashboardFixtures.committee(meetingAt = at(day = 12))
                    val (ended, _) = DashboardFixtures.committee(meetingAt = at(day = 12))
                    DashboardFixtures.seat(committeeId = ended, memberId = me, until = LocalDate(2030, 6, 1))
                    DashboardFixtures.seat(committeeId = foreign, memberId = DashboardFixtures.member())
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.MEETING_UPCOMING)!!
                    signal.count shouldBe 1
                    signal.priority shouldBe DashboardPriority.INFO
                    signal.params shouldBe mapOf("date" to "2030-06-15")
                    signal.objectId shouldBe nearMeeting.toString()
                }
            }

            test("the general assembly meeting reaches every active member") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    DashboardFixtures.committee(type = CommitteeType.GENERAL_ASSEMBLY, meetingAt = at(day = 14))
                    val signal = collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.MEETING_UPCOMING)
                    (signal != null && signal.count >= 1) shouldBe true
                }
            }
        }

        context("membership") {
            test("crowdfunding decision within 14 days") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    DashboardFixtures.crowdfunding(submitter = me, status = CrowdfundingProjectStatus.APPROVED, reviewedAt = at(day = 8))
                    DashboardFixtures.crowdfunding(
                        submitter = me,
                        status = CrowdfundingProjectStatus.REJECTED,
                        reviewedAt = LocalDateTime(2030, 5, 20, 0, 0),
                    )
                    DashboardFixtures.crowdfunding(submitter = me, status = CrowdfundingProjectStatus.PENDING, reviewedAt = null)
                    collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.CROWDFUNDING_OWN_DECIDED)!!.count shouldBe 1
                }
            }

            test("next registered event: confirmed only, within 14 days, class B date untouched even with a non-UTC zone") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    val zone = TimeZone.of("Asia/Tokyo")
                    val eventDay = LocalDateTime(2030, 6, 20, 23, 30)
                    val event = DashboardFixtures.event(creator = me, startsAt = eventDay)
                    DashboardFixtures.registration(eventId = event, memberId = me)
                    DashboardFixtures.registration(
                        eventId = DashboardFixtures.event(creator = me, startsAt = LocalDateTime(2030, 6, 12, 10, 0)),
                        memberId = me,
                        status = EventRegistrationStatus.WAITLISTED,
                    )
                    DashboardFixtures.registration(
                        eventId = DashboardFixtures.event(creator = me, startsAt = LocalDateTime(2030, 7, 20, 10, 0)),
                        memberId = me,
                    )
                    val signal = collect(dashboardContext(memberId = me, zone = zone)).reason(DashboardReasonKeys.EVENTS_NEXT_REGISTERED)!!
                    signal.params shouldBe mapOf("date" to "2030-06-20")
                    signal.deadline shouldBe "2030-06-20T23:30:00"
                    signal.objectId shouldBe event.toString()
                }
            }

            test(
                "contributions: overdue is urgent, open an action, SEPA debit is left alone, the earliest due date is the parameter",
            ) {
                rolledBack {
                    val me = DashboardFixtures.member()
                    DashboardFixtures.contribution(memberId = me, status = ContributionStatus.OPEN, dueDate = LocalDate(2030, 8, 1))
                    DashboardFixtures.contribution(memberId = me, status = ContributionStatus.OPEN, dueDate = LocalDate(2030, 7, 1))
                    DashboardFixtures.contribution(
                        memberId = me,
                        status = ContributionStatus.OPEN,
                        dueDate = LocalDate(2030, 6, 1),
                        method = ContributionPaymentMethod.SEPA_DEBIT,
                    )
                    DashboardFixtures.contribution(memberId = me, status = ContributionStatus.PAID)
                    var signals = collect(dashboardContext(memberId = me))
                    signals.reason(DashboardReasonKeys.CONTRIBUTIONS_OVERDUE) shouldBe null
                    val open = signals.reason(DashboardReasonKeys.CONTRIBUTIONS_OPEN)!!
                    open.count shouldBe 2
                    open.priority shouldBe DashboardPriority.ACTION
                    open.params shouldBe mapOf("date" to "2030-07-01")
                    DashboardFixtures.contribution(memberId = me, status = ContributionStatus.IN_DUNNING)
                    signals = collect(dashboardContext(memberId = me))
                    signals.reason(DashboardReasonKeys.CONTRIBUTIONS_OVERDUE)!!.priority shouldBe DashboardPriority.URGENT
                    // somebody else's contribution never shows up for me
                    collect(
                        dashboardContext(memberId = DashboardFixtures.member()),
                    ).reason(DashboardReasonKeys.CONTRIBUTIONS_OVERDUE) shouldBe
                        null
                }
            }

            test("travel expenses: decided or paid within 14 days") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    DashboardFixtures.travel(memberId = me, status = TravelExpenseReportStatus.APPROVED, decidedAt = at(day = 9))
                    DashboardFixtures.travel(
                        memberId = me,
                        status = TravelExpenseReportStatus.EXECUTED,
                        decidedAt = at(day = 1),
                        executedAt = at(day = 8),
                    )
                    DashboardFixtures.travel(
                        memberId = me,
                        status = TravelExpenseReportStatus.REJECTED,
                        decidedAt = LocalDateTime(2030, 5, 20, 0, 0),
                    )
                    DashboardFixtures.travel(memberId = me, status = TravelExpenseReportStatus.REQUESTED, decidedAt = null)
                    collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.TRAVEL_STATUS_CHANGED)!!.count shouldBe 2
                }
            }

            test("AI drafts: only while MCP is on, only OPEN, only mine") {
                rolledBack {
                    val me = DashboardFixtures.member()
                    DashboardFixtures.aiDraft(memberId = me)
                    DashboardFixtures.aiDraft(memberId = me, status = network.lapis.cloud.shared.domain.McpPostDraftStatus.RELEASED)
                    DashboardFixtures.aiDraft(memberId = DashboardFixtures.member())
                    collect(dashboardContext(memberId = me)).reason(DashboardReasonKeys.AI_DRAFTS_WAITING)!!.count shouldBe 1
                    val off = SignalModuleFlags(auctionEnabled = true, conferenceEnabled = true, mcpEnabled = false, dunningEnabled = true)
                    collect(dashboardContext(memberId = me, flags = off)).reason(DashboardReasonKeys.AI_DRAFTS_WAITING) shouldBe null
                }
            }
        }

        context("role based head counts") {
            test("applications: board and admin see a bare count without an id, a member sees nothing") {
                rolledBack {
                    val board = DashboardFixtures.member(role = AccountRole.BOARD)
                    val before =
                        collect(
                            dashboardContext(memberId = board, role = AccountRole.BOARD),
                        ).reason(DashboardReasonKeys.APPLICATIONS_OPEN)?.count
                            ?: 0
                    DashboardFixtures.member(status = MemberStatus.APPLICATION)
                    val signal =
                        collect(
                            dashboardContext(memberId = board, role = AccountRole.BOARD),
                        ).reason(DashboardReasonKeys.APPLICATIONS_OPEN)!!
                    signal.count shouldBe before + 1
                    signal.objectId shouldBe null
                    signal.priority shouldBe DashboardPriority.ACTION
                    val member = DashboardFixtures.member()
                    collect(dashboardContext(memberId = member)).reason(DashboardReasonKeys.APPLICATIONS_OPEN) shouldBe null
                    val treasurer = DashboardFixtures.member(role = AccountRole.TREASURER)
                    collect(
                        dashboardContext(memberId = treasurer, role = AccountRole.TREASURER),
                    ).reason(DashboardReasonKeys.APPLICATIONS_OPEN) shouldBe
                        null
                }
            }

            test("treasury: overdue contributions count for the roles that read dunning, only with dunning on, never with an id") {
                rolledBack {
                    val treasurer = DashboardFixtures.member(role = AccountRole.TREASURER)
                    val payer = DashboardFixtures.member()
                    val ctx = dashboardContext(memberId = treasurer, role = AccountRole.TREASURER)
                    val before = collect(ctx).reason(DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING)?.count ?: 0
                    DashboardFixtures.contribution(memberId = payer, status = ContributionStatus.OVERDUE)
                    DashboardFixtures.contribution(memberId = payer, status = ContributionStatus.IN_DUNNING)
                    val signal = collect(ctx).reason(DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING)!!
                    signal.count shouldBe before + 1
                    signal.objectId shouldBe null
                    val off = SignalModuleFlags(auctionEnabled = true, conferenceEnabled = true, mcpEnabled = true, dunningEnabled = false)
                    collect(dashboardContext(memberId = treasurer, role = AccountRole.TREASURER, flags = off))
                        .reason(DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING) shouldBe null
                    collect(dashboardContext(memberId = payer)).reason(DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING) shouldBe null
                }
            }
        }

        context("contract") {
            test("no title, name, text or amount of anybody ever appears in the serialized signals") {
                rolledBack {
                    val g = DashboardFixtures.governance()
                    val me = DashboardFixtures.member(name = "$SECRET-Ich")
                    val rival = DashboardFixtures.member()
                    val auction = DashboardFixtures.auction(seller = rival, endsAt = at(day = 11))
                    DashboardFixtures.bid(auctionId = auction, bidder = rival, max = "99.00")
                    DashboardFixtures.bid(auctionId = auction, bidder = me, max = "5.00")
                    DashboardFixtures.tender(g = g, deadline = at(day = 12))
                    DashboardFixtures.election(g = g, secret = false, eligible = listOf(me))
                    DashboardFixtures.poll(creator = rival, closesAt = at(day = 12))
                    DashboardFixtures.contribution(memberId = me, status = ContributionStatus.OVERDUE)
                    DashboardFixtures.travel(memberId = me, status = TravelExpenseReportStatus.APPROVED, decidedAt = at(day = 9))
                    DashboardFixtures.aiDraft(memberId = me)
                    val signals = collect(dashboardContext(memberId = me))
                    (signals.size >= 6) shouldBe true
                    val json = Json.encodeToString(ListSerializer(DashboardSignalDto.serializer()), signals)
                    json shouldNotContain SECRET
                    json shouldNotContain "99.00"
                    signals.forEach { (it.moduleKey in DashboardModuleKeys.ALL) shouldBe true }
                }
            }

            test(
                "a source that throws is skipped; the others still answer, the transaction stays usable and the source runs exactly once",
            ) {
                rolledBack {
                    val me = DashboardFixtures.member()
                    DashboardFixtures.aiDraft(memberId = me)
                    var runs = 0
                    val broken =
                        object : DashboardSignalSource {
                            override val moduleKey = "broken"

                            override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
                                runs++
                                // a statement that fails on the database side: on PostgreSQL it would poison the transaction without a
                                // savepoint
                                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
                                    .current()
                                    .exec("SELECT 1 FROM table_that_does_not_exist_dashboard")
                                return emptyList()
                            }
                        }
                    val result =
                        DashboardSignalCollector.collect(
                            ctx = dashboardContext(memberId = me),
                            sources =
                                listOf(broken) + DashboardSources.all,
                        )
                    runs shouldBe 1
                    result.reason(DashboardReasonKeys.AI_DRAFTS_WAITING)!!.count shouldBe 1
                }
            }

            test("sorting: urgent first, then the earlier deadline (none last), then module and reason") {
                val a =
                    DashboardSignalDto(
                        moduleKey = DashboardModuleKeys.SURVEY,
                        reasonKey = DashboardReasonKeys.SURVEY_NOT_ANSWERED,
                        count = 1,
                        priority = 200,
                    )
                val b =
                    DashboardSignalDto(
                        moduleKey = DashboardModuleKeys.TENDER,
                        reasonKey = DashboardReasonKeys.TENDER_DEADLINE_SOON,
                        count = 1,
                        priority = 300,
                        deadline = "2030-06-12T12:00:00",
                    )
                val c =
                    DashboardSignalDto(
                        moduleKey = DashboardModuleKeys.AUCTION,
                        reasonKey = DashboardReasonKeys.AUCTION_OUTBID,
                        count = 1,
                        priority = 300,
                        deadline = "2030-06-11T12:00:00",
                    )
                val d =
                    DashboardSignalDto(
                        moduleKey = DashboardModuleKeys.CONTRIBUTIONS,
                        reasonKey = DashboardReasonKeys.CONTRIBUTIONS_OVERDUE,
                        count = 1,
                        priority = 300,
                    )
                DashboardSignalCollector.sanitizeAndSort(listOf(a, b, d, c)) shouldBe listOf(c, b, d, a)
            }

            test("sanitize drops free text parameters, an id on a count above one, unknown keys and a zero count; the count is capped") {
                val good =
                    DashboardSignalDto(
                        moduleKey = DashboardModuleKeys.SURVEY,
                        reasonKey = DashboardReasonKeys.SURVEY_NOT_ANSWERED,
                        count = 150,
                        priority = 200,
                    )
                val text = good.copy(params = mapOf("date" to "Meine Geheimnisse"))
                val idOnMany = good.copy(count = 2, objectId = Uuid.random().toString())
                val unknown = good.copy(reasonKey = "survey.unknown")
                val zero = good.copy(count = 0)
                val result = DashboardSignalCollector.sanitizeAndSort(listOf(good, text, idOnMany, unknown, zero))
                result.size shouldBe 1
                result.single().count shouldBe 99
            }
        }
    })

private fun String?.shouldNotBeNullAndNotEmpty() {
    (this != null && isNotEmpty()) shouldBe true
}

class DashboardSignalTest : DashboardSignalScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DashboardSignalPostgresTest : DashboardSignalScenarios(TestDatabase.Postgres())
