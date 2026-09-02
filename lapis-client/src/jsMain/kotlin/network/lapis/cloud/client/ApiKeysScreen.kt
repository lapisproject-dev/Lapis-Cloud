package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ApiKeyDto
import network.lapis.cloud.shared.domain.ApiKeyIssueResultDto
import network.lapis.cloud.shared.domain.WebhookEndpointDto
import network.lapis.cloud.shared.domain.WebhookEndpointSetResultDto
import network.lapis.cloud.shared.rpc.IApiKeyService
import network.lapis.cloud.shared.rpc.IWebhookService

/**
 * Welle V1.3.1 "API-Fundament, lesend" -- `/api-keys`, BOARD/ADMIN. Manages the API keys that
 * authenticate the read-only `/api/v1` REST surface (see `docs/api/public-api-v1.adoc`).
 *
 * **No re-reveal modal** (Design-Team decision #8): a freshly issued/reissued raw key is shown
 * exactly once, in a persistent card at the top of the list (never a dialog that can be dismissed
 * by accident) -- "Schlüssel verloren? Einfach neu ausstellen" is the recovery path, not a second
 * attempt to retrieve the same secret. Every row also carries its OWN "Neu ausstellen" button for
 * exactly this reason.
 *
 * **Welle V1.3.2 "Webhooks" (ausgehend) -- Design-Team decision D1**: the former flat `hPanel` row
 * per key became a Bootstrap CARD (`.card > .card-body`) -- header section unchanged (key info +
 * actions), a NEW footer section holds the webhook block. No second screen, no new route, no modal
 * for the URL input (inline text field, same discipline the key-issuance row already established).
 */
fun renderApiKeysScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 800.px
            marginTop = 24.px
        }
    root.h1(tr("API-Schlüssel"))
    root.div(
        tr(
            "Diese Schlüssel authentifizieren externe Zugriffe auf die schreibgeschützte REST-API " +
                "(/api/v1) -- siehe die API-Dokumentation für Endpunkte und Rate-Limits. Optional kann " +
                "je Schlüssel ein Webhook konfiguriert werden, der bei bestimmten Ereignissen automatisch " +
                "einen HTTP-POST an eine von Ihnen angegebene Adresse sendet.",
        ),
    ) { addCssClasses("text-muted small") }

    // D3 -- ONE shared reveal-card slot for BOTH the API-key reveal card and the webhook
    // signature-secret reveal card; `slot.removeAll()` (in showApiKeyRevealCard/
    // showWebhookSecretRevealCard) guarantees the two are never shown simultaneously (Raskin-
    // Auflage, same discipline `ConfirmDialog.kt`'s own "cancel left, danger action right" rule
    // establishes for this screen's confirmation dialogs).
    val revealCardSlot = root.vPanel(spacing = 8)
    val listSlot = root.vPanel(spacing = 8) { addCssClasses("mt-2") }

    fun loadKeysAndWebhooks() {
        listSlot.removeAll()
        listSlot.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val keys = guarded { rpcService<IApiKeyService>().listApiKeys(includeRevoked = true) } ?: return@launch
            val endpoints =
                webhookGuarded(conflictMessage = tr(WEBHOOK_LIST_CONFLICT_MESSAGE)) {
                    rpcService<IWebhookService>().listWebhookEndpoints()
                }.orEmpty()
            val endpointsByApiKeyId = endpoints.associateBy { it.apiKeyId }
            listSlot.removeAll()
            renderApiKeysList(listSlot, keys, endpointsByApiKeyId, revealCardSlot, ::loadKeysAndWebhooks)
        }
    }

    val issueRow = root.hPanel(spacing = 8) { addCssClasses("align-items-end mt-2") }
    val labelInput = issueRow.text(label = tr("Bezeichnung"))
    val issueButton = issueRow.button(tr("Neuen Schlüssel ausstellen"), style = ButtonStyle.PRIMARY)
    issueButton.onClick {
        val label = labelInput.value?.trim().orEmpty()
        if (label.isBlank()) {
            notifyError(tr("Bitte eine Bezeichnung angeben."))
            return@onClick
        }
        issueButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IApiKeyService>().issueApiKey(label = label) }
            issueButton.disabled = false
            if (result != null) {
                labelInput.value = null
                showApiKeyRevealCard(revealCardSlot, result)
                loadKeysAndWebhooks()
            }
        }
    }

    loadKeysAndWebhooks()
}

