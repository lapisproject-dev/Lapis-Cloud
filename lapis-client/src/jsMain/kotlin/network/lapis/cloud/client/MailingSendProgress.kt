package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import network.lapis.cloud.shared.rpc.IMailingService

/** How often a running send is re-read while it is QUEUED. */
internal const val MAILING_PROGRESS_POLL_MS = 15_000L

/**
 * Welle V1.9.81 -- the progress of a mailing-list send that is still `QUEUED`: a static `<progress>` and the text beside it.
 * No animation (R54), no fixed width (R55), no inner scroll area (R59) -- the bar is a plain full-width element, the numbers are text.
 *
 * Polls [fetch] every [pollIntervalMs] (15 s) from the moment the panel is in the document until it leaves it or the send has nothing
 * left to do (then [onFinished] runs once, so the list can show the final status). The timer lives exactly as long as the widget:
 * it is started by the insert hook and cancelled by the destroy hook (see [addWithLifecycle]); both are safe to run more than once
 * (a language change rebuilds the document from the same widget objects). A failed or rate-limited poll is simply skipped -- a
 * progress display must never raise an error toast every 15 seconds.
 */
internal class MailingSendProgressPanel(
    private val messageId: String,
    private val onFinished: () -> Unit,
    private val pollIntervalMs: Long = MAILING_PROGRESS_POLL_MS,
    private val fetch: suspend (String) -> MailingSendProgressDto? = { id ->
        runCatching { rpcService<IMailingService>().mailingSendProgress(id) }.getOrNull()
    },
) : VPanel(spacing = 4) {
    private val headline = Div(className = "small")
    private val bar = Tag(TAG.PROGRESS, className = "w-100")
    private val pauseLine = Div(className = "text-muted small")
    private val interruptedLine = Div(className = "small text-warning-emphasis")
    private val failedLine = Div(className = "small text-danger")
    private var job: Job? = null
    private var finished = false

    init {
        addCssClass("lapis-send-progress")
        add(headline)
        add(bar)
        add(pauseLine)
        add(interruptedLine)
        add(failedLine)
        bar.setAttribute("max", "1")
        bar.setAttribute("value", "0")
        bar.setAttribute("aria-label", "progress")
        untrustedContent(headline, "")
        pauseLine.hide()
        interruptedLine.hide()
        failedLine.hide()
    }

    /** Idempotent: a second call while the poll is running does nothing. */
    fun start() {
        if (job != null || finished) return
        job =
            AppScope.launch {
                while (true) {
                    val progress = fetch(messageId)
                    if (progress != null) {
                        render(progress)
                        if (progress.pending == 0) {
                            finished = true
                            onFinished()
                            break
                        }
                    }
                    delay(pollIntervalMs)
                }
            }
    }

    /** Cancels the poll; the panel can be started again (re-attach after a language change). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Shows [progress]; `internal` so the DOM test can drive it without a server. */
    internal fun render(progress: MailingSendProgressDto) {
        val texts = mailingSendProgressTexts(progress)
        untrustedContent(headline, texts.headline)
        val done = (progress.total - progress.pending).coerceAtLeast(0)
        bar.setAttribute("max", progress.total.coerceAtLeast(1).toString())
        bar.setAttribute("value", done.toString())
        bar.setAttribute("aria-label", texts.headline)
        setLine(pauseLine, texts.pause)
        setLine(interruptedLine, texts.interrupted)
        setLine(failedLine, texts.failed)
    }

    private fun setLine(
        line: Div,
        text: String?,
    ) {
        if (text == null) {
            line.hide()
        } else {
            untrustedContent(line, text)
            line.show()
        }
    }
}

/** Adds a [MailingSendProgressPanel] for [messageId] with its timer tied to the widget's life. */
internal fun SimplePanel.mailingSendProgress(
    messageId: String,
    onFinished: () -> Unit,
): MailingSendProgressPanel {
    val panel = MailingSendProgressPanel(messageId = messageId, onFinished = onFinished)
    return addWithLifecycle(panel, onInsert = { panel.start() }, onDestroy = { panel.stop() })
}
