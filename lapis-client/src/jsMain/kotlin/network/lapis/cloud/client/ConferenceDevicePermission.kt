package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.form.select.Select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import kotlin.js.Promise

/*
 * V1.9.169 -- device picker without browser permission.
 *
 * Until a person has allowed camera/microphone access, `enumerateDevices()` returns entries with blank `deviceId`s, which
 * `conferenceUsableDeviceOptions` drops: all three selects used to vanish without a word. This file holds the pure decision, the ONE
 * place that may call `getUserMedia` (on a click, never silently) and the block that explains and offers the access.
 *
 * SECURITY: nothing here reads, stores or logs a device label; only the browser's error NAME is ever looked at, and every track
 * acquired for the permission is stopped at once. There is deliberately no role check: the block depends on the browser's state only.
 */

/** The browser's permission state of one device kind; [UNKNOWN] when the Permissions API is missing or throws. */
internal enum class DevicePermissionStatus { GRANTED, PROMPT, DENIED, UNKNOWN }

/** Raw `enumerateDevices()` result of one kind: all entries, and those with a usable (non-blank) `deviceId`. */
internal data class RawEnumeration(
    val entries: Int,
    val usable: Int,
)

internal enum class DevicePermissionState { NEEDS_ACCESS, BLOCKED, GRANTED }

internal fun conferenceDevicePermissionState(
    status: DevicePermissionStatus,
    raw: RawEnumeration,
): DevicePermissionState =
    when (status) {
        DevicePermissionStatus.DENIED -> DevicePermissionState.BLOCKED
        // "prompt" with an empty list: the machine has no device of this kind (a desktop PC without a webcam), so there is nothing
        // to ask for. Treated as granted, the row then hides itself as it did before this wave.
        DevicePermissionStatus.PROMPT ->
            if (raw.entries == 0) DevicePermissionState.GRANTED else DevicePermissionState.NEEDS_ACCESS
        DevicePermissionStatus.GRANTED -> DevicePermissionState.GRANTED
        DevicePermissionStatus.UNKNOWN ->
            if (raw.usable > 0) DevicePermissionState.GRANTED else DevicePermissionState.NEEDS_ACCESS
    }

/** The block is shown while the microphone (which also decides the speaker) or the camera still lacks access. */
internal fun conferencePermissionBlockVisible(
    mic: DevicePermissionState,
    camera: DevicePermissionState,
): Boolean = mic != DevicePermissionState.GRANTED || camera != DevicePermissionState.GRANTED

internal enum class DeviceRowMode { PLACEHOLDER, HIDDEN, NORMAL }

/** How one select row presents itself: the disabled placeholder without access, the usual list, or (granted, nothing found) hidden. */
internal fun conferenceDeviceRowMode(
    state: DevicePermissionState,
    usableCount: Int,
): DeviceRowMode =
    when {
        state != DevicePermissionState.GRANTED -> DeviceRowMode.PLACEHOLDER
        usableCount == 0 -> DeviceRowMode.HIDDEN
        else -> DeviceRowMode.NORMAL
    }

/** Result of the click-driven access request. */
internal sealed interface DevicePermissionRequestResult {
    data class Allowed(
        val audio: Boolean,
        val video: Boolean,
    ) : DevicePermissionRequestResult

    data object Blocked : DevicePermissionRequestResult

    data object NotFound : DevicePermissionRequestResult

    data class Failed(
        val errorName: String,
    ) : DevicePermissionRequestResult
}

internal interface DevicePermissionProvider {
    /** Reads the permission state only (`permissions.query`), never opens a device. */
    suspend fun query(kind: ConferenceDeviceKind): DevicePermissionStatus

    /** Opens the devices once to obtain the browser's permission and stops every track at once. Only ever called from a click. */
    suspend fun request(
        audio: Boolean,
        video: Boolean,
    ): DevicePermissionRequestResult

    /** Calls [listener] when the browser's permission of [kind] changes; the returned function unregisters it. */
    fun onChange(
        kind: ConferenceDeviceKind,
        listener: () -> Unit,
    ): () -> Unit
}

