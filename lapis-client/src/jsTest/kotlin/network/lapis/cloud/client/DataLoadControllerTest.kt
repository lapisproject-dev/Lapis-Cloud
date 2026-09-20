package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Welle V1.4.25 -- the widget-free load lifecycle behind `dataSection`. `Dispatchers.Unconfined` +
 * `CompletableDeferred` make every step deterministic without a browser event loop.
 */
class DataLoadControllerTest {
    private class Harness(
        val gate: () -> CompletableDeferred<List<Int>?>,
        filter: String? = null,
    ) {
        val states = mutableListOf<DataViewState<List<Int>>>()
        val settled = mutableListOf<List<Int>?>()
        var loads = 0
        val controller =
            DataLoadController<List<Int>>(
                scope = CoroutineScope(Dispatchers.Unconfined),
                load = {
                    loads++
                    gate().await()
                },
                isEmpty = { it.isEmpty() },
                filterTerm = { filter },
                onSettled = { settled += it },
                onState = { states += it },
            )
    }

    @Test
    fun reload_showsLoadingFirst_thenTheContent() {
        val pending = CompletableDeferred<List<Int>?>()
        val harness = Harness(gate = { pending })
        harness.controller.reload()
        assertEquals(listOf<DataViewState<List<Int>>>(DataViewState.Loading), harness.states)
        pending.complete(listOf(1, 2))
        assertEquals(DataViewState.Content(listOf(1, 2)), harness.states.last())
        assertEquals(listOf<List<Int>?>(listOf(1, 2)), harness.settled)
    }

    @Test
    fun failedLoad_becomesTheErrorState_andRetryLoadsAgain() {
        val first = CompletableDeferred<List<Int>?>()
        val second = CompletableDeferred<List<Int>?>()
        val gates = ArrayDeque(listOf(first, second))
        val harness = Harness(gate = { gates.removeFirst() })
        harness.controller.reload()
        first.complete(null)
        assertEquals(DataViewState.Error, harness.states.last())
        assertEquals(listOf<List<Int>?>(null), harness.settled)

        harness.controller.reload() // the retry button
        assertEquals(2, harness.loads)
        assertEquals(DataViewState.Loading, harness.states.last())
        second.complete(listOf(7))
        assertEquals(DataViewState.Content(listOf(7)), harness.states.last())
    }

    @Test
    fun staleResult_isDropped_whenANewerLoadHasTakenOver() {
        val older = CompletableDeferred<List<Int>?>()
        val newer = CompletableDeferred<List<Int>?>()
        val gates = ArrayDeque(listOf(older, newer))
        val harness = Harness(gate = { gates.removeFirst() })
        harness.controller.reload()
        harness.controller.reload()
        newer.complete(listOf(2))
        older.complete(listOf(1)) // arrives late
        assertEquals(DataViewState.Content(listOf(2)), harness.states.last())
        assertEquals(listOf<List<Int>?>(listOf(2)), harness.settled)
    }

    @Test
    fun emptyResult_withAFilterTerm_isNoMatch_withoutOneIsEmpty() {
        val noTerm = CompletableDeferred<List<Int>?>()
        val withoutTerm = Harness(gate = { noTerm })
        withoutTerm.controller.reload()
        noTerm.complete(emptyList())
        assertEquals(DataViewState.Empty, withoutTerm.states.last())
        assertEquals(listOf<List<Int>?>(emptyList()), withoutTerm.settled)

        val termGate = CompletableDeferred<List<Int>?>()
        val withTerm = Harness(gate = { termGate }, filter = "xyz")
        withTerm.controller.reload()
        termGate.complete(emptyList())
        assertEquals(DataViewState.NoMatch("xyz"), withTerm.states.last())
    }

    @Test
    fun throwingLoad_isTreatedAsAFailure_withoutLeakingItsMessage() {
        val states = mutableListOf<DataViewState<List<Int>>>()
        DataLoadController<List<Int>>(
            scope = CoroutineScope(Dispatchers.Unconfined),
            load = { error("secret-internal-identifier-42") },
            isEmpty = { it.isEmpty() },
            filterTerm = { null },
            onSettled = {},
            onState = { states += it },
        ).reload()
        assertEquals(DataViewState.Error, states.last())
        assertFalse(states.toString().contains("secret-internal-identifier-42"))
    }
}
