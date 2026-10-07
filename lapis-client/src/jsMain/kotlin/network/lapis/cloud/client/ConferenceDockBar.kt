package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/*
 * V1.9.70 -- the mini bar of the conference dock: what the person sees while a conference runs and its view is not on screen.
 *
 * It shows ONLY what the person must know and can do from anywhere: that a conference is running, what is being recorded or streamed (the
 * consent display must not disappear with the view), whether microphone and camera are on, and "leave". No room title, no names, no room
 * id (the dock keeps those in the view). No moderation, no animation, no Escape handling, no shortcut. It never steals the focus.
 *
 * The pure part ([dockBarViewOf], [dockBarAnnouncement]) is covered by jsTests; the widget only paints it.
 */

/** The bar is two rows below this width (badges above the controls). */
internal const val DOCK_NARROW_MAX_WIDTH_PX = 480

internal data class DockBarView(
    val visible: Boolean,
    /** Resolve with [resolvedAttributeText] before it goes into an attribute. */
    val statusText: String,
    val accessibleName: String,
    /** Glyph and text, never colour alone ("● Aufzeichnung", "◆ Live-Stream", "◆ Live-Stream pausiert"). */
    val consentBadges: List<String>,
    val voteBadge: Boolean,
    val screenShare: Boolean,
    val showDevices: Boolean,
    val showLeave: Boolean,
    val micPressed: Boolean,
    val cameraPressed: Boolean,
    val stopped: DockStopReason?,
    val twoRows: Boolean,
)

internal fun dockStopText(reason: DockStopReason): String =
    when (reason) {
        DockStopReason.ENDED -> tr("Die Besprechung wurde beendet oder die Verbindung getrennt.")
        DockStopReason.DUPLICATE_IDENTITY -> tr("Dieses Konto ist auf einem anderen Gerät verbunden.")
        DockStopReason.REJOIN_EXHAUSTED -> tr("Die Verbindung wurde mehrmals getrennt.")
        DockStopReason.CONNECT_FAILED -> tr("Die Verbindung zur Besprechung konnte nicht hergestellt werden.")
    }

/** Pure: the paint instructions of the bar for [state]. [narrow]: viewport below [DOCK_NARROW_MAX_WIDTH_PX]. */
internal fun dockBarViewOf(
    state: DockState,
    narrow: Boolean,
): DockBarView {
    val hidden =
        DockBarView(
            visible = false,
            statusText = "",
            accessibleName = "",
            consentBadges = emptyList(),
            voteBadge = false,
            screenShare = false,
            showDevices = false,
            showLeave = false,
            micPressed = false,
            cameraPressed = false,
            stopped = null,
            twoRows = false,
        )
    if (state is DockState.Idle || state.isAttached) return hidden
    val snapshot = state.snapshotOrNull
    val status =
        when (state) {
            is DockState.Joining -> tr("Verbindung wird hergestellt …")
            is DockState.Live -> if (state.snapshot.transitioning != null) tr("Verbindung wird hergestellt …") else tr("Besprechung läuft")
            is DockState.Resolving -> tr("Verbindung wird gewechselt …")
            is DockState.Stopped -> dockStopText(state.reason)
            is DockState.Idle -> ""
        }
    val badges =
        buildList {
            if (snapshot?.recording == true) add(tr("● Aufzeichnung"))
            if (snapshot?.streaming == true) add(if (snapshot.streamPaused) tr("◆ Live-Stream pausiert") else tr("◆ Live-Stream"))
        }
    val voteBadge = snapshot?.voteOpen == true
    val screenShare = snapshot?.screenSharing == true
    val live = state is DockState.Live
    return hidden.copy(
        visible = true,
        statusText = status,
        accessibleName = gettext("Zur Konferenz: %1", resolvedAttributeText(status)),
        consentBadges = badges,
        voteBadge = voteBadge,
        screenShare = screenShare && live,
        showDevices = live,
        showLeave = state is DockState.Joining || live,
        micPressed = snapshot?.micOn == true,
        cameraPressed = snapshot?.cameraOn == true,
        stopped = (state as? DockState.Stopped)?.reason,
        twoRows = narrow && (badges.isNotEmpty() || voteBadge || (screenShare && live)),
    )
}

