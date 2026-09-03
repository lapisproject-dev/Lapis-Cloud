package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionDto
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.rpc.ICrmService
import kotlin.time.Clock

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- UI/UX-Design-Team-Entscheidungen (siehe
 * Welle-Plan §6):
 *
 * - Liste = drei Signale (Name · Typ-Badge · "Letzter Kontakt vor …"), Details per Akkordeon --
 *   selbe Grammatik wie [renderDonorsScreen]'s Spenderliste.
 * - Erfassungsformular für eine neue Interaktion ist MODELESS, dauerhaft sichtbar oberhalb der
 *   Zeitleiste, mit GENAU EINEM Pflichtfeld (`summary`) -- jedes zusätzliche Pflichtfeld halbiert
 *   die Erfassungsrate (Raskin/Duarte, von Jobs im Review verschärft). `occurred_at` ist optional
 *   und defaultet server-seitig auf "jetzt" (siehe [ICrmService.recordInteraction] KDoc) -- diese
 *   Welle bietet dafür bewusst ein einfaches Freitext-ISO-Feld statt eines eigens gebauten
 *   Datum/Zeit-Widgets (in diesem Codebase existiert noch keine wiederverwendbare Datum/Zeit-
 *   Eingabekomponente; ein Vollausbau ist eine eigene UI-Bausteinwelle wert, siehe Datei-KDoc der
 *   angrenzenden Screens) -- bewusste, offengelegte Vereinfachung dieser Welle, kein stiller Verzicht.
 * - `lawful_basis` hat KEINEN vorbelegten Wert -- eine Vorbelegung wäre eine ungeprüfte
 *   Rechtsgrundlagen-Behauptung. Bei `CONSENT` erscheinen `consent_source`/`consent_given_at` als
 *   Pflichtfelder.
 * - Datenschutz-Block (Auskunft/Löschung) sitzt am ENDE des Details, sichtbar abgesetzt --
 *   "Archivieren" dagegen in der Kopfzeile, damit die beiden nie verwechselt werden.
 * - Löschung nach Art. 17 verlangt eine Namens-Tippbestätigung
 *   ([confirmWithTypedConfirmationDialog]) -- keine Einzelklick-Bestätigung für den einzigen
 *   Vorgang, der eine Zeile diese Codebase unwiderruflich löscht (nicht anonymisiert).
 *
 * Rollen ([ICrmService] KDoc): lesen/schreiben BOARD+ADMIN, `eraseContact` ADMIN-only -- der
 * Löschen-Knopf wird für BOARD daher gar nicht erst gerendert (nicht nur deaktiviert).
 */
fun renderCrmContactsScreen(container: SimplePanel) {
    val canErase = AppState.hasRole(AccountRole.ADMIN)

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Kontakte & Interessenten"))
    root.div(
        tr(
            "Interessenten, Sympathisanten und sonstige Kontakte, die weder Mitglied noch (notwendigerweise) " +
                "ein erfasster Spender sind -- eine eigenständige Adressverwaltung, getrennt von der " +
                "Mitgliederverwaltung und dem Spenderstamm.",
        ),
    ) { addCssClasses("text-muted small") }

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val typeOptions = listOf("" to tr("-- Alle Typen --")) + CrmContactType.entries.map { it.name to crmContactTypeLabel(it) }
    val typeSelect = filterRow.select(options = typeOptions, value = "", label = tr("Typ"))
    val overdueCheck = filterRow.checkBox(label = tr("Wiedervorlage überfällig"))
    val includeArchivedCheck = filterRow.checkBox(label = tr("Archivierte anzeigen"))
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 6)
    // Real offset/`total`-based "Mehr laden" pagination -- `CrmContactPageDto.total` was previously
    // fetched and silently ignored (a party with > 200 contacts saw a hard, unsignalled cutoff at
    // `limit`, see review finding "Kontaktliste bricht bei 200 Einträgen stillschweigend ab").
    // Same `loadMoreButton`/offset-accumulation shape [renderAuditLogScreen] establishes, adapted to
    // offset (not keyset) pagination because [CrmContactPageDto] carries a real `total`.
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    var loadedOffset = 0

    fun loadContactPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            loadedOffset = 0
        }
        AppScope.launch {
            val filterType = runCatching { CrmContactType.valueOf(typeSelect.value.orEmpty()) }.getOrNull()
            val page =
                guarded {
                    rpcService<ICrmService>().listContacts(
                        filterType = filterType,
                        onlyRetentionOverdue = overdueCheck.value,
                        includeArchived = includeArchivedCheck.value,
                        limit = CRM_CONTACT_PAGE_SIZE,
                        offset = loadedOffset,
                    )
                } ?: return@launch
            if (page.items.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Kontakte gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            page.items.forEach { contact -> renderCrmContactRow(listPanel, contact, canErase) { loadContactPage(reset = true) } }
            loadedOffset += page.items.size
            if (loadedOffset < page.total) loadMoreButton.show() else loadMoreButton.hide()
        }
    }

    fun refreshList() = loadContactPage(reset = true)
    refreshButton.onClick { refreshList() }
    typeSelect.subscribe { refreshList() }
    overdueCheck.subscribe { refreshList() }
    includeArchivedCheck.subscribe { refreshList() }
    loadMoreButton.onClick { loadContactPage(reset = false) }
    refreshList()

    root.h2(tr("Neuen Kontakt anlegen"))
    renderCrmContactCreationForm(root, ::refreshList)
}

