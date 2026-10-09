package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.i18n.tr
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.conferenceDeviceEnableErrorMessage
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.setAttrIfChanged

/**
 * V1.9.62 Begegnungsraum (B2) -- the own-device controls of an OFFICE HOLDER (pulpit or steward). The ONLY encounter UI that touches a
 * camera or microphone (`ClientEncounterPrivacyTripwireTest`), and it can only be built from an [EncounterSpeakerSession] -- a
 * congregation member has no such session, so for them this class cannot even be constructed.
 *
 * Defaults (design review): on entering, the camera of a pulpit person is switched on (in the click that entered, so Safari's gesture
 * rule is met), the MICROPHONE stays OFF. V1.9.90: there is no standing band any more -- the state lives on the button alone. Norman: the
 * frequent error is "speaking into a muted microphone", so the OFF state is the amplified one (red outline, slashed symbol, never colour
 * alone) and the room announces it once, politely, on entering (`EncounterRoom`). A steward starts with both off.
 *
 * The buttons stand in the left group of the bar ("Geräte"). Microphone/camera and the table microphone/"Kanzel lauter" of a seated
 * congregation person never occur together: the pulpit controls exist only with `canPublish`, `buildTableControls` only for the congregation.
 *
 * The toggle buttons carry their state in `aria-pressed` and a filled style, not in a changing label. The state follows the SDK's mute
 * events ([onMuteChanged]); a click flips it at once when the device call succeeded.
 */
internal class EncounterPulpitControls(
    toolbar: Container,
    private val session: EncounterSpeakerSession,
) {
    internal val micButton: Button = toolbar.encounterControlButton(ActionIcon.MICROPHONE, tr("Mikrofon"))
    internal val cameraButton: Button = toolbar.encounterControlButton(ActionIcon.CAMERA, tr("Kamera"))
    private var micOn = false
    private var cameraOn = false

    init {
        micButton.onClick { toggleMic() }
        cameraButton.onClick { toggleCamera() }
        render()
    }

    /** Switches the camera on right after the connection (called in the entry click's own coroutine); the microphone stays off. */
    suspend fun startCamera() {
        setCamera(true)
    }

    val isMicOn: Boolean get() = micOn
    val isCameraOn: Boolean get() = cameraOn

    /** The SDK's own word about the local tracks (`source` is `"microphone"`/`"camera"`). */
    fun onMuteChanged(
        source: String,
        muted: Boolean,
    ) {
        when (source) {
            "microphone" -> micOn = !muted
            "camera" -> cameraOn = !muted
            else -> return
        }
        render()
    }

    private fun toggleMic() {
        AppScope.launch { setMic(!micOn) }
    }

    private fun toggleCamera() {
        AppScope.launch { setCamera(!cameraOn) }
    }

    private suspend fun setMic(enabled: Boolean) {
        val failure = session.setMicrophone(enabled)
        report(ConferenceDeviceKind.MICROPHONE, failure)
        if (failure == null) micOn = enabled
        render()
    }

    private suspend fun setCamera(enabled: Boolean) {
        val failure = session.setCamera(enabled)
        report(ConferenceDeviceKind.CAMERA, failure)
        if (failure == null) cameraOn = enabled
        render()
    }

    private fun report(
        kind: ConferenceDeviceKind,
        failure: ConferenceDeviceFailure?,
    ) {
        if (failure != null) notifyError(conferenceDeviceEnableErrorMessage(kind, failure))
    }

    private fun render() {
        // The label stays ("Mikrofon", "Kamera"); the state is `aria-pressed`, the filled style and the slash symbol (never colour alone).
        micButton.setAttrIfChanged("aria-pressed", micOn.toString())
        cameraButton.setAttrIfChanged("aria-pressed", cameraOn.toString())
        micButton.style = if (micOn) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEDANGER
        cameraButton.style = if (cameraOn) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
        val micIcon = if (micOn) "fas fa-microphone" else "fas fa-microphone-slash"
        val cameraIcon = if (cameraOn) "fas fa-video" else "fas fa-video-slash"
        if (micButton.icon != micIcon) micButton.icon = micIcon
        if (cameraButton.icon != cameraIcon) cameraButton.icon = cameraIcon
        if (cameraOn) cameraButton.removeCssClass("text-danger") else cameraButton.addCssClass("text-danger")
    }
}
