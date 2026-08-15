package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.TextArea
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.shared.domain.ConferenceNotesStateDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.NOTES_MAX_CONTENT_LENGTH
import network.lapis.cloud.shared.domain.NoteBlockBroadcastDto
import network.lapis.cloud.shared.domain.NoteBlockCreateWireDto
import network.lapis.cloud.shared.domain.NoteBlockDto
import network.lapis.cloud.shared.domain.NoteBlockEditWireDto
import network.lapis.cloud.shared.rpc.IConferenceNotesService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * V1.0 Wave 8 "Geteilte Notizen", security-audit fix -- debounce window for [ConferenceNotesController]'s
 * own `scheduleNotesRefresh`, same coalescing idiom as `ConferenceScreen.kt`'s own
 * `GUEST_HOMESERVER_REFRESH_DEBOUNCE_MS`/`scheduleGuestHomeserverRefresh` (security-audit fix, "DoS
 * amplification"): a burst of `lapis-notes-commit` packets -- genuine (several participants saving in
 * quick succession) OR forged (a malicious peer spamming the topic, see class KDoc "Required change #3")
 * -- coalesces into a single trailing [network.lapis.cloud.shared.rpc.IConferenceNotesService.getNotesState]
 * call, not one per packet.
 */
internal const val NOTES_REFRESH_DEBOUNCE_MS = 400L

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- self-contained panel, mirroring
 * [ConferenceWhiteboardController]'s own "self-contained conference sub-feature in its own file"
 * precedent. Design review verdict (root `CLAUDE.md` "UI/UX-Design-Team"):
 *
 * - **Placement**: a collapsible panel below the video grid, same `controlsRow` toggle-button +
 *   `vPanel().hide()` pattern as Chat/Whiteboard, `!isBreakout`-gated identically -- see
 *   `ConferenceScreen.kt` wiring.
 * - **Edit affordance**: a `textArea` + explicit per-row "Speichern" button, NO auto-commit on
 *   blur. Every form in this codebase (agenda add, motions, DSGVO records, chat compose) commits on
 *   an explicit button click, never on blur -- there is zero blur-triggered-mutation precedent
 *   anywhere in this codebase to break from, and blur-commit would be actively dangerous in THIS
 *   exact UI (clicking over to the video grid or chat panel to glance at something would silently
 *   commit a half-finished sentence).
 * - **Delete affordance**: own-last-editor OR moderator, gated client-side as a UX hint only (the
 *   server's own gate in `ConferenceNotesService.deleteBlock` is the sole authority regardless).
 *   Gets a lightweight INLINE two-step confirm ("Entfernen" -> "Wirklich entfernen? Ja/Nein" in
 *   place, reverts if not confirmed) -- deliberately NOT [ConferenceWhiteboardController]'s heavier
 *   Tier-3 modal (a single block's blast radius is narrower than a whole-board wipe), but MORE
 *   friction than the agenda list's own zero-step "Entfernen" (a note block can hold up to 8,000
 *   characters of composed prose, materially harder to reconstruct from memory than a one-line
 *   agenda title, combined with a wider authorization surface than any comparable destructive
 *   action in this module).
 * - **"Als Dokument speichern"**: mirrors `whiteboardSaveAsDocumentDialog`'s shape and non-danger
 *   framing exactly -- saving is additive and repeatable.
 *
 * ## Required change #1 -- focus protection (design review, blocking)
 *
 * Unlike Whiteboard/Chat, this panel embeds a LIVE-EDITABLE form control (the per-row `textArea`)
 * INSIDE the exact same structure that incoming broadcasts/[applyState] would otherwise rebuild.
 * Without protection, a participant composing a paragraph in block A would have their textarea torn
 * down (losing cursor position, possibly the whole unsaved draft) every time ANYONE ELSE saves ANY
 * block, including blocks they aren't even looking at -- at this wave's own edit-rate budget across
 * up to 25 participants, this could fire every few seconds during a working session. [focusedBlockId]
 * tracks which row's textarea currently holds focus (native `focus`/`blur` DOM listeners, same
 * raw-DOM-under-`addAfterInsertHook` discipline `ConferenceScreen.kt`'s own inline-rename `editInput`
 * hook already uses); [syncRow] skips overwriting that row's rendered textarea value AND its
 * [RowUi.editingBaseVersion] for as long as it stays focused -- see [syncRow] KDoc for why freezing
 * BOTH (not just the visible text) is what keeps the version-conflict system correct, not just the
 * rendering.
 *
 * ## Required change #2 -- conflict-banner retry must actually be able to succeed (design review, blocking)
 *
 * On a stale `baseVersion`, EITHER banner action ("Verwerfen und aktuelle Version übernehmen" /
 * "Weiter bearbeiten") advances [RowUi.editingBaseVersion] to the server's current value -- see
 * [showConflict]. Without this, "Weiter bearbeiten" would let a participant keep typing but every
 * subsequent "Speichern" click would resubmit against the SAME stale version and be rejected again,
 * forever -- offering a retry path engineered to never work, failing this wave's own explicit
 * requirement ("let the participant retry, not silently lose their edit").
 *
 * ## Required change #3 -- a broadcast is a resync HINT, never a truth source (security-audit fix, blocking)
 *
 * The pre-fix version of [applyCommitBroadcast] wrote a `lapis-notes-commit` packet's own
 * `content`/`version` fields DIRECTLY into [blocks]/the row, on the reasoning (see the superseded
 * `LiveKitRoomSession` KDoc "Notes trust boundary") that this "grants an attacker nothing they could
 * not already do for real via `commitBlockEdit`". That reasoning was wrong: this server never
 * observes LiveKit data-channel traffic at all (by design, same as Whiteboard), so nothing binds a
 * broadcast's `(blockId, content, version)` tuple to an actual server-accepted
 * `commitBlockEdit`/`createBlock` result. Any current participant already holds a LiveKit publish
 * token and can publish an arbitrary, structurally-valid [NoteBlockBroadcastDto] straight onto the
 * topic -- for an EXISTING `blockId` with attacker-chosen content and an inflated `version` (every
 * other open panel renders it as a genuine edit, correctly attributed to the attacker's real SDK
 * identity but never actually persisted/CAS-accepted server-side), or for a brand-new `blockId` that
 * was never created via [network.lapis.cloud.shared.rpc.IConferenceNotesService.createBlock] at all.
 * Worse than a purely cosmetic defacement: it silently poisons [RowUi.editingBaseVersion] for every
 * OTHER participant's copy of that row, so their next genuine "Speichern" is spuriously rejected as
 * stale against a version the server never actually stored -- and the forged content is invisible to
 * [doSaveAsDocument] (which reads authoritative server state), a live-view-vs-saved-document
 * divergence. [applyCommitBroadcast] therefore no longer trusts the packet's payload AT ALL --
 * [broadcast] is used purely as an edge-triggered "something changed, go fetch the truth" signal
 * (same "raw push is a trigger, not a value" discipline `ConferenceScreen.kt`'s own
 * `onRecordingStatusChanged`/D8 already uses for a different LiveKit signal), coalesced via
 * `scheduleNotesRefresh` into a debounced [network.lapis.cloud.shared.rpc.IConferenceNotesService.getNotesState]
 * call whose RESPONSE (never the packet) is the only thing ever written into [blocks]/rows, through
 * the SAME [applyState] path late-joiners already use -- which is also why [applyState]'s existing
 * focus-protection (Required change #1) already covers this path for free, no separate guard needed.
 */
class ConferenceNotesController(
    panel: SimplePanel,
    private val roomId: String,
    private val canModerate: Boolean,
    private val localMemberId: String,
    private val session: LiveKitRoomSession,
) {
    /** Background model -- ALWAYS reflects the latest known server-confirmed state, including for the currently-focused row (see class KDoc "Required change #1"). Rendered via `blocks.values.sortedBy { it.position }`. */
    private val blocks = LinkedHashMap<String, NoteBlockDto>()

    private class RowUi(
        val blockId: String,
        val container: SimplePanel,
        val textAreaWidget: TextArea,
        val captionDiv: Div,
        val conflictBox: SimplePanel,
        val deleteButton: Button?,
        val deleteConfirmRow: SimplePanel?,
        var editingBaseVersion: Int,
    )

    private val rows = LinkedHashMap<String, RowUi>()
    private var focusedBlockId: String? = null

    /** Security-audit fix, see class KDoc "Required change #3" -- the pending debounced [scheduleNotesRefresh] job, if any. */
    private var notesRefreshJob: Job? = null

    private val listPanel: SimplePanel
    private val emptyStateDiv: Div
    private var addButton: Button? = null
    private var saveDocButton: Button? = null

    init {
        panel.removeAll()
        panel.h2(tr("Notizen")) { addCssClass("h6") }
        listPanel = panel.vPanel(spacing = 2)
        emptyStateDiv = panel.div(tr("Noch keine Notizen.")) { addCssClasses("text-muted small") }
        renderAddBlockForm(panel)
        val saveRow = panel.hPanel(spacing = 6) { addCssClasses("mt-2") }
        val save = saveRow.button(tr("Als Dokument speichern"), style = ButtonStyle.OUTLINEPRIMARY)
        save.addCssClass("btn-sm")
        save.onClick { notesSaveAsDocumentDialog { level -> doSaveAsDocument(level) } }
        saveDocButton = save
        updateEmptyState()
    }

    /** V1.0 Wave 8 -- called once, right after `getNotesState` resolves (late-joiner/panel-reopen seed, see [network.lapis.cloud.shared.rpc.IConferenceNotesService.getNotesState] KDoc). */
    fun applyState(state: ConferenceNotesStateDto) {
        val incomingIds = state.blocks.map { it.id }.toSet()
        val staleIds = rows.keys.filter { it !in incomingIds }
        staleIds.forEach { id -> removeRow(id) }
        blocks.clear()
        state.blocks.forEach { block -> blocks[block.id] = block }
        state.blocks.sortedBy { it.position }.forEach { block -> syncRow(block) }
        updateEmptyState()
    }

    /**
     * Wired to [LiveKitRoomSession]'s `onNotesCommit` callback.
     *
     * Security-audit fix, see class KDoc "Required change #3" -- [broadcast]'s own
     * `content`/`version`/[authorMemberId]/[authorDisplayName] are DELIBERATELY never read here.
     * This server never observes LiveKit data-channel traffic, so nothing binds those fields to an
     * actual server-accepted commit; treating them as authoritative let any current participant
     * deface a block or inject a fake one, and poison other participants' [RowUi.editingBaseVersion]
     * into rejecting their own genuine, non-stale saves. The packet is used ONLY as an edge-triggered
     * "something changed" signal -- [scheduleNotesRefresh] fetches the real state and reconciles
     * through [applyState], the same authoritative path late-joiners use.
     */
    fun applyCommitBroadcast(
        authorMemberId: String,
        authorDisplayName: String,
        broadcast: NoteBlockBroadcastDto,
    ) {
        scheduleNotesRefresh()
    }

    /**
     * Security-audit fix, see class KDoc "Required change #3". Debounced/coalesced -- same idiom as
     * `ConferenceScreen.kt`'s own `scheduleGuestHomeserverRefresh` (security-audit fix, "DoS
     * amplification"): any burst of `lapis-notes-commit` packets within [NOTES_REFRESH_DEBOUNCE_MS]
     * (genuine concurrent saves, or a malicious peer spamming forged packets) collapses into a SINGLE
     * trailing [network.lapis.cloud.shared.rpc.IConferenceNotesService.getNotesState] call -- each new
     * broadcast cancels the still-pending previous job before scheduling its own.
     */
    private fun scheduleNotesRefresh() {
        notesRefreshJob?.cancel()
        notesRefreshJob =
            AppScope.launch {
                delay(NOTES_REFRESH_DEBOUNCE_MS)
                val state = guarded { rpcService<IConferenceNotesService>().getNotesState(roomId) } ?: return@launch
                applyState(state)
            }
    }

    // ── row sync (focus-aware, see class KDoc "Required change #1") ──────

    /**
     * Creates the row for [block] if it does not exist yet, otherwise refreshes it. The caption
     * ("zuletzt bearbeitet von ...") and delete-affordance visibility always refresh live -- purely
     * informational/non-destructive. The TEXTAREA VALUE and [RowUi.editingBaseVersion] are skipped
     * entirely while this row is [focusedBlockId] -- see class KDoc for why freezing
     * [RowUi.editingBaseVersion] (not just the visible text) matters: if an incoming remote edit
     * silently advanced the focused row's base version, a subsequent "Speichern" click would submit
     * against content the participant never actually saw, turning optimistic concurrency into a
     * silent last-writer-wins. [blocks] itself (the background model) is updated by the CALLER
     * regardless of focus, so other computations (`nextPosition`, other rows) always see fresh data.
     */
    private fun syncRow(block: NoteBlockDto) {
        val existingRow = rows[block.id]
        if (existingRow == null) {
            createRow(block)
            return
        }
        existingRow.captionDiv.content = captionText(block)
        updateDeleteVisibility(existingRow, block)
        if (focusedBlockId == block.id) return
        existingRow.textAreaWidget.value = block.content
        existingRow.editingBaseVersion = block.version
    }

    private fun removeRow(blockId: String) {
        val row = rows.remove(blockId) ?: return
        listPanel.remove(row.container)
        if (focusedBlockId == blockId) focusedBlockId = null
    }

    private fun captionText(block: NoteBlockDto): String = gettext("Zuletzt bearbeitet von %1", block.lastEditedByDisplayName)

    private fun canDeleteBlock(block: NoteBlockDto): Boolean = canModerate || block.lastEditedByMemberId == localMemberId

    private fun updateDeleteVisibility(
        row: RowUi,
        block: NoteBlockDto,
    ) {
        val allowed = canDeleteBlock(block)
        if (allowed) row.deleteButton?.show() else row.deleteButton?.hide()
        if (!allowed) row.deleteConfirmRow?.hide()
    }

    private fun updateEmptyState() {
        if (rows.isEmpty()) emptyStateDiv.show() else emptyStateDiv.hide()
    }

    private fun createRow(block: NoteBlockDto) {
        val container = listPanel.vPanel(spacing = 2) { addCssClasses("border-bottom pb-2 mb-2") }
        val textAreaWidget = container.textArea(value = block.content, rows = 3) { addCssClasses("w-100") }
        val captionDiv = container.div(captionText(block)) { addCssClasses("text-muted small") }
        val conflictBox = container.vPanel(spacing = 2) { addCssClasses("border rounded p-2 mt-1") }
        conflictBox.hide()
        val actionRow = container.hPanel(spacing = 6) { addCssClasses("mt-1") }
        val saveButton = actionRow.button(tr("Speichern"), style = ButtonStyle.OUTLINEPRIMARY)
        saveButton.addCssClass("btn-sm")

        val deleteButton = actionRow.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
        deleteButton.addCssClass("btn-sm")
        val deleteConfirmRow = actionRow.hPanel(spacing = 6)
        deleteConfirmRow.hide()
        deleteConfirmRow.div(tr("Wirklich entfernen?")) { addCssClasses("text-muted small") }
        val confirmYes = deleteConfirmRow.button(tr("Ja"), style = ButtonStyle.DANGER)
        confirmYes.addCssClass("btn-sm")
        val confirmNo = deleteConfirmRow.button(tr("Nein"), style = ButtonStyle.OUTLINESECONDARY)
        confirmNo.addCssClass("btn-sm")

        val rowUi =
            RowUi(
                blockId = block.id,
                container = container,
                textAreaWidget = textAreaWidget,
                captionDiv = captionDiv,
                conflictBox = conflictBox,
                deleteButton = deleteButton,
                deleteConfirmRow = deleteConfirmRow,
                editingBaseVersion = block.version,
            )
        rows[block.id] = rowUi
        updateDeleteVisibility(rowUi, block)

        // Raw-DOM focus/blur, same discipline as ConferenceScreen.kt's own inline-rename `editInput`
        // hook (`addAfterInsertHook` + defensive `querySelector` fallback) -- see class KDoc
        // "Required change #1".
        textAreaWidget.addAfterInsertHook { vnode ->
            val root = vnode.elm as? HTMLElement
            val el = (root as? HTMLTextAreaElement) ?: root?.querySelector("textarea") as? HTMLTextAreaElement
            el?.addEventListener("focus", { focusedBlockId = block.id })
            el?.addEventListener(
                "blur",
                {
                    if (focusedBlockId == block.id) focusedBlockId = null
                },
            )
        }

        saveButton.onClick { doSaveBlockEdit(rowUi) }
        deleteButton.onClick {
            deleteButton.hide()
            deleteConfirmRow.show()
        }
        confirmNo.onClick {
            deleteConfirmRow.hide()
            deleteButton.show()
        }
        confirmYes.onClick { doDeleteBlock(block.id) }

        updateEmptyState()
    }

    // ── edit / conflict handling ──────────────────────────────────────────

    private fun doSaveBlockEdit(row: RowUi) {
        val content = row.textAreaWidget.value.orEmpty()
        if (content.isBlank()) {
            notifyError(tr("Notizblock darf nicht leer sein."))
            return
        }
        if (content.length > NOTES_MAX_CONTENT_LENGTH) {
            notifyError(gettext("Notizblock ist zu lang (max. %1 Zeichen).", NOTES_MAX_CONTENT_LENGTH))
            return
        }
        val baseVersion = row.editingBaseVersion
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IConferenceNotesService>()
                        .commitBlockEdit(roomId, NoteBlockEditWireDto(row.blockId, content, baseVersion))
                } ?: return@launch
            // `block` is captured into a local `val` here (not read repeatedly off `result.block`)
            // because Kotlin does not smart-cast a nullable property declared in a DIFFERENT module
            // (result.block is a lapis-shared type) even after an explicit `!= null` check --
            // capturing into a local val is what makes the non-null type available below.
            val block = result.block
            when {
                result.accepted && block != null -> {
                    blocks[row.blockId] = block
                    row.editingBaseVersion = block.version
                    row.captionDiv.content = captionText(block)
                    hideConflict(row)
                    runCatching {
                        session.sendNotesCommit(
                            NoteBlockBroadcastDto(block.id, block.content, block.position, block.version),
                        )
                    }
                }
                !result.accepted && block != null -> showConflict(row, block)
                else -> {
                    // accepted == false, block == null -- deleted concurrently, see
                    // NoteBlockCommitResultDto KDoc.
                    blocks.remove(row.blockId)
                    removeRow(row.blockId)
                    updateEmptyState()
                    notifyError(tr("Dieser Block wurde von jemandem entfernt."))
                }
            }
        }
    }

    /**
     * See class KDoc "Required change #2" -- BOTH actions advance [RowUi.editingBaseVersion] to
     * [current]'s version, so a subsequent "Speichern" click can actually succeed.
     */
    private fun showConflict(
        row: RowUi,
        current: NoteBlockDto,
    ) {
        blocks[row.blockId] = current
        row.conflictBox.removeAll()
        row.conflictBox.div(
            gettext("Jemand hat diesen Block bereits aktualisiert (zuletzt von %1).", current.lastEditedByDisplayName),
        ) { addCssClasses("small fw-bold") }
        row.conflictBox.div(current.content) { addCssClasses("small text-muted") }
        val actions = row.conflictBox.hPanel(spacing = 6) { addCssClasses("mt-1") }
        val discard = actions.button(tr("Verwerfen und aktuelle Version übernehmen"), style = ButtonStyle.OUTLINEWARNING)
        discard.addCssClass("btn-sm")
        discard.onClick {
            row.textAreaWidget.value = current.content
            row.editingBaseVersion = current.version
            hideConflict(row)
        }
        val keepEditing = actions.button(tr("Weiter bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
        keepEditing.addCssClass("btn-sm")
        keepEditing.onClick {
            row.editingBaseVersion = current.version
            hideConflict(row)
        }
        row.conflictBox.show()
    }

    private fun hideConflict(row: RowUi) {
        row.conflictBox.removeAll()
        row.conflictBox.hide()
    }

    private fun doDeleteBlock(blockId: String) {
        AppScope.launch {
            val result = guarded { rpcService<IConferenceNotesService>().deleteBlock(roomId, blockId) }
            if (result != null) {
                blocks.remove(blockId)
                removeRow(blockId)
                updateEmptyState()
                notifySuccess(tr("Notizblock entfernt."))
            }
        }
    }

    // ── add block ────────────────────────────────────────────────────────

    private fun renderAddBlockForm(panel: SimplePanel) {
        val formPanel = panel.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-2") }
        formPanel.p(tr("Notizblock hinzufügen")) { addCssClasses("fw-bold small mb-1") }
        val contentInput = formPanel.textArea(rows = 2, label = tr("Inhalt"))
        val add = formPanel.button(tr("Hinzufügen"), style = ButtonStyle.OUTLINEPRIMARY)
        add.addCssClass("btn-sm")
        add.onClick { doAddBlock(contentInput) }
        addButton = add
    }

    private fun doAddBlock(input: TextArea) {
        val content = input.value.orEmpty().trim()
        if (content.isBlank()) {
            notifyError(tr("Bitte einen Inhalt eingeben."))
            return
        }
        if (content.length > NOTES_MAX_CONTENT_LENGTH) {
            notifyError(gettext("Notizblock ist zu lang (max. %1 Zeichen).", NOTES_MAX_CONTENT_LENGTH))
            return
        }
        // Client-computed nextPosition can collide under concurrent adds by two participants at the
        // exact same instant -- harmless: position is a cosmetic, self-healing sort key with no
        // safety implications, see NoteBlockBroadcastDto.isStructurallyValid KDoc.
        val nextPosition = (blocks.values.maxOfOrNull { it.position } ?: 0) + 1
        val blockId = "$localMemberId-${Clock.System.now().toEpochMilliseconds()}-${Uuid.random()}"
        addButton?.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<IConferenceNotesService>()
                        .createBlock(roomId, NoteBlockCreateWireDto(blockId, content, nextPosition))
                }
            addButton?.disabled = false
            if (result != null) {
                input.value = ""
                blocks[result.id] = result
                syncRow(result)
                updateEmptyState()
                runCatching {
                    session.sendNotesCommit(NoteBlockBroadcastDto(result.id, result.content, result.position, result.version))
                }
            }
        }
    }

    // ── save-as-document ────────────────────────────────────────────────

    private fun doSaveAsDocument(accessLevel: DocumentAccessLevel) {
        val button = saveDocButton ?: return
        button.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IConferenceNotesService>().saveAsDocument(roomId, accessLevel) }
            button.disabled = false
            if (result != null) {
                notifySuccess(tr("Notizen als Dokument gespeichert."))
            }
        }
    }
}

