package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.I18n
import io.kvision.i18n.tr
import io.kvision.modal.Modal

/**
 * Audit fix B2 (V1.4.31): a language switch must never end a running video conference by accident.
 *
 * [io.kvision.i18n.I18n.language]'s setter restarts the KVision root -- every screen's destroy hooks run and
 * the whole tree is rebuilt. For the conference screen the destroy hook is the real teardown (resets
 * [ConferenceCallPresence], removes the `beforeunload` listener and DISCONNECTS the LiveKit session), and it
 * cannot be made re-attachable without keeping a live media session alive across a full re-render. So the
 * switcher asks first while a call is (possibly) live: [ConferenceCallPresence.live] is the same conservative
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
    languageChangeEndsCallDialog(apply)
}

/** The confirmation of [requestLanguageChange]: states the concrete consequence in plain language; cancel keeps the call untouched. */
internal fun languageChangeEndsCallDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Sprache wechseln"))
    modal.div(tr("Die Sprache zu wechseln beendet die laufende Besprechung.")) { addCssClasses("fw-bold text-danger") }
    modal.div(tr("Die Verbindung wird getrennt. Sie können der Besprechung danach erneut beitreten.")) {
        addCssClasses("text-muted small")
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
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
