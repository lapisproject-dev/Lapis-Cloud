package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCreateInput
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDefaultsDto
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderDraftUpdateInput
import network.lapis.cloud.shared.domain.TenderMotionIneligibility
import network.lapis.cloud.shared.domain.TenderMotionOptionDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import network.lapis.cloud.shared.rpc.ITenderService

/*
 * V1.9.202 "Ausschreibungen", wave 2: the screens. This file is the LIST ("Ausschreibungen", `/tenders`) and the form that creates a tender or
 * edits a draft; the detail page is `TenderDetailScreen.kt`. The client only SHOWS what the server decided: who may bid (`bidBlockedReason`), who
 * sees which bids (`bidView`), which bid can win (`awardable`), how far the decision is (`awardReadiness`). Nothing here repeats a rule.
 *
 * Roles: every status that reads tenders opens the list; BOARD/ADMIN get the form ("+ Neue Ausschreibung"), BOARD/ADMIN/TREASURER the filter
 * "Entwürfe" (the TREASURER reads, never manages). `getTenderDefaults` answers 403 for everybody else, so it is only asked for those three roles;
 * that is a gate against a pointless refusal, the permission itself is the server's.
 */

/** The labels and colours of the tender enums (static texts; one place for the list and the detail page). */
internal object TenderLabels {
    fun status(status: TenderStatus): String =
        when (status) {
            TenderStatus.DRAFT -> gettext("Entwurf")
            TenderStatus.PUBLISHED -> gettext("Veröffentlicht")
            TenderStatus.CLOSED -> gettext("Frist abgelaufen")
            TenderStatus.AWARDED -> gettext("Vergeben")
            TenderStatus.COMPLETED -> gettext("Abgenommen")
            TenderStatus.CANCELLED -> gettext("Abgesagt")
        }

    fun statusColor(status: TenderStatus): String =
        when (status) {
            TenderStatus.DRAFT -> "secondary"
            TenderStatus.PUBLISHED -> "success"
            TenderStatus.CLOSED -> "warning"
            TenderStatus.AWARDED -> "info"
            TenderStatus.COMPLETED -> "primary"
            TenderStatus.CANCELLED -> "danger"
        }

    fun currency(currency: TenderCurrency): String =
        when (currency) {
            TenderCurrency.LTR -> gettext("LTR")
            TenderCurrency.EUR -> gettext("Euro")
        }

    fun criterion(criterion: TenderAwardCriterion): String =
        when (criterion) {
            TenderAwardCriterion.LOWEST_PRICE -> gettext("Günstigster Preis")
            TenderAwardCriterion.BEST_OFFER -> gettext("Bestes Angebot")
        }

    fun visibility(visibility: TenderBidVisibility): String = tenderVisibilityLabel(visibility)

    fun bidStatus(status: TenderBidStatus): String =
        when (status) {
            TenderBidStatus.SUBMITTED -> gettext("Eingereicht")
            TenderBidStatus.WITHDRAWN -> gettext("Zurückgezogen")
            TenderBidStatus.AWARDED -> gettext("Zuschlag erhalten")
            TenderBidStatus.REJECTED -> gettext("Nicht berücksichtigt")
        }

    fun bidStatusColor(status: TenderBidStatus): String =
        when (status) {
            TenderBidStatus.SUBMITTED -> "primary"
            TenderBidStatus.WITHDRAWN -> "secondary"
            TenderBidStatus.AWARDED -> "success"
            TenderBidStatus.REJECTED -> "secondary"
        }

    fun motionReason(reason: TenderMotionIneligibility?): String =
        when (reason) {
            TenderMotionIneligibility.NOT_RESOLVED -> gettext("noch nicht beschlossen")
            TenderMotionIneligibility.AMENDMENT -> gettext("ist ein Änderungsantrag")
            TenderMotionIneligibility.RESOLUTION_NOT_ADOPTED -> gettext("Beschluss nicht angenommen")
            TenderMotionIneligibility.WRONG_COMMITTEE -> gettext("Beschluss in einem anderen Gremium gefasst")
            null -> ""
        }
}

