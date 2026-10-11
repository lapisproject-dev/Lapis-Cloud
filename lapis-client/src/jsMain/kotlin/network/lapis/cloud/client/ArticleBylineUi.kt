package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.check.radioGroup
import io.kvision.form.text.Text
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.BoardBylineInput
import network.lapis.cloud.shared.domain.BoardBylineMode
import network.lapis.cloud.shared.rpc.IArticleService

/** Mirrors `ArticleBylinePolicy.BYLINE_MAX` -- a convenience limit in the input, the server stays authoritative. */
internal const val ARTICLE_BYLINE_MAX = 120

/**
 * Welle V1.9.105 -- "Wird als „X“ genannt." / "Wird nicht genannt." The byline is member- or import-controlled text, so every caller puts the
 * result through an `untrusted*` widget (never a bare `content =`).
 */
internal fun articleBylineSummary(byline: String?): String =
    if (byline.isNullOrBlank()) tr("Wird nicht genannt.") else gettext("Wird als „%1“ genannt.", byline)

/**
 * Welle V1.9.105 -- the author's consent card of the article editor ("Autorenzeile"). Native checkbox "Mich als Autor nennen" (default off,
 * the consent text hangs on it through `aria-describedby`); only when ticked a text field "So möchte ich genannt werden" appears.
 *
 * **What is saved when** (each save is a consent record and an audit entry, so never per keystroke): unticking saves at once (withdrawal);
 * the text is saved on `change` (blur/Enter), not on every key. Ticking with an empty field stores nothing -- the server demands a text -- and
 * the summary line says so honestly ("Wird nicht genannt.") until a text is saved. A live region announces "Einwilligung gespeichert."/
 * "Einwilligung widerrufen.".
 *
 * Needs an article id (the server stores the consent against a row): [renderArticleEditor] shows a hint instead until the first auto-save.
 */
internal fun renderArticleBylineCard(
    panel: SimplePanel,
    articleId: String,
    initial: ArticleDto?,
) {
    val card =
        panel.vPanel(spacing = 4) {
            addCssClasses("border rounded p-2 mb-2 lapis-article-byline")
            setAttribute("role", "group")
            setAttribute("aria-label", gettext("Autorenzeile"))
        }
    card.div(tr("Autorenzeile")) { addCssClasses("fw-bold") }

    // The text currently stored as PERSONAL (null = no consent stored). A line the board set (organization/imported) is not "ours" to edit here.
    var savedPersonal: String? = initial?.takeIf { it.bylineKind == ArticleBylineKind.PERSONAL }?.byline
    var shownByline: String? = initial?.byline

    val form = card.lapisForm()
    val consentField =
        form.checkField(
            label = tr("Mich als Autor nennen"),
            value = savedPersonal != null,
            hint =
                tr(
                    "Wenn Sie zustimmen, steht Ihr Name mit dem Artikel auf der Webseite. Ohne Ihre Zustimmung wird kein Autor genannt. " +
                        "Sie können die Zustimmung jederzeit widerrufen; Ihr Name verschwindet dann sofort aus Lapis. " +
                        "Eine statische Webseite zeigt die Änderung erst nach ihrer nächsten Aktualisierung.",
                ),
        )
    val nameBox = form.panel.vPanel(spacing = 4)
    val nameField =
        form.textField(
            label = tr("So möchte ich genannt werden"),
            value = savedPersonal,
            host = nameBox,
            init = {
                it.maxlength = ARTICLE_BYLINE_MAX
                it.placeholder = tr("z. B. Max Muster")
            },
        )
    form.finish()

    val summary: Div = card.div("") { addCssClasses("small") }
    val live: Div =
        card.div("") {
            addCssClasses("text-muted small")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }

    fun renderSummary() {
        untrustedContent(summary, articleBylineSummary(shownByline))
    }

    fun syncNameBox(consentOn: Boolean) {
        if (consentOn) nameBox.show() else nameBox.hide()
    }
    syncNameBox(savedPersonal != null)
    renderSummary()

    fun withdraw() {
        AppScope.launch {
            val result = guarded { rpcService<IArticleService>().setOwnByline(articleId, false, null) }
            if (result != null) {
                savedPersonal = result.takeIf { it.bylineKind == ArticleBylineKind.PERSONAL }?.byline
                shownByline = result.byline
                renderSummary()
                live.content = tr("Einwilligung widerrufen.")
                if (consentField.value != "true") nameField.setValue("")
            }
        }
    }

    fun applyResult(
        result: ArticleDto,
        message: String,
    ) {
        savedPersonal = result.takeIf { it.bylineKind == ArticleBylineKind.PERSONAL }?.byline
        shownByline = result.byline
        renderSummary()
        live.content = message
        // Race guard: a save that was already in flight when the author un-ticked the box answers PERSONAL although the box now says "no consent".
        // The stored state must never contradict what the author sees, so withdraw again at once.
        if (savedPersonal != null && consentField.value != "true") withdraw()
    }

    consentField.subscribe { raw ->
        val on = raw == "true"
        syncNameBox(on)
        if (!on && savedPersonal != null) {
            // Withdrawal is saved at once, without a second click.
            withdraw()
        }
    }

    (nameField.control as Text).onEvent {
        change = {
            val consentOn = consentField.value == "true"
            val text = nameField.value.trim()
            if (consentOn && text.isNotEmpty() && text != savedPersonal) {
                AppScope.launch {
                    val result = guarded { rpcService<IArticleService>().setOwnByline(articleId, true, text) }
                    if (result != null) applyResult(result, tr("Einwilligung gespeichert."))
                }
            }
        }
    }
}

