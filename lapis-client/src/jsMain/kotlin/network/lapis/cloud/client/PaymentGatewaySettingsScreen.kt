package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.FinanceSetupStep
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.PaymentGatewaySettingsDto
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PspConfigStatusDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IPaymentGatewayService

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `/payment-gateway-settings`, ADMIN
 * only. Three sections mirroring `SepaSettingsScreen.kt` exactly: (a) status + enable-with-
 * disclaimer-modal / disable, reading `version`/`sha256` off the just-fetched DTO as LOCAL values,
 * never editable form fields; (b) `getPspConfigStatus()` rendered as green/red presence badges plus
 * the exact webhook URL to paste into the Stripe dashboard -- **never a secret value, not even
 * truncated**; (c) since V1.9.206 only a one-line status of the ledger-account mapping with a link -- the mapping itself has one
 * home, the tab "Einrichtung" of "Kontenplan & Journal" (`FinanceSetupTab.kt`).
 */
fun renderPaymentGatewaySettingsScreen(container: SimplePanel) {
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    root.pageHeader(tr("Zahlungs-Konfiguration"), intro = PageIntros.forRoute(Routes.PAYMENT_GATEWAY_SETTINGS))

    renderPaymentGatewayAdminSection(root)
    renderFinanceMappingStatusLine(root)
    renderPspConfigStatusSection(root)
}

/** Id of the message that explains why "Online-Zahlung aktivieren" does not proceed; the button points at it via `aria-describedby`. */
private const val PAYMENT_MAPPING_GATE_MESSAGE_ID = "payment-mapping-gate-message"

/**
 * V1.9.206. Text and Bootstrap colour of the one-line finance-mapping status. Incomplete is a WARNING (yellow), not an error: the
 * books are merely not ready yet. Pure, so the wording is testable without a DOM.
 */
internal fun financeMappingStatusText(status: FinanceSetupStatusDto): Pair<String, String> =
    if (status.mappingComplete) {
        tr("Kontenzuordnung: vollständig") to "success"
    } else {
        val missing = status.firstOpenRequired
        if (missing == null) {
            tr("Kontenzuordnung: unvollständig") to "warning"
        } else {
            gettext("Kontenzuordnung: unvollständig, es fehlt %1", setupStepTitle(missing)) to "warning"
        }
    }

/**
 * V1.9.206. The account mapping no longer lives on this page (it has exactly one home: "Kontenplan & Journal", tab "Einrichtung").
 * Here only a status line with a real link stays, so nobody wonders where the four accounts went.
 */
private fun renderFinanceMappingStatusLine(root: SimplePanel) {
    root.h2(tr("Kontenzuordnung")) { addCssClass("h5") }
    val line = root.div(className = "lapis-finance-status-line")
    line.span(tr("Wird geladen …"), className = "text-muted small")
    AppScope.launch {
        val status = guarded { rpcService<IAccountingService>().getFinanceSetupStatus() } ?: return@launch
        line.removeAll()
        val (text, color) = financeMappingStatusText(status)
        line.statusBadge(text, color)
        line.link(tr("Zur Einrichtung"), url = ledgerHash(LedgerTab.SETUP, status.firstOpenRequired))
    }
}

// ================================================================================================
// Aktivieren/Deaktivieren
// ================================================================================================

internal enum class EnableConflictOutcome { ALREADY_REPORTED, MAPPING_GATE, GENERIC_ERROR }

/** What an enable `ConflictException` means, given the re-read mapping status (`null` = that re-read failed too). */
internal fun enableConflictOutcome(fresh: FinanceSetupStatusDto?): EnableConflictOutcome =
    when {
        fresh == null -> EnableConflictOutcome.ALREADY_REPORTED
        !fresh.mappingComplete -> EnableConflictOutcome.MAPPING_GATE
        else -> EnableConflictOutcome.GENERIC_ERROR
    }

private const val PAYMENT_PROVIDER_WARNING_ID = "payment-provider-warning"

