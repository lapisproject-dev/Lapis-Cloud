package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Link
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderAwardMotionIneligibility
import network.lapis.cloud.shared.domain.TenderAwardMotionOptionDto
import network.lapis.cloud.shared.domain.TenderAwardReadiness
import network.lapis.cloud.shared.domain.TenderBidBlockedReason
import network.lapis.cloud.shared.domain.TenderBidDto
import network.lapis.cloud.shared.domain.TenderBidInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidView
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderDescriptionFormat
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ITenderService

/*
 * V1.9.202 -- the detail page of one tender (`/tenders/:id`): the status row, the facts, the description, the bid card, the bids that the server
 * shows to this viewer, and the board's actions. Everything a viewer may do or see is a FIELD of the DTO (the server's decision):
 * `bidBlockedReason` (may bid?), `bidView` (which bids?), `bid.awardable` (can win?), `awardReadiness` (how far is the decision?),
 * `viewerIsConflicted` (a bidder never awards, accepts or cancels). The page hides what is not allowed; the server refuses it anyway.
 * No Markdown is rendered here: the description arrives as sanitized HTML (`descriptionHtml`) and goes through the one rich site `agreementHtml`.
 */

fun renderTenderDetailScreen(
    container: Container,
    id: String,
    rpc: ITenderService = rpcService(),
    navigate: (String) -> Unit = ::navigateTo,
) {
    if (AppState.session == null) {
        navigateTo(Routes.LOGIN)
        return
    }
    val root = container.pageShell(PageWidth.FORM, spacing = 12)
    root.overviewBackButton(Routes.TENDERS, navigate = navigate)
    val header = root.pageHeader(tr("Ausschreibung"), subtitle = "")
    lateinit var section: DataSection
    section =
        root.dataSection<TenderDetailDto>(
            isEmpty = { false },
            load = { guarded { rpc.getTender(id) } },
            onSettled = { dto -> if (dto != null) header.setSubtitle(sanitizeUntrustedI18nText(dto.tender.title)) },
            render = { panel, dto -> renderTenderDetail(panel, dto, rpc) { section.reload() } },
        )
    section.reload()
}

/** The ordered steps of the status row; a cancelled tender shows no row but the reason. */
private val STATUS_STEPS: List<TenderStatus> =
    listOf(TenderStatus.DRAFT, TenderStatus.PUBLISHED, TenderStatus.CLOSED, TenderStatus.AWARDED, TenderStatus.COMPLETED)

private fun stepLabel(status: TenderStatus): String =
    if (status ==
        TenderStatus.COMPLETED
    ) {
        gettext("Abgenommen")
    } else {
        TenderLabels.status(status)
    }

