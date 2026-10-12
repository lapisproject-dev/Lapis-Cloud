package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EVENT_CONFERENCE_DEFAULT_CAPACITY
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventVisibility

// Welle V1.9.113 "Online-Teilnahme an Veranstaltungen" -- the pure, DOM-free part of the client side: which kinds Lapis itself hosts, the
// three states of the join button, and the words for the outcomes. Everything here is unit-testable without a mounted tree.

/** `true` for the two kinds Lapis hosts (a conference created on the first join, or the entry into an encounter space). */
internal fun EventOnlineKind.isLapisHosted(): Boolean = this == EventOnlineKind.LAPIS_CONFERENCE || this == EventOnlineKind.LAPIS_ENCOUNTER

/** The state of the join button of one event. There are exactly three, and the button stays at the same place in all of them. */
internal sealed interface EventOnlineButtonState {
    /** The window has not opened yet: the button stays visible, `aria-disabled`, and says when. [opensAt] is the organization-zone wall clock. */
    data class NotYet(
        val opensAt: LocalDateTime,
    ) : EventOnlineButtonState

    /** The window is open: the person may come in. */
    data object Open : EventOnlineButtonState

    /** The window is over. */
    data object Ended : EventOnlineButtonState
}

/**
 * The button state of [e] at [wallNow] (class B, organization zone -- both the DTO's window and the clock are wall clocks of that zone, so
 * they compare directly). `null` for an event without a Lapis online way of taking part, or one that is not published.
 */
internal fun eventOnlineButtonState(
    e: EventDto,
    wallNow: LocalDateTime,
): EventOnlineButtonState? {
    if (!e.onlineKind.isLapisHosted()) return null
    val opens = e.onlineWindowOpensAt ?: return null
    val closes = e.onlineWindowClosesAt ?: return null
    return when {
        wallNow < opens -> EventOnlineButtonState.NotYet(opens)
        wallNow > closes -> EventOnlineButtonState.Ended
        else -> EventOnlineButtonState.Open
    }
}

/** The label of the button in each state. [kind] decides between "Teilnehmen" (a conference) and "Raum betreten" (an encounter space). */
internal fun eventOnlineButtonLabel(
    state: EventOnlineButtonState,
    kind: EventOnlineKind,
): String =
    when (state) {
        is EventOnlineButtonState.NotYet -> gettext("Teilnehmen ab %1 Uhr", formatTimeOfDay(state.opensAt))
        EventOnlineButtonState.Open ->
            if (kind == EventOnlineKind.LAPIS_ENCOUNTER) gettext("Raum betreten") else gettext("Teilnehmen")
        EventOnlineButtonState.Ended -> gettext("Beendet")
    }

/** Time of day of a wall clock, in the display convention of the app (the date is added when the window opens on another day than today). */
private fun formatTimeOfDay(at: LocalDateTime): String = formatTime(at)

/** The explanation behind a non-open button (read through `aria-describedby`), `null` when the button is open. */
internal fun eventOnlineButtonReason(state: EventOnlineButtonState): String? =
    when (state) {
        is EventOnlineButtonState.NotYet ->
            gettext("Der Zugang öffnet am %1.", formatDateTime(state.opensAt))
        EventOnlineButtonState.Ended -> gettext("Die Online-Teilnahme ist beendet.")
        EventOnlineButtonState.Open -> null
    }

/** The words for what the server answered when the person tried to come in. [JOINED] has no message (the person is in). */
internal fun eventOnlineOutcomeMessage(
    outcome: EventOnlineOutcome,
    opensAt: LocalDateTime? = null,
): String? =
    when (outcome) {
        EventOnlineOutcome.JOINED -> null
        EventOnlineOutcome.NOT_YET ->
            if (opensAt != null) {
                gettext("Der Zugang öffnet am %1.", formatDateTime(opensAt))
            } else {
                gettext("Der Zugang ist noch nicht geöffnet.")
            }
        EventOnlineOutcome.ENDED -> gettext("Die Online-Teilnahme ist beendet.")
        EventOnlineOutcome.FORBIDDEN -> gettext("Für diese Veranstaltung ist keine Online-Teilnahme für Sie möglich.")
        EventOnlineOutcome.FULL -> gettext("Der Online-Raum ist voll.")
        EventOnlineOutcome.PREPARING -> gettext("Der Raum wird gerade vorbereitet -- bitte versuchen Sie es in einem Moment erneut.")
        EventOnlineOutcome.UNAVAILABLE -> gettext("Die Online-Teilnahme ist derzeit nicht verfügbar.")
    }