/** Page size for [ICrmService.listContacts]' "Mehr laden" pagination -- see [renderCrmContactsScreen]. */
private const val CRM_CONTACT_PAGE_SIZE = 50

/** Page size for [ICrmService.listInteractions]' "Mehr laden" pagination -- see [renderCrmInteractionTimeline]. */
private const val CRM_INTERACTION_PAGE_SIZE = 50

// ============================================================================================
// List row + detail accordion
// ============================================================================================

private fun renderCrmContactRow(
    panel: SimplePanel,
    contact: CrmContactDto,
    canErase: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(contact.displayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.typeBadge(crmContactTypeLabel(contact.contactType), crmContactTypeColor(contact.contactType))
    headerRow.div(crmLastInteractionRelativeText(contact.lastInteractionAt)) { addCssClasses("text-muted small") }
    if (contact.archivedAt != null) headerRow.typeBadge(tr("Archiviert"), "secondary")
    val archiveButton =
        headerRow.button(
            if (contact.archivedAt == null) tr("Archivieren") else tr("Entarchivieren"),
            style = ButtonStyle.OUTLINESECONDARY,
        )
    val detailButton = headerRow.button(tr("Details anzeigen"), style = ButtonStyle.OUTLINEPRIMARY)

    archiveButton.onClick {
        AppScope.launch {
            val result =
                guarded {
                    if (contact.archivedAt == null) {
                        rpcService<ICrmService>().archiveContact(contact.id)
                    } else {
                        rpcService<ICrmService>().unarchiveContact(contact.id)
                    }
                }
            if (result != null) onChanged()
        }
    }

    val detailPanel = row.vPanel(spacing = 8) { hide() }
    var expanded = false
    detailButton.onClick {
        expanded = !expanded
        if (!expanded) {
            detailPanel.hide()
            return@onClick
        }
        detailPanel.removeAll()
        detailPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        detailPanel.show()
        AppScope.launch {
            val fresh = guarded { rpcService<ICrmService>().getContact(contact.id) } ?: return@launch
            detailPanel.removeAll()
            renderCrmContactDetail(detailPanel, fresh, canErase, onChanged)
        }
    }
}

private fun renderCrmContactDetail(
    panel: SimplePanel,
    contact: CrmContactDto,
    canErase: Boolean,
    onChanged: () -> Unit,
) {
    // ---- Stammdaten (+ "Bearbeiten" toggle, Art. 16 DSGVO) -------------------------------
    val infoHeaderRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    infoHeaderRow.div(tr("Stammdaten")) { addCssClasses("flex-grow-1 fw-bold") }
    val editButton = infoHeaderRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)

    val infoPanel = panel.vPanel(spacing = 2) { addCssClasses("small") }
    val editFormHolder = panel.vPanel(spacing = 6) { hide() }

    fun renderInfo() {
        infoPanel.removeAll()
        contact.email?.let { infoPanel.div(gettext("E-Mail: %1", it)) }
        contact.phone?.let { infoPanel.div(gettext("Telefon: %1", it)) }
        infoPanel.div(gettext("Adresse: %1", crmContactAddressLine(contact)))
        infoPanel.div(gettext("Rechtsgrundlage: %1", crmLawfulBasisLabel(contact.lawfulBasis)))
        contact.consentSource?.let { infoPanel.div(gettext("Einwilligung erteilt via: %1", it)) }
        if (contact.consentWithdrawnAt != null) {
            infoPanel.div(tr("Einwilligung wurde widerrufen.")) { addCssClass("text-danger") }
        } else if (contact.consentGivenAt != null) {
            val withdrawRow = infoPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            withdrawRow.div(tr("Einwilligung ist aktiv."))
            val withdrawButton = withdrawRow.button(tr("Einwilligung widerrufen"), style = ButtonStyle.OUTLINEDANGER)
            withdrawButton.onClick {
                AppScope.launch {
                    val result = guarded { rpcService<ICrmService>().withdrawConsent(contact.id) }
                    if (result != null) {
                        notifyInfo(tr("Einwilligung wurde als widerrufen erfasst."))
                        onChanged()
                    }
                }
            }
        }
        infoPanel.div(gettext("E-Mail-Ansprache erlaubt: %1", if (contact.mayReceiveEmail) tr("Ja") else tr("Nein")))
        infoPanel.div(gettext("Wiedervorlage fällig: %1", contact.retentionReviewDueAt.toString()))
    }
    renderInfo()

    var editing = false
    editButton.onClick {
        editing = !editing
        editFormHolder.removeAll()
        if (editing) {
            infoPanel.hide()
            editFormHolder.show()
            renderCrmContactEditForm(
                editFormHolder,
                contact,
                onSaved = {
                    editing = false
                    onChanged()
                },
                onCancel = {
                    editing = false
                    editFormHolder.removeAll()
                    editFormHolder.hide()
                    infoPanel.show()
                },
            )
        } else {
            infoPanel.show()
            editFormHolder.hide()
        }
    }

    // ---- Interaktions-Erfassung (modeless, EIN Pflichtfeld) ------------------------------
    panel.h2(tr("Neue Interaktion erfassen")) { addCssClass("h5") }
    val captureFormHolder = panel.vPanel(spacing = 0)

    // ---- Zeitleiste (neueste zuerst) -- erstellt VOR dem Formular referenziert, damit dessen
    // Erfolgs-Callback sie direkt neu laden kann, ohne eine Container-Indirektion zu brauchen.
    panel.h2(tr("Interaktionsverlauf")) { addCssClass("h5") }
    val timelinePanel = panel.vPanel(spacing = 4)

    renderCrmInteractionCaptureForm(captureFormHolder, contact.id) { renderCrmInteractionTimeline(timelinePanel, contact.id) }
    renderCrmInteractionTimeline(timelinePanel, contact.id)

    // ---- Datenschutz-Block (Art. 15/17 DSGVO) -- immer am ENDE, sichtbar abgesetzt --------
    panel.h2(tr("Datenschutz")) { addCssClass("h5") }
    val privacyPanel = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3 border-warning") }
    privacyPanel.link(tr("Auskunft exportieren (JSON)"), url = crmContactExportUrl(contact.id), target = "_blank")
    if (canErase) {
        val eraseButton = privacyPanel.button(tr("Löschen nach Art. 17 DSGVO"), style = ButtonStyle.OUTLINEDANGER)
        eraseButton.onClick {
            confirmWithTypedConfirmationDialog(
                title = tr("Kontakt endgültig löschen"),
                message =
                    tr(
                        "Dieser Kontakt und alle erfassten Interaktionen werden UNWIDERRUFLICH gelöscht -- " +
                            "keine Anonymisierung, ein echtes Löschen ohne Wiederherstellung.",
                    ),
                expectedText = contact.displayName,
            ) {
                AppScope.launch {
                    val result = guarded { rpcService<ICrmService>().eraseContact(contact.id) }
                    if (result != null) {
                        notifyInfo(tr("Kontakt wurde gelöscht."))
                        onChanged()
                    }
                }
            }
        }
    }
}

