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
import network.lapis.cloud.shared.rpc.IApiKeyService

/**
 * Welle V1.3.1 "API-Fundament, lesend" -- `/api-keys`, BOARD/ADMIN. Manages the API keys that
 * authenticate the read-only `/api/v1` REST surface (see `docs/api/public-api-v1.adoc`).
 *
 * **No re-reveal modal** (Design-Team decision #8): a freshly issued/reissued raw key is shown
 * exactly once, in a persistent card at the top of the list (never a dialog that can be dismissed
 * by accident) -- "Schlüssel verloren? Einfach neu ausstellen" is the recovery path, not a second
 * attempt to retrieve the same secret. Every row also carries its OWN "Neu ausstellen" button for
 * exactly this reason.
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
                "(/api/v1) -- siehe die API-Dokumentation für Endpunkte und Rate-Limits.",
        ),
    ) { addCssClasses("text-muted small") }

    val revealCardSlot = root.vPanel(spacing = 8)
    val listSlot = root.vPanel(spacing = 8) { addCssClasses("mt-2") }

    fun loadKeys() {
        listSlot.removeAll()
        listSlot.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val keys = guarded { rpcService<IApiKeyService>().listApiKeys(includeRevoked = true) } ?: return@launch
            listSlot.removeAll()
            renderApiKeysList(listSlot, keys, revealCardSlot, ::loadKeys)
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
                showRevealCard(revealCardSlot, result)
                loadKeys()
            }
        }
    }

    loadKeys()
}

private fun renderApiKeysList(
    panel: SimplePanel,
    keys: List<ApiKeyDto>,
    revealCardSlot: SimplePanel,
    reload: () -> Unit,
) {
    if (keys.isEmpty()) {
        panel.div(tr("Noch keine API-Schlüssel ausgestellt.")) { addCssClasses("text-muted small") }
        return
    }
    keys.forEach { key -> renderApiKeyRow(panel, key, revealCardSlot, reload) }
}

private fun renderApiKeyRow(
    panel: SimplePanel,
    key: ApiKeyDto,
    revealCardSlot: SimplePanel,
    reload: () -> Unit,
) {
    val row =
        panel.hPanel(spacing = 12) {
            addCssClasses("align-items-center border rounded p-2")
        }
    val info = row.vPanel(spacing = 2) { addCssClasses("flex-grow-1") }
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
        row.statusBadge(tr("Aktiv"), "success")
    } else {
        row.statusBadge(tr("Widerrufen"), "secondary")
    }

    val actions = row.hPanel(spacing = 8)
    val reissueButton = actions.button(tr("Neu ausstellen"), style = ButtonStyle.OUTLINEPRIMARY)
    reissueButton.onClick {
        reissueButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IApiKeyService>().reissueApiKey(id = key.id) }
            reissueButton.disabled = false
            if (result != null) {
                showRevealCard(revealCardSlot, result)
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
}

/**
 * The persistent, non-dismissible-by-accident card showing a freshly issued/reissued raw key
 * (Design-Team decision #8) -- large, monospaced, full-width copy target. Replaces any previous
 * card in [slot] rather than stacking multiple.
 */
private fun showRevealCard(
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