/** What each award criterion means -- shown under the choice in the form. */
internal fun tenderCriterionExplanation(criterion: TenderAwardCriterion): String =
    when (criterion) {
        TenderAwardCriterion.LOWEST_PRICE ->
            gettext("Der Vorstand erteilt den Zuschlag dem günstigsten Angebot. Ein Beschluss ist dafür nicht nötig.")
        TenderAwardCriterion.BEST_OFFER ->
            gettext("Der Zuschlag ist eine Wertung. Darüber entscheidet das Gremium mit einem Vergabebeschluss nach Ablauf der Frist.")
    }

/** What each visibility means -- the SAME sentences in the form, on the detail page and in the organisation settings (the reader decides with them). */
internal fun tenderVisibilityExplanation(visibility: TenderBidVisibility): String =
    when (visibility) {
        TenderBidVisibility.HIDDEN_UNTIL_DEADLINE ->
            gettext("Bis zum Ende der Frist sieht niemand fremde Angebote. Danach sehen alle Bieter und der Vorstand alle Angebote.")
        TenderBidVisibility.OPEN -> gettext("Alle Beteiligten sehen die Angebote der anderen sofort.")
        TenderBidVisibility.OWN_ONLY ->
            gettext(
                "Jeder Bieter sieht nur sein eigenes Angebot. Der Vorstand sieht nach Ablauf der Frist alle Angebote. " +
                    "Wer den Zuschlag erhält, wird allen angezeigt.",
            )
    }

/** The filter of the list. [DONE] is COMPLETED or CANCELLED (the server has no single status for it, so it is cut on the client). */
internal enum class TenderFilter(
    val status: TenderStatus?,
) {
    ALL(null),
    OPEN(TenderStatus.PUBLISHED),
    CLOSED(TenderStatus.CLOSED),
    AWARDED(TenderStatus.AWARDED),
    DONE(null),
    DRAFT(TenderStatus.DRAFT),
    ;

    fun label(): String =
        when (this) {
            ALL -> gettext("Alle")
            OPEN -> gettext("Offen")
            CLOSED -> gettext("Frist abgelaufen")
            AWARDED -> gettext("Vergeben")
            DONE -> gettext("Abgeschlossen")
            DRAFT -> gettext("Entwürfe")
        }

    /** The server filter: one status, or all of them (the cut for [DONE] happens in [keeps]). */
    fun serverFilter(): TenderStatus? = if (this == DONE) null else status

    fun keeps(summary: TenderSummaryDto): Boolean =
        if (this == DONE) summary.status == TenderStatus.COMPLETED || summary.status == TenderStatus.CANCELLED else true
}

fun renderTendersScreen(
    container: Container,
    rpc: ITenderService = rpcService(),
) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Ausschreibungen"), intro = PageIntros.forRoute(Routes.TENDERS))
    // The form is collapsed; its button sits in the title row and the form opens right under the header (R36B).
    val formHost = root.vPanel(spacing = 6)
    root.h2(tr("Übersicht")) { addCssClass("h5") }
    val filterHost = root.div()
    val listHost = root.vPanel(spacing = 6)

    var defaults: TenderDefaultsDto? = null
    var filter = TenderFilter.ALL
    lateinit var section: DataSection
    section =
        listHost.dataSection<List<TenderSummaryDto>>(
            emptyText = gettext("Keine Ausschreibungen in dieser Auswahl."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpc.listTenders(filter.serverFilter()).filter { filter.keeps(it) } } },
            render = { panel, tenders -> renderTenderTable(panel, tenders) },
        )

    fun buildFilter() {
        filterHost.removeAll()
        val options =
            TenderFilter.entries
                .filter { it != TenderFilter.DRAFT || defaults?.canReadInternal == true }
                .map { SegmentOption(value = it, label = it.label()) }
        filterHost.segmentedRadioGroup(options = options, selected = filter, ariaLabel = tr("Auswahl")) { chosen ->
            filter = chosen
            section.reload()
        }
    }

    buildFilter()
    section.reload()

    // The defaults (and with them the form and the "Entwürfe" filter) only for the three roles the server answers.
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN, AccountRole.TREASURER)) {
        AppScope.launch {
            val loaded = guarded { rpc.getTenderDefaults() } ?: return@launch
            defaults = loaded
            buildFilter()
            if (loaded.canManage) {
                // Loaded once with the page, like the other screens (the form must be built without a suspension).
                val options = guarded { rpc.listTenderMotionOptions() } ?: emptyList()
                collapsibleCreateForm<Unit>(
                    actionSlot = header.actionSlot,
                    formHost = formHost,
                    buttonLabel = tr("Neue Ausschreibung"),
                    formId = "lapis-create-tender",
                ) { _, close ->
                    tenderForm(
                        rpc = rpc,
                        defaultVisibility = loaded.defaultVisibility,
                        motionOptions = options,
                        draft = null,
                        collapse = close,
                        onChanged = { section.reload() },
                    )
                }
            }
        }
    }
}