private fun renderPaymentGatewayAdminSection(root: SimplePanel) {
    root.h2(tr("Status")) { addCssClass("h5") }
    val settingsPanel = root.vPanel(spacing = 4)
    val actionsHost = root.toggleActionsHost()
    val gateMessage = root.div().apply { setAttribute("id", PAYMENT_MAPPING_GATE_MESSAGE_ID) }
    lateinit var toggle: ToggleSettingsPanel<PaymentGatewaySettingsDto>
    var configStatus: PspConfigStatusDto? = null

    // The provider picker, the "not configured" warning and the Enable button exist only while the gateway is DISABLED (V1.9.180):
    // they are built by the `enable` lambda below. The function of the CURRENT row is kept here so that the late answer of
    // getPspConfigStatus() refreshes the widgets that are on screen, never a row that was replaced meanwhile.
    var refreshProviderWarning: () -> Unit = {}

    fun providerIsConfigured(provider: PaymentProvider): Boolean {
        val status = configStatus ?: return true // unknown yet -- never block before the status has loaded
        return when (provider) {
            PaymentProvider.STRIPE -> status.secretKeyConfigured && status.webhookSecretConfigured
            PaymentProvider.PAYPAL ->
                status.paypalClientIdConfigured && status.paypalClientSecretConfigured && status.paypalWebhookIdConfigured
            PaymentProvider.MANUAL -> true
        }
    }

    fun acknowledgeAndEnable(
        enableButton: Button,
        selectedProvider: PaymentProvider,
    ) {
        if (!providerIsConfigured(selectedProvider)) return
        enableButton.disabled = true
        AppScope.launch {
            // V1.9.206: no disclaimer modal while the books cannot receive the postings -- say so, with a way forward.
            val setup = guarded { rpcService<IAccountingService>().getFinanceSetupStatus() }
            gateMessage.removeAll()
            if (setup != null && !setup.mappingComplete) {
                enableButton.disabled = false
                showMappingGateMessage(gateMessage, setup.firstOpenRequired)
                enableButton.setAttribute("aria-describedby", PAYMENT_MAPPING_GATE_MESSAGE_ID)
                return@launch
            }
            val disclaimer = guarded { rpcService<IPaymentGatewayService>().getPaymentGatewayComplianceDisclaimer() }
            enableButton.disabled = false
            if (disclaimer != null) {
                paymentGatewayEnableDisclaimerModal(disclaimer) {
                    AppScope.launch {
                        // Outside `guarded`: a ConflictException here means the server refused for an incomplete mapping (a race with
                        // another admin); Kilua transmits only the type, so the client's own sentence is the explanation.
                        val result =
                            try {
                                rpcService<IPaymentGatewayService>().enablePaymentGateway(
                                    provider = selectedProvider,
                                    acknowledgment =
                                        PaymentGatewayComplianceAcknowledgmentInput(
                                            disclaimerVersion = disclaimer.version,
                                            disclaimerSha256 = disclaimer.sha256,
                                        ),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ConflictException) {
                                val fresh = guarded { rpcService<IAccountingService>().getFinanceSetupStatus() }
                                when (enableConflictOutcome(fresh)) {
                                    EnableConflictOutcome.ALREADY_REPORTED -> null // the status call failed: `guarded` showed its error
                                    EnableConflictOutcome.MAPPING_GATE -> {
                                        gateMessage.removeAll()
                                        showMappingGateMessage(gateMessage, fresh?.firstOpenRequired)
                                        enableButton.setAttribute("aria-describedby", PAYMENT_MAPPING_GATE_MESSAGE_ID)
                                        null
                                    }
                                    // Mapping is fine: the refusal has another cause (e.g. a changed disclaimer) -- generic error path.
                                    EnableConflictOutcome.GENERIC_ERROR -> guarded<PaymentGatewaySettingsDto> { throw e }
                                }
                            } catch (e: Throwable) {
                                guarded<PaymentGatewaySettingsDto> { throw e }
                            }
                        if (result != null) {
                            notifySuccess(tr("Online-Zahlung aktiviert."))
                            toggle.load(focusStatus = true)
                        }
                    }
                }
            }
        }
    }

    toggle =
        ToggleSettingsPanel(
            settingsPanel = settingsPanel,
            actionsHost = actionsHost,
            fetch = {
                refreshProviderWarning = {}
                guarded { rpcService<IPaymentGatewayService>().getPaymentGatewaySettings() }
            },
            isEnabled = { it.paymentGatewayEnabled },
            renderSummary = ::renderPaymentGatewaySettingsSummary,
            enable = { row ->
                // Fix (Review round 1, CRITICAL): Welle V1.2.8b added PAYPAL as a fully first-class provider server-side
                // (`enablePaymentGateway` accepts both STRIPE and PAYPAL); the picker makes it reachable. Order (PAYPAL, STRIPE)
                // matches PaymentProvider's own declared order; MANUAL is deliberately excluded -- `enablePaymentGateway` rejects
                // it server-side (see IPaymentGatewayService KDoc "Role: ADMIN. provider must be PAYPAL or STRIPE").
                val providerSelect =
                    row.select(
                        options =
                            listOf(
                                PaymentProvider.PAYPAL.name to paymentProviderLabel(PaymentProvider.PAYPAL),
                                PaymentProvider.STRIPE.name to paymentProviderLabel(PaymentProvider.STRIPE),
                            ),
                        value = PaymentProvider.STRIPE.name,
                        label = tr("Anbieter"),
                    )
                providerSelect.addCssClass("lapis-setting-row__select")
                val enableButton = row.button(tr("Online-Zahlung aktivieren …"), style = ButtonStyle.PRIMARY)
                enableButton.addCssClass("lapis-setting-row__action")
                // Fix (Review round 2, MINOR): KVision's select() has no per-option disabled state, so a provider whose transport is
                // not configured is warned about inline and the Enable button is disabled instead.
                // V1.9.190: the warning is NOT a child of the row (it squeezed select and button) but the next sibling below it.
                val unconfiguredWarning =
                    actionsHost
                        .div {
                            setAttribute("id", PAYMENT_PROVIDER_WARNING_ID)
                            addCssClasses("lapis-setting-row__warning text-danger small mt-2")
                        }.apply { visible = false }

                fun selectedProvider(): PaymentProvider =
                    PaymentProvider.entries.firstOrNull { it.name == providerSelect.value } ?: PaymentProvider.STRIPE

                val refresh = {
                    val provider = selectedProvider()
                    val configured = providerIsConfigured(provider)
                    enableButton.disabled = !configured
                    unconfiguredWarning.visible = !configured
                    if (configured) {
                        enableButton.removeAttribute("aria-describedby")
                    } else {
                        enableButton.setAttribute("aria-describedby", PAYMENT_PROVIDER_WARNING_ID)
                        unconfiguredWarning.content =
                            gettext(
                                "%1 ist serverseitig noch nicht vollständig konfiguriert -- siehe Diagnose-Abschnitt unten.",
                                paymentProviderLabel(provider),
                            )
                    }
                }
                providerSelect.subscribe { refresh() }
                refreshProviderWarning = refresh
                refresh()
                enableButton.onClick { acknowledgeAndEnable(enableButton, selectedProvider()) }
                enableButton
            },
            disable = { row ->
                refreshProviderWarning = {}
                row.actionButton(ActionIcon.REVOKE, tr("Online-Zahlung deaktivieren"), style = ButtonStyle.OUTLINEDANGER).also { button ->
                    button.onClick {
                        paymentGatewayDisableConfirmDialog {
                            button.disabled = true
                            AppScope.launch {
                                val result = guarded { rpcService<IPaymentGatewayService>().disablePaymentGateway() }
                                button.disabled = false
                                if (result != null) {
                                    notifySuccess(tr("Online-Zahlung deaktiviert."))
                                    toggle.load(focusStatus = true)
                                }
                            }
                        }
                    }
                }
            },
            rowClass = "lapis-setting-row",
        )
    AppScope.launch {
        configStatus = guarded { rpcService<IPaymentGatewayService>().getPspConfigStatus() }
        refreshProviderWarning()
    }
    toggle.load()
}

private fun renderPaymentGatewaySettingsSummary(
    panel: SimplePanel,
    settings: PaymentGatewaySettingsDto,
) {
    // V1.9.190: the status is shown once, in the toggle row below ("Status: Aktiviert/Deaktiviert"); no second status line here.
    val provider = settings.paymentGatewayProvider
    if (provider != null) {
        panel.div(gettext("Anbieter: %1", paymentProviderLabel(provider))) { addCssClasses("text-muted small") }
    }
    if (settings.lastAcknowledgedByDisplayName != null) {
        panel.div(
            gettext(
                "Zuletzt bestätigt von %1 am %2 (Hinweistext-Version %3).",
                settings.lastAcknowledgedByDisplayName,
                formatSystemDateTime(settings.lastAcknowledgedAt!!),
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
        content = sanitizeUntrustedI18nText(disclaimer.text)
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
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
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        newActionButton(ActionIcon.REVOKE, tr("Deaktivieren"), ButtonStyle.DANGER).apply {
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

/** The explanation next to a refused activation, with a real link to the first missing step. */
private fun showMappingGateMessage(
    host: SimplePanel,
    missing: FinanceSetupStep?,
) {
    host.div(className = "alert alert-warning d-flex flex-wrap align-items-center gap-2 mt-2") {
        setAttribute("role", "alert")
        span(tr("Online-Zahlung kann erst aktiviert werden, wenn die Kontenzuordnung vollständig ist."))
        link(tr("Zur Einrichtung"), url = ledgerHash(LedgerTab.SETUP, missing))
    }
}
