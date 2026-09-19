package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AiAnswerOutcome
import network.lapis.cloud.shared.domain.AiAssistantStateDto
import network.lapis.cloud.shared.domain.AiFeature
import network.lapis.cloud.shared.domain.StatuteAnswerDto
import network.lapis.cloud.shared.rpc.IAiAssistantService

/** One question and its answer, kept **only** in this screen's memory for the running session. */
private class QaEntry(
    val question: String,
    val answer: StatuteAnswerDto,
)

/**
 * "Fragen zur Satzung" (V1.6.1, optional AI assistance, default OFF server-side) -- Design-Team
 * decision, implemented 1:1: a single column, no panels/tiles/illustration; from the top: heading,
 * consent switch with two explanatory sentences (never pre-selected, revocable here at any time),
 * the "searched documents" block (indexed documents with version, then the ones that could not be
 * indexed with their reason), the input, and the running session log (newest first, memory only --
 * no persistence, no `localStorage`, no context handed to the model).
 *
 * **Everything model-derived is rendered as plain text nodes** (`p(text)`/`div(text)` -- never
 * `rich = true`, never `innerHTML`, no Markdown renderer). Combined with the server-side
 * `PlainTextSanitizer` this is the client half of the XSS defence for untrusted model output.
 * Citations carry no link (a citation mark is not a hyperlink).
 *
 * The screen makes no RPC call at all when the session says the AI layer is off
 * ([network.lapis.cloud.shared.domain.SessionInfoDto.aiAssistantEnabled]): the service would not
 * even be registered. All wording and state logic lives in [StatuteQaUi] (unit-tested).
 */