internal enum class DockAnnouncementKind { POLITE, ALERT }

internal data class DockAnnouncement(
    val kind: DockAnnouncementKind,
    val text: String,
)

/**
 * Pure: what the bar announces for the step [prev] -> [next]. Only while the view is not on screen (when it is, the view announces
 * itself). Order of priority: a change of recording / stream (alert, every change), an involuntary end (alert or status), the one polite
 * note when the view goes away for the first time in this call ([alreadyAnnounced] = false). Switching the microphone or the camera is
 * never announced -- the person did it.
 */
internal fun dockBarAnnouncement(
    prev: DockState,
    next: DockState,
    alreadyAnnounced: Boolean,
): DockAnnouncement? {
    if (next is DockState.Idle || next.isAttached) return null
    val consent = consentChanges(prev.snapshotOrNull, next.snapshotOrNull)
    if (consent.isNotEmpty()) return DockAnnouncement(DockAnnouncementKind.ALERT, consent.joinToString(" "))
    if (next is DockState.Stopped && prev !is DockState.Stopped) {
        val text = resolvedAttributeText(dockStopText(next.reason))
        val kind =
            when (next.reason) {
                DockStopReason.DUPLICATE_IDENTITY, DockStopReason.REJOIN_EXHAUSTED -> DockAnnouncementKind.ALERT
                DockStopReason.ENDED, DockStopReason.CONNECT_FAILED -> DockAnnouncementKind.POLITE
            }
        return DockAnnouncement(kind, text)
    }
    if (!alreadyAnnounced && prev !is DockState.Idle && prev.isAttached) {
        val snapshot = next.snapshotOrNull
        val devices = snapshot != null && (snapshot.micOn || snapshot.cameraOn)
        val base = resolvedAttributeText(tr("Die Besprechung läuft im Hintergrund weiter."))
        val text =
            if (devices) base + " " + resolvedAttributeText(tr("Mikrofon oder Kamera sind eingeschaltet.")) else base
        return DockAnnouncement(DockAnnouncementKind.POLITE, text)
    }
    return null
}

private fun consentChanges(
    before: DockSnapshot?,
    after: DockSnapshot?,
): List<String> {
    if (after == null) return emptyList()
    val result = mutableListOf<String>()
    val wasRecording = before?.recording ?: false
    if (wasRecording != after.recording) {
        if (after.recording) {
            result += resolvedAttributeText(tr("Aufzeichnung läuft"))
        } else if (before != null) {
            result += resolvedAttributeText(tr("Aufzeichnung beendet"))
        }
    }
    val wasStreaming = before?.streaming ?: false
    if (wasStreaming != after.streaming) {
        if (after.streaming) {
            result += resolvedAttributeText(tr("Live-Stream läuft"))
        } else if (before != null) {
            result += resolvedAttributeText(tr("Live-Stream beendet"))
        }
    } else if (after.streaming && (before?.streamPaused ?: false) != after.streamPaused) {
        result +=
            resolvedAttributeText(
                if (after.streamPaused) tr("Live-Stream pausiert") else tr("Live-Stream fortgesetzt"),
            )
    }
    return result
}

/** `Widget.setAttribute` re-renders on every call; only a real change is written. */
private fun Widget.setAttrIfChanged(
    name: String,
    value: String,
) {
    if (getAttribute(name) != value) setAttribute(name, value)
}

private fun focusMain() {
    (document.getElementById("lapis-content") as? HTMLElement)?.focus()
}

/** "Zur Konferenz": navigation only, never a join. The focus moves into the view after it was rendered ([ConferenceDock.attachView]). */
internal fun conferenceDockReturnToView() {
    ConferenceDock.pendingFocusFromBar = true
    navigateTo(Routes.CONFERENCE)
}

