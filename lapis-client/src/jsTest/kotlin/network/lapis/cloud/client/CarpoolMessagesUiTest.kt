package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CarpoolMessageDto
import network.lapis.cloud.shared.domain.CarpoolMessagePageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.139 -- the pure decisions of the ride-message UI: the counter, the state lines, the send shortcut, the merge of pages. */
class CarpoolMessagesUiTest {
    private fun message(id: String) =
        CarpoolMessageDto(
            id = id,
            mine = false,
            senderLabel = "X",
            body = id,
            sentAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    private fun page(
        ids: List<String> = emptyList(),
        ended: Boolean = false,
        endedByMe: Boolean = false,
        writable: Boolean = true,
        hasMore: Boolean = false,
    ) = CarpoolMessagePageDto(ids.map(::message), hasMore, ended, endedByMe, writable, ids.size)

    @Test
    fun counter_showsLengthOfThousand() {
        assertTrue(carpoolMessageCounterText(0).contains("0"))
        assertTrue(carpoolMessageCounterText(12).contains("12"))
        assertTrue(carpoolMessageCounterText(12).contains("1000") || carpoolMessageCounterText(12).contains("1.000"))
    }

    @Test
    fun endedText_isPerSide_andAbsentWhileOpen() {
        assertNull(carpoolThreadEndedText(page()))
        assertEquals("Sie haben den Verlauf beendet.", carpoolThreadEndedText(page(ended = true, endedByMe = true, writable = false)))
        assertEquals("Der Verlauf wurde beendet.", carpoolThreadEndedText(page(ended = true, endedByMe = false, writable = false)))
    }

    @Test
    fun fullText_onlyWhenOpenButNotWritable() {
        assertNull(carpoolThreadFullText(page()))
        assertNull(carpoolThreadFullText(page(ended = true, writable = false)))
        assertTrue(carpoolThreadFullText(page(writable = false))!!.contains("200"))
    }

    @Test
    fun sendShortcut_isCtrlOrCmdEnter_notEnterAlone() {
        assertTrue(isSendShortcut(ctrl = true, meta = false, key = "Enter"))
        assertTrue(isSendShortcut(ctrl = false, meta = true, key = "Enter"))
        assertFalse(isSendShortcut(ctrl = false, meta = false, key = "Enter"))
        assertFalse(isSendShortcut(ctrl = true, meta = false, key = "a"))
    }

    @Test
    fun merge_keepsOlderPagesBehindTheNewestPage() {
        val shown = listOf(message("5"), message("4"), message("3"), message("2"), message("1"))
        val merged = mergeCarpoolMessages(shown, page(ids = listOf("6", "5", "4")))
        assertEquals(listOf("6", "5", "4", "3", "2", "1"), merged.map { it.id })
    }
}
