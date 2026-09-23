package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceConfigDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentInput
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatusSets
import network.lapis.cloud.shared.domain.VolunteerAllowanceRules
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale", Selbstbedienungsseite -- mirrors
 * `TravelExpenseScreen`'s house style. `requireAuth` (jedes authentifizierte Mitglied) -- siehe
 * `Routes.VOLUNTEER_ALLOWANCES` KDoc. Erstellt IMMER für sich selbst (kein "im Namen von"-Picker
 * in dieser Selbstbedienungssicht, gleiche Vereinfachung wie `TravelExpenseScreen`).
 *
 * R24/R24B/R29/R34 (W4d batch 5): das Zahlungsformular (Kategorie/Betrag/Tätigkeitsbeschreibung/
 * Zahlungsdatum, für Entwurf-Anlage UND -Bearbeitung dasselbe Formular) ist ein [LapisForm], 1:1
 * nach `TravelExpenseScreen.renderReportHeaderForm`s Vorbild -- die Kategorie ist ein
 * `selectField` (R24B), NICHT ein Filter: sie ist ein Pflichtfeld des Zahlungsantrags. Jeder
 * schreibende `AppScope.launch` (Einreichen, Verwerfen, Zurückziehen, Selbstauskunft bestätigen)
 * läuft durch `runGuardedAction`; der Ladevorgang selbst zeigt bei einem gescheiterten ersten
 * Abruf einen Fehlerzustand mit Wiederholung statt eines leeren Panels (R34, Vorbild
 * `TravelExpenseScreen.reload`).
 */
fun renderVolunteerAllowanceScreen(
    container: SimplePanel,
    focusedPaymentId: String?,
) {
    val currentMemberId = AppState.session?.memberId
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 900.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Ehrenamts- und Übungsleiterpauschalen"))

    val configBanner = root.vPanel(spacing = 4)
    val editorPanel = root.vPanel(spacing = 10)
    root.h2(tr("Meine Zahlungen")) { addCssClasses("h5 mt-3") }
    val listPanel = root.vPanel(spacing = 10)

    fun reload() {
        editorPanel.removeAll()
        listPanel.removeAll()
        AppScope.launch {
            // R34: ein fehlgeschlagener Ladevorgang ist ein Fehlerzustand mit Wiederholung, kein leeres Panel.
            val config = guarded { rpcService<IVolunteerAllowanceService>().getVolunteerAllowanceConfig() }
            if (config == null) {
                configBanner.removeAll()
                editorPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }
            val payments = guarded { rpcService<IVolunteerAllowanceService>().listMyPayments() }
            if (payments == null) {
                listPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }

            configBanner.removeAll()
            renderConfigBanner(configBanner, config)

            editorPanel.removeAll()
            val draft = payments.firstOrNull { it.status == VolunteerAllowancePaymentStatus.DRAFT }
            if (draft != null) {
                renderDraftEditor(editorPanel, draft, ::reload)
            } else {
                renderNewDraftButton(editorPanel, ::reload)
            }

            listPanel.removeAll()
            val ordered =
                payments.filter { it.status != VolunteerAllowancePaymentStatus.DRAFT }.sortedByDescending {
                    it.createdAt
                        .toString()
                }
            if (ordered.isEmpty()) {
                listPanel.p(tr("Noch keine eingereichten Zahlungen.")) { addCssClasses("text-muted small") }
            }
            ordered.forEach { payment -> renderOwnPaymentCard(listPanel, payment, focusedPaymentId, currentMemberId, ::reload) }
        }
    }
    reload()
}

private fun renderConfigBanner(
    panel: SimplePanel,
    config: VolunteerAllowanceConfigDto,
) {
    panel.p(
        gettext(
            "Übungsleiterpauschale bis %1 pro Jahr · Ehrenamtspauschale bis %2 pro Jahr",
            formatMoney(config.instructorCap),
            formatMoney(config.honoraryCap),
        ),
    ) { addCssClasses("text-muted small") }
    panel.p(
        tr(
            "Diese Beträge sind ein Gesetzesdatum und werden nicht automatisch an Gesetzesänderungen angepasst -- " +
                "bitte gegen den aktuellen Gesetzestext prüfen.",
        ),
    ) { addCssClasses("text-muted small") }
    if (!config.expenseAccountConfigured || !config.bankAccountConfigured) {
        panel.p(
            tr(
                "Eine Zahlung ist möglich, aber die Buchung scheitert, bis eine Administratorin oder ein " +
                    "Administrator die Kontenzuordnung vervollständigt.",
            ),
        ) { addCssClasses("alert alert-warning") }
    }
}

