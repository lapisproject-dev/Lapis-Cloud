package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.isConsensus

/*
 * V1.9.31 "Umfragen" -- the list view: a status filter (open by default), one row per poll with the viewer's own state, "Mehr laden" for
 * the next page, and -- only for those who may start polls -- "Neue Umfrage" (a collapsed create form, R36B). The filter lives in one local variable: no storage, no URL.
 * The number of answers is a column of the closed polls only (an open poll shows none, see `showsResponseCount`).
 */

/** What the list says about the viewer: an open poll still waiting for the answer, an answered one, or nothing worth a column. */
internal enum class PollOwnStatus { OpenForYou, Answered, None }

internal fun pollOwnStatus(
    poll: PollDto,
    p: PollParticipationDto?,
): PollOwnStatus =
    when {
        p == null -> PollOwnStatus.None
        poll.status == PollStatus.OPEN && p.canRespond -> PollOwnStatus.OpenForYou
        p.hasResponded -> PollOwnStatus.Answered
        else -> PollOwnStatus.None
    }

private fun pollEmptyText(status: PollStatus): String =
    when (status) {
        PollStatus.OPEN -> gettext("Zurzeit keine offenen Umfragen.")
        PollStatus.CLOSED -> gettext("Noch keine geschlossenen Umfragen.")
        PollStatus.ABORTED -> gettext("Keine abgebrochenen Umfragen.")
    }

internal fun renderPollList(
    root: SimplePanel,
    header: PageHeader,
    ctx: PollUiContext,
) {
    // R36B: the create form sits directly under the header, collapsed; the list follows below it.
    val formHost = root.vPanel(spacing = 10)
    val listHost = root.vPanel(spacing = 10)
    var filter = PollStatus.OPEN
    var createFormMounted = false

    val toolbar = listHost.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val chips =
        toolbar.div(className = "btn-group") {
            setAttribute("role", "group")
            setAttribute("aria-label", gettext("Status"))
        }

    val chipButtons = mutableMapOf<PollStatus, Button>()
    lateinit var section: DataSection

    fun markPressed() {
        chipButtons.forEach { (status, chip) ->
            chip.setAttribute("aria-pressed", (status == filter).toString())
            chip.style = if (status == filter) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY
        }
    }
    PollStatus.entries.forEach { status ->
        val chip = chips.button(pollStatusLabel(status), style = ButtonStyle.OUTLINEPRIMARY)
        chipButtons[status] = chip
        chip.onClick {
            if (filter != status) {
                filter = status
                markPressed()
                section.reload()
            }
        }
    }
    markPressed()

    section =
        listHost.dataSection<PollListData>(
            // The empty text depends on the filter, so the emptiness is decided inside the render, not by the section.
            isEmpty = { false },
            onSettled = { data ->
                // onSettled can fire again (filter change, reload): the button is hung into the header exactly once.
                if (data?.canCreate == true && !createFormMounted) {
                    createFormMounted = true
                    collapsibleCreateForm<Unit>(
                        actionSlot = header.actionSlot,
                        formHost = formHost,
                        buttonLabel = tr("Neue Umfrage"),
                        formId = "lapis-create-poll",
                    ) { _, close -> renderPollCreateForm(close = close, onCreated = { navigateTo("/polls/${it.id}") }) }
                }
            },
            load = { loadPollList(filter, 0) },
            render = { panel, data -> renderPollRows(panel, data, filter, ctx) },
        )

    section.reload()
}

@Suppress("UNUSED_PARAMETER")
private fun renderPollRows(
    panel: SimplePanel,
    first: PollListData,
    status: PollStatus,
    ctx: PollUiContext,
) {
    if (first.polls.isEmpty()) {
        panel.p(pollEmptyText(status)) { addCssClasses("text-muted") }
        return
    }
    val polls = first.polls.toMutableList()
    val own = first.own.toMutableMap()
    var hasMore = first.hasMore
    val tableHost = panel.simplePanel()
    val moreHost = panel.simplePanel()

    fun draw() {
        tableHost.removeAll()
        tableHost.dataTable(
            columns =
                listOf(
                    textColumn<PollDto>(title = tr("Frage"), primary = true, cssClasses = "fw-bold text-truncate d-inline-block mw-100") {
                        it.question
                    },
                    DataColumn(
                        title = tr("Status"),
                        cell = { container, poll ->
                            container.statusBadge(pollStatusLabel(poll.status), pollStatusColor(poll.status))
                            // Plain text mark, no icon: the consensus kinds are rated by resistance, not answered by a single choice.
                            if (poll.kind.isConsensus) container.span(gettext("Konsensieren"), className = "text-muted small ms-1")
                        },
                    ),
                    textColumn(title = tr("Frist")) { pollDeadlineLabel(it) },
                    DataColumn(
                        title = tr("Ihr Stand"),
                        cell = { container, poll ->
                            when (pollOwnStatus(poll, own[poll.id])) {
                                PollOwnStatus.OpenForYou -> container.statusBadge(tr("Offen für Sie"), "info")
                                PollOwnStatus.Answered -> container.span(gettext("Beantwortet")) { addCssClasses("text-muted small") }
                                PollOwnStatus.None -> Unit
                            }
                        },
                    ),
                    textColumn(title = tr("Anzahl der Antworten"), numeric = true) {
                        if (showsResponseCount(it)) pollAnswersLabel(it.responseCount ?: 0) else ""
                    },
                ),
            rows = polls,
            actions = { container, poll ->
                container
                    .tableActionButton("fas fa-eye", gettext("Umfrage öffnen"))
                    .onClick { navigateTo("/polls/${poll.id}") }
            },
        )
        moreHost.removeAll()
        if (hasMore && polls.size < PollRules.MAX_LIST_OFFSET) {
            val more = moreHost.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY)
            more.onClick {
                runGuardedAction(more) {
                    val next = loadPollList(status, polls.size)
                    if (next != null) {
                        polls += next.polls
                        own += next.own
                        hasMore = next.hasMore
                        draw()
                    }
                }
            }
        }
    }
    draw()
}
