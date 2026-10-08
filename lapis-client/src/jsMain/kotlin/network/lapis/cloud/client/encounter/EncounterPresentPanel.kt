package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.confirmDialog
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.notifySuccess
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.untrustedDiv
import network.lapis.cloud.client.untrustedSpan
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.encounterSeatPosition
import network.lapis.cloud.shared.domain.encounterSeatRow
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement

/**
 * V1.9.62 Begegnungsraum (B2) -- the "Anwesende" tab: who is in the room NOW (names only, no time, no history -- the server returns the
 * live presence list and nothing else) and, for people who moderate, the moderation menu and the raised hands.
 *
 * - The first load goes through the shared data state ([dataSection]: loading, error with retry, content); later refreshes replace the
 *   content in place so the list never flashes empty. Refreshes are requested by the room (a timer every 20 s, a roster event at most
 *   once per 5 s, only while the tab is visible -- the `list` rate budget of the server is shared with `getSpace`/`getEntryInfo`).
 * - The guest marker "Gast" is shown ONLY to people who moderate (the server sends `isGuest` to everybody present -- see the known
 *   limitations in the CHANGELOG -- but the page does not display it to the congregation).
 * - The menu ("Stummschalten", "Entfernen") appears only where [encounterCanActOn] says so; the server stays the authority and answers
 *   a refused action with a plain, fixed sentence.
 * - Raised hands are listed, in the order they went up, ONLY for office holders and BOARD/ADMIN (the congregation sees seats, not a queue).
 */