private fun renderNewDraftButton(
    panel: SimplePanel,
    onChanged: () -> Unit,
) {
    val button = panel.button(tr("Neue Zahlung beantragen"), style = ButtonStyle.PRIMARY)
    button.onClick {
        val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
        button.hide()
        renderPaymentForm(formPanel, null) { category, amount, description, date ->
            val result =
                guarded {
                    rpcService<IVolunteerAllowanceService>().createDraft(
                        AppState.session?.memberId.orEmpty(),
                        VolunteerAllowancePaymentInput(
                            category = category,
                            amount = amount,
                            activityDescription = description,
                            paymentDate = date,
                        ),
                    )
                }
            if (result != null) onChanged()
        }
    }
}

private fun renderDraftEditor(
    panel: SimplePanel,
    draft: VolunteerAllowancePaymentDto,
    onChanged: () -> Unit,
) {
    val formPanel = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    formPanel.div(tr("Entwurf")) { addCssClasses("fw-bold") }
    renderPaymentForm(formPanel, draft) { category, amount, description, date ->
        val result =
            guarded {
                rpcService<IVolunteerAllowanceService>().updateDraft(
                    draft.id,
                    VolunteerAllowancePaymentInput(
                        category = category,
                        amount = amount,
                        activityDescription = description,
                        paymentDate = date,
                    ),
                )
            }
        if (result != null) onChanged()
    }
    val buttonsRow = formPanel.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val submitButton = buttonsRow.button(tr("Einreichen"), style = ButtonStyle.SUCCESS)
    val withdrawButton = buttonsRow.button(tr("Verwerfen"), style = ButtonStyle.OUTLINEDANGER)
    submitButton.onClick {
        runGuardedAction(submitButton) {
            val result = guarded { rpcService<IVolunteerAllowanceService>().submitPayment(draft.id) }
            if (result != null) {
                notifySuccess(tr("Zahlung eingereicht."))
                onChanged()
            }
        }
    }
    withdrawButton.onClick {
        runGuardedAction(withdrawButton) {
            val result = guarded { rpcService<IVolunteerAllowanceService>().withdrawPayment(draft.id) }
            if (result != null) {
                notifySuccess(tr("Entwurf verworfen."))
                onChanged()
            }
        }
    }
}

// R24/R24B (W4d batch 5): migrated to the form grammar -- Kategorie (required selectField,
// UNVERAENDERLICH auf einer bestehenden Zahlung, siehe VolunteerAllowanceCategory KDoc), Betrag
// (required text, money), Tätigkeitsbeschreibung (required textArea, min/max Zeichen) und
// Zahlungsdatum (required text, ISO-Datum) -- 1:1 nach `TravelExpenseScreen
// .renderReportHeaderForm`s Vorbild, dasselbe Formular für Entwurf-Anlage UND -Bearbeitung.
private fun renderPaymentForm(
    panel: SimplePanel,
    existing: VolunteerAllowancePaymentDto?,
    onSave: suspend (category: VolunteerAllowanceCategory, amount: Decimal, description: String, date: LocalDate) -> Unit,
) {
    val form = panel.lapisForm()
    val categoryOptions = VolunteerAllowanceCategory.entries.map { it.name to volunteerAllowanceCategoryLabel(it) }
    val categoryField =
        form.selectField(
            label = tr("Kategorie"),
            options = categoryOptions,
            value = (existing?.category ?: VolunteerAllowanceCategory.HONORARY).name,
            required = true,
            init = {
                // Kategorie UNVERAENDERLICH auf einer bestehenden Zahlung -- siehe VolunteerAllowanceCategory KDoc.
                it.disabled = existing != null
            },
        )
    val amountField =
        form.textField(
            label = tr("Betrag (EUR)"),
            value = existing?.amount?.toString(),
            required = true,
            hint = tr("Beispiel: 1234,56."),
            rule = { volunteerAllowanceAmountCheck(it) },
        )
    val descriptionField =
        form.textAreaField(
            label = tr("Tätigkeitsbeschreibung"),
            rows = 2,
            value = existing?.activityDescription,
            required = true,
            rule = { volunteerAllowanceDescriptionCheck(it) },
            init = { it.maxlength = VolunteerAllowanceRules.MAX_ACTIVITY_DESCRIPTION_LENGTH },
        )
    val dateField =
        form.textField(
            label = tr("Zahlungsdatum (JJJJ-MM-TT)"),
            value = existing?.paymentDate?.toString(),
            required = true,
            hint = tr("Beispiel: 2026-03-14."),
            rule = { FormRules.isoDate(it) },
        )
    val saveButton = Button(if (existing == null) tr("Entwurf anlegen") else tr("Speichern"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val category = checkNotNull(VolunteerAllowanceCategory.entries.firstOrNull { it.name == categoryField.value })
            val amount = (parseAmountInput(amountField.value, allowZero = false, enforceMaxAmount = false) as AmountInput.Valid).value
            val description = descriptionField.value.trim()
            val date = LocalDate.parse(dateField.value.trim())
            onSave(category, amount, description, date)
        }
    }
}

