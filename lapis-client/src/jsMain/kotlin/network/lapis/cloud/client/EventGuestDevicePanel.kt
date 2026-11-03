package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.client.encounter.encounterUsableOutputs
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.livekit.DeviceEnumeration

/*
 * V1.9.272 -- the device section of the event guest call view: microphone, camera and (where the browser can) speaker.
 *
 * PRIVACY: a guest has no account and leaves nothing behind. This file therefore has no storage access of any kind (nothing is remembered
 * between visits), no remote call, no log and puts no device id and no device label into any message: a label only ever appears as the text
 * of an option. The browser's permission dialog is never opened here on its own; the only caller of `getUserMedia` is the click on the
 * button of [ConferenceDevicePermissionRow]. The panel talks to the call only through [GuestDevicePort], so it is testable without LiveKit.
 */

/** What the panel needs from the call; implemented over `LiveKitRoomSession` by the view and by a fake in the tests. */
internal interface GuestDevicePort {
    /** false when the session publishes nothing: no device block is ever shown then. */
    val publishesMedia: Boolean

    suspend fun list(kind: ConferenceDeviceKind): DeviceEnumeration

    fun active(kind: ConferenceDeviceKind): String?

    suspend fun switch(
        kind: ConferenceDeviceKind,
        deviceId: String,
    ): ConferenceDeviceFailure?
}

/**
 * Whether the speaker field exists: only with the sink API and at least two real outputs (the Chrome alias `default` does not count:
 * "system default plus one device" is no choice). Only the DECISION is shared with the encounter room; the guest never uses its
 * "system default" entry, which has a blank id that `switchDevice` rejects.
 */
internal fun guestSpeakerFieldVisible(
    sinkApi: Boolean,
    outputs: List<ConferenceDeviceOption>,
): Boolean = sinkApi && encounterUsableOutputs(outputs).size >= 2

private var guestDevicePanelSerial = 0

/** Runs [block]; a failure other than a cancellation becomes [default] (a missing `enumerateDevices` must not break the call view). */
private suspend fun <T> guestSafely(
    default: T,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        default
    }

