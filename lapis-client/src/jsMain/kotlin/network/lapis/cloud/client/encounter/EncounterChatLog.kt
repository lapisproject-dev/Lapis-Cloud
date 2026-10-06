package network.lapis.cloud.client.encounter

/** The longest chat message the room accepts or shows (characters). */
internal const val ENCOUNTER_CHAT_MAX_CHARS = 500

/**
 * One chat line. [senderIdentity]/[senderName] come from the SDK-verified participant, never from the payload. There is deliberately NO
 * time: nothing in the room shows when something was said, so the chat leaves no time trail.
 */
internal data class EncounterChatEntry(
    val senderIdentity: String,
    val senderName: String,
    val text: String,
)

/**
 * The in-memory chat of one visit: at most [cap] lines (the oldest fall out), each cut to [ENCOUNTER_CHAT_MAX_CHARS]. It lives only in
 * the page's memory -- closing the page or leaving the room ends it; nothing is stored or sent anywhere.
 */
internal class EncounterChatLog(
    private val cap: Int = 200,
) {
    private val lines = ArrayDeque<EncounterChatEntry>()

    val entries: List<EncounterChatEntry> get() = lines.toList()

    fun add(entry: EncounterChatEntry) {
        lines.addLast(entry.copy(text = entry.text.take(ENCOUNTER_CHAT_MAX_CHARS)))
        while (lines.size > cap) lines.removeFirst()
    }
}

/** What happens to text typed into the chat field. */
internal sealed interface EncounterChatDraft {
    /** Nothing to send (blank). */
    data object Empty : EncounterChatDraft

    /** Over the limit: not sent, the field shows the counter. */
    data object TooLong : EncounterChatDraft

    data class Ready(
        val text: String,
    ) : EncounterChatDraft
}

internal fun encounterChatDraft(input: String): EncounterChatDraft {
    val text = input.trim()
    return when {
        text.isEmpty() -> EncounterChatDraft.Empty
        text.length > ENCOUNTER_CHAT_MAX_CHARS -> EncounterChatDraft.TooLong
        else -> EncounterChatDraft.Ready(text)
    }
}