/**
 * Welle V1.9.105 -- the author's "Namensnennung widerrufen" on an article that can no longer be edited (`SUBMITTED`/`PUBLISHED`). Asks first,
 * then withdraws at once and tells the TWO honest facts: it is gone from Lapis immediately, a static website follows its next update.
 */
internal fun confirmRevokeOwnByline(
    articleId: String,
    onRevoked: () -> Unit,
) {
    confirmDialog(
        title = tr("Namensnennung widerrufen"),
        message = tr("Möchten Sie Ihre Zustimmung zur Namensnennung bei diesem Artikel widerrufen?"),
        confirmLabel = tr("Widerrufen"),
        confirmIcon = ActionIcon.REVOKE,
    ) {
        AppScope.launch {
            val result = guarded { rpcService<IArticleService>().revokeOwnByline(articleId) }
            if (result != null) {
                notifySuccess(
                    tr(
                        "Ihr Name wird in Lapis nicht mehr angezeigt. " +
                            "Eine statische Webseite zeigt die Änderung erst nach ihrer nächsten Aktualisierung.",
                    ),
                )
                onRevoked()
            }
        }
    }
}

private const val KEEP_VALUE = "KEEP"
private const val NONE_VALUE = "NONE"
private const val ORGANIZATION_VALUE = "ORGANIZATION"

/**
 * Welle V1.9.105 -- the board's "Autorenzeile ändern" dialog: keep (only offered if a line exists, the default), do not name anybody, or name
 * an organization/editorial team. A personal name cannot be entered here by design -- it needs the author's own consent.
 */
internal fun articleBylineBoardDialog(
    currentByline: String?,
    currentKind: ArticleBylineKind?,
    onSave: (BoardBylineInput) -> Unit,
) {
    val modal = Modal(caption = tr("Autorenzeile ändern"))
    val form = modal.lapisForm()
    val options =
        buildList {
            if (currentByline != null) add(KEEP_VALUE to gettext("Beibehalten: „%1“", currentByline))
            add(NONE_VALUE to tr("Nicht nennen"))
            add(ORGANIZATION_VALUE to tr("Organisation oder Redaktion"))
        }
    val radio =
        form.panel.radioGroup(
            options = untrustedOptions(options),
            value = if (currentByline != null) KEEP_VALUE else NONE_VALUE,
            label = tr("Autorenzeile"),
        )
    form.register(control = radio, label = tr("Autorenzeile"), required = true)
    val orgBox = form.panel.vPanel(spacing = 4)
    val orgField =
        form.textField(
            label = tr("Organisation oder Redaktion"),
            value = if (currentKind == ArticleBylineKind.ORGANIZATION) currentByline else Branding.title,
            hint = tr("Kein Personenname."),
            host = orgBox,
            init = { it.maxlength = ARTICLE_BYLINE_MAX },
        )
    form.finish()

    fun sync() {
        if (radio.value == ORGANIZATION_VALUE) orgBox.show() else orgBox.hide()
    }
    radio.subscribe { sync() }
    sync()

    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    saveButton.onClick {
        when (radio.value) {
            ORGANIZATION_VALUE -> {
                val text = orgField.value.trim()
                if (text.isEmpty()) {
                    orgField.showError(tr("Bitte geben Sie eine Organisation oder Redaktion an."))
                    orgField.focus()
                    return@onClick
                }
                modal.hide()
                onSave(BoardBylineInput(BoardBylineMode.ORGANIZATION, text))
            }
            NONE_VALUE -> {
                modal.hide()
                onSave(BoardBylineInput(BoardBylineMode.NONE))
            }
            else -> modal.hide()
        }
    }
    modal.addButton(cancelButton)
    modal.addButton(saveButton)
    modal.show()
}

/** One muted line "Wird als „X“ genannt." for a list card; the text is untrusted, so it goes through [untrustedDiv]. */
internal fun Container.articleBylineLine(byline: String?): Div = untrustedDiv(articleBylineSummary(byline), className = "text-muted small")
