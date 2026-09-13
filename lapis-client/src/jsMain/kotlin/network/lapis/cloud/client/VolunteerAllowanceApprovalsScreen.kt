package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.VolunteerAllowanceCapAcknowledgmentInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationInput
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService

/**
 * Welle V1.4.12, Vorstands-Warteschlange -- mirrors `TravelExpenseApprovalsScreen`'s house style.
 * Route-Gate ist BOARD/ADMIN, **nicht** TREASURER -- siehe `Routes.VOLUNTEER_ALLOWANCE_APPROVALS` KDoc.
 */
fun renderVolunteerAllowanceApprovalsScreen(container: SimplePanel) {
    val currentMemberId = AppState.session?.memberId
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Ehrenamtspauschalen-Freigaben"))

    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val statusOptions =
        listOf("" to tr("Alle Status")) +
            VolunteerAllowancePaymentStatus.entries.filter { it != VolunteerAllowancePaymentStatus.DRAFT }.map {
                it.name to volunteerAllowanceStatusLabel(it)
            }
    val statusSelect = filterRow.select(options = statusOptions, value = "", label = tr("Status"))
    val filterButton = filterRow.button(tr("Filtern"), style = ButtonStyle.OUTLINESECONDARY)

    val listPanel = root.vPanel(spacing = 8)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var cursor: VolunteerAllowancePageCursor? = null

    fun loadPage(reset: Boolean) {
        if (reset) {
            listPanel.removeAll()
            cursor = null
        }
        val status = parseOptionalEnum<VolunteerAllowancePaymentStatus>(statusSelect.value)
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IVolunteerAllowanceService>().listPayments(
                        status = status,
                        afterSubmittedAt = cursor?.submittedAt,
                        afterId = cursor?.id,
                    )
                } ?: return@launch
            if (page.isEmpty()) {
                if (reset) listPanel.p(tr("Keine Zahlungen für diese Filter gefunden."))
                loadMoreButton.hide()
                return@launch
            }
            page.forEach { payment -> renderVolunteerAllowanceApprovalCard(listPanel, payment, currentMemberId) { loadPage(reset = true) } }
            cursor = nextVolunteerAllowanceCursor(page)
            if (volunteerAllowanceHasMorePages(page.size)) loadMoreButton.show() else loadMoreButton.hide()
        }
    }
    filterButton.onClick { loadPage(reset = true) }
    loadMoreButton.onClick { loadPage(reset = false) }
    loadPage(reset = true)
}

