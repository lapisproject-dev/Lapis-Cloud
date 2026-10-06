package network.lapis.cloud.client.encounter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** V1.9.62 -- the in-memory chat of one visit: bounded, cut to the limit, no time anywhere. */
class EncounterChatLogTest {
    private fun entry(
        index: Int,
        text: String = "line $index",
    ) = EncounterChatEntry(senderIdentity = "id-$index", senderName = "Name $index", text = text)

    @Test
    fun theLog_keepsTheNewest200Lines() {
        val log = EncounterChatLog()
        repeat(250) { log.add(entry(it)) }
        assertEquals(200, log.entries.size)
        assertEquals("line 50", log.entries.first().text)
        assertEquals("line 249", log.entries.last().text)
    }

    @Test
    fun aReceivedLine_isCutTo500Characters() {
        val log = EncounterChatLog()
        log.add(entry(1, "x".repeat(5_000)))
        assertEquals(
            ENCOUNTER_CHAT_MAX_CHARS,
            log.entries
                .single()
                .text.length,
        )
    }

    @Test
    fun theEntry_carriesNoTimeField() {
        // A chat line is who and what -- nothing in the room shows when something was said.
        val shown = entry(1).toString()
        assertTrue(!shown.contains("epoch", ignoreCase = true) && !shown.contains("sentAt", ignoreCase = true))
    }

    @Test
    fun aDraft_isTrimmed_blankIsEmpty_andOverTheLimitIsRefused() {
        assertEquals(EncounterChatDraft.Empty, encounterChatDraft("   \n "))
        assertEquals(EncounterChatDraft.Ready("Amen"), encounterChatDraft("  Amen  "))
        assertEquals(
            EncounterChatDraft.Ready("x".repeat(ENCOUNTER_CHAT_MAX_CHARS)),
            encounterChatDraft("x".repeat(ENCOUNTER_CHAT_MAX_CHARS)),
        )
        assertEquals(EncounterChatDraft.TooLong, encounterChatDraft("x".repeat(ENCOUNTER_CHAT_MAX_CHARS + 1)))
        assertIs<EncounterChatDraft.Ready>(
            encounterChatDraft("<script>alert(1)</script>"),
            "text is never interpreted, only length-checked",
        )
    }
}