private val BLOCKED_ERROR_NAMES = setOf("NotAllowedError", "SecurityError", "PermissionDeniedError")
private val MISSING_ERROR_NAMES = setOf("NotFoundError", "OverconstrainedError", "DevicesNotFoundError")

private fun permissionName(kind: ConferenceDeviceKind): String = if (kind == ConferenceDeviceKind.CAMERA) "camera" else "microphone"

private fun classifyFailure(errorName: String): DevicePermissionRequestResult =
    when (errorName) {
        in BLOCKED_ERROR_NAMES -> DevicePermissionRequestResult.Blocked
        in MISSING_ERROR_NAMES -> DevicePermissionRequestResult.NotFound
        else -> DevicePermissionRequestResult.Failed(errorName)
    }

/**
 * The decision of one access request, independent of the browser: [attempt] returns null on success, else the browser's error name.
 * When both kinds are asked for and the combined attempt fails (for whatever reason: a missing device, a blocked camera, a camera held
 * by another application) each kind is tried on its own, so one unusable device never keeps the other one from being allowed.
 */
internal suspend fun conferenceRequestAccess(
    audio: Boolean,
    video: Boolean,
    attempt: suspend (audio: Boolean, video: Boolean) -> String?,
): DevicePermissionRequestResult {
    val first = attempt(audio, video) ?: return DevicePermissionRequestResult.Allowed(audio, video)
    if (!(audio && video)) return classifyFailure(first)
    val audioError = attempt(true, false)
    val videoError = attempt(false, true)
    val audioOk = audioError == null
    val videoOk = videoError == null
    if (audioOk || videoOk) return DevicePermissionRequestResult.Allowed(audioOk, videoOk)
    val results = listOfNotNull(audioError, videoError).map { classifyFailure(it) }
    return results.firstOrNull { it == DevicePermissionRequestResult.Blocked }
        ?: results.firstOrNull { it is DevicePermissionRequestResult.Failed }
        ?: DevicePermissionRequestResult.NotFound
}

private fun defaultAcquire(
    audio: Boolean,
    video: Boolean,
): Promise<dynamic> {
    val constraints = js("({})")
    constraints.audio = audio
    constraints.video = video
    return window.navigator
        .asDynamic()
        .mediaDevices
        .getUserMedia(constraints)
        .unsafeCast<Promise<dynamic>>()
}

/**
 * [acquire] opens the devices (default: `getUserMedia`), [permissionsApi] returns the Permissions API object or null; both are
 * parameters only so a test can stand in for the browser.
 */
internal class BrowserDevicePermissionProvider(
    private val acquire: (audio: Boolean, video: Boolean) -> Promise<dynamic> = ::defaultAcquire,
    private val permissionsApi: () -> dynamic = { window.navigator.asDynamic().permissions },
) : DevicePermissionProvider {
    override suspend fun query(kind: ConferenceDeviceKind): DevicePermissionStatus =
        try {
            val permissions = permissionsApi()
            if (permissions == null || permissions == undefined) {
                DevicePermissionStatus.UNKNOWN
            } else {
                val descriptor = js("({})")
                descriptor.name = permissionName(kind)
                val status = permissions.query(descriptor).unsafeCast<Promise<dynamic>>().await()
                when (status.state as? String) {
                    "granted" -> DevicePermissionStatus.GRANTED
                    "prompt" -> DevicePermissionStatus.PROMPT
                    "denied" -> DevicePermissionStatus.DENIED
                    else -> DevicePermissionStatus.UNKNOWN
                }
            }
        } catch (e: Throwable) {
            DevicePermissionStatus.UNKNOWN
        }

    /** null on success (every acquired track is stopped at once), otherwise the browser's error name. */
    private suspend fun attempt(
        audio: Boolean,
        video: Boolean,
    ): String? =
        try {
            val stream = acquire(audio, video).await()
            stream.getTracks().unsafeCast<Array<dynamic>>().forEach { it.stop() }
            null
        } catch (e: Throwable) {
            e
                .asDynamic()
                .name
                ?.toString()
                .orEmpty()
                .ifEmpty { "unknown" }
        }

    override suspend fun request(
        audio: Boolean,
        video: Boolean,
    ): DevicePermissionRequestResult = conferenceRequestAccess(audio, video, ::attempt)

    override fun onChange(
        kind: ConferenceDeviceKind,
        listener: () -> Unit,
    ): () -> Unit {
        var status: dynamic = null
        var disposed = false
        try {
            val permissions = permissionsApi()
            if (permissions != null && permissions != undefined) {
                val descriptor = js("({})")
                descriptor.name = permissionName(kind)
                permissions
                    .query(descriptor)
                    .unsafeCast<Promise<dynamic>>()
                    .then { result: dynamic ->
                        if (disposed) {
                            null
                        } else {
                            status = result
                            result.onchange = { _: dynamic -> listener() }
                            null
                        }
                    }.catch { null }
            }
        } catch (e: Throwable) {
            // no Permissions API: the heuristic of the enumeration is all there is
        }
        return {
            disposed = true
            try {
                if (status != null) status.onchange = null
            } catch (e: Throwable) {
                // already gone
            }
        }
    }
}