private fun renderApiKeysList(
    panel: SimplePanel,
    keys: List<ApiKeyDto>,
    endpointsByApiKeyId: Map<String, WebhookEndpointDto>,
    revealCardSlot: SimplePanel,
    reload: () -> Unit,
) {
    if (keys.isEmpty()) {
        panel.div(tr("Noch keine API-Schlüssel ausgestellt.")) { addCssClasses("text-muted small") }
        return
    }
    keys.forEach { key -> renderApiKeyCard(panel, key, endpointsByApiKeyId[key.id], revealCardSlot, reload) }
}

private fun renderApiKeyCard(
    panel: SimplePanel,
    key: ApiKeyDto,
    endpoint: WebhookEndpointDto?,
    revealCardSlot: SimplePanel,
    reload: () -> Unit,
) {
    val card = panel.vPanel { addCssClasses("card") }
    val cardBody = card.vPanel(spacing = 8) { addCssClasses("card-body") }

    // D4 -- deactivation banner ABOVE the key header, inside the card, when the webhook was
    // auto-deactivated. Names the key + reason -- same "name exactly what freezes/breaks" grammar
    // as `ConfirmDialog.kt`'s own dangerNote.
    if (endpoint != null && !endpoint.active) {
        cardBody.div {
            addCssClasses("fw-bold text-danger mb-1")
            content =
                gettext(
                    "Webhook für „%1“ deaktiviert: %2.",
                    key.label,
                    endpoint.deactivationReason?.let { webhookDeactivationReasonLabel(it) } ?: tr("unbekannter Grund"),
                )
        }
    }

    val header = cardBody.hPanel(spacing = 12) { addCssClasses("align-items-center") }
    val info = header.vPanel(spacing = 2) { addCssClasses("flex-grow-1") }
    info.div(key.label) { addCssClasses("fw-bold") }
    info.div("${key.keyPrefix}…") { addCssClasses("small font-monospace text-muted") }
    val statusLine =
        when {
            key.revokedAt != null -> tr("Widerrufen am") + " ${key.revokedAt}"
            key.expiresAt != null -> tr("Läuft ab am") + " ${key.expiresAt}"
            else -> tr("Kein Ablaufdatum")
        }
    info.div(statusLine) { addCssClasses("small text-muted") }
    val lastUsedLine =
        if (key.lastUsedAt != null) {
            // 5-Minuten-Genauigkeit -- ApiKeyStore.touchLastUsed throttles writes, siehe dessen KDoc.
            gettext("Zuletzt genutzt (5-Minuten-Genauigkeit): %1", key.lastUsedAt.toString())
        } else {
            tr("Noch nicht genutzt")
        }
    info.div(lastUsedLine) { addCssClasses("small text-muted") }

    if (key.revokedAt == null) {
        header.statusBadge(tr("Aktiv"), "success")
    } else {
        header.statusBadge(tr("Widerrufen"), "secondary")
    }

    val actions = header.hPanel(spacing = 8)
    val reissueButton = actions.button(tr("Neu ausstellen"), style = ButtonStyle.OUTLINEPRIMARY)
    reissueButton.onClick {
        reissueButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IApiKeyService>().reissueApiKey(id = key.id) }
            reissueButton.disabled = false
            if (result != null) {
                showApiKeyRevealCard(revealCardSlot, result)
                reload()
            }
        }
    }
    if (key.revokedAt == null) {
        val revokeButton = actions.button(tr("Widerrufen"), style = ButtonStyle.OUTLINEDANGER)
        revokeButton.onClick {
            apiKeyRevokeConfirmDialog(key.label) {
                revokeButton.disabled = true
                AppScope.launch {
                    val result = guarded { rpcService<IApiKeyService>().revokeApiKey(id = key.id) }
                    revokeButton.disabled = false
                    if (result != null) {
                        notifySuccess(tr("Schlüssel widerrufen."))
                        reload()
                    }
                }
            }
        }
    }

    // Webhook footer -- for a NON-revoked key, always (offers "Webhook einrichten" even with no
    // endpoint yet). For a REVOKED key, only when it still has a leftover endpoint row (review
    // fix): `ApiKeyService.revokeApiKey` DEACTIVATES that endpoint but does not delete it (unlike
    // `reissueApiKey`, which migrates the row via `migrateApiKeyId`), so its destination URL and
    // at-rest-encrypted signature secret would otherwise sit in the database invisibly, reachable
    // only through a raw RPC call, with no "Entfernen" the product ever offers. Rendered READ-ONLY
    // in that case (see [renderWebhookBlock]'s own `readOnly` KDoc) -- setting up a webhook for a
    // key that no longer authenticates anything would be pointless, and `setWebhookUrl`/
    // `rotateWebhookSecret`/`reactivateWebhookEndpoint` all reject a revoked key server-side
    // anyway (`WebhookService.requireApiKeyExists`, MAJOR review fix).
    if (key.revokedAt == null || endpoint != null) {
        val footer = cardBody.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
        renderWebhookBlock(footer, key, endpoint, revealCardSlot, reload, readOnly = key.revokedAt != null)
    }
}

