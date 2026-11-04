package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ConferenceStreamBroadcastInput
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamRetryLimits
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto

/** V1.9.275 -- what the person sees of ONE target: the five states of the interface (the retry is a state of its own, never "Live"). */
internal enum class StreamTargetStateClass { CONNECTING, LIVE, RETRYING, FAILED, FINISHED }

internal fun streamTargetStateClass(t: ConferenceStreamTargetStatusDto): StreamTargetStateClass =
    when (t.status) {
        ConferenceStreamTargetStatus.PENDING ->
            if (t.autoRetryAttempt > 0) StreamTargetStateClass.RETRYING else StreamTargetStateClass.CONNECTING
        ConferenceStreamTargetStatus.ACTIVE -> StreamTargetStateClass.LIVE
        ConferenceStreamTargetStatus.FAILED -> StreamTargetStateClass.FAILED
        ConferenceStreamTargetStatus.FINISHED -> StreamTargetStateClass.FINISHED
    }

/** The sentence under a FAILED target. A target with no [ConferenceStreamTargetStatusDto.failureKind] shows the server's fixed text (e.g. "key missing"). */
internal fun streamTargetFailureText(
    t: ConferenceStreamTargetStatusDto,
    label: String,
): String? {
    val safe = sanitizeUntrustedI18nText(label)
    return when (t.failureKind) {
        ConferenceStreamFailureKind.REJECTED ->
            gettext("%1 hat die Verbindung abgelehnt. Prüfen Sie den Stream-Schlüssel und verbinden Sie neu.", safe)
        ConferenceStreamFailureKind.TIMEOUT -> gettext("%1 hat nicht rechtzeitig geantwortet. Verbinden Sie neu.", safe)
        ConferenceStreamFailureKind.CONNECTION -> gettext("Die Verbindung zu %1 ist fehlgeschlagen. Verbinden Sie neu.", safe)
        ConferenceStreamFailureKind.UNKNOWN -> gettext("Die Übertragung zu %1 ist abgebrochen. Verbinden Sie neu.", safe)
        null -> t.failureReason?.let { sanitizeUntrustedI18nText(it) }
    }
}

internal const val FACEBOOK_ONE_TIME_KEY_HINT =
    "Ein Einmal-Schlüssel gilt bei Facebook nur für eine Verbindung. Geben Sie einen neuen Schlüssel ein und verbinden Sie neu. " +
        "Besser: in Facebook einen dauerhaften Schlüssel einrichten."
internal const val X_ONE_TIME_KEY_HINT =
    "Ein Einmal-Schlüssel gilt bei X nur für eine Verbindung. Geben Sie einen neuen Schlüssel ein und verbinden Sie neu. " +
        "Besser: in X Media Studio einen dauerhaften Schlüssel einrichten."

/** The platform specific hint: only for a one-time key of Facebook/X whose failure may be the used-up key. */
internal fun streamTargetPlatformHint(t: ConferenceStreamTargetStatusDto): String? {
    if (t.status != ConferenceStreamTargetStatus.FAILED || t.keyKind != ConferenceStreamKeyKind.PER_BROADCAST) return null
    if (t.failureKind != ConferenceStreamFailureKind.REJECTED && t.failureKind != ConferenceStreamFailureKind.UNKNOWN) return null
    return when (t.keyHint) {
        ConferenceStreamKeyHint.FACEBOOK -> tr(FACEBOOK_ONE_TIME_KEY_HINT)
        ConferenceStreamKeyHint.X -> tr(X_ONE_TIME_KEY_HINT)
        ConferenceStreamKeyHint.OTHER -> null
    }
}

internal fun streamReconnectVisible(
    stream: ConferenceStreamDto,
    t: ConferenceStreamTargetStatusDto,
    canManage: Boolean,
): Boolean = canManage && stream.status == ConferenceStreamStatus.LIVE && t.status == ConferenceStreamTargetStatus.FAILED