/**
 * The placeholder of a device select without access ("Erst nach Freigabe verfügbar"). `aria-disabled` goes on the NATIVE `<select>`
 * (`Select.input`), not on KVision's wrapper, so assistive technology reads the control itself as disabled. [setProgrammatic] raises the
 * reentrancy guard around the value assignment; nothing here touches the persisted preference.
 */
internal fun applyDevicePlaceholder(
    select: Select,
    label: String,
    setProgrammatic: (Boolean) -> Unit,
) {
    setProgrammatic(true)
    try {
        select.options = listOf("" to label)
        select.value = ""
    } finally {
        setProgrammatic(false)
    }
    select.input.setAttribute("aria-disabled", "true")
    select.show()
}

internal fun clearDevicePlaceholder(select: Select) {
    if (select.input.getAttribute("aria-disabled") != null) select.input.removeAttribute("aria-disabled")
}

internal fun isDevicePlaceholder(select: Select): Boolean = select.input.getAttribute("aria-disabled") == "true"

/**
 * Mutex and queue of the device enumeration. A refresh asked for while one runs is repeated once afterwards, unless it came from a
 * hotplug event ([preserveFocusedSelect]), which is simply dropped (the next event catches up).
 */
internal class DeviceRefreshGate {
    var running = false
        private set
    var queued = false
        private set

    /** true when the caller must not start now. */
    fun shouldDefer(preserveFocusedSelect: Boolean): Boolean {
        if (!running) return false
        if (!preserveFocusedSelect) queued = true
        return true
    }

    fun start() {
        running = true
    }

    /** Ends the run; true when one more refresh is due. */
    fun finish(): Boolean {
        running = false
        val again = queued
        queued = false
        return again
    }
}

private var devicePermissionSerial = 0

/**
 * The block above the microphone row: a short explanation, ONE primary button and a line for the outcome. It appears only while
 * microphone or camera access is missing; it never switches a microphone or camera on (the button only lets the browser list the devices).
 * The button stays `aria-disabled`-free and focusable; while the request runs it carries `aria-busy` and ignores further clicks.
 */
