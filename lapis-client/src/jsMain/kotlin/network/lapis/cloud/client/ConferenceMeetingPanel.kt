package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Icon
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingSessionNextStep
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MemberPickPageDto
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.domain.SessionRosterEntryDto
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import org.w3c.dom.HTMLElement

/**
 * V1.9.236 "Sitzungsmodus im Konferenzraum" -- the panel next to "Abstimmen". Four sections one under the other: head (phase, opening, the
 * lead's next step), quorum (live calculation, established line), agenda (an ordered list, the current item marked), attendance (LEAD and
 * MINUTES only). What the caller sees is decided by the server (`callerRole`); this file only never draws what the answer does not contain,
 * so a PARTICIPANT's DOM holds no name and no roster at all.
 *
 * The quiet rule: [ConferenceMeetingPanelHandle.apply] rebuilds a section only when ITS slice of the state changed, so a poll that brings
 * nothing new moves nothing under the member's hand. State is conveyed by symbol AND text, never by colour alone. The panel is a plain
 * flow panel in normal view and a bottom sheet on a narrow screen (`theme.css`, `.conference-meeting-panel`).
 */
internal class ConferenceMeetingPanelHandle(
    val panel: SimplePanel,
    val apply: (ConferenceMeetingStateDto?) -> Unit,
    val applyFailure: (Int) -> Unit,
    val setOpen: (Boolean) -> Unit,
    val isOpen: () -> Boolean,
    val dispose: () -> Unit,
)

/** The roster as the attendance section's slice sees it: who is in the room (`connected`) is patched in place and does not rebuild the section. */
private fun attendanceRosterSlice(state: ConferenceMeetingStateDto): List<SessionRosterEntryDto> =
    state.roster.map { it.copy(connected = false) }

// `lapis-conference-voting` gives the panel the geometry of the voting panel (flow / fullscreen rail / bottom sheet) without a new scroll surface
private const val PANEL_CSS = "border rounded p-2 lapis-conference-voting conference-meeting-panel"