/** "Live auf 2 von 3 Zielen" -- only for a LIVE stream with at least two targets of which one is not live (never a plain "Live" then). */
internal fun streamLiveHeadline(stream: ConferenceStreamDto): String? {
    if (stream.status != ConferenceStreamStatus.LIVE || stream.targets.size < 2) return null
    val live = stream.targets.count { it.status == ConferenceStreamTargetStatus.ACTIVE }
    if (live == stream.targets.size) return null
    return gettext("Live auf %1 von %2 Zielen", live, stream.targets.size)
}

internal fun streamTargetAnnouncement(
    label: String,
    cls: StreamTargetStateClass,
): String? {
    val safe = sanitizeUntrustedI18nText(label)
    return when (cls) {
        StreamTargetStateClass.LIVE -> gettext("%1 ist live.", safe)
        StreamTargetStateClass.RETRYING -> gettext("%1 wird erneut verbunden.", safe)
        StreamTargetStateClass.FAILED -> gettext("%1 ist fehlgeschlagen.", safe)
        StreamTargetStateClass.CONNECTING, StreamTargetStateClass.FINISHED -> null
    }
}

/** The one timer of the panel (the debounce of the announcement); injectable so a test can count and fire it. */
internal interface StreamPanelScheduler {
    fun after(
        millis: Int,
        action: () -> Unit,
    ): Int

    fun cancel(id: Int)
}

internal object BrowserStreamPanelScheduler : StreamPanelScheduler {
    override fun after(
        millis: Int,
        action: () -> Unit,
    ): Int = window.setTimeout(action, millis)

    override fun cancel(id: Int) = window.clearTimeout(id)
}

internal const val STREAM_TARGET_ANNOUNCE_DEBOUNCE_MS = 1500

/**
 * V1.9.275 -- one row per stream target, updated IN PLACE (a poll never destroys an open key field), with the retry state, the reason and the
 * "Neu verbinden" action for moderators. [onReconnect] returns the new stream or `null` on a refusal (the caller shows it).
 *
 * Accessibility: exactly one polite live region per panel; only a change of the state CLASS is announced (debounced), never a new attempt number.
 * The key field is [BroadcastKeyInput] unchanged (write-only, never a form field) and is emptied and closed on confirm, cancel, a state change of
 * its target and [dispose]. [dispose] also clears the only timer and removes the live region.
 */
