package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.form.text.TextArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.h4
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.PROTOCOL_BLOCK_MAX_LENGTH
import network.lapis.cloud.shared.domain.PROTOCOL_TASK_MAX_LENGTH
import network.lapis.cloud.shared.domain.ProtocolAgendaItemDto
import network.lapis.cloud.shared.domain.ProtocolBlockSaveResultDto
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolPersonDto
import network.lapis.cloud.shared.domain.ProtocolResolutionDto
import network.lapis.cloud.shared.domain.ProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.rpc.IProtocolDraftService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Date
import kotlin.time.Duration.Companion.milliseconds

private const val PROTOCOL_POLL_MS = 10_000
private const val PROTOCOL_POLL_HIDDEN_MS = 30_000

/** Failed polls in a row before the calm "is being refreshed" line shows (never an error text). */
internal const val PROTOCOL_QUIET_HINT_AFTER_FAILURES = 3

/** V1.9.239 -- the RPCs of the Protokoll draft behind one thin seam, so a test can replace them (same shape as [ConferenceMeetingRpc]). */
internal class ProtocolDraftRpc(
    val get: suspend (meetingId: String) -> MeetingProtocolDraftDto = { rpcService<IProtocolDraftService>().getMeetingProtocolDraft(it) },
    val save: suspend (
        meetingId: String,
        agendaItemId: String,
        expectedVersion: Long,
        body: String,
    ) -> ProtocolBlockSaveResultDto = { meeting, item, version, body ->
        rpcService<IProtocolDraftService>().saveMeetingProtocolBlock(meeting, item, version, body)
    },
    val addTask: suspend (meetingId: String, input: ProtocolTaskInput) -> ProtocolTaskDto = { meeting, input ->
        rpcService<IProtocolDraftService>().addMeetingProtocolTask(meeting, input)
    },
    val removeTask: suspend (meetingId: String, taskId: String) -> Unit = { meeting, task ->
        rpcService<IProtocolDraftService>().removeMeetingProtocolTask(meeting, task)
    },
)

/** The length as the server counts it: UTF-16 units (the bound of both database engines, and what the browser's `maxlength` counts). */
internal fun protocolCharCount(text: String): Int = text.length

/**
 * [snippet] inserted at [start]..[end] of [text] (a selection is replaced). `null` when the result would exceed [limit] characters -- nothing is
 * inserted then. The second value is the new cursor position.
 */
internal fun insertIntoProtocolText(
    text: String,
    start: Int,
    end: Int,
    snippet: String,
    limit: Int = PROTOCOL_BLOCK_MAX_LENGTH,
): Pair<String, Int>? {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    val result = text.substring(0, from) + snippet + text.substring(to)
    if (protocolCharCount(result) > limit) return null
    return result to from + snippet.length
}

/** What a lost race shows: the newer text of the other writer, read-only, above the person's own text. */
internal class ProtocolConflict(
    val body: String,
    val updatedAt: LocalDateTime?,
)

/**
 * V1.9.239 -- the editing state of the Protokoll draft, without any widget: what is displayed in each text, which text was saved last, which
 * version a save names, which items are in conflict. A poll NEVER overwrites a text that is being edited ([applyServer]); a lost race keeps the
 * person's own text and moves the base version to the newer one, so saving again is a deliberate overwrite (never an automatic merge).
 */
internal class ProtocolDraftController {
    var draft: MeetingProtocolDraftDto? = null
        private set

    private val local = mutableMapOf<String, String>()
    private val saved = mutableMapOf<String, String>()
    private val shownVersion = mutableMapOf<String, Long>()
    private val conflicts = mutableMapOf<String, ProtocolConflict>()
    private val saving = mutableSetOf<String>()

    fun localText(itemId: String): String = local[itemId].orEmpty()

    fun version(itemId: String): Long = shownVersion[itemId] ?: 0L

    fun conflict(itemId: String): ProtocolConflict? = conflicts[itemId]

    fun isDirty(itemId: String): Boolean = local[itemId].orEmpty() != saved[itemId].orEmpty()

    fun isSaving(itemId: String): Boolean = itemId in saving

    /** Leaving the field saves on its own only while there is no open conflict: with one, saving is the person's explicit decision. */
    fun autoSaveAllowed(itemId: String): Boolean = itemId !in conflicts

    /** The person typed (or an insertion changed the text). */
    fun edit(
        itemId: String,
        text: String,
    ) {
        local[itemId] = text
    }

