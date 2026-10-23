package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.math.BigDecimal

/** Pure checks of the static LTR plan of the staging top-up (V1.9.197); no database. */
class StagingSeedLtrPlanTest :
    FunSpec({
        val mints = StagingSeedLtr.mints
        val transfers = StagingSeedLtr.transfers
        val targets = StagingSeedLtr.targets

        fun net(member: Int): BigDecimal =
            mints.filter { it.member == member }.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount } +
                transfers.filter { it.recipient == member }.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount } -
                transfers.filter { it.sender == member }.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }

        test("the net of every planned member equals its documented target") {
            StagingSeedLtr.plannedMembers() shouldBe targets.keys
            targets.forEach { (member, target) -> net(member).compareTo(target) shouldBe 0 }
        }

        test("the named members are pinned") {
            fun t(n: Int) = targets.getValue(n).toPlainString()
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 13).map { t(it) } shouldBe
                listOf("500.00", "250.00", "120.00", "60.00", "25.00", "12.50", "5.00", "0.05", "20.00")
            targets.filterKeys { it !in setOf(1, 2, 3, 4, 5, 6, 7, 8, 13) }.values.forEach {
                it shouldBeGreaterThanOrEqualTo BigDecimal("10")
                (it <= BigDecimal("80")) shouldBe true
            }
        }

        test("amounts have scale <= 2 and are at least 0.01; texts start with [SEED] and fit the column") {
            (mints.map { it.amount } + transfers.map { it.amount }).forEach {
                (it.scale() <= 2) shouldBe true
                (it >= BigDecimal("0.01")) shouldBe true
            }
            (mints.map { it.note } + transfers.map { it.purpose }).forEach {
                it shouldStartWith "[SEED]"
                it.length shouldBeLessThanOrEqual 500
            }
        }

        test("every member has at least two entries; daysAgo is positive") {
            StagingSeedLtr.plannedMembers().forEach { member ->
                val count = mints.count { it.member == member } + transfers.count { it.sender == member || it.recipient == member }
                count shouldBeGreaterThanOrEqual 2
            }
            (mints.map { it.daysAgo } + transfers.map { it.daysAgo }).forEach { it shouldBeGreaterThanOrEqual 1 }
        }

        test("derived ids are unique, in namespace 6 and disjoint from the other seed namespaces") {
            val ids =
                mints.map { SeedIds.ltr(it.index) } +
                    transfers.flatMap {
                        listOf(
                            SeedIds.ltr(0x1000 + it.index),
                            SeedIds.ltr(0x2000 + it.index),
                            SeedIds.ltr(0x3000 + it.index),
                        )
                    }
            ids.toSet().size shouldBe ids.size
            ids.forEach { it.toString() shouldStartWith "0000000a-0000-0000-0000-6" }
            val others =
                (0..0x60).flatMap {
                    listOf(
                        SeedIds.member(it),
                        SeedIds.finance(it),
                        SeedIds.governance(it),
                        SeedIds.community(it),
                        SeedIds.base(it),
                    )
                }
            ids.toSet().intersect(others.toSet()).size shouldBe 0
            mints.map { it.index }.max() shouldBeLessThanOrEqual 0x0fff
            ids.take(2) shouldContainAll listOf(SeedIds.ltr(0), SeedIds.ltr(1))
        }

        test("sender differs from recipient; outgoing transfers are covered by the sender's own earlier mints") {
            transfers.forEach { it.sender shouldBe it.sender.takeIf { s -> s != it.recipient } }
            transfers.forEach { t ->
                val covered =
                    mints.filter { it.member == t.sender && it.daysAgo > t.daysAgo }.fold(
                        BigDecimal.ZERO,
                    ) { a, m -> a + m.amount }
                val outgoingUpToNow =
                    transfers.filter { it.sender == t.sender && it.daysAgo >= t.daysAgo }.fold(BigDecimal.ZERO) { a, o ->
                        a +
                            o.amount
                    }
                (covered >= outgoingUpToNow) shouldBe true
            }
        }
    })
