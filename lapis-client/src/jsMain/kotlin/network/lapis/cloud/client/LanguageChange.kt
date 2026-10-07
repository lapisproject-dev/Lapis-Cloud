package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.I18n
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import kotlinx.coroutines.launch

/**
 * Audit fix B2 (V1.4.31): a language switch must never end a running video conference by accident.
 *
 * [io.kvision.i18n.I18n.language]'s setter restarts the KVision root -- every screen's destroy hooks run and
 * the whole tree is rebuilt, including the conference dock host (V1.9.70), whose call view cannot be kept alive
 * across a full re-render. So the call ends (hard, via [ConferenceDock.terminate]) and the switcher asks first
 * while a call is (possibly) live: [ConferenceCallPresence.live] is the same conservative
 * signal the "new version available" banner uses (Connecting / Connected / Reconnecting / Resolving).
 *
 * [apply] runs the actual switch -- immediately when no call is live, otherwise only after the person confirmed
 * that the meeting ends. Choosing the language that is already active is a no-op. The parameters with defaults
 * are the test seams.
 */
internal fun requestLanguageChange(
    code: String,
    currentLanguage: String = I18n.language,
    callLive: Boolean = ConferenceCallPresence.live,
    apply: () -> Unit,
) {
    if (code == currentLanguage) return
    if (!callLive) {
        apply()
        return
    }
    languageChangeEndsCallDialog {
        // V1.9.70: the call now lives in the dock host, which is rebuilt together with the root -- so the switch ends it HARD first
        // (camera and microphone off, sessions closed), exactly what the dialog announced. Nothing running: no detour.
        if (ConferenceDock.state is DockState.Idle) {
            apply()
        } else {
            AppScope.launch {
                ConferenceDock.terminate(DockTerminateReason.LANGUAGE_CHANGE)
                apply()
            }
        }
    }
}

/** The confirmation of [requestLanguageChange]: states the concrete consequence in plain language; cancel keeps the call untouched. */
internal fun languageChangeEndsCallDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Sprache wechseln"))
    modal.div(tr("Die Sprache zu wechseln beendet die laufende Besprechung.")) { addCssClasses("fw-bold text-danger") }
    modal.div(tr("Die Verbindung wird getrennt. Sie können der Besprechung danach erneut beitreten.")) {
        addCssClasses("text-muted small")
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Sprache wechseln und Besprechung beenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}
