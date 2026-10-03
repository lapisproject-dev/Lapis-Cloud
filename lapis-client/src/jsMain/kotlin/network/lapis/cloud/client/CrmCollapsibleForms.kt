package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.rpc.ICrmService

/*
 * V1.9.48 (R36B): the two CRM create forms behind collapsible buttons -- "Kontakt anlegen" (page header) and "Interaktion erfassen"
 * (title row "Interaktionsverlauf" of a contact's detail panel). Only write RPCs live here; the list and timeline reads stay in
 * [CrmContactsScreen.kt].
 */

/** Sequence for the form id of the interaction form: several contact details can be open at once, so the id is never built from a contact id. */
private var crmInteractionFormSeq = 0

/** Hangs the collapsed interaction form of [contactId] into [titleSlot] (button) and [host] (form); returns its controller. */
internal fun mountCrmInteractionCapture(
    titleSlot: Container,
    host: SimplePanel,
    contactId: String,
    onRecorded: () -> Unit,
): CollapsibleCreateFormController<Unit> =
    collapsibleCreateForm<Unit>(
        actionSlot = titleSlot,
        formHost = host,
        buttonLabel = tr("Interaktion erfassen"),
        formId = "lapis-create-crm-interaction-${++crmInteractionFormSeq}",
    ) { _, close -> renderCrmInteractionCaptureForm(this, contactId, close, onRecorded) }

internal fun renderCrmInteractionCaptureForm(
    panel: SimplePanel,
    contactId: String,
    collapse: ((Boolean) -> Unit)? = null,
    onRecorded: () -> Unit,
): FormSnapshot {
    val form = panel.vPanel(spacing = 6)
    val kindOptions = CrmInteractionKind.entries.map { it.name to crmInteractionKindLabel(it) }
    val kindSelect = form.select(options = kindOptions, value = CrmInteractionKind.NOTE.name, label = tr("Art"))
    val occurredAtInput = form.text(label = tr("Zeitpunkt (optional, ISO -- leer = jetzt, z. B. 2026-09-14T10:00)"))
    val summaryInput = form.textArea(label = tr("Notiz (Pflichtfeld)")) { rows = 3 }
    val errorBox =
        form.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = form.hPanel(spacing = 8)
    val recordButton = buttonRow.actionButton(ActionIcon.SAVE, tr("Interaktion speichern"), style = ButtonStyle.PRIMARY)
    if (collapse != null) buttonRow.add(collapseCancelButton(collapse))

    recordButton.onClick {
        errorBox.hide()
        val summary = summaryInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(summary)) {
            errorBox.content = tr("Bitte eine Notiz eingeben.")
            errorBox.show()
            return@onClick
        }
        val kind = runCatching { CrmInteractionKind.valueOf(kindSelect.value.orEmpty()) }.getOrNull() ?: CrmInteractionKind.NOTE
        val occurredAtRaw = occurredAtInput.value?.trim().orEmpty()
        val occurredAt =
            if (occurredAtRaw.isBlank()) {
                null
            } else {
                runCatching { LocalDateTime.parse(occurredAtRaw) }.getOrNull()
                    ?: run {
                        errorBox.content = tr("Zeitpunkt ist kein gültiges ISO-Format (z. B. 2026-09-14T10:00).")
                        errorBox.show()
                        return@onClick
                    }
            }

        recordButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ICrmService>().recordInteraction(
                        CrmInteractionInput(contactId = contactId, occurredAt = occurredAt, kind = kind, summary = summary),
                    )
                }
            recordButton.disabled = false
            if (result != null) {
                notifySuccess(tr("Interaktion wurde gespeichert."))
                collapse?.invoke(true)
                onRecorded()
            }
        }
    }
    return FormSnapshot { listOf(kindSelect.value.orEmpty(), occurredAtInput.value.orEmpty(), summaryInput.value.orEmpty()) }
}

