package network.lapis.cloud.client

import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.li
import io.kvision.html.p
import io.kvision.html.ul
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- ADMIN-only, READ-ONLY screen. Kein
 * Einstellungsscreen: `LAPIS_EMBED_ALLOWED_ORIGINS` wird ausschließlich aus der Umgebungsvariable
 * beim Serverstart gelesen (siehe `network.lapis.cloud.server.embed.EmbedConfig` KDoc "env-only,
 * niemals in der Datenbank persistiert") -- ein kompromittierter ADMIN-Account darf keine fremde
 * Origin autorisieren können. Diese Seite zeigt ausschließlich, was der Server beim Start geladen
 * hat, und speichert nichts.
 *
 * Rollen-Verifikation: `Routing.kt` gattert [Routes.EMBED_INTEGRATION] auf
 * [network.lapis.cloud.shared.domain.AccountRole.ADMIN] -- identisch zu [Routes.BACKUP], dem ersten
 * ADMIN-only-Screen dieses Clients (siehe dessen KDoc).
 */
fun renderEmbedIntegrationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1(tr("Website-Integration"))
    root.div(
        tr(
            "Widgets zum Einbetten auf der eigenen Vereins-/Parteiwebsite (Mitglieder-Login, Mitglied werden).",
        ),
    ) { addCssClasses("text-muted small") }

    val statusPanel = root.vPanel(spacing = 6)
    statusPanel.p(tr("Lädt …"))

    AppScope.launch {
        val status = EmbedIntegrationHttp.fetchStatus()
        statusPanel.removeAll()
        if (status == null) {
            statusPanel.p(tr("Status konnte nicht geladen werden."))
            return@launch
        }
        renderStatusBlock(statusPanel, status)
        if (status.enabled) {
            renderSnippetBlock(root, status.publicBaseUrl)
            renderChecklistBlock(root)
        }
    }
}

private fun renderStatusBlock(
    panel: SimplePanel,
    status: EmbedAdminStatus,
) {
    panel.h2(tr("Zustand"))
    panel.p(if (status.enabled) tr("Aktiviert: Ja") else tr("Aktiviert: Nein"))
    if (status.enabled) {
        panel.p(gettext("%1 freigeschaltete Origin(s):", status.allowedOrigins.size))
        val list = panel.ul()
        status.allowedOrigins.forEach { origin -> list.li(origin) }
    }
    panel.p(
        tr(
            "Diese Liste wird beim Serverstart aus LAPIS_EMBED_ALLOWED_ORIGINS gelesen und lässt sich hier nicht ändern.",
        ),
    ) { addCssClasses("text-muted small") }
    if (status.donationWidgetAvailable) {
        panel.p(tr("Spenden-Widget: verfügbar"))
    } else {
        panel.p(gettext("Spenden-Widget: nicht verfügbar (Grund: %1)", status.donationWidgetUnavailableReason ?: "?"))
    }
}

private fun renderSnippetBlock(
    root: SimplePanel,
    publicBaseUrl: String,
) {
    root.h2(tr("Einbindungs-Code"))
    val snippet = buildEmbedSnippet(publicBaseUrl)
    val snippetField = root.textArea(value = snippet, rows = 9) { addCssClasses("font-monospace small") }
    snippetField.getElement()?.setAttribute("readonly", "readonly")
    val copyLabel = tr("Kopieren")
    val copiedLabel = tr("Kopiert")
    lateinit var copyButton: Button
    copyButton =
        root.button(copyLabel, style = ButtonStyle.SECONDARY) {
            onClick {
                copyToClipboard(snippet) {
                    copyButton.text = copiedLabel
                    AppScope.launch {
                        delay(2000)
                        copyButton.text = copyLabel
                    }
                }
            }
        }
}

private fun renderChecklistBlock(root: SimplePanel) {
    root.h2(tr("Prüf-Checkliste"))
    val list = root.ul()
    list.li(tr("Skript geladen?"))
    list.li(tr("Origin freigeschaltet?"))
    list.li(tr("Konsolenmeldung vorhanden?"))
}

/**
 * D6-Muster (siehe `ConferenceScreen.showInviteTextFallback`): kein Toast, kein Dialog, keine
 * Animation -- nur der Label-Wechsel des Aufrufers. Bei fehlender/blockierter Clipboard-API wird
 * [onCopied] trotzdem NICHT aufgerufen (ehrliches Fehlschlagen statt eines irreführenden "Kopiert").
 */
private fun copyToClipboard(
    text: String,
    onCopied: () -> Unit,
) {
    val clipboard: dynamic = window.navigator.asDynamic().clipboard
    if (clipboard == null || clipboard == undefined) return
    val promise: dynamic = clipboard.writeText(text)
    promise.then({ onCopied() }, {})
}