private fun renderCrmInteractionCaptureForm(
    panel: SimplePanel,
    contactId: String,
    onRecorded: () -> Unit,
) {
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
    val recordButton = form.button(tr("Interaktion speichern"), style = ButtonStyle.PRIMARY)

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
                summaryInput.value = null
                occurredAtInput.value = null
                onRecorded()
            }
        }
    }
}

/**
 * Real offset-based "Mehr laden" pagination (mirrors [renderCrmContactsScreen]'s list, same
 * motivation) -- a hard `limit = 100` with no way to reach older history previously made the
 * timeline silently drop everything past the 100th-newest interaction (review finding
 * "Kontaktliste bricht bei 200 Einträgen stillschweigend ab", second half).
 */
private fun renderCrmInteractionTimeline(
    panel: SimplePanel,
    contactId: String,
) {
    panel.removeAll()
    val statusText = panel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    val itemsPanel = panel.vPanel(spacing = 4)
    val loadMoreButton = panel.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }
    var loadedOffset = 0

    fun loadPage() {
        AppScope.launch {
            val interactions =
                guarded {
                    rpcService<ICrmService>().listInteractions(contactId, limit = CRM_INTERACTION_PAGE_SIZE, offset = loadedOffset)
                } ?: return@launch
            statusText.hide()
            if (interactions.isEmpty() && loadedOffset == 0) {
                itemsPanel.p(tr("Noch keine Interaktionen erfasst.")) { addCssClasses("text-muted small") }
                loadMoreButton.hide()
                return@launch
            }
            interactions.forEach { interaction -> renderCrmInteractionRow(itemsPanel, interaction) }
            loadedOffset += interactions.size
            if (interactions.size < CRM_INTERACTION_PAGE_SIZE) loadMoreButton.hide() else loadMoreButton.show()
        }
    }
    loadMoreButton.onClick { loadPage() }
    loadPage()
}

