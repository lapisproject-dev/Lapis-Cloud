package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.conferenceDeviceErrorMessage
import network.lapis.cloud.client.conferenceDeviceOptionLabel
import network.lapis.cloud.client.conferenceDeviceSelectionToApply
import network.lapis.cloud.client.conferenceDeviceStorageKey
import network.lapis.cloud.client.conferenceDeviceSubject
import network.lapis.cloud.client.conferenceStoredDeviceId
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.setAttrIfChanged
import network.lapis.cloud.client.untrustedContent
import network.lapis.cloud.client.untrustedOptions
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.get

/**
 * V1.9.91 -- the device choice of the encounter room: who may choose what.
 *
 * | Role | Microphone | Camera | Speaker |
 * |---|---|---|---|
 * | office holder (pulpit, steward) | yes | yes | yes |
 * | congregation, at a table | the table microphone | -- | yes |
 * | congregation, no table | -- | -- | yes |
 *
 * The speaker field exists only when the browser has `setSinkId` AND offers at least two usable output devices. Without the API the
 * picker shows a plain sentence instead of a dead control. The picker NEVER asks the browser for a permission: it lists what is already
 * visible, and a device's name appears only after the person has switched a microphone or camera on once.
 *
 * **Privacy.** Choosing a device is local. A device id or name is never sent anywhere, never logged, never put into an announcement or
 * an error message (a label is often a person's name: "Iraklis iPhone-Mikrofon"). The ONLY thing written to the browser is the device
 * id of a choice the person made on purpose, under the same key the video conference already uses ([conferenceDeviceStorageKey]); a
 * key of its own would additionally record that this browser visited an encounter room. Listing, opening, synchronising or a failed
 * switch never write. ([encounterRememberDevice] is the only writer.)
 *
 * The room only hands over the pieces (the speaker session, the table's microphone devices, the audio output); a device id never
 * passes through `EncounterRoom.kt` or `EncounterTables.kt`, so a file with a remote call and a file with a device id are never the
 * same file (`ClientEncounterPrivacyTripwireTest`).
 */
internal enum class EncounterDeviceRole { OFFICE_HOLDER, CONGREGATION_AT_TABLE, CONGREGATION }

/** One field of the panel; the pure result of [encounterDeviceFields]. */
internal sealed interface EncounterDeviceField {
    val kind: ConferenceDeviceKind

    /** A real choice. [selected] is the active device (`null` = none known); [disabled] while the table is quieted. */
    data class Choice(
        override val kind: ConferenceDeviceKind,
        val options: List<ConferenceDeviceOption>,
        val selected: String?,
        val disabled: Boolean = false,
    ) : EncounterDeviceField

    /** No device is visible yet: the browser shows device names only after one use. A sentence instead of an empty field. */
    data class PermissionHint(
        override val kind: ConferenceDeviceKind,
    ) : EncounterDeviceField

    /** The table is quieted: the table microphone cannot be chosen. */
    data class TableQuieted(
        override val kind: ConferenceDeviceKind,
    ) : EncounterDeviceField
}

/**
 * The fields of the panel for [role]. Speaker: only with the sink API and at least two usable outputs (the Chrome alias `default` does not
 * count: "system default plus one device" is no choice). The speaker field starts with the "system default" entry.
 */
