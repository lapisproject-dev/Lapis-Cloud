package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollGroupResultDto
import network.lapis.cloud.shared.domain.RoomPollResultDto
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollVoterGroup

/*
 * V1.9.304 -- the result of a room poll: up to three columns (members | guests and friends | all together) in the SAME order and with the SAME
 * bar width on every screen. Neutral colour only, no winner marked, options in the order they were created. Texts only through the sanitising
 * helpers and `textContent` (a question or an option is the leadership's free text). The server has already suppressed everything below the
 * thresholds, so this file only ever draws what the answer contains -- it never guesses a hidden group.
 */

internal const val ROOM_POLL_RESULT_CLASS = "lapis-room-poll-result"

/** Builds the result view into [parent]; the returned element is the view's root. */
internal fun Container.roomPollResultView(
    poll: RoomPollDto,
    result: RoomPollResultDto,
): Div {
    val root = div(className = ROOM_POLL_RESULT_CLASS)
    root.setAttribute("role", "group")
    root.setAttribute("aria-label", gettext("Ergebnis der Umfrage"))
    if (!result.resultAvailable) {
        root.div(
            gettext("Ergebnis erst ab %1 Antworten (bisher %2)", RoomPollRules.MIN_ANSWERS_PER_GROUP, result.totalAnswerCount),
            className = "text-muted small lapis-room-poll-hint",
        )
        return root
    }
    val options = poll.options.sortedBy { it.position }
    val grid = root.div(className = "lapis-room-poll-columns")
    val columns =
        if (result.splitAvailable) {
            listOfNotNull(
                result.members?.let { gettext("Mitglieder") to it },
                result.guestsAndFriends?.let { gettext("Gäste und Freunde") to it },
                result.all?.let { gettext("Alle zusammen") to it },
            )
        } else {
            listOfNotNull(result.all?.let { gettext("Alle zusammen") to it })
        }
    columns.forEach { (title, column) ->
        grid.roomPollColumn(title, column, poll, options.map { sanitizeUntrustedI18nText(it.text) }, result)
    }
    if (!result.splitAvailable) {
        root.div(
            gettext("Aufteilung erst ab %1 Antworten je Gruppe", RoomPollRules.MIN_ANSWERS_PER_GROUP),
            className = "text-muted small lapis-room-poll-hint",
        )
    }
    if (poll.countingMode != RoomPollCountingMode.HEAD_COUNT) {
        root.div(gettext("LTR-Gewichtung zählt nur Mitglieder"), className = "text-muted small lapis-room-poll-hint")
    }
    return root
}

private fun Container.roomPollColumn(
    title: String,
    column: RoomPollGroupResultDto,
    poll: RoomPollDto,
    optionTexts: List<String>,
    result: RoomPollResultDto,
) {
    val col = div(className = "lapis-room-poll-column")
    col.tag(TAG.H3, content = title, className = "h6 mb-1")
    col.div(gettext("n = %1", column.answerCount), className = "text-muted small lapis-room-poll-n")
    val showHead = column.headSharesPercent.isNotEmpty()
    val showWeighted = column.weightedAvailable && column.weightedSharesPercent.isNotEmpty()
    val both = showHead && showWeighted
    optionTexts.forEachIndexed { index, text ->
        val option = col.div(className = "lapis-room-poll-bar-row")
        option.div(text, className = "lapis-room-poll-option-text")
        if (showHead) option.roomPollBar(if (both) gettext("Köpfe") else null, column.headSharesPercent.getOrElse(index) { 0 })
        if (showWeighted) option.roomPollBar(if (both) gettext("LTR") else null, column.weightedSharesPercent.getOrElse(index) { 0 })
    }
    // Why a weighted figure is missing here: only in the column that carries it (members, or all together when the split is hidden).
    val carriesWeight = if (result.splitAvailable) column.group == RoomPollVoterGroup.MEMBERS else column.group == null
    if (poll.countingMode != RoomPollCountingMode.HEAD_COUNT && carriesWeight && !column.weightedAvailable) {
        col.div(pollWithheldText(column.weightedWithheldReason), className = "text-muted small lapis-room-poll-hint")
    }
    if (column.group == RoomPollVoterGroup.GUESTS_AND_FRIENDS && poll.countingMode != RoomPollCountingMode.HEAD_COUNT) {
        col.div(gettext("Gäste haben kein LTR – nur Kopfzahl"), className = "text-muted small lapis-room-poll-hint")
    }
}

private fun Container.roomPollBar(
    label: String?,
    percent: Int,
) {
    val row = div(className = "lapis-room-poll-bar")
    if (label != null) row.div(label, className = "lapis-room-poll-bar-label small text-muted")
    val track = row.div(className = "lapis-room-poll-track")
    val fill = track.div(className = "lapis-room-poll-fill")
    fill.setAttribute("style", "width: $percent%")
    row.div("$percent %", className = "lapis-room-poll-figure small")
}