/**
 * The bar, built once and mounted as a direct child of the shell (after `.lapis-content`, before the version banner). It subscribes to
 * [ConferenceDock] when it is inserted and unsubscribes when it is destroyed.
 */
internal fun Container.conferenceDockBar(): Div {
    val bar =
        Div(className = "lapis-conference-dock-bar $DOCK_HIDDEN_CLASS") {
            setAttribute("role", "region")
            setAttribute("aria-label", gettext("Leiste der laufenden Besprechung"))
        }
    val returnButton = Button("", style = ButtonStyle.LIGHT, className = "lapis-dock-return")
    val statusSpan = Span(className = "lapis-dock-status text-truncate")
    val actionSpan = Span(className = "lapis-dock-return-label d-none d-md-inline")
    val chevron = Span(className = "fas fa-chevron-up")
    chevron.setAttribute("aria-hidden", "true")
    returnButton.add(statusSpan)
    returnButton.add(actionSpan)
    returnButton.add(chevron)
    bar.add(returnButton)
    returnButton.onClick { conferenceDockReturnToView() }

    val badges = Div(className = "lapis-dock-badges")
    bar.add(badges)
    val controls =
        bar.conferenceDockBarControls(
            onMic = { ConferenceDock.session?.toggleMic() },
            onCamera = { ConferenceDock.session?.toggleCamera() },
            onStopShare = { ConferenceDock.session?.stopScreenShare() },
            onLeave = {
                // a secret-ballot receipt exists only in the view: leaving from here would lose it, so the person is taken there
                if (ConferenceReceiptGate.blocksUnload) {
                    conferenceDockReturnToView()
                } else {
                    ConferenceDock.leaveFromBar()
                }
            },
        )
    val stoppedActions = Div(className = "lapis-dock-stopped-actions")
    val toView = stoppedActions.actionButton(ActionIcon.ENTER, tr("Zur Konferenz"), ButtonStyle.PRIMARY)
    val toOverview = stoppedActions.actionButton(ActionIcon.BACK, tr("Zur Übersicht"), ButtonStyle.OUTLINESECONDARY)
    val close = stoppedActions.actionButton(ActionIcon.CLOSE, tr("Schließen"), ButtonStyle.OUTLINESECONDARY)
    bar.add(stoppedActions)
    toView.onClick { conferenceDockReturnToView() }
    toOverview.onClick {
        ConferenceDock.dismiss()
        navigateTo(Routes.CONFERENCE)
    }
    close.onClick {
        ConferenceDock.dismiss()
        focusMain()
    }

    // two visually hidden live regions: polite for status, alert for consent changes and involuntary ends
    val politeRegion =
        Div(className = "visually-hidden") {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            setAttribute("aria-atomic", "true")
        }
    val alertRegion =
        Div(className = "visually-hidden") {
            setAttribute("role", "alert")
            setAttribute("aria-atomic", "true")
        }
    bar.add(politeRegion)
    bar.add(alertRegion)

    val narrowQuery = window.matchMedia("(max-width: ${DOCK_NARROW_MAX_WIDTH_PX - 0.02}px)")
    var previous: DockState = ConferenceDock.state
    var shownBadges: List<String> = emptyList()
    var shownVote = false
    var shownShare = false
    var wasVisible = false

    fun paint(
        state: DockState,
        announce: Boolean,
    ) {
        val view = dockBarViewOf(state, narrowQuery.matches)
        val root = document.documentElement
        if (!view.visible) {
            if (!bar.hasCssClass(DOCK_HIDDEN_CLASS)) bar.addCssClass(DOCK_HIDDEN_CLASS)
            root?.classList?.remove(DOCK_TWO_ROWS_CLASS)
            (root as? HTMLElement)?.style?.removeProperty("--lapis-dock-h")
            if (wasVisible) {
                // the bar the focus was in is gone: the focus goes to the page, not to the top of the document
                val active = document.activeElement
                if (active == null || active === document.body || bar.getElement()?.contains(active) == true) focusMain()
            }
            wasVisible = false
        } else {
            if (bar.hasCssClass(DOCK_HIDDEN_CLASS)) bar.removeCssClass(DOCK_HIDDEN_CLASS)
            wasVisible = true
            if (view.twoRows) root?.classList?.add(DOCK_TWO_ROWS_CLASS) else root?.classList?.remove(DOCK_TWO_ROWS_CLASS)
            val stopped = view.stopped != null
            val statusText = view.statusText
            statusSpan.content = statusText
            actionSpan.content = tr("Zur Konferenz")
            returnButton.setAttrIfChanged("aria-label", view.accessibleName)
            returnButton.setAttrIfChanged("title", view.accessibleName)
            if (stopped) returnButton.hide() else returnButton.show()
            if (stopped) stoppedActions.show() else stoppedActions.hide()
            when (view.stopped) {
                DockStopReason.DUPLICATE_IDENTITY, DockStopReason.REJOIN_EXHAUSTED -> {
                    toView.show()
                    toOverview.hide()
                }
                DockStopReason.ENDED, DockStopReason.CONNECT_FAILED -> {
                    toView.hide()
                    toOverview.show()
                }
                null -> Unit
            }
            if (view.consentBadges != shownBadges || view.voteBadge != shownVote || view.screenShare != shownShare) {
                shownBadges = view.consentBadges
                shownVote = view.voteBadge
                shownShare = view.screenShare
                badges.removeAll()
                view.consentBadges.forEach { badges.statusBadge(it, "danger") }
                if (view.voteBadge) badges.statusBadge(tr("Abstimmung läuft"), "info")
                if (view.screenShare) badges.statusBadge(tr("Bildschirmfreigabe"), "secondary")
            }
            if (view.consentBadges.isEmpty() && !view.voteBadge && !view.screenShare) badges.hide() else badges.show()
            if (view.showDevices) {
                controls.root.show()
            } else if (view.showLeave) {
                controls.root.show()
            } else {
                controls.root.hide()
            }
            if (view.showDevices) {
                controls.mic.show()
                controls.camera.show()
                controls.applyDevices(view.micPressed, view.cameraPressed)
            } else {
                controls.mic.hide()
                controls.camera.hide()
            }
            if (view.screenShare) controls.stopShare.show() else controls.stopShare.hide()
            if (view.showLeave) controls.leave.show() else controls.leave.hide()
            // the page reserves what the bar really measures (badges may wrap in a long locale on a narrow phone), one tick after the patch
            window.setTimeout({
                val height = bar.getElement()?.offsetHeight ?: 0
                if (height > 0) (document.documentElement as? HTMLElement)?.style?.setProperty("--lapis-dock-h", "${height}px")
            }, 0)
        }
        if (announce) {
            val announcement = dockBarAnnouncement(previous, state, ConferenceDock.announcedFirstDetach)
            if (announcement != null) {
                val announcedText = announcement.text
                if (announcement.kind == DockAnnouncementKind.ALERT) {
                    alertRegion.content = announcedText
                } else {
                    politeRegion.content = announcedText
                    if (previous.isAttached && previous !is DockState.Idle && !state.isAttached && state !is DockState.Stopped) {
                        ConferenceDock.announcedFirstDetach = true
                    }
                }
            }
        }
        previous = state
    }

    var unobserve: (() -> Unit)? = null
    val narrowListener: (Event) -> Unit = { paint(ConferenceDock.state, announce = false) }
    return addWithLifecycle(
        bar,
        onInsert = {
            unobserve?.invoke()
            unobserve = ConferenceDock.observe { state -> paint(state, announce = true) }
            narrowQuery.addEventListener("change", narrowListener)
            previous = ConferenceDock.state
            // NOT synchronously: changing the bar's own classes inside its insert hook re-patches the vnode that is still being inserted,
            // which fires this hook again (endless recursion). The first paint runs right after the insertion has finished.
            window.setTimeout({ paint(ConferenceDock.state, announce = false) }, 0)
        },
        onDestroy = {
            unobserve?.invoke()
            unobserve = null
            narrowQuery.removeEventListener("change", narrowListener)
        },
    )
}