internal class EventGuestDevicePanel(
    parent: Container,
    private val port: GuestDevicePort,
    private val permission: DevicePermissionProvider = BrowserDevicePermissionProvider(),
    private val sinkApi: Boolean = sinkIdApiAvailable(),
    private val onNote: (String) -> Unit,
    private val onCameraSwitched: () -> Unit,
    initiallyOpen: Boolean,
) {
    private val serial = ++guestDevicePanelSerial

    /** The id the "Geräte" button points at with `aria-controls`. */
    val panelId: String = "lapis-guest-devices-$serial"
    val root: Div = parent.div(className = "lapis-guest-devices w-100")
    private var programmatic = false
    private val gate = DeviceRefreshGate()
    private var connected = false
    private var activated = false
    private var disposed = false
    private var speakerSelect: Select? = null
    private val permissionRow: ConferenceDevicePermissionRow
    private val micSelect: Select
    private val cameraSelect: Select

    /** true while the section is shown. */
    var isOpen: Boolean = initiallyOpen
        private set

    init {
        root.setAttribute("id", panelId)
        root.setStyle("max-width", "28rem")
        permissionRow =
            ConferenceDevicePermissionRow(
                root,
                permission,
                { refresh(preserveFocus = false) },
                { focusMicrophone() },
            )
        micSelect = newSelect(tr("Mikrofon"), ConferenceDeviceKind.MICROPHONE)
        cameraSelect = newSelect(tr("Kamera"), ConferenceDeviceKind.CAMERA)
        if (!initiallyOpen) root.hide()
    }

    private fun focusMicrophone() {
        micSelect.input
            .getElement()
            ?.unsafeCast<org.w3c.dom.HTMLElement>()
            ?.focus()
    }

    private fun newSelect(
        label: String,
        kind: ConferenceDeviceKind,
    ): Select {
        val select = root.select(options = listOf("" to tr("Wird geladen …")), value = "", label = label)
        select.addCssClass("w-100")
        select.input.setStyle("min-height", "44px")
        wireConferenceDeviceSelect(
            select = select,
            kind = kind,
            switch = { k, id -> port.switch(k, id) },
            activeId = { k -> port.active(k) },
            isProgrammatic = { programmatic },
            setProgrammatic = { programmatic = it },
            guarded = { block -> guestGuarded(block) },
            onFailure = onNote,
            onApplied = { _ -> if (kind == ConferenceDeviceKind.CAMERA) onCameraSwitched() },
        )
        return select
    }

    private suspend fun guestGuarded(block: suspend () -> ConferenceDeviceFailure?): ConferenceDeviceFailure? =
        guestSafely(ConferenceDeviceFailure.OTHER) { block() }

    /** The call is up: from now on the devices may be listed (at once when the section is open, else on its first opening). */
    fun onConnected() {
        connected = true
        if (isOpen) activate()
    }

    fun open() = setOpen(true)

    fun close() = setOpen(false)

    fun toggle(): Boolean {
        setOpen(!isOpen)
        return isOpen
    }

    private fun setOpen(open: Boolean) {
        isOpen = open
        if (open) root.show() else root.hide()
        if (open && connected) activate()
    }

    private fun activate() {
        if (activated || disposed) return
        activated = true
        AppScope.launch { refresh(preserveFocus = false) }
    }

    /**
     * Lists the devices of all kinds again. Never opens a device. Before the first opening of the section nothing is listed. A refresh
     * asked for while one runs is repeated once afterwards, a hotplug one ([preserveFocus]) is dropped (the next event catches up).
     */
    suspend fun refresh(preserveFocus: Boolean) {
        if (!activated || disposed) return
        if (gate.shouldDefer(preserveFocus)) return
        if (preserveFocus) {
            val focused = document.activeElement
            val selects = listOfNotNull(micSelect, cameraSelect, speakerSelect)
            if (selects.any { it.input.getElement() == focused }) return
        }
        gate.start()
        try {
            var micState = DevicePermissionState.GRANTED
            var cameraState = DevicePermissionState.GRANTED
            for (kind in ConferenceDeviceKind.entries) {
                val enumeration = guestSafely(DeviceEnumeration(emptyList(), 0)) { port.list(kind) }
                if (kind == ConferenceDeviceKind.SPEAKER) {
                    renderSpeaker(enumeration.usable, micState, preserveFocus)
                    continue
                }
                val state =
                    if (!port.publishesMedia) {
                        DevicePermissionState.GRANTED
                    } else {
                        conferenceDevicePermissionState(
                            permission.query(kind),
                            RawEnumeration(enumeration.rawCount, enumeration.usable.size),
                        )
                    }
                if (kind == ConferenceDeviceKind.MICROPHONE) micState = state
                if (kind == ConferenceDeviceKind.CAMERA) cameraState = state
                val select = if (kind == ConferenceDeviceKind.MICROPHONE) micSelect else cameraSelect
                if (conferenceDeviceRowMode(state, enumeration.usable.size) == DeviceRowMode.PLACEHOLDER) {
                    applyDevicePlaceholder(select, tr("Erst nach Freigabe verfügbar")) { programmatic = it }
                    continue
                }
                renderOptions(select, kind, enumeration.usable, preserveFocus, firstIfNoActive = false)
            }
            if (!disposed) permissionRow.render(micState, cameraState)
        } finally {
            if (gate.finish() && !disposed) AppScope.launch { refresh(preserveFocus = false) }
        }
    }

    private fun renderSpeaker(
        outputs: List<ConferenceDeviceOption>,
        micState: DevicePermissionState,
        preserveFocus: Boolean,
    ) {
        val visible = micState == DevicePermissionState.GRANTED && guestSpeakerFieldVisible(sinkApi, outputs)
        if (!visible) {
            speakerSelect?.hide()
            return
        }
        val select = speakerSelect ?: newSelect(tr("Lautsprecher"), ConferenceDeviceKind.SPEAKER).also { speakerSelect = it }
        renderOptions(select, ConferenceDeviceKind.SPEAKER, outputs, preserveFocus, firstIfNoActive = true)
    }

    private fun renderOptions(
        select: Select,
        kind: ConferenceDeviceKind,
        options: List<ConferenceDeviceOption>,
        preserveFocus: Boolean,
        firstIfNoActive: Boolean,
    ) {
        clearDevicePlaceholder(select)
        val active = conferenceUsableDeviceId(port.active(kind))
        val ids = options.map { it.deviceId }
        val preferred = conferencePreferredDeviceId(null, active, ids)
        // The device in use is gone: the field shows the first one that is left (and says so), as the member screen does for its stored choice.
        val vanished = active != null && active !in ids
        val shown = preferred ?: if (vanished || firstIfNoActive) ids.firstOrNull() else null
        if (conferenceShouldNotifyDeviceLost(preserveFocus, active, ids, shown)) {
            onNote(conferenceDeviceLostMessage(kind))
        }
        programmatic = true
        try {
            // PRIVACY: only the label becomes option text; ids and labels go nowhere else.
            select.options =
                options.mapIndexed { index, option ->
                    option.deviceId to conferenceDeviceOptionLabel(kind, option.rawLabel, index + 1)
                }
            select.value = shown
        } finally {
            programmatic = false
        }
        if (options.isEmpty()) select.hide() else select.show()
    }

    /** `onActiveDeviceChanged`: shows the device LiveKit now uses. Blank ids and ids not in the list are ignored. */
    fun syncActive(
        kind: ConferenceDeviceKind,
        deviceId: String,
    ) {
        val select =
            when (kind) {
                ConferenceDeviceKind.MICROPHONE -> micSelect
                ConferenceDeviceKind.CAMERA -> cameraSelect
                ConferenceDeviceKind.SPEAKER -> speakerSelect
            } ?: return
        val usable = conferenceUsableDeviceId(deviceId) ?: return
        if (isDevicePlaceholder(select) || select.value == usable) return
        if (select.options?.none { it.first == usable } != false) return
        programmatic = true
        try {
            select.value = usable
        } finally {
            programmatic = false
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        permissionRow.dispose()
    }
}
