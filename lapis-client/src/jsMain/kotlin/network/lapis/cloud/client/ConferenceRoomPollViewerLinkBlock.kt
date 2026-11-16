package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.RoomPollViewerLinkDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.Promise

internal const val ROOM_POLL_LINK_BLOCK_CLASS = "lapis-room-poll-viewer-link"
internal const val ROOM_POLL_QR_OVERLAY_CLASS = "lapis-room-poll-qr-overlay"
private const val QR_SMALL_PX = "160"
private const val COPIED_FOR_MS = 2_000

/**
 * Room polls, wave B -- the leadership's "Zuschauer-Link" block under the poll form: the link to the viewer page, "Link kopieren", the QR code
 * and a full-screen QR view for a projector. Everything about the link comes from the server ([fetch] = `getRoomPollViewerLink`, leadership
 * only); it is requested lazily, ONCE, when the panel first learns the caller is leadership. A refusal hides the block silently. The URL goes
 * into a read-only field through `value` (never as markup); the QR code is built from DOM calls only ([roomPollQrSvg]).
 */
internal class ConferenceRoomPollViewerLinkBlock(
    parent: Container,
    private val roomId: String,
    private val fetch: suspend (String) -> RoomPollViewerLinkDto,
    private val announce: (String) -> Unit,
) {
    private val host: SimplePanel = parent.vPanel(spacing = 6) { addCssClasses("$ROOM_POLL_LINK_BLOCK_CLASS border-top pt-2") }
    private var requested = false
    private var link: RoomPollViewerLinkDto? = null
    private var streaming: Boolean? = null
    private var hint: Div? = null
    private var closeOverlay: (() -> Unit)? = null
    private var disposed = false

    init {
        host.hide()
    }

    /** Called with every state: the first one with leadership starts the one request. */
    fun onLeadership(isLeadership: Boolean) {
        if (!isLeadership || requested || disposed) return
        requested = true
        AppScope.launch {
            val fetched =
                try {
                    fetch(roomId)
                } catch (e: Throwable) {
                    null
                }
            if (fetched != null && !disposed) {
                link = fetched
                build(fetched)
            }
        }
    }

    /** `false` = the room does not stream (yet): the block says the link works as soon as it does. `null` = not known. */
    fun setStreaming(value: Boolean?) {
        streaming = value
        paintHint()
    }

    fun dispose() {
        disposed = true
        closeOverlay?.invoke()
    }

    private fun paintHint() {
        val tag = hint ?: return
        if (streaming == false) tag.show() else tag.hide()
    }

    private fun build(link: RoomPollViewerLinkDto) {
        host.tag(TAG.H3, content = gettext("Zuschauer-Link"), className = "h6 mb-0")
        val field = host.tag(TAG.INPUT, className = "form-control form-control-sm lapis-room-poll-viewer-url")
        field.setAttribute("type", "text")
        field.setAttribute("readonly", "")
        field.setAttribute("value", link.url)
        field.setAttribute("aria-label", gettext("Zuschauer-Link"))
        val status = host.div("", className = "small text-muted lapis-room-poll-copy-status")
        val copy = host.actionButton(ActionIcon.COPY, tr("Link kopieren"), style = ButtonStyle.OUTLINESECONDARY) { addCssClass("btn-sm") }
        copy.onClick {
            val clipboard = window.navigator.asDynamic().clipboard
            if (clipboard == null) {
                (field.getElement() as? HTMLInputElement)?.select()
            } else {
                val written = clipboard.writeText(link.url).unsafeCast<Promise<Any?>>()
                written.then({
                    status.content = gettext("Kopiert")
                    announce(gettext("Kopiert"))
                    window.setTimeout({ status.content = "" }, COPIED_FOR_MS)
                }, { (field.getElement() as? HTMLInputElement)?.select() })
            }
        }
        val qrHost = Div(className = "lapis-room-poll-qr-host")
        host.addWithLifecycle(
            qrHost,
            onInsert = { vnode ->
                (vnode.elm as? HTMLElement)?.let { element ->
                    while (element.firstChild != null) element.removeChild(element.firstChild!!)
                    element.appendChild(roomPollQrSvg(link, QR_SMALL_PX))
                }
            },
        )
        val large =
            host.actionButton(
                ActionIcon.FULLSCREEN,
                tr("QR groß anzeigen"),
                style = ButtonStyle.OUTLINESECONDARY,
            ) { addCssClass("btn-sm") }
        large.onClick {
            closeOverlay?.invoke()
            closeOverlay = openRoomPollQrOverlay(link, opener = large.getElement() as? HTMLElement) { closeOverlay = null }
        }
        hint =
            host.div(
                gettext("Der Link funktioniert, sobald dieser Raum streamt."),
                className = "small text-muted lapis-room-poll-link-hint",
            )
        paintHint()
        host.show()
    }
}

/**
 * The full-viewport QR view: always white with black (a fixed pair of tokens, the same in every theme), the heading, the code and the URL in
 * large type, and "Schließen". Escape closes it and the focus returns to [opener]. `role=dialog`, `aria-modal=true`; Tab stays on the one button.
 * Returns a function that closes it (idempotent).
 */
internal fun openRoomPollQrOverlay(
    link: RoomPollViewerLinkDto,
    opener: HTMLElement?,
    onClosed: () -> Unit = {},
): () -> Unit {
    val overlay = document.createElement("div") as HTMLElement
    overlay.className = ROOM_POLL_QR_OVERLAY_CLASS
    overlay.setAttribute("role", "dialog")
    overlay.setAttribute("aria-modal", "true")
    overlay.setAttribute("aria-labelledby", "lapis-room-poll-qr-title")
    val heading = document.createElement("h2") as HTMLElement
    heading.id = "lapis-room-poll-qr-title"
    heading.className = "lapis-room-poll-qr-title"
    heading.textContent = gettext("Zur Umfrage: QR-Code scannen")
    val code = document.createElement("div") as HTMLElement
    code.className = "lapis-room-poll-qr-large"
    code.appendChild(roomPollQrSvg(link, "100%"))
    val url = document.createElement("p") as HTMLElement
    url.className = "lapis-room-poll-qr-url"
    url.textContent = link.url
    val close = document.createElement("button") as HTMLElement
    close.setAttribute("type", "button")
    close.className = "btn btn-dark btn-lg lapis-room-poll-qr-close"
    close.textContent = gettext("Schließen")
    overlay.appendChild(heading)
    overlay.appendChild(code)
    overlay.appendChild(url)
    overlay.appendChild(close)
    var closed = false
    lateinit var keyListener: (Event) -> Unit

    fun closeNow() {
        if (closed) return
        closed = true
        document.removeEventListener("keydown", keyListener)
        overlay.parentNode?.removeChild(overlay)
        opener?.focus()
        onClosed()
    }
    keyListener = { event ->
        val key = (event as? KeyboardEvent)?.key
        if (key == "Escape") {
            event.preventDefault()
            closeNow()
        } else if (key == "Tab") {
            // the one focusable element: the focus never leaves the dialog
            event.preventDefault()
            close.focus()
        }
    }
    close.addEventListener("click", { closeNow() })
    document.addEventListener("keydown", keyListener)
    document.body?.appendChild(overlay)
    close.focus()
    return ::closeNow
}
