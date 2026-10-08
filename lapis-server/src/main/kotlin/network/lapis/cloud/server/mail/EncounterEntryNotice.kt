package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime

/**
 * Welle V1.9.76 -- the data of the anonymous entry notice to the office holders of an encounter space. **Anonymous by construction**
 * (Art. 9 GDPR: attending a church service reveals religious belief): NO field can carry a person -- no member id, name, e-mail
 * address or role of the entrant, only the room title, a time (minute for a single entry, a five-minute window otherwise) and two numbers.
 * [recipients] are the office holders who are being informed, never the entrant.
 */
data class EncounterEntryNotice(
    val recipients: List<String>,
    val spaceTitle: String,
    val kind: Kind,
    /** FIRST_GUEST: organisation wall time floored to the minute; WINDOW: the window start. */
    val at: LocalDateTime,
    /** WINDOW only. */
    val windowEnd: LocalDateTime?,
    /** Number of persons without an office (FIRST_GUEST = 1). */
    val entries: Int,
    /** Everybody currently present in the room. */
    val presentCount: Int,
) {
    enum class Kind { FIRST_GUEST, WINDOW }
}

/**
 * Abstraction over "tell the office holders that somebody arrived". **Fire-and-forget, never throws** -- the caller invokes [send]
 * AFTER the entry transaction has committed, so a mail failure can never block or undo an entry.
 */
interface EncounterEntryNoticeMailer {
    /** `false` = no mail can be sent (SMTP not configured): the notifier then does no work at all, not even a database read. */
    val enabled: Boolean

    fun send(notice: EncounterEntryNotice)
}