    /**
     * Takes a fresh reading. Returns the items whose displayed text must be replaced by the server text: items never seen, and items that
     * are neither being edited ([focusedItemId] or unsaved changes) nor already current.
     */
    fun applyServer(
        fresh: MeetingProtocolDraftDto,
        focusedItemId: String?,
    ): Set<String> {
        draft = fresh
        val ids = fresh.items.map { it.agendaItemId }.toSet()
        listOf(local, saved, shownVersion, conflicts).forEach { map -> map.keys.retainAll(ids) }
        val replace = mutableSetOf<String>()
        fresh.items.forEach { item ->
            if (takeServerText(item = item, focused = item.agendaItemId == focusedItemId)) {
                replace +=
                    item.agendaItemId
            }
        }
        return replace
    }

    private fun takeServerText(
        item: ProtocolAgendaItemDto,
        focused: Boolean,
    ): Boolean {
        val id = item.agendaItemId
        val firstSight = id !in local
        val editing = !firstSight && (focused || isDirty(id) || id in saving)
        val current = !firstSight && shownVersion[id] == item.version && local[id] == item.body
        if (editing || current) return false
        local[id] = item.body
        saved[id] = item.body
        shownVersion[id] = item.version
        conflicts.remove(id)
        return true
    }

    /** A save starts; `null` when there is nothing to save, a save is running already, or the text is too long. */
    fun beginSave(itemId: String): Pair<Long, String>? {
        if (itemId in saving) return null
        val text = local[itemId].orEmpty()
        if (text == saved[itemId].orEmpty()) return null
        if (protocolCharCount(text) > PROTOCOL_BLOCK_MAX_LENGTH) return null
        saving += itemId
        return version(itemId) to text
    }

    fun endSave(
        itemId: String,
        sent: String,
        result: ProtocolBlockSaveResultDto?,
    ) {
        saving -= itemId
        if (result == null) return
        if (result.saved) {
            saved[itemId] = sent
            shownVersion[itemId] = result.version
            conflicts.remove(itemId)
        } else {
            // The own text stays exactly as typed; the newer text goes above it, and the next save names the newer version on purpose.
            conflicts[itemId] = ProtocolConflict(body = result.currentBody.orEmpty(), updatedAt = result.currentUpdatedAt)
            shownVersion[itemId] = result.version
        }
    }

    /** The conflict box was dealt with (the person merged by hand and saved, or the item vanished). */
    fun dismissConflict(itemId: String) {
        conflicts.remove(itemId)
    }
}

internal class ProtocolDraftSectionHandle(
    val root: Div,
    val controller: ProtocolDraftController,
    val setActive: (Boolean) -> Unit,
    val refreshNow: () -> Unit,
    val dispose: () -> Unit,
)

private class ItemUi(
    val itemId: String,
    val editable: Boolean,
    val textArea: TextArea?,
    val counter: Div?,
    val versionLabel: Div?,
    val conflictHost: Div,
    val resolutionsHost: Div,
    val tasksHost: Div,
    val toolbar: Div?,
    val menuHost: Div?,
    val taskFormHost: Div?,
    val readonlyBody: Div?,
    var bodySlice: String? = null,
    var resolutionsSlice: Any? = null,
    var tasksSlice: Any? = null,
    var conflictSlice: Any? = null,
    var versionSlice: Any? = null,
)

private fun textElementOf(area: TextArea): HTMLTextAreaElement? {
    val root = area.getElement() as? HTMLElement ?: return null
    return (root as? HTMLTextAreaElement) ?: root.querySelector("textarea") as? HTMLTextAreaElement
}

private fun textOf(area: TextArea): String = textElementOf(area)?.value ?: area.value.orEmpty()

private fun setTextOf(
    area: TextArea,
    text: String,
) {
    area.value = text
    textElementOf(area)?.value = text
}

/** The six building blocks of the "Textbaustein" menu: static texts, so a language switch resolves them. */
private fun snippetTexts(): List<String> =
    listOf(
        gettext("Die Sitzung wird eröffnet."),
        gettext("Die Beschlussfähigkeit wird festgestellt."),
        gettext("Es gibt keine Wortmeldungen."),
        gettext("Die Beratung wird abgeschlossen."),
        gettext("Der Punkt wird vertagt."),
        gettext("Die Sitzung wird geschlossen."),
    )

