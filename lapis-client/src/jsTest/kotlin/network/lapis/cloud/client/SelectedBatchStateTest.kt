package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.2.2 SEPA-Client-UI wave, Review Round 2 (2026-08-20, MAJOR + MINOR): regression coverage for
 * [SelectedBatchState] -- the fold-in-the-freshest-non-empty-`failedItemIds` cache `showDetail`
 * (`SepaBatchesScreen.kt`) relies on (see that class's own KDoc "S-5"). No test existed for this
 * class before this fix; a test would have caught the MAJOR finding that `SepaBatchesScreen` never
 * actually fed a non-empty `failedItemIds` into it -- `settleBatch`'s own response was discarded in
 * favor of a `getBatch()` refetch, which per [SepaDebitBatchDetailDto.failedItemIds] KDoc ALWAYS
 * returns an empty list.
 *
 * `fromSettle` (MINOR fix, same round): the MAJOR fix's own `apply()` call from `settleBatch`'s
 * response and a subsequent `getBatch()` refetch both used to run through the same "non-empty
 * wins" rule, so a settle response's empty `failedItemIds` (a fully successful retry) was
 * indistinguishable from an uninformative `getBatch()` refetch and never cleared a previously
 * cached failure. Tests below pass `fromSettle = true` for simulated `settleBatch` responses and
 * leave it at its default `false` for simulated `getBatch()` refetches, matching the real call
 * sites in `SepaBatchesScreen.showDetail`.
 */
class SelectedBatchStateTest {
    private fun batch(id: String) =
        SepaDebitBatchDto(
            id = id,
            messageId = "msg-$id",
            paymentInfoId = "pmt-$id",
            requestedCollectionDate = LocalDate(2026, 9, 30),
            sequenceType = SepaSequenceType.RCUR,
            status = SepaDebitBatchStatus.SUBMITTED,
            itemCount = 2,
            totalAmount = 42.0.toDecimal(),
            createdByDisplayName = "Schatzmeister",
            createdAt = LocalDateTime(2026, 8, 1, 10, 0),
            notifiedAt = LocalDateTime(2026, 8, 1, 10, 0),
            requiredNoticeDays = 14,
            fileGenerationAllowedFrom = LocalDate(2026, 8, 15),
            generatedAt = LocalDateTime(2026, 8, 15, 9, 0),
            generatedDocumentId = "doc-1",
            prenotificationDocumentId = null,
            submittedAt = LocalDateTime(2026, 8, 16, 9, 0),
            submittedNote = null,
            settledAt = null,
            settlementEligibleFrom = null,
            cancelledAt = null,
            cancellationReason = null,
        )

    private fun detail(
        batchId: String,
        failedItemIds: List<String> = emptyList(),
    ) = SepaDebitBatchDetailDto(batch = batch(batchId), items = emptyList(), failedItemIds = failedItemIds)

    @Test
    fun apply_foldsNonEmptyFailedItemIdsFromSettleIntoLaterEmptyGetBatchRefetch() {
        val state = SelectedBatchState()
        val settled = state.apply(detail("batch-1", failedItemIds = listOf("item-1", "item-2")), fromSettle = true)
        assertEquals(listOf("item-1", "item-2"), settled.failedItemIds)

        // Simulates the subsequent getBatch() refetch that always returns an empty list -- the
        // cache must keep surfacing the last known-failed ids instead of silently losing them.
        val refetched = state.apply(detail("batch-1", failedItemIds = emptyList()))
        assertEquals(listOf("item-1", "item-2"), refetched.failedItemIds)
    }

    @Test
    fun apply_resetsCacheWhenADifferentBatchIsSelected() {
        val state = SelectedBatchState()
        state.apply(detail("batch-1", failedItemIds = listOf("item-1")), fromSettle = true)

        val otherBatch = state.apply(detail("batch-2", failedItemIds = emptyList()))
        assertTrue(otherBatch.failedItemIds.isEmpty())

        // Re-selecting the original batch afterwards must NOT resurrect the stale cache either --
        // the reset is unconditional the moment a different batch id was seen in between.
        val backToFirst = state.apply(detail("batch-1", failedItemIds = emptyList()))
        assertTrue(backToFirst.failedItemIds.isEmpty())
    }

    @Test
    fun apply_returnsEmptyWhenNoSettleHasEverHappened() {
        val state = SelectedBatchState()
        val plain = state.apply(detail("batch-1"))
        assertTrue(plain.failedItemIds.isEmpty())
    }

    @Test
    fun apply_newerNonEmptyFailedItemIdsFromSettleOverwriteAnOlderCachedSet() {
        val state = SelectedBatchState()
        state.apply(detail("batch-1", failedItemIds = listOf("item-1")), fromSettle = true)

        // A second, later `settleBatch` retry that fails on a DIFFERENT item must replace the
        // cache, not merge with it -- the previous failure was presumably resolved by the retry.
        val secondSettle = state.apply(detail("batch-1", failedItemIds = listOf("item-2")), fromSettle = true)
        assertEquals(listOf("item-2"), secondSettle.failedItemIds)
    }

    /**
     * MINOR fix regression test (Review Round 2, 2026-08-20): the exact scenario from the finding
     * -- a `settleBatch` retry that fully succeeds (empty `failedItemIds`) after an earlier partial
     * failure must clear the cache, not fold the old failure into the new, empty response. Before
     * the `fromSettle` fix, `apply()` could not distinguish this from an uninformative
     * `getBatch()` refetch and kept resurfacing the three stale "fehlgeschlagen" markers even
     * though the batch had already moved to SETTLED.
     */
    @Test
    fun apply_emptyFailedItemIdsFromASuccessfulSettleRetryClearsAnEarlierCachedFailure() {
        val state = SelectedBatchState()
        state.apply(detail("batch-1", failedItemIds = listOf("item-1", "item-2", "item-3")), fromSettle = true)

        val secondSettle = state.apply(detail("batch-1", failedItemIds = emptyList()), fromSettle = true)
        assertTrue(secondSettle.failedItemIds.isEmpty())

        // The cleared cache must also stick across a subsequent getBatch() refetch, not just for
        // the settle response itself.
        val refetchedAfter = state.apply(detail("batch-1", failedItemIds = emptyList()))
        assertTrue(refetchedAfter.failedItemIds.isEmpty())
    }
}
