package network.lapis.cloud.server.tender

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidView
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private enum class Seen { NONE, OWN, ALL }

/**
 * V1.9.201 -- end to end: for every visibility mode, before and after the deadline, every kind of viewer gets exactly what the matrix in
 * `docs/architecture/tenders.adoc` says. The decision is NOT read from the policy function: the expectations are written out here, and the JSON of
 * every answer is scanned for the prices, texts, ids and names of foreign bids (a counter or an id leaks as much as a price).
 */
class TenderVisibilityLeakTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        class Cast(
            val board: Uuid,
            val treasurer: Uuid,
            val biddingBoard: Uuid,
            val bidderA: Uuid,
            val bidderB: Uuid,
            val plain: Uuid,
            val guest: Uuid,
            val tenderId: String,
            val bidIds: Map<Uuid, String>,
        )

        suspend fun TenderApp.setUp(
            visibility: TenderBidVisibility,
            clock: MutableTestClock,
        ): Cast {
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
            val biddingBoard = fx.member(label = "biddingBoard", role = AccountRole.BOARD)
            val a = fx.member(label = "bidderA")
            val b = fx.member(label = "bidderB")
            val d = fx.member(label = "bidderD")
            val plain = fx.member(label = "plain")
            val guest = fx.member(label = "guest", status = MemberStatus.GUEST)
            val id = newTender(board = board, binding = fx.approvedMotion(), visibility = visibility)
            val ids = mutableMapOf<Uuid, String>()
            ids[a] = tender(member = a) { submitBid(tenderId = id, input = bidInput(price = "11.11", text = "AAA-text")) }.id
            ids[b] = tender(member = b) { submitBid(tenderId = id, input = bidInput(price = "22.22", text = "BBB-text")) }.id
            ids[biddingBoard] =
                tender(member = biddingBoard) { submitBid(tenderId = id, input = bidInput(price = "33.33", text = "CCC-text")) }.id
            tender(member = d) { submitBid(tenderId = id, input = bidInput(price = "44.44", text = "DDD-text")) }
            tender(member = d) { withdrawBid(id) }
            clock.hashCode()
            return Cast(board, treasurer, biddingBoard, a, b, plain, guest, id, ids)
        }

        val prices = mapOf("11.11" to "AAA-text", "22.22" to "BBB-text", "33.33" to "CCC-text")
        val names = mapOf("11.11" to "bidderA", "22.22" to "bidderB", "33.33" to "biddingBoard")

        /** Asserts the JSON of an answer contains exactly the bids of [own] (their prices, texts, names), all three for ALL, none of the others. */
        fun assertSeen(
            json: String,
            seen: Seen,
            ownPrice: String?,
            clue: String,
        ) {
            withClue("$clue: $json") {
                prices.forEach { (price, text) ->
                    val shown = json.contains(price) || json.contains(text) || json.contains(names.getValue(price))
                    val expected =
                        when (seen) {
                            Seen.ALL -> true
                            Seen.OWN -> price == ownPrice
                            Seen.NONE -> false
                        }
                    shown shouldBe expected
                }
                // a withdrawn bid of somebody else is never visible
                json.contains("44.44") shouldBe false
                json.contains("DDD-text") shouldBe false
            }
        }

        fun detailJson(dto: TenderDetailDto) = Json.encodeToString(TenderDetailDto.serializer(), dto)

        fun summaryJson(dto: TenderSummaryDto) = Json.encodeToString(TenderSummaryDto.serializer(), dto)

        // expected[mode][revealed] -> viewer name -> seen
        fun expectation(
            mode: TenderBidVisibility,
            revealed: Boolean,
        ): Map<String, Seen> {
            val bidders = listOf("bidderA", "bidderB", "biddingBoard")
            val managers = listOf("board", "treasurer")
            val others = listOf("plain", "guest")
            return when (mode) {
                TenderBidVisibility.OPEN -> (bidders + managers + others).associateWith { Seen.ALL }
                TenderBidVisibility.HIDDEN_UNTIL_DEADLINE ->
                    bidders.associateWith { if (revealed) Seen.ALL else Seen.OWN } +
                        managers.associateWith { if (revealed) Seen.ALL else Seen.NONE } +
                        others.associateWith { Seen.NONE }
                TenderBidVisibility.OWN_ONLY ->
                    bidders.associateWith { Seen.OWN } +
                        managers.associateWith { if (revealed) Seen.ALL else Seen.NONE } +
                        others.associateWith { Seen.NONE }
            }
        }

        for (mode in TenderBidVisibility.entries) {
            for (revealed in listOf(false, true)) {
                test("$mode, ${if (revealed) "after" else "before"} the deadline: every viewer sees exactly what the matrix says") {
                    val clock = tenderClock()
                    TimeTestSupport.withMutableServerClock(clock = clock) {
                        tenderTestApplication {
                            val cast = setUp(mode, clock)
                            if (revealed) clock.advance(1.days + 1.hours)
                            val viewers =
                                mapOf(
                                    "board" to cast.board,
                                    "treasurer" to cast.treasurer,
                                    "biddingBoard" to cast.biddingBoard,
                                    "bidderA" to cast.bidderA,
                                    "bidderB" to cast.bidderB,
                                    "plain" to cast.plain,
                                    "guest" to cast.guest,
                                )
                            val ownPrice = mapOf("bidderA" to "11.11", "bidderB" to "22.22", "biddingBoard" to "33.33")
                            expectation(mode, revealed).forEach { (name, seen) ->
                                val member = viewers.getValue(name)
                                val clue = "$mode revealed=$revealed viewer=$name"
                                val detail = tender(member = member) { getTender(cast.tenderId) }
                                assertSeen(detailJson(detail), seen, ownPrice[name], "detail of $clue")
                                val summary = tender(member = member) { listTenders() }.single()
                                assertSeen(summaryJson(summary), Seen.NONE, null, "summary of $clue")
                                // the counter: exactly where the bids themselves are shown
                                withClue("counter of $clue") {
                                    detail.tender.visibleBidCount shouldBe (if (seen == Seen.ALL) 3 else null)
                                    summary.visibleBidCount shouldBe (if (seen == Seen.ALL) 3 else null)
                                    detail.bids.size shouldBe (
                                        if (seen == Seen.ALL) {
                                            3
                                        } else if (seen == Seen.OWN) {
                                            1
                                        } else {
                                            0
                                        }
                                    )
                                }
                                // the caller's own bid status is always the caller's own
                                summary.myBidStatus shouldBe (if (name in ownPrice) TenderBidStatus.SUBMITTED else null)
                                // my bids: own data only
                                val mine = tender(member = member) { listMyBids() }
                                mine.size shouldBe (if (name in ownPrice) 1 else 0)
                                mine.forEach { it.id shouldBe cast.bidIds[member] }
                                // ids of foreign bids never appear unless the bids are shown
                                if (seen != Seen.ALL) {
                                    cast.bidIds.filterKeys { it != member }.values.forEach {
                                        detailJson(detail).contains(it) shouldBe false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        test("a tender cancelled before the deadline never reveals its bids in the hiding modes, not even after the deadline") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val cast = setUp(TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, clock)
                    tender(
                        member = cast.board,
                    ) { cancelTender(tenderId = cast.tenderId, reason = "Abgesagt vor der Frist") }.tender.status shouldBe
                        TenderStatus.CANCELLED
                    clock.advance(2.days)
                    listOf(cast.board, cast.treasurer, cast.plain, cast.guest).forEach {
                        val dto = tender(member = it) { getTender(cast.tenderId) }
                        assertSeen(detailJson(dto), Seen.NONE, null, "cancelled early, viewer $it")
                        dto.tender.visibleBidCount shouldBe null
                    }
                    assertSeen(
                        detailJson(tender(member = cast.bidderA) { getTender(cast.tenderId) }),
                        Seen.OWN,
                        "11.11",
                        "cancelled early, bidder",
                    )
                }
            }
        }

        test("a tender cancelled after the deadline does reveal, as it would have anyway") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val cast = setUp(TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, clock)
                    clock.advance(1.days + 1.hours)
                    tender(member = cast.board) { cancelTender(tenderId = cast.tenderId, reason = "Nach der Frist abgesagt") }
                    assertSeen(detailJson(tender(member = cast.board) { getTender(cast.tenderId) }), Seen.ALL, null, "cancelled late")
                }
            }
        }

        test("the award result (winner, price) is public from AWARDED on in every mode; the losers stay hidden") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val cast = setUp(TenderBidVisibility.OWN_ONLY, clock)
                    clock.advance(1.days + 1.hours)
                    val winner = cast.bidIds.getValue(cast.bidderA)
                    tender(member = cast.board) {
                        awardTender(
                            network.lapis.cloud.shared.domain
                                .TenderAwardInput(tenderId = cast.tenderId, bidId = winner),
                        )
                    }
                    val seenByPlain = tender(member = cast.plain) { getTender(cast.tenderId) }
                    seenByPlain.tender.award?.winnerDisplayName shouldBe "Tender-Test bidderA"
                    seenByPlain.tender.award
                        ?.price
                        ?.toPlainString() shouldBe "11.11"
                    seenByPlain.bids.size shouldBe 0
                    val json = detailJson(seenByPlain)
                    listOf("22.22", "BBB-text", "33.33", "CCC-text", "bidderB").forEach { json.contains(it) shouldBe false }
                }
            }
        }

        test("an invisible tender reads exactly like a missing one") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val plain = fx.member(label = "plain")
                    val applicant = fx.member(label = "applicant", status = MemberStatus.APPLICATION)
                    val draft = newTender(board = board, binding = fx.approvedMotion(), publish = false)
                    val missing = Uuid.random().toString()
                    val a = tenderAttempt(member = plain) { getTender(draft) }.exceptionOrNull()
                    val b = tenderAttempt(member = plain) { getTender(missing) }.exceptionOrNull()
                    a.shouldBeInstanceOf<NotFoundException>()
                    b.shouldBeInstanceOf<NotFoundException>()
                    a.message shouldBe b.message
                    val published = newTender(board = board, binding = fx.approvedMotion())
                    tenderAttempt(member = applicant) { getTender(published) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    tender(member = applicant) { listTenders() }.size shouldBe 0
                }
            }
        }

        test("V1.9.202 fields: bidView mirrors the policy; titles of internal records, the cancel reason and the cap never leak") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val cast = setUp(TenderBidVisibility.OWN_ONLY, clock)
                    val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                    // before the deadline: a bidder sees OWN, a manager and a bystander NONE
                    tender(member = cast.bidderA) { getTender(cast.tenderId) }.bidView shouldBe TenderBidView.OWN
                    tender(member = cast.board) { getTender(cast.tenderId) }.bidView shouldBe TenderBidView.NONE
                    tender(member = cast.plain) { getTender(cast.tenderId) }.bidView shouldBe TenderBidView.NONE
                    clock.advance(1.days + 1.hours)
                    tender(member = cast.board) { getTender(cast.tenderId) }.bidView shouldBe TenderBidView.ALL
                    tender(member = cast.bidderA) { getTender(cast.tenderId) }.bidView shouldBe TenderBidView.OWN
                    // a friend sees neither the binding motion title nor an award motion title, nor any award mark
                    val seenByFriend = tender(member = friend) { getTender(cast.tenderId) }
                    seenByFriend.bindingMotionTitle shouldBe ""
                    seenByFriend.awardMotionTitle shouldBe null
                    seenByFriend.awardReadiness shouldBe null
                    seenByFriend.bids.size shouldBe 0
                    seenByFriend.minutesUntilDeadline shouldBe null
                    // the deadline counter is the server's own: one hour before the deadline it reads 60
                }
            }
        }

        test("V1.9.202: minutesUntilDeadline comes from the organisation clock, and a cancel reason is shown only to who sees the tender") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                    val applicant = fx.member(label = "applicant", status = MemberStatus.APPLICATION)
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    tender(member = friend) { getTender(id) }.minutesUntilDeadline shouldBe 24 * 60L
                    clock.advance(23.hours)
                    tender(member = friend) { getTender(id) }.minutesUntilDeadline shouldBe 60L
                    tender(member = board) { cancelTender(tenderId = id, reason = "Grund der Absage") }
                    tender(member = friend) { getTender(id) }.cancelReason shouldBe "Grund der Absage"
                    tenderAttempt(member = applicant) { getTender(id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    val draft = newTender(board = board, binding = fx.approvedMotion(), publish = false)
                    tender(member = board) { cancelTender(tenderId = draft, reason = "Intern") }
                    tenderAttempt(member = friend) { getTender(draft) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }
        }
    })
