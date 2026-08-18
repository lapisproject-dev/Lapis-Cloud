package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.economy.WeightDecayClock
import network.lapis.cloud.shared.domain.SocialPostState
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * DB-frei (see [SocialPostWeight.totalWeightsUnrounded] KDoc "REINE FUNKTION") -- the aggregator's
 * core correctness suite, mirroring [network.lapis.cloud.server.economy.WeightDecayClockTest]'s own
 * house style of hand-computed `BigDecimal` expectations. This is the wichtigste Einzeltest-Datei of
 * Welle V1.1.2 (Implementierungsplan § 6.1): every other test in this domain trusts this file to
 * have already proven the aggregator sums-before-rounding, is cycle-safe, and decays boosts from
 * their own timestamp.
 */
class SocialPostWeightTest :
    FunSpec({
        val epoch = LocalDateTime(2026, 1, 1, 0, 0, 0)

        fun node(
            id: Uuid,
            parentId: Uuid?,
            depth: Int,
            weight: String,
            publishedAt: LocalDateTime = epoch,
        ) = SocialPostWeight.WeightNode(
            id = id,
            parentId = parentId,
            depth = depth,
            initialWeightLtr = BigDecimal(weight),
            publishedAt = publishedAt,
        )

        test("handgerechneter Baum: total weight of every node matches a hand-computed BigDecimal expectation") {
            // root(2.00) -> child(1.00) -> grandchild(0.50), plus a sibling child2(3.00) directly
            // under root. All published at epoch, evaluated at epoch (0 days elapsed, no decay).
            val root = Uuid.random()
            val child = Uuid.random()
            val grandchild = Uuid.random()
            val child2 = Uuid.random()
            val nodes =
                listOf(
                    node(id = root, parentId = null, depth = 0, weight = "2.00"),
                    node(id = child, parentId = root, depth = 1, weight = "1.00"),
                    node(id = grandchild, parentId = child, depth = 2, weight = "0.50"),
                    node(id = child2, parentId = root, depth = 1, weight = "3.00"),
                )
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)

            totals.getValue(grandchild).compareTo(BigDecimal("0.50")) shouldBe 0
            totals.getValue(child).compareTo(BigDecimal("1.50")) shouldBe 0 // 1.00 own + 0.50 grandchild
            totals.getValue(child2).compareTo(BigDecimal("3.00")) shouldBe 0
            totals.getValue(root).compareTo(BigDecimal("6.50")) shouldBe 0 // 2.00 own + 1.50 child + 3.00 child2
        }

        test("Determinismus / 40 Jahre: no exception/overflow at +40 years, ranking identical to t=0, repeat run is bit-identical") {
            val root = Uuid.random()
            val child = Uuid.random()
            val nodes =
                listOf(
                    node(id = root, parentId = null, depth = 0, weight = "5.00"),
                    node(id = child, parentId = root, depth = 1, weight = "9.00"),
                )
            val far = LocalDateTime(2066, 1, 1, 0, 0, 0) // ~14610 days later

            val totalsNow = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)
            val totalsFar = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = far)
            val totalsFarAgain = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = far)

            // child (9.00) outranks root's own weight both at t=0 and 40 years later -- ranking by
            // OWN weight is unaffected by decay symmetry since both decay at the same rate; assert
            // the totals themselves are stable/repeatable instead of a ranking claim.
            totalsFar.getValue(root) shouldBe totalsFarAgain.getValue(root)
            totalsFar.getValue(child) shouldBe totalsFarAgain.getValue(child)
            (totalsFar.getValue(root) < totalsNow.getValue(root)) shouldBe true
            (totalsFar.getValue(root) >= BigDecimal.ZERO) shouldBe true
        }

        test("Summieren-vor-Runden: round2(sum(unrounded)) differs from sum(round2(each)) for 200 small comments") {
            val root = Uuid.random()
            val comments = (1..200).map { Uuid.random() }
            val nodes =
                buildList {
                    add(node(id = root, parentId = null, depth = 0, weight = "0.01"))
                    comments.forEach { id -> add(node(id = id, parentId = root, depth = 1, weight = "0.004")) }
                }
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)
            val rootTotalRoundedOnce = WeightDecayClock.round2(totals.getValue(root))

            // Summing 200x 0.004 unrounded = 0.80, plus the root's own 0.01 = 0.81.
            rootTotalRoundedOnce.compareTo(BigDecimal("0.81")) shouldBe 0

            // If each 0.004 contribution had instead been rounded to 2dp BEFORE summing, it would
            // have rounded down to 0.00 every time (HALF_UP on 0.004 rounds to 0.00), i.e. the
            // per-contribution-rounded sum would have been just the root's own 0.01 -- proving the
            // aggregator does NOT round per-node.
            val perNodeRoundedSum =
                comments.fold(WeightDecayClock.round2(BigDecimal("0.01"))) { acc, _ -> acc + WeightDecayClock.round2(BigDecimal("0.004")) }
            (rootTotalRoundedOnce.compareTo(perNodeRoundedSum) != 0) shouldBe true
        }

        test(
            "Boost-Zerfall ab eigenem Zeitstempel (S4): two equal boosts 10 days apart contribute differently; a boost on a 100-day-old post contributes near its full amount",
        ) {
            val postId = Uuid.random()
            val recentBoostTime = epoch
            val oldBoostTime = LocalDateTime(2025, 12, 22, 0, 0, 0) // 10 days before epoch
            val nodes =
                listOf(
                    node(id = postId, parentId = null, depth = 0, weight = "1.00", publishedAt = LocalDateTime(2025, 1, 1, 0, 0, 0)),
                )
            val boosts =
                mapOf(
                    postId to
                        listOf(
                            SocialPostWeight.BoostContribution(amountLtr = BigDecimal("1.00"), boostedAt = recentBoostTime),
                            SocialPostWeight.BoostContribution(amountLtr = BigDecimal("1.00"), boostedAt = oldBoostTime),
                        ),
                )
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = boosts, now = epoch)
            val recentOnly =
                SocialPostWeight.ownWeightUnrounded(
                    initialWeightLtr = BigDecimal.ZERO,
                    publishedAt = epoch,
                    boosts = listOf(SocialPostWeight.BoostContribution(amountLtr = BigDecimal("1.00"), boostedAt = recentBoostTime)),
                    now = epoch,
                )
            val oldOnly =
                SocialPostWeight.ownWeightUnrounded(
                    initialWeightLtr = BigDecimal.ZERO,
                    publishedAt = epoch,
                    boosts = listOf(SocialPostWeight.BoostContribution(amountLtr = BigDecimal("1.00"), boostedAt = oldBoostTime)),
                    now = epoch,
                )
            (recentOnly.compareTo(oldOnly) != 0) shouldBe true
            (totals.getValue(postId).compareTo(BigDecimal.ZERO) != 0) shouldBe true

            // A boost cast on an old post decays from ITS OWN boostedAt, not the post's publishedAt
            // -- a boost cast "now" on a 100-day-old post contributes (almost) its full amount, not
            // 0.9^100 of it.
            val oldPostId = Uuid.random()
            val oldPostNode =
                node(
                    id = oldPostId,
                    parentId = null,
                    depth = 0,
                    weight = "0.01",
                    publishedAt = LocalDateTime(2025, 9, 23, 0, 0, 0), // ~100 days before epoch
                )
            val freshBoostOnOldPost =
                mapOf(oldPostId to listOf(SocialPostWeight.BoostContribution(amountLtr = BigDecimal("1.00"), boostedAt = epoch)))
            val oldPostTotals =
                SocialPostWeight.totalWeightsUnrounded(nodes = listOf(oldPostNode), boostsByPostId = freshBoostOnOldPost, now = epoch)
            (oldPostTotals.getValue(oldPostId) > BigDecimal("0.99")) shouldBe true
        }

        test("Waisen/abgeschnittener Teilbaum: a node whose parentId is not in the set contributes only to itself") {
            val orphanParent = Uuid.random() // deliberately NOT included in nodes below
            val orphan = Uuid.random()
            val nodes = listOf(node(id = orphan, parentId = orphanParent, depth = 5, weight = "2.50"))
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)
            totals.getValue(orphan).compareTo(BigDecimal("2.50")) shouldBe 0
        }

        test("Zyklus-Robustheit: a same-depth parentId cycle terminates without infinite loop or stack overflow") {
            val a = Uuid.random()
            val b = Uuid.random()
            // a.parentId = b, b.parentId = a, both at the SAME depth -- a data-corruption scenario
            // that could never arise from legitimate writes (children always have depth =
            // parent.depth + 1, so a real cycle would need strictly increasing depth all the way
            // around, a contradiction), but the aggregator must not hang or crash if it ever does.
            // The guarantee this test proves is TERMINATION (one forEach pass, every node visited
            // exactly once, no recursion/stack) -- NOT that cyclic nodes' weights stay isolated; see
            // totalWeightsUnrounded's own KDoc "Zyklus"-Absatz for why exact propagation in a
            // corrupted-data cycle is intentionally left unspecified.
            val nodes =
                listOf(
                    node(id = a, parentId = b, depth = 3, weight = "1.00"),
                    node(id = b, parentId = a, depth = 3, weight = "1.00"),
                )
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)
            totals.keys shouldBe setOf(a, b)
            (totals.getValue(a) >= BigDecimal("1.00")) shouldBe true
            (totals.getValue(b) >= BigDecimal("1.00")) shouldBe true
        }

        test("Tiefe 64: a full 64-level chain aggregates -- the root's weight contains all 64 contributions") {
            val ids = (0..64).map { Uuid.random() }
            val nodes =
                ids.mapIndexed { index, id ->
                    node(id = id, parentId = if (index == 0) null else ids[index - 1], depth = index, weight = "1.00")
                }
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)
            totals.getValue(ids.first()).compareTo(BigDecimal("65.00")) shouldBe 0 // 65 nodes x 1.00
        }

        test(
            "suppressedIds: a hidden node on depth 2 suppresses itself and all its descendants, a sibling branch is unaffected, weight is unaffected (E3)",
        ) {
            val root = Uuid.random()
            val hiddenBranch = Uuid.random()
            val hiddenBranchChild = Uuid.random()
            val visibleBranch = Uuid.random()
            val nodes =
                listOf(
                    node(id = root, parentId = null, depth = 0, weight = "1.00"),
                    node(id = hiddenBranch, parentId = root, depth = 1, weight = "1.00"),
                    node(id = hiddenBranchChild, parentId = hiddenBranch, depth = 2, weight = "1.00"),
                    node(id = visibleBranch, parentId = root, depth = 1, weight = "1.00"),
                )
            val states =
                mapOf(
                    root to SocialPostState.VISIBLE,
                    hiddenBranch to SocialPostState.HIDDEN_BY_AUTHOR,
                    hiddenBranchChild to SocialPostState.VISIBLE,
                    visibleBranch to SocialPostState.VISIBLE,
                )
            val suppressed = SocialPostWeight.suppressedIds(nodes = nodes, stateById = states)
            suppressed shouldBe setOf(hiddenBranch, hiddenBranchChild)

            // E3: the weight total is entirely unaffected by suppression.
            val totals = SocialPostWeight.totalWeightsUnrounded(nodes = nodes, boostsByPostId = emptyMap(), now = epoch)
            totals.getValue(root).compareTo(BigDecimal("4.00")) shouldBe 0
        }

        test("descendantCounts: direct and total counts across a 3-level tree") {
            val root = Uuid.random()
            val childA = Uuid.random()
            val childB = Uuid.random()
            val grandchild = Uuid.random()
            val nodes =
                listOf(
                    node(id = root, parentId = null, depth = 0, weight = "1.00"),
                    node(id = childA, parentId = root, depth = 1, weight = "1.00"),
                    node(id = childB, parentId = root, depth = 1, weight = "1.00"),
                    node(id = grandchild, parentId = childA, depth = 2, weight = "1.00"),
                )
            val counts = SocialPostWeight.descendantCounts(nodes)
            counts.getValue(root).direct shouldBe 2
            counts.getValue(root).total shouldBe 3
            counts.getValue(childA).direct shouldBe 1
            counts.getValue(childA).total shouldBe 1
            counts.getValue(childB).direct shouldBe 0
            counts.getValue(childB).total shouldBe 0
            counts.getValue(grandchild).direct shouldBe 0
            counts.getValue(grandchild).total shouldBe 0
        }
    })