internal fun renderConferenceMeetingPanel(
    parent: Container,
    roomId: String,
    rpc: ConferenceMeetingRpc = ConferenceMeetingRpc(),
    guestSearch: (committeeId: String) -> (suspend (String, Int) -> MemberPickPageDto?) = ::committeeCandidateSearch,
    onCloseRequested: () -> Unit = {},
    onFreshState: (ConferenceMeetingStateDto) -> Unit = {},
    onStaleState: () -> Unit = {},
    onOpenVotingPanel: () -> Unit = {},
    protocolRpc: ProtocolDraftRpc = ProtocolDraftRpc(),
    protocolFinalRpc: MeetingProtocolRpc = MeetingProtocolRpc(),
): ConferenceMeetingPanelHandle {
    val panel = parent.vPanel(spacing = 6) { addCssClasses(PANEL_CSS) }
    panel.hide()
    panel.setAttribute("role", "region")
    panel.setAttribute("aria-label", gettext("Sitzung"))

    val header = panel.hPanel(spacing = 6) { addCssClasses("align-items-center justify-content-between") }
    header.h2(tr("Sitzung")) { addCssClasses("h6 mb-0") }
    header.actionButton(ActionIcon.CLOSE, tr("Sitzung schließen"), style = ButtonStyle.OUTLINESECONDARY) {
        addCssClass("btn-sm")
        onClick { onCloseRequested() }
    }
    val liveRegion =
        panel.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val note = panel.div("") { addCssClasses("text-muted small conference-meeting-note") }
    note.hide()
    val refreshHint = panel.div(tr("Status wird aktualisiert …")) { addCssClasses("text-muted small") }
    refreshHint.hide()
    // V1.9.239: two tabs -- the Sitzung itself (opening, agenda, attendance) and the Protokoll draft. Both are visible to every role of the Sitzung.
    val tabBar = panel.hPanel(spacing = 4) { addCssClass("conference-meeting-tabs") }
    tabBar.setAttribute("role", "tablist")
    tabBar.setAttribute("aria-label", gettext("Sitzung"))
    val sessionTab = Button(text = tr("Sitzung"), style = ButtonStyle.OUTLINESECONDARY)
    val protocolTab = Button(text = tr("Protokoll"), style = ButtonStyle.OUTLINESECONDARY)
    listOf(sessionTab, protocolTab).forEach { tab ->
        tab.addCssClasses("btn-sm conference-meeting-tab")
        tab.setAttribute("role", "tab")
        tabBar.add(tab)
    }
    tabBar.hide()
    val body = panel.vPanel(spacing = 8) { addCssClass("conference-meeting-body") }
    val protocolHost = panel.vPanel(spacing = 4) { addCssClass("conference-meeting-protocol") }
    protocolHost.hide()
    // V1.9.238: the running vote, a polite live region at the very top (a vote that opens is announced)
    val bannerSection = body.vPanel(spacing = 4) { addCssClass("conference-meeting-banner-host") }
    bannerSection.setAttribute("aria-live", "polite")
    val headSection = body.vPanel(spacing = 4) { addCssClass("conference-meeting-head") }
    val quorumSection = body.vPanel(spacing = 4) { addCssClass("conference-meeting-quorum") }
    val agendaSection = body.vPanel(spacing = 4) { addCssClass("conference-meeting-agenda") }
    val attendanceSection = body.vPanel(spacing = 4) { addCssClass("conference-meeting-attendance") }
    body.hide()

    var current: ConferenceMeetingStateDto? = null
    var busy = false
    var disposed = false
    var open = false
    var bannerSlice: Any? = null
    var headSlice: Any? = null
    var quorumSlice: Any? = null
    var agendaSlice: Any? = null
    var attendanceSlice: Any? = null
    // V1.9.242: a section is never rebuilt while a control in it has the focus or a pointer is down in it (see SectionGuard); the drawing waits
    // and uses the NEWEST state when it finally runs.
    var latestState: ConferenceMeetingStateDto? = null
    var forceAttendanceRedraw = false
    val guards = SectionGuards()
    lateinit var renderBanner: (ConferenceMeetingStateDto) -> Unit
    lateinit var renderHead: (ConferenceMeetingStateDto) -> Unit
    lateinit var renderQuorum: (ConferenceMeetingStateDto) -> Unit
    lateinit var renderAgenda: (ConferenceMeetingStateDto) -> Unit
    lateinit var renderAttendance: (ConferenceMeetingStateDto) -> Unit
    val bannerGuard = guards.add(SectionGuard({ bannerSection.getElement() }) { latestState?.let { renderBanner(it) } })
    val headGuard = guards.add(SectionGuard({ headSection.getElement() }) { latestState?.let { renderHead(it) } })
    val quorumGuard = guards.add(SectionGuard({ quorumSection.getElement() }) { latestState?.let { renderQuorum(it) } })
    val agendaGuard = guards.add(SectionGuard({ agendaSection.getElement() }) { latestState?.let { renderAgenda(it) } })
    val attendanceGuard = guards.add(SectionGuard({ attendanceSection.getElement() }) { latestState?.let { renderAttendance(it) } })
    var protocolTabActive = false
    var protocolHandle: ProtocolDraftSectionHandle? = null
    var protocolFinalHandle: ProtocolFinalHandle? = null
    var protocolMeetingId: String? = null

    fun paintTabs() {
        val hasState = current != null
        if (hasState) tabBar.show() else tabBar.hide()
        sessionTab.setAttribute("aria-selected", (!protocolTabActive).toString())
        protocolTab.setAttribute("aria-selected", protocolTabActive.toString())
        if (protocolTabActive) {
            sessionTab.removeCssClass("active")
            protocolTab.addCssClass("active")
        } else {
            protocolTab.removeCssClass("active")
            sessionTab.addCssClass("active")
        }
        if (hasState && !protocolTabActive) body.show() else body.hide()
        if (hasState && protocolTabActive) protocolHost.show() else protocolHost.hide()
        // The section is built the first time its tab is shown (the Sitzung tab never carries its markup), and read only while that tab is visible.
        val state = current
        if (state != null && protocolTabActive && protocolHandle == null) {
            protocolMeetingId = state.meetingId
            // V1.9.240: finalising / confirming / correcting sit above the draft; an action of theirs makes the draft read again at once
            protocolFinalHandle =
                renderProtocolFinalSection(
                    parent = protocolHost,
                    meetingId = state.meetingId,
                    rpc = protocolFinalRpc,
                    onChanged = { protocolHandle?.refreshNow?.invoke() },
                    onJumpToDraft = { protocolHandle?.focusFirstField?.invoke() },
                )
            protocolHandle =
                renderConferenceProtocolDraftSection(
                    parent = protocolHost,
                    meetingId = state.meetingId,
                    rpc = protocolRpc,
                    finalRpc = protocolFinalRpc,
                )
        }
        protocolHandle?.setActive(hasState && protocolTabActive && open)
        if (hasState && protocolTabActive) protocolFinalHandle?.reload?.invoke()
    }

    /** Forgets every waiting drawing and makes the next state draw those sections again (their slices no longer describe the DOM). */
    fun dropPending() {
        if (bannerGuard.pending) bannerSlice = null
        if (headGuard.pending) headSlice = null
        if (quorumGuard.pending) quorumSlice = null
        if (agendaGuard.pending) agendaSlice = null
        if (attendanceGuard.pending) attendanceSlice = null
        guards.dropAll()
    }

    // switching the tab hides the section, so nothing in it is in use any more: a waiting drawing is done at once instead of being lost
    fun flushPending() {
        guards.flushAll()
    }

    sessionTab.onClick {
        protocolTabActive = false
        paintTabs()
        flushPending()
    }
    protocolTab.onClick {
        protocolTabActive = true
        paintTabs()
        flushPending()
    }

    fun showNote(text: String?) {
        if (text == null) {
            note.hide()
        } else {
            note.content = text
            note.show()
        }
    }

    fun announce(text: String) {
        liveRegion.content = text
    }

    lateinit var motionActions: MeetingMotionActions

    /** Runs one mutation: one at a time, the answer (the fresh state) is rendered; a failure is a toast and a fresh read. */
    lateinit var applyState: (ConferenceMeetingStateDto?) -> Unit

    fun act(block: suspend () -> ConferenceMeetingStateDto) {
        if (busy || disposed) return
        busy = true
        showNote(null)
        AppScope.launch {
            var movedOn = false
            val answer =
                guarded {
                    try {
                        block()
                    } catch (e: AgendaMovedMeanwhileException) {
                        // only the stale-view conflict means "moved on" (a distinct type: the message never reaches the client)
                        movedOn = true
                        null
                    }
                }
            busy = false
            if (disposed) return@launch
            if (answer != null) {
                onFreshState(answer)
                applyState(answer)
            } else {
                if (movedOn) showNote(gettext("Der Tagesordnungspunkt hat sich inzwischen geändert."))
                // a rejected change leaves the displayed controls (a <select>) on the refused value: draw them again
                // (this redraw is forced past the guard: the select that was just used is the focused one, and a deferred drawing would
                // leave the refused value on display)
                attendanceSlice = null
                forceAttendanceRedraw = true
                onStaleState()
            }
        }
    }

    motionActions =
        MeetingMotionActions(roomId = roomId, rpc = rpc, act = { block ->
            act(block)
        }, latest = { current }, onOpenVotingPanel = onOpenVotingPanel)

    fun timeText(state: ConferenceMeetingStateDto): String = state.openedAt?.let { formatSystemTime(it) }.orEmpty()

    fun nextStepText(step: MeetingSessionNextStep?): String? =
        when (step) {
            MeetingSessionNextStep.OPEN_MEETING -> gettext("Nächster Schritt: Sitzung eröffnen")
            MeetingSessionNextStep.ADOPT_CONNECTED -> gettext("Nächster Schritt: Verbundene übernehmen")
            MeetingSessionNextStep.ESTABLISH_QUORUM -> gettext("Nächster Schritt: Beschlussfähigkeit feststellen")
            MeetingSessionNextStep.NEXT_AGENDA_ITEM -> gettext("Nächster Schritt: Nächster Tagesordnungspunkt")
            MeetingSessionNextStep.NONE, null -> null
        }

    fun drawHead(state: ConferenceMeetingStateDto) {
        headSection.removeAll()
        headSection.untrustedDiv(state.meetingTitle, className = "fw-semibold")
        headSection.div(className = "conference-meeting-phase") {
            when (state.phase) {
                MeetingSessionPhase.NOT_OPENED -> {
                    icon("far fa-circle") {
                        setAttribute("aria-hidden", "true")
                        addCssClass("me-1")
                    }
                    span(tr("Nicht eröffnet"))
                }
                MeetingSessionPhase.OPEN -> {
                    icon("fas fa-circle-play") {
                        setAttribute("aria-hidden", "true")
                        addCssClass("me-1")
                    }
                    span(gettext("Eröffnet um %1", timeText(state)))
                }
                MeetingSessionPhase.CLOSED -> {
                    icon("fas fa-flag-checkered") {
                        setAttribute("aria-hidden", "true")
                        addCssClass("me-1")
                    }
                    span(tr("Abgeschlossen"))
                }
            }
        }
        if (state.callerRole == MeetingSessionRole.LEAD) {
            nextStepText(state.nextStepHint)?.let { headSection.div(it) { addCssClasses("text-muted small conference-meeting-next") } }
            if (state.phase == MeetingSessionPhase.NOT_OPENED) {
                headSection.actionGroup {
                    actionButton(ActionIcon.ENTER, tr("Sitzung eröffnen"), style = ButtonStyle.PRIMARY) {
                        onClick { act { rpc.open(roomId, state.meetingId) } }
                    }
                }
            }
        }
    }

    fun drawQuorum(state: ConferenceMeetingStateDto) {
        quorumSection.removeAll()
        quorumSection.h3(tr("Beschlussfähigkeit")) { addCssClasses("h6 mb-0") }
        val live = state.liveQuorum
        if (live != null) {
            quorumSection.div(
                gettext("%1 von %2 anwesend · beschlussfähig ab %3", live.presentCount, live.eligibleMemberCount, live.requiredCount),
            ) { addCssClasses("conference-meeting-figures") }
            quorumSection.div(className = "conference-meeting-quorum-state") {
                if (live.met) {
                    icon("fas fa-circle-check") {
                        setAttribute("aria-hidden", "true")
                        addCssClass("me-1")
                    }
                    span(tr("Beschlussfähig"))
                } else {
                    icon("far fa-circle") {
                        setAttribute("aria-hidden", "true")
                        addCssClass("me-1")
                    }
                    span(gettext("Es fehlen noch %1.", (live.requiredCount - live.presentCount).coerceAtLeast(0)))
                }
            }
        }
        val established = state.establishedQuorum
        if (established != null) {
            quorumSection.div(
                gettext(
                    "Festgestellt um %1 (%2 von %3)",
                    formatSystemTime(established.at),
                    established.present,
                    established.eligible,
                ),
            ) { addCssClasses("conference-meeting-figures") }
            if (established.overridden) {
                quorumSection.div(
                    tr("Festgestellt, obwohl die Berechnung „nicht beschlussfähig“ ergab."),
                ) { addCssClasses("text-muted small") }
            }
            if (live != null && live.presentCount != established.present) {
                quorumSection.div(tr("Seit der Feststellung geändert")) { addCssClasses("text-muted small conference-meeting-changed") }
            }
        } else {
            quorumSection.div(tr("Noch nicht festgestellt")) { addCssClasses("text-muted small") }
        }
        if (state.callerRole == MeetingSessionRole.LEAD && state.phase == MeetingSessionPhase.OPEN && live != null) {
            quorumSection.actionGroup {
                actionButton(ActionIcon.APPROVE, tr("Beschlussfähigkeit feststellen"), style = ButtonStyle.OUTLINEPRIMARY) {
                    onClick {
                        if (live.met) {
                            act { rpc.establish(roomId, state.meetingId, false) }
                        } else {
                            confirmDialog(
                                title = gettext("Beschlussfähigkeit feststellen"),
                                message =
                                    gettext(
                                        "Laut Berechnung nicht beschlussfähig (%1 von %2, ab %3). Trotzdem feststellen? Das wird so festgehalten.",
                                        live.presentCount,
                                        live.eligibleMemberCount,
                                        live.requiredCount,
                                    ),
                                confirmLabel = tr("Trotzdem feststellen"),
                                confirmStyle = ButtonStyle.PRIMARY,
                                focusCancel = true,
                                onConfirm = { act { rpc.establish(roomId, state.meetingId, true) } },
                            )
                        }
                    }
                }
            }
        }
    }

    fun drawAgenda(state: ConferenceMeetingStateDto) {
        agendaSection.removeAll()
        agendaSection.h3(tr("Tagesordnung")) { addCssClasses("h6 mb-0") }
        if (state.agenda.isEmpty()) {
            agendaSection.div(tr("Keine Tagesordnung hinterlegt.")) { addCssClasses("text-muted small") }
            return
        }
        val list = Tag(TAG.OL, className = "conference-meeting-agenda-list")
        agendaSection.add(list)
        state.agenda.forEach { item ->
            val isCurrent = item.id == state.currentAgendaItemId
            val li = Tag(TAG.LI, className = if (isCurrent) "conference-meeting-agenda-current" else null)
            if (isCurrent) li.setAttribute("aria-current", "step")
            list.add(li)
            if (isCurrent) {
                li.icon("fas fa-caret-right") {
                    setAttribute("aria-hidden", "true")
                    addCssClass("me-1")
                }
            }
            li.untrustedSpan(item.title)
            if (!isCurrent) {
                motionCountsText(state, item.id)?.let { li.div(it) { addCssClasses("small text-muted") } }
            }
        }
        if (state.callerRole == MeetingSessionRole.LEAD && state.phase == MeetingSessionPhase.OPEN) {
            val index = state.agenda.indexOfFirst { it.id == state.currentAgendaItemId }

            fun move(targetId: String) {
                if (busy || disposed) return
                busy = true
                showNote(null)
                AppScope.launch {
                    var answer: ConferenceMeetingStateDto? = null
                    var conflict = false
                    val failed =
                        guarded {
                            try {
                                answer = rpc.move(roomId, state.meetingId, state.currentAgendaItemId, targetId)
                            } catch (e: AgendaMovedMeanwhileException) {
                                // only the stale-view conflict means "moved on" (a distinct type: the message never reaches the client)
                                conflict = true
                            }
                        } == null
                    busy = false
                    if (disposed) return@launch
                    val fresh = answer
                    when {
                        fresh != null -> {
                            onFreshState(fresh)
                            applyState(fresh)
                        }
                        conflict -> {
                            showNote(gettext("Die Tagesordnung wurde inzwischen weitergeschaltet."))
                            onStaleState()
                        }
                        failed -> onStaleState()
                    }
                }
            }
            val moveGroup = agendaSection.actionGroup()
            moveGroup.actionButton(
                ActionIcon.MOVE_UP,
                tr("Vorheriger Punkt"),
                labelMode = ActionLabelMode.RESPONSIVE,
            ) {
                disabled = index <= 0
                onClick { if (index > 0) move(state.agenda[index - 1].id) }
            }
            moveGroup.actionButton(
                ActionIcon.MOVE_DOWN,
                tr("Nächster Punkt"),
                labelMode = ActionLabelMode.RESPONSIVE,
            ) {
                disabled = index >= state.agenda.lastIndex
                onClick { if (index < state.agenda.lastIndex) move(state.agenda[index + 1].id) }
            }
        }
        renderAgendaMotions(agendaSection, state, motionActions)
        // the current item stays in view, without animation for people who asked for less motion
        window.setTimeout({
            val el = (list.getElement()?.querySelector("[aria-current]") as? HTMLElement)
            val reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches
            val options: dynamic = js("({})")
            options.block = "nearest"
            options.behavior = if (reduced) "auto" else "smooth"
            el?.asDynamic()?.scrollIntoView(options)
        }, 0)
    }

    fun statusOptions(): List<Pair<String, String>> =
        listOf(
            "" to gettext("offen"),
            AttendanceStatus.PRESENT.name to gettext("Anwesend"),
            AttendanceStatus.EXCUSED.name to gettext("Entschuldigt"),
            AttendanceStatus.UNEXCUSED.name to gettext("Unentschuldigt"),
            AttendanceStatus.REPRESENTED.name to gettext("Vertretung"),
        )

    /** The name cell of a roster row with the marker that shows who is in the room; patched in place when only that marker changes. */
    class RosterName(
        val span: Span,
        var marker: Pair<Icon, Span>? = null,
    )

    val rosterNames = mutableMapOf<String, RosterName>()

    fun setConnected(
        name: RosterName,
        connected: Boolean,
    ) {
        if (connected == (name.marker != null)) return
        if (connected) {
            val icon =
                Icon("fas fa-wifi").apply {
                    setAttribute("aria-hidden", "true")
                    addCssClass("me-1")
                }
            val hidden = Span(tr("Im Raum verbunden"), className = "visually-hidden")
            name.span.add(0, icon)
            name.span.add(1, hidden)
            name.marker = icon to hidden
        } else {
            name.marker?.let {
                name.span.remove(it.first)
                name.span.remove(it.second)
            }
            name.marker = null
        }
    }

    /** V1.9.242 -- a join or leave in the room changes only the marker of one row: nothing else of the roster is touched. */
    fun patchConnected(state: ConferenceMeetingStateDto) {
        state.roster.forEach { entry -> rosterNames[entry.memberId]?.let { setConnected(it, entry.connected) } }
    }

    fun renderRosterRow(
        list: Tag,
        state: ConferenceMeetingStateDto,
        entry: SessionRosterEntryDto,
        editable: Boolean,
    ) {
        val li = Tag(TAG.LI, className = "conference-meeting-row")
        list.add(li)
        val nameSpan = li.span(className = "conference-meeting-name") { untrustedSpan(entry.displayName) }
        val rosterName = RosterName(nameSpan)
        setConnected(rosterName, entry.connected)
        rosterNames[entry.memberId] = rosterName
        val select = Select(options = statusOptions(), value = entry.status?.name.orEmpty())
        // the name goes on the real <select>, not on the form-group wrapper around it
        select.input.setAttribute("aria-label", gettext("Anwesenheit: %1", sanitizeUntrustedI18nText(entry.displayName)))
        select.disabled = !editable
        li.add(select)
        var pendingStandIn = entry.status == AttendanceStatus.REPRESENTED
        val standInHost = Div(className = "conference-meeting-standin")
        li.add(standInHost)

        fun send(
            status: AttendanceStatus?,
            standIn: String?,
        ) = act { rpc.setAttendance(roomId, state.meetingId, SessionAttendanceInput(entry.memberId, status, standIn)) }

        fun showStandIn() {
            standInHost.removeAll()
            val options = untrustedOptions(state.roster.filter { it.memberId != entry.memberId }.map { it.memberId to it.displayName })
            val chooser =
                standInHost.searchableSelect(
                    options = options,
                    value = entry.representedByMemberId,
                    label = tr("Vertreten durch"),
                )
            chooser.setAttribute("aria-label", gettext("Vertreten durch"))
            chooser.subscribe { picked ->
                if (!picked.isNullOrEmpty() && picked != entry.representedByMemberId) send(AttendanceStatus.REPRESENTED, picked)
            }
        }
        if (pendingStandIn) showStandIn()
        val initialValue = entry.status?.name.orEmpty()
        select.subscribe { value ->
            // the subscription also reports the value the control was built with: only a real change is an action
            if (value.orEmpty() == initialValue) return@subscribe
            val status = value?.takeIf { it.isNotEmpty() }?.let { AttendanceStatus.valueOf(it) }
            standInHost.removeAll()
            pendingStandIn = status == AttendanceStatus.REPRESENTED
            if (pendingStandIn) showStandIn() else send(status, null)
        }
    }

    fun drawAttendance(state: ConferenceMeetingStateDto) {
        attendanceSection.removeAll()
        rosterNames.clear()
        if (state.callerRole == MeetingSessionRole.PARTICIPANT) return
        attendanceSection.h3(tr("Anwesenheit")) { addCssClasses("h6 mb-0") }
        if (state.ruleKind == AttendanceRuleKind.UNSUPPORTED) {
            attendanceSection.div(
                tr(
                    "Die Anwesenheit der Mitgliederversammlung wird hier nicht geführt. Beschlussfähigkeit und Tagesordnung gelten wie angezeigt.",
                ),
            ) { addCssClasses("text-muted small") }
            return
        }
        val editable = state.phase == MeetingSessionPhase.OPEN
        if (!editable) {
            attendanceSection.div(
                tr("Die Anwesenheit kann geführt werden, sobald die Sitzung eröffnet ist."),
            ) { addCssClasses("text-muted small") }
        }
        attendanceSection.actionGroup {
            actionButton(
                ActionIcon.PEOPLE,
                gettext("Alle Verbundenen übernehmen (%1)", state.adoptableCount),
                style = ButtonStyle.OUTLINEPRIMARY,
            ) {
                disabled = !editable || state.adoptableCount == 0
                onClick {
                    if (busy || disposed) return@onClick
                    act {
                        rpc.adopt(roomId, state.meetingId)
                        rpc.getState(roomId) ?: throw IllegalStateException("no state")
                    }
                }
            }
        }
        if (state.extraConnectedCount > 0) {
            attendanceSection.div(gettext("+%1 weitere Verbundene", state.extraConnectedCount)) { addCssClasses("text-muted small") }
        }
        val list = Tag(TAG.UL, className = "conference-meeting-roster list-unstyled")
        attendanceSection.add(list)
        state.roster.forEach { renderRosterRow(list, state, it, editable) }
        if (state.guests.isNotEmpty()) {
            attendanceSection.h3(tr("Gäste")) { addCssClasses("h6 mb-0 mt-2") }
            val guestList = Tag(TAG.UL, className = "conference-meeting-guests list-unstyled")
            attendanceSection.add(guestList)
            state.guests.forEach { guest ->
                val li = Tag(TAG.LI, className = "conference-meeting-row")
                guestList.add(li)
                li.span(className = "conference-meeting-name") {
                    icon("fas fa-user-tag") {
                        setAttribute("aria-hidden", "true")
                        addCssClass("me-1")
                    }
                    untrustedSpan(guest.displayName)
                    span(tr("Gast")) { addCssClasses("badge text-bg-secondary ms-2") }
                }
                if (editable) {
                    li.actionButton(ActionIcon.REMOVE, tr("Gast entfernen"), style = ButtonStyle.OUTLINESECONDARY, small = true) {
                        onClick { act { rpc.setAttendance(roomId, state.meetingId, SessionAttendanceInput(guest.memberId, null)) } }
                    }
                }
            }
        }
        if (editable && state.callerRole == MeetingSessionRole.LEAD) {
            val picker = RemoteMemberPicker(search = guestSearch(state.committeeId), label = null)
            picker.setAttribute("aria-label", gettext("Gast suchen"))
            val addRow = attendanceSection.vPanel(spacing = 4) { addCssClass("conference-meeting-add-guest") }
            addRow.add(picker)
            addRow.actionGroup {
                actionButton(ActionIcon.ADD_PERSON, tr("Gast hinzufügen"), style = ButtonStyle.OUTLINESECONDARY) {
                    onClick {
                        val id = picker.selectedPick?.id ?: return@onClick
                        act { rpc.setAttendance(roomId, state.meetingId, SessionAttendanceInput(id, AttendanceStatus.GUEST)) }
                    }
                }
            }
        }
    }

    renderBanner = { renderRunningVoteBanner(bannerSection, it, motionActions) }
    renderHead = ::drawHead
    renderQuorum = ::drawQuorum
    renderAgenda = ::drawAgenda
    renderAttendance = ::drawAttendance

    applyState = { state ->
        if (!disposed) {
            val before = current
            current = state
            latestState = state ?: latestState
            refreshHint.hide()
            if (state != null && protocolHandle != null && protocolMeetingId != state.meetingId) {
                // the room was bound to another Sitzung: the old draft view goes, a new one is built when its tab is shown
                protocolHandle?.dispose?.invoke()
                protocolHandle = null
                protocolFinalHandle = null
                protocolHost.removeAll()
            }
            if (state == null) {
                dropPending()
                body.hide()
                paintTabs()
            } else {
                paintTabs()
                val role = state.callerRole
                val newHead = listOf(state.meetingTitle, state.phase, state.openedAt, role, state.nextStepHint)
                val newQuorum = listOf(state.liveQuorum, state.establishedQuorum, state.phase, role)
                val newAgenda =
                    listOf(
                        state.agenda,
                        state.currentAgendaItemId,
                        state.phase,
                        role,
                        state.agendaMotions,
                        state.canManageMotions,
                        state.attachableMotions,
                        state.runningMotionVote != null,
                    )
                val newBanner = listOf(state.runningMotionVote, state.canManageMotions, state.phase)
                val newAttendance =
                    listOf(
                        attendanceRosterSlice(state),
                        state.guests,
                        state.adoptableCount,
                        state.extraConnectedCount,
                        state.ruleKind,
                        state.phase,
                        role,
                    )
                if (newBanner != bannerSlice) {
                    bannerSlice = newBanner
                    bannerGuard.requestRender()
                }
                if (newHead != headSlice) {
                    headSlice = newHead
                    headGuard.requestRender()
                }
                if (newQuorum != quorumSlice) {
                    quorumSlice = newQuorum
                    quorumGuard.requestRender()
                }
                if (newAgenda != agendaSlice) {
                    agendaSlice = newAgenda
                    agendaGuard.requestRender()
                }
                if (newAttendance != attendanceSlice) {
                    attendanceSlice = newAttendance
                    if (forceAttendanceRedraw) {
                        forceAttendanceRedraw = false
                        attendanceGuard.renderNow()
                    } else {
                        attendanceGuard.requestRender()
                    }
                } else if (!attendanceGuard.pending) {
                    patchConnected(state)
                }
                if (before != null && before.phase != state.phase && state.phase == MeetingSessionPhase.OPEN) {
                    announce(gettext("Die Sitzung ist eröffnet."))
                }
            }
        }
    }

    return ConferenceMeetingPanelHandle(
        panel = panel,
        apply = { applyState(it) },
        applyFailure = { failures -> if (failures >= MEETING_QUIET_HINT_AFTER_FAILURES) refreshHint.show() },
        setOpen = { wanted ->
            open = wanted
            if (wanted) panel.show() else panel.hide()
            paintTabs()
        },
        isOpen = { open },
        dispose = {
            disposed = true
            guards.dispose()
            protocolHandle?.dispose?.invoke()
        },
    )
}

/** The toggle button of the control bar. Hidden until the first answer says there is a Sitzung for this person. */
internal class ConferenceMeetingToggle(
    val button: Button,
) {
    fun update(
        visible: Boolean,
        pressed: Boolean,
    ) {
        val label = gettext("Sitzungsmodus")
        if (button.title != label) button.title = label
        button.setAttr("aria-label", label)
        button.setAttr("aria-pressed", pressed.toString())
        if (pressed) button.addCssClass("active") else button.removeCssClass("active")
        if (visible) button.show() else button.hide()
    }
}

internal fun Container.conferenceMeetingToggle(): ConferenceMeetingToggle {
    val button = conferenceMeetingButton()
    button.hide()
    return ConferenceMeetingToggle(button)
}