fun renderStatuteQaScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1(tr("Fragen zur Satzung"))

    if (AppState.session?.aiAssistantEnabled != true) {
        root.p(tr(StatuteQaUi.DISABLED_TEXT))
        return
    }

    val consentPanel = root.vPanel(spacing = 6)
    val scopePanel = root.vPanel(spacing = 4)
    val inputPanel = root.vPanel(spacing = 6)
    val logPanel = root.vPanel(spacing = 14)

    var state: AiAssistantStateDto? = null
    var optIn = false
    var loading = false
    var lastOutcome: AiAnswerOutcome? = null
    val log = mutableListOf<QaEntry>()

    val question = inputPanel.textArea(label = tr("Ihre Frage"), rows = 3) { addCssClass("w-100") }
    val counter = inputPanel.div { addCssClasses("text-muted small") }
    val hint = inputPanel.div { addCssClasses("text-muted small") }
    val submit = inputPanel.button(tr("Frage stellen"), icon = "fas fa-magnifying-glass", style = ButtonStyle.PRIMARY)
    counter.hide()

    fun refreshInput() {
        val current = state
        val length =
            question.value
                .orEmpty()
                .trim()
                .length
        val locked = loading || !optIn || current == null
        question.disabled = locked
        submit.disabled =
            current == null ||
            !StatuteQaUi.canSubmit(
                trimmedLength = length,
                min = current.minQuestionChars,
                max = current.maxQuestionChars,
                optIn = optIn,
                loading = loading,
            )
        val rawLength = question.value.orEmpty().length
        if (current != null && StatuteQaUi.counterVisible(rawLength)) {
            counter.content = gettext("%1 von %2 Zeichen", rawLength, current.maxQuestionChars)
            counter.show()
        } else {
            counter.hide()
        }
        hint.content =
            when (StatuteQaUi.stateFor(featureEnabled = true, optIn = optIn, loading = loading, lastOutcome = lastOutcome)) {
                StatuteQaState.OPT_IN_MISSING -> tr(StatuteQaUi.OPT_IN_REQUIRED_TEXT)
                else -> ""
            }
    }

    fun renderScope() {
        scopePanel.removeAll()
        val current = state ?: return
        scopePanel.h2(tr("Durchsuchte Dokumente"))
        if (current.indexedScope.isEmpty() && current.unindexedScope.isEmpty()) {
            scopePanel.p(tr("Noch keine Dokumente für die KI-Auskunft freigegeben."))
            return
        }
        current.indexedScope.forEach { item ->
            scopePanel.div(gettext("%1 · v%2", item.documentTitle, item.versionNumber))
        }
        current.unindexedScope.forEach { item ->
            val reason = tr(StatuteQaUi.unindexedReasonText(item.unindexedReason))
            scopePanel.div(gettext("%1 · v%2 (%3)", item.documentTitle, item.versionNumber, reason)) { addCssClass("text-muted") }
        }
    }

    fun renderAnswer(
        entryPanel: SimplePanel,
        answer: StatuteAnswerDto,
    ) {
        when (answer.outcome) {
            AiAnswerOutcome.ANSWERED -> {
                entryPanel.p(answer.summary.orEmpty())
                answer.citations.forEach { citation ->
                    val header = StatuteQaUi.citationHeader(citation.documentTitle, citation.versionNumber, citation.locator)
                    entryPanel.div(header) { addCssClasses("text-muted small") }
                    entryPanel.div(StatuteQaUi.shortenExcerpt(citation.excerpt)) { addCssClasses("border-start ps-3 fst-italic") }
                }
                entryPanel.p(tr(StatuteQaUi.DISCLAIMER))
            }
            AiAnswerOutcome.NOTHING_FOUND -> {
                entryPanel.p(tr(StatuteQaUi.NOTHING_FOUND_TEXT))
                if (answer.searchedDocuments.isNotEmpty()) {
                    val titles = answer.searchedDocuments.joinToString(separator = ", ") { it.documentTitle }
                    entryPanel.div("${tr("Durchsuchte Dokumente")}: $titles") { addCssClasses("text-muted small") }
                }
            }
            AiAnswerOutcome.RATE_LIMITED -> {
                val (key, amount) = StatuteQaUi.waitParts(answer.retryAfterSeconds ?: 0)
                val wait = if (amount == null) tr(key) else gettext(key, amount)
                entryPanel.p(gettext(StatuteQaUi.RATE_LIMITED_TEXT, wait))
            }
            AiAnswerOutcome.PROVIDER_UNAVAILABLE -> entryPanel.p(tr(StatuteQaUi.PROVIDER_ERROR_TEXT))
        }
    }

    fun renderLog() {
        logPanel.removeAll()
        if (loading) {
            val bar = logPanel.div(className = "progress")
            val inner = bar.div(className = "progress-bar progress-bar-striped progress-bar-animated")
            inner.setAttribute("role", "progressbar")
            inner.setStyle("width", "100%")
            logPanel.div(tr(StatuteQaUi.LOADING_TEXT)) { addCssClasses("text-muted small") }
        }
        log.forEach { entry ->
            val entryPanel: Div = logPanel.div { addCssClasses("border-bottom pb-3") }
            entryPanel.div(entry.question) { addCssClass("fw-bold") }
            val body = entryPanel.vPanel(spacing = 6)
            renderAnswer(body, entry.answer)
        }
    }

    fun renderConsent() {
        consentPanel.removeAll()
        val box: CheckBox =
            consentPanel.checkBox(
                value = optIn,
                label = tr("Ich stimme zu, dass meine Fragen von einer KI beantwortet werden"),
            )
        consentPanel.p(tr(StatuteQaUi.OPT_IN_TEXT)) { addCssClass("text-muted") }
        box.subscribe { checked ->
            // Compared against the last KNOWN server state, so the initial synthetic callback and the
            // programmatic revert below are both no-ops regardless of KVision's callback timing.
            if (checked == optIn) return@subscribe
            AppScope.launch {
                val updated = guarded { rpcService<IAiAssistantService>().setMemberOptIn(AiFeature.STATUTE_QA, checked) }
                if (updated == null) {
                    box.value = optIn
                } else {
                    state = updated
                    optIn = updated.optIn
                    notifySuccess(if (optIn) tr("Zustimmung gespeichert.") else tr("Zustimmung widerrufen."))
                    renderScope()
                    refreshInput()
                }
            }
        }
    }

    submit.onClick {
        val text = question.value.orEmpty().trim()
        val current = state ?: return@onClick
        if (!StatuteQaUi.canSubmit(text.length, current.minQuestionChars, current.maxQuestionChars, optIn, loading)) return@onClick
        loading = true
        refreshInput()
        renderLog()
        AppScope.launch {
            val answer = guarded { rpcService<IAiAssistantService>().askStatuteQuestion(text) }
            loading = false
            if (answer != null) {
                lastOutcome = answer.outcome
                log.add(0, QaEntry(question = text, answer = answer))
                question.value = null
            } else {
                // The error toast was already shown by `guarded`; refresh the server state (an opt-in
                // revoked in another tab, say) so the screen does not keep a stale picture.
                guarded { rpcService<IAiAssistantService>().getAssistantState() }?.let {
                    state = it
                    optIn = it.optIn
                    renderScope()
                }
            }
            renderLog()
            refreshInput()
        }
    }
    question.subscribe { refreshInput() }

    refreshInput()
    AppScope.launch {
        val initial = guarded { rpcService<IAiAssistantService>().getAssistantState() } ?: return@launch
        state = initial
        optIn = initial.optIn
        renderConsent()
        renderScope()
        refreshInput()
    }
}
