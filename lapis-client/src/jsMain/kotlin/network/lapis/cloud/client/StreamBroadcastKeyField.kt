package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import org.w3c.dom.HTMLInputElement

/**
 * V1.9.274 -- the typed-in stream key of ONE broadcast (a destination whose key the platform issues anew for every broadcast).
 *
 * A raw `<input>` and not a [io.kvision.form.text.Password], on purpose: a `type=password` field is offered to password managers and
 * (Chrome) to "save this password", which is exactly what must not happen to a key that is meant to live for one broadcast. So the field is
 * `type=text` with the CSS `-webkit-text-security: disc` mask, every manager/autofill request switched off and no surrounding `<form>`. Where
 * the browser does not know `-webkit-text-security` (feature-detected once per field), the fallback is `type=password` with `autocomplete=off`.
 *
 * The value is read ONLY through [takeAndClear], which empties the field at once; nothing keeps it in a Kotlin field, an attribute, a title or a
 * message (the feedback line below shows the length, never the content). Nothing is trimmed: a space or line break is an error the person sees.
 */
internal class BroadcastKeyInput(
    labelText: String,
    hintText: String?,
) : SimplePanel() {
    private val serial = nextSerial++
    private val inputId = "lapis-broadcast-key-$serial"
    private val hintId = "$inputId-hint"
    private val feedbackId = "$inputId-feedback"
    private val errorId = "$inputId-error"

    private val describedByBase = mutableListOf<String>()

    private val input: Tag
    private val feedback: Div
    private val errorBox: Div

    init {
        addCssClasses("lapis-broadcast-key mb-2")
        tag(TAG.LABEL, content = labelText, className = "form-label").setAttribute("for", inputId)
        val masked = cssSupportsTextSecurity()
        input = tag(TAG.INPUT, className = if (masked) "form-control lapis-secret-mask" else "form-control")
        input.setAttribute("id", inputId)
        input.setAttribute("type", if (masked) "text" else "password")
        SECRET_INPUT_ATTRIBUTES.forEach { (name, value) -> input.setAttribute(name, value) }
        input.setAttribute("maxlength", BROADCAST_KEY_INPUT_LIMIT.toString())
        if (hintText != null) {
            div(hintText, className = "text-muted small").setAttribute("id", hintId)
            describedByBase += hintId
        }
        // Plain text, deliberately NOT a live region: a screen reader must not announce the length of a secret on every keystroke.
        feedback = div(className = "text-muted small")
        feedback.setAttribute("id", feedbackId)
        describedByBase += feedbackId
        errorBox = div(className = "text-danger small")
        errorBox.setAttribute("id", errorId)
        errorBox.hide()
        input.setAttribute("aria-describedby", describedByBase.joinToString(" "))
        input.onEvent { this.input = { refreshFeedback() } }
    }

    private fun element(): HTMLInputElement? = input.getElement() as? HTMLInputElement

    /** The current text, untouched. Used only for the length line and the check; the value is emptied by [takeAndClear]. */
    private fun raw(): String = element()?.value.orEmpty()

    private fun refreshFeedback() {
        val length = raw().length
        feedback.content = if (length == 0) null else gettext("✓ Schlüssel eingegeben (%1 Zeichen)", length)
        if (length > 0) clearError()
    }

    /** `null` = fine, else the message for the field. Nothing is trimmed or repaired. */
    fun problem(): String? = broadcastKeyProblem(raw())

    fun showError(message: String) {
        errorBox.content = message
        errorBox.show()
        input.setAttribute("aria-invalid", "true")
        input.setAttribute("aria-describedby", (describedByBase + errorId).joinToString(" "))
    }

    fun clearError() {
        errorBox.content = null
        errorBox.hide()
        input.removeAttribute("aria-invalid")
        input.setAttribute("aria-describedby", describedByBase.joinToString(" "))
    }

    fun focusInput() {
        element()?.focus()
    }

    /** Reads the key and empties the field in one step. `null` when it was empty. */
    fun takeAndClear(): String? {
        val el = element() ?: return null
        val text = el.value
        el.value = ""
        refreshFeedback()
        return text.takeIf { it.isNotEmpty() }
    }

    /** Empties the field without reading it. */
    fun clear() {
        element()?.value = ""
        refreshFeedback()
    }

    private companion object {
        var nextSerial = 0
    }
}

/** The 512-character bound the server enforces; the native `maxlength` only keeps a paste from running away. */
internal const val BROADCAST_KEY_INPUT_LIMIT = 512

private val SECRET_INPUT_ATTRIBUTES: Map<String, String> =
    mapOf(
        "autocomplete" to "off",
        "autocapitalize" to "off",
        "autocorrect" to "off",
        "spellcheck" to "false",
        "data-1p-ignore" to "true",
        "data-lpignore" to "true",
        "data-bwignore" to "true",
        "data-form-type" to "other",
    )

/** Feature test for the CSS mask; evaluated for every new field so a test can stub `CSS.supports`. */
internal fun cssSupportsTextSecurity(): Boolean =
    try {
        js("typeof CSS !== 'undefined' && typeof CSS.supports === 'function' && CSS.supports('-webkit-text-security', 'disc')") as Boolean
    } catch (e: Throwable) {
        false
    }

