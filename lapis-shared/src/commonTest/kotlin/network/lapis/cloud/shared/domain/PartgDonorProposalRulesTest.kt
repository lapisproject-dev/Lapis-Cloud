package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.293 (R5b) -- the shared rules of the proposal for statement B: threshold, classification, validation of the picks, counts, origin. */
class PartgDonorProposalRulesTest {
    private val m1 = "M:" + "11111111-1111-4111-8111-111111111111"
    private val m2 = "M:" + "22222222-2222-4222-8222-222222222222"
    private val x1 = "X:" + "33333333-3333-4333-8333-333333333333"

    private fun row(
        ref: String?,
        status: PartgDonorProposalStatus,
        allowed: Set<PartgDonorProposalAction>,
        similar: List<String> = emptyList(),
    ) = PartgDonorProposalRowDto(
        ref = ref,
        status = status,
        personKind = PartgPersonKind.NATURAL,
        name = "N",
        street = null,
        postalCode = null,
        city = null,
        country = "DE",
        bookedCents = 1_200_000L,
        enteredCents = null,
        linkedRowId = null,
        similarRowIds = similar,
        allowedActions = allowed,
        preselected = null,
    )

    @Test
    fun theThresholdIsStrictlyGreaterThanTenThousandEuro() {
        assertFalse(PartgDonorProposalRules.aboveThreshold(1_000_000L))
        assertTrue(PartgDonorProposalRules.aboveThreshold(1_000_001L))
        assertFalse(PartgDonorProposalRules.aboveThreshold(-5L))
    }