internal fun renderCrmContactCreationForm(
    root: SimplePanel,
    collapse: ((Boolean) -> Unit)? = null,
    onCreated: () -> Unit,
): FormSnapshot {
    val panel = root.vPanel(spacing = 6)
    val displayNameInput = panel.text(label = tr("Name"))
    val emailInput = panel.text(label = tr("E-Mail (optional)"))
    val phoneInput = panel.text(label = tr("Telefon (optional)"))
    val streetInput = panel.text(label = tr("Straße (optional)"))
    val postalCodeInput = panel.text(label = tr("PLZ (optional)"))
    val cityInput = panel.text(label = tr("Ort (optional)"))
    val countryInput = panel.text(label = tr("Land (optional)"))
    val typeOptions = listOf("" to tr("-- Typ wählen --")) + CrmContactType.entries.map { it.name to crmContactTypeLabel(it) }
    val typeSelect = panel.select(options = typeOptions, value = "", label = tr("Typ"))
    val basisOptions = listOf("" to tr("-- Rechtsgrundlage wählen --")) + CrmLawfulBasis.entries.map { it.name to crmLawfulBasisLabel(it) }
    val basisSelect = panel.select(options = basisOptions, value = "", label = tr("Rechtsgrundlage (Art. 6 DSGVO)"))
    val consentSourceInput = panel.text(label = tr("Herkunft der Einwilligung (z. B. \"Infostand Braunschweig\")")) { hide() }
    val consentGivenAtInput = panel.text(label = tr("Zeitpunkt der Einwilligung (ISO, z. B. 2026-09-14T10:00)")) { hide() }
    basisSelect.subscribe { value ->
        val isConsent = value == CrmLawfulBasis.CONSENT.name
        consentSourceInput.visible = isConsent
        consentGivenAtInput.visible = isConsent
    }
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val createButton = buttonRow.actionButton(ActionIcon.ADD, tr("Kontakt anlegen"), style = ButtonStyle.PRIMARY)
    if (collapse != null) buttonRow.add(collapseCancelButton(collapse))

    createButton.onClick {
        errorBox.hide()
        val displayName = displayNameInput.value.orEmpty().trim()
        val contactType = runCatching { CrmContactType.valueOf(typeSelect.value.orEmpty()) }.getOrNull()
        val lawfulBasis = runCatching { CrmLawfulBasis.valueOf(basisSelect.value.orEmpty()) }.getOrNull()

        if (!Validation.isNonBlank(displayName) || contactType == null || lawfulBasis == null) {
            errorBox.content = tr("Bitte Name, Typ und Rechtsgrundlage angeben.")
            errorBox.show()
            return@onClick
        }

        val consentGivenAt =
            if (lawfulBasis == CrmLawfulBasis.CONSENT) {
                val raw = consentGivenAtInput.value?.trim().orEmpty()
                if (raw.isBlank()) {
                    errorBox.content = tr("Bei Rechtsgrundlage 'Einwilligung' ist der Zeitpunkt der Einwilligung Pflicht.")
                    errorBox.show()
                    return@onClick
                }
                runCatching { LocalDateTime.parse(raw) }.getOrNull()
                    ?: run {
                        errorBox.content = tr("Zeitpunkt ist kein gültiges ISO-Format (z. B. 2026-09-14T10:00).")
                        errorBox.show()
                        return@onClick
                    }
            } else {
                null
            }

        createButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ICrmService>().createContact(
                        CrmContactInput(
                            displayName = displayName,
                            email = emailInput.value?.trim()?.takeIf { it.isNotBlank() },
                            phone = phoneInput.value?.trim()?.takeIf { it.isNotBlank() },
                            street = streetInput.value?.trim()?.takeIf { it.isNotBlank() },
                            postalCode = postalCodeInput.value?.trim()?.takeIf { it.isNotBlank() },
                            city = cityInput.value?.trim()?.takeIf { it.isNotBlank() },
                            country = countryInput.value?.trim()?.takeIf { it.isNotBlank() },
                            contactType = contactType,
                            lawfulBasis = lawfulBasis,
                            consentSource = consentSourceInput.value?.trim()?.takeIf { it.isNotBlank() },
                            consentGivenAt = consentGivenAt,
                            externalDonorId = null,
                            memberId = null,
                        ),
                    )
                }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Kontakt \"%1\" wurde angelegt.", displayName))
                collapse?.invoke(true)
                onCreated()
            }
        }
    }
    return FormSnapshot {
        listOf(
            displayNameInput.value.orEmpty(),
            emailInput.value.orEmpty(),
            phoneInput.value.orEmpty(),
            streetInput.value.orEmpty(),
            postalCodeInput.value.orEmpty(),
            cityInput.value.orEmpty(),
            countryInput.value.orEmpty(),
            typeSelect.value.orEmpty(),
            basisSelect.value.orEmpty(),
            consentSourceInput.value.orEmpty(),
            consentGivenAtInput.value.orEmpty(),
        )
    }
}
