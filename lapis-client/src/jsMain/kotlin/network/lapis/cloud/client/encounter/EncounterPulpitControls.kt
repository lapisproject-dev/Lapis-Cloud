package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.tr
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.conferenceDeviceEnableErrorMessage
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.notifyError

/**
 * V1.9.62 Begegnungsraum (B2) -- the own-device controls of an OFFICE HOLDER (pulpit or steward). The ONLY encounter UI that touches a
 * camera or microphone (`ClientEncounterPrivacyTripwireTest`), and it can only be built from an [EncounterSpeakerSession] -- a
 * congregation member has no such session, so for them this class cannot even be constructed.
 *
 * Defaults (design review): on entering, the camera of a pulpit person is switched on (in the click that entered, so Safari's gesture
 * rule is met), the MICROPHONE stays OFF -- a standing status band ("Ihr Mikrofon ist aus", with a switch-on button, `role="status"`)
 * says so, so nobody speaks into a muted microphone or sits unaware in an unmuted one. A steward starts with both off.
 *
 * The toggle buttons carry their state in `aria-pressed` and a filled style, not in a changing label. The state follows the SDK's mute
 * events ([onMuteChanged]); a click flips it at once when the device call succeeded.
 */
internal class EncounterPulpitControls(
    toolbar: Container,
    band: Container,
    private val session: EncounterSpeakerSession,
) {
    private val micButton: Button = toolbar.actionButton(ActionIcon.MICROPHONE, tr("Mikrofon"))
    private val cameraButton: Button = toolbar.actionButton(ActionIcon.CAMERA, tr("Kamera"))
    private val micBand: Div = band.div(className = "lapis-encounter-mic-band d-flex align-items-center gap-2")
    private var micOn = false
    private var cameraOn = false

    init {
        micBand.setAttribute("role", "status")
        micBand.div(tr("Ihr Mikrofon ist aus."))
        micBand.actionButton(ActionIcon.MICROPHONE, tr("Einschalten"), style = ButtonStyle.OUTLINEPRIMARY).onClick { toggleMic() }
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
        micButton.setAttribute("aria-pressed", micOn.toString())
        cameraButton.setAttribute("aria-pressed", cameraOn.toString())
        micButton.style = if (micOn) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
        cameraButton.style = if (cameraOn) ButtonStyle.PRIMARY else ButtonStyle.OUTLINESECONDARY
        if (micOn) micBand.hide() else micBand.show()
    }
}