/**
 * Design review: mirrors `whiteboardSaveAsDocumentDialog`'s shape and non-danger framing exactly --
 * saving is additive and repeatable, unlike starting a recording that exposes live speech.
 */
private fun notesSaveAsDocumentDialog(onConfirm: (DocumentAccessLevel) -> Unit) {
    val modal = Modal(caption = tr("Notizen als Dokument speichern"))
    modal.div(
        tr("Der aktuelle Stand der geteilten Notizen wird als Markdown-Dokument in der Dokumentenablage gespeichert."),
    ) { addCssClasses("small mb-2") }
    val accessOptions = DocumentAccessLevel.entries.map { it.name to conferenceRecordingAccessLevelLabel(it) }
    val accessSelect =
        modal.select(options = accessOptions, value = DocumentAccessLevel.BOARD_ONLY.name, label = tr("Zugriffsebene"))
    modal.div(
        tr(
            "Bei \"Vorstand\" können anwesende Mitglieder, die nicht dem Vorstand angehören, das " +
                "gespeicherte Dokument später NICHT ansehen -- wählen Sie \"Mitglieder\", wenn es allen " +
                "Teilnehmenden zugänglich sein soll.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Speichern"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                val level = accessSelect.value?.let { DocumentAccessLevel.valueOf(it) } ?: DocumentAccessLevel.BOARD_ONLY
                modal.hide()
                onConfirm(level)
            }
        },
    )
    modal.show()
}