internal fun encounterDeviceFields(
    role: EncounterDeviceRole,
    tableQuieted: Boolean,
    sinkApi: Boolean,
    microphones: List<ConferenceDeviceOption>,
    cameras: List<ConferenceDeviceOption>,
    outputs: List<ConferenceDeviceOption>,
    active: Map<ConferenceDeviceKind, String?>,
): List<EncounterDeviceField> {
    fun inputField(
        kind: ConferenceDeviceKind,
        options: List<ConferenceDeviceOption>,
        quieted: Boolean,
    ): EncounterDeviceField =
        when {
            quieted && options.isEmpty() -> EncounterDeviceField.TableQuieted(kind)
            options.isEmpty() -> EncounterDeviceField.PermissionHint(kind)
            else -> EncounterDeviceField.Choice(kind, options, active[kind], disabled = quieted)
        }
    return buildList {
        when (role) {
            EncounterDeviceRole.OFFICE_HOLDER -> {
                add(inputField(ConferenceDeviceKind.MICROPHONE, microphones, quieted = false))
                add(inputField(ConferenceDeviceKind.CAMERA, cameras, quieted = false))
            }
            EncounterDeviceRole.CONGREGATION_AT_TABLE ->
                add(inputField(ConferenceDeviceKind.MICROPHONE, microphones, quieted = tableQuieted))
            EncounterDeviceRole.CONGREGATION -> Unit
        }
        val usable = encounterUsableOutputs(outputs)
        if (sinkApi && usable.size >= 2) {
            add(
                EncounterDeviceField.Choice(
                    kind = ConferenceDeviceKind.SPEAKER,
                    options = listOf(ConferenceDeviceOption(ENCOUNTER_SYSTEM_DEFAULT_OUTPUT, "")) + usable,
                    selected = active[ConferenceDeviceKind.SPEAKER] ?: ENCOUNTER_SYSTEM_DEFAULT_OUTPUT,
                ),
            )
        }
    }
}

/** The button exists while at least one field is a real, enabled choice. A hint alone is no reason for a control in the bar. */
internal fun encounterDeviceButtonVisible(fields: List<EncounterDeviceField>): Boolean =
    fields.any { it is EncounterDeviceField.Choice && !it.disabled }

/** The sentence "choose the speaker in your device's settings": only where the person has an input device field and no speaker field is possible. */
internal fun encounterSpeakerNoteVisible(
    fields: List<EncounterDeviceField>,
    sinkApi: Boolean,
): Boolean = !sinkApi && fields.any { it.kind != ConferenceDeviceKind.SPEAKER }

/** The accessible name of the bar button: it names what can be chosen. */
internal fun encounterDeviceButtonLabel(
    role: EncounterDeviceRole,
    fields: List<EncounterDeviceField>,
): String {
    val speaker = fields.any { it.kind == ConferenceDeviceKind.SPEAKER && it is EncounterDeviceField.Choice }
    val microphone = fields.any { it.kind == ConferenceDeviceKind.MICROPHONE && it is EncounterDeviceField.Choice }
    return when {
        role == EncounterDeviceRole.OFFICE_HOLDER -> gettext("Geräte wählen")
        speaker && microphone -> gettext("Mikrofon und Lautsprecher wählen")
        speaker -> gettext("Lautsprecher wählen")
        else -> gettext("Geräte wählen")
    }
}

/** What to do with the remembered device: [id] is the one to switch to (`null` = nothing to do); [fellBack] = the remembered device is not there. */
internal data class EncounterDeviceResolution(
    val id: String?,
    val fellBack: Boolean,
)

/**
 * Compares the remembered device [stored] with the visible [options] and the [active] one. Nothing remembered, or nothing visible to
 * compare with (no permission yet), means "nothing to do" -- never a claim that a device is missing.
 */
internal fun encounterResolveDevice(
    stored: String?,
    options: List<ConferenceDeviceOption>,
    active: String?,
): EncounterDeviceResolution {
    val wanted = conferenceStoredDeviceId(stored)?.trim()?.takeIf { it.isNotEmpty() } ?: return EncounterDeviceResolution(null, false)
    if (options.isEmpty()) return EncounterDeviceResolution(null, false)
    if (options.none { it.deviceId == wanted }) return EncounterDeviceResolution(null, true)
    return EncounterDeviceResolution(if (wanted == active) null else wanted, false)
}

/**
 * The active device vanished during the visit (unplugged): `null` while it is still there (or nothing is known), otherwise the first
 * device of the list (for the speaker that is the system default, which stands first).
 */
