package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.PaymentGatewaySettingsDto
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PspConfigStatusDto
import network.lapis.cloud.shared.rpc.IPaymentGatewayService

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `/payment-gateway-settings`, ADMIN
 * only. Three sections mirroring `SepaSettingsScreen.kt` exactly: (a) status + enable-with-
 * disclaimer-modal / disable, reading `version`/`sha256` off the just-fetched DTO as LOCAL values,
 * never editable form fields; (b) `getPspConfigStatus()` rendered as green/red presence badges plus
 * the exact webhook URL to paste into the Stripe dashboard -- **never a secret value, not even
 * truncated**; (c) the four ledger-account mappings, reusing `LedgerScreen.kt`'s own
 * `renderPaymentAccountMappingSection` rather than a second, duplicate implementation (see that
 * function's own KDoc "Welle V1.2.8" addendum).
 */
fun renderPaymentGatewaySettingsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 700.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Zahlungs-Konfiguration"))

    renderPaymentGatewayAdminSection(root)
    renderPspConfigStatusSection(root)
    root.h2(tr("Kontenzuordnung")) { addCssClass("h5") }
    renderPaymentAccountMappingSection(root, canManage = AppState.hasRole(AccountRole.ADMIN))
}

// ================================================================================================
// Aktivieren/Deaktivieren
// ================================================================================================