internal class StreamTargetsPanel(
    private val onReconnect: suspend (destinationId: String, key: ConferenceStreamBroadcastInput?) -> ConferenceStreamDto?,
    private val scheduler: StreamPanelScheduler = BrowserStreamPanelScheduler,
) : SimplePanel() {
    private class Row(
        val root: SimplePanel,
        val info: SimplePanel,
        val keyHost: SimplePanel,
    ) {
        var signature: String? = null
        var stateClass: StreamTargetStateClass? = null
        var keyInput: BroadcastKeyInput? = null
        var button: Button? = null
        var busy = false
    }

    private val rows = LinkedHashMap<String, Row>()
    private val headline: Div
    private val rowHost: SimplePanel
    private val liveRegion: Div
    private var timerId: Int? = null
    private var pendingAnnouncement: String? = null
    private var disposed = false
    private var currentStream: ConferenceStreamDto? = null

    init {
        addCssClasses("ms-2")
        headline = div(className = "small fw-semibold text-warning")
        headline.hide()
        rowHost = vPanel(spacing = 2)
        liveRegion = div(className = "visually-hidden")
        liveRegion.setAttribute("role", "status")
        liveRegion.setAttribute("aria-live", "polite")
        liveRegion.setAttribute("aria-atomic", "true")
        hide()
    }

    /** Ids of the destinations that currently have a row (a test reads it). */
    val rowIds: List<String> get() = rows.keys.toList()

    /** `true` while the key field of [destinationId] is open (a test reads it). */
    fun isKeyFieldOpen(destinationId: String): Boolean = rows[destinationId]?.keyInput != null

    fun update(
        stream: ConferenceStreamDto?,
        canManage: Boolean,
    ) {
        if (disposed) return
        currentStream = stream
        val targets = stream?.targets.orEmpty()
        if (stream == null || targets.isEmpty() || stream.status == ConferenceStreamStatus.PAUSED) {
            // While paused no per-target signal exists (the stale "Live" would be a lie); an open key field is closed with its row.
            rows.values.forEach { closeKeyField(it) }
            rows.values.forEach { rowHost.remove(it.root) }
            rows.clear()
            headline.hide()
            hide()
            return
        }
        show()
        headline.content = streamLiveHeadline(stream)
        if (headline.content == null) headline.hide() else headline.show()
        val ids = targets.map { it.destinationId }.toSet()
        rows.keys.filter { it !in ids }.forEach { gone ->
            rows.remove(gone)?.let {
                closeKeyField(it)
                rowHost.remove(it.root)
            }
        }
        targets.forEach { t ->
            val row = rows.getOrPut(t.destinationId) { newRow() }
            val cls = streamTargetStateClass(t)
            val previous = row.stateClass
            if (previous != null && previous != cls) {
                closeKeyField(row)
                row.busy = false
                streamTargetAnnouncement(t.label, cls)?.let { queueAnnouncement(it) }
            }
            row.stateClass = cls
            val signature =
                listOf(
                    cls.name,
                    t.label,
                    t.failureKind?.name,
                    t.failureReason,
                    t.autoRetryAttempt,
                    t.keyKind.name,
                    t.keyHint.name,
                    streamReconnectVisible(stream, t, canManage),
                ).joinToString("|")
            if (signature != row.signature) {
                row.signature = signature
                renderInfo(row = row, stream = stream, t = t, canManage = canManage)
            }
        }
    }

    private fun newRow(): Row {
        val root = rowHost.vPanel(spacing = 1) { addCssClasses("lapis-stream-target-row") }
        val info = root.vPanel(spacing = 1)
        val keyHost = root.vPanel(spacing = 1)
        return Row(root = root, info = info, keyHost = keyHost)
    }

    private fun renderInfo(
        row: Row,
        stream: ConferenceStreamDto,
        t: ConferenceStreamTargetStatusDto,
        canManage: Boolean,
    ) {
        row.info.removeAll()
        row.button = null
        val cls = streamTargetStateClass(t)
        val line = row.info.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
        val color =
            when (cls) {
                StreamTargetStateClass.LIVE -> "success"
                StreamTargetStateClass.FAILED -> "danger"
                StreamTargetStateClass.FINISHED -> "secondary"
                StreamTargetStateClass.CONNECTING, StreamTargetStateClass.RETRYING -> "warning"
            }
        val badgeText =
            when (cls) {
                StreamTargetStateClass.RETRYING -> "↻ " + gettext("Wird erneut verbunden")
                StreamTargetStateClass.FAILED -> "✕ " + conferenceStreamTargetStatusLabel(t.status)
                StreamTargetStateClass.LIVE -> "● " + conferenceStreamTargetStatusLabel(t.status)
                else -> conferenceStreamTargetStatusLabel(t.status)
            }
        line.statusBadge(badgeText, color)
        // Admin-/provider-controlled free text: sanitized before KVision can resolve a forged marker on render.
        line.div(sanitizeUntrustedI18nText(t.label)) { addCssClasses("small") }
        if (cls == StreamTargetStateClass.RETRYING) {
            row.info.div(
                sanitizeUntrustedI18nText(
                    gettext("Wird erneut verbunden (%1 von %2) …", t.autoRetryAttempt, ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS),
                ),
            ) { addCssClasses("text-warning small") }
        }
        if (cls == StreamTargetStateClass.FAILED) {
            streamTargetFailureText(t, t.label)?.let { row.info.div(it) { addCssClasses("text-danger small") } }
            if (t.autoRetryAttempt > 0) {
                row.info.div(gettext("Automatische Versuche: %1", t.autoRetryAttempt)) { addCssClasses("text-muted small") }
            }
            streamTargetPlatformHint(t)?.let { row.info.div(it) { addCssClasses("text-muted small") } }
        }
        if (streamReconnectVisible(stream, t, canManage)) {
            val label = sanitizeUntrustedI18nText(t.label)
            val button = row.info.button(tr("Neu verbinden"), style = ButtonStyle.OUTLINEPRIMARY)
            button.addCssClasses("lapis-stream-reconnect-btn")
            button.setAttribute("aria-label", gettext("%1 neu verbinden", label))
            button.onClick { onReconnectClicked(row = row, t = t) }
            row.button = button
        }
    }

    private fun onReconnectClicked(
        row: Row,
        t: ConferenceStreamTargetStatusDto,
    ) {
        if (row.busy || row.keyInput != null) return
        if (t.keyKind == ConferenceStreamKeyKind.PER_BROADCAST) {
            openKeyField(row = row, t = t)
        } else {
            submit(row = row, destinationId = t.destinationId, key = null)
        }
    }

    private fun openKeyField(
        row: Row,
        t: ConferenceStreamTargetStatusDto,
    ) {
        val label = sanitizeUntrustedI18nText(t.label)
        val input =
            BroadcastKeyInput(
                labelText = gettext("Neuer Stream-Schlüssel für %1 (optional)", label),
                hintText = tr("Leer lassen, um den bisherigen Schlüssel erneut zu verwenden."),
            )
        row.keyHost.add(input)
        val actions = row.keyHost.hPanel(spacing = 6) { addCssClasses("lapis-stream-reconnect-actions") }
        val confirm = actions.button(tr("Verbinden"), style = ButtonStyle.PRIMARY)
        val cancel = actions.actionButton(ActionIcon.CANCEL, tr("Abbrechen"))
        confirm.addCssClasses("lapis-stream-reconnect-btn")
        cancel.addCssClasses("lapis-stream-reconnect-btn")
        row.keyInput = input
        confirm.onClick {
            val problem = input.problem()
            // An empty field is fine (reuse the sealed key); anything else must pass the key check.
            if (problem != null && problem != broadcastKeyProblem("")) {
                input.showError(problem)
                return@onClick
            }
            val typed = input.takeAndClear()
            closeKeyField(row)
            submit(
                row = row,
                destinationId = t.destinationId,
                key = typed?.let { ConferenceStreamBroadcastInput(destinationId = t.destinationId, streamKey = it) },
            )
        }
        cancel.onClick {
            closeKeyField(row)
            row.button?.focus()
        }
        input.focusInput()
    }

    private fun closeKeyField(row: Row) {
        val input = row.keyInput ?: return
        input.clear()
        row.keyHost.removeAll()
        row.keyInput = null
    }

    private fun submit(
        row: Row,
        destinationId: String,
        key: ConferenceStreamBroadcastInput?,
    ) {
        row.busy = true
        row.button?.let {
            it.disabled = true
            it.text = tr("Wird verbunden …")
        }
        AppScope.launch {
            val result =
                try {
                    onReconnect(destinationId, key)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            row.busy = false
            if (disposed) return@launch
            if (result == null) {
                row.button?.let {
                    it.disabled = false
                    it.text = tr("Neu verbinden")
                }
            }
        }
    }

    private fun queueAnnouncement(text: String) {
        pendingAnnouncement = text
        timerId?.let { scheduler.cancel(it) }
        timerId =
            scheduler.after(STREAM_TARGET_ANNOUNCE_DEBOUNCE_MS) {
                timerId = null
                val message = pendingAnnouncement
                pendingAnnouncement = null
                if (!disposed && message != null) liveRegion.content = message
            }
    }

    /** Clears the timer, empties and closes every key field and removes the live region. Safe to call twice. */
    override fun dispose() {
        if (disposed) return
        disposed = true
        timerId?.let { scheduler.cancel(it) }
        timerId = null
        pendingAnnouncement = null
        rows.values.forEach { closeKeyField(it) }
        rows.clear()
        removeAll()
        hide()
        super.dispose()
    }
}