/**
 * [readOnly] (review fix) -- set for a REVOKED key's leftover endpoint (see call site KDoc): shows
 * the same status/URL/last-HTTP-status/delivery-log information as the normal footer, but offers
 * ONLY "Entfernen", never "URL ändern"/"Signaturgeheimnis neu erzeugen"/"Test-Event senden"/"Wieder
 * aktivieren" -- every one of those would either be pointless (the key that would receive a fresh
 * secret authenticates nothing any more) or is already rejected server-side
 * (`WebhookService.requireApiKeyExists`). [readOnly] is never `true` with `endpoint == null` (the
 * call site only renders this block for a revoked key when an endpoint actually exists), so the
 * "Kein Webhook eingerichtet." setup form below is unreachable in that combination.
 */
private fun renderWebhookBlock(
    footer: SimplePanel,
    key: ApiKeyDto,
    endpoint: WebhookEndpointDto?,
    revealCardSlot: SimplePanel,
    reload: () -> Unit,
    readOnly: Boolean = false,
) {
    if (endpoint == null) {
        footer.div(tr("Kein Webhook eingerichtet.")) { addCssClasses("small text-muted") }
        val row = footer.hPanel(spacing = 8) { addCssClasses("align-items-end") }
        val urlInput = row.text(label = tr("Webhook-URL (https://…)"))
        val setupButton = row.button(tr("Webhook einrichten"), style = ButtonStyle.OUTLINEPRIMARY)
        setupButton.onClick {
            val url = urlInput.value?.trim().orEmpty()
            if (url.isBlank()) {
                notifyError(tr("Bitte eine Adresse angeben."))
                return@onClick
            }
            setupButton.disabled = true
            AppScope.launch {
                val result =
                    webhookGuarded(conflictMessage = tr(WEBHOOK_SET_URL_CONFLICT_MESSAGE)) {
                        rpcService<IWebhookService>().setWebhookUrl(apiKeyId = key.id, url = url)
                    }
                setupButton.disabled = false
                if (result != null) {
                    showWebhookSecretRevealCard(revealCardSlot, result)
                    reload()
                }
            }
        }
        return
    }

    val urlLine = footer.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    urlLine.div(endpoint.url) { addCssClasses("small text-break font-monospace flex-grow-1") }
    if (endpoint.active) {
        urlLine.statusBadge(tr("Aktiv"), "success")
    } else {
        urlLine.statusBadge(tr("Deaktiviert"), "danger")
    }
    if (endpoint.lastHttpStatus != null) {
        footer.div(gettext("Letzter HTTP-Status: %1", endpoint.lastHttpStatus.toString())) { addCssClasses("small text-muted") }
    }

    val actions = footer.hPanel(spacing = 8) { addCssClasses("flex-wrap") }

    // readOnly (review fix, revoked key) -- none of URL-change/secret-rotate/test-event/reactivate
    // below are offered; see this function's own KDoc for why each would be either pointless or
    // already rejected server-side.
    if (!readOnly) {
        val editRow = footer.hPanel(spacing = 8) { addCssClasses("align-items-end") }
        val urlInput = editRow.text(label = tr("Neue Webhook-URL")) { hide() }
        val saveUrlButton = editRow.button(tr("Speichern"), style = ButtonStyle.OUTLINEPRIMARY) { hide() }

        val changeUrlButton = actions.button(tr("URL ändern"), style = ButtonStyle.LINK)
        changeUrlButton.onClick {
            urlInput.value = endpoint.url
            urlInput.show()
            saveUrlButton.show()
        }
        saveUrlButton.onClick {
            val url = urlInput.value?.trim().orEmpty()
            if (url.isBlank()) {
                notifyError(tr("Bitte eine Adresse angeben."))
                return@onClick
            }
            saveUrlButton.disabled = true
            AppScope.launch {
                val result =
                    webhookGuarded(conflictMessage = tr(WEBHOOK_SET_URL_CONFLICT_MESSAGE)) {
                        rpcService<IWebhookService>().setWebhookUrl(apiKeyId = key.id, url = url)
                    }
                saveUrlButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Webhook-Adresse aktualisiert."))
                    reload()
                }
            }
        }

        val rotateButton = actions.button(tr("Signaturgeheimnis neu erzeugen"), style = ButtonStyle.LINK)
        rotateButton.onClick {
            webhookSecretRotateConfirmDialog(key.label) {
                rotateButton.disabled = true
                AppScope.launch {
                    val result =
                        webhookGuarded(conflictMessage = tr(WEBHOOK_ROTATE_SECRET_CONFLICT_MESSAGE)) {
                            rpcService<IWebhookService>().rotateWebhookSecret(apiKeyId = key.id)
                        }
                    rotateButton.disabled = false
                    if (result != null) {
                        showWebhookSecretRevealCard(revealCardSlot, result)
                        reload()
                    }
                }
            }
        }

        // D2 -- three states: ruhe -> disabled "Sendet Test-Event …" -> a PERSISTENT result line
        // under the card (never a toast, the outcome must stay visible while the operator inspects
        // the receiving end).
        val testButton = actions.button(tr("Test-Event senden"), style = ButtonStyle.LINK)
        val testResultLine = footer.div("") { addCssClasses("small") }
        testButton.onClick {
            testButton.disabled = true
            testButton.text = tr("Sendet Test-Event …")
            testResultLine.content = ""
            AppScope.launch {
                val result =
                    webhookGuarded(conflictMessage = tr(WEBHOOK_TEST_EVENT_CONFLICT_MESSAGE)) {
                        rpcService<IWebhookService>().sendWebhookTestEvent(apiKeyId = key.id)
                    }
                testButton.disabled = false
                testButton.text = tr("Test-Event senden")
                if (result != null) {
                    val delivered = result.lastHttpStatus != null && result.lastHttpStatus in 200..299
                    testResultLine.removeCssClass("text-success")
                    testResultLine.removeCssClass("text-danger")
                    testResultLine.addCssClass(if (delivered) "text-success" else "text-danger")
                    testResultLine.content =
                        if (result.lastHttpStatus != null) {
                            gettext(
                                "HTTP %1 -- %2",
                                result.lastHttpStatus.toString(),
                                if (delivered) tr("zugestellt") else tr("fehlgeschlagen"),
                            )
                        } else {
                            gettext(
                                "Fehlgeschlagen: %1",
                                result.lastErrorCode?.let { webhookFailureReasonLabel(it) } ?: tr("unbekannter Fehler"),
                            )
                        }
                }
            }
        }

        if (!endpoint.active) {
            // Reaktivieren OHNE Bestätigungsdialog -- nicht destruktiv (Design-Team decision D4).
            val reactivateButton = actions.button(tr("Wieder aktivieren"), style = ButtonStyle.OUTLINESUCCESS)
            reactivateButton.onClick {
                reactivateButton.disabled = true
                AppScope.launch {
                    val result =
                        webhookGuarded(conflictMessage = tr(WEBHOOK_REACTIVATE_CONFLICT_MESSAGE)) {
                            rpcService<IWebhookService>().reactivateWebhookEndpoint(apiKeyId = key.id)
                        }
                    reactivateButton.disabled = false
                    if (result != null) {
                        notifySuccess(tr("Webhook wieder aktiviert."))
                        reload()
                    }
                }
            }
        }
    }

    val removeButton = actions.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.onClick {
        webhookRemoveConfirmDialog(key.label) {
            removeButton.disabled = true
            AppScope.launch {
                val ok =
                    webhookGuarded(conflictMessage = tr(WEBHOOK_REMOVE_CONFLICT_MESSAGE)) {
                        rpcService<IWebhookService>().removeWebhookUrl(apiKeyId = key.id)
                        true
                    }
                removeButton.disabled = false
                if (ok != null) {
                    notifySuccess(tr("Webhook entfernt."))
                    reload()
                }
            }
        }
    }

    footer.renderWebhookDeliveryLogPanel(key.id)
}

