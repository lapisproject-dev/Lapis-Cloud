package network.lapis.cloud.server.mail

import java.util.Collections

/**
 * Test-only stand-in for [EncounterEntryNoticeMailer] -- records every notice, can be switched off ([enabled]) and can simulate a
 * failing transport ([throwOnSend]). Thread-safe (the concurrency tests enter in parallel).
 */
class FakeEncounterEntryNoticeMailer : EncounterEntryNoticeMailer {
    val calls: MutableList<EncounterEntryNotice> = Collections.synchronizedList(mutableListOf())

    @Volatile override var enabled: Boolean = true

    @Volatile var throwOnSend: Boolean = false

    override fun send(notice: EncounterEntryNotice) {
        calls += notice
        if (throwOnSend) throw RuntimeException("simulated mail transport failure")
    }
}