/** The check of a typed broadcast key (mirrors the server rule: 1..512 printable ASCII characters without space). `null` = fine. */
internal fun broadcastKeyProblem(text: String): String? =
    when {
        text.isEmpty() -> tr("Bitte geben Sie den Stream-Schlüssel dieser Sendung ein.")
        text.any { it.code <= 0x20 || it.code == 0x7F } -> tr("Der Schlüssel darf keine Leerzeichen oder Zeilenumbrüche enthalten.")
        text.length > BROADCAST_KEY_INPUT_LIMIT || text.any { it.code > 0x7E } ->
            tr("Der Schlüssel enthält ungültige Zeichen oder ist zu lang.")
        else -> null
    }

/** The hint under the key field of a per-broadcast destination, by where the platform shows the key. */
internal fun broadcastKeyHint(hint: ConferenceStreamKeyHint): String =
    when (hint) {
        ConferenceStreamKeyHint.FACEBOOK -> tr("Steht im Facebook Live Producer unter \"Streamschlüssel\".")
        ConferenceStreamKeyHint.X -> tr("Steht im X Media Studio unter \"Producer\".")
        ConferenceStreamKeyHint.OTHER ->
            tr("Zeigt die Plattform beim Einrichten der Sendung an, bei YouTube im YouTube Studio.")
    }

/** The "Schlüssel gilt:" choice of a destination form: two radios (name per form) and the value kept in step with them. */
internal class KeyKindRadios(
    parent: Container,
    serial: Int,
    initial: ConferenceStreamKeyKind,
    private val onChange: (ConferenceStreamKeyKind) -> Unit,
) {
    var value: ConferenceStreamKeyKind = initial
        private set

    private val inputs = mutableMapOf<ConferenceStreamKeyKind, Tag>()

    init {
        val group = parent.div(className = "mb-2")
        group.setAttribute("role", "radiogroup")
        group.setAttribute("aria-label", gettext("Schlüssel gilt:"))
        group.div(tr("Schlüssel gilt:"), className = "form-label fw-semibold mb-1")
        ConferenceStreamKeyKind.entries.forEach { kind ->
            val row = group.div(className = "form-check")
            val id = "lapis-key-kind-$serial-${kind.name.lowercase()}"
            val radio = row.tag(TAG.INPUT, className = "form-check-input")
            radio.setAttribute("type", "radio")
            radio.setAttribute("name", "lapis-key-kind-$serial")
            radio.setAttribute("id", id)
            radio.setAttribute("value", kind.name)
            if (kind == initial) radio.setAttribute("checked", "")
            val label = row.tag(TAG.LABEL, className = "form-check-label")
            label.setAttribute("for", id)
            label.add(Tag(TAG.SPAN, content = keyKindLabel(kind), className = "d-block"))
            label.add(Tag(TAG.SPAN, content = keyKindHelp(kind), className = "d-block text-muted small"))
            radio.onEvent { change = { choose(kind) } }
            inputs[kind] = radio
        }
    }

    private fun choose(kind: ConferenceStreamKeyKind) {
        if (value == kind) return
        value = kind
        onChange(kind)
    }
}

internal fun keyKindLabel(kind: ConferenceStreamKeyKind): String =
    when (kind) {
        ConferenceStreamKeyKind.PERSISTENT -> tr("dauerhaft")
        ConferenceStreamKeyKind.PER_BROADCAST -> tr("nur pro Sendung")
    }

internal fun keyKindHelp(kind: ConferenceStreamKeyKind): String =
    when (kind) {
        ConferenceStreamKeyKind.PERSISTENT -> tr("Lapis speichert den Schlüssel verschlüsselt und nutzt ihn für jede Sendung.")
        ConferenceStreamKeyKind.PER_BROADCAST ->
            tr("Sie geben den Schlüssel bei jedem Start neu ein. Lapis löscht ihn, wenn die Sendung endet.")
    }

// ================================================================================================
// "Kommentare öffnen" -- the one place a viewer page is opened
// ================================================================================================

private val YOUTUBE_CHAT_POPOUT_PREFIX = "https://www.youtube.com/live_chat?is_popout=1&v="
private val TWITCH_CHAT_POPOUT = Regex("^https://www\\.twitch\\.tv/popout/[a-z0-9_]{4,25}/chat$")

/** Only the two chat pages that are built for a small window open as a popup; everything else opens as a tab. */
internal fun conferenceViewerUrlOpensPopup(url: String): Boolean =
    url.startsWith(YOUTUBE_CHAT_POPOUT_PREFIX) || TWITCH_CHAT_POPOUT.matches(url)

/** The host of a stored viewer address for the small "öffnet …" line (without `www.`), or `null` if it cannot be read. */
internal fun conferenceViewerUrlHost(url: String): String? =
    try {
        js("new URL(url).hostname") as String
    } catch (e: Throwable) {
        null
    }?.removePrefix("www.")

/**
 * THE only `window.open` for a viewer page. The address comes from the target the SERVER sent (already normalised and checked there), never from
 * an input field of this page. `noopener,noreferrer` always; the popup size only for the YouTube/Twitch chat pages. With `noopener` the browser
 * returns `null` whatever happens, so a blocked popup cannot be detected and no message is built on it.
 */
internal fun openConferenceViewerUrl(target: ConferenceStreamTargetStatusDto) {
    val url = target.viewerUrl ?: return
    if (!url.startsWith("https://")) return
    val features = if (conferenceViewerUrlOpensPopup(url)) "noopener,noreferrer,popup,width=420,height=720" else "noopener,noreferrer"
    window.open(url, "_blank", features)
}
