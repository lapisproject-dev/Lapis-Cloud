package network.lapis.cloud.server.events.series

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.events.EventRoomCollisionGuard
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.ConflictException
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Dritte und letzte Folgewelle "Wiederkehrende Veranstaltungen" -- the bridge between the pure
 * fachlogik [RecurrenceExpander]/[EventSeriesScopeEngine] (no DB access) and [EventStore]'s
 * persistence layer. Unlike [RecurrenceRuleBuilder]/[RecurrenceExpander]/[EventSeriesScopeEngine],
 * this object DOES touch the DB -- same posture `network.lapis.cloud.server.events
 * .EventRegistrationSubmission` already establishes for a fachlogik object that orchestrates
 * `*Store` calls. Every function here must run inside an already-open `transaction {}` (opened by
 * the caller, `EventService`) -- this object never opens its own, same discipline every `*Store`
 * in this codebase requires of its callers.
 *
 * **Entscheidung F3 (Serie veröffentlichen)**: eine neu angelegte Serie materialisiert JEDE Instanz
 * direkt als [EventStatus.PUBLISHED] (nicht `DRAFT` + einzelnes manuelles Veröffentlichen pro
 * Instanz) -- ein manueller Publish-Klick pro Instanz wäre bei bis zu 104 Terminen unzumutbar. Der
 * BOARD/ADMIN prüft die Vorlage VOR dem Absenden (Live-Vorschau via `previewSeries`), nicht danach
 * pro Instanz.
 *
 * **Entscheidung F2 (Raumkollision)**: alles-oder-nichts, wie beim bestehenden Einzel-Event-Pfad
 * ([EventRoomCollisionGuard] wird für JEDE Instanz aufgerufen, innerhalb derselben Transaktion --
 * die erste Kollision wirft, die gesamte Serienanlage rollt zurück). Kein Teil-Erfolg.
 *
 * **Entscheidung 3 (Hart-Löschen-Gate)**: [deleteOrCancel] löscht eine Instanz nur hart, wenn
 * [EventStore.countAllRegistrationsForEvents] für sie `0` ist -- ALLE Registrierungszeilen jeden
 * Status, nicht nur aktive (`fk_event_registration_event` hat kein `ON DELETE CASCADE`).
 */
internal object EventSeriesMaterializer {
    /**
     * Expandiert [rrule]/[dtstart] und inseriert je Occurrence eine `event`-Zeile mit
     * `series_id`/`series_original_start` gesetzt. Prüft Raumkollision PRO Instanz (falls
     * `template.roomId != null`) -- alles-oder-nichts (Entscheidung F2). Läuft komplett innerhalb
     * der Caller-Transaktion; wirft [ConflictException]/[network.lapis.cloud.shared.rpc
     * .BadRequestException] wie jeder andere Guard in dieser Codebase.
     */
    fun materialize(
        seriesId: Uuid,
        rrule: String,
        dtstart: LocalDateTime,
        zone: ZoneId,
        durationMinutes: Int,
        template: EventInput,
        createdBy: Uuid,
        now: LocalDateTime,
    ): List<Uuid> {
        val occurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = zone)
        val roomId = template.roomId?.let { Uuid.parse(it) }