/** "noch 2 Tage und 3 Stunden" / "noch 5 Minuten"; `null` minutes = the deadline has passed. The number comes from the server clock, never from the browser. */
internal fun tenderRemainingText(minutes: Long): String {
    val days = (minutes / MINUTES_PER_DAY).toInt()
    val hours = ((minutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR).toInt()
    val rest = (minutes % MINUTES_PER_HOUR).toInt()
    val dayText = ngettext("%1 Tag", "%1 Tage", days, days)
    val hourText = ngettext("%1 Stunde", "%1 Stunden", hours, hours)
    val minuteText = ngettext("%1 Minute", "%1 Minuten", rest, rest)
    return when {
        days > 0 -> gettext("noch %1 und %2", dayText, hourText)
        hours > 0 -> gettext("noch %1 und %2", hourText, minuteText)
        else -> gettext("noch %1", minuteText)
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1440L

private fun renderTenderDetail(
    panel: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    val tender = dto.tender
    renderStatusRow(panel, dto)
    renderFacts(panel, dto)

    panel.h2(tr("Erwartetes Ergebnis")) { addCssClass("h5") }
    panel.renderAgreementText(dto.expectedResult)
    panel.h2(tr("Beschreibung")) { addCssClass("h5") }
    panel.renderAgreementBody(
        text = dto.description,
        format = if (dto.descriptionFormat == TenderDescriptionFormat.MARKDOWN) AgreementTextFormat.MARKDOWN else AgreementTextFormat.PLAIN,
        renderedHtml = dto.descriptionHtml,
    )

    renderBidCard(panel, dto, rpc, reload)
    renderBids(panel, dto, rpc, reload)
    renderAward(panel, dto)
    if (dto.acceptances.isNotEmpty()) renderAcceptances(panel, dto)
    if (dto.canManage && tender.status != TenderStatus.CANCELLED) renderBoardActions(panel, dto, rpc, reload)
}

private fun renderStatusRow(
    panel: SimplePanel,
    dto: TenderDetailDto,
) {
    val tender = dto.tender
    if (tender.status == TenderStatus.CANCELLED) {
        panel.div(className = "alert alert-danger mb-0") {
            setAttribute("role", "status")
            // The reason is free text of the board: element text, sanitized.
            span(tr("Abgesagt")) { addCssClass("fw-bold") }
            span(" ")
            cellText(dto.cancelReason.orEmpty())
        }
        return
    }
    val row = panel.div(className = "d-flex flex-wrap gap-2 align-items-center")
    row.setAttribute("role", "list")
    val current = STATUS_STEPS.indexOf(tender.status)
    STATUS_STEPS.forEachIndexed { index, step ->
        val badge =
            if (index == current) {
                row.statusBadge(stepLabel(step), TenderLabels.statusColor(step))
            } else {
                row.typeBadge(stepLabel(step), "secondary")
            }
        badge.setAttribute("role", "listitem")
        if (index == current) badge.setAttribute("aria-current", "step")
    }
}

private fun renderFacts(
    panel: SimplePanel,
    dto: TenderDetailDto,
) {
    val tender = dto.tender
    val facts = panel.vPanel(spacing = 4)
    val remaining = dto.minutesUntilDeadline
    val deadlineText =
        if (tender.status == TenderStatus.DRAFT || tender.status == TenderStatus.CANCELLED) {
            formatDateTime(tender.deadline)
        } else if (remaining != null) {
            gettext("%1 (%2)", formatDateTime(tender.deadline), tenderRemainingText(remaining))
        } else {
            gettext("abgelaufen am %1", formatDateTime(tender.deadline))
        }
    facts.p(gettext("Frist: %1", deadlineText), className = "mb-0")
    facts.p(gettext("Vergabe nach: %1", TenderLabels.criterion(tender.awardCriterion)), className = "mb-0")
    facts.p(gettext("Vergütung: %1", TenderLabels.currency(tender.currency)), className = "mb-0")
    facts.p(gettext("Sichtbarkeit der Angebote: %1", TenderLabels.visibility(tender.bidVisibility)), className = "mb-0")
    facts.p(tenderVisibilityExplanation(tender.bidVisibility), className = "text-muted small mb-0")
    // A friend or guest has no access to the governance book: the title is empty for them and the link is not offered.
    if (dto.bindingMotionTitle.isNotBlank()) {
        val line = facts.div(className = "mb-0")
        line.span(tr("Beruht auf: "))
        line.add(
            Link(
                label = sanitizeUntrustedI18nText(dto.bindingMotionTitle),
                url = "#${detailRoute(Routes.MOTIONS, tender.bindingMotionId)}",
            ),
        )
    }
}

// ── bid card ────────────────────────────────────────────────────────────────────────────────────────────

private fun blockedText(
    reason: TenderBidBlockedReason,
    dto: TenderDetailDto,
): String =
    when (reason) {
        TenderBidBlockedReason.GUEST_LTR ->
            gettext(
                "Als Gast können Sie auf diese Ausschreibung nicht bieten: Sie wird in LTR vergütet, und Gäste führen ihr LTR-Konto auf " +
                    "ihrem Heimatserver.",
            )
        TenderBidBlockedReason.NOT_PARTICIPANT -> gettext("Sie können auf diese Ausschreibung nicht bieten.")
        TenderBidBlockedReason.NOT_OPEN ->
            when (dto.tender.status) {
                TenderStatus.DRAFT -> gettext("Diese Ausschreibung ist noch nicht veröffentlicht.")
                TenderStatus.CANCELLED -> gettext("Diese Ausschreibung wurde abgesagt.")
                else -> gettext("Die Frist ist abgelaufen. Es können keine Angebote mehr abgegeben werden.")
            }
        TenderBidBlockedReason.MAX_BIDS_REACHED -> gettext("Diese Ausschreibung kann keine weiteren Angebote aufnehmen.")
    }

private fun renderBidCard(
    panel: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    // A draft or cancelled-before-publication tender is the board's business: no bid card.
    if (dto.tender.status == TenderStatus.DRAFT) return
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    card.h2(tr("Ihr Angebot")) { addCssClass("h5") }
    val mine = dto.myBid
    val blocked = dto.bidBlockedReason
    val formHost = card.vPanel(spacing = 6)

    fun showOwnBidText(bid: TenderBidDto) {
        card.p(gettext("Preis: %1", formatLtr(bid.price)), className = "mb-0")
        card.renderAgreementText(bid.text)
        card.p(gettext("Status: %1", TenderLabels.bidStatus(bid.status)), className = "text-muted small mb-0")
    }

    if (blocked != null) {
        if (mine != null) showOwnBidText(mine)
        // A guest's bid from before the rule can still be withdrawn: withdrawing is always safe.
        card.p(blockedText(blocked, dto), className = "text-muted mb-0")
        if (blocked == TenderBidBlockedReason.GUEST_LTR &&
            mine != null &&
            mine.status == TenderBidStatus.SUBMITTED &&
            dto.tender.status == TenderStatus.PUBLISHED
        ) {
            card.actionButton(ActionIcon.UNDO, tr("Angebot zurückziehen"), style = ButtonStyle.OUTLINEDANGER).onClick {
                confirmWithdraw(rpc, dto, reload)
            }
        }
        return
    }

    val active = mine != null && mine.status == TenderBidStatus.SUBMITTED
    if (mine == null || !active) {
        if (mine !=
            null
        ) {
            card.p(
                tr("Sie haben Ihr Angebot zurückgezogen. Sie können bis zur Frist ein neues abgeben."),
                className = "text-muted mb-0",
            )
        }
        bidForm(formHost, rpc, dto, existing = null, onDone = reload, onCancel = null)
        return
    }
    showOwnBidText(mine)
    val actions = card.div(className = "d-flex flex-wrap gap-2")
    val change = actions.actionButton(ActionIcon.EDIT, tr("Angebot ändern"), style = ButtonStyle.OUTLINEPRIMARY)
    val withdraw = actions.actionButton(ActionIcon.UNDO, tr("Angebot zurückziehen"), style = ButtonStyle.OUTLINEDANGER)
    change.onClick {
        formHost.removeAll()
        change.hide()
        bidForm(
            formHost,
            rpc,
            dto,
            existing = mine,
            onDone = reload,
            onCancel = {
                formHost.removeAll()
                change.show()
            },
        )
    }
    withdraw.onClick { confirmWithdraw(rpc, dto, reload) }
}

private fun confirmWithdraw(
    rpc: ITenderService,
    dto: TenderDetailDto,
    reload: () -> Unit,
) {
    confirmDialog(
        title = tr("Angebot zurückziehen?"),
        message = tr("Sie können bis zur Frist jederzeit ein neues Angebot abgeben."),
        confirmLabel = tr("Zurückziehen"),
        confirmIcon = ActionIcon.UNDO,
        focusCancel = true,
    ) {
        AppScope.launch {
            if (tenderGuarded { rpc.withdrawBid(dto.tender.id) } != null) {
                notifySuccess(tr("Angebot zurückgezogen."))
                reload()
            }
        }
    }
}

private fun bidForm(
    host: SimplePanel,
    rpc: ITenderService,
    dto: TenderDetailDto,
    existing: TenderBidDto?,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val form = host.lapisForm()
    val priceField =
        form.textField(
            label = tr("Preis in LTR"),
            required = true,
            value = existing?.let { formatPlainPrice(it) },
            hint = tr("Höchstens zwei Nachkommastellen."),
            rule = { raw ->
                when (val parsed = parseAmountInput(raw, maxFractionDigits = 2)) {
                    is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
                    else -> FieldCheck.Ok
                }
            },
        )
    val textField =
        form.textAreaField(
            label = tr("Ihr Angebot"),
            rows = 4,
            required = true,
            value = existing?.text,
            init = { it.maxlength = BID_TEXT_MAX },
        )
    val submit =
        newActionButton(
            ActionIcon.SEND,
            if (existing == null) tr("Angebot abgeben") else tr("Änderung speichern"),
            ButtonStyle.PRIMARY,
        )
    form.buttons(
        primary = submit,
        cancel =
            onCancel?.let { cancel ->
                newActionButton(ActionIcon.CANCEL, tr("Abbrechen")).apply { onClick { cancel() } }
            },
    )
    submit.onClick {
        form.submit(submit) {
            val price = (parseAmountInput(priceField.value, maxFractionDigits = 2) as? AmountInput.Valid)?.value ?: return@submit
            val saved = tenderGuarded { rpc.submitBid(dto.tender.id, TenderBidInput(price = price, text = textField.value.trim())) }
            if (saved != null) {
                notifySuccess(tr("Angebot gespeichert."))
                onDone()
            }
        }
    }
}

private const val BID_TEXT_MAX = 4_000

/** The price of an existing bid as the person would type it (plain digits, the comma of the language, no currency). */
private fun formatPlainPrice(bid: TenderBidDto): String = formatPlainAmountIn(I18n.language, bid.price)

// ── bids ────────────────────────────────────────────────────────────────────────────────────────────────

private fun renderBids(
    panel: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    val tender = dto.tender
    if (tender.status == TenderStatus.DRAFT) return
    panel.h2(tr("Angebote")) { addCssClass("h5") }
    val readyToAward = dto.awardReadiness == TenderAwardReadiness.READY
    if (dto.bids.isEmpty()) {
        val text =
            when {
                dto.bidView == TenderBidView.ALL -> tr("Noch keine Angebote.")
                tender.bidVisibility == TenderBidVisibility.OWN_ONLY ->
                    tr("Bei dieser Ausschreibung sieht jeder Bieter nur sein eigenes Angebot.")
                dto.minutesUntilDeadline != null ->
                    gettext("Die Angebote anderer sind bis %1 verdeckt.", formatDateTime(tender.deadline))
                else -> tr("Die Angebote sehen nur die Bieter und der Vorstand.")
            }
        panel.p(text, className = "text-muted")
        return
    }
    val headers =
        buildList {
            add(TableHeader(tr("Bieter")))
            add(TableHeader(tr("Preis"), numeric = true))
            add(TableHeader(tr("Status")))
            add(TableHeader(tr("Eingereicht")))
            if (readyToAward) add(TableHeader(tr("Aktionen")))
        }
    val table = panel.standardTable(headers)
    dto.bids.forEach { bid ->
        table.row {
            cell {
                cellText(bid.bidderDisplayName.orEmpty())
                if (bid.isMine) {
                    span(" ")
                    statusBadge(tr("Ihr Angebot"), "info")
                }
                div { cellText(bid.text, cssClasses = "text-muted small") }
            }
            numCell { span(formatLtr(bid.price)) }
            cell { statusBadge(TenderLabels.bidStatus(bid.status), TenderLabels.bidStatusColor(bid.status)) }
            textCell(formatSystemDateTime(bid.submittedAt))
            if (readyToAward) {
                cell {
                    if (bid.awardable) {
                        val name = sanitizeUntrustedI18nText(bid.bidderDisplayName.orEmpty())
                        tableActionGroup()
                            .tableActionButton(ActionIcon.AWARD, gettext("Zuschlag erteilen an %1", name), ButtonStyle.OUTLINEPRIMARY)
                            .onClick { confirmAward(rpc, dto, bid, reload) }
                    }
                }
            }
        }
    }
    if (dto.bidView == TenderBidView.OWN &&
        tender.bidVisibility == TenderBidVisibility.HIDDEN_UNTIL_DEADLINE &&
        dto.minutesUntilDeadline != null
    ) {
        panel.p(gettext("Die Angebote anderer sind bis %1 verdeckt.", formatDateTime(tender.deadline)), className = "text-muted small")
    }
}

private fun confirmAward(
    rpc: ITenderService,
    dto: TenderDetailDto,
    bid: TenderBidDto,
    reload: () -> Unit,
) {
    val name = sanitizeUntrustedI18nText(bid.bidderDisplayName.orEmpty())
    confirmWithReasonDialog(
        title = tr("Zuschlag erteilen?"),
        message = gettext("Zuschlag an %1 für %2.", name, formatLtr(bid.price)),
        dangerNote = tr("Der Zuschlag kann nicht zurückgenommen werden."),
        reasonLabel = tr("Begründung"),
        reasonRequired = dto.awardJustificationRequired,
        reasonMaxLength = BID_TEXT_MAX,
        confirmLabel = tr("Zuschlag erteilen"),
        confirmIcon = ActionIcon.AWARD,
    ) { reason ->
        AppScope.launch {
            if (tenderGuarded { rpc.awardTender(TenderAwardInput(tenderId = dto.tender.id, bidId = bid.id, justification = reason)) } !=
                null
            ) {
                notifySuccess(tr("Zuschlag erteilt."))
                reload()
            }
        }
    }
}

private fun renderAward(
    panel: SimplePanel,
    dto: TenderDetailDto,
) {
    val award = dto.tender.award ?: return
    panel.h2(tr("Zuschlag")) { addCssClass("h5") }
    panel.p(
        gettext(
            "%1 hat den Zuschlag erhalten (%2, am %3).",
            sanitizeUntrustedI18nText(award.winnerDisplayName),
            formatLtr(award.price),
            formatSystemDateTime(award.awardedAt),
        ),
        className = "mb-0",
    )
}

private fun renderAcceptances(
    panel: SimplePanel,
    dto: TenderDetailDto,
) {
    panel.h2(tr("Abnahme")) { addCssClass("h5") }
    dto.acceptances.forEach { acceptance ->
        val line = panel.div(className = "d-flex flex-wrap gap-2 align-items-center")
        if (acceptance.decision == TenderAcceptanceDecision.ACCEPTED) {
            line.statusBadge(tr("Abgenommen"), "success")
        } else {
            line.statusBadge(tr("Abgelehnt"), "danger")
        }
        line.span(formatSystemDateTime(acceptance.decidedAt))
        acceptance.reason?.let { line.cellText(it) }
    }
}

// ── board ───────────────────────────────────────────────────────────────────────────────────────────────

private fun renderBoardActions(
    panel: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    val tender = dto.tender
    panel.h2(tr("Vorstand")) { addCssClass("h5") }
    val box = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
    if (dto.viewerIsConflicted) {
        // A bidder (a withdrawn bid counts too) has an interest in the outcome: no award, no acceptance, no cancellation, no award motion.
        box.p(
            tr(
                "Sie haben selbst ein Angebot abgegeben (auch ein zurückgezogenes zählt). Deshalb dürfen Sie diese Ausschreibung weder " +
                    "vergeben noch abnehmen noch absagen.",
            ),
            className = "mb-0",
        )
        return
    }
    when (tender.status) {
        TenderStatus.DRAFT -> {
            val actions = box.div(className = "d-flex flex-wrap gap-2")
            val edit = actions.actionButton(ActionIcon.EDIT, tr("Entwurf bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY)
            val publish = actions.actionButton(ActionIcon.SEND, tr("Veröffentlichen"), style = ButtonStyle.PRIMARY)
            val formHost = box.vPanel(spacing = 6)
            edit.onClick {
                edit.hide()
                AppScope.launch {
                    val options = guarded { rpc.listTenderMotionOptions() } ?: emptyList()
                    val defaults = guarded { rpc.getTenderDefaults() }
                    formHost.removeAll()
                    formHost.tenderForm(
                        rpc = rpc,
                        defaultVisibility = defaults?.defaultVisibility ?: tender.bidVisibility,
                        motionOptions = options,
                        draft = dto,
                        collapse = { _ ->
                            formHost.removeAll()
                            edit.show()
                        },
                        onChanged = { reload() },
                    )
                }
            }
            publish.onClick {
                confirmDialog(
                    title = tr("Ausschreibung veröffentlichen?"),
                    message = tr("Danach können Mitglieder und Freunde bis zur Frist Angebote abgeben."),
                    extraLines =
                        listOf(
                            tr("Frist, Vergabe-Kriterium und Sichtbarkeit lassen sich nach dem Veröffentlichen nicht mehr ändern."),
                        ),
                    confirmLabel = tr("Veröffentlichen"),
                    confirmIcon = ActionIcon.SEND,
                    confirmStyle = ButtonStyle.PRIMARY,
                ) {
                    AppScope.launch {
                        if (tenderGuarded { rpc.publishTender(tender.id) } != null) {
                            notifySuccess(tr("Ausschreibung veröffentlicht."))
                            reload()
                        }
                    }
                }
            }
        }
        TenderStatus.CLOSED -> renderAwardStep(box, dto, rpc, reload)
        TenderStatus.AWARDED -> renderAcceptanceForm(box, dto, rpc, reload)
        else -> Unit
    }
    if (tender.status in CANCELLABLE_STATUSES) {
        box.actionButton(ActionIcon.CANCEL, tr("Ausschreibung absagen"), style = ButtonStyle.OUTLINEDANGER).onClick {
            confirmWithReasonDialog(
                title = tr("Ausschreibung absagen?"),
                message = tr("Der Grund ist für alle sichtbar, die die Ausschreibung sehen."),
                dangerNote = tr("Eine abgesagte Ausschreibung kann nicht wieder geöffnet werden."),
                reasonLabel = tr("Grund"),
                reasonRequired = true,
                reasonMaxLength = CANCEL_REASON_MAX,
                confirmLabel = tr("Absagen"),
                confirmIcon = ActionIcon.CANCEL,
            ) { reason ->
                AppScope.launch {
                    if (tenderGuarded { rpc.cancelTender(tender.id, reason.orEmpty()) } != null) {
                        notifySuccess(tr("Ausschreibung abgesagt."))
                        reload()
                    }
                }
            }
        }
    }
}

private val CANCELLABLE_STATUSES = setOf(TenderStatus.DRAFT, TenderStatus.PUBLISHED, TenderStatus.CLOSED, TenderStatus.AWARDED)
private const val CANCEL_REASON_MAX = 2_000

/** After the deadline: LOWEST_PRICE is an act of the board (the icons sit on the bids); BEST_OFFER needs the award motion first. */
private fun renderAwardStep(
    box: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    when (dto.awardReadiness) {
        TenderAwardReadiness.NEEDS_AWARD_MOTION, TenderAwardReadiness.AWARD_MOTION_NOT_ADOPTED -> {
            if (dto.awardReadiness == TenderAwardReadiness.AWARD_MOTION_NOT_ADOPTED) {
                box.p(tr("Der bisherige Vergabeantrag wurde nicht angenommen. Legen Sie einen neuen fest."), className = "mb-0")
            } else {
                box.p(tr("Die Frist ist abgelaufen. Für den Zuschlag braucht es einen Vergabebeschluss des Gremiums."), className = "mb-0")
            }
            box.p(
                tr(
                    "Legen Sie unter Anträge einen Antrag für den Vergabebeschluss an. Er wird nach Ablauf der Frist in einer Sitzung entschieden.",
                ),
                className = "text-muted small mb-0",
            )
            awardMotionPicker(box, dto, rpc, reload)
        }
        TenderAwardReadiness.AWARD_MOTION_PENDING -> {
            box.p(tr("Über den Vergabeantrag ist noch nicht entschieden."), className = "mb-0")
            awardMotionLink(box, dto)
        }
        TenderAwardReadiness.AWARD_MOTION_TOO_EARLY -> {
            box.p(
                tr("Der Vergabebeschluss wurde vor Ablauf der Frist gefasst und zählt deshalb nicht."),
                className = "mb-0",
            )
            awardMotionLink(box, dto)
        }
        TenderAwardReadiness.READY ->
            box.p(
                if (dto.tender.awardCriterion == TenderAwardCriterion.LOWEST_PRICE) {
                    tr("Den Zuschlag erteilen Sie am Angebot in der Tabelle oben.")
                } else {
                    tr("Der Vergabebeschluss liegt vor. Den Zuschlag erteilen Sie am Angebot in der Tabelle oben.")
                },
                className = "mb-0",
            )
        null -> Unit
    }
}

private fun awardMotionLink(
    box: SimplePanel,
    dto: TenderDetailDto,
) {
    val motionId = dto.awardMotionId ?: return
    val line = box.div(className = "mb-0")
    line.span(tr("Vergabeantrag: "))
    line.add(Link(label = sanitizeUntrustedI18nText(dto.awardMotionTitle.orEmpty()), url = "#${detailRoute(Routes.MOTIONS, motionId)}"))
}

private fun awardMotionReason(reason: TenderAwardMotionIneligibility?): String =
    when (reason) {
        TenderAwardMotionIneligibility.SAME_AS_BINDING -> gettext("ist der Beschluss, auf dem die Ausschreibung beruht")
        TenderAwardMotionIneligibility.WRONG_COMMITTEE -> gettext("gehört zu einem anderen Gremium")
        TenderAwardMotionIneligibility.AMENDMENT -> gettext("ist ein Änderungsantrag")
        TenderAwardMotionIneligibility.NOT_OPEN -> gettext("ist schon entschieden oder zurückgezogen")
        TenderAwardMotionIneligibility.ALREADY_BOUND -> gettext("gehört schon zu einer Ausschreibung")
        TenderAwardMotionIneligibility.HAS_AMENDMENT -> gettext("hat einen Änderungsantrag")
        TenderAwardMotionIneligibility.DECISION_STARTED -> gettext("wird schon abgestimmt")
        null -> ""
    }

private fun awardMotionPicker(
    box: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    val host = box.vPanel(spacing = 6)
    AppScope.launch {
        val options: List<TenderAwardMotionOptionDto> = guarded { rpc.listAwardMotionOptions(dto.tender.id) } ?: return@launch
        val reasons = options.filter { !it.selectable }.associate { it.motionId to awardMotionReason(it.reason) }
        val form = host.lapisForm()
        val field =
            form.selectField(
                label = tr("Vergabeantrag"),
                options =
                    listOf("" to tr("-- bitte wählen --")) +
                        untrustedOptions(
                            options.map {
                                it.motionId to
                                    if (it.selectable) it.title else gettext("%1 -- nicht wählbar: %2", it.title, reasons[it.motionId])
                            },
                        ),
                value = "",
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
        val set = newActionButton(ActionIcon.SAVE, tr("Vergabebeschluss festlegen"), ButtonStyle.PRIMARY)
        form.buttons(primary = set)
        set.onClick {
            form.submit(set) {
                if (tenderGuarded { rpc.registerAwardMotion(dto.tender.id, field.value) } != null) {
                    notifySuccess(tr("Vergabeantrag festgelegt."))
                    reload()
                }
            }
        }
    }
}

private fun renderAcceptanceForm(
    box: SimplePanel,
    dto: TenderDetailDto,
    rpc: ITenderService,
    reload: () -> Unit,
) {
    box.p(tr("Der Zuschlag ist erteilt. Nehmen Sie das Ergebnis ab oder lehnen Sie es mit einer Notiz ab."), className = "mb-0")
    var decision = TenderAcceptanceDecision.ACCEPTED
    val form = box.lapisForm()
    form.panel.segmentedRadioGroup(
        options =
            listOf(
                SegmentOption(TenderAcceptanceDecision.ACCEPTED, tr("Abnehmen")),
                SegmentOption(TenderAcceptanceDecision.REJECTED, tr("Ablehnen")),
            ),
        selected = decision,
        ariaLabel = tr("Entscheidung"),
    ) { decision = it }
    val note =
        form.textAreaField(
            label = tr("Notiz (bei Ablehnung Pflicht)"),
            rows = 3,
            init = { it.maxlength = BID_TEXT_MAX },
        )
    val save = newActionButton(ActionIcon.SAVE, tr("Abnahme erfassen"), ButtonStyle.PRIMARY)
    form.buttons(primary = save)
    save.onClick {
        // A rule of the field would skip an empty value (that is what `required` is for), and a rejection makes the note required only now.
        if (decision == TenderAcceptanceDecision.REJECTED && note.value.isBlank()) {
            note.showError(gettext("Bei einer Ablehnung ist eine Notiz nötig."))
            note.focus()
            return@onClick
        }
        form.submit(save) {
            val reason = note.value.trim().takeIf { it.isNotEmpty() }
            val saved =
                tenderGuarded {
                    rpc.recordAcceptance(
                        TenderAcceptanceInput(tenderId = dto.tender.id, decision = decision, reason = reason),
                    )
                }
            if (saved != null) {
                notifySuccess(tr("Abnahme erfasst."))
                reload()
            }
        }
    }
}