/**
 * The persistent, non-dismissible-by-accident card showing a freshly issued/reissued raw key
 * (Design-Team decision #8) -- large, monospaced, full-width copy target. Replaces any previous
 * card in [slot] rather than stacking multiple (see D3 -- also replaces a webhook-secret reveal
 * card, if one was showing).
 */
private fun showApiKeyRevealCard(
    slot: SimplePanel,
    result: ApiKeyIssueResultDto,
) {
    slot.removeAll()
    val card =
        slot.vPanel(spacing = 8) {
            addCssClasses("border border-warning rounded p-3")
        }
    card.h2(tr("Neuer Schlüssel -- jetzt speichern")) { addCssClass("h5") }
    card.div(
        tr(
            "Dieser Schlüssel wird aus Sicherheitsgründen nur dieses eine Mal angezeigt. " +
                "Kopieren Sie ihn jetzt an einen sicheren Ort.",
        ),
    ) { addCssClasses("text-muted small") }
    card.div(result.rawKey) {
        addCssClasses("font-monospace fs-6 p-2 bg-body-secondary rounded")
        overflow = Overflow.AUTO
    }
    card.div(tr("Schlüssel verloren? Einfach neu ausstellen -- der alte wird dabei automatisch widerrufen.")) {
        addCssClasses("small text-muted")
    }
    val dismissButton = card.button(tr("Ich habe den Schlüssel gespeichert"), style = ButtonStyle.SECONDARY)
    dismissButton.onClick { slot.removeAll() }
}

