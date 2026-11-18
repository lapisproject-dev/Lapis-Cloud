package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.Select
import io.kvision.form.text.Text
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.PartgReviewerSessionInfoDto
import network.lapis.cloud.shared.domain.PartgReviewerVersionDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import org.w3c.fetch.RequestInit

/** One answer of the auditor's routes: the status and the text (JSON or empty). `null` = no connection. */
internal class ReviewerResponse(
    val status: Int,
    val text: String,
)

/**
 * V1.9.310 (R6b) -- the four calls of the auditor's page, over `fetch` with the cookie of the same origin (`lapis_pruefung`, HttpOnly: this code never
 * sees it). The token goes in a POST BODY, never in a URL.
 */
internal object ReviewerHttp {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(
        method: String,
        url: String,
        body: String? = null,
    ): ReviewerResponse? =
        try {
            val init: dynamic = js("({})")
            init.method = method
            init.credentials = "same-origin"
            init.cache = "no-store"
            init.referrerPolicy = "no-referrer"
            if (body != null) {
                init.headers = js("({'Content-Type': 'application/json'})")
                init.body = body
            }
            val response = window.fetch(url, init.unsafeCast<RequestInit>()).await()
            ReviewerResponse(status = response.status.toInt(), text = response.text().await())
        } catch (e: Throwable) {
            null
        }

    suspend fun requestCode(token: String): ReviewerResponse? = send("POST", "/api/pruefung/code", """{"token":"$token"}""")

    suspend fun login(
        token: String,
        code: String,
    ): ReviewerResponse? = send("POST", "/api/pruefung/session", """{"token":"$token","code":"$code"}""")

    suspend fun session(): PartgReviewerSessionInfoDto? {
        val r = send("GET", "/api/pruefung/session") ?: return null
        return if (r.status ==
            200
        ) {
            runCatching { json.decodeFromString(PartgReviewerSessionInfoDto.serializer(), r.text) }.getOrNull()
        } else {
            null
        }
    }

    suspend fun end(): ReviewerResponse? = send("POST", "/api/pruefung/session/end")

    suspend fun versions(): Pair<Int, List<PartgReviewerVersionDto>?> {
        val r = send("GET", "/api/pruefung/versions") ?: return 0 to null
        val list =
            if (r.status == 200) {
                runCatching { json.decodeFromString(ListSerializer(PartgReviewerVersionDto.serializer()), r.text) }.getOrNull()
            } else {
                null
            }
        return r.status to list
    }

    suspend fun compare(
        a: String,
        b: String,
    ): Pair<Int, PartgVersionDiffDto?> {
        val r = send("GET", "/api/pruefung/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}") ?: return 0 to null
        val diff =
            if (r.status == 200) runCatching { json.decodeFromString(PartgVersionDiffDto.serializer(), r.text) }.getOrNull() else null
        return r.status to diff
    }

    fun fileUrl(
        id: String,
        what: String,
    ): String = "/api/pruefung/versions/${encodeURIComponent(id)}/$what"

    fun jsonUrl(id: String): String = fileUrl(id, "document") + "?download=1"
}

private external fun encodeURIComponent(value: String): String

/**
 * V1.9.310 (R6b) -- the page of the auditor, `/pruefung`. NOT the members' app: no navigation, no session of the members, no RPC. It starts from
 * `App.start()` when the path is `/pruefung`. The token of the link (`#t=...`) is read ONCE, removed from the address bar at once and kept in memory
 * only (never in `localStorage`, never in a URL). States: loading, link + request a code, enter the code, signed in (the versions), no link.
 *
 * The error texts are neutral: a wrong code, an expired code and a revoked link read the same. The header is plain text (no live region); the one
 * tab "Versionen" holds a card per version (checksum shortened, downloads, comparison) that is a stack on a phone.
 */
