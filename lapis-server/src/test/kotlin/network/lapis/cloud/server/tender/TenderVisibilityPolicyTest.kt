package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderStatus
import kotlin.uuid.Uuid

private fun viewer(
    role: AccountRole = AccountRole.MEMBER,
    status: MemberStatus = MemberStatus.ACTIVE,
    hasBid: Boolean = false,
) = TenderViewer(memberId = Uuid.random(), role = role, status = status, hasBid = hasBid)

private val DEADLINE = LocalDateTime(2026, 11, 3, 11, 0)
private val BEFORE = LocalDateTime(2026, 11, 3, 10, 59, 59)
private val AFTER = LocalDateTime(2026, 11, 3, 11, 0, 1)

/**
 * V1.9.201 -- the whole decision space of the pure visibility policy, as the matrix in `docs/architecture/tenders.adoc` states it. A manager view
 * is BOARD/ADMIN/TREASURER WITHOUT a bid of their own; a bidding board member is treated like any bidder.
 */
class TenderVisibilityPolicyTest :
    FunSpec({
        val managers = listOf(AccountRole.BOARD, AccountRole.ADMIN, AccountRole.TREASURER)

        context("OPEN: everybody who may see the tender sees all bids and the counter, before and after the deadline") {
            for (revealed in listOf(false, true)) {
                test("revealed=$revealed") {
                    listOf(
                        viewer(hasBid = true),
                        viewer(hasBid = false),
                        viewer(role = AccountRole.BOARD),
                        viewer(role = AccountRole.BOARD, hasBid = true),
                        viewer(status = MemberStatus.GUEST),
                        viewer(status = MemberStatus.FRIEND),
                    ).forEach {
                        bidViewFor(viewer = it, visibility = TenderBidVisibility.OPEN, revealed = revealed) shouldBe BidView.ALL
                        bidCountVisible(viewer = it, visibility = TenderBidVisibility.OPEN, revealed = revealed) shouldBe true
                    }
                }
            }
        }

        context("HIDDEN_UNTIL_DEADLINE") {
            test("before the deadline nobody sees a foreign bid or the counter; a bidder sees the own bid") {
                bidViewFor(
                    viewer = viewer(hasBid = true),
                    visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                    revealed = false,
                ) shouldBe
                    BidView.OWN
                bidViewFor(
                    viewer = viewer(role = AccountRole.BOARD, hasBid = true),
                    visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                    revealed = false,
                ) shouldBe
                    BidView.OWN
                managers.forEach {
                    bidViewFor(
                        viewer = viewer(role = it),
                        visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                        revealed = false,
                    ) shouldBe
                        BidView.NONE
                }
                bidViewFor(viewer = viewer(), visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, revealed = false) shouldBe
                    BidView.NONE
                listOf(viewer(hasBid = true), viewer(role = AccountRole.ADMIN), viewer()).forEach {
                    bidCountVisible(viewer = it, visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, revealed = false) shouldBe false
                }
            }
            test("after the deadline bidders and the manager view see everything and the counter; a non-bidder sees nothing") {
                bidViewFor(viewer = viewer(hasBid = true), visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, revealed = true) shouldBe
                    BidView.ALL
                bidViewFor(
                    viewer = viewer(role = AccountRole.BOARD, hasBid = true),
                    visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                    revealed = true,
                ) shouldBe
                    BidView.ALL
                managers.forEach {
                    bidViewFor(viewer = viewer(role = it), visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, revealed = true) shouldBe
                        BidView.ALL
                }
                bidViewFor(viewer = viewer(), visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, revealed = true) shouldBe BidView.NONE
                bidViewFor(
                    viewer = viewer(status = MemberStatus.GUEST),
                    visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                    revealed = true,
                ) shouldBe
                    BidView.NONE
                bidCountVisible(
                    viewer = viewer(hasBid = true),
                    visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE,
                    revealed = true,
                ) shouldBe
                    true
                bidCountVisible(viewer = viewer(), visibility = TenderBidVisibility.HIDDEN_UNTIL_DEADLINE, revealed = true) shouldBe false
            }
        }

        context("OWN_ONLY") {
            test("a bidder (a bidding board member too) sees only the own bid, before and after, and never a counter") {
                for (revealed in listOf(false, true)) {
                    listOf(
                        viewer(hasBid = true),
                        viewer(role = AccountRole.BOARD, hasBid = true),
                        viewer(role = AccountRole.ADMIN, hasBid = true),
                    ).forEach {
                        bidViewFor(viewer = it, visibility = TenderBidVisibility.OWN_ONLY, revealed = revealed) shouldBe BidView.OWN
                        bidCountVisible(viewer = it, visibility = TenderBidVisibility.OWN_ONLY, revealed = revealed) shouldBe false
                    }
                }
            }
            test("the manager view sees nothing before the deadline and everything after it") {
                managers.forEach {
                    bidViewFor(viewer = viewer(role = it), visibility = TenderBidVisibility.OWN_ONLY, revealed = false) shouldBe
                        BidView.NONE
                    bidViewFor(viewer = viewer(role = it), visibility = TenderBidVisibility.OWN_ONLY, revealed = true) shouldBe BidView.ALL
                    bidCountVisible(viewer = viewer(role = it), visibility = TenderBidVisibility.OWN_ONLY, revealed = true) shouldBe true
                    bidCountVisible(viewer = viewer(role = it), visibility = TenderBidVisibility.OWN_ONLY, revealed = false) shouldBe false
                }
            }
            test("a plain non-bidding participant never sees a foreign bid") {
                for (revealed in listOf(false, true)) {
                    listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST).forEach {
                        bidViewFor(viewer = viewer(status = it), visibility = TenderBidVisibility.OWN_ONLY, revealed = revealed) shouldBe
                            BidView.NONE
                    }
                }
            }
        }

        context("who knows that a tender exists") {
            val everyStatus = TenderStatus.entries
            test("a draft and a tender cancelled before its publication are visible to the board read view only") {
                for (internal in listOf(TenderStatus.DRAFT to true, TenderStatus.CANCELLED to false)) {
                    val (status, ever) = internal
                    managers.forEach { tenderVisibleTo(viewer = viewer(role = it), status = status, everPublished = ever) shouldBe true }
                    tenderVisibleTo(viewer = viewer(), status = status, everPublished = ever) shouldBe false
                    tenderVisibleTo(viewer = viewer(status = MemberStatus.GUEST), status = status, everPublished = ever) shouldBe false
                    tenderVisibleTo(viewer = viewer(status = MemberStatus.FRIEND), status = status, everPublished = ever) shouldBe false
                }
            }
            test("a published tender is visible to ACTIVE, FRIEND and GUEST, in every status after the draft") {
                everyStatus.filter { it != TenderStatus.DRAFT }.forEach { status ->
                    listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST).forEach {
                        tenderVisibleTo(viewer = viewer(status = it), status = status, everPublished = true) shouldBe true
                    }
                }
            }
            test("a status outside the participant set sees nothing") {
                listOf(
                    MemberStatus.APPLICATION,
                    MemberStatus.WITHDRAWN,
                    MemberStatus.REJECTED,
                    MemberStatus.DONOR,
                    MemberStatus.DECEASED,
                ).forEach {
                    everyStatus.forEach { status ->
                        tenderVisibleTo(viewer = viewer(status = it), status = status, everPublished = true) shouldBe
                            false
                    }
                }
            }
        }

        context("the effective status and the reveal moment") {
            test("a PUBLISHED tender reads CLOSED exactly from the deadline on, every other stored status is unchanged") {
                effectiveTenderStatus(stored = TenderStatus.PUBLISHED, deadline = DEADLINE, wallNow = BEFORE) shouldBe
                    TenderStatus.PUBLISHED
                effectiveTenderStatus(stored = TenderStatus.PUBLISHED, deadline = DEADLINE, wallNow = DEADLINE) shouldBe TenderStatus.CLOSED
                effectiveTenderStatus(stored = TenderStatus.DRAFT, deadline = DEADLINE, wallNow = AFTER) shouldBe TenderStatus.DRAFT
                effectiveTenderStatus(stored = TenderStatus.AWARDED, deadline = DEADLINE, wallNow = AFTER) shouldBe TenderStatus.AWARDED
            }
            test("revealed: the deadline reached and not cancelled before it") {
                tenderBidsRevealed(deadline = DEADLINE, wallNow = BEFORE, cancelledAtWall = null) shouldBe false
                tenderBidsRevealed(deadline = DEADLINE, wallNow = DEADLINE, cancelledAtWall = null) shouldBe true
                tenderBidsRevealed(deadline = DEADLINE, wallNow = AFTER, cancelledAtWall = BEFORE) shouldBe false
                tenderBidsRevealed(deadline = DEADLINE, wallNow = AFTER, cancelledAtWall = DEADLINE) shouldBe true
                tenderBidsRevealed(deadline = DEADLINE, wallNow = AFTER, cancelledAtWall = AFTER) shouldBe true
            }
        }
    })