internal class EncounterPresentPanel(
    parent: Container,
    private val spaceId: String,
    private val terms: EncounterTerms,
    private val viewer: EncounterViewerRights,
    private val raisedHands: () -> List<String>,
    private val onRoster: (List<EncounterPresentDto>) -> Unit,
    // Called right before a confirmation dialog opens: the room leaves the full screen so the dialog (mounted at body level) is visible.
    private val beforeDialog: () -> Unit = {},
    // V1.9.79: the seat list alternative (BR-E2) for people who do not use the picture. `null` = nothing to offer (an office holder cannot sit).
    private val seatList: () -> EncounterSeatListState? = { null },
    private val onChooseSeat: (Int) -> Unit = {},
    private val onReleaseSeat: () -> Unit = {},
) {
    val root: Div = parent.div(className = "lapis-encounter-present")
    private var content: SimplePanel? = null
    private var people: List<EncounterPresentDto> = emptyList()
    private var seatListOpen = false

    /** Counts the lists that arrived from elsewhere; a `listPresent` that started under an older count is stale when it answers. */
    private var generation = 0
    private val section =
        root.dataSection<List<EncounterPresentDto>>(
            emptyText = tr("Niemand ist anwesend."),
            isEmpty = { false },
            onSettled = { loaded -> loaded?.let(::accept) },
            load = { guarded { rpcService<IEncounterSpaceService>().listPresent(spaceId) } },
        ) { panel, loaded ->
            content = panel
            render(panel, loaded)
        }

    fun load() = section.reload()

    /** Replaces the list quietly (no loading state); an error leaves the previous list standing. Returns whether it worked. */
    suspend fun refresh(): Boolean {
        val startedAt = generation
        val loaded =
            try {
                rpcService<IEncounterSpaceService>().listPresent(spaceId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return false
            }
        // An answer to a request that started before a newer picture arrived (the answer of `selectSeat`) is older than that picture: drop it.
        if (startedAt != generation) return true
        accept(loaded)
        rebuild(loaded)
        return true
    }

    /** V1.9.79: takes a list that came from elsewhere (the answer of `selectSeat`), exactly like a refresh. */
    fun replace(loaded: List<EncounterPresentDto>) {
        generation++
        accept(loaded)
        rebuild(loaded)
    }

    /** Re-renders with the current hands (a hand went up or down). */
    fun rerender() {
        rebuild(people)
    }

    /** Rebuilds the content and puts the keyboard focus back on the seat button that had it (the list is rebuilt on every refresh). */
    private fun rebuild(loaded: List<EncounterPresentDto>) {
        val panel = content ?: return
        val focused = (document.activeElement as? HTMLElement)?.getAttribute("data-list-seat")
        panel.removeAll()
        render(panel, loaded)
        if (focused != null) restoreFocus(focused)
    }

    private fun restoreFocus(key: String) {
        var attempts = 0

        fun attempt() {
            val target = root.getElement()?.querySelector("[data-list-seat=\"$key\"]") as? HTMLElement
            if (target != null) {
                target.focus()
            } else if (attempts++ < FOCUS_RETRIES) {
                window.setTimeout({ attempt() }, FOCUS_RETRY_MS)
            }
        }
        attempt()
    }

    private fun accept(loaded: List<EncounterPresentDto>) {
        people = loaded
        onRoster(loaded)
    }

    private fun render(
        panel: SimplePanel,
        loaded: List<EncounterPresentDto>,
    ) {
        if (viewer.canModerate) renderRaisedHands(panel, loaded)
        renderSeatList(panel)
        panel.h2(tr("In diesem Raum")) { addCssClass("h6") }
        panel.div(gettext("%1 anwesend", loaded.size), className = "text-muted small")
        loaded.forEach { person -> renderPerson(panel, person) }
    }

    /**
     * V1.9.79 (BR-E2): "Platz über eine Liste wählen" -- the same choice as the seat plan, as a `<details>` of plain buttons for people who
     * do not use the picture. Only FREE seats, per row a labelled group; no names and no taken seats. The disclosure state survives the
     * rebuilds of the panel.
     */
    private fun renderSeatList(panel: SimplePanel) {
        val state = seatList() ?: return
        val details = Tag(TAG.DETAILS, className = "lapis-encounter-seat-list")
        if (seatListOpen) details.setAttribute("open", "")
        // A click on the summary (also Enter/Space on it) flips the disclosure: remember it for the next rebuild.
        val summary = Tag(TAG.SUMMARY, content = terms.chooseFromListSummary())
        summary.onClick { seatListOpen = !seatListOpen }
        details.add(summary)
        val status =
            state.ownSeat?.let { terms.yourSeatStatus(encounterSeatRow(it), encounterSeatPosition(it)) } ?: terms.noSeatYetStatus()
        details.add(Div(content = status, className = "small text-muted my-1"))
        if (state.ownSeat != null) {
            val release =
                newActionButton(ActionIcon.RELEASE_SEAT, terms.releaseSeatLabelContent(), ButtonStyle.OUTLINESECONDARY, small = true)
            release.setAttribute("data-list-seat", "release")
            release.onClick { onReleaseSeat() }
            details.add(release)
        }
        if (state.freeSeats.isEmpty()) {
            if (state.ownSeat == null) details.add(Div(content = terms.noFreeSeatNote(), className = "small"))
        } else {
            state.freeSeats.groupBy { encounterSeatRow(it) }.forEach { (row, seats) ->
                val group = Div(className = "lapis-encounter-seat-list-row")
                group.setAttribute("role", "group")
                group.setAttribute("aria-label", gettext("Reihe %1", row))
                seats.forEach { seat ->
                    val button = Tag(TAG.BUTTON, className = "btn btn-outline-secondary lapis-encounter-seat-list-button")
                    button.setAttribute("type", "button")
                    button.setAttribute("data-list-seat", seat.toString())
                    button.setAttribute("aria-label", terms.listSeatButtonLabel(row, encounterSeatPosition(seat)))
                    button.content = encounterSeatPosition(seat).toString()
                    button.onClick { onChooseSeat(seat) }
                    group.add(button)
                }
                details.add(group)
            }
        }
        panel.add(details)
    }

    private fun renderRaisedHands(
        panel: SimplePanel,
        loaded: List<EncounterPresentDto>,
    ) {
        val byId = loaded.associateBy { it.memberId }
        val raised = raisedHands().mapNotNull { byId[it] }
        if (raised.isEmpty()) return
        panel.h2(tr("Erhobene Hände")) { addCssClass("h6") }
        val list = panel.vPanel(spacing = 2)
        list.setAttribute("role", "list")
        raised.forEach { person ->
            val row = list.div(className = "lapis-encounter-hand-row")
            row.setAttribute("role", "listitem")
            row.untrustedSpan(person.displayName)
        }
    }

    private fun renderPerson(
        panel: SimplePanel,
        person: EncounterPresentDto,
    ) {
        val row = panel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center lapis-encounter-person") }
        val text = row.vPanel(spacing = 0) { addCssClass("flex-grow-1") }
        text.untrustedDiv(person.displayName)
        val facts = encounterPersonFacts(person, showGuestMarker = viewer.canModerate, profile = terms.profile)
        if (facts.isNotEmpty()) text.div(facts, className = "text-muted small")
        if (!encounterCanActOn(viewer = viewer, target = person)) return
        row.actionButton(ActionIcon.SILENCE, tr("Stummschalten"), style = ButtonStyle.OUTLINESECONDARY, small = true).onClick {
            moderate(successMessage = tr("Die Person wurde stummgeschaltet.")) {
                rpcService<IEncounterSpaceService>().silenceInSpace(spaceId, person.memberId)
            }
        }
        row.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER, small = true).onClick {
            beforeDialog()
            confirmDialog(
                title = tr("Diese Person entfernen?"),
                message = terms.removalWarningContent(),
                confirmLabel = tr("Entfernen"),
                confirmIcon = ActionIcon.REMOVE,
            ) {
                moderate(successMessage = tr("Die Person wurde entfernt.")) {
                    rpcService<IEncounterSpaceService>().removeFromSpace(spaceId, person.memberId)
                }
            }
        }
    }

    private fun moderate(
        successMessage: String,
        action: suspend () -> Unit,
    ) {
        AppScope.launch {
            val done =
                guarded {
                    try {
                        action()
                        true
                    } catch (e: ForbiddenException) {
                        // An office holder may act against the congregation only; the server refuses another office holder and BOARD/ADMIN.
                        notifyError(tr("Diese Person können Sie hier nicht entfernen."))
                        false
                    }
                }
            if (done == true) {
                notifySuccess(successMessage)
                refresh()
            }
        }
    }
}

private const val FOCUS_RETRIES = 30
private const val FOCUS_RETRY_MS = 16

/** What the seat list alternative needs: the viewer's own seat (if any) and every free seat of the drawn grid. */
internal class EncounterSeatListState(
    val ownSeat: Int?,
    val freeSeats: List<Int>,
)

/** The muted second line of a person: the role (office holders only -- the congregation needs no label) and, for moderators, the guest marker. */
internal fun encounterPersonFacts(
    person: EncounterPresentDto,
    showGuestMarker: Boolean,
    profile: EncounterProfile,
): String =
    listOfNotNull(
        if (person.role !=
            network.lapis.cloud.shared.domain.EncounterPresenceRole.CONGREGATION
        ) {
            encounterPresenceRoleLabel(person.role, profile)
        } else {
            null
        },
        if (showGuestMarker && person.isGuest) gettext("Gast") else null,
    ).joinToString(" · ")