private fun renderTenderTable(
    host: Container,
    tenders: List<TenderSummaryDto>,
) {
    val headers =
        listOf(
            TableHeader(tr("Titel")),
            TableHeader(tr("Status")),
            TableHeader(tr("Frist")),
            TableHeader(tr("Vergütung")),
            TableHeader(tr("Angebote"), numeric = true),
            TableHeader(tr("Mein Angebot")),
            TableHeader(tr("Aktionen")),
        )
    val table = host.masterTable(tr("Ausschreibungen"), headers)
    tenders.forEach { tender ->
        table.row {
            masterRow()
            val titleCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(titleCell)
            // The title is free text of the board: sanitized inside [recordLink].
            titleCell.recordLink(tender.title, Routes.TENDERS, tender.id)
            cell { statusBadge(TenderLabels.status(tender.status), TenderLabels.statusColor(tender.status)) }
            textCell(formatDateTime(tender.deadline))
            textCell(TenderLabels.currency(tender.currency))
            val count = tender.visibleBidCount
            if (count != null) {
                numCell(count.toString())
            } else {
                // The number of bids is as sensitive as the bids themselves: where it may not be shown it is a dash with the reason.
                numCell {
                    span("–") { title = tr("Die Zahl der Angebote ist verdeckt.") }
                    span(tr("Die Zahl der Angebote ist verdeckt.")) { addCssClass("visually-hidden") }
                }
            }
            cell {
                val mine = tender.myBidStatus
                if (mine == null) span("–") else statusBadge(TenderLabels.bidStatus(mine), TenderLabels.bidStatusColor(mine))
            }
            cell {
                tableActionGroup()
                    .tableActionButton(ActionIcon.VIEW, gettext("Ausschreibung %1 öffnen", sanitizeUntrustedI18nText(tender.title)))
                    .onClick {
                        MasterDetailFocus.remember(Routes.TENDERS, tender.id)
                        navigateTo(detailRoute(Routes.TENDERS, tender.id))
                    }
            }
        }
    }
}

/**
 * The form of a new tender or of a draft ([draft] != null). "Als Entwurf speichern" keeps the draft private; "Veröffentlichen" asks first, saves
 * and publishes -- if publishing is refused the draft stays saved and the person is told so. New descriptions are saved as Markdown (rendered by
 * the server); a legacy plain-text draft stays plain until its text is changed. EUR is shown, disabled, with the reason in plain sight.
 */
