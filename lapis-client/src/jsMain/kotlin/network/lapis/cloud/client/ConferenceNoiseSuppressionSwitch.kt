package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.launch

private var noiseSwitchSerial = 0

/** The sentence that says why the filter is not used. Always followed by the fallback ("the browser's own reduction is used"). */
internal fun noiseSuppressionUnavailableSentence(reason: NoiseSuppressionUnavailable): String =
    when (reason) {
        NoiseSuppressionUnavailable.NO_AUDIO_WORKLET ->
            gettext("Nicht verfügbar – dieser Browser unterstützt AudioWorklet nicht. Es wird die Rauschminderung des Browsers verwendet.")
        NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY ->
            gettext("Nicht verfügbar – dieser Browser unterstützt WebAssembly nicht. Es wird die Rauschminderung des Browsers verwendet.")
        NoiseSuppressionUnavailable.LOAD_FAILED ->
            gettext(
                "Nicht verfügbar – die Rauschunterdrückung konnte nicht geladen werden. Es wird die Rauschminderung des Browsers verwendet.",
            )
    }

/**
 * V1.9.114 -- the one row of the noise suppression, the same in the conference's device block and in the pulpit's device panel: label left,
 * switch right, a one-line hint below that is ALWAYS visible (it names the price of the filter: singing and music sound altered).
 *
 * - The switch is a real `<button type="button" role="switch">` (Space and Enter work, the focus ring is the global one), with `aria-checked`.
 *   No slider, no icon, no listening test; the state is the knob's side, never colour alone.
 * - While a change runs and while the filter is unavailable the switch carries `aria-disabled="true"` (it stays focusable and says why).
 * - The `role="status"` element (polite, never assertive) is EMPTY on construction. A sentence appears only after a person's own switch-on
 *   ("Rauschunterdrückung aktiv.") or when the filter fails; switching off clears it.
 * - Texts: [tr] for visible content, [gettext] for attributes and for text that is set later.
 */
internal class NoiseSuppressionSwitchRow(
    parent: Container,
    private val controller: ConferenceNoiseSuppressionController,
) {
    private val serial = ++noiseSwitchSerial
    private val labelId = "lapis-noise-label-$serial"
    private val hintId = "lapis-noise-hint-$serial"

    val root: Div = parent.div(className = "lapis-noise-suppression-row")
    val switchButton: Button
    internal val status: Div
    private val unavailableTag: Span
    private val reasonLine: Div

    init {
        val line = root.div(className = "lapis-noise-suppression-line")
        val label = line.span(tr("Rauschunterdrückung"))
        label.setAttribute("id", labelId)
        unavailableTag = line.span(tr("Nicht verfügbar"), className = "text-muted small ms-auto")
        unavailableTag.hide()
        switchButton = line.button("", className = "lapis-noise-switch")
        switchButton.setAttribute("type", "button")
        switchButton.setAttribute("role", "switch")
        switchButton.setAttribute("aria-checked", "false")
        switchButton.setAttribute("aria-labelledby", labelId)
        switchButton.setAttribute("aria-describedby", hintId)
        val hint =
            root.div(
                tr("Wird nur in diesem Browser verarbeitet. Gesang und Musik klingen damit verändert."),
                className = "text-muted small",
            )
        hint.setAttribute("id", hintId)
        reasonLine = root.div(className = "text-muted small")
        reasonLine.hide()
        status = root.div(className = "visually-hidden")
        status.setAttribute("role", "status")
        status.setAttribute("aria-live", "polite")
        status.setAttribute("aria-atomic", "true")
        switchButton.onClick {
            // synchronous, inside the gesture: the audio context may only start here
            controller.prepareFromGesture()
            AppScope.launch { controller.toggle() }
        }
        controller.observe { state, announce -> render(state, announce) }
    }

    private fun render(
        state: NoiseSuppressionState,
        announce: Boolean,
    ) {
        val unavailable = state as? NoiseSuppressionState.Unavailable
        switchButton.setAttrIfChanged("aria-checked", (state is NoiseSuppressionState.On).toString())
        switchButton.setAttrIfChanged("aria-disabled", (controller.busy || unavailable != null).toString())
        if (unavailable != null) {
            unavailableTag.show()
            val sentence = noiseSuppressionUnavailableSentence(unavailable.reason)
            reasonLine.content = sentence
            reasonLine.show()
            if (announce) status.content = sentence
            return
        }
        unavailableTag.hide()
        reasonLine.hide()
        when {
            state is NoiseSuppressionState.Off -> status.content = ""
            state is NoiseSuppressionState.On && announce -> status.content = gettext("Rauschunterdrückung aktiv.")
        }
    }
}
