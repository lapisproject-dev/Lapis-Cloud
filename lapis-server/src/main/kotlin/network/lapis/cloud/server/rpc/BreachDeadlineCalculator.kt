package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import kotlin.time.Duration.Companion.hours

/**
 * Pure Art. 33(1) DSGVO 72-hour authority-notification-deadline clock, extracted so it is
 * unit-testable without a database -- same "pure logic extracted to a sibling file" idiom as
 * [PartyDonationComplianceCalculator]/[DpiaRiskMatrix]. Only ever invoked by
 * [DsgvoComplianceService] to compute [network.lapis.cloud.shared.domain.DataBreachIncidentDto
 * .authorityNotificationDeadline]/`.deadlineStatus` at read time -- never persisted.
 *
 * **This shows the clock, it does not decide the law.** [AUTHORITY_NOTIFICATION_WINDOW_HOURS] is
 * the statutory 72-hour figure from Art. 33(1) DSGVO, but WHETHER a given incident actually
 * requires notification at all is
 * [network.lapis.cloud.shared.domain.DataBreachIncidentDto.authorityNotificationRequired] -- a
 * separate, always-human-entered field this calculator never reads or influences. Likewise, the
 * precise legal meaning of "Kenntnis" (the moment [discoveredAt] is meant to represent) and any
 * recognized exceptions to the 72h clock are Rechtswertungen this object does not attempt --
 * **current understanding, verify against the current DSGVO text and a
 * Datenschutzbeauftragter/lawyer before relying on this for a real incident**, same disclaimer
 * class as [PartyDonationComplianceCalculator]/`14-audit-log.kuml.kts`'s own top-of-file note.
 */
internal object BreachDeadlineCalculator {
    /**
     * Art. 33(1) DSGVO's statutory notification window -- current understanding, verify against
     * the current DSGVO text and a lawyer (see class KDoc "ab Kenntnis" caveat).
     */
    const val AUTHORITY_NOTIFICATION_WINDOW_HOURS = 72L

    /**
     * Pure UI/display heuristic ("close enough to the deadline to flag urgently") -- NOT itself a
     * legal figure, unlike [AUTHORITY_NOTIFICATION_WINDOW_HOURS]. Freely adjustable without any
     * legal-review implication.
     */
    const val DUE_SOON_THRESHOLD_HOURS = 12L

    /**
     * [discoveredAt] plus the statutory 72h window -- see class KDoc. V1.9.38: `discoveredAt` is a class-B
     * wall-clock typed in by a person in [orgZone] (the organization zone), NOT a UTC stamp. The 72 hours are
     * added to the REAL instant it denotes, and the result is expressed as a wall-clock in [orgZone] again, so
     * it stays class B (displayed without conversion) and a DST change inside the window is honoured.
     * Before this, the wall-clock was read as UTC, which made the deadline 1-2 h too late in Germany.
     */
    fun deadline(
        discoveredAt: LocalDateTime,
        orgZone: TimeZone,
    ): LocalDateTime = deadlineInstant(discoveredAt = discoveredAt, orgZone = orgZone).toLocalDateTime(orgZone)

    private fun deadlineInstant(
        discoveredAt: LocalDateTime,
        orgZone: TimeZone,
    ) = discoveredAt.toInstant(orgZone).plus(AUTHORITY_NOTIFICATION_WINDOW_HOURS.hours)

    /**
     * [BreachDeadlineStatus.SATISFIED] iff [authorityNotifiedAt] is non-null (notification already
     * happened -- reported regardless of whether it landed before or after the deadline, since
     * this object makes no judgement on lateness once the human action is recorded). Otherwise
     * [BreachDeadlineStatus.OVERDUE] once [now] is past [deadline], [BreachDeadlineStatus.DUE_SOON]
     * within [DUE_SOON_THRESHOLD_HOURS] of it, else [BreachDeadlineStatus.WITHIN_WINDOW]. Never
     * reads/sets `authorityNotificationRequired` -- see class KDoc.
     *
     * [now] is a class-A system timestamp (UTC), [discoveredAt] a class-B wall-clock in [orgZone]; the two are
     * compared as real instants.
     */
    fun status(
        discoveredAt: LocalDateTime,
        authorityNotifiedAt: LocalDateTime?,
        now: LocalDateTime,
        orgZone: TimeZone,
    ): BreachDeadlineStatus {
        if (authorityNotifiedAt != null) return BreachDeadlineStatus.SATISFIED

        val deadlineInstant = deadlineInstant(discoveredAt = discoveredAt, orgZone = orgZone)
        val nowInstant = now.toInstant(ServerClock.zone)
        return when {
            nowInstant > deadlineInstant -> BreachDeadlineStatus.OVERDUE
            deadlineInstant - nowInstant <= DUE_SOON_THRESHOLD_HOURS.hours -> BreachDeadlineStatus.DUE_SOON
            else -> BreachDeadlineStatus.WITHIN_WINDOW
        }
    }
}