internal fun encounterVanishedFallback(
    active: String?,
    options: List<ConferenceDeviceOption>,
): String? {
    val known = active?.takeIf { it.isNotBlank() } ?: return null
    if (options.isEmpty() || options.any { it.deviceId == known }) return null
    return options.first().deviceId
}

// ── the browser storage: the ONLY place of the encounter room besides the scene toggle ───────────────────────────────────

/** The remembered device id of [kind] (a blank or unreadable value counts as nothing). Reading never writes. */
internal fun encounterStoredDevice(kind: ConferenceDeviceKind): String? =
    try {
        conferenceStoredDeviceId(localStorage[conferenceDeviceStorageKey(kind)])
    } catch (e: Throwable) {
        null
    }

/** Remembers the device id of a choice the person made on purpose (`null` = the system default: the key is removed). Never a name. */
internal fun encounterRememberDevice(
    kind: ConferenceDeviceKind,
    id: String?,
) {
    try {
        if (id == null) {
            localStorage.removeItem(conferenceDeviceStorageKey(kind))
        } else {
            localStorage.setItem(conferenceDeviceStorageKey(kind), id)
        }
    } catch (e: Throwable) {
        // blocked storage: the choice simply is not remembered
    }
}

/**
 * The panel and its bar button. One row per kind of device; the rows are created once and only updated, so a refresh never replaces a
 * widget the person is using. The panel is not a dialog and has no focus trap: Escape, a click outside or Tab out of it closes it.
 */