    @Test
    fun onlyANewRowIsPreselected() {
        val new = PartgDonorProposalRules.classify(bookedCents = 1_200_000L, linked = null, similarCount = 0, masterBlock = null)
        assertEquals(PartgDonorProposalStatus.NEW, new.status)
        assertEquals(PartgDonorProposalAction.CREATE, new.preselected)
        val similar = PartgDonorProposalRules.classify(bookedCents = 1_200_000L, linked = null, similarCount = 2, masterBlock = null)
        assertEquals(PartgDonorProposalStatus.SIMILAR, similar.status)
        assertEquals(setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE), similar.allowed)
        assertNull(similar.preselected)
        val different =
            PartgDonorProposalRules.classify(
                bookedCents = 1_200_000L,
                linked = 1_100_000L to PartgContributionKind.DONATION,
                similarCount = 0,
                masterBlock = null,
            )
        assertEquals(PartgDonorProposalStatus.DIFFERENT, different.status)
        assertEquals(setOf(PartgDonorProposalAction.REPLACE_AMOUNT), different.allowed)
        assertNull(different.preselected)
        val same =
            PartgDonorProposalRules.classify(
                bookedCents = 1_200_000L,
                linked = 1_200_000L to PartgContributionKind.BOTH,
                similarCount = 0,
                masterBlock = null,
            )
        assertEquals(PartgDonorProposalStatus.UNCHANGED, same.status)
        assertTrue(same.allowed.isEmpty())
    }

    @Test
    fun feesAndMasterDataProblemsAreNotTransferable() {
        listOf(PartgContributionKind.FEE, PartgContributionKind.BOTH).forEach {
            val c =
                PartgDonorProposalRules.classify(
                    bookedCents = 1_200_000L,
                    linked = 1_100_000L to it,
                    similarCount = 0,
                    masterBlock = null,
                )
            assertEquals(PartgDonorProposalStatus.NOT_TRANSFERABLE, c.status)
            assertEquals(PartgDonorProposalBlock.CONTAINS_FEES, c.block)
            assertTrue(c.allowed.isEmpty())
        }
        PartgDonorProposalBlock.entries.filter { it != PartgDonorProposalBlock.CONTAINS_FEES }.forEach {
            val c = PartgDonorProposalRules.classify(bookedCents = 1_200_000L, linked = null, similarCount = 3, masterBlock = it)
            assertEquals(PartgDonorProposalStatus.NOT_TRANSFERABLE, c.status)
            assertEquals(it, c.block)
            assertTrue(c.allowed.isEmpty())
            assertNull(c.preselected)
        }
    }

    @Test
    fun picksAreValidatedAgainstTheRows() {
        val rows =
            listOf(
                row(ref = m1, status = PartgDonorProposalStatus.NEW, allowed = setOf(PartgDonorProposalAction.CREATE)),
                row(
                    ref = m2,
                    status = PartgDonorProposalStatus.SIMILAR,
                    allowed = setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE),
                    similar = listOf("t1"),
                ),
                row(ref = x1, status = PartgDonorProposalStatus.UNCHANGED, allowed = emptySet()),
                row(ref = null, status = PartgDonorProposalStatus.ENTERED_ONLY, allowed = emptySet()),
            )

        fun check(vararg picks: PartgDonorProposalPickDto) = PartgDonorProposalRules.validatePicks(rows = rows, picks = picks.toList())
        val create = PartgDonorProposalPickDto(ref = m1, action = PartgDonorProposalAction.CREATE)
        val link = PartgDonorProposalPickDto(ref = m2, action = PartgDonorProposalAction.LINK, targetRowId = "t1")
        assertNull(check(create, link))
        assertEquals("NO_PICKS", check())
        assertEquals("DUPLICATE_REF", check(create, create))
        assertEquals(
            "UNKNOWN_REF",
            check(PartgDonorProposalPickDto(ref = "M:" + "44444444-4444-4444-8444-444444444444", action = PartgDonorProposalAction.CREATE)),
        )
        assertEquals("ACTION_NOT_ALLOWED", check(PartgDonorProposalPickDto(ref = m1, action = PartgDonorProposalAction.REPLACE_AMOUNT)))
        assertEquals("ACTION_NOT_ALLOWED", check(PartgDonorProposalPickDto(ref = x1, action = PartgDonorProposalAction.CREATE)))
        assertEquals("TARGET_MISSING", check(PartgDonorProposalPickDto(ref = m2, action = PartgDonorProposalAction.LINK)))
        assertEquals(
            "TARGET_INVALID",
            check(PartgDonorProposalPickDto(ref = m2, action = PartgDonorProposalAction.LINK, targetRowId = "other")),
        )
        assertEquals(
            "TARGET_UNEXPECTED",
            check(PartgDonorProposalPickDto(ref = m1, action = PartgDonorProposalAction.CREATE, targetRowId = "t1")),
        )
    }

    @Test
    fun theSameTargetCannotBeLinkedTwice() {
        val both = setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE)
        val rows =
            listOf(
                row(ref = m1, status = PartgDonorProposalStatus.SIMILAR, allowed = both, similar = listOf("t1")),
                row(ref = m2, status = PartgDonorProposalStatus.SIMILAR, allowed = both, similar = listOf("t1")),
            )
        val picks =
            listOf(
                PartgDonorProposalPickDto(ref = m1, action = PartgDonorProposalAction.LINK, targetRowId = "t1"),
                PartgDonorProposalPickDto(ref = m2, action = PartgDonorProposalAction.LINK, targetRowId = "t1"),
            )
        assertEquals("DUPLICATE_TARGET", PartgDonorProposalRules.validatePicks(rows = rows, picks = picks))
    }

    @Test
    fun moreThanAThousandPicksAreRefused() {
        val picks =
            (0..PartgDonorProposalRules.MAX_PICKS).map {
                PartgDonorProposalPickDto(ref = m1 + it, action = PartgDonorProposalAction.CREATE)
            }
        assertEquals("TOO_MANY_PICKS", PartgDonorProposalRules.validatePicks(rows = emptyList(), picks = picks))
    }

    @Test
    fun countsAndFormats() {
        val counts =
            PartgDonorProposalRules.counts(
                listOf(
                    PartgDonorProposalPickDto(ref = m1, action = PartgDonorProposalAction.CREATE),
                    PartgDonorProposalPickDto(ref = m2, action = PartgDonorProposalAction.LINK, targetRowId = "t"),
                    PartgDonorProposalPickDto(ref = x1, action = PartgDonorProposalAction.REPLACE_AMOUNT),
                ),
            )
        assertEquals(PartgDonorProposalCounts(created = 1, replaced = 1, linked = 1), counts)
        assertTrue(PartgDonorProposalRules.validRef(m1))
        assertTrue(PartgDonorProposalRules.validRef(x1))
        assertFalse(PartgDonorProposalRules.validRef("Y:" + "11111111-1111-4111-8111-111111111111"))
        assertFalse(PartgDonorProposalRules.validRef("M:not-a-uuid"))
        assertFalse(PartgDonorProposalRules.validRef("M:ABCDEFAB-1111-4111-8111-111111111111"))
        assertTrue(PartgDonorProposalRules.validChecksum("a".repeat(64)))
        assertFalse(PartgDonorProposalRules.validChecksum("A".repeat(64)))
        assertFalse(PartgDonorProposalRules.validChecksum("a".repeat(63)))
    }

    @Test
    fun theOriginFollowsTheTimeOfTheTakeOver() {
        val applied = LocalDateTime(2026, 3, 1, 10, 0)
        assertEquals(PartgLargeDonorOrigin.MANUAL, PartgDonorProposalRules.originOf(sourceAppliedAt = null, updatedAt = applied))
        assertEquals(PartgLargeDonorOrigin.LEDGER, PartgDonorProposalRules.originOf(sourceAppliedAt = applied, updatedAt = applied))
        assertEquals(
            PartgLargeDonorOrigin.LEDGER_EDITED,
            PartgDonorProposalRules.originOf(sourceAppliedAt = applied, updatedAt = LocalDateTime(2026, 3, 1, 10, 1)),
        )
    }
}