private fun renderVolunteerAllowanceApprovalCard(
    panel: SimplePanel,
    payment: VolunteerAllowancePaymentDto,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(volunteerAllowanceCategoryLabel(payment.category), volunteerAllowanceCategoryColor(payment.category))
    headerRow.statusBadge(volunteerAllowanceStatusLabel(payment.status), volunteerAllowanceStatusColor(payment.status))
    headerRow.div(payment.subjectDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    if (payment.requestedBy != payment.subjectMemberId) {
        headerRow.typeBadge(gettext("Im Namen von %1 gestellt", payment.requestedByDisplayName), "secondary")
    }
    headerRow.div(formatMoney(payment.amount)) { addCssClasses("fw-bold") }

    card.div(payment.paymentDate.toString()) { addCssClasses("text-muted small") }
    card.div(payment.activityDescription)

    if (payment.decisionNote != null) {
        card.div(gettext("Begründung: %1", payment.decisionNote)) { addCssClasses("text-muted small") }
    }
    if (payment.status == VolunteerAllowancePaymentStatus.EXECUTED) {
        card.div(volunteerAllowancePayoutDisclaimer()) { addCssClasses("text-muted small") }
        val exceedingAmount = payment.exceedingAmountSnapshot
        if (exceedingAmount != null && exceedingAmount.toDouble() > 0.0) {
            card.div(
                gettext(
                    "%1 steuerfrei / %2 steuer- und ggf. sozialversicherungspflichtig",
                    formatMoney(payment.freeAmountSnapshot ?: payment.amount),
                    formatMoney(exceedingAmount),
                ),
            ) { addCssClasses("text-muted small") }
        }
    }

    if (payment.subjectMemberId == currentMemberId || payment.requestedBy == currentMemberId) {
        card.div(tr("Eigene Zahlung — Entscheidung durch ein anderes Vorstandsmitglied.")) {
            addCssClasses("text-muted small fst-italic")
        }
        return
    }

    when (payment.status) {
        VolunteerAllowancePaymentStatus.REQUESTED -> renderVolunteerAllowanceDecisionSection(card, payment, onChanged, isRetry = false)
        VolunteerAllowancePaymentStatus.APPROVED -> {
            card.div(volunteerAllowancePostingErrorMessage(payment.executionError)) { addCssClasses("alert alert-danger") }
            renderVolunteerAllowanceDecisionSection(card, payment, onChanged, isRetry = true)
        }
        VolunteerAllowancePaymentStatus.REJECTED,
        VolunteerAllowancePaymentStatus.EXECUTED,
        VolunteerAllowancePaymentStatus.WITHDRAWN,
        VolunteerAllowancePaymentStatus.DRAFT,
        -> Unit
    }
}

/**
 * The year-progress bar, self-declaration state and cap-acknowledgment checkbox, then the
 * decision buttons. Loaded asynchronously (needs `getYearStatus`) -- the card shows a loading line
 * until it resolves.
 */
private fun renderVolunteerAllowanceDecisionSection(
    card: SimplePanel,
    payment: VolunteerAllowancePaymentDto,
    onChanged: () -> Unit,
    isRetry: Boolean,
) {
    val section = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    val loadingLine = section.p(tr("Jahresstatus wird geladen …")) { addCssClasses("text-muted small") }

    AppScope.launch {
        val yearStatus =
            guarded {
                rpcService<IVolunteerAllowanceService>().getYearStatus(payment.subjectMemberId, payment.category, payment.paymentDate.year)
            }
        loadingLine.hide()
        if (yearStatus == null) return@launch

        section.div(
            gettext(
                "%1 verbraucht + %2 diese Zahlung -> %3 %4 (Jahresdeckel %5)",
                formatMoney(yearStatus.postedTotalInThisOrganization),
                formatMoney(payment.amount),
                formatMoney(yearStatus.remainingInThisOrganization),
                volunteerAllowanceRemainingLabel(),
                formatMoney(yearStatus.annualCap),
            ),
        ) { addCssClasses("small") }
        section.div(volunteerAllowanceForeignOrgsDisclaimer()) { addCssClasses("text-muted small") }

        val declaration = yearStatus.declaration
        if (declaration != null) {
            section.div(volunteerAllowanceDeclarationBadge(declaration)) { addCssClasses("small") }
            // Security-Fund (INFORMATIONAL: "keine Korrektur-/Widerrufsmöglichkeit für eine falsch
            // oder missbräuchlich erfasste Papier-Selbstauskunft") -- ADMIN only (stricter than
            // this whole screen's own BOARD/ADMIN route gate, see
            // IVolunteerAllowanceService.voidPaperDeclaration KDoc), and only ever for an ON_PAPER
            // declaration -- the subject's own IN_APP assertion is never voidable from here.
            if (declaration.source == VolunteerAllowanceDeclarationSource.ON_PAPER && AppState.hasRole(AccountRole.ADMIN)) {
                renderVoidPaperDeclarationButton(section, declaration) { onChanged() }
            }
        } else {
            section.div(tr("Selbstauskunft fehlt noch.")) { addCssClasses("text-danger small") }
            renderPaperDeclarationForm(section, payment) { onChanged() }
        }

        val projectedTotal = yearStatus.postedTotalInThisOrganization.toDouble() + payment.amount.toDouble()
        val exceedsCap = projectedTotal > yearStatus.annualCap.toDouble()

        val noteInput = section.textArea(label = tr("Entscheidungsnotiz (Pflicht)"), rows = 2) { maxlength = 1000 }
        val errorBox =
            section.div().apply {
                addCssClass("text-danger")
                hide()
            }

        var ackCheck: CheckBox? = null
        var disclaimerVersion = ""
        var disclaimerSha256 = ""
        if (exceedsCap) {
            val disclaimerPanel = section.vPanel(spacing = 4) { addCssClasses("border rounded p-2 small") }
            // Disabled until the disclaimer text has actually loaded -- otherwise the box is
            // ackable while empty, so a fast click sends disclaimerVersion="" /
            // disclaimerSha256="" and decidePayment rejects it with an opaque ConflictException
            // (VolunteerAllowanceCapDisclaimer.matches requires the CURRENT version/sha256).
            val checkBox = section.checkBox(label = tr("Rechtshinweis zur Kenntnis genommen"))
            checkBox.disabled = true
            ackCheck = checkBox

            // Named + re-invokable rather than a one-shot `AppScope.launch { ... }`: a transient
            // failure (network blip, session refresh, 500) must not leave the checkbox disabled
            // and the box stuck on "Wird geladen …" forever with no way out but a full page reload
            // (review INFORMATIONAL finding).
            fun loadDisclaimer() {
                disclaimerPanel.removeAll()
                disclaimerPanel.div(tr("Wird geladen …"))
                AppScope.launch {
                    val disclaimer = guarded { rpcService<IVolunteerAllowanceService>().getCapDisclaimer() }
                    if (disclaimer == null) {
                        disclaimerPanel.removeAll()
                        disclaimerPanel.div(tr("Der Rechtshinweis konnte nicht geladen werden.")) { addCssClasses("text-danger") }
                        val retryButton = disclaimerPanel.button(tr("Erneut laden"), style = ButtonStyle.OUTLINESECONDARY)
                        retryButton.onClick { loadDisclaimer() }
                        return@launch
                    }
                    disclaimerPanel.removeAll()
                    disclaimerPanel.div(disclaimer.text)
                    disclaimerVersion = disclaimer.version
                    disclaimerSha256 = disclaimer.sha256
                    checkBox.disabled = false
                }
            }
            loadDisclaimer()
        }

        val buttonsRow = section.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
        val approveButton =
            buttonsRow.button(if (isRetry) tr("Buchung wiederholen") else tr("Genehmigen und buchen"), style = ButtonStyle.SUCCESS)
        val rejectButton = buttonsRow.button(tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)

        fun requireNote(): String? {
            val note = noteInput.value?.trim()
            if (note.isNullOrBlank()) {
                errorBox.content = tr("Bitte eine Entscheidungsnotiz eingeben.")
                errorBox.show()
                return null
            }
            errorBox.hide()
            return note
        }

        approveButton.onClick {
            if (isRetry) {
                approveButton.disabled = true
                rejectButton.disabled = true
                AppScope.launch {
                    try {
                        val result = guarded { rpcService<IVolunteerAllowanceService>().retryPosting(payment.id) }
                        if (result != null) {
                            notifySuccess(tr("Buchung wiederholt."))
                            onChanged()
                        }
                    } finally {
                        approveButton.disabled = false
                        rejectButton.disabled = false
                    }
                }
                return@onClick
            }
            val note = requireNote() ?: return@onClick
            if (exceedsCap && ackCheck?.value != true) {
                errorBox.content = tr("Bitte den Rechtshinweis zur Kenntnis nehmen, bevor Sie genehmigen.")
                errorBox.show()
                return@onClick
            }
            approveButton.disabled = true
            rejectButton.disabled = true
            AppScope.launch {
                try {
                    val ack =
                        if (exceedsCap) {
                            VolunteerAllowanceCapAcknowledgmentInput(
                                disclaimerVersion = disclaimerVersion,
                                disclaimerSha256 = disclaimerSha256,
                            )
                        } else {
                            null
                        }
                    val result = guarded { rpcService<IVolunteerAllowanceService>().decidePayment(payment.id, true, note, ack) }
                    if (result != null) {
                        notifySuccess(tr("Zahlung genehmigt und gebucht."))
                        onChanged()
                    }
                } finally {
                    approveButton.disabled = false
                    rejectButton.disabled = false
                }
            }
        }
        rejectButton.onClick {
            val note = requireNote() ?: return@onClick
            approveButton.disabled = true
            rejectButton.disabled = true
            AppScope.launch {
                try {
                    val result = guarded { rpcService<IVolunteerAllowanceService>().decidePayment(payment.id, false, note) }
                    if (result != null) {
                        notifySuccess(tr("Zahlung abgelehnt."))
                        onChanged()
                    }
                } finally {
                    approveButton.disabled = false
                    rejectButton.disabled = false
                }
            }
        }
    }
}

/** Board/ADMIN-Weg zur Nacherfassung einer Papiererklärung -- niemals die IN_APP-Checkbox hier (Normans Fälschungsszenario). */
private fun renderPaperDeclarationForm(
    panel: SimplePanel,
    payment: VolunteerAllowancePaymentDto,
    onRecorded: () -> Unit,
) {
    val formRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-end flex-wrap") }
    val signedOnInput = formRow.text(label = tr("Unterschrieben am (JJJJ-MM-TT)"))
    val recordButton = formRow.button(tr("Papiererklärung erfassen"), style = ButtonStyle.OUTLINEPRIMARY)
    recordButton.onClick {
        val signedOn = runCatching { LocalDate.parse(signedOnInput.value.orEmpty().trim()) }.getOrNull()
        if (signedOn == null) {
            notifyError(tr("Bitte ein gültiges Unterschriftsdatum angeben (JJJJ-MM-TT)."))
            return@onClick
        }
        recordButton.disabled = true
        AppScope.launch {
            try {
                val result =
                    guarded {
                        rpcService<IVolunteerAllowanceService>().recordPaperDeclaration(
                            VolunteerAllowanceSelfDeclarationInput(
                                memberId = payment.subjectMemberId,
                                category = payment.category,
                                calendarYear = payment.paymentDate.year,
                                source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                                signedOn = signedOn,
                            ),
                        )
                    }
                if (result != null) {
                    notifySuccess(tr("Papiererklärung erfasst."))
                    onRecorded()
                }
            } finally {
                recordButton.disabled = false
            }
        }
    }
}

/**
 * Security-Fund (INFORMATIONAL) -- ADMIN-only Korrektur einer fehlerhaft erfassten
 * ON_PAPER-Selbstauskunft. HARD-Delete, siehe `IVolunteerAllowanceService.voidPaperDeclaration`
 * KDoc; deshalb ein Bestätigungsdialog, gleiches "irreversibel" Muster wie
 * `apiKeyRevokeConfirmDialog`/`webhookRemoveConfirmDialog` (`ApiKeysScreen.kt`).
 */
private fun renderVoidPaperDeclarationButton(
    panel: SimplePanel,
    declaration: VolunteerAllowanceSelfDeclarationDto,
    onVoided: () -> Unit,
) {
    val voidButton = panel.button(tr("Papiererklärung zurücknehmen"), style = ButtonStyle.OUTLINEDANGER) { addCssClasses("btn-sm") }
    voidButton.onClick {
        val modal = Modal(caption = tr("Papiererklärung zurücknehmen bestätigen"))
        modal.div(
            tr(
                "Die erfasste Papiererklärung wird unwiderruflich entfernt (kein Soft-Delete) -- die betroffene " +
                    "Person kann danach selbst eine In-App-Erklärung für dasselbe Jahr abgeben.",
            ),
        ) { addCssClasses("fw-bold text-danger") }
        modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        modal.addButton(
            Button(tr("Zurücknehmen"), style = ButtonStyle.DANGER).apply {
                onClick {
                    modal.hide()
                    voidButton.disabled = true
                    AppScope.launch {
                        try {
                            val result = guarded { rpcService<IVolunteerAllowanceService>().voidPaperDeclaration(declaration.id) }
                            if (result != null) {
                                notifySuccess(tr("Papiererklärung zurückgenommen."))
                                onVoided()
                            }
                        } finally {
                            voidButton.disabled = false
                        }
                    }
                }
            },
        )
        modal.show()
    }
}

/** Server-seitige Seitengröße (`VolunteerAllowanceService.MAX_LIST_RESULTS`). */
private const val VOLUNTEER_ALLOWANCE_PAGE_SIZE = 200

internal fun volunteerAllowanceHasMorePages(
    pageSize: Int,
    capacity: Int = VOLUNTEER_ALLOWANCE_PAGE_SIZE,
): Boolean = pageSize >= capacity

internal data class VolunteerAllowancePageCursor(
    val submittedAt: LocalDateTime,
    val id: String,
)

internal fun nextVolunteerAllowanceCursor(page: List<VolunteerAllowancePaymentDto>): VolunteerAllowancePageCursor? =
    page.lastOrNull()?.submittedAt?.let { submittedAt -> VolunteerAllowancePageCursor(submittedAt = submittedAt, id = page.last().id) }