/** One sentence per kind, shown under the kind selector of the event form. Exactly one is visible at a time. */
internal fun eventOnlineKindSentence(kind: EventOnlineKind): String =
    when (kind) {
        EventOnlineKind.NONE -> gettext("Die Veranstaltung findet nur vor Ort statt.")
        EventOnlineKind.EXTERNAL_LINK -> gettext("Sie geben einen eigenen Link an, zum Beispiel zu einer anderen Videokonferenz.")
        EventOnlineKind.LAPIS_CONFERENCE ->
            gettext("Lapis legt die Videokonferenz an, sobald die erste Person beitritt. Der Zugang öffnet 15 Minuten vor Beginn.")
        EventOnlineKind.LAPIS_ENCOUNTER ->
            gettext("Die Teilnehmenden treten in einen dauerhaften Begegnungsraum ein. Der Zugang öffnet 15 Minuten vor Beginn.")
    }

/** The short word of each kind (radio label and list badge). */
internal fun eventOnlineKindLabel(kind: EventOnlineKind): String =
    when (kind) {
        EventOnlineKind.NONE -> gettext("Vor Ort")
        EventOnlineKind.EXTERNAL_LINK -> gettext("Externer Link")
        EventOnlineKind.LAPIS_CONFERENCE -> gettext("Videokonferenz")
        EventOnlineKind.LAPIS_ENCOUNTER -> gettext("Begegnungsraum")
    }

/**
 * The warnings under the selector -- each a plain sentence, none depends on colour. [visibility]/[hasRegistrationDeadline]/[capacity] come
 * from the form, [eventMaxParticipants] from the server's availability (0 = unknown), [spaceMembersOnly] from the chosen encounter space.
 */
internal fun eventOnlineWarnings(
    kind: EventOnlineKind,
    visibility: EventVisibility,
    hasRegistrationDeadline: Boolean,
    capacity: Int?,
    eventMaxParticipants: Int,
    spaceMembersOnly: Boolean,
): List<String> {
    val warnings = mutableListOf<String>()
    // The server derives the seats as min(capacity or the default, the server-wide ceiling): the sentence names the ceiling only when it bites.
    if (kind == EventOnlineKind.LAPIS_CONFERENCE &&
        eventMaxParticipants > 0 &&
        (capacity ?: EVENT_CONFERENCE_DEFAULT_CAPACITY) > eventMaxParticipants
    ) {
        warnings += gettext("Die Videokonferenz ist auf %1 Plätze begrenzt.", eventMaxParticipants.toString())
    }
    if (kind.isLapisHosted() && visibility == EventVisibility.PUBLIC && !hasRegistrationDeadline) {
        warnings += gettext("Ohne Anmeldefrist kann jede Person mit dem Link teilnehmen, auch ohne Konto.")
    }
    if (kind == EventOnlineKind.LAPIS_ENCOUNTER && visibility == EventVisibility.PUBLIC && spaceMembersOnly) {
        warnings += gettext("In diesem Raum sind nur Mitglieder zugelassen.")
    }
    if (kind == EventOnlineKind.LAPIS_ENCOUNTER && hasRegistrationDeadline) {
        warnings +=
            gettext("Ein Begegnungsraum kann nicht auf angemeldete Personen beschränkt werden -- bitte ohne Anmeldeschluss anlegen.")
    }
    return warnings
}

/** Short note shown with the options when conferencing is not configured on this server. */
internal fun eventOnlineUnavailableNote(): String =
    tr("Videokonferenzen sind auf diesem Server nicht eingerichtet -- Videokonferenz und Begegnungsraum sind nicht wählbar.")