/**
 * V1.9.239 "Protokollentwurf in der Sitzung" (D1) -- the Protokoll tab of the Sitzung panel. Everything the system filled in (header,
 * attendance, quorum, resolutions) sits in a marked "taken from the Sitzung" box that nobody edits; the free text of each agenda item and the
 * tasks are what the lead and the minute taker write. A reader (PARTICIPANT, or a locked/not-yet-open draft) gets the same view without any
 * field or tool.
 *
 * All text is shown through `textContent`/`value` only (never `rich`, never `innerHTML`). Nothing is saved while typing: a text is saved when
 * the field is left and with the "Speichern" button. The draft is read only while the tab is the active one ([ProtocolDraftSectionHandle.setActive]).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
internal fun renderConferenceProtocolDraftSection(
    parent: Container,
    meetingId: String,
    rpc: ProtocolDraftRpc = ProtocolDraftRpc(),
    scheduler: ConferenceVoteScheduler = BrowserVoteScheduler,
    clock: () -> Double = { Date.now() },
    isHidden: () -> Boolean = { js("document.visibilityState === 'hidden'") as Boolean },
    finalRpc: MeetingProtocolRpc = MeetingProtocolRpc(),
): ProtocolDraftSectionHandle {
    val controller = ProtocolDraftController()
    val root = Div(className = "protocol-draft")
    parent.add(root)
    val liveRegion =
        root.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val banner = root.div(className = "protocol-banner alert alert-secondary py-1 px-2 small mb-2")
    val hint = root.div(tr("Das Protokoll wird aktualisiert …")) { addCssClasses("text-muted small") }
    hint.hide()
    val loading = root.div(tr("Das Protokoll wird geladen …")) { addCssClasses("text-muted") }
    // the error state of the FIRST reading (nothing to show yet): one fixed sentence with a retry; later misses only show the calm hint above
    val errorHost = root.div("")
    val note = root.div("") { addCssClasses("text-muted small") }
    note.hide()
    val body = root.vPanel(spacing = 8) { addCssClass("protocol-body") }
    body.hide()
    val headerHost = body.vPanel(spacing = 2) { addCssClasses("protocol-system-part") }
    val attendanceHost = body.vPanel(spacing = 2) { addCssClasses("protocol-system-part") }
    val quorumHost = body.vPanel(spacing = 2) { addCssClasses("protocol-system-part") }
    body.h3(tr("Verlauf")) { addCssClasses("h6 mb-0 mt-2") }
    val itemsHost = body.vPanel(spacing = 10) { addCssClass("protocol-items") }
    val unassignedHost = body.vPanel(spacing = 2) { addCssClasses("protocol-system-part") }
    val closingHost = body.vPanel(spacing = 2)

    val items = mutableMapOf<String, ItemUi>()
    var structureSlice: Any? = null
    var headerSlice: Any? = null
    var attendanceSlice: Any? = null
    var quorumSlice: Any? = null
    var unassignedSlice: Any? = null
    var closingSlice: Any? = null
    var activeItemId: String? = null
    var focusedItemId: String? = null
    var active = false
    var disposed = false
    var inFlight = false
    var followUp = false
    var failures = 0
    var timer: Int? = null
    var loadedServerNow: LocalDateTime? = null
    var loadedClock = 0.0

    fun announce(text: String) {
        liveRegion.content = text
    }

    fun showNote(text: String?) {
        if (text == null) {
            note.hide()
        } else {
            note.content = text
            note.show()
        }
    }

    /** The server time as of now: the time the draft was read plus what elapsed since (the server is the clock of the Protokoll). */
    fun serverTimeText(): String {
        val base = loadedServerNow ?: return ""
        val moved = base.toInstant(TimeZone.UTC) + (clock() - loadedClock).milliseconds
        return formatSystemTime(moved.toLocalDateTime(TimeZone.UTC))
    }

    fun syncLocal(itemId: String) {
        val area = items[itemId]?.textArea ?: return
        controller.edit(itemId, textOf(area))
    }

    fun syncAllLocal() = items.keys.forEach { syncLocal(it) }

    fun lockedLine(): Div {
        val line = Div(className = "protocol-system-note text-muted small")
        line.icon("fas fa-lock") {
            setAttribute("aria-label", gettext("Aus der Sitzung übernommen"))
            setAttribute("role", "img")
            addCssClass("me-1")
        }
        line.span(tr("Aus der Sitzung übernommen"))
        return line
    }

    fun labelRow(
        host: Container,
        label: String,
        value: String,
    ) {
        host.div(className = "protocol-row") {
            span(label, className = "text-muted me-2")
            untrustedSpan(value)
        }
    }

    fun names(people: List<ProtocolPersonDto>): String = if (people.isEmpty()) "–" else people.joinToString(", ") { it.displayName }

    fun renderHeader(d: MeetingProtocolDraftDto) {
        headerHost.removeAll()
        headerHost.add(lockedLine())
        val h = d.header
        headerHost.untrustedHeading(h.committeeName, level = 3, className = "h6 mb-0")
        headerHost.untrustedDiv(h.title, className = "fw-semibold")
        labelRow(headerHost, gettext("Gremium"), committeeTypeLabel(h.committeeType))
        labelRow(headerHost, gettext("Datum"), formatDateTime(h.scheduledAt))
        labelRow(headerHost, gettext("Format"), meetingFormatLabel(h.format))
        h.location?.let { labelRow(headerHost, gettext("Ort"), it) }
        labelRow(headerHost, gettext("Beginn"), h.openedAt?.let { formatSystemTime(it) } ?: "–")
        labelRow(headerHost, gettext("Ende"), h.endedAt?.let { formatSystemTime(it) } ?: "–")
        labelRow(headerHost, gettext("Sitzungsleitung"), h.chairName ?: "–")
        labelRow(headerHost, gettext("Protokollführung"), h.minuteTakerName ?: "–")
    }

    fun renderAttendance(d: MeetingProtocolDraftDto) {
        attendanceHost.removeAll()
        attendanceHost.add(lockedLine())
        val a = d.attendance
        attendanceHost.h4(tr("Anwesenheit")) { addCssClasses("h6 mb-0") }
        labelRow(attendanceHost, gettext("Anwesend"), names(a.present))
        labelRow(attendanceHost, gettext("Entschuldigt"), names(a.excused))
        labelRow(attendanceHost, gettext("Abwesend"), names(a.absent))
        labelRow(
            attendanceHost,
            gettext("Vertreten durch"),
            if (a.represented.isEmpty()) {
                "–"
            } else {
                a.represented.joinToString("; ") { "${it.representedName} → ${it.representativeName ?: "–"}" }
            },
        )
        labelRow(attendanceHost, gettext("Gäste"), names(a.guests))
    }

    fun renderQuorum(d: MeetingProtocolDraftDto) {
        quorumHost.removeAll()
        quorumHost.add(lockedLine())
        val q = d.quorum
        if (q == null) {
            quorumHost.div(tr("Beschlussfähigkeit: noch nicht festgestellt"))
            return
        }
        quorumHost.div(
            gettext("Beschlussfähigkeit festgestellt um %1 (%2 von %3 anwesend)", formatSystemTime(q.establishedAt), q.present, q.eligible),
        )
        if (q.overridden) {
            quorumHost.div(tr("Festgestellt, obwohl die Berechnung „nicht beschlussfähig“ ergab.")) { addCssClasses("text-muted small") }
        }
    }

    fun resolutionRow(
        host: Container,
        r: ProtocolResolutionDto,
    ) {
        host.div(className = "protocol-resolution") {
            div(className = "fw-semibold") {
                untrustedSpan(gettext("Beschluss %1", sanitizeUntrustedI18nText(r.number)))
                untrustedSpan(" – ${r.title}")
            }
            untrustedDiv(r.text, className = "protocol-plain small")
            div(className = "small") {
                if (r.figuresWithheld) {
                    span(tr("Geheime Abstimmung – Zahlen zurückgehalten"), className = "text-muted")
                } else {
                    span(gettext("Ja %1 · Nein %2 · Enthaltung %3", r.yes, r.no, r.abstain))
                }
                span(resolutionStatusLabel(r.status), className = "ms-2")
                if (!r.quorumMet) span(tr("nicht beschlussfähig"), className = "ms-2 text-muted")
                if (r.secretBallot) {
                    icon("fas fa-person-booth") {
                        setAttribute("role", "img")
                        setAttribute("aria-label", gettext("Geheime Abstimmung – nur Summen"))
                        setAttribute("title", gettext("Geheime Abstimmung – nur Summen"))
                        addCssClass("ms-2")
                    }
                }
            }
        }
    }

    fun renderUnassigned(d: MeetingProtocolDraftDto) {
        unassignedHost.removeAll()
        if (d.unassignedResolutions.isEmpty()) return
        unassignedHost.add(lockedLine())
        unassignedHost.h4(tr("Ohne Tagesordnungspunkt")) { addCssClasses("h6 mb-0") }
        d.unassignedResolutions.forEach { resolutionRow(unassignedHost, it) }
    }

    fun renderClosing(d: MeetingProtocolDraftDto) {
        closingHost.removeAll()
        closingHost.h4(tr("Schluss")) { addCssClasses("h6 mb-0") }
        closingHost.div(gettext("Aufgaben insgesamt: %1", d.taskCount)) { addCssClasses("text-muted small") }
    }

    // ---- saving ----

    lateinit var paintConflict: (String) -> Unit
    lateinit var reload: () -> Unit

    fun save(itemId: String) {
        if (disposed) return
        syncLocal(itemId)
        val ui = items[itemId] ?: return
        if (ui.textArea == null) return
        if (protocolCharCount(controller.localText(itemId)) > PROTOCOL_BLOCK_MAX_LENGTH) {
            showNote(gettext("Der Abschnitt ist voll."))
            return
        }
        val plan = controller.beginSave(itemId) ?: return
        showNote(null)
        AppScope.launch {
            val result = guarded { rpc.save(meetingId, itemId, plan.first, plan.second) }
            controller.endSave(itemId = itemId, sent = plan.second, result = result)
            if (disposed) return@launch
            paintConflict(itemId)
            if (result != null && result.saved) announce(gettext("Gespeichert um %1", serverTimeText()))
            if (result != null && !result.saved) announce(gettext("Inzwischen geändert – bitte zusammenführen"))
        }
    }

    // ---- editing tools ----

    fun insertText(
        itemId: String,
        snippet: String,
    ) {
        val ui = items[itemId] ?: return
        val area = ui.textArea ?: return
        val element = textElementOf(area)
        val current = textOf(area)
        val start = element?.selectionStart ?: current.length
        val end = element?.selectionEnd ?: start
        val inserted = insertIntoProtocolText(text = current, start = start, end = end, snippet = snippet)
        if (inserted == null) {
            showNote(gettext("Der Abschnitt ist voll."))
            return
        }
        showNote(null)
        setTextOf(area, inserted.first)
        element?.setSelectionRange(inserted.second, inserted.second)
        controller.edit(itemId, inserted.first)
        ui.counter?.content = gettext("%1 / %2 Zeichen", protocolCharCount(inserted.first), PROTOCOL_BLOCK_MAX_LENGTH)
    }

    fun clearMenu(ui: ItemUi) {
        ui.menuHost?.removeAll()
        ui.menuHost?.hide()
    }

    fun showMenu(
        ui: ItemUi,
        label: String,
        entries: List<Pair<String, String>>,
    ) {
        val host = ui.menuHost ?: return
        host.removeAll()
        host.setAttribute("role", "group")
        host.setAttribute("aria-label", label)
        entries.forEach { (shown, snippet) ->
            val button = Button(text = sanitizeUntrustedI18nText(shown), style = ButtonStyle.OUTLINESECONDARY)
            button.addCssClasses("btn-sm protocol-menu-entry")
            button.onClick {
                insertText(ui.itemId, snippet)
                clearMenu(ui)
            }
            host.add(button)
        }
        host.show()
    }

    fun renderTaskForm(
        ui: ItemUi,
        d: MeetingProtocolDraftDto,
    ) {
        val host = ui.taskFormHost ?: return
        host.removeAll()
        host.show()
        host.setAttribute("role", "group")
        host.setAttribute("aria-label", gettext("Aufgabe hinzufügen"))
        val assignees = listOf("" to gettext("Bitte wählen")) + untrustedOptions(d.assignableMembers.map { it.memberId to it.displayName })
        val who = Select(options = assignees, value = "", label = tr("Wer"))
        who.input.setAttribute("aria-label", gettext("Wer"))
        host.add(who)
        val what = TextArea(rows = 2, label = tr("Was"))
        what.input.setAttribute("maxlength", PROTOCOL_TASK_MAX_LENGTH.toString())
        what.input.setAttribute("aria-label", gettext("Was"))
        host.add(what)
        val until = host.localDateInput(label = tr("Bis wann (optional)"), hintLine = false)
        val error = host.div("") { addCssClasses("text-danger small") }
        error.hide()
        host.div(tr("Erscheint nach Fertigstellung bei der Person unter „Für Sie“.")) { addCssClasses("text-muted small") }
        host.actionGroup {
            actionButton(ActionIcon.ADD, tr("Aufgabe hinzufügen"), style = ButtonStyle.OUTLINEPRIMARY) {
                onClick {
                    val assignee = who.value.orEmpty()
                    val text = (textElementOf(what)?.value ?: what.value.orEmpty()).trim()
                    val due = until.dateOrNull()
                    val problem =
                        when {
                            assignee.isEmpty() -> gettext("Bitte eine Person aus dem Gremium wählen.")
                            text.isEmpty() -> gettext("Bitte beschreiben Sie die Aufgabe.")
                            protocolCharCount(text) > PROTOCOL_TASK_MAX_LENGTH -> gettext("Die Beschreibung ist zu lang.")
                            due != null && due < d.header.scheduledAt.date -> gettext("Die Frist liegt vor der Sitzung.")
                            else -> null
                        }
                    if (problem != null) {
                        error.content = problem
                        error.show()
                        return@onClick
                    }
                    error.hide()
                    AppScope.launch {
                        val added = guarded { rpc.addTask(meetingId, ProtocolTaskInput(ui.itemId, assignee, text, due)) }
                        if (disposed || added == null) return@launch
                        host.removeAll()
                        host.hide()
                        announce(gettext("Aufgabe hinzugefügt."))
                        // the list changes with the next reading; ask for it now
                        reload()
                    }
                }
            }
            actionButton(ActionIcon.CANCEL, tr("Abbrechen")) {
                onClick {
                    host.removeAll()
                    host.hide()
                }
            }
        }
    }

    fun renderTasks(
        ui: ItemUi,
        item: ProtocolAgendaItemDto,
        editable: Boolean,
        refresh: () -> Unit,
    ) {
        ui.tasksHost.removeAll()
        if (item.tasks.isEmpty()) return
        ui.tasksHost.h4(tr("Aufgaben")) { addCssClasses("h6 mb-0") }
        val list = Tag(TAG.UL, className = "protocol-tasks list-unstyled mb-0")
        ui.tasksHost.add(list)
        item.tasks.forEach { task ->
            val li = Tag(TAG.LI, className = "protocol-task d-flex align-items-center gap-2")
            list.add(li)
            li.span(className = "flex-grow-1") {
                untrustedSpan(task.assigneeName, className = "fw-semibold")
                untrustedSpan(": ${task.description}")
                task.dueDate?.let { span(gettext("bis %1", formatDate(it)), className = "text-muted ms-2") }
            }
            if (editable) {
                li.actionButton(ActionIcon.REMOVE, tr("Aufgabe entfernen"), style = ButtonStyle.OUTLINESECONDARY, iconOnly = true) {
                    onClick {
                        AppScope.launch {
                            val done = guarded { rpc.removeTask(meetingId, task.taskId) }
                            if (!disposed && done != null) {
                                announce(gettext("Aufgabe entfernt."))
                                refresh()
                            }
                        }
                    }
                }
            }
        }
    }

    paintConflict = { itemId ->
        val ui = items[itemId]
        val conflict = controller.conflict(itemId)
        if (ui != null) {
            val slice = listOf(conflict?.body, conflict?.updatedAt)
            if (slice != ui.conflictSlice || conflict == null) {
                ui.conflictSlice = slice
                ui.conflictHost.removeAll()
                if (conflict == null) {
                    ui.conflictHost.hide()
                } else {
                    ui.conflictHost.show()
                    ui.conflictHost.setAttribute("role", "alert")
                    ui.conflictHost.div(tr("Inzwischen geändert – bitte zusammenführen")) { addCssClasses("fw-semibold") }
                    ui.conflictHost.div(tr("Neuere Fassung")) { addCssClasses("text-muted small") }
                    // read-only, and a plain text node: the newer text of somebody else is never interpreted
                    ui.conflictHost.untrustedDiv(conflict.body, className = "protocol-plain protocol-conflict-body")
                }
            }
            ui.versionLabel?.content = gettext("Fassung %1", controller.version(itemId))
        }
    }

    // ---- the items ----

    fun updateToolbars() {
        items.values.forEach { ui ->
            ui.toolbar?.let { if (ui.itemId == activeItemId) it.show() else it.hide() }
        }
    }

    fun buildItem(
        item: ProtocolAgendaItemDto,
        d: MeetingProtocolDraftDto,
    ): ItemUi {
        val box = itemsHost.vPanel(spacing = 4) { addCssClass("protocol-item") }
        box.h4 {
            untrustedSpan("${item.position}. ${item.title}")
        }
        // V1.9.240: the item that approves the Protokoll of the previous Sitzung carries the recorded result (and, for the chair, the select)
        item.approval?.let { box.protocolApprovalBlock(approval = it, agendaItemId = item.agendaItemId, rpc = finalRpc) }
        val resolutionsHost = Div(className = "protocol-system-part")
        box.add(resolutionsHost)
        val conflictHost = Div(className = "protocol-conflict alert alert-warning py-1 px-2 small")
        conflictHost.hide()
        val itemId = item.agendaItemId
        box.add(conflictHost)
        if (!d.editable) {
            val readonlyBody = box.untrustedDiv(item.body, className = "protocol-plain protocol-body-readonly")
            val tasksHost = Div(className = "protocol-task-list")
            box.add(tasksHost)
            return ItemUi(
                itemId = itemId,
                editable = false,
                textArea = null,
                counter = null,
                versionLabel = null,
                conflictHost = conflictHost,
                resolutionsHost = resolutionsHost,
                tasksHost = tasksHost,
                toolbar = null,
                menuHost = null,
                taskFormHost = null,
                readonlyBody = readonlyBody,
            ).also { it.bodySlice = item.body }
        }
        val area = TextArea(value = item.body, rows = 5) { addCssClasses("w-100 protocol-textarea") }
        // the focus/blur hook is registered BEFORE the textarea is rendered (see KvisionLifecycle.kt): a late hook would replace the field mid-typing
        box.addWithLifecycle(
            area,
            onInsert = { vnode ->
                val el = vnode.elm as? HTMLElement
                val textarea = (el as? HTMLTextAreaElement) ?: el?.querySelector("textarea") as? HTMLTextAreaElement
                textarea?.addEventListener(
                    "focus",
                    {
                        focusedItemId = itemId
                        activeItemId = itemId
                        updateToolbars()
                    },
                )
                textarea?.addEventListener(
                    "blur",
                    {
                        if (focusedItemId == itemId) focusedItemId = null
                        // with an open conflict only the explicit "Speichern" overwrites the newer text of the other person
                        if (controller.autoSaveAllowed(itemId)) save(itemId)
                    },
                )
            },
        )
        area.input.setAttribute("maxlength", PROTOCOL_BLOCK_MAX_LENGTH.toString())
        area.input.setAttribute("aria-label", gettext("Protokolltext: %1", sanitizeUntrustedI18nText(item.title)))
        val meta = box.hPanel(spacing = 8)
        val counter =
            meta.div(gettext("%1 / %2 Zeichen", protocolCharCount(item.body), PROTOCOL_BLOCK_MAX_LENGTH)) {
                addCssClasses("text-muted small")
            }
        val versionLabel = meta.div(gettext("Fassung %1", item.version)) { addCssClasses("text-muted small") }
        val menuHost = Div(className = "protocol-menu d-flex flex-wrap gap-1")
        menuHost.hide()
        val taskFormHost = Div(className = "protocol-task-form border rounded p-2")
        taskFormHost.hide()
        val tasksHost = Div(className = "protocol-task-list")
        tasksHost.hide()
        val toolbar = box.actionGroup()
        toolbar.addCssClass("protocol-toolbar")
        box.add(menuHost)
        box.add(taskFormHost)
        box.add(tasksHost)
        val ui =
            ItemUi(
                itemId = itemId,
                editable = true,
                textArea = area,
                counter = counter,
                versionLabel = versionLabel,
                conflictHost = conflictHost,
                resolutionsHost = resolutionsHost,
                tasksHost = tasksHost,
                toolbar = toolbar,
                menuHost = menuHost,
                taskFormHost = taskFormHost,
                readonlyBody = null,
            )
        toolbar.actionButton(ActionIcon.SAVE, tr("Speichern"), style = ButtonStyle.OUTLINEPRIMARY, labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { save(itemId) }
        }
        toolbar.actionButton(ActionIcon.INSERT_RESOLUTION, tr("Beschluss einfügen"), labelMode = ActionLabelMode.RESPONSIVE) {
            onClick {
                val current =
                    controller.draft
                        ?.items
                        ?.firstOrNull { it.agendaItemId == itemId }
                        ?.resolutions
                        .orEmpty()
                val entries =
                    current.map { r ->
                        val text =
                            gettext(
                                "Beschluss %1: %2 – siehe unten.",
                                sanitizeUntrustedI18nText(r.number),
                                sanitizeUntrustedI18nText(r.title),
                            )
                        "${r.number}: ${r.title}" to text
                    }
                when (entries.size) {
                    0 -> showNote(gettext("Zu diesem Punkt gibt es noch keinen Beschluss."))
                    1 -> insertText(itemId, entries.single().second)
                    else -> showMenu(ui, gettext("Beschluss einfügen"), entries)
                }
            }
        }
        toolbar.actionButton(ActionIcon.ADD_TASK, tr("Aufgabe hinzufügen"), labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { controller.draft?.let { renderTaskForm(ui, it) } }
        }
        toolbar.actionButton(ActionIcon.TEXT_SNIPPET, tr("Textbaustein ▾"), labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { showMenu(ui, gettext("Textbaustein"), snippetTexts().map { it to it }) }
        }
        toolbar.actionButton(ActionIcon.TIME_MARK, tr("Zeitmarke"), labelMode = ActionLabelMode.RESPONSIVE) {
            onClick { insertText(itemId, "[${serverTimeText()}] ") }
        }
        return ui
    }

    fun render(
        d: MeetingProtocolDraftDto,
        replace: Set<String>,
    ) {
        banner.content =
            if (d.phase == ProtocolDraftPhase.LOCKED) {
                gettext("Fassung %1 – fertiggestellt. Nur lesen.", d.latestVersionNo ?: 1)
            } else if (d.editable) {
                gettext("Entwurf – nur für Mitglieder des Gremiums sichtbar. Noch nicht fertiggestellt.")
            } else {
                gettext("Entwurf – nur für Mitglieder des Gremiums sichtbar. Noch nicht fertiggestellt. Nur lesen.")
            }
        val newStructure =
            listOf(
                d.items.map { Triple(it.agendaItemId, it.position, it.title) },
                d.editable,
                d.role,
                d.phase,
                d.items.map { it.approval?.result },
            )
        var rebuilt = false
        if (newStructure != structureSlice) {
            structureSlice = newStructure
            rebuilt = true
            items.clear()
            itemsHost.removeAll()
            // the focused field is gone with the rebuild; its blur will not come, so the item must not stay "being edited" forever
            focusedItemId = null
            d.items.forEach { items[it.agendaItemId] = buildItem(item = it, d = d) }
            if (activeItemId == null || activeItemId !in items) activeItemId = d.items.firstOrNull()?.agendaItemId
            // new fields: they show what the person typed, not what the server last said
            d.items.forEach { item -> items[item.agendaItemId]?.textArea?.let { setTextOf(it, controller.localText(item.agendaItemId)) } }
        }
        if (headerSlice != d.header) {
            headerSlice = d.header
            renderHeader(d)
        }
        if (attendanceSlice != d.attendance) {
            attendanceSlice = d.attendance
            renderAttendance(d)
        }
        if (quorumSlice != d.quorum) {
            quorumSlice = d.quorum
            renderQuorum(d)
        }
        if (unassignedSlice != d.unassignedResolutions) {
            unassignedSlice = d.unassignedResolutions
            renderUnassigned(d)
        }
        val newClosing = d.taskCount
        if (closingSlice != newClosing) {
            closingSlice = newClosing
            renderClosing(d)
        }
        d.items.forEach { item ->
            val ui = items[item.agendaItemId] ?: return@forEach
            ui.readonlyBody?.let { shown ->
                if (ui.bodySlice != item.body) {
                    ui.bodySlice = item.body
                    shown.content = sanitizeUntrustedI18nText(item.body)
                }
            }
            if (ui.resolutionsSlice != item.resolutions) {
                ui.resolutionsSlice = item.resolutions
                ui.resolutionsHost.removeAll()
                if (item.resolutions.isNotEmpty()) {
                    ui.resolutionsHost.add(lockedLine())
                    ui.resolutionsHost.h4(tr("Beschlüsse")) { addCssClasses("h6 mb-0") }
                    item.resolutions.forEach { resolutionRow(ui.resolutionsHost, it) }
                }
            }
            if (ui.tasksSlice != item.tasks) {
                ui.tasksSlice = item.tasks
                renderTasks(ui = ui, item = item, editable = d.editable, refresh = { reload() })
                if (item.tasks.isEmpty()) ui.tasksHost.hide() else ui.tasksHost.show()
            }
            if (!rebuilt && item.agendaItemId in replace) {
                ui.textArea?.let { setTextOf(it, controller.localText(item.agendaItemId)) }
                ui.counter?.content =
                    gettext("%1 / %2 Zeichen", protocolCharCount(controller.localText(item.agendaItemId)), PROTOCOL_BLOCK_MAX_LENGTH)
            }
            paintConflict(item.agendaItemId)
        }
        updateToolbars()
        loading.hide()
        body.show()
    }

    // ---- reading ----

    fun scheduleNext() {
        timer?.let { scheduler.cancel(it) }
        timer = null
        if (!active || disposed) return
        timer =
            scheduler.schedule(if (isHidden()) PROTOCOL_POLL_HIDDEN_MS else PROTOCOL_POLL_MS) {
                timer = null
                reload()
            }
    }

    reload = {
        if (!disposed && active) {
            if (inFlight) {
                followUp = true
            } else {
                timer?.let { scheduler.cancel(it) }
                timer = null
                inFlight = true
                AppScope.launch {
                    var answer: MeetingProtocolDraftDto? = null
                    try {
                        answer = rpc.get(meetingId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        inFlight = false
                        throw e
                    } catch (ignored: Throwable) {
                        // Never shown: the calm hint after three misses is the whole reaction, and `message` is never read.
                        answer = null
                    }
                    inFlight = false
                    if (!disposed) {
                        if (answer == null) {
                            failures++
                            if (failures >= PROTOCOL_QUIET_HINT_AFTER_FAILURES) {
                                if (controller.draft == null) {
                                    if (errorHost.getChildren().isEmpty()) {
                                        loading.hide()
                                        errorHost.dataErrorState(onRetry = {
                                            errorHost.removeAll()
                                            loading.show()
                                            failures = 0
                                            reload()
                                        })
                                    }
                                } else {
                                    hint.show()
                                }
                            }
                        } else {
                            failures = 0
                            hint.hide()
                            errorHost.removeAll()
                            syncAllLocal()
                            loadedServerNow = answer.serverNow
                            loadedClock = clock()
                            val replace = controller.applyServer(fresh = answer, focusedItemId = focusedItemId)
                            render(answer, replace)
                        }
                        if (followUp) {
                            followUp = false
                            reload()
                        } else {
                            scheduleNext()
                        }
                    }
                }
            }
        }
    }

    return ProtocolDraftSectionHandle(
        root = root,
        controller = controller,
        setActive = { wanted ->
            val wasActive = active
            active = wanted
            if (wanted) {
                root.show()
                // the sitting panel repaints its tabs on every poll: only the switch to this tab reads, the own timer paces the rest
                if (!wasActive) reload()
            } else {
                timer?.let { scheduler.cancel(it) }
                timer = null
                root.hide()
            }
        },
        refreshNow = { reload() },
        dispose = {
            disposed = true
            timer?.let { scheduler.cancel(it) }
            timer = null
        },
    )
}