internal fun SimplePanel.tenderForm(
    rpc: ITenderService,
    defaultVisibility: TenderBidVisibility,
    motionOptions: List<TenderMotionOptionDto>,
    draft: TenderDetailDto?,
    collapse: ((saved: Boolean) -> Unit)?,
    onChanged: (TenderDetailDto) -> Unit,
): FormSnapshot {
    val form = lapisForm()
    val titleField =
        form.textField(label = tr("Titel"), required = true, value = draft?.tender?.title, init = { it.maxlength = TENDER_TITLE_MAX })

    // The binding resolution. A motion that cannot be chosen stays in the list WITH its reason and is refused by the rule of the field.
    val known = motionOptions.associateBy { it.motionId }
    val motionChoices = motionOptions.toMutableList()
    val draftMotion = draft?.tender?.bindingMotionId?.takeIf { it.isNotBlank() }
    if (draftMotion != null && draftMotion !in known) {
        motionChoices += TenderMotionOptionDto(draftMotion, draft.bindingMotionTitle, "", selectable = true)
    }
    val reasons = motionChoices.filter { !it.selectable }.associate { it.motionId to TenderLabels.motionReason(it.reason) }
    val motionField =
        form.selectField(
            label = tr("Beschluss, auf dem die Ausschreibung beruht"),
            options =
                listOf("" to tr("-- bitte wählen --")) +
                    untrustedOptions(
                        motionChoices.map { option ->
                            val label =
                                if (option.committeeName.isBlank()) option.title else gettext("%1 (%2)", option.title, option.committeeName)
                            option.motionId to
                                if (option.selectable) label else gettext("%1 -- nicht wählbar: %2", label, reasons[option.motionId])
                        },
                    ),
            value = draftMotion ?: "",
            required = true,
            rule = { id ->
                if (id in
                    reasons
                ) {
                    FieldCheck.Invalid(gettext("Dieser Antrag kann nicht gewählt werden: %1", reasons.getValue(id)))
                } else {
                    FieldCheck.Ok
                }
            },
        )

    val descriptionEditor =
        form.markdownTextEditor(
            label = tr("Beschreibung"),
            current = draft?.description ?: "",
            isMarkdown = draft == null || draft.descriptionFormat == TenderDescriptionFormat.MARKDOWN,
            maxLength = TENDER_DESCRIPTION_MAX,
            legacyHint =
                tr(
                    "Der bisherige Text ist einfacher Text. Sobald Sie ihn ändern, wird er als Markdown gespeichert: Absätze trennt eine " +
                        "Leerzeile, Aufzählungen beginnen mit \"- \".",
                ),
            rows = 8,
            required = true,
            preview = { rpc.previewTenderDescription(it) },
        )
    val expectedField =
        form.textAreaField(
            label = tr("Erwartetes Ergebnis"),
            rows = 3,
            value = draft?.expectedResult,
            required = true,
            init = { it.maxlength = TENDER_EXPECTED_MAX },
        )
    val deadlineFields = form.localDateTimeFields(label = gettext("Frist"), value = draft?.tender?.deadline, required = true)
    form.panel.p(
        tr("Mindestens eine Stunde nach dem Veröffentlichen. Bis zu dieser Uhrzeit können Angebote abgegeben werden."),
        className = "text-muted small mb-0",
    )

    var criterion = draft?.tender?.awardCriterion ?: TenderAwardCriterion.LOWEST_PRICE
    form.panel.div(tr("Vergabe nach"), className = "fw-semibold")
    val criterionNote = form.panel.p(tenderCriterionExplanation(criterion), className = "text-muted small mb-0")
    form.panel.segmentedRadioGroup(
        options = TenderAwardCriterion.entries.map { SegmentOption(value = it, label = TenderLabels.criterion(it)) },
        selected = criterion,
        ariaLabel = tr("Vergabe nach"),
    ) {
        criterion = it
        criterionNote.content = tenderCriterionExplanation(it)
    }

    var visibility = draft?.tender?.bidVisibility ?: defaultVisibility
    form.panel.div(tr("Sichtbarkeit der Angebote"), className = "fw-semibold")
    val visibilityNote = form.panel.p(tenderVisibilityExplanation(visibility), className = "text-muted small mb-0")
    form.panel.segmentedRadioGroup(
        options = TenderBidVisibility.entries.map { SegmentOption(value = it, label = TenderLabels.visibility(it)) },
        selected = visibility,
        ariaLabel = tr("Sichtbarkeit der Angebote"),
    ) {
        visibility = it
        visibilityNote.content = tenderVisibilityExplanation(it)
    }

    form.panel.div(tr("Vergütung"), className = "fw-semibold")
    form.panel.segmentedRadioGroup(
        options =
            listOf(
                SegmentOption(value = TenderCurrency.LTR, label = TenderLabels.currency(TenderCurrency.LTR)),
                SegmentOption(
                    value = TenderCurrency.EUR,
                    label = TenderLabels.currency(TenderCurrency.EUR),
                    enabled = false,
                    disabledHint = tr("Euro-Vergütung kommt später."),
                ),
            ),
        selected = TenderCurrency.LTR,
        ariaLabel = tr("Vergütung"),
    ) {}
    form.panel.p(tr("Euro-Vergütung kommt später."), className = "text-muted small mb-0")

    val publishButton = newActionButton(ActionIcon.SEND, tr("Veröffentlichen"), ButtonStyle.PRIMARY)
    val draftButton = newActionButton(ActionIcon.SAVE, tr("Als Entwurf speichern"), ButtonStyle.OUTLINESECONDARY)
    val row = form.buttons(primary = publishButton, cancel = collapse?.let { collapseCancelButton(it) })
    row.add(draftButton)

    // The draft this form currently stands for: starts as [draft] and, once a new tender was saved, becomes that saved draft, so a later
    // click (e.g. after a refused publish) updates it instead of creating a second one.
    var savedDraft: TenderDetailDto? = draft

    /** Saves the form as a new draft or as the changed draft; `null` = refused (the person was told). */
    suspend fun save(): TenderDetailDto? {
        val deadline = deadlineFields.dateTimeOrNull()
        if (deadline == null) {
            form.showFormError(tr("Bitte geben Sie die Frist mit Datum und Uhrzeit an."))
            return null
        }
        val description =
            descriptionEditor.field.value
                .replace("\r\n", "\n")
                .trim()
        val format = if (descriptionEditor.markdownToSend()) TenderDescriptionFormat.MARKDOWN else TenderDescriptionFormat.PLAIN
        val motionId = motionField.value
        val current = savedDraft
        val result =
            if (current == null) {
                tenderGuarded {
                    rpc.createTender(
                        TenderCreateInput(
                            bindingMotionId = motionId,
                            title = titleField.value.trim(),
                            description = description,
                            expectedResult = expectedField.value.trim(),
                            deadline = deadline,
                            awardCriterion = criterion,
                            bidVisibility = visibility,
                            descriptionFormat = format,
                        ),
                    )
                }
            } else {
                tenderGuarded {
                    rpc.updateTenderDraft(
                        current.tender.id,
                        TenderDraftUpdateInput(
                            bindingMotionId = motionId,
                            title = titleField.value.trim(),
                            description = description,
                            expectedResult = expectedField.value.trim(),
                            deadline = deadline,
                            awardCriterion = criterion,
                            bidVisibility = visibility,
                            descriptionFormat = format,
                        ),
                    )
                }
            }
        if (result != null) savedDraft = result
        return result
    }

    draftButton.onClick {
        form.submit(draftButton) {
            val saved = save() ?: return@submit
            notifySuccess(tr("Entwurf gespeichert."))
            onChanged(saved)
            collapse?.invoke(true)
        }
    }
    publishButton.onClick {
        if (!form.validateAndReport()) return@onClick
        confirmDialog(
            title = tr("Ausschreibung veröffentlichen?"),
            message = tr("Danach können Mitglieder und Freunde bis zur Frist Angebote abgeben."),
            extraLines = listOf(tr("Frist, Vergabe-Kriterium und Sichtbarkeit lassen sich nach dem Veröffentlichen nicht mehr ändern.")),
            confirmLabel = tr("Veröffentlichen"),
            confirmIcon = ActionIcon.SEND,
            confirmStyle = ButtonStyle.PRIMARY,
        ) {
            AppScope.launch {
                form.runBusy(publishButton) {
                    val saved = save() ?: return@runBusy
                    val outcome = tenderCall { rpc.publishTender(saved.tender.id) }
                    val published = outcome.value
                    if (published == null) {
                        // The draft is saved either way; say so, with the reason when the tender gave one.
                        outcome.refusal?.let { notifyError(gettext("Als Entwurf gespeichert, aber nicht veröffentlicht: %1", it)) }
                        onChanged(saved)
                    } else {
                        notifySuccess(tr("Ausschreibung veröffentlicht."))
                        onChanged(published)
                        collapse?.invoke(true)
                    }
                }
            }
        }
    }
    return form.snapshot()
}

private const val TENDER_TITLE_MAX = 200
private const val TENDER_DESCRIPTION_MAX = 10_000
private const val TENDER_EXPECTED_MAX = 4_000