        val ids = mutableListOf<Uuid>()
        for (occurrenceStart in occurrences) {
            val occurrenceEnd = occurrenceStart.plusMinutesKt(durationMinutes)
            if (roomId != null) {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = roomId,
                    startsAt = occurrenceStart,
                    endsAt = occurrenceEnd,
                    excludingEventId = null,
                )
            }
            val id = Uuid.random()
            val slug =
                network.lapis.cloud.server.events.EventPolicy.slugFor(title = template.title) { candidate ->
                    EventStore.slugTaken(slug = candidate, excludingId = null)
                }
            EventStore.insertEvent(
                id = id,
                slug = slug,
                title = template.title.trim(),
                description = template.description.trim(),
                locationText = template.locationText?.trim()?.takeIf { it.isNotBlank() },
                onlineUrl = template.onlineUrl?.trim()?.takeIf { it.isNotBlank() },
                startsAt = occurrenceStart,
                endsAt = occurrenceEnd,
                capacity = template.capacity,
                feeAmount = template.feeAmount,
                feeCurrency = template.feeCurrency,
                visibility = template.visibility,
                registrationClosesAt = template.registrationClosesAt,
                createdAt = now,
                createdBy = createdBy,
                roomId = roomId,
                status = EventStatus.PUBLISHED,
                seriesId = seriesId,
                seriesOriginalStart = occurrenceStart,
            )
            ids += id
        }
        return ids
    }

    /** Result of [applyEdit]/[deleteOrCancel] -- the affected `event` ids, for the caller's `EventSeriesEditResultDto`/mail step. */
    data class EditApplyResult(
        val affectedEventIds: List<Uuid>,
        val notices: List<SeriesCancellationNotice> = emptyList(),
    )

    /** A cancellation-notice recipient, resolved to a plain value INSIDE the transaction -- mail is sent by the caller AFTER commit (same idiom `EventService.cancelEvent` establishes). */
    data class SeriesCancellationNotice(
        val to: String,
        val recipientName: String,
    )

    /**
     * Applies [scopePlan] to [seriesId]: locks every affected `event` row (ascending UUID order --
     * deadlock avoidance for concurrent series edits, same discipline `EventService.updateEvent`/
     * `.cancelEvent` already establish for a single row), updates each with [newTemplate]'s fields
     * (title/description/location/online-link/capacity/fee/visibility/room -- never `startsAt`/
     * `endsAt`, which stay tied to the occurrence's own `series_original_start`+series duration).
     * For [network.lapis.cloud.shared.domain.EventSeriesEditScope.THIS] this also detaches the
     * single occurrence ([EventStore.markSeriesEventsDetached]). For a genuine FOLLOWING split
     * ([scopePlan]'s `split` non-null), inserts the new [EventSeriesTable] row and re-points the
     * moved-forward occurrences' `series_id`.
     */
    fun applyEdit(
        scope: network.lapis.cloud.shared.domain.EventSeriesEditScope,
        scopePlan: EventSeriesScopeEngine.ScopePlan,
        seriesId: Uuid,
        newTemplate: EventInput,
        durationMinutes: Int,
        createdBy: Uuid,
        now: LocalDateTime,
    ): EditApplyResult {
        val affectedRows =
            EventStore
                .findSeriesEventsByOriginalStarts(seriesId = seriesId, originalStarts = scopePlan.affectedOriginalStarts)
                .sortedBy { it[network.lapis.cloud.server.db.generated.EventTable.id].toString() }
        val roomId = newTemplate.roomId?.let { Uuid.parse(it) }

        val affectedIds = mutableListOf<Uuid>()
        for (row in affectedRows) {
            val eventId = row[network.lapis.cloud.server.db.generated.EventTable.id]
            EventStore.lockEventForUpdate(eventId)
            if (roomId != null) {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = roomId,
                    startsAt = row[network.lapis.cloud.server.db.generated.EventTable.startsAt],
                    endsAt = row[network.lapis.cloud.server.db.generated.EventTable.startsAt].plusMinutesKt(durationMinutes),
                    excludingEventId = eventId,
                )
            }
            EventStore.updateEvent(
                id = eventId,
                title = newTemplate.title.trim(),
                description = newTemplate.description.trim(),
                locationText = newTemplate.locationText?.trim()?.takeIf { it.isNotBlank() },
                onlineUrl = newTemplate.onlineUrl?.trim()?.takeIf { it.isNotBlank() },
                startsAt = row[network.lapis.cloud.server.db.generated.EventTable.startsAt],
                endsAt = row[network.lapis.cloud.server.db.generated.EventTable.startsAt].plusMinutesKt(durationMinutes),
                capacity = newTemplate.capacity,
                feeAmount = newTemplate.feeAmount,
                feeCurrency = newTemplate.feeCurrency,
                visibility = newTemplate.visibility,
                registrationClosesAt = newTemplate.registrationClosesAt,
                roomId = roomId,
            )
            affectedIds += eventId
        }

        if (scope == network.lapis.cloud.shared.domain.EventSeriesEditScope.THIS) {
            EventStore.markSeriesEventsDetached(affectedIds)
        }

        val split = scopePlan.split
        if (split != null) {
            val newSeriesId = Uuid.random()
            val originalSeries = EventStore.getSeriesOrThrow(seriesId)
            EventStore.insertSeries(
                id = newSeriesId,
                rrule = split.newSeriesRrule,
                dtstart = split.newSeriesDtstart,
                timezone = originalSeries[network.lapis.cloud.server.db.generated.EventSeriesTable.timezone],
                durationMinutes = durationMinutes,
                splitFromSeriesId = seriesId,
                createdBy = createdBy,
                createdAt = now,
            )
            EventStore.updateSeriesRrule(id = seriesId, rrule = split.originalSeriesRrule)
            EventStore.repointSeriesId(eventIds = affectedIds, newSeriesId = newSeriesId)
        }

        return EditApplyResult(affectedEventIds = affectedIds)
    }

    /**
     * Deletes or cancels [scopePlan]'s affected occurrences of [seriesId]: hard-deletes an
     * instance with ZERO `event_registration` rows of any status ([EventStore.deleteEventHard]);
     * cancels (status -> CANCELLED, active registrations -> CANCELLED, same effect as
     * `EventService.cancelEvent`'s single-event path) every instance that has at least one. Locks
     * every affected row first, ascending UUID order, same discipline as [applyEdit]. Returns the
     * resolved mail notices (to/name) for the caller to send AFTER the transaction commits.
     */
    fun deleteOrCancel(
        scope: network.lapis.cloud.shared.domain.EventSeriesEditScope,
        scopePlan: EventSeriesScopeEngine.ScopePlan,
        seriesId: Uuid,
        now: LocalDateTime,
    ): EditApplyResult {
        val affectedRows =
            EventStore
                .findSeriesEventsByOriginalStarts(seriesId = seriesId, originalStarts = scopePlan.affectedOriginalStarts)
                .sortedBy { it[network.lapis.cloud.server.db.generated.EventTable.id].toString() }

        val affectedIds = mutableListOf<Uuid>()
        val notices = mutableListOf<SeriesCancellationNotice>()
        for (row in affectedRows) {
            val eventId = row[network.lapis.cloud.server.db.generated.EventTable.id]
            EventStore.lockEventForUpdate(eventId)
            val registrationCount = EventStore.countAllRegistrationsForEvents(listOf(eventId))
            if (registrationCount == 0) {
                EventStore.deleteEventHard(eventId)
            } else {
                if (row[network.lapis.cloud.server.db.generated.EventTable.status] != EventStatus.CANCELLED) {
                    EventStore.setStatus(id = eventId, status = EventStatus.CANCELLED, cancelledAt = now)
                }
                val activeRegistrations =
                    EventStore.listByEvent(eventId).filter { r ->
                        r[EventRegistrationTable.status] !in network.lapis.cloud.shared.domain.EventRegistrationStatusSets.INACTIVE
                    }
                activeRegistrations.forEach { r -> EventStore.cancelRegistration(id = r[EventRegistrationTable.id], now = now) }
                notices +=
                    activeRegistrations.mapNotNull { r ->
                        val memberId = r[EventRegistrationTable.memberId]
                        val to = if (memberId != null) EventStore.memberEmailOrNull(memberId) else r[EventRegistrationTable.guestEmail]
                        val name =
                            if (memberId != null) {
                                EventStore.memberDisplayNameOrNull(memberId) ?: ""
                            } else {
                                r[EventRegistrationTable.guestName] ?: ""
                            }
                        to?.let { SeriesCancellationNotice(to = it, recipientName = name) }
                    }
            }
            affectedIds += eventId
        }
        if (scope == network.lapis.cloud.shared.domain.EventSeriesEditScope.THIS) {
            EventStore.markSeriesEventsDetached(affectedIds)
        }
        return EditApplyResult(affectedEventIds = affectedIds, notices = notices)
    }
}

/** `LocalDateTime + N minutes`, without pulling in a `DateTimeUnit`/`TimeZone` conversion -- the series' own [ZoneId] wall-clock arithmetic already happened in [RecurrenceExpander.expand]; this only adds a fixed duration to an already-resolved wall-clock occurrence start. */
private fun LocalDateTime.plusMinutesKt(minutes: Int): LocalDateTime =
    this.toJavaLocalDateTime().plusMinutes(minutes.toLong()).toKotlinLocalDateTime()
