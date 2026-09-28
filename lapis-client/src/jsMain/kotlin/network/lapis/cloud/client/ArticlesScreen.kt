package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.ArticleSummaryDto
import network.lapis.cloud.shared.rpc.IArticleService

private enum class ArticlesTab { MINE, REVIEW, PUBLISHED }

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- `Routes.ARTICLES`. Reiter "Meine
 * Artikel" für jedes ORGANIZATION_MEMBER, "Freigabe"/"Veröffentlicht" zusätzlich für BOARD/ADMIN
 * (Rollenprüfung innerhalb des Bildschirms, nicht über eine zweite Route -- siehe `Routes.ARTICLES`
 * KDoc). Der Editor ([renderArticleEditor]) ist ein Zustand INNERHALB dieses Bildschirms.
 */
fun renderArticlesScreen(container: SimplePanel) {
    val root = container.dataScreenRoot(spacing = 14)
    root.pageHeader(tr("Artikel"))

    val isBoard = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)

    val tabRow =
        root.hPanel(spacing = 8) {
            addCssClasses("flex-wrap")
            setAttribute("role", "tablist")
        }
    val contentPanel = root.vPanel(spacing = 10)

    val mineButton = tabRow.button(tr("Meine Artikel"), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") }
    val reviewButton =
        if (isBoard) tabRow.button(tr("Freigabe"), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") } else null
    val publishedButton =
        if (isBoard) tabRow.button(tr("Veröffentlicht"), style = ButtonStyle.OUTLINEPRIMARY) { setAttribute("role", "tab") } else null

    fun markActive(active: Button) {
        listOfNotNull(mineButton, reviewButton, publishedButton).forEach {
            it.setAttribute("aria-selected", (it == active).toString())
        }
    }

    fun showTab(tab: ArticlesTab) {
        contentPanel.removeAll()
        when (tab) {
            ArticlesTab.MINE -> {
                markActive(mineButton)
                renderMyArticlesTab(contentPanel)
            }
            ArticlesTab.REVIEW -> {
                reviewButton?.let(::markActive)
                renderReviewTab(contentPanel)
            }
            ArticlesTab.PUBLISHED -> {
                publishedButton?.let(::markActive)
                renderPublishedTab(contentPanel)
            }
        }
    }
    mineButton.onClick { showTab(ArticlesTab.MINE) }
    reviewButton?.onClick { showTab(ArticlesTab.REVIEW) }
    publishedButton?.onClick { showTab(ArticlesTab.PUBLISHED) }
    showTab(ArticlesTab.MINE)
}

// -- "Meine Artikel" --

private fun renderMyArticlesTab(panel: SimplePanel) {
    val listPanel = panel.simplePanel { addCssClass("lapis-card-list") }
    val editorHost = panel.vPanel(spacing = 10) { hide() }

    fun load() {
        listPanel.removeAll()
        listPanel.show()
        editorHost.hide()
        editorHost.removeAll()
        AppScope.launch {
            val articles = guarded { rpcService<IArticleService>().listMyArticles() }
            if (articles == null) {
                listPanel.dataErrorState(onRetry = ::load)
                return@launch
            }
            val newButton = listPanel.button(tr("Neuer Artikel"), style = ButtonStyle.PRIMARY)
            newButton.onClick {
                listPanel.hide()
                editorHost.show()
                renderArticleEditor(editorHost, null) { load() }
            }
            if (articles.isEmpty()) {
                listPanel.p(tr("Noch keine Artikel.")) { addCssClasses("text-muted") }
                return@launch
            }
            articles.forEach { article -> renderMyArticleCard(listPanel, article, editorHost, ::load) }
        }
    }
    load()
}

private fun renderMyArticleCard(
    panel: SimplePanel,
    article: ArticleDto,
    editorHost: SimplePanel,
    onChanged: () -> Unit,
) {
    val displayStatus = ArticleLabels.displayStatus(article)
    val card = panel.vPanel(spacing = 6) { addCssClass("lapis-data-card") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(ArticleLabels.label(displayStatus), ArticleLabels.colorHex(displayStatus))
    headerRow.untrustedCardTitle(article.title)
    card.untrustedDiv(article.excerpt) { addCssClasses("text-muted small") }
    if (article.status == ArticleStatus.REJECTED && article.rejectionReason != null) {
        card.untrustedDiv(gettext("Begründung: %1", article.rejectionReason), className = "text-muted small")
    }

    val buttonRow = card.hPanel(spacing = 8)
    when (article.status) {
        ArticleStatus.DRAFT, ArticleStatus.REJECTED -> {
            val editButton = buttonRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
            editButton.onClick {
                panel.hide()
                editorHost.show()
                renderArticleEditor(editorHost, article) { onChanged() }
            }
            val submitButton = buttonRow.button(tr("Zur Freigabe einreichen"), style = ButtonStyle.PRIMARY)
            submitButton.onClick {
                AppScope.launch {
                    val result = guarded { rpcService<IArticleService>().submitArticle(article.id) }
                    if (result != null) {
                        notifySuccess(tr("Artikel zur Freigabe eingereicht."))
                        onChanged()
                    }
                }
            }
            if (article.status == ArticleStatus.DRAFT) {
                val deleteButton = buttonRow.button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
                deleteButton.onClick {
                    confirmDialog(
                        title = tr("Entwurf löschen"),
                        message = tr("Möchten Sie diesen Entwurf wirklich löschen?"),
                        confirmLabel = tr("Löschen"),
                    ) {
                        AppScope.launch {
                            val result = guarded { rpcService<IArticleService>().deleteDraft(article.id) }
                            if (result != null) {
                                notifySuccess(tr("Entwurf gelöscht."))
                                onChanged()
                            }
                        }
                    }
                }
            }
        }
        ArticleStatus.SUBMITTED -> {
            val withdrawButton = buttonRow.button(tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)
            withdrawButton.onClick {
                AppScope.launch {
                    val result = guarded { rpcService<IArticleService>().withdrawArticle(article.id) }
                    if (result != null) {
                        notifySuccess(tr("Artikel zurückgezogen."))
                        onChanged()
                    }
                }
            }
        }
        ArticleStatus.PUBLISHED -> {
            if (article.slug != null) {
                buttonRow.untrustedLink(tr("Auf der Webseite ansehen"), url = "/aktuelles/${article.slug}", target = "_blank")
            }
        }
    }
}

// -- "Freigabe" (BOARD/ADMIN) --

private fun renderReviewTab(panel: SimplePanel) {
    val listPanel = panel.simplePanel { addCssClass("lapis-card-list") }

    fun load() {
        listPanel.removeAll()
        AppScope.launch {
            val queue = guarded { rpcService<IArticleService>().listSubmittedArticles() }
            if (queue == null) {
                listPanel.dataErrorState(onRetry = ::load)
                return@launch
            }
            if (queue.isEmpty()) {
                listPanel.p(tr("Keine Artikel zur Freigabe.")) { addCssClasses("text-muted") }
                return@launch
            }
            queue.forEach { summary -> renderReviewSummaryCard(listPanel, summary, ::load) }
        }
    }
    load()
}

private fun renderReviewSummaryCard(
    panel: SimplePanel,
    summary: ArticleSummaryDto,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClass("lapis-data-card") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.untrustedCardTitle(summary.title)
    card.untrustedDiv(summary.excerpt) { addCssClasses("text-muted small") }

    if (summary.authorIsSelf) {
        card.div(tr("Eigener Artikel — Freigabe durch ein anderes Vorstandsmitglied.")) {
            addCssClasses("text-muted small fst-italic")
        }
        return
    }

    val detailPanel = card.vPanel(spacing = 8) { hide() }
    val openButton = card.button(tr("Ansehen"), style = ButtonStyle.OUTLINEPRIMARY)
    var loaded = false
    openButton.onClick {
        if (detailPanel.visible) {
            detailPanel.hide()
            return@onClick
        }
        detailPanel.show()
        if (loaded) return@onClick
        loaded = true
        AppScope.launch {
            val review = guarded { rpcService<IArticleService>().getArticleForReview(summary.id) }
            if (review == null) return@launch
            articlePreviewFrame(detailPanel, review.title, review.excerpt, review.coverImageUrl, review.renderedBodyHtml)
            val actionRow = detailPanel.hPanel(spacing = 8)
            val approveButton = actionRow.button(tr("Genehmigen"), style = ButtonStyle.SUCCESS)
            val rejectButton = actionRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)
            approveButton.onClick {
                confirmDialog(
                    title = tr("Artikel genehmigen"),
                    message = tr("Möchten Sie diesen Artikel wirklich veröffentlichen?"),
                    confirmLabel = tr("Genehmigen"),
                ) {
                    AppScope.launch {
                        val result = guarded { rpcService<IArticleService>().approveArticle(summary.id) }
                        if (result != null) {
                            notifySuccess(tr("Artikel veröffentlicht."))
                            onChanged()
                        }
                    }
                }
            }
            rejectButton.onClick {
                confirmWithReasonDialog(
                    title = tr("Artikel ablehnen"),
                    message = tr("Möchten Sie diesen Artikel ablehnen?"),
                    reasonLabel = tr("Begründung (optional)"),
                    reasonRequired = false,
                    confirmLabel = tr("Ablehnen"),
                    reasonMaxLength = 1000,
                    reasonPlaceholder = tr("z. B. Thema ist bereits abgedeckt"),
                ) { reason ->
                    AppScope.launch {
                        val result = guarded { rpcService<IArticleService>().rejectArticle(summary.id, reason) }
                        if (result != null) {
                            notifySuccess(tr("Artikel abgelehnt."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

// -- "Veröffentlicht" (BOARD/ADMIN) --

private fun renderPublishedTab(panel: SimplePanel) {
    val listPanel = panel.simplePanel { addCssClass("lapis-card-list") }

    fun load() {
        listPanel.removeAll()
        AppScope.launch {
            val published = guarded { rpcService<IArticleService>().listPublishedArticles() }
            if (published == null) {
                listPanel.dataErrorState(onRetry = ::load)
                return@launch
            }
            if (published.isEmpty()) {
                listPanel.p(tr("Keine veröffentlichten Artikel.")) { addCssClasses("text-muted") }
                return@launch
            }
            published.forEach { summary -> renderPublishedSummaryCard(listPanel, summary, ::load) }
        }
    }
    load()
}

private fun renderPublishedSummaryCard(
    panel: SimplePanel,
    summary: ArticleSummaryDto,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClass("lapis-data-card") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.untrustedCardTitle(summary.title)
    if (summary.slug != null) {
        headerRow.untrustedLink(tr("Ansehen"), url = "/aktuelles/${summary.slug}", target = "_blank", className = "small")
    }
    card.untrustedDiv(summary.excerpt) { addCssClasses("text-muted small") }

    val unpublishButton = card.button(tr("Depublizieren"), style = ButtonStyle.OUTLINEDANGER)
    if (summary.authorIsSelf) {
        unpublishButton.disabled = true
        card.div(tr("Eigener Artikel — Depublizieren durch ein anderes Vorstandsmitglied.")) {
            addCssClasses("text-muted small fst-italic")
        }
        return
    }
    unpublishButton.onClick {
        confirmWithReasonDialog(
            title = tr("Artikel depublizieren"),
            message = tr("Möchten Sie diesen Artikel wirklich von der Webseite nehmen?"),
            reasonLabel = tr("Begründung (Pflicht, 10–1000 Zeichen)"),
            reasonRequired = true,
            reasonMinLength = 10,
            reasonMaxLength = 1000,
            showCounter = true,
            confirmLabel = tr("Depublizieren"),
        ) { reason ->
            val safeReason = reason ?: return@confirmWithReasonDialog
            AppScope.launch {
                val result = guarded { rpcService<IArticleService>().unpublishArticle(summary.id, safeReason) }
                if (result != null) {
                    notifySuccess(tr("Artikel depubliziert."))
                    onChanged()
                }
            }
        }
    }
}
