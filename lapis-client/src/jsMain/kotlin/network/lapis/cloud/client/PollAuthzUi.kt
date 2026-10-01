package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollStatus

/*
 * V1.9.31 "Umfragen" -- the pure, DOM-free gates of the poll screens. They only read the hints the server already computed
 * (`canRespond`, `canManage`, the effective status); the server re-checks every call, so these decide what the screen OFFERS, never what is
 * allowed.
 */

/** The answer booth is offered: the poll is open and the server says this member may still answer. */
internal fun canRespondToPoll(
    poll: PollDto,
    p: PollParticipationDto,
): Boolean = poll.status == PollStatus.OPEN && p.canRespond

/** "Umfrage schließen" is offered: the server says the viewer manages this poll and it is still open. */
internal fun canClosePoll(poll: PollDto): Boolean = poll.canManage && poll.status == PollStatus.OPEN

/** "Umfrage abbrechen" is offered: same condition as closing. */
internal fun canAbortPoll(poll: PollDto): Boolean = poll.canManage && poll.status == PollStatus.OPEN

/** A result exists only for a closed poll (the server refuses it while open and for an aborted one). */
internal fun showsPollResult(poll: PollDto): Boolean = poll.status == PollStatus.CLOSED

/** The number of answers is shown only after the poll is closed and only if the server sent one. */
internal fun showsResponseCount(poll: PollDto): Boolean = poll.status == PollStatus.CLOSED && poll.responseCount != null

private const val WHOLE = 100

/**
 * Whole percents of the head counts in position order, by the largest-remainder method (ties go to the lower position), so the shares add up
 * to exactly 100 -- the same method the server uses for the weighted result, so "by heads" never shows 99 % or 101 %. All counts 0 gives all 0.
 */
internal fun headSharePercents(countsInPositionOrder: List<Int>): List<Int> {
    val total = countsInPositionOrder.sumOf { it.coerceAtLeast(0) }
    if (total == 0) return countsInPositionOrder.map { 0 }
    val floors = countsInPositionOrder.map { it.coerceAtLeast(0) * WHOLE / total }
    val remainders = countsInPositionOrder.map { it.coerceAtLeast(0) * WHOLE % total }
    val leftover = WHOLE - floors.sum()
    val bumped =
        countsInPositionOrder.indices
            .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
            .take(leftover)
            .toSet()
    return floors.mapIndexed { index, floor -> if (index in bumped) floor + 1 else floor }
}