internal class EncounterDevicePicker(
    devicesGroup: Container,
    panelParent: Container,
    private val role: () -> EncounterDeviceRole,
    private val speaker: EncounterSpeakerSession?,
    private val tableMic: () -> EncounterTableMicrophoneDevices?,
    private val tableQuieted: () -> Boolean,
    private val output: EncounterAudioOutput,
    private val env: EncounterDeviceEnvironment,
    private val announce: (String) -> Unit,
    private val onBeforeOpen: () -> Unit,
    private val onVisibilityChanged: () -> Unit,
) {
    /** Icon-only (R58 named exception c, the bar's own factory): the name is set per role in [refresh]. */
    val button: Button = devicesGroup.encounterControlButton(ActionIcon.SETTINGS, tr("Geräte wählen"))
    private val panel: Div = panelParent.div(className = "lapis-encounter-device-panel")
    private val rows: Map<ConferenceDeviceKind, Row>
    private val speakerNote: Div
    private val rememberedNote: Div
    private var isOpen = false
    private var disposed = false
    private var applying = false
    private var refreshing = false
    private var refreshQueued = false
    private var queuedFromDeviceChange = false
    private var deferredRefresh = false
    private var wantVisible = false
    private var lastMicrophones: List<ConferenceDeviceOption> = emptyList()
    private val lastChosen = mutableMapOf<ConferenceDeviceKind, String>()
    private val fellBack = mutableSetOf<ConferenceDeviceKind>()
    private val timers = mutableListOf<Int>()
    private var closeListeners: (() -> Unit)? = null
    private val stopDeviceChange: () -> Unit

    init {
        button.setAttribute("aria-expanded", "false")
        button.setAttribute("aria-controls", PANEL_ID)
        button.hide()
        button.onClick { if (isOpen) close(returnFocus = true) else open() }
        panel.setAttribute("id", PANEL_ID)
        panel.setAttribute("role", "group")
        panel.setAttribute("aria-labelledby", HEADING_ID)
        panel.div(tr("Geräte"), className = "lapis-encounter-device-heading fw-bold").setAttribute("id", HEADING_ID)
        rows =
            listOf(
                ConferenceDeviceKind.MICROPHONE to tr("Mikrofon"),
                ConferenceDeviceKind.CAMERA to tr("Kamera"),
                ConferenceDeviceKind.SPEAKER to tr("Lautsprecher"),
            ).associate { (kind, label) -> kind to Row(kind, label) }
        speakerNote =
            panel.div(
                tr("Den Lautsprecher wählen Sie in diesem Browser über die Einstellungen Ihres Geräts."),
                className = "text-muted small",
            )
        speakerNote.hide()
        rememberedNote = panel.div(className = "text-muted small")
        rememberedNote.setAttribute("role", "status")
        rememberedNote.hide()
        panel.hide()
        stopDeviceChange = env.onDeviceChange { if (!disposed) AppScope.launch { refresh(preserveFocus = true) } }
    }

    private inner class Row(
        val kind: ConferenceDeviceKind,
        label: String,
    ) {
        private val wrapper: Div = panel.div(className = "lapis-encounter-device-row")
        val select: Select = wrapper.select(options = emptyList(), value = "", label = label)
        private val activeLine: Div = wrapper.div(className = "text-muted small")
        private val note: Div = wrapper.div(className = "text-muted small")
        var shownPairs: List<Pair<String, String>> = emptyList()

        init {
            val describedBy = "$ACTIVE_ID_PREFIX${kind.name.lowercase()}"
            activeLine.setAttribute("id", describedBy)
            select.input.setAttribute("aria-describedby", describedBy)
            select.input.setAttribute("autocomplete", "off")
            wrapper.hide()
            note.hide()
            activeLine.hide()
            select.subscribe { picked ->
                val id = conferenceDeviceSelectionToApply(applyingProgrammatic = applying, deviceId = picked)
                if (id != null) onPicked(kind, id)
            }
        }

        val hasFocus: Boolean get() = document.activeElement != null && document.activeElement === select.input.getElement()

        val usable: Boolean get() = wrapper.visible && select.visible && !select.disabled

        fun focus() {
            (select.input.getElement() as? HTMLElement)?.focus()
        }

        fun show(field: EncounterDeviceField?) {
            when (field) {
                null -> wrapper.hide()
                is EncounterDeviceField.Choice -> {
                    wrapper.show()
                    note.hide()
                    select.show()
                    val pairs = pairsFor(kind, field.options)
                    if (hasFocus) {
                        // never replace the options under the person's hands; the blur catches up
                        deferredRefresh = true
                    } else {
                        if (pairs != shownPairs) {
                            applying = true
                            select.options = untrustedOptions(pairs)
                            applying = false
                            shownPairs = pairs
                        }
                        val wanted = field.selected ?: pairs.firstOrNull()?.first.orEmpty()
                        if (select.value != wanted) {
                            applying = true
                            select.value = wanted
                            applying = false
                        }
                    }
                    select.disabled = field.disabled
                    val activeLabel = pairs.firstOrNull { it.first == field.selected }?.second
                    if (activeLabel == null) {
                        activeLine.hide()
                    } else {
                        untrustedContent(activeLine, gettext("Aktiv: %1", activeLabel))
                        activeLine.show()
                    }
                }
                is EncounterDeviceField.PermissionHint -> {
                    wrapper.show()
                    select.hide()
                    activeLine.hide()
                    note.content =
                        gettext("Die Gerätenamen zeigt der Browser erst, wenn Sie Mikrofon oder Kamera einmal eingeschaltet haben.")
                    note.show()
                }
                is EncounterDeviceField.TableQuieted -> {
                    wrapper.show()
                    select.hide()
                    activeLine.hide()
                    note.content = gettext("Ihr Tisch ist gerade beruhigt.")
                    note.show()
                }
            }
        }
    }

    // ── open and close ───────────────────────────────────────────────────────

    private fun open() {
        if (isOpen || disposed) return
        onBeforeOpen()
        isOpen = true
        panel.show()
        button.setAttrIfChanged("aria-expanded", "true")
        val onKey: (Event) -> Unit = { event -> if ((event as? KeyboardEvent)?.key == "Escape") close(returnFocus = true) }
        val onClick: (Event) -> Unit = { event ->
            if (!isInside(event.target as? Node)) close(returnFocus = false)
        }
        val onFocusOut: (Event) -> Unit = { event ->
            val next = (event as? FocusEvent)?.relatedTarget as? Node
            if (next != null && !isInside(next)) {
                close(returnFocus = false)
            } else if (deferredRefresh) {
                later(0) { AppScope.launch { refresh() } }
            }
        }
        document.addEventListener("keydown", onKey)
        document.addEventListener("click", onClick)
        document.addEventListener("focusout", onFocusOut)
        closeListeners = {
            document.removeEventListener("keydown", onKey)
            document.removeEventListener("click", onClick)
            document.removeEventListener("focusout", onFocusOut)
        }
        AppScope.launch {
            refresh()
            if (isOpen) later(0) { rows.values.firstOrNull { it.usable }?.focus() }
        }
    }

    /** Closes the panel; [returnFocus] puts the focus back on the button (Escape), a click elsewhere keeps the focus where the person put it. */
    fun close(returnFocus: Boolean) {
        if (!isOpen) return
        isOpen = false
        panel.hide()
        button.setAttrIfChanged("aria-expanded", "false")
        closeListeners?.invoke()
        closeListeners = null
        if (returnFocus) (button.getElement() as? HTMLElement)?.focus()
        applyButtonVisibility()
    }

    private fun isInside(target: Node?): Boolean =
        target != null &&
            (panel.getElement()?.contains(target) == true || button.getElement()?.contains(target) == true)

    // ── listing, drawing ────────────────────────────────────────────────────

    /**
     * Reads the devices, redraws the panel and the button. Never writes to the browser and never switches a device, EXCEPT after a
     * `devicechange` ([preserveFocus] = true): then a device that vanished is replaced by the first one of its list (not remembered).
     */
    suspend fun refresh(preserveFocus: Boolean = false) {
        if (disposed) return
        if (refreshing) {
            refreshQueued = true
            queuedFromDeviceChange = queuedFromDeviceChange || preserveFocus
            return
        }
        refreshing = true
        var fromDeviceChange = preserveFocus
        try {
            do {
                refreshQueued = false
                refreshOnce(fromDeviceChange = fromDeviceChange)
                fromDeviceChange = queuedFromDeviceChange
                queuedFromDeviceChange = false
            } while (refreshQueued && !disposed)
        } finally {
            refreshing = false
        }
    }

    private suspend fun refreshOnce(fromDeviceChange: Boolean) {
        val currentRole = role()
        val quieted = tableQuieted()
        var microphones = listMicrophones(currentRole)
        if (currentRole == EncounterDeviceRole.CONGREGATION_AT_TABLE && quieted && microphones.isEmpty()) {
            microphones = lastMicrophones
        } else if (microphones.isNotEmpty()) {
            lastMicrophones = microphones
        }
        val cameras = listCameras(currentRole)
        val outputs = output.listOutputs()
        if (disposed) return
        if (fromDeviceChange) {
            replaceVanished(currentRole, microphones, cameras, outputs)
        }
        val fields =
            encounterDeviceFields(
                role = currentRole,
                tableQuieted = quieted,
                sinkApi = output.available,
                microphones = microphones,
                cameras = cameras,
                outputs = outputs,
                active =
                    mapOf(
                        ConferenceDeviceKind.MICROPHONE to activeMicrophone(currentRole),
                        ConferenceDeviceKind.CAMERA to speaker?.activeDeviceId(ConferenceDeviceKind.CAMERA),
                        ConferenceDeviceKind.SPEAKER to output.sinkId,
                    ),
            )
        deferredRefresh = false
        rows.forEach { (kind, row) -> row.show(fields.firstOrNull { it.kind == kind }) }
        speakerNote.visible = encounterSpeakerNoteVisible(fields, output.available)
        if (fellBack.isNotEmpty()) {
            rememberedNote.content = gettext("Ihr gemerktes Gerät ist gerade nicht angeschlossen. Es wird das Standardgerät verwendet.")
            rememberedNote.show()
        } else {
            rememberedNote.hide()
        }
        val name = encounterDeviceButtonLabel(currentRole, fields)
        button.setAttrIfChanged("aria-label", name)
        button.setAttrIfChanged("title", name)
        button.setAttrIfChanged("data-label", name)
        wantVisible = encounterDeviceButtonVisible(fields)
        applyButtonVisibility()
    }

    /** The bar button appears at once; it disappears only while neither the panel nor the button holds the focus. */
    private fun applyButtonVisibility() {
        val focused = document.activeElement != null && document.activeElement === button.getElement()
        if (wantVisible && !button.visible) {
            button.show()
            onVisibilityChanged()
        } else if (!wantVisible && button.visible && !isOpen && !focused) {
            button.hide()
            onVisibilityChanged()
        }
    }

    private suspend fun listMicrophones(currentRole: EncounterDeviceRole): List<ConferenceDeviceOption> =
        when (currentRole) {
            EncounterDeviceRole.OFFICE_HOLDER ->
                runCatching { speaker?.listDevices(ConferenceDeviceKind.MICROPHONE) }.getOrNull().orEmpty()
            EncounterDeviceRole.CONGREGATION_AT_TABLE -> runCatching { tableMic()?.listMicrophones() }.getOrNull().orEmpty()
            EncounterDeviceRole.CONGREGATION -> emptyList()
        }

    private suspend fun listCameras(currentRole: EncounterDeviceRole): List<ConferenceDeviceOption> =
        if (currentRole == EncounterDeviceRole.OFFICE_HOLDER) {
            runCatching { speaker?.listDevices(ConferenceDeviceKind.CAMERA) }.getOrNull().orEmpty()
        } else {
            emptyList()
        }

    private fun activeMicrophone(currentRole: EncounterDeviceRole): String? =
        when (currentRole) {
            EncounterDeviceRole.OFFICE_HOLDER -> speaker?.activeDeviceId(ConferenceDeviceKind.MICROPHONE)
            EncounterDeviceRole.CONGREGATION_AT_TABLE -> tableMic()?.activeMicrophoneId()
            EncounterDeviceRole.CONGREGATION -> null
        }

    private fun pairsFor(
        kind: ConferenceDeviceKind,
        options: List<ConferenceDeviceOption>,
    ): List<Pair<String, String>> {
        var number = 0
        return options.map { option ->
            if (option.deviceId == ENCOUNTER_SYSTEM_DEFAULT_OUTPUT) {
                option.deviceId to gettext("Standard des Systems")
            } else {
                number++
                option.deviceId to conferenceDeviceOptionLabel(kind, option.rawLabel, number)
            }
        }
    }

    /**
     * A device that was active and is gone after a `devicechange`: switch to the first one of the list and say so ONCE, in one sentence per
     * kind (the kind, never the name). One region holds one text, so all sentences of one plug event go out together.
     */
    private suspend fun replaceVanished(
        currentRole: EncounterDeviceRole,
        microphones: List<ConferenceDeviceOption>,
        cameras: List<ConferenceDeviceOption>,
        outputs: List<ConferenceDeviceOption>,
    ) {
        val replaced = mutableListOf<ConferenceDeviceKind>()
        encounterVanishedFallback(activeMicrophone(currentRole), microphones)?.let { replacement ->
            if (switchMicrophone(currentRole, replacement) == null) replaced += ConferenceDeviceKind.MICROPHONE
        }
        if (currentRole == EncounterDeviceRole.OFFICE_HOLDER) {
            encounterVanishedFallback(speaker?.activeDeviceId(ConferenceDeviceKind.CAMERA), cameras)?.let { replacement ->
                if (switchCamera(replacement) == null) replaced += ConferenceDeviceKind.CAMERA
            }
        }
        val current = output.sinkId
        if (current != null && outputs.isNotEmpty() && outputs.none { it.deviceId == current }) {
            output.select(null)
            replaced += ConferenceDeviceKind.SPEAKER
        }
        if (replaced.isNotEmpty()) announceVanished(replaced)
    }

    /** The speaker fell back to the system default because its device is gone ([EncounterAudioOutput.apply]): the room says so once. */
    fun speakerGone() {
        if (disposed) return
        announceVanished(listOf(ConferenceDeviceKind.SPEAKER))
        AppScope.launch { refresh() }
    }

    private fun announceVanished(kinds: List<ConferenceDeviceKind>) {
        announce(
            kinds.joinToString(" ") { kind ->
                gettext("%1 ist nicht mehr verfügbar. Es wird das Standardgerät verwendet.", conferenceDeviceSubject(kind))
            },
        )
    }

    // ── a choice of the person ──────────────────────────────────────────────

    private fun currentSelection(kind: ConferenceDeviceKind): String? =
        when (kind) {
            ConferenceDeviceKind.MICROPHONE -> activeMicrophone(role())
            ConferenceDeviceKind.CAMERA -> speaker?.activeDeviceId(ConferenceDeviceKind.CAMERA)
            ConferenceDeviceKind.SPEAKER -> output.sinkId ?: ENCOUNTER_SYSTEM_DEFAULT_OUTPUT
        }

    private fun onPicked(
        kind: ConferenceDeviceKind,
        id: String,
    ) {
        if (disposed || id == currentSelection(kind)) return
        AppScope.launch {
            when (kind) {
                ConferenceDeviceKind.SPEAKER -> pickSpeaker(id)
                ConferenceDeviceKind.MICROPHONE, ConferenceDeviceKind.CAMERA -> {
                    val failure =
                        if (kind == ConferenceDeviceKind.MICROPHONE) {
                            switchMicrophone(role(), id)
                        } else {
                            switchCamera(id)
                        }
                    if (failure == null) {
                        remember(kind, id)
                    } else {
                        notifyError(conferenceDeviceErrorMessage(kind, failure))
                        resetSelect(kind)
                    }
                }
            }
            if (!disposed) refresh()
        }
    }

    private suspend fun pickSpeaker(id: String) {
        val target = if (id == ENCOUNTER_SYSTEM_DEFAULT_OUTPUT) null else id
        when (output.select(target)) {
            EncounterOutputResult.APPLIED -> remember(ConferenceDeviceKind.SPEAKER, target)
            EncounterOutputResult.REVERTED_TO_PREVIOUS -> {
                notifyError(gettext("Der Lautsprecher konnte nicht gewechselt werden. Es bleibt das bisherige Gerät."))
                resetSelect(ConferenceDeviceKind.SPEAKER)
            }
            EncounterOutputResult.FELL_BACK_TO_DEFAULT -> {
                announceVanished(listOf(ConferenceDeviceKind.SPEAKER))
                resetSelect(ConferenceDeviceKind.SPEAKER)
            }
            EncounterOutputResult.UNSUPPORTED -> resetSelect(ConferenceDeviceKind.SPEAKER)
        }
    }

    /** A choice that worked: remembered (the id only), and the "remembered device is missing" sentence of this kind is obsolete. */
    private fun remember(
        kind: ConferenceDeviceKind,
        id: String?,
    ) {
        encounterRememberDevice(kind, id)
        if (id != null) lastChosen[kind] = id else lastChosen.remove(kind)
        fellBack -= kind
    }

    private fun resetSelect(kind: ConferenceDeviceKind) {
        val row = rows[kind] ?: return
        val wanted = currentSelection(kind) ?: return
        applying = true
        row.select.value = wanted
        applying = false
    }

    private suspend fun switchMicrophone(
        currentRole: EncounterDeviceRole,
        id: String,
    ): ConferenceDeviceFailure? =
        when (currentRole) {
            EncounterDeviceRole.OFFICE_HOLDER -> {
                val session = speaker
                if (session ==
                    null
                ) {
                    ConferenceDeviceFailure.OTHER
                } else {
                    guardedSwitch { session.switchDevice(ConferenceDeviceKind.MICROPHONE, id) }
                }
            }
            EncounterDeviceRole.CONGREGATION_AT_TABLE -> {
                val devices = tableMic()
                if (devices == null) ConferenceDeviceFailure.OTHER else guardedSwitch { devices.switchMicrophone(id) }
            }
            EncounterDeviceRole.CONGREGATION -> ConferenceDeviceFailure.OTHER
        }

    /** `null` = switched; no session at all is a failure (never read the success `null` of the switch as "no session"). */
    private suspend fun switchCamera(id: String): ConferenceDeviceFailure? {
        val session = speaker ?: return ConferenceDeviceFailure.OTHER
        return guardedSwitch { session.switchDevice(ConferenceDeviceKind.CAMERA, id) }
    }

    /** `null` = switched. A thrown exception is a failure (the underlying switch answers success with `null`, so no `guarded {}` here). */
    private suspend fun guardedSwitch(block: suspend () -> ConferenceDeviceFailure?): ConferenceDeviceFailure? =
        try {
            block()
        } catch (e: Throwable) {
            ConferenceDeviceFailure.OTHER
        }

    // ── the remembered choice, applied quietly ──────────────────────────────

    /** The office holder switched [kind] on: the remembered device (if it is there and not yet active) is applied without a word. */
    suspend fun deviceSwitchedOn(kind: ConferenceDeviceKind) {
        val session = speaker ?: return
        if (disposed) return
        val options = runCatching { session.listDevices(kind) }.getOrNull().orEmpty()
        applyRemembered(kind, options, session.activeDeviceId(kind)) { id ->
            session.switchDevice(kind, id)
        }
        refresh()
    }

    /** The table microphone was switched on (also after a rotation: a new session): the remembered device is applied without a word. */
    suspend fun tableMicrophoneOn(devices: EncounterTableMicrophoneDevices) {
        if (disposed) return
        val options = runCatching { devices.listMicrophones() }.getOrNull().orEmpty()
        applyRemembered(ConferenceDeviceKind.MICROPHONE, options, devices.activeMicrophoneId()) { id ->
            devices.switchMicrophone(id)
        }
        refresh()
    }

    /** After the room connected: the remembered speaker is applied (when it is in the list), otherwise the list shows what there is. */
    suspend fun restoreOutput() {
        if (disposed) return
        if (output.available) {
            val stored = encounterStoredDevice(ConferenceDeviceKind.SPEAKER)
            val options = output.listOutputs()
            val resolution = encounterResolveDevice(stored, options, output.sinkId)
            if (resolution.fellBack) fellBack += ConferenceDeviceKind.SPEAKER else fellBack -= ConferenceDeviceKind.SPEAKER
            resolution.id?.let { output.select(it) }
        }
        refresh()
    }

    private suspend fun applyRemembered(
        kind: ConferenceDeviceKind,
        options: List<ConferenceDeviceOption>,
        active: String?,
        switch: suspend (String) -> ConferenceDeviceFailure?,
    ) {
        val wanted = lastChosen[kind] ?: encounterStoredDevice(kind)
        val resolution = encounterResolveDevice(wanted, options, active)
        if (resolution.fellBack) fellBack += kind else fellBack -= kind
        val id = resolution.id ?: return
        if (guardedSwitch { switch(id) } != null) fellBack += kind
    }

    // ── life cycle ──────────────────────────────────────────────────────────

    private fun later(
        ms: Int,
        block: () -> Unit,
    ) {
        timers += window.setTimeout({ if (!disposed) block() }, ms)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        closeListeners?.invoke()
        closeListeners = null
        stopDeviceChange()
        timers.forEach { window.clearTimeout(it) }
        timers.clear()
    }

    private companion object {
        const val PANEL_ID = "lapis-encounter-device-panel"
        const val HEADING_ID = "lapis-encounter-device-heading"
        const val ACTIVE_ID_PREFIX = "lapis-encounter-device-active-"
    }
}