private fun renderPaymentGatewayAdminSection(root: SimplePanel) {
    root.h2(tr("Status")) { addCssClass("h5") }
    val settingsPanel = root.vPanel(spacing = 4)
    settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadSettings() {
        settingsPanel.removeAll()
        settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<IPaymentGatewayService>().getPaymentGatewaySettings() } ?: return@launch
            settingsPanel.removeAll()
            renderPaymentGatewaySettingsSummary(settingsPanel, settings)
        }
    }

    val actionsRow = root.hPanel(spacing = 8) { addCssClasses("mt-2 align-items-center") }
    // Fix (Review round 1, CRITICAL): Welle V1.2.8b added PAYPAL as a fully first-class provider
    // server-side (`enablePaymentGateway` accepts both STRIPE and PAYPAL), but this screen never
    // gained a picker -- `enablePaymentGateway` was called with the STRIPE literal unconditionally,
    // making PayPal unreachable end-to-end despite ~1,900 lines of correct server-side plumbing.
    // Order (PAYPAL, STRIPE) matches PaymentProvider's own declared order (see that enum's own
    // "Literal order load-bearing" KDoc); MANUAL is deliberately excluded -- `enablePaymentGateway`
    // rejects it server-side (see IPaymentGatewayService KDoc "Role: ADMIN. provider must be PAYPAL
    // or STRIPE").
    val providerSelect =
        actionsRow.select(
            options =
                listOf(
                    PaymentProvider.PAYPAL.name to paymentProviderLabel(PaymentProvider.PAYPAL),
                    PaymentProvider.STRIPE.name to paymentProviderLabel(PaymentProvider.STRIPE),
                ),
            value = PaymentProvider.STRIPE.name,
            label = tr("Anbieter"),
        )
    val enableButton = actionsRow.button(tr("Online-Zahlung aktivieren …"), style = ButtonStyle.PRIMARY)
    val disableButton = actionsRow.button(tr("Online-Zahlung deaktivieren"), style = ButtonStyle.OUTLINEDANGER)
    // Fix (Review round 2, MINOR): plan §C5 called for greying out/warning against a provider whose
    // transport is not configured -- previously both options stayed selectable regardless of
    // getPspConfigStatus(), so nothing warned an ADMIN at selection time that PayPal/Stripe had no
    // env vars set (enablePaymentGateway would then fail server-side, or -- worse, if only the
    // shared LAPIS_PSP_* knobs were set -- silently select a provider whose real credentials are
    // missing). KVision's select() has no per-option disabled state, so this warns inline and
    // disables the Enable button instead, achieving the same "cannot enable an unconfigured
    // provider from this screen" outcome.
    val unconfiguredWarning =
        actionsRow.div { addCssClasses("text-danger small") }.apply { visible = false }
    var configStatus: PspConfigStatusDto? = null

    fun providerIsConfigured(provider: PaymentProvider): Boolean {
        val status = configStatus ?: return true // unknown yet -- never block before the status has loaded
        return when (provider) {
            PaymentProvider.STRIPE -> status.secretKeyConfigured && status.webhookSecretConfigured
            PaymentProvider.PAYPAL ->
                status.paypalClientIdConfigured && status.paypalClientSecretConfigured && status.paypalWebhookIdConfigured
            PaymentProvider.MANUAL -> true
        }
    }

    fun refreshProviderWarning() {
        val selectedProvider = PaymentProvider.entries.firstOrNull { it.name == providerSelect.value } ?: PaymentProvider.STRIPE
        val configured = providerIsConfigured(selectedProvider)
        enableButton.disabled = !configured
        unconfiguredWarning.visible = !configured
        if (!configured) {
            unconfiguredWarning.content =
                gettext(
                    "%1 ist serverseitig noch nicht vollständig konfiguriert -- siehe Diagnose-Abschnitt unten.",
                    paymentProviderLabel(selectedProvider),
                )
        }
    }
    providerSelect.subscribe { refreshProviderWarning() }
    AppScope.launch {
        configStatus = guarded { rpcService<IPaymentGatewayService>().getPspConfigStatus() }
        refreshProviderWarning()
    }

    fun acknowledgeAndEnable() {
        val selectedProvider = PaymentProvider.entries.firstOrNull { it.name == providerSelect.value } ?: PaymentProvider.STRIPE
        if (!providerIsConfigured(selectedProvider)) return
        enableButton.disabled = true
        AppScope.launch {
            val disclaimer = guarded { rpcService<IPaymentGatewayService>().getPaymentGatewayComplianceDisclaimer() }
            enableButton.disabled = false
            if (disclaimer != null) {
                paymentGatewayEnableDisclaimerModal(disclaimer) {
                    AppScope.launch {
                        val result =
                            guarded {
                                rpcService<IPaymentGatewayService>().enablePaymentGateway(
                                    provider = selectedProvider,
                                    acknowledgment =
                                        PaymentGatewayComplianceAcknowledgmentInput(
                                            disclaimerVersion = disclaimer.version,
                                            disclaimerSha256 = disclaimer.sha256,
                                        ),
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Online-Zahlung aktiviert."))
                            loadSettings()
                        }
                    }
                }
            }
        }
    }
    enableButton.onClick { acknowledgeAndEnable() }

    disableButton.onClick {
        paymentGatewayDisableConfirmDialog {
            disableButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IPaymentGatewayService>().disablePaymentGateway() }
                disableButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Online-Zahlung deaktiviert."))
                    loadSettings()
                }
            }
        }
    }

    loadSettings()
}

private fun renderPaymentGatewaySettingsSummary(
    panel: SimplePanel,
    settings: PaymentGatewaySettingsDto,
) {
    val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("Status:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (settings.paymentGatewayEnabled) tr("Aktiviert") else tr("Deaktiviert"),
        if (settings.paymentGatewayEnabled) "success" else "secondary",
    )
    val provider = settings.paymentGatewayProvider
    if (provider != null) {
        panel.div(gettext("Anbieter: %1", paymentProviderLabel(provider))) { addCssClasses("text-muted small") }
    }
    if (settings.lastAcknowledgedByDisplayName != null) {
        panel.div(
            gettext(
                "Zuletzt bestätigt von %1 am %2 (Hinweistext-Version %3).",
                settings.lastAcknowledgedByDisplayName,
                settings.lastAcknowledgedAt,
                settings.lastDisclaimerVersion,
            ),
        ) { addCssClasses("text-muted small") }
    } else {
        panel.div(tr("Noch keine Bestätigung des rechtlichen Hinweistexts erfolgt.")) { addCssClasses("text-muted small") }
    }
}