/**
 * D3 -- the SIGNATURE-SECRET counterpart of [showApiKeyRevealCard], visually distinguished on
 * three independent channels (border color, icon, prefix) so the two are never confused even
 * though they share [slot]: `border-info` (not `border-warning`), a signature icon (not a key
 * icon), and the `whsec_lapis_…` prefix visibly different from `lapis_…`. The footer text is
 * DELIBERATELY different from [showApiKeyRevealCard]'s own -- unlike an API key, rotating the
 * secret invalidates the OLD one immediately with no grace window, so re-using the key-loss
 * sentence here would be a lie (see `IWebhookService.rotateWebhookSecret` KDoc).
 */
private fun showWebhookSecretRevealCard(
    slot: SimplePanel,
    result: WebhookEndpointSetResultDto,
) {
    val rawSecret = result.rawSecret ?: return
    slot.removeAll()
    val card =
        slot.vPanel(spacing = 8) {
            addCssClasses("border border-info rounded p-3")
        }
    card.h2(tr("Neues Signaturgeheimnis -- jetzt speichern")) { addCssClass("h5") }
    card.div(
        tr(
            "Dieses Signaturgeheimnis wird aus Sicherheitsgründen nur dieses eine Mal angezeigt. " +
                "Kopieren Sie es jetzt an einen sicheren Ort.",
        ),
    ) { addCssClasses("text-muted small") }
    card.div(rawSecret) {
        addCssClasses("font-monospace fs-6 p-2 bg-body-secondary rounded")
        overflow = Overflow.AUTO
    }
    card.div(
        tr(
            "Verloren? Nur ein neues Geheimnis erzeugen -- das alte wird dabei sofort ungültig, " +
                "laufende Integrationen brechen bis zur Umstellung ab.",
        ),
    ) { addCssClasses("small text-muted") }
    val dismissButton = card.button(tr("Ich habe das Geheimnis gespeichert"), style = ButtonStyle.SECONDARY)
    dismissButton.onClick { slot.removeAll() }
}

private fun apiKeyRevokeConfirmDialog(
    label: String,
    onConfirm: () -> Unit,
) {
    val modal = io.kvision.modal.Modal(caption = tr("API-Schlüssel widerrufen bestätigen"))
    modal.div(gettext("Der Schlüssel „%1“ kann danach nicht mehr für Zugriffe auf /api/v1 verwendet werden.", label)) {
        addCssClasses("fw-bold text-danger")
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Widerrufen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

private fun webhookSecretRotateConfirmDialog(
    label: String,
    onConfirm: () -> Unit,
) {
    val modal = io.kvision.modal.Modal(caption = tr("Signaturgeheimnis neu erzeugen bestätigen"))
    modal.div(
        gettext(
            "Das aktuelle Signaturgeheimnis für den Webhook von „%1“ wird sofort ungültig -- laufende " +
                "Integrationen brechen bis zur Umstellung ab.",
            label,
        ),
    ) { addCssClasses("fw-bold text-danger") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Neu erzeugen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

private fun webhookRemoveConfirmDialog(
    label: String,
    onConfirm: () -> Unit,
) {
    val modal = io.kvision.modal.Modal(caption = tr("Webhook entfernen bestätigen"))
    modal.div(gettext("Der Webhook für „%1“ wird vollständig entfernt, inklusive Signaturgeheimnis und Zustellungsprotokoll.", label)) {
        addCssClasses("fw-bold text-danger")
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Entfernen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}
