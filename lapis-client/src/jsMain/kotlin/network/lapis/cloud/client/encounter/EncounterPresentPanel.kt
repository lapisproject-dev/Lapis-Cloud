package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.confirmDialog
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.notifySuccess
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.untrustedDiv
import network.lapis.cloud.client.untrustedSpan
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService

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
) {
    val root: Div = parent.div(className = "lapis-encounter-present")
    private var content: SimplePanel? = null
    private var people: List<EncounterPresentDto> = emptyList()
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
        val loaded =
            try {
                rpcService<IEncounterSpaceService>().listPresent(spaceId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return false
            }
        accept(loaded)
        content?.let {
            it.removeAll()
            render(it, loaded)
        }
        return true
    }

    /** Re-renders with the current hands (a hand went up or down). */
    fun rerender() {
        content?.let {
            it.removeAll()
            render(it, people)
        }
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
        panel.h2(tr("In diesem Raum")) { addCssClass("h6") }
        panel.div(gettext("%1 anwesend", loaded.size), className = "text-muted small")
        loaded.forEach { person -> renderPerson(panel, person) }
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
