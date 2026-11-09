package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.membermap.PostalCodeCentroidIndex
import network.lapis.cloud.server.rpc.AuctionBidView
import network.lapis.cloud.server.rpc.CrowdfundingWeightDecay
import network.lapis.cloud.server.rpc.computeAuctionOutcome
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismLimits
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.FamilyMembershipRules
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberHonorLimits
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import java.math.BigDecimal

/**
 * Pure checks of the V1.9.205 plans (no database): the whole LTR history (V1.9.197 plan plus every part) never lets a balance drop below zero at
 * any point in time, the end balances are pinned, the domain rules of the plans hold, ids are unique and disjoint, plans only grow at the end, and
 * no plan text can carry real data.
 */
class StagingSeedPartsPlanTest :
    FunSpec({
        val clock = SeedClock(now = LocalDateTime(2026, 10, 24, 12, 30), orgZone = TimeZone.of("Europe/Berlin"))

        fun ltrRows(): List<SeedLedgerRow> {
            val mints =
                StagingSeedLtr.mints.map {
                    SeedLedgerRow(
                        id = SeedIds.ltr(it.index),
                        member = it.member,
                        type = LtrLedgerEntryType.MINT,
                        amount = it.amount,
                        referenceType = null,
                        referenceId = null,
                        note = it.note,
                        at = clock.utcAt(daysAgo = it.daysAgo, hour = 10),
                    )
                }
            val transfers =
                StagingSeedLtr.transfers.flatMap {
                    val at = clock.utcAt(daysAgo = it.daysAgo, hour = 10)
                    listOf(
                        SeedLedgerRow(
                            id = SeedIds.ltr(0x2000 + it.index),
                            member = it.sender,
                            type = LtrLedgerEntryType.PEER_TRANSFER_OUT,
                            amount = it.amount.negate(),
                            referenceType = LtrLedgerReferenceType.PEER_TRANSFER,
                            referenceId = SeedIds.ltr(0x1000 + it.index),
                            note = it.purpose,
                            at = at,
                        ),
                        SeedLedgerRow(
                            id = SeedIds.ltr(0x3000 + it.index),
                            member = it.recipient,
                            type = LtrLedgerEntryType.PEER_TRANSFER_IN,
                            amount = it.amount,
                            referenceType = LtrLedgerReferenceType.PEER_TRANSFER,
                            referenceId = SeedIds.ltr(0x1000 + it.index),
                            note = it.purpose,
                            at = at,
                        ),
                    )
                }
            return mints + transfers
        }

        val partRows: List<SeedLedgerRow> =
            StagingSeedCrowdfunding.ledgerPlan(clock) +
                StagingSeedAuctions.ledgerPlan(clock) +
                StagingSeedSocial.ledgerPlan(clock)
        val allRows = ltrRows() + partRows

        test("PARTS: names and order are pinned, governance runs before the tenders") {
            StagingSeedTopUp.PARTS.map { it.name } shouldBe
                listOf(
                    "crowdfunding",
                    "auctions",
                    "social",
                    "politicians",
                    "governance",
                    "tenders",
                    "events",
                    "oracle",
                    "member-details",
                    "families",
                    "activism",
                    "honors",
                )
        }

        test("the running balance of every member is never negative, at any point of the history (LTR plan plus all parts)") {
            val balance = mutableMapOf<Int, BigDecimal>()
            val negatives = mutableListOf<String>()
            allRows.sortedBy { it.at }.forEach { row ->
                val next = (balance[row.member] ?: BigDecimal.ZERO) + row.amount
                balance[row.member] = next
                if (next < BigDecimal.ZERO) negatives += "member ${row.member} at ${row.at}: $next after ${row.type}"
            }
            negatives.shouldBeEmpty()
        }

        test("every member with a part ledger row is a member of the V1.9.197 plan (so the balance exists before the part runs)") {
            (partRows.map { it.member }.toSet() - StagingSeedLtr.plannedMembers()).shouldBeEmpty()
        }

        test("end balances (LTR plan plus parts) are pinned") {
            val end = allRows.groupBy { it.member }.mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount } }
            val actual = end.toSortedMap().mapValues { it.value.toPlainString() }
            actual shouldBe EXPECTED_END_BALANCES
        }

        test("ledger rows: unique ids, scale two, [SEED] notes that fit the column, never in the future") {
            partRows.map { it.id }.toSet().size shouldBe partRows.size
            partRows.forEach {
                (it.amount.scale() <= 2) shouldBe true
                it.note shouldStartWith "[SEED]"
                (it.note.length <= 500) shouldBe true
                (it.at <= clock.now) shouldBe true
                (it.amount.signum() != 0) shouldBe true
            }
        }

        test(
            "crowdfunding: the weights satisfy the entry hurdle at every submission, statuses are consistent, reactions only on approved projects",
        ) {
            val projects = StagingSeedCrowdfunding.projects
            projects.forEach { project ->
                val submitted = StagingSeedCrowdfunding.submittedStamp(project = project, clock = clock)
                val earlier =
                    projects.filter {
                        val before = StagingSeedCrowdfunding.submittedStamp(project = it, clock = clock) < submitted
                        val rejectedByThen =
                            it.status == CrowdfundingProjectStatus.REJECTED &&
                                clock.utcAt(daysAgo = it.reviewedDaysAgo!!, hour = 14) <= submitted
                        before && !rejectedByThen
                    }
                val top =
                    earlier.maxOfOrNull {
                        CrowdfundingWeightDecay.currentWeight(
                            initialWeightLtr = it.weight,
                            submittedAt = StagingSeedCrowdfunding.submittedStamp(project = it, clock = clock),
                            now = submitted,
                        )
                    }
                val hurdle = maxOf(BigDecimal("1.00"), top ?: BigDecimal.ZERO)
                (project.weight >= hurdle) shouldBe true
                (project.weight.scale() <= 2) shouldBe true
                if (project.status != CrowdfundingProjectStatus.PENDING) {
                    (project.reviewer != null && project.reviewedDaysAgo != null) shouldBe true
                    (project.reviewedDaysAgo!! < project.submittedDaysAgo) shouldBe true
                } else {
                    (project.reviewer == null) shouldBe true
                }
                (project.status == CrowdfundingProjectStatus.REJECTED) shouldBe (project.rejectionReason != null)
            }
            // the states the model knows are all present, and the silent one is auto-approved while the fresh one is not
            projects.map { it.status }.toSet() shouldBe
                setOf(CrowdfundingProjectStatus.PENDING, CrowdfundingProjectStatus.APPROVED, CrowdfundingProjectStatus.REJECTED)
            val pending = projects.filter { it.status == CrowdfundingProjectStatus.PENDING }
            pending.map {
                CrowdfundingWeightDecay.isAutoApproved(
                    submittedAt = StagingSeedCrowdfunding.submittedStamp(project = it, clock = clock),
                    now = clock.now,
                )
            } shouldBe
                listOf(true, false)
            StagingSeedCrowdfunding.reactions.forEach { reaction ->
                val project = projects.first { it.index == reaction.project }
                val approvedAt =
                    when (project.status) {
                        CrowdfundingProjectStatus.APPROVED -> clock.utcAt(daysAgo = project.reviewedDaysAgo!!, hour = 14)
                        CrowdfundingProjectStatus.PENDING ->
                            clock.utcShift(base = StagingSeedCrowdfunding.submittedStamp(project = project, clock = clock), byDays = 14)
                        CrowdfundingProjectStatus.REJECTED -> error("a reaction on a rejected project")
                    }
                (clock.utcAt(daysAgo = reaction.daysAgo, hour = 16) > approvedAt) shouldBe true
            }
            StagingSeedCrowdfunding.reactions
                .map { it.project to it.member }
                .toSet()
                .size shouldBe StagingSeedCrowdfunding.reactions.size
        }

        test("auctions: outcomes equal the live computation, seller is no bidder, every bid reaches the starting bid, holds net out") {
            StagingSeedAuctions.auctions.forEach { auction ->
                val derived = StagingSeedAuctions.derive(auction = auction, clock = clock)
                val start = BigDecimal(auction.startingBid)
                (auction.bids.none { it.bidder == auction.seller }) shouldBe true
                auction.bids.forEach { (BigDecimal(it.max) >= start) shouldBe true }
                auction.bids
                    .map { it.bidder }
                    .toSet()
                    .size shouldBe auction.bids.size
                val views =
                    derived.bids.map {
                        AuctionBidView(
                            bidId = it.id,
                            bidderMemberId = SeedIds.member(it.bidder),
                            maxBidLtr = it.max,
                            createdAt = it.createdAt,
                        )
                    }
                val outcome = computeAuctionOutcome(startingBidLtr = start, minIncrementLtr = BigDecimal("0.01"), bids = views)
                val holds =
                    derived.ledger
                        .filter { it.type == LtrLedgerEntryType.AUCTION_HOLD || it.type == LtrLedgerEntryType.AUCTION_HOLD_RELEASE }
                        .fold(BigDecimal.ZERO) { a, r -> a + r.amount }
                when (derived.status) {
                    AuctionStatus.OPEN -> holds.negate().compareTo(outcome.leaderMaxBidLtr ?: BigDecimal.ZERO) shouldBe 0
                    else -> holds.compareTo(BigDecimal.ZERO) shouldBe 0
                }
                if (derived.status == AuctionStatus.SETTLED && auction.buyNowPrice == null) {
                    derived.finalPrice!!.compareTo(outcome.currentPriceLtr) shouldBe 0
                    SeedIds.member(derived.winner!!) shouldBe outcome.leaderMemberId
                }
                val sales =
                    derived.ledger.filter {
                        it.type == LtrLedgerEntryType.AUCTION_SALE_OUT || it.type == LtrLedgerEntryType.AUCTION_SALE_IN
                    }
                sales.fold(BigDecimal.ZERO) { a, r -> a + r.amount }.compareTo(BigDecimal.ZERO) shouldBe 0
                (derived.winner != auction.seller) shouldBe true
            }
            val byIndex = StagingSeedAuctions.auctions.associate { it.index to StagingSeedAuctions.derive(auction = it, clock = clock) }
            byIndex.getValue(1).status shouldBe AuctionStatus.OPEN
            byIndex.getValue(2).run { (status to finalPrice?.toPlainString()) shouldBe (AuctionStatus.SETTLED to "22.01") }
            byIndex.getValue(2).winner shouldBe 3
            byIndex.getValue(3).status shouldBe AuctionStatus.CLOSED_NO_SALE
            byIndex.getValue(4).run { (winner to finalPrice?.toPlainString()) shouldBe (5 to "15.00") }
            byIndex.getValue(5).status shouldBe AuctionStatus.OPEN
            byIndex.getValue(5).bids.size shouldBe 1
        }

        test(
            "social: comments take the root's visibility, parents are visible, a friend never posts for members only, boosts follow the post",
        ) {
            val posts = StagingSeedSocial.posts
            val byIndex = posts.associateBy { it.index }
            posts.forEach { post ->
                (post.weight >= BigDecimal("0.01")) shouldBe true
                (post.content.length <= 5000) shouldBe true
                if (post.parent != null) {
                    val parent = byIndex.getValue(post.parent)
                    parent.state shouldBe SocialPostState.VISIBLE
                    post.depth shouldBe parent.depth + 1
                    post.visibility shouldBe byIndex.getValue(post.root).visibility
                    (post.daysAgo < parent.daysAgo) shouldBe true
                }
                if (post.author == 13) (post.visibility != SocialPostVisibility.MEMBERS_ONLY) shouldBe true
                (post.state == SocialPostState.REMOVED_LEGAL) shouldBe (post.stateReason != null)
            }
            posts.count { it.state == SocialPostState.REMOVED_LEGAL } shouldBe 1
            posts.count { it.state == SocialPostState.HIDDEN_BY_AUTHOR } shouldBe 1
            posts.count { it.parent == null } shouldBe 9
            posts.count { it.parent != null } shouldBe 3
            StagingSeedSocial.boosts.size shouldBe 4
            StagingSeedSocial.boosts.forEach { boost ->
                val post = byIndex.getValue(boost.post)
                post.state shouldBe SocialPostState.VISIBLE
                (
                    StagingSeedSocial.boostStamp(
                        boost = boost,
                        clock = clock,
                    ) > StagingSeedSocial.publishedStamp(post = post, clock = clock)
                ) shouldBe
                    true
            }
        }

        test("events: capacity, waiting list and registration keys are consistent") {
            val events = StagingSeedEvents.events
            events.map { it.slug }.toSet().size shouldBe events.size
            events.forEach { event ->
                val confirmed = event.registrations.count { it.status == EventRegistrationStatus.CONFIRMED }
                val waitlisted = event.registrations.filter { it.status == EventRegistrationStatus.WAITLISTED }
                if (event.capacity != null) (confirmed <= event.capacity) shouldBe true
                if (waitlisted.isNotEmpty()) confirmed shouldBe event.capacity
                waitlisted.map { it.waitlistPosition } shouldBe (1..waitlisted.size).toList()
                event.registrations
                    .mapNotNull { it.member }
                    .toSet()
                    .size shouldBe event.registrations.count { it.member != null }
                event.registrations.forEach { reg ->
                    ((reg.member == null) != (reg.guestIndex == null)) shouldBe true
                    (reg.status == EventRegistrationStatus.CANCELLED) shouldBe (reg.cancelledDaysAgo != null)
                }
                (event.location != null || event.onlineUrl != null) shouldBe true
            }
            events.first { it.index == 2 }.run { (registrations.size to capacity) shouldBe (11 to 12) }
            events.first { it.index == 5 }.registrationClosesOffsetDays shouldBe -1
        }

        test("oracle: three series, unique instants per anchor, before now, deterministic") {
            val series = StagingSeedOracle.series(clock)
            series.count { it.anchor.name == "BITCOIN_BTC" } shouldBe 2160
            series.count { it.anchor.name == "GOLD_XAU" } shouldBe 180
            series.count { it.anchor.name == "FIAT" } shouldBe 90
            series.map { it.id }.toSet().size shouldBe series.size
            series.map { it.anchor to it.priceTimestamp }.toSet().size shouldBe series.size
            series.forEach {
                (it.priceTimestamp < clock.now) shouldBe true
                (it.capturedAt <= clock.now) shouldBe true
                it.sourcesUsed shouldStartWith "[SEED] "
                it.price.signum() shouldBe 1
            }
            StagingSeedOracle.series(clock).map { it.price } shouldBe series.map { it.price }
        }

        test("ids: unique over all parts, in the part namespaces, disjoint from the earlier namespaces; plans only grow at the end") {
            val partIds =
                StagingSeedCrowdfunding.projects.map { it.id } +
                    StagingSeedCrowdfunding.reactions.map { SeedIds.crowdfunding(0x2000 + it.index) } +
                    StagingSeedAuctions.auctions.map { it.id } + StagingSeedSocial.posts.map { it.id } +
                    StagingSeedSocial.boosts.map { SeedIds.social(0x2000 + it.index) } + StagingSeedPoliticians.profiles.map { it.id } +
                    StagingSeedPoliticians.reactions.map { SeedIds.politicians(0x2000 + it.index) } +
                    StagingSeedTenders.tenders.flatMap { t -> listOf(t.id) + t.bids.map { t.bidId(it.slot) } } +
                    StagingSeedEvents.events.map { it.id } + partRows.map { it.id } +
                    StagingSeedOracle.series(clock).map { it.id } +
                    StagingSeedActivism.actions.flatMap { a ->
                        listOf(a.id) + a.tasks.map { a.taskId(it.slot) } + a.textBlocks.indices.map { a.textBlockId(it + 1) } +
                            a.contributions.map { a.contributionId(it.slot) }
                    } +
                    StagingSeedFamilies.families.flatMap { f -> listOf(f.id) + f.members.indices.map { f.linkId(it) } } +
                    StagingSeedHonors.honors.map { it.id } +
                    listOf(
                        StagingSeedGovernanceTopUp.bauausschuss,
                        StagingSeedGovernanceTopUp.arbeitskreis,
                        StagingSeedGovernanceTopUp.motion1,
                        StagingSeedGovernanceTopUp.motion2,
                        StagingSeedGovernanceTopUp.motion3,
                        StagingSeedGovernanceTopUp.motion4,
                        StagingSeedGovernanceTopUp.motion5,
                    )
            partIds.toSet().size shouldBe partIds.size
            partIds.forEach { id ->
                id.toString().substring(0, 24) shouldBe "0000000a-0000-0000-0000-"
                (id.toString().substring(24, 26).toInt(16) in 0x70..0x7a) shouldBe true
            }
            val earlier =
                (0..0x60).flatMap {
                    listOf(SeedIds.member(it), SeedIds.finance(it), SeedIds.governance(it), SeedIds.community(it), SeedIds.base(it))
                } + (0..0x3fff).map { SeedIds.ltr(it) }
            partIds.toSet().intersect(earlier.toSet()).shouldBeEmpty()

            StagingSeedCrowdfunding.projects.map { it.index } shouldBe listOf(1, 2, 3, 4, 5)
            StagingSeedCrowdfunding.reactions.map { it.index } shouldBe (1..12).toList()
            StagingSeedAuctions.auctions.map { it.index } shouldBe listOf(1, 2, 3, 4, 5)
            StagingSeedSocial.posts.map { it.index } shouldBe listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13)
            StagingSeedSocial.boosts.map { it.index } shouldBe listOf(1, 2, 3, 4)
            StagingSeedPoliticians.profiles.map { it.index } shouldBe listOf(1, 2, 3, 4, 5)
            StagingSeedPoliticians.reactions.map { it.index } shouldBe (1..10).toList()
            StagingSeedTenders.tenders.map { it.index } shouldBe listOf(1, 2, 3, 4)
            StagingSeedEvents.events.map { it.index } shouldBe listOf(1, 2, 3, 4, 5)
            StagingSeedActivism.actions.map { it.index } shouldBe listOf(1, 2, 3, 4, 5)
            StagingSeedFamilies.families.map { it.index } shouldBe listOf(1, 2, 3)
            StagingSeedHonors.honors.map { it.index } shouldBe listOf(1, 2, 3, 4)
            StagingSeedMemberDetails.plan.map { it.member } shouldBe
                listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 19, 20, 21, 23, 24, 27, 28, 29, 30, 32, 33, 34, 35, 37, 38, 39, 40)
        }

        test("activism: every started action has a form, times and quantities are valid, results only on completed actions") {
            val actions = StagingSeedActivism.actions
            actions.map { it.status }.toSet() shouldBe
                setOf(
                    ActivismStatus.IDEA,
                    ActivismStatus.PLANNED,
                    ActivismStatus.RUNNING,
                    ActivismStatus.COMPLETED,
                    ActivismStatus.WITHDRAWN,
                )
            actions.forEach { a ->
                if (a.status != ActivismStatus.IDEA) (a.formAppointment || a.formProgress || a.formSignal) shouldBe true
                if (a.startsInDays != null && a.endsInDays != null) {
                    (
                        clock.wallAt(daysFromToday = a.endsInDays, hour = a.endHour!!) >=
                            clock.wallAt(daysFromToday = a.startsInDays, hour = a.startHour!!)
                    ) shouldBe true
                }
                a.goalQuantity?.let { (it in ActivismLimits.GOAL_QUANTITY_MIN..ActivismLimits.GOAL_QUANTITY_MAX) shouldBe true }
                (a.title.length <= ActivismLimits.TITLE_MAX) shouldBe true
                (a.description.length <= ActivismLimits.DESCRIPTION_MAX) shouldBe true
                (a.responsibles.size in ActivismLimits.RESPONSIBLES_MIN..ActivismLimits.RESPONSIBLES_MAX) shouldBe true
                (a.tasks.size <= ActivismLimits.TASKS_MAX) shouldBe true
                (a.tasks.all { it.text.length <= ActivismLimits.TASK_TEXT_MAX }) shouldBe true
                if (a.confirmContributions) a.contributions.isNotEmpty() shouldBe true
                a.contributions.forEach {
                    (it.quantity in ActivismLimits.CONTRIBUTION_QUANTITY_MIN..ActivismLimits.CONTRIBUTION_QUANTITY_MAX) shouldBe true
                    (it.reportedDaysAgo <= a.createdDaysAgo) shouldBe true
                    // a decided contribution needs an action that confirms
                    if (it.status != ActivismContributionStatus.REPORTED) a.confirmContributions shouldBe true
                    (it.status == ActivismContributionStatus.REJECTED) shouldBe (it.rejectReason != null)
                }
                a.tasks
                    .map { it.slot }
                    .toSet()
                    .size shouldBe a.tasks.size
                a.contributions
                    .map { it.member }
                    .toSet()
                    .size shouldBe a.contributions.size
                (a.result != null) shouldBe (a.status == ActivismStatus.COMPLETED)
                (a.withdrawnReason != null) shouldBe (a.status == ActivismStatus.WITHDRAWN)
                a.withdrawnReason?.let { (it.length >= ActivismLimits.WITHDRAW_REASON_MIN) shouldBe true }
                (a.contributions.size <= 15) shouldBe true // far below the 24 hour cap per member and action
                a.signals.toSet().size shouldBe a.signals.size
                a.result?.let { r ->
                    r.quantity shouldBe a.contributions.filter { it.status == ActivismContributionStatus.CONFIRMED }.sumOf { it.quantity }
                    (r.quantity <= ActivismLimits.RESULT_NUMBER_MAX) shouldBe true
                }
            }
            // the visible mix: the info stand shows a signal count (>= the display threshold), the running action has all three contribution states
            actions.first { it.index == 1 }.signals.size shouldBe 6
            (actions.first { it.index == 1 }.signals.size >= ActivismLimits.SIGNAL_MIN_DISPLAY) shouldBe true
            actions
                .first { it.index == 2 }
                .contributions
                .map { it.status }
                .toSet() shouldBe ActivismContributionStatus.entries.toSet()
        }

        test("families: one payer each, no member in two families, no management login as payer, a minor depends on the last family") {
            val families = StagingSeedFamilies.families
            val everybody = families.flatMap { it.members }
            everybody.toSet().size shouldBe everybody.size
            families.forEach { f ->
                f.dependents.isNotEmpty() shouldBe true
                (f.payer in 1..3) shouldBe false
                f.dependents.contains(f.payer) shouldBe false
                (f.name.length <= 200) shouldBe true
            }
            families.map { it.id }.toSet().size shouldBe families.size
        }

        test(
            "member details: planned postal codes resolve in the bundled geodata, birth dates are sane, the minor turns 18 inside the window",
        ) {
            val index = PostalCodeCentroidIndex.bundled
            (index != null) shouldBe true
            val plan = StagingSeedMemberDetails.plan
            plan.map { it.member }.toSet().size shouldBe plan.size
            plan.mapNotNull { it.postalCode }.forEach { (index!!.lookup(it) != null) shouldBe true }
            plan.count { it.postalCode == null } shouldBe 2 // members 9 and 40 get no postal code on purpose (25 is not planned at all)
            plan.mapNotNull { it.birthOffset }.forEach { offset ->
                val dob = StagingSeedMemberDetails.dateOfBirth(clock = clock, offset = offset)
                (dob < clock.today) shouldBe true
                (dob.month == Month.FEBRUARY && dob.day == 29) shouldBe false
            }
            // 3 or 4 birthdays inside the next 14 days
            val soon =
                plan.mapNotNull { it.birthOffset }.filter {
                    AnniversaryCalendar.nextOccurrence(
                        anniversary = StagingSeedMemberDetails.dateOfBirth(clock = clock, offset = it),
                        today = clock.today,
                    ) <= AnniversaryCalendar.windowEnd(today = clock.today, windowDays = 14)
                }
            (soon.size in 3..5) shouldBe true
            val minor =
                plan
                    .first {
                        it.member ==
                            StagingSeedFamilies.families
                                .last()
                                .dependents
                                .single()
                    }.birthOffset!!
            val dob = StagingSeedMemberDetails.dateOfBirth(clock = clock, offset = minor)
            val majority = AnniversaryCalendar.nthAnniversary(anniversary = dob, years = FamilyMembershipRules.MAJORITY_AGE_YEARS)
            (majority > clock.today) shouldBe true
            (majority <= AnniversaryCalendar.windowEnd(today = clock.today, windowDays = 30)) shouldBe true
        }

        test("honors: past award dates, texts fit the columns, one honor per category") {
            val honors = StagingSeedHonors.honors
            honors.map { it.category }.toSet().size shouldBe 4
            honors.forEach {
                (it.awardedDaysAgo > 0) shouldBe true
                (it.title.length <= MemberHonorLimits.TITLE_MAX_LENGTH) shouldBe true
                (it.awardedBy.length <= MemberHonorLimits.AWARDED_BY_MAX_LENGTH) shouldBe true
                (it.note.length <= MemberHonorLimits.NOTE_MAX_LENGTH) shouldBe true
            }
        }

        test("content: no real address, URL, IBAN or credential-like word in any plan text; visible texts say Demodaten") {
            val visible =
                StagingSeedCrowdfunding.projects.flatMap { listOf(it.title, it.description) } +
                    StagingSeedAuctions.auctions.flatMap { listOf(it.title, it.description) } +
                    StagingSeedSocial.posts.map { it.content } +
                    StagingSeedPoliticians.profiles.mapNotNull { it.mandate } +
                    StagingSeedTenders.tenders.flatMap { listOf(it.title, it.description) } +
                    StagingSeedEvents.events.flatMap { listOf(it.title, it.description) } +
                    StagingSeedActivism.actions.flatMap { listOf(it.title, it.description) + it.tasks.map { t -> t.text } } +
                    StagingSeedActivism.actions.flatMap { a -> a.textBlocks.flatMap { listOf(it.first, it.second) } } +
                    StagingSeedFamilies.families.map { it.name } +
                    StagingSeedHonors.honors.flatMap { listOf(it.title, it.note) }
            visible.forEach { it shouldContain "Demodaten" }
            val everything =
                visible +
                    partRows.map { it.note } +
                    StagingSeedCrowdfunding.projects.mapNotNull { it.rejectionReason } +
                    StagingSeedSocial.posts.mapNotNull { it.stateReason } +
                    StagingSeedTenders.tenders.flatMap { t -> t.bids.map { it.text } + t.expectedResult } +
                    StagingSeedEvents.events.flatMap { e ->
                        listOfNotNull(e.onlineUrl, e.location) + e.registrations.mapNotNull { it.message } +
                            e.registrations.mapNotNull { it.guestIndex }.map { "gast$it@staging.invalid" }
                    } +
                    StagingSeedOracle.series(clock).map { it.sourcesUsed } +
                    StagingSeedActivism.actions.flatMap { a ->
                        listOfNotNull(a.location, a.unit, a.withdrawnReason, a.result?.text) +
                            a.contributions.flatMap { listOfNotNull(it.area, it.rejectReason) }
                    } +
                    StagingSeedHonors.honors.map { it.awardedBy }
            val address = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+""")
            val url = Regex("""https?://[^\s/]+""")
            val iban = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
            val credential = Regex("""(?i)passwort|password|token|secret""")
            everything.forEach { text ->
                address.findAll(text).forEach { it.value.endsWith("@staging.invalid") shouldBe true }
                url.findAll(text).forEach { it.value.endsWith(".invalid") shouldBe true }
                iban.containsMatchIn(text) shouldBe false
                credential.containsMatchIn(text) shouldBe false
            }
        }
    })

/** Pinned end balances (V1.9.197 targets minus everything the parts spend, plus what they pay out). */
internal val EXPECTED_END_BALANCES: Map<Int, String> =
    mapOf(
        1 to "461.50",
        2 to "235.99",
        3 to "90.98",
        4 to "69.99",
        5 to "8.49",
        6 to "11.50",
        7 to "4.50",
        8 to "0.04",
        9 to "74.00",
        10 to "43.00",
        13 to "19.00",
        19 to "70.00",
        20 to "35.00",
        21 to "54.00",
        23 to "15.00",
        24 to "50.00",
        25 to "25.00",
        27 to "40.00",
        28 to "30.00",
        29 to "65.00",
        30 to "20.00",
        32 to "55.00",
        33 to "10.00",
        34 to "75.00",
        35 to "12.00",
        37 to "18.00",
        38 to "22.00",
        39 to "14.00",
        40 to "10.00",
    )
