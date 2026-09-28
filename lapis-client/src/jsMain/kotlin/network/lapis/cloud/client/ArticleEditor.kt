package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.upload.upload
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.image
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.rpc.IArticleService

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- inserts server-rendered article HTML
 * (`network.lapis.cloud.server.articles.ArticleMarkdown.render`'s output, delivered as
 * [ArticleDto.body]'s live preview via `previewArticle` or as `ArticleReviewDto
 * .renderedBodyHtml`) -- the ONE `rich = true` call site this file uses, and ONLY for that HTML.
 * NEVER for a title/excerpt/author-controlled field, which always go through the plain,
 * auto-escaping widget-content API (`untrusted*`) instead -- see this codebase's own
 * `UntrustedText.kt` KDoc for the "never assign a DTO field to `.content` directly" discipline
 * this deliberately does NOT bypass for anything except this one, already-sanitized HTML string.
 */
internal fun articlePreviewFrame(
    container: Container,
    title: String,
    excerpt: String,
    coverImageUrl: String?,
    renderedBodyHtml: String,
): Div =
    container.div {
        addCssClass("lapis-article-preview-frame")
        if (coverImageUrl != null) image(coverImageUrl, title, className = "lapis-article-cover")
        // h1 is reserved for PageHeader.kt's own page header (R6, see UntrustedText.kt
        // `untrustedHeading` KDoc) -- this is a section title within the screen, not the page's h1.
        // R7: every h2 carries an "h5" size class literal on the same line.
        untrustedHeading(title, level = 2, className = "h5")
        untrustedP(excerpt, className = "lapis-article-lead")
        div(content = renderedBodyHtml, rich = true) { addCssClass("lapis-article-body") }
    }

/**
 * Renders the article editor (create-or-edit) for the author's own article into [container].
 * [initial] `null` starts a brand-new draft; otherwise the given [ArticleDto] (any status the
 * author may still edit: `DRAFT`/`REJECTED`) is loaded. [onBack] returns to the list.
 *
 * **Auto-save**: [ArticleAutoSaveController] (2000ms debounce, at-most-one-save-in-flight) drives
 * every `saveDraft` call -- this function is the ONLY caller (Stolperfalle §6 "Doppelte Artikel").
 * The cover-image section only appears once an `articleId` exists (the upload route needs one) --
 * before the first successful auto-save it shows a hint instead.
 */
fun renderArticleEditor(
    container: SimplePanel,
    initial: ArticleDto?,
    onBack: () -> Unit,
) {
    val readOnly = initial != null && initial.status != ArticleStatus.DRAFT && initial.status != ArticleStatus.REJECTED

    val root = container.vPanel(spacing = 10)
    val headerRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center justify-content-between") }
    val headerTitle =
        when {
            readOnly -> tr("Artikel ansehen")
            initial == null -> tr("Neuer Artikel")
            else -> tr("Artikel bearbeiten")
        }
    headerRow.h2(headerTitle, className = "h5") // R7: h5 size class literal on the same line as h2(
    val statusText = headerRow.div("") { addCssClasses("text-muted small") }
    val retryButton =
        headerRow.button(tr("Erneut versuchen"), style = ButtonStyle.OUTLINEDANGER) { hide() }

    // Forward-declared so the "Zurück"-button below (and `retryButton`) can close over it even
    // though the controller itself is only constructed further down, once the form fields exist.
    // Never touched in the `readOnly` branch below (that branch has no autosave controller at all
    // and returns before this is assigned), so the back button there must NOT reference it.
    lateinit var controller: ArticleAutoSaveController

    val backButton = root.button(tr("← Zurück zur Liste"), style = ButtonStyle.LINK)
    backButton.onClick {
        if (readOnly) {
            onBack()
            return@onClick
        }
        // Stolperfalle "orphaned debounce timer": flush AND WAIT for any pending edit before
        // leaving, otherwise the list can reload stale content and a later action there (e.g.
        // "Zur Freigabe einreichen") acts on an outdated version while the orphaned timer's own
        // save fires afterwards against an already-transitioned article -- see
        // ArticleAutoSaveController.flushNowAndAwait's KDoc.
        backButton.disabled = true
        AppScope.launch {
            controller.flushNowAndAwait()
            // Stolperfalle "stille Datenverlust beim Verlassen": flushNowAndAwait() completes on
            // BOTH the success and the failure path -- a transient network/5xx error must not
            // silently navigate away with the last edits unsaved (the list would show stale
            // content, and the "Speichern fehlgeschlagen."/retry UI leaves with the editor). Ask
            // for confirmation instead of leaving straight away.
            if (controller.saveState is ArticleAutoSaveController.SaveState.Failed) {
                backButton.disabled = false
                confirmDialog(
                    title = tr("Änderungen verwerfen?"),
                    message =
                        tr(
                            "Die letzten Änderungen konnten nicht gespeichert werden. " +
                                "Wenn Sie jetzt zur Liste zurückkehren, gehen sie verloren.",
                        ),
                    confirmLabel = tr("Trotzdem verlassen"),
                    onConfirm = onBack,
                )
            } else {
                onBack()
            }
        }
    }
    if (readOnly) {
        // SUBMITTED/PUBLISHED: read-only view, no editor fields -- see class KDoc.
        val readOnlyBody = root.vPanel(spacing = 10)

        fun loadReadOnly() {
            readOnlyBody.removeAll()
            AppScope.launch {
                val html = guarded { rpcService<IArticleService>().previewArticle(initial!!.body) }
                if (html == null) {
                    readOnlyBody.dataErrorState(onRetry = ::loadReadOnly)
                    return@launch
                }
                articlePreviewFrame(readOnlyBody, initial.title, initial.excerpt, initial.coverImageUrl, html)
            }
        }
        loadReadOnly()
        return
    }

    // -- Tabs "Schreiben"/"Vorschau" --
    val tabRow = root.hPanel(spacing = 8) { setAttribute("role", "tablist") }
    val writeTabButton = tabRow.button(tr("Schreiben"), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") }
    val previewTabButton = tabRow.button(tr("Vorschau"), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") }

    val writePanel = root.vPanel(spacing = 10)
    val previewPanel = root.vPanel(spacing = 10) { hide() }

    val form = writePanel.lapisForm()
    val titleCounter = writePanel.div("") { addCssClasses("text-muted small") }
    val titleField =
        form.textField(
            label = tr("Titel"),
            value = initial?.title,
            required = true,
            init = { it.maxlength = 140 },
        )
    val excerptCounter = writePanel.div("") { addCssClasses("text-muted small") }
    val excerptField =
        form.textField(
            label = tr("Auszug"),
            value = initial?.excerpt,
            required = true,
            init = { it.maxlength = 300 },
        )
    val bodyField =
        form.textAreaField(
            label = tr("Text (Markdown)"),
            rows = 16,
            value = initial?.body,
            required = true,
        )
    form.finish()

    fun updateCounters() {
        titleCounter.content = "${titleField.value.length}/140"
        val excerptLen = excerptField.value.length
        excerptCounter.content = "$excerptLen/300"
        excerptCounter.removeCssClass("text-warning")
        if (excerptLen >= 280) excerptCounter.addCssClass("text-warning")
    }
    updateCounters()
    titleField.subscribe { updateCounters() }
    excerptField.subscribe { updateCounters() }

    var articleId: String? = initial?.id
    // Tracks the cover currently in effect -- kept in sync with whatever `renderArticleCoverCard`
    // uploads/removes, so the "Vorschau"-tab shows the SAME cover the author just changed instead
    // of the stale one the editor loaded with (see `activateTab`'s use of this below).
    var currentCoverUrl: String? = initial?.coverImageUrl
    val coverSection = writePanel.div { }

    fun renderCoverSection() {
        coverSection.removeAll()
        val id = articleId
        if (id == null) {
            coverSection.div(tr("Titelbild: bitte zuerst einmal speichern (geschieht automatisch beim Tippen).")) {
                addCssClasses("text-muted small")
            }
            return
        }
        renderArticleCoverCard(coverSection, id, currentCoverUrl, initial?.title.orEmpty()) { newUrl ->
            currentCoverUrl = newUrl
        }
    }
    renderCoverSection()

    // -- Auto-save --
    val realSchedule =
        ArticleAutoSaveSchedule { delayMs, action ->
            val handle = window.setTimeout({ action() }, delayMs)
            ArticleAutoSaveSchedule.Handle { window.clearTimeout(handle) }
        }
    controller =
        ArticleAutoSaveController(
            scope = AppScope,
            save = { id, input -> rpcServiceSaveDraft(id, input) },
            schedule = realSchedule,
            now = { kotlin.js.Date.now() },
            onStateChange = { state ->
                when (state) {
                    is ArticleAutoSaveController.SaveState.Idle -> {
                        statusText.content = ""
                        retryButton.hide()
                    }
                    is ArticleAutoSaveController.SaveState.Saving -> {
                        statusText.content = tr("Wird gespeichert…")
                        retryButton.hide()
                    }
                    is ArticleAutoSaveController.SaveState.Saved -> {
                        statusText.content = tr("Gespeichert.")
                        retryButton.hide()
                        val newId = controller.articleId
                        if (newId != null && newId != articleId) {
                            articleId = newId
                            renderCoverSection()
                        }
                    }
                    is ArticleAutoSaveController.SaveState.Failed -> {
                        // No automatic retry actually happens -- see ArticleAutoSaveController.retry's
                        // KDoc -- so this must NOT promise one; offer the explicit `retryButton` instead.
                        statusText.content = tr("Speichern fehlgeschlagen.")
                        retryButton.show()
                    }
                }
            },
        )
    controller.setInitialArticleId(initial?.id)
    retryButton.onClick { controller.retry() }

    fun currentInput() = ArticleDraftInput(title = titleField.value.trim(), excerpt = excerptField.value.trim(), body = bodyField.value)
    titleField.subscribe { controller.onChange(currentInput()) }
    excerptField.subscribe { controller.onChange(currentInput()) }
    bodyField.subscribe { controller.onChange(currentInput()) }

    var previewLoadedForCurrentTab = false

    fun activateTab(preview: Boolean) {
        writeTabButton.setAttribute("aria-selected", (!preview).toString())
        previewTabButton.setAttribute("aria-selected", preview.toString())
        if (preview) {
            writePanel.hide()
            previewPanel.show()
            if (!previewLoadedForCurrentTab) {
                previewLoadedForCurrentTab = true
                previewPanel.removeAll()
                previewPanel.div(tr("Vorschau wird geladen…")) { addCssClasses("text-muted small") }
                AppScope.launch {
                    val html = guarded { rpcService<IArticleService>().previewArticle(bodyField.value) }
                    previewPanel.removeAll()
                    if (html == null) {
                        previewLoadedForCurrentTab = false
                        previewPanel.dataErrorState(onRetry = { activateTab(true) })
                        return@launch
                    }
                    articlePreviewFrame(previewPanel, titleField.value, excerptField.value, currentCoverUrl, html)
                }
            }
        } else {
            previewPanel.hide()
            writePanel.show()
        }
    }
    writeTabButton.onClick {
        previewLoadedForCurrentTab = false
        activateTab(false)
    }
    previewTabButton.onClick { activateTab(true) }
    activateTab(false)
}

/** The `saveDraft` call site [ArticleAutoSaveController] alone is allowed to use -- see that class' own KDoc "Doppelte Artikel". */
private suspend fun rpcServiceSaveDraft(
    id: String?,
    input: ArticleDraftInput,
): ArticleDto = rpcService<IArticleService>().saveDraft(id, input)

/**
 * Titelbild-Karte -- 1:1 nach `EventsScreen.renderEventCoverCard`s Vorbild, mit [ArticleCoverHttp]
 * statt [EventCoverHttp]. [onCoverUrlChanged] is called with the new cover URL (`null` on removal)
 * after every successful upload/removal, so callers (the "Vorschau"-tab in [renderArticleEditor])
 * can keep their own copy in sync instead of showing a stale cover -- this card only keeps
 * [currentUrl] locally otherwise.
 */
private fun renderArticleCoverCard(
    panel: SimplePanel,
    articleId: String,
    initialCoverUrl: String?,
    title: String,
    onCoverUrlChanged: (String?) -> Unit = {},
) {
    val card = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2 mb-2") }
    card.div(tr("Titelbild")) { addCssClasses("fw-bold") }

    var currentUrl = initialCoverUrl
    val preview =
        card.div {
            addCssClasses("mb-2 border rounded")
            setStyle("max-width", "320px")
            setStyle("aspect-ratio", "16 / 9")
            setStyle("overflow", "hidden")
        }

    fun renderPreview() {
        preview.removeAll()
        val url = currentUrl
        if (url != null) {
            preview.image(url, title) {
                setStyle("width", "100%")
                setStyle("height", "100%")
                setStyle("object-fit", "cover")
            }
        } else {
            preview.div(tr("Kein Titelbild – JPEG oder PNG, mind. 800x600, max. 5 MB")) { addCssClasses("text-muted small p-2") }
        }
    }
    renderPreview()

    val statusBox =
        card.div().apply {
            addCssClasses("text-muted small")
            hide()
        }
    val fileUpload = card.upload(label = tr("Bild auswählen…"), multiple = false)
    fileUpload.setAttribute("accept", "image/jpeg,image/png")
    val buttonRow = card.hPanel(spacing = 8)
    val uploadButton = buttonRow.button(tr("Hochladen"), style = ButtonStyle.PRIMARY)
    val removeButton = buttonRow.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.visible = currentUrl != null

    uploadButton.onClick {
        val nativeFile = fileUpload.value?.firstOrNull()?.let { fileUpload.getNativeFile(it) }
        if (nativeFile == null) {
            statusBox.content = tr("Bitte zuerst eine Datei auswählen.")
            statusBox.show()
            return@onClick
        }
        uploadButton.disabled = true
        removeButton.disabled = true
        statusBox.content = tr("Wird hochgeladen…")
        statusBox.show()
        AppScope.launch {
            when (val result = ArticleCoverHttp.upload(articleId, nativeFile)) {
                is ArticleCoverHttp.Result.Ok -> {
                    currentUrl = result.coverImageUrl
                    renderPreview()
                    removeButton.visible = currentUrl != null
                    statusBox.hide()
                    onCoverUrlChanged(currentUrl)
                    notifySuccess(tr("Titelbild gespeichert."))
                }
                is ArticleCoverHttp.Result.Error -> {
                    untrustedContent(statusBox, result.message)
                    statusBox.show()
                }
            }
            uploadButton.disabled = false
            removeButton.disabled = false
        }
    }

    removeButton.onClick {
        uploadButton.disabled = true
        removeButton.disabled = true
        AppScope.launch {
            when (val result = ArticleCoverHttp.remove(articleId)) {
                is ArticleCoverHttp.Result.Ok -> {
                    currentUrl = null
                    renderPreview()
                    removeButton.visible = false
                    onCoverUrlChanged(null)
                    notifySuccess(tr("Titelbild entfernt."))
                }
                is ArticleCoverHttp.Result.Error -> {
                    untrustedContent(statusBox, result.message)
                    statusBox.show()
                }
            }
            uploadButton.disabled = false
            removeButton.disabled = false
        }
    }
}
