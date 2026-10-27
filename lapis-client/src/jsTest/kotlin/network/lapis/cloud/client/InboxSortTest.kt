package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.231 -- the order of the message list is deterministic: newest first, a conversation before another type, then by id. */
class InboxSortTest {
    private fun conversation(
        id: String,
        minute: Int,
    ) = ConversationRow(DirectMessagePartnerDto(id, "Person $id", LocalDateTime(2026, 10, 2, 9, minute), 0))

    /** A second row type, as a later wave will add it (a circular letter). */
    private class OtherRow(
        override val id: String,
        override val sortAt: LocalDateTime,
    ) : InboxRow {
        override val typeRank: Int get() = 1
    }

    @Test
    fun newestFirst() {
        val sorted = sortInboxRows(listOf(conversation("a", 1), conversation("b", 30), conversation("c", 10)))
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun onTheSameTime_aConversationComesBeforeAnotherType_thenByIdAscending() {
        val at = LocalDateTime(2026, 10, 2, 9, 5)
        val rows = listOf(OtherRow("a-other", at), conversation("z", 5), conversation("m", 5))
        assertEquals(listOf("m", "z", "a-other"), sortInboxRows(rows).map { it.id })
    }

    @Test
    fun theOrderDoesNotDependOnTheInputOrder_andIsStableAcrossCalls() {
        val rows = (1..6).map { conversation("p$it", if (it % 2 == 0) 5 else 7) }
        val expected = sortInboxRows(rows).map { it.id }
        assertEquals(expected, sortInboxRows(rows.reversed()).map { it.id })
        assertEquals(expected, sortInboxRows(rows).map { it.id })
        assertEquals(listOf("p1", "p3", "p5", "p2", "p4", "p6"), expected)
    }
}