private fun renderCrmInteractionRow(
    panel: SimplePanel,
    interaction: CrmInteractionDto,
) {
    val row = panel.vPanel(spacing = 2) { addCssClasses("border-bottom pb-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.typeBadge(crmInteractionKindLabel(interaction.kind), "info")
    headerRow.div(interaction.occurredAt.toString()) { addCssClasses("text-muted small") }
    headerRow.div(gettext("erfasst von %1", interaction.recordedByDisplayName)) { addCssClasses("text-muted small flex-grow-1 text-end") }
    row.div(interaction.summary)
}

// ============================================================================================
// Creation form
// ============================================================================================

private fun renderCrmContactCreationForm(
    root: SimplePanel,
    onCreated: () -> Unit,
) {
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
    val createButton = panel.button(tr("Kontakt anlegen"), style = ButtonStyle.PRIMARY)

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
                displayNameInput.value = null
                emailInput.value = null
                phoneInput.value = null
                streetInput.value = null
                postalCodeInput.value = null
                cityInput.value = null
                countryInput.value = null
                typeSelect.value = ""
                basisSelect.value = ""
                consentSourceInput.value = null
                consentGivenAtInput.value = null
                onCreated()
            }
        }
    }
}

// ============================================================================================
// Edit form (Art. 16 DSGVO Berichtigung) -- toggled from `renderCrmContactDetail`'s "Bearbeiten"
// button. Pre-filled from the currently displayed [CrmContactDto], calls [ICrmService.updateContact]
// -- previously implemented server-side but entirely unreachable from the UI (review finding
// "updateContact ist implementiert, aber unerreichbar"). `externalDonorId`/`memberId` are NOT
// editable here (no UI anywhere lets an operator pick a member/external donor to link -- creating
// that picker is out of scope for this fix, same "Vollausbau ist eine eigene Welle wert" posture
// [renderCrmInteractionCaptureForm]'s own KDoc already documents for `occurredAt`) -- the update
// always resubmits the contact's EXISTING linkage unchanged, so an edit can never accidentally sever
// or move a member/donor link.
// ============================================================================================