/**
 * Feldregel für den Zahlbetrag: [parseAmountInput] MIT der Obergrenze
 * [VolunteerAllowanceRules.MAX_PAYMENT_AMOUNT] (dieselbe Grenze zieht der Server, siehe
 * `VolunteerAllowanceService`). Gleiches Muster wie `travelExpenseLineAmountCheck`.
 */
internal fun volunteerAllowanceAmountCheck(value: String): FieldCheck =
    when (val parsed = parseAmountInput(value, allowZero = false, enforceMaxAmount = false)) {
        is AmountInput.Empty -> FieldCheck.Ok
        is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
        is AmountInput.Valid ->
            if (parsed.value.toDouble() > VolunteerAllowanceRules.MAX_PAYMENT_AMOUNT) {
                FieldCheck.Invalid(
                    gettext(
                        "Der Betrag ist zu groß (höchstens %1).",
                        formatMoney(VolunteerAllowanceRules.MAX_PAYMENT_AMOUNT.toDouble().toDecimal()),
                    ),
                )
            } else {
                FieldCheck.Ok
            }
    }

/**
 * Feldregel für die Tätigkeitsbeschreibung: mindestens [VolunteerAllowanceRules
 * .MIN_ACTIVITY_DESCRIPTION_LENGTH], höchstens [VolunteerAllowanceRules
 * .MAX_ACTIVITY_DESCRIPTION_LENGTH] Zeichen nach `trim()` -- spiegelt
 * `VolunteerAllowanceService.createDraft`s Prüfung; der Server bleibt Autorität.
 */
internal fun volunteerAllowanceDescriptionCheck(value: String): FieldCheck {
    val trimmed = value.trim()
    return when {
        trimmed.length < VolunteerAllowanceRules.MIN_ACTIVITY_DESCRIPTION_LENGTH ->
            FieldCheck.Invalid(
                gettext(
                    "Bitte eine Tätigkeitsbeschreibung mit mindestens %1 Zeichen angeben.",
                    VolunteerAllowanceRules.MIN_ACTIVITY_DESCRIPTION_LENGTH,
                ),
            )
        else -> FormRules.maxLength(trimmed, VolunteerAllowanceRules.MAX_ACTIVITY_DESCRIPTION_LENGTH)
    }
}