internal class ReviewerPage(
    private val host: Container,
) {
    private var token: String? = null
    private val header: SimplePanel = host.simplePanel { addCssClasses("lapis-reviewer-header") }
    private val main: SimplePanel = host.simplePanel { addCssClasses("lapis-reviewer-main") }

    fun start() {
        token = reviewerTokenFromHash(window.location.hash)
        // the fragment goes at once: no history entry, no screenshot, no copy of the address bar carries the token
        runCatching { window.history.replaceState(null, "", "/pruefung") }
        renderLoading()
        AppScope.launch {
            val info = ReviewerHttp.session()
            if (info != null && token == null) {
                renderSignedIn(info)
            } else if (info != null) {
                // a new link while a session of another invitation is alive: end the old session, the new link starts its own code flow
                ReviewerHttp.end()
                renderRequestCode()
            } else if (token != null) {
                renderRequestCode()
            } else {
                renderNoLink(tr("Bitte öffnen Sie den Link aus Ihrer Einladung."))
            }
        }
    }

    private fun resetHeader(info: PartgReviewerSessionInfoDto?) {
        header.removeAll()
        header.div(className = "d-flex flex-wrap align-items-center justify-content-between gap-2 border-bottom pb-2 mb-3") {
            if (info == null) {
                span(tr("Lesezugang für die Prüfung"), className = "fw-semibold")
            } else {
                span(
                    gettext(
                        "Lesezugang für die Prüfung · Rechenschaftsbericht %1 · gültig bis %2",
                        info.year.toString(),
                        formatSystemDateTime(info.invitationExpiresAt),
                    ),
                    className = "fw-semibold",
                ) { setAttribute("data-reviewer-header", "1") }
                actionButton(ActionIcon.LEAVE, tr("Abmelden"), ButtonStyle.OUTLINESECONDARY, small = true) {
                    id = "reviewer-logout"
                    onClick {
                        AppScope.launch {
                            ReviewerHttp.end()
                            renderNoLink(tr("Sie wurden abgemeldet. Zum erneuten Anmelden öffnen Sie bitte den Link aus Ihrer Einladung."))
                        }
                    }
                }
            }
        }
    }

    private fun renderLoading() {
        resetHeader(null)
        main.removeAll()
        main.div(tr("Wird geladen …"), className = "text-muted")
    }

    private fun renderNoLink(message: String) {
        resetHeader(null)
        main.removeAll()
        main.div(message) {
            addCssClasses("alert alert-secondary")
            setAttribute("data-reviewer-nolink", "1")
        }
    }

    private fun renderRequestCode() {
        resetHeader(null)
        main.removeAll()
        main.div(
            tr(
                "Sie wurden eingeladen, Versionen des Rechenschaftsberichts zu lesen. " +
                    "Fordern Sie einen Code an: Er wird an die E-Mail-Adresse gesendet, an die die Einladung gerichtet ist.",
            ),
            className = "mb-3",
        )
        val notice = main.div { addCssClasses("small mt-2") }
        val send = newActionButton(ActionIcon.SEND, tr("Code per E-Mail senden"), ButtonStyle.PRIMARY)
        send.id = "reviewer-send-code"
        main.add(send)
        val codeBox = main.simplePanel()
        send.onClick {
            val t = token ?: return@onClick
            send.disabled = true
            AppScope.launch {
                val answer = ReviewerHttp.requestCode(t)
                notice.content =
                    if (answer?.status == 429) {
                        tr("Zu viele Anfragen. Bitte versuchen Sie es später erneut.")
                    } else {
                        tr("Falls die Einladung gültig ist, wurde ein Code per E-Mail gesendet.")
                    }
                send.disabled = false
                if (codeBox.getChildren().isEmpty()) renderCodeEntry(codeBox)
            }
        }
    }

    private fun renderCodeEntry(box: SimplePanel) {
        val code = Text(label = tr("Code aus der E-Mail"))
        code.id = "reviewer-code"
        // on the real <input>, not on the wrapper of the KVision control
        code.input.setAttribute("inputmode", "numeric")
        code.input.setAttribute("autocomplete", "one-time-code")
        code.maxlength = CODE_INPUT_MAX_CHARS
        box.add(code)
        val error = box.div { addCssClasses("alert alert-danger small mt-2") }
        error.visible = false
        error.setAttribute("role", "alert")
        val login = newActionButton(ActionIcon.ENTER, tr("Anmelden"), ButtonStyle.PRIMARY)
        login.id = "reviewer-login"
        box.add(login)
        login.onClick {
            val t = token ?: return@onClick
            val input = reviewerCodeInput(code.value.orEmpty())
            if (input == null) {
                error.content = tr("Code ungültig oder abgelaufen.")
                error.visible = true
                return@onClick
            }
            login.disabled = true
            AppScope.launch {
                val answer = ReviewerHttp.login(t, input)
                if (answer != null && answer.status in HTTP_OK_RANGE) {
                    token = null
                    code.value = null
                    val info = ReviewerHttp.session()
                    if (info != null) {
                        renderSignedIn(info)
                        return@launch
                    }
                }
                error.content =
                    if (answer?.status == 429) {
                        tr("Zu viele Versuche. Bitte warten Sie einen Moment.")
                    } else {
                        tr("Code ungültig oder abgelaufen.")
                    }
                error.visible = true
                login.disabled = false
            }
        }
    }

    private fun ended() = renderNoLink(tr("Sitzung beendet. Bitte öffnen Sie den Link erneut."))

    private fun renderSignedIn(info: PartgReviewerSessionInfoDto) {
        resetHeader(info)
        main.removeAll()
        val tabs = main.div(className = "nav nav-tabs mb-3") { setAttribute("role", "tablist") }
        tabs.add(
            Tag(TAG.BUTTON, content = tr("Versionen"), className = "nav-link active").apply {
                setAttribute("role", "tab")
                setAttribute("aria-selected", "true")
                setAttribute("type", "button")
                id = "reviewer-tab-versions"
            },
        )
        val panel = main.div { setAttribute("role", "tabpanel") }
        panel.setAttribute("aria-labelledby", "reviewer-tab-versions")
        AppScope.launch {
            val (status, list) = ReviewerHttp.versions()
            if (status == 401) return@launch ended()
            if (list == null) {
                panel.div(tr("Die Versionen konnten nicht geladen werden. Bitte laden Sie die Seite neu.")) {
                    addCssClasses("alert alert-danger")
                    setAttribute("role", "alert")
                }
                return@launch
            }
            panel.renderVersions(list)
        }
    }

    private fun SimplePanel.renderVersions(list: List<PartgReviewerVersionDto>) {
        if (list.isEmpty()) {
            div(tr("Zu dieser Einladung gehört keine Version mehr."), className = "text-muted")
            return
        }
        list.forEach { version ->
            div(className = "card mb-2 lapis-reviewer-card") {
                setAttribute("data-reviewer-version", version.id)
                div(className = "card-body") {
                    div(className = "d-flex flex-wrap align-items-center gap-2") {
                        span(gettext("Version %1", version.versionNumber.toString()), className = "fw-semibold")
                        if (version.finalized) span(tr("Intern final"), className = "badge rounded-pill text-bg-secondary")
                    }
                    div(gettext("Festgehalten am %1", formatSystemDateTime(version.frozenAt)), className = "small text-muted")
                    div(gettext("Prüfsumme: %1 …", version.contentHash.take(HASH_PREVIEW_CHARS)), className = "small text-muted")
                    val details = Tag(TAG.DETAILS, className = "small")
                    details.add(Tag(TAG.SUMMARY, content = tr("Prüfsumme vollständig anzeigen")))
                    details.add(Tag(TAG.DIV, content = version.contentHash, className = "text-break"))
                    add(details)
                    div(className = "d-flex flex-wrap gap-2 mt-2") {
                        downloadLink(label = tr("PDF"), url = ReviewerHttp.fileUrl(version.id, "pdf"))
                        downloadLink(label = tr("Excel"), url = ReviewerHttp.fileUrl(version.id, "xlsx"))
                        downloadLink(label = tr("JSON"), url = ReviewerHttp.jsonUrl(version.id))
                    }
                }
            }
        }
        renderCompare(list)
    }

    private fun SimplePanel.renderCompare(list: List<PartgReviewerVersionDto>) {
        div(tr("Versionen vergleichen"), className = "fw-bold mt-3") { setAttribute("data-reviewer-compare", "1") }
        if (list.size < 2) {
            div(tr("Zum Vergleichen werden mindestens zwei Versionen gebraucht."), className = "small text-muted")
            return
        }
        val options = list.map { it.id to gettext("Version %1", it.versionNumber.toString()) }
        val older = Select(options = options, value = list.first().id, label = tr("Version A (älter)"))
        val newer = Select(options = options, value = list.last().id, label = tr("Version B (neuer)"))
        older.id = "reviewer-compare-a"
        newer.id = "reviewer-compare-b"
        add(older)
        add(newer)
        val result = simplePanel()
        val live = div { addCssClasses("small text-muted") }
        val run = newActionButton(ActionIcon.SEARCH, tr("Vergleichen"), ButtonStyle.OUTLINEPRIMARY)
        run.id = "reviewer-compare-run"
        add(run)
        run.onClick {
            val a = older.value ?: return@onClick
            val b = newer.value ?: return@onClick
            AppScope.launch {
                val (status, diff) = ReviewerHttp.compare(a, b)
                if (status == 401) return@launch ended()
                result.removeAll()
                if (diff == null) {
                    live.content = tr("Der Vergleich konnte nicht geladen werden.")
                } else {
                    live.content = if (partgNoDifferences(diff)) tr("Keine Unterschiede in den Zahlen.") else ""
                    result.renderDiff(diff)
                }
            }
        }
    }

    private fun Container.downloadLink(
        label: String,
        url: String,
    ) {
        val link = Tag(TAG.A, content = label, className = "btn btn-outline-primary btn-sm lapis-reviewer-download")
        link.setAttribute("href", url)
        link.setAttribute("download", "")
        link.setAttribute("rel", "noopener")
        add(link)
    }

    private companion object {
        const val HASH_PREVIEW_CHARS = 12
        const val CODE_INPUT_MAX_CHARS = 7
        val HTTP_OK_RANGE = 200..299
    }
}

/** Entry from `App.start()` for the path `/pruefung`. */
internal fun Container.renderReviewerApp() {
    val shell = simplePanel { addCssClasses("container py-3 lapis-reviewer") }
    ReviewerPage(shell).start()
}
