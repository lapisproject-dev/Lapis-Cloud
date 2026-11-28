package network.lapis.cloud.server.translation

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * The ONLY writer of `event.translation_consent` / `_by` / `_at` (tripwire: `TranslationStructureTest`).
 *
 * `_by`/`_at` hold the LAST decision (grant or revoke). `_at` is the "generation" a running translation job compares against when it writes
 * its result: a revoke followed by a re-grant always produces a strictly later `_at`, so a job started before the revoke can never store.
 */
internal object EventTranslationConsent {
    /**
     * Runs in the caller's transaction. Locks the event row, sets value/by/at and -- on revoke -- deletes every cached translation of the
     * event in the SAME transaction. Returns whether the stored consent value changed.
     */
    fun set(
        eventId: Uuid,
        consent: Boolean,
        actorId: Uuid,
        now: LocalDateTime,
    ): Boolean {
        val row = EventStore.lockEventForUpdate(eventId) ?: throw NotFoundException("Event $eventId not found")
        val changed = row[EventTable.translationConsent] != consent
        if (changed) {
            val previousAt = row[EventTable.translationConsentAt]
            val at = if (previousAt != null && now <= previousAt) previousAt.plusMillisecond() else now
            EventTable.update({ EventTable.id eq eventId }) {
                it[translationConsent] = consent
                it[translationConsentBy] = actorId
                it[translationConsentAt] = at
            }
        }
        if (!consent) ContentTranslationStore.deleteForContent(type = TranslatableContentType.EVENT, id = eventId)
        return changed
    }

    private fun LocalDateTime.plusMillisecond(): LocalDateTime = toInstant(TimeZone.UTC).plus(1.milliseconds).toLocalDateTime(TimeZone.UTC)
}