private fun renderOwnPaymentCard(
    panel: SimplePanel,
    payment: VolunteerAllowancePaymentDto,
    focusedPaymentId: String?,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card =
        panel.vPanel(spacing = 6) {
            addCssClasses("border rounded p-3")
            if (payment.id == focusedPaymentId) addCssClasses("border-primary")
        }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(volunteerAllowanceCategoryLabel(payment.category), volunteerAllowanceCategoryColor(payment.category))
    headerRow.statusBadge(volunteerAllowanceStatusLabel(payment.status), volunteerAllowanceStatusColor(payment.status))
    // Requested-on-behalf-of-another-member rows are a legitimate BOARD/ADMIN-only RPC path
    // (createDraft(subjectMemberId = someone else), see IVolunteerAllowanceService KDoc) that this
    // self-service list also surfaces to the REQUESTER. Without this badge the requester's own card
    // gave no indication the payment is about someone else at all -- the review MAJOR finding this
    // fixes: a requester clicking "Selbstauskunft bestätigen" below on such a card would otherwise
    // silently self-declare in THEIR OWN name instead of the subject's, and that mistake cannot be
    // undone (no delete endpoint for a self-declaration, `uq_vasd_member_category_year` then blocks
    // the requester from ever giving their own real declaration for that category/year).
    if (payment.requestedBy != payment.subjectMemberId) {
        // Viewer-aware wording: the requester needs to see WHO this is for, the subject needs to
        // see WHO filed it for them -- naming the viewer's own display name back to them would be
        // confusing noise, not the clarification this badge exists for.
        val label =
            if (currentMemberId == payment.subjectMemberId) {
                gettext("Gestellt von %1", payment.requestedByDisplayName)
            } else {
                gettext("Für %1 gestellt", payment.subjectDisplayName)
            }
        headerRow.typeBadge(label, "secondary")
    }
    headerRow.div(formatMoney(payment.amount)) { addCssClasses("fw-bold flex-grow-1") }
    card.div(payment.paymentDate.toString()) { addCssClasses("text-muted small") }
    card.untrustedDiv(payment.activityDescription)
    if (payment.decisionNote != null) {
        card.div(gettext("Begründung: %1", payment.decisionNote)) { addCssClasses("text-muted small") }
    }

    // APPROVED with executionError=self_declaration_missing is the documented in-app
    // catch-up path (docs/architecture/volunteer-allowance.adoc "the declaration can be
    // supplied afterwards"): decidePayment/VolunteerAllowanceExecution both refuse to post
    // without a declaration, so an APPROVED row here is guaranteed missing one. Without this
    // branch the subject member had NO way to self-declare and every board retryPosting kept
    // failing with the same code -- ON_PAPER by BOARD/ADMIN was the only escape hatch left.
    when (payment.status) {
        VolunteerAllowancePaymentStatus.APPROVED -> {
            val errorBox = card.div(volunteerAllowancePostingErrorMessage(payment.executionError)) { addCssClasses("alert alert-danger") }
            renderSelfDeclarationArea(card, payment, currentMemberId, errorBox, onChanged)
        }
        VolunteerAllowancePaymentStatus.EXECUTED -> {
            card.div(volunteerAllowancePayoutDisclaimer()) { addCssClasses("text-muted small") }
            renderSelfDeclarationArea(card, payment, currentMemberId, errorBox = null, onChanged = onChanged)
        }
        VolunteerAllowancePaymentStatus.DRAFT,
        VolunteerAllowancePaymentStatus.REQUESTED,
        VolunteerAllowancePaymentStatus.REJECTED,
        VolunteerAllowancePaymentStatus.WITHDRAWN,
        -> Unit
    }

    if (payment.status in VolunteerAllowancePaymentStatusSets.WITHDRAWABLE) {
        val withdrawButton = card.button(tr("Zurückziehen"), style = ButtonStyle.OUTLINEDANGER)
        withdrawButton.onClick {
            runGuardedAction(withdrawButton) {
                val result = guarded { rpcService<IVolunteerAllowanceService>().withdrawPayment(payment.id) }
                if (result != null) {
                    notifySuccess(tr("Zurückgezogen."))
                    onChanged()
                }
            }
        }
    }
}

/**
 * Gate for [renderSelfDeclarationSection]: renders it ONLY when the viewer IS the payment's
 * receiving person (Review MAJOR finding) -- a BOARD/ADMIN requester viewing a payment they
 * created on someone else's behalf (`createDraft(subjectMemberId = someone else)`) must NEVER see
 * this button, because `declareSelf` always records the declaration for the CALLER
 * (`VolunteerAllowanceService.declareSelf`), never for [VolunteerAllowancePaymentDto
 * .subjectMemberId]. Everyone else gets a hint instead of a silent wrong-person recording that
 * cannot be undone afterwards through THIS screen -- `declareSelf` (IN_APP) still has no
 * delete/void endpoint at all (see [network.lapis.cloud.shared.rpc.IVolunteerAllowanceService
 * .voidPaperDeclaration] KDoc for why: it deliberately only ever touches `ON_PAPER` rows, never a
 * member's own asserted `IN_APP` declaration). An `ON_PAPER` mistake IS now correctable by an
 * ADMIN via that endpoint, exposed in `VolunteerAllowanceApprovalsScreen.renderPaperDeclarationForm`.
 */
