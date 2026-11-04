package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import org.w3c.dom.HTMLInputElement

/**
 * V1.9.274 -- "Kommentare öffnen" per stream target, for the moderators of the room only. Level 1 of the comments: Lapis shows no comments itself,
 * it opens the viewer page of the platform (the address the moderator entered or pasted at the start) in a new window; a platform account is
 * needed there. Shown while the stream is `STARTING`/`LIVE`/`PAUSING`/`PAUSED` and the person is a moderator; empty and hidden otherwise.
 *
 * The panel is rebuilt only when the addresses or the stream changed AND no address editor is open: the room polls the stream state, and a
 * rebuild would destroy a half typed address. The only `window.open` is [openConferenceViewerUrl].
 */
internal class StreamCommentsPanel : SimplePanel() {
    private var signature: String? = null
    private var editorOpen = false
    private var current: ConferenceStreamDto? = null
    private var currentCanModerate = false
    private var onTargetSaved: (ConferenceStreamTargetStatusDto) -> Unit = {}

    init {
        addCssClasses("ms-2")
        hide()
    }

    /** `true` while an address editor is open (a test reads it; the room does not need to). */
    val editing: Boolean get() = editorOpen

    fun update(
        stream: ConferenceStreamDto?,
        canModerate: Boolean,
        onTargetSaved: (ConferenceStreamTargetStatusDto) -> Unit,
    ) {
        current = stream
        currentCanModerate = canModerate
        this.onTargetSaved = onTargetSaved
        rebuild()
    }

    private fun visible(stream: ConferenceStreamDto?): Boolean =
        currentCanModerate &&
            stream != null &&
            stream.targets.isNotEmpty() &&
            stream.status in
            setOf(
                ConferenceStreamStatus.STARTING,
                ConferenceStreamStatus.LIVE,
                ConferenceStreamStatus.PAUSING,
                ConferenceStreamStatus.PAUSED,
            )

    private fun rebuild() {
        val stream = current
        if (!visible(stream) || stream == null) {
            if (!editorOpen) {
                signature = null
                removeAll()
                hide()
            }
            return
        }
        val newSignature = stream.id + "|" + stream.targets.joinToString("|") { "${it.destinationId}:${it.viewerUrl}" }
        if (editorOpen || newSignature == signature) return
        signature = newSignature
        removeAll()
        show()
        stream.targets.forEach { target -> renderTarget(stream, target) }
    }

    private fun renderTarget(
        stream: ConferenceStreamDto,
        target: ConferenceStreamTargetStatusDto,
    ) {
        val row = vPanel(spacing = 2) { addCssClasses("mb-1") }
        val line = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        line.div(sanitizeUntrustedI18nText(target.label)) { addCssClasses("small fw-semibold") }
        val viewerUrl = target.viewerUrl
        if (viewerUrl != null) {
            val openButton = line.button(tr("Kommentare öffnen"), style = ButtonStyle.OUTLINEPRIMARY)
            openButton.setAttribute(
                "aria-label",
                sanitizeUntrustedI18nText(gettext("Kommentare auf %1 öffnen (neues Fenster)", target.label)),
            )
            openButton.onClick { openConferenceViewerUrl(target) }
            conferenceViewerUrlHost(viewerUrl)?.let { host ->
                line.div(sanitizeUntrustedI18nText(gettext("öffnet %1", host))) { addCssClasses("text-muted small") }
            }
        }
        val editorHost = row.vPanel(spacing = 2)
        val toggle =
            line.button(
                if (viewerUrl == null) tr("Zuschauer-Adresse eintragen") else tr("Zuschauer-Adresse ändern"),
                style = ButtonStyle.LINK,
            )
        toggle.onClick {
            if (editorOpen) return@onClick
            editorOpen = true
            toggle.hide()
            val input = Tag(TAG.INPUT, className = "form-control form-control-sm")
            input.setAttribute("type", "text")
            input.setAttribute("inputmode", "url")
            input.setAttribute("autocomplete", "off")
            input.setAttribute("aria-label", sanitizeUntrustedI18nText(gettext("Zuschauer-Adresse für %1", target.label)))
            editorHost.add(input)
            editorHost.div(tr(CONFERENCE_VIEWER_URL_HINT)) { addCssClasses("text-muted small") }
            val buttons = editorHost.hPanel(spacing = 6)
            val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).also { buttons.add(it) }
            val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY).also { buttons.add(it) }
            cancel.onClick { closeEditor() }
            save.onClick {
                val typed = (input.getElement() as? HTMLInputElement)?.value?.trim().orEmpty()
                save.disabled = true
                AppScope.launch {
                    val updated =
                        guarded {
                            rpcService<IConferenceStreamingService>().setViewerUrl(stream.id, target.destinationId, typed.ifEmpty { null })
                        }
                    save.disabled = false
                    if (updated != null) {
                        current =
                            current?.let { now ->
                                now.copy(targets = now.targets.map { if (it.destinationId == updated.destinationId) updated else it })
                            }
                        onTargetSaved(updated)
                        closeEditor()
                    }
                }
            }
            (input.getElement() as? HTMLInputElement)?.focus()
        }
    }

    private fun closeEditor() {
        editorOpen = false
        signature = null
        rebuild()
    }
}