internal class ConferenceDevicePermissionRow(
    parent: Container,
    private val provider: DevicePermissionProvider,
    private val onAccessChanged: suspend () -> Unit,
    private val focusMicSelect: () -> Unit,
) {
    private val serial = ++devicePermissionSerial
    val root: Div = parent.div(className = "lapis-conference-device-permission")

    /** Polite live region OUTSIDE [root], so the sentence is still announced when the block hides itself after a success. */
    val status: Div = parent.div(className = "visually-hidden")
    val button: Button
    val blockedLine: Div
    private var busy = false
    private var blockedLineOwnedByRender = false
    private var unregister: List<() -> Unit> = emptyList()

    val visible: Boolean get() = root.visible

    init {
        val explanation =
            root.div(
                tr("Ihr Browser zeigt die Geräte erst, wenn Sie den Zugriff erlauben. Mikrofon und Kamera bleiben dabei ausgeschaltet."),
                className = "text-muted small",
            )
        explanation.setAttribute("id", "lapis-device-permission-hint-$serial")
        button =
            root.button(tr("Zugriff auf Mikrofon und Kamera erlauben"), style = ButtonStyle.PRIMARY, className = "w-100")
        button.setAttribute("type", "button")
        button.setAttribute("aria-describedby", "lapis-device-permission-hint-$serial")
        blockedLine = root.div(className = "text-muted small")
        blockedLine.setAttribute("role", "status")
        blockedLine.setAttribute("aria-live", "polite")
        blockedLine.hide()
        status.setAttribute("role", "status")
        status.setAttribute("aria-live", "polite")
        status.setAttribute("aria-atomic", "true")
        root.hide()
        button.onClick { AppScope.launch { requestAccess() } }
        unregister =
            listOf(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceKind.CAMERA).map { kind ->
                provider.onChange(kind) { AppScope.launch { onAccessChanged() } }
            }
    }

    /** Shows or hides the block for the current states; a blocked kind shows the blocked sentence at once. */
    fun render(
        mic: DevicePermissionState,
        camera: DevicePermissionState,
    ) {
        if (conferencePermissionBlockVisible(mic, camera)) root.show() else root.hide()
        if (mic == DevicePermissionState.BLOCKED && camera == DevicePermissionState.BLOCKED) {
            showMessage(blockedMessage(), ownedByRender = true)
        } else if (blockedLineOwnedByRender || !root.visible) {
            // the sentence the state itself put there is stale now; one that answers a click stays until the next click
            blockedLine.hide()
            blockedLineOwnedByRender = false
        }
    }

    fun dispose() {
        unregister.forEach { it() }
        unregister = emptyList()
    }

    private fun blockedMessage(): String =
        gettext(
            "Der Browser hat den Zugriff blockiert. Erlauben Sie Mikrofon und Kamera über das Schloss-Symbol in der Adressleiste oder in den Website-Einstellungen Ihres Browsers und versuchen Sie es erneut.",
        )

    private fun showMessage(
        text: String,
        ownedByRender: Boolean = false,
    ) {
        blockedLineOwnedByRender = ownedByRender
        blockedLine.content = text
        blockedLine.show()
    }

    private suspend fun requestAccess() {
        if (busy) return
        busy = true
        button.setAttribute("aria-busy", "true")
        try {
            val result = provider.request(audio = true, video = true)
            onAccessChanged()
            val message =
                when (result) {
                    is DevicePermissionRequestResult.Allowed ->
                        when {
                            result.audio && result.video -> gettext("Zugriff erlaubt. Sie können Ihre Geräte jetzt auswählen.")
                            result.audio -> gettext("Nur das Mikrofon ist freigegeben.")
                            else -> gettext("Nur die Kamera ist freigegeben.")
                        }
                    DevicePermissionRequestResult.Blocked -> blockedMessage()
                    DevicePermissionRequestResult.NotFound -> gettext("Es wurde weder ein Mikrofon noch eine Kamera gefunden.")
                    is DevicePermissionRequestResult.Failed ->
                        gettext("Ein Gerät konnte nicht geöffnet werden. Möglicherweise wird es von einer anderen Anwendung verwendet.")
                }
            val full = result is DevicePermissionRequestResult.Allowed && result.audio && result.video
            if (full) {
                status.content = message
                blockedLine.hide()
                if (!root.visible) focusMicSelect() else button.focus()
            } else {
                showMessage(message)
                status.content = message
                button.focus()
            }
        } finally {
            button.removeAttribute("aria-busy")
            busy = false
        }
    }
}