private fun renderSelfDeclarationArea(
    card: SimplePanel,
    payment: VolunteerAllowancePaymentDto,
    currentMemberId: String?,
    errorBox: Div?,
    onChanged: () -> Unit,
) {
    if (!volunteerAllowanceShowsSelfDeclaration(payment.status, payment.subjectMemberId, currentMemberId)) {
        card.div(volunteerAllowanceForeignSubjectDeclarationHint()) { addCssClasses("text-muted small fst-italic") }
        return
    }
    renderSelfDeclarationSection(card, payment, errorBox, onChanged)
}

/**
 * Selbstauskunfts-Checkbox mit Volltext für IN_APP -- NUR hier, in der eigenen Sicht. NIE im
 * Vorstandsformular (Normans Fälschungsszenario) -- siehe `VolunteerAllowanceApprovalsScreen
 * .renderPaperDeclarationForm` KDoc für den Board-Weg. Only ever called via
 * [renderSelfDeclarationArea], which has already verified the viewer is the subject.
 */
private fun renderSelfDeclarationSection(
    card: SimplePanel,
    payment: VolunteerAllowancePaymentDto,
    errorBox: Div?,
    onChanged: () -> Unit,
) {
    AppScope.launch {
        val yearStatus =
            guarded {
                rpcService<IVolunteerAllowanceService>().getYearStatus(payment.subjectMemberId, payment.category, payment.paymentDate.year)
            } ?: return@launch
        val declaration = yearStatus.declaration
        if (declaration != null) {
            card.div(volunteerAllowanceDeclarationBadge(declaration)) { addCssClasses("small") }
            // The declaration now exists, but the payment row itself is stale until the board's
            // NEXT retryPosting -- without this, the red "declaration missing" box above stays up
            // right next to the just-rendered "confirmed" badge, and the member cannot tell whether
            // their click had any effect (review INFORMATIONAL finding).
            val declarationClearsTheExecutionError =
                payment.executionError?.let { parseVolunteerAllowancePostingErrorCode(it) } ==
                    VolunteerAllowancePostingErrorCode.SELF_DECLARATION_MISSING
            if (errorBox != null && declarationClearsTheExecutionError) {
                errorBox.removeCssClass("alert-danger")
                errorBox.addCssClass("alert-warning")
                errorBox.content = volunteerAllowanceSelfDeclarationPendingRepostMessage()
            }
            return@launch
        }
        val declareRow = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
        declareRow.div(
            tr(
                "Bitte bestätigen Sie, dass Sie den gesetzlichen Freibetrag für diese Kategorie in diesem Kalenderjahr " +
                    "nicht bereits anderswo ausgeschöpft haben.",
            ),
        ) { addCssClasses("small") }
        val declareButton = declareRow.button(tr("Selbstauskunft bestätigen"), style = ButtonStyle.OUTLINEPRIMARY)
        declareButton.onClick {
            runGuardedAction(declareButton) {
                val result =
                    guarded {
                        rpcService<IVolunteerAllowanceService>().declareSelf(payment.category, payment.paymentDate.year)
                    }
                if (result != null) {
                    notifySuccess(tr("Selbstauskunft erfasst."))
                    onChanged()
                }
            }
        }
    }
}

// ── Pure, testable prädikate ──────────────────────────────────────────────────────────────

/**
 * Only APPROVED/EXECUTED rows carry a self-declaration need (see `renderOwnPaymentCard`'s own
 * `when`), and even then ONLY when [currentMemberId] IS [subjectMemberId] -- `declareSelf` always
 * records for the caller, never for an arbitrary subject (Review MAJOR finding: a BOARD/ADMIN
 * requester must never see this button on a payment filed on someone else's behalf).
 */
internal fun volunteerAllowanceShowsSelfDeclaration(
    status: VolunteerAllowancePaymentStatus,
    subjectMemberId: String,
    currentMemberId: String?,
): Boolean =
    (status == VolunteerAllowancePaymentStatus.APPROVED || status == VolunteerAllowancePaymentStatus.EXECUTED) &&
        subjectMemberId == currentMemberId
