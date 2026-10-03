package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.panel.SimplePanel

/**
 * V1.9.51 -- the lobby of the conference screen and the action in its title row ("Besprechung jetzt starten") are shown and hidden
 * TOGETHER.
 *
 * The page header outlives [renderLobby]'s panel: entering a call only hides the lobby panel, the header stays on screen. Without this
 * coupling the start button would stay visible during a call and a second meeting could be started from inside the first one. Every
 * place that shows or hides the lobby therefore goes through [setConferenceLobbyVisible] (tripwire in `ClientUiGuidelineTripwireTest`).
 *
 * The pairing lives in a JS `WeakMap` keyed by the lobby panel: no new field on the widget and no leak when the screen is torn down.
 * A panel without a registered action (the federated-guest lobby has none) is simply shown or hidden on its own.
 */
private val headerActionByLobby: dynamic = js("new WeakMap()")

/** Pairs [headerAction] (the title-row slot) with [lobbyPanel]; a later registration for the same panel replaces the earlier one. */
internal fun registerConferenceLobbyHeaderAction(
    lobbyPanel: SimplePanel,
    headerAction: Container,
) {
    headerActionByLobby.set(lobbyPanel, headerAction)
}

/** Shows or hides [lobbyPanel] together with the title-row action registered for it (if any). */
internal fun setConferenceLobbyVisible(
    lobbyPanel: SimplePanel,
    visible: Boolean,
) {
    if (visible) lobbyPanel.show() else lobbyPanel.hide()
    val action = headerActionByLobby.get(lobbyPanel) as? Widget ?: return
    if (visible) action.show() else action.hide()
}
