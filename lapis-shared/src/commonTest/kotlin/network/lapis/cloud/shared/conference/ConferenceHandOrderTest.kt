package network.lapis.cloud.shared.conference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** V1.9.253 -- raised hands first: the pure order of the tiles. */
class ConferenceHandOrderTest {
    private val base = listOf("a", "me", "b", "c", "d")

    private fun order(
        raised: List<String>,
        self: String? = "me",
        from: List<String> = base,
    ) = orderTilesByRaisedHands(base = from, raisedInOrder = raised, selfId = self)

    @Test
    fun noHand_returnsTheSameInstance() {
        assertSame(base, order(raised = emptyList()))
    }

    @Test
    fun twoHands_standInTheOrderTheyWereRaised_notInJoinOrder() {
        // d was raised before b; the own tile keeps index 1
        assertEquals(listOf("d", "me", "b", "a", "c"), order(raised = listOf("d", "b")))
    }

    @Test
    fun ownTile_keepsItsIndex_evenWithAnOwnHand() {
        assertEquals(1, order(raised = listOf("c")).indexOf("me"))
        assertSame(base, order(raised = listOf("me")))
        assertEquals(listOf("c", "me", "a", "b", "d"), order(raised = listOf("me", "c")))
    }

    @Test
    fun afterLowering_theOrderIsTheBaseAgain() {
        assertEquals(listOf("c", "me", "a", "b", "d"), order(raised = listOf("c")))
        assertSame(base, order(raised = emptyList()))
    }

    @Test
    fun sameInput_givesTheSameOutputTwice() {
        assertEquals(order(raised = listOf("d", "b")), order(raised = listOf("d", "b")))
    }

    @Test
    fun aHandWithoutATile_isIgnored() {
        assertSame(base, order(raised = listOf("ghost")))
        assertEquals(listOf("b", "me", "a", "c", "d"), order(raised = listOf("ghost", "b")))
    }

    @Test
    fun duplicatesInTheHandList_countOnce() {
        assertEquals(listOf("c", "me", "b", "a", "d"), order(raised = listOf("c", "b", "c", "b")))
    }

    @Test
    fun selfIdNullOrAbsent_noReinsertion() {
        assertEquals(listOf("c", "a", "me", "b", "d"), order(raised = listOf("c"), self = null))
        assertEquals(listOf("c", "a", "b"), order(raised = listOf("c"), from = listOf("a", "b", "c")))
    }
}
