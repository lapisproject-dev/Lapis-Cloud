package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PublicTextRules
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStartInput
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus

/*
 * V1.9.304 "Raum-Umfragen" -- the DOM-free part of the client: the leadership form, the polling rhythm, the badge, the announcements, the
 * auto-open rule. Pure functions so `jsTest` pins them without a browser; the DOM lives in ConferenceRoomPollPanel.kt.
 */

/** The three ways to give the answers: two ready-made sets and the free list. */
internal enum class RoomPollTemplate { YES_NO_ABSTAIN, YES_NO, CUSTOM }

/** What the leadership has typed so far. [custom] holds the free rows (at least two are always on screen). */
internal data class RoomPollForm(
    val question: String = "",
    val template: RoomPollTemplate = RoomPollTemplate.YES_NO_ABSTAIN,
    val custom: List<String> = listOf("", ""),
    val mode: RoomPollCountingMode = RoomPollCountingMode.BOTH,
)

/** Why the form cannot be sent yet. `null` = it can. */
internal enum class RoomPollFormProblem { QUESTION_LENGTH, OPTIONS, FORBIDDEN_CHARACTERS }

/** The answer texts the current template stands for (the ready-made ones are translated, the free rows are what was typed). */
internal fun RoomPollForm.options(): List<String> =
    when (template) {
        RoomPollTemplate.YES_NO_ABSTAIN -> listOf(gettext("Ja"), gettext("Nein"), gettext("Enthaltung"))
        RoomPollTemplate.YES_NO -> listOf(gettext("Ja"), gettext("Nein"))
        RoomPollTemplate.CUSTOM -> custom
    }

/** The same check the server runs ([RoomPollRules]), so the form says "no" before a round trip. */
internal fun roomPollFormProblem(form: RoomPollForm): RoomPollFormProblem? {
    val texts = listOf(form.question) + form.options()
    if (texts.any { RoomPollRules.rejectReason(it) != null }) return RoomPollFormProblem.FORBIDDEN_CHARACTERS
    val question = RoomPollRules.normalize(form.question)
    val tooLong = question.length > RoomPollRules.MAX_QUESTION
    val tooShort = PublicTextRules.codePointCount(question) < RoomPollRules.MIN_QUESTION
    if (tooLong || tooShort) return RoomPollFormProblem.QUESTION_LENGTH
    val options = form.options().map { RoomPollRules.normalize(it) }
    val distinct = options.map { it.lowercase() }.toSet().size == options.size
    val sized = options.all { it.length in 1..RoomPollRules.MAX_OPTION_LENGTH }
    return if (options.size in RoomPollRules.MIN_OPTIONS..RoomPollRules.MAX_OPTIONS &&
        distinct &&
        sized
    ) {
        null
    } else {
        RoomPollFormProblem.OPTIONS
    }
}

internal fun roomPollFormInput(
    roomId: String,
    form: RoomPollForm,
): RoomPollStartInput = RoomPollStartInput(roomId = roomId, question = form.question, options = form.options(), countingMode = form.mode)

/** A poll that is running (the form is not shown and "Starten" stays off while one runs). */
internal fun RoomPollStateDto?.isOpen(): Boolean = this?.poll?.status == RoomPollStatus.OPEN

/** The dot on the toolbar button: an open poll the person has not answered yet. */
internal fun roomPollBadgeVisible(state: RoomPollStateDto?): Boolean = state.isOpen() && state?.canAnswer == true

internal const val ROOM_POLL_HIDDEN_POLL_MS = 15_000

/** 4 s for the leadership while a poll runs, 5 s for everybody else, 15 s in a hidden tab. */
internal fun roomPollPollDelayMs(
    state: RoomPollStateDto?,
    hidden: Boolean,
): Int =
    when {
        hidden -> ROOM_POLL_HIDDEN_POLL_MS
        state.isOpen() && state?.isLeadership == true -> RoomPollRules.LEADERSHIP_POLL_MS
        else -> RoomPollRules.CLIENT_POLL_MS
    }

/** A new open poll this person can answer: a person who is not the leadership hears it and sees the toolbar dot. */
internal fun roomPollIsNewForAnswering(
    previous: RoomPollStateDto?,
    next: RoomPollStateDto,
): Boolean = next.isOpen() && next.canAnswer && next.poll?.id != previous?.poll?.id

/** The screen reader text of a change: a new poll (with its question), or the end. `null` = nothing to say. [question] is already sanitized. */
internal fun roomPollAnnouncement(
    previous: RoomPollStateDto?,
    next: RoomPollStateDto,
    question: String,
): String? =
    when {
        roomPollIsNewForAnswering(previous, next) -> gettext("Neue Umfrage: %1", question)
        previous.isOpen() && previous?.poll?.id == next.poll?.id && next.poll?.status == RoomPollStatus.CLOSED ->
            gettext("Umfrage beendet. Das Ergebnis ist da.")
        else -> null
    }

/** From 992 px the panel opens by itself when a new poll arrives; on a narrower screen it never takes the view away. */
internal fun roomPollShouldAutoOpen(
    previous: RoomPollStateDto?,
    next: RoomPollStateDto,
    wideViewport: Boolean,
): Boolean = wideViewport && roomPollIsNewForAnswering(previous, next)

internal const val ROOM_POLL_WIDE_QUERY = "(min-width: 992px)"
