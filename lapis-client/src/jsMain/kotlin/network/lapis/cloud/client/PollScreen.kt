package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.rpc.IPollService

/**
 * V1.9.31 "Umfragen" -- the web client of the opinion polls (`IPollService`, V1.9.30): short, NON-BINDING polls (Stimmungsbilder) among the
 * members. A poll is never a resolution; the answer is anonymous and the result is visible only after the poll has ended.
 *
 * Two views on one route family: `/polls` is the list, `/polls/:id` the detail of one poll (answer booth while it is open, result after).
 *
 * What the screens never do (enforced by `PollSecrecyTripwireTest`): show a server message (Kilua RPC transmits only the exception type),
 * write the chosen option anywhere but the radio buttons' own checked state, or log anything about an answer. Every read of this feature
 * lives in this file, so the other files stay free of loading logic.
 */
internal data class PollUiContext(
    val currentMemberId: String,
)

fun renderPollScreen(
    container: SimplePanel,
    initialPollId: String? = null,
) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val ctx = PollUiContext(session.memberId)
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    val header = root.pageHeader(tr("Umfragen"))
    if (initialPollId != null) {
        root.button(tr("Zur Übersicht"), style = ButtonStyle.OUTLINESECONDARY).onClick { navigateTo(Routes.POLLS) }
        renderPollDetail(root.vPanel(spacing = 10), initialPollId, ctx)
        return
    }
    renderPollList(root, header, ctx)
}

/** One page of the list: the polls, the viewer's own state per poll, whether a further page exists and whether the viewer may start a poll. */
internal class PollListData(
    val polls: List<PollDto>,
    val own: Map<String, PollParticipationDto>,
    val hasMore: Boolean,
    val canCreate: Boolean,
)

/**
 * One page (newest first) of the polls with [status] starting at [offset]. One more row than a page is requested: it only tells whether a
 * next page exists and is cut off. The viewer's own state comes in batches ([loadOwnParticipations]), never one call per row. Whether the
 * viewer may start a poll is asked on the first page only.
 */
internal suspend fun loadPollList(
    status: PollStatus,
    offset: Int,
): PollListData? {
    val service = rpcService<IPollService>()
    val safeOffset = offset.coerceIn(0, PollRules.MAX_LIST_OFFSET)
    val page = pollReadGuarded { service.listPolls(status, PollRules.DEFAULT_LIST_LIMIT + 1, safeOffset) } ?: return null
    val polls = page.take(PollRules.DEFAULT_LIST_LIMIT)
    val own = loadOwnParticipations(polls.map { it.id })
    val canCreate = if (safeOffset == 0) loadCanCreatePolls() else false
    return PollListData(polls = polls, own = own, hasMore = page.size > PollRules.DEFAULT_LIST_LIMIT, canCreate = canCreate)
}

/** The viewer's own participation for [pollIds], in blocks of the server's batch maximum. A failed block simply leaves its rows without a state. */
internal suspend fun loadOwnParticipations(pollIds: List<String>): Map<String, PollParticipationDto> {
    val service = rpcService<IPollService>()
    return pollIds
        .chunked(PollRules.MAX_PARTICIPATION_BATCH)
        .flatMap { ids -> pollReadGuarded { service.listPollParticipations(ids) } ?: emptyList() }
        .associateBy { it.pollId }
}

/** Everything one detail view shows, loaded together so the screen is built from one consistent snapshot. */
internal class PollDetailData(
    val poll: PollDto,
    val participation: PollParticipationDto,
    val result: PollResultDto?,
)

internal suspend fun loadPollDetail(pollId: String): PollDetailData? {
    val service = rpcService<IPollService>()
    val poll = pollReadGuarded { service.getPoll(pollId) } ?: return null
    val participation = pollReadGuarded { service.getPollParticipation(pollId) } ?: return null
    // The result exists only for a closed poll; asking for it earlier is a conflict the server answers with an error.
    val result = if (poll.status == PollStatus.CLOSED) pollReadGuarded { service.getPollResult(pollId) } else null
    return PollDetailData(poll = poll, participation = participation, result = result)
}

/** Whether the viewer may start a poll. Quiet: a failure is simply "no" (the button is a hint, the server re-checks on every create). */
internal suspend fun loadCanCreatePolls(): Boolean =
    try {
        rpcService<IPollService>().canCreatePolls()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }

/** What a failed answer leaves to find out: whether the member has answered and whether the poll is still open. `null` means unknown. */
internal class PollProbe(
    val participation: PollParticipationDto?,
    val poll: PollDto?,
)

/** Reads the state silently (no toast, no log): a failed read is simply unknown, never an error to show. */
internal suspend fun probePollState(pollId: String): PollProbe {
    val service = rpcService<IPollService>()
    val participation = probeQuietly { service.getPollParticipation(pollId) }
    val poll = probeQuietly { service.getPoll(pollId) }
    return PollProbe(participation, poll)
}

private suspend fun <T> probeQuietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