private fun renderCrmContactEditForm(
    panel: SimplePanel,
    contact: CrmContactDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val displayNameInput = panel.text(label = tr("Name")).apply { value = contact.displayName }
    val emailInput = panel.text(label = tr("E-Mail (optional)")).apply { value = contact.email }
    val phoneInput = panel.text(label = tr("Telefon (optional)")).apply { value = contact.phone }
    val streetInput = panel.text(label = tr("Straße (optional)")).apply { value = contact.street }
    val postalCodeInput = panel.text(label = tr("PLZ (optional)")).apply { value = contact.postalCode }
    val cityInput = panel.text(label = tr("Ort (optional)")).apply { value = contact.city }
    val countryInput = panel.text(label = tr("Land (optional)")).apply { value = contact.country }
    val typeOptions = CrmContactType.entries.map { it.name to crmContactTypeLabel(it) }
    val typeSelect = panel.select(options = typeOptions, value = contact.contactType.name, label = tr("Typ"))
    val basisOptions = CrmLawfulBasis.entries.map { it.name to crmLawfulBasisLabel(it) }
    val basisSelect = panel.select(options = basisOptions, value = contact.lawfulBasis.name, label = tr("Rechtsgrundlage (Art. 6 DSGVO)"))
    val isInitiallyConsent = contact.lawfulBasis == CrmLawfulBasis.CONSENT
    val consentSourceInput =
        panel.text(label = tr("Herkunft der Einwilligung (z. B. \"Infostand Braunschweig\")")).apply {
            value = contact.consentSource
            visible = isInitiallyConsent
        }
    val consentGivenAtInput =
        panel.text(label = tr("Zeitpunkt der Einwilligung (ISO, z. B. 2026-09-14T10:00)")).apply {
            value = contact.consentGivenAt?.toString()
            visible = isInitiallyConsent
        }
    // Art. 16 DSGVO correction path for a WRONGLY recorded consent (review finding "eine
    // irrtümlich erfasste Einwilligung lässt sich über keinen Codepfad wieder entfernen") -- only
    // offered once there is actually evidence to remove, and only while the basis is no longer
    // CONSENT (an active CONSENT basis requires exactly this evidence, see
    // `CrmContactPolicy.validate`). Distinct from simply blanking the two fields above, which
    // (deliberately) means "keep the existing evidence" -- see `CrmContactStore.update`'s "Consent
    // evidence is preserved" KDoc.
    val hasExistingConsentEvidence = contact.consentSource != null || contact.consentGivenAt != null
    val clearConsentEvidenceCheck =
        panel.checkBox(label = tr("Fälschlich erfasste Einwilligung entfernen (Art. 16 DSGVO)")) { hide() }

    fun updateConsentFieldVisibility(basisValue: String?) {
        val isConsent = basisValue == CrmLawfulBasis.CONSENT.name
        consentSourceInput.visible = isConsent
        consentGivenAtInput.visible = isConsent
        if (hasExistingConsentEvidence && !isConsent) {
            clearConsentEvidenceCheck.show()
        } else {
            clearConsentEvidenceCheck.hide()
            clearConsentEvidenceCheck.value = false
        }
    }
    updateConsentFieldVisibility(contact.lawfulBasis.name)
    basisSelect.subscribe { value -> updateConsentFieldVisibility(value) }
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val saveButton = buttonRow.button(tr("Änderungen speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = buttonRow.button(tr("Abbrechen"), style = ButtonStyle.OUTLINESECONDARY)

    saveButton.onClick {
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

        saveButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ICrmService>().updateContact(
                        contact.id,
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
                            consentSource =
                                if (clearConsentEvidenceCheck.value) null else consentSourceInput.value?.trim()?.takeIf { it.isNotBlank() },
                            consentGivenAt = consentGivenAt,
                            externalDonorId = contact.externalDonorId,
                            memberId = contact.memberId,
                            clearConsentEvidence = clearConsentEvidenceCheck.value,
                        ),
                    )
                }
            saveButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Kontakt \"%1\" wurde aktualisiert.", displayName))
                onSaved()
            }
        }
    }
    cancelButton.onClick { onCancel() }
}

// ============================================================================================
// Pure helpers -- covered by CrmLabelsTest.kt-adjacent tests
// ============================================================================================

fun crmContactExportUrl(contactId: String): String = "/api/dsgvo/crm-contacts/$contactId/export"

/** Shared "Straße, PLZ Ort, Land" address-line formatting -- mirrors `donorAddressLine` (DonorsScreen.kt). */
fun crmContactAddressLine(contact: CrmContactDto): String {
    val streetLine = contact.street
    val cityLine = listOfNotNull(contact.postalCode, contact.city).joinToString(" ").takeIf { it.isNotBlank() }
    val parts = listOfNotNull(streetLine, cityLine, contact.country).filter { it.isNotBlank() }
    return if (parts.isEmpty()) gettext("Keine Adresse hinterlegt") else parts.joinToString(", ")
}

/** "Noch nie" / "heute" / "vor N Tagen" / "vor N Monaten" / "vor N Jahren" -- coarse, deliberately not to-the-minute precise. */
fun crmLastInteractionRelativeText(lastInteractionAt: LocalDateTime?): String {
    if (lastInteractionAt == null) return gettext("Noch kein Kontakt")
    val now = Clock.System.now()
    val then = lastInteractionAt.toInstant(TimeZone.currentSystemDefault())
    val days = (now - then).inWholeDays
    return when {
        days <= 0 -> gettext("Letzter Kontakt: heute")
        days == 1L -> gettext("Letzter Kontakt: gestern")
        days < 30 -> gettext("Letzter Kontakt vor %1 Tagen", days.toString())
        days < 365 -> gettext("Letzter Kontakt vor %1 Monaten", (days / 30).toString())
        else -> gettext("Letzter Kontakt vor %1 Jahren", (days / 365).toString())
    }
}