private fun paymentGatewayEnableDisclaimerModal(
    disclaimer: PaymentGatewayComplianceDisclaimerDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = gettext("Online-Zahlung aktivieren -- rechtlicher Hinweis (Version %1)", disclaimer.version))
    modal.div(
        tr(
            "Bitte lesen Sie den folgenden rechtlichen Hinweistext vollständig, bevor Sie die Online-Zahlung " +
                "aktivieren. Diese Plattform führt keine automatisierte Rechtsberatung durch -- die rechtliche " +
                "Einordnung liegt bei Ihrer Organisation.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    modal.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 300.px
        overflow = Overflow.AUTO
        content = sanitizeUntrustedI18nText(disclaimer.text)
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Ich bestätige, den aktuellen Text gelesen zu haben"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

private fun paymentGatewayDisableConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Online-Zahlung deaktivieren bestätigen"))
    modal.div(
        tr(
            "Neue Checkout-Sitzungen können bis zur erneuten Aktivierung nicht mehr erstellt werden. Bereits " +
                "erstellte Sitzungen laufen ab, ohne gebucht zu werden.",
        ),
    ) { addCssClasses("fw-bold text-danger") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Deaktivieren"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Diagnose (nur Vorhandensein, nie Werte)
// ================================================================================================

private fun renderPspConfigStatusSection(root: SimplePanel) {
    root.h2(tr("Zahlungsdienstleister-Diagnose")) { addCssClass("h5") }
    val panel = root.vPanel(spacing = 4)
    panel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    AppScope.launch {
        val status = guarded { rpcService<IPaymentGatewayService>().getPspConfigStatus() } ?: return@launch
        panel.removeAll()
        panel.div(tr("Stripe")) { addCssClasses("fw-bold small mt-1") }
        renderPspConfigStatusRow(panel, tr("Geheimer Schlüssel gesetzt"), status.secretKeyConfigured)
        renderPspConfigStatusRow(panel, tr("Webhook-Signing-Secret gesetzt"), status.webhookSecretConfigured)
        panel.div(
            gettext(
                "Im Stripe-Dashboard einzutragende Webhook-URL (Event checkout.session.completed): %1",
                status.webhookUrl,
            ),
        ) { addCssClasses("small font-monospace mt-2") }
        // Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) -- same presence-only discipline as the
        // Stripe rows above, never a secret value.
        panel.div(tr("PayPal")) { addCssClasses("fw-bold small mt-3") }
        renderPspConfigStatusRow(panel, tr("Client-ID gesetzt"), status.paypalClientIdConfigured)
        renderPspConfigStatusRow(panel, tr("Client-Secret gesetzt"), status.paypalClientSecretConfigured)
        renderPspConfigStatusRow(panel, tr("Webhook-ID gesetzt"), status.paypalWebhookIdConfigured)
        panel.div(
            gettext(
                "Im PayPal-Entwickler-Dashboard einzutragende Webhook-URL: %1",
                status.paypalWebhookUrl,
            ),
        ) { addCssClasses("small font-monospace mt-2") }
        panel.div(tr("Kontenzuordnung")) { addCssClasses("fw-bold small mt-3") }
        renderPspConfigStatusRow(panel, tr("Bankkonto zugeordnet"), status.paymentBankAccountConfigured)
        renderPspConfigStatusRow(panel, tr("Beitragserlöskonto zugeordnet"), status.contributionIncomeAccountConfigured)
        renderPspConfigStatusRow(panel, tr("Spendenerlöskonto zugeordnet"), status.donationIncomeAccountConfigured)
        renderPspConfigStatusRow(panel, tr("Gebührenkonto zugeordnet"), status.paymentFeeAccountConfigured)
    }
}

private fun renderPspConfigStatusRow(
    panel: SimplePanel,
    label: String,
    configured: Boolean,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    row.div(label) { addCssClasses("text-muted small") }
    row.statusBadge(if (configured) tr("Ja") else tr("Nein"), if (configured) "success" else "danger")
}
