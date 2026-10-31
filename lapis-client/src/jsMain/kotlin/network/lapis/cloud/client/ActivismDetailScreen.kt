package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismContributionInput
import network.lapis.cloud.shared.domain.ActivismContributionsViewDto
import network.lapis.cloud.shared.domain.ActivismLimits
import network.lapis.cloud.shared.domain.ActivismResultInput
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismTaskInput
import network.lapis.cloud.shared.rpc.IActivismService

/**
 * V1.9.246 "Aktionen A1" -- `/activism/{id}`: one page in a fixed order: (1) the title row with status and the buttons that fit the person's
 * role, (2) goal and progress, (3) when and where, (4) taking part (signal, progress report), (5) the checklist, (6) copy-and-paste texts,
 * (7) material, (8) the notice about rules, (9) the result and, at the very end, "Melden". Everything written by people is set through the
 * `untrusted*` helpers. No names in aria labels, toasts or live regions. No polling: only the changed area is loaded again.
 */
fun renderActivismDetailScreen(
    container: SimplePanel,
    actionId: String,
) {
    val root = container.pageShell(PageWidth.WIDE, spacing = 14)
    root.overviewBackButton(Routes.ACTIVISM, tr("Zu den Aktionen"))
    lateinit var data: DataSection
    data =
        root.dataSection<ActivismActionDetailDto>(
            isEmpty = { false },
            load = { guarded { rpcService<IActivismService>().getAction(actionId) } },
            render = { panel, detail -> renderDetail(panel, detail) { data.reload() } },
        )
    data.reload()
}

private fun renderDetail(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    renderTitleRow(panel, d, reload)
    renderGoal(panel, d)
    renderWhen(panel, d)
    renderParticipation(panel, d, reload)
    renderTasks(panel, d, reload)
    renderTextBlocks(panel, d, reload)
    renderMaterial(panel, d)
    renderNotice(panel, d)
    renderResult(panel, d, reload)
    renderReport(panel, d)
}

private fun renderTitleRow(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    panel.pageHeader(
        d.title,
        subtitle = "${ActivismTexts.kind(d.kind)} · ${ActivismTexts.status(d.status)}",
    )
    val bar = panel.div(className = "d-flex flex-wrap gap-2")
    if (ActivismLogic.canEdit(d.viewerRole) && d.status != ActivismStatus.WITHDRAWN) {
        bar.actionButton(ActionIcon.EDIT, tr("Bearbeiten")) { onClick { navigateTo("${Routes.ACTIVISM}/${d.id}/edit") } }
    }
    val reviewRequired = d.reviewRequired
    ActivismLogic.statusTargets(d, reviewRequired).forEach { target ->
        bar.actionButton(ActionIcon.NEXT, gettext("Setzen auf: %1", ActivismTexts.status(target)), ButtonStyle.OUTLINEPRIMARY) {
            onClick {
                runGuardedAction(
                    this,
                ) { guarded { rpcService<IActivismService>().setActionStatus(d.id, target) }?.let { reload() } }
            }
        }
    }
    if (ActivismLogic.canSubmitForReview(d, reviewRequired)) {
        bar.actionButton(ActionIcon.SEND, tr("Zur Freigabe einreichen"), ButtonStyle.PRIMARY) {
            onClick { runGuardedAction(this) { guarded { rpcService<IActivismService>().submitForReview(d.id) }?.let { reload() } } }
        }
    }
    if (d.status == ActivismStatus.AWAITING_REVIEW && ActivismLogic.isManager(d.viewerRole)) {
        bar.actionButton(ActionIcon.APPROVE, tr("Freigeben"), ButtonStyle.PRIMARY) {
            onClick { runGuardedAction(this) { guarded { rpcService<IActivismService>().approveAction(d.id) }?.let { reload() } } }
        }
        inlineReasonForm(panel, tr("Ablehnen"), tr("Grund"), 1, ActivismLimits.REJECT_REASON_MAX) { reason ->
            guarded { rpcService<IActivismService>().rejectReview(d.id, reason) }?.let { reload() }
        }
    }
    d.reviewRejectReason?.let { panel.untrustedDiv(gettext("Abgelehnt: %1", it), className = "text-danger small") }
    if (ActivismLogic.isManager(d.viewerRole)) {
        if (d.status == ActivismStatus.WITHDRAWN) {
            bar.actionButton(ActionIcon.REFRESH, tr("Wiederherstellen")) {
                onClick { runGuardedAction(this) { guarded { rpcService<IActivismService>().restoreAction(d.id) }?.let { reload() } } }
            }
            d.withdrawnReason?.let { panel.untrustedDiv(gettext("Zurückgezogen: %1", it), className = "text-danger small") }
        } else {
            inlineReasonForm(
                panel,
                tr("Zurückziehen"),
                tr("Grund"),
                ActivismLimits.WITHDRAW_REASON_MIN,
                ActivismLimits.WITHDRAW_REASON_MAX,
            ) { reason ->
                guarded { rpcService<IActivismService>().withdrawAction(d.id, reason) }?.let { reload() }
            }
        }
    }
}

private fun renderGoal(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
) {
    if (!d.formProgress && d.goalText == null) return
    panel.h2(tr("Ziel und Fortschritt")) { addCssClass("h5") }
    d.goalText?.let { panel.untrustedDiv(it, className = "lapis-pre-line") }
    if (d.formProgress) {
        val percent = ActivismLogic.progressPercent(d.progressReported, d.goalQuantity)
        if (percent != null) {
            // A native <progress>: value and max are attributes, there is no inline style and no transition.
            val bar = Tag(TAG.PROGRESS, className = "lapis-progress")
            bar.setAttribute("max", "100")
            bar.setAttribute("value", percent.toString())
            bar.setAttribute("aria-label", gettext("Fortschritt"))
            panel.add(bar)
        }
        panel.div(ActivismLogic.progressText(d.progressReported, d.progressConfirmed, d.goalQuantity, d.unit))
    }
}

private fun renderWhen(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
) {
    if (d.description == null && d.location == null && d.startsAt == null) return
    panel.h2(tr("Wann und wo")) { addCssClass("h5") }
    d.startsAt?.let { start ->
        val text = d.endsAt?.let { gettext("%1 bis %2", formatDateTime(start), formatDateTime(it)) } ?: formatDateTime(start)
        panel.div(text)
    }
    d.location?.let { panel.untrustedDiv(it) }
    d.description?.let { panel.untrustedDiv(it, className = "lapis-pre-line") }
    d.eventShifts?.takeIf { it.isNotEmpty() }?.let { shifts ->
        panel.h2(tr("Schichten der Veranstaltung")) { addCssClass("h6") }
        shifts.forEach {
            panel.div(
                gettext(
                    "%1 bis %2: %3 von %4 Plätzen besetzt",
                    formatDateTime(it.startsAt),
                    formatDateTime(it.endsAt),
                    it.confirmed.toString(),
                    it.needed.toString(),
                ),
            )
        }
    }
}

private fun renderParticipation(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    if (!d.formSignal && !d.formProgress) return
    panel.h2(tr("Mitmachen")) { addCssClass("h5") }
    if (d.formSignal) {
        panel.div(ActivismLogic.signalText(d.signal), className = "small") {
            setAttribute("role", "status")
        }
        if (ActivismLogic.canSignal(d) || d.signal.mine) {
            val box = panel.checkBox(value = d.signal.mine, label = tr("Ich komme"))
            box.onClick {
                val wanted = box.value == true
                box.disabled = true
                runGuardedAction(button = null) {
                    guarded { rpcService<IActivismService>().setSignal(d.id, wanted) }
                    reload()
                }
            }
        }
    }
    if (ActivismLogic.canReportProgress(d)) renderContributionForm(panel, d, reload)
    if (d.formProgress) renderContributions(panel, d, reload)
}

private fun renderContributionForm(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    val form = panel.lapisForm()
    val quantity =
        form.textField(
            label = tr("Menge"),
            required = true,
            rule = {
                if (ActivismLogic.parseWhole(it, ActivismLimits.CONTRIBUTION_QUANTITY_MIN, ActivismLimits.CONTRIBUTION_QUANTITY_MAX) ==
                    null
                ) {
                    FieldCheck.Invalid(gettext("Bitte geben Sie eine ganze Zahl zwischen 1 und 10.000 ein."))
                } else {
                    FieldCheck.Ok
                }
            },
        )
    val area = form.textField(label = tr("Gebiet (optional)"))
    val note = form.textAreaField(label = tr("Notiz (optional)"), rows = 2)
    val send = newActionButton(ActionIcon.SEND, tr("Fortschritt melden"), ButtonStyle.PRIMARY)
    form.buttons(primary = send)
    send.onClick {
        form.submit(send) {
            val amount =
                ActivismLogic.parseWhole(quantity.value, ActivismLimits.CONTRIBUTION_QUANTITY_MIN, ActivismLimits.CONTRIBUTION_QUANTITY_MAX)
                    ?: return@submit
            guarded { rpcService<IActivismService>().reportContribution(d.id, ActivismContributionInput(amount, area.value, note.value)) }
                ?: return@submit
            reload()
        }
    }
}

private fun renderContributions(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    lateinit var section: DataSection
    section =
        panel.dataSection<ActivismContributionsViewDto>(
            emptyText = "",
            isEmpty = { false },
            load = { guarded { rpcService<IActivismService>().listContributions(d.id) } },
            render = { host, view ->
                if (view.areaSums.isNotEmpty()) {
                    host.h2(tr("Nach Gebiet")) { addCssClass("h6") }
                    view.areaSums.forEach { sum ->
                        host.untrustedDiv("${sum.area ?: gettext("Ohne Gebiet")}: ${ActivismLogic.count(sum.sum)}", className = "small")
                    }
                }
                val shown = view.all ?: view.own
                if (shown.isNotEmpty()) host.h2(if (view.all != null) tr("Alle Meldungen") else tr("Meine Meldungen")) { addCssClass("h6") }
                shown.forEach { c ->
                    val row =
                        host.div(
                            className = "d-flex flex-wrap align-items-center gap-2 small border-bottom py-1",
                        ) { setAttribute("role", "group") }
                    row.span(ActivismLogic.count(c.quantity), className = "fw-bold")
                    row.span(ActivismTexts.contributionStatus(c.status), className = "badge text-bg-light")
                    c.area?.let { row.untrustedSpan(it) }
                    c.memberName?.let { row.untrustedSpan(it, className = "text-muted") }
                    c.note?.let { row.untrustedSpan(it, className = "text-muted") }
                    if (view.all != null && c.status == network.lapis.cloud.shared.domain.ActivismContributionStatus.REPORTED) {
                        row.actionButton(ActionIcon.APPROVE, tr("Bestätigen"), small = true) {
                            onClick {
                                runGuardedAction(this) {
                                    guarded { rpcService<IActivismService>().decideContribution(c.id, true, null) }
                                    section.reload()
                                    reload()
                                }
                            }
                        }
                        row.actionButton(ActionIcon.REJECT, tr("Ablehnen"), small = true) {
                            onClick {
                                runGuardedAction(this) {
                                    guarded {
                                        rpcService<IActivismService>().decideContribution(
                                            c.id,
                                            false,
                                            gettext("Nicht nachvollziehbar"),
                                        )
                                    }
                                    section.reload()
                                }
                            }
                        }
                    } else if (c.editable) {
                        row.actionButton(ActionIcon.DELETE, tr("Meldung zurücknehmen"), ButtonStyle.OUTLINEDANGER, small = true) {
                            onClick {
                                runGuardedAction(this) {
                                    guarded { rpcService<IActivismService>().withdrawOwnContribution(c.id) }
                                    section.reload()
                                }
                            }
                        }
                    }
                }
            },
        )
    section.reload()
}

private fun renderTasks(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    if (d.tasks.isEmpty() && !ActivismLogic.canEdit(d.viewerRole)) return
    panel.h2(tr("Aufgaben")) { addCssClass("h5") }
    val list = panel.div { setAttribute("role", "list") }
    d.tasks.forEach { task ->
        val row = list.div(className = "d-flex flex-wrap align-items-center gap-2 py-1") { setAttribute("role", "listitem") }
        val mayTick = ActivismLogic.canEdit(d.viewerRole) || task.assigneeIsMe
        if (mayTick) {
            val box = row.checkBox(value = task.done, label = tr("Erledigt"))
            box.onClick {
                val wanted = box.value == true
                runGuardedAction(button = null) {
                    guarded { rpcService<IActivismService>().setTaskDone(task.id, wanted) }
                    reload()
                }
            }
        }
        row.untrustedSpan(task.text, className = if (task.done) "text-decoration-line-through" else null)
        task.assigneeName?.let { row.untrustedSpan("($it)", className = "small text-muted") }
        task.dueOn?.let { row.span(formatDate(it), className = "small text-muted") }
        if (ActivismLogic.canEdit(d.viewerRole)) {
            row.actionButton(ActionIcon.DELETE, tr("Entfernen"), ButtonStyle.OUTLINEDANGER, small = true) {
                onClick {
                    runGuardedAction(this) {
                        guarded { rpcService<IActivismService>().deleteTask(task.id) }
                        reload()
                    }
                }
            }
        }
    }
    if (ActivismLogic.canEdit(d.viewerRole)) {
        val form = panel.lapisForm()
        val text = form.textField(label = tr("Neue Aufgabe"), required = true)
        val add = newActionButton(ActionIcon.ADD, tr("Aufgabe hinzufügen"))
        form.buttons(primary = add)
        add.onClick {
            form.submit(add) {
                guarded { rpcService<IActivismService>().addTask(d.id, ActivismTaskInput(text = text.value)) } ?: return@submit
                reload()
            }
        }
    }
}

private fun renderTextBlocks(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    if (d.textBlocks.isEmpty() && !ActivismLogic.canEdit(d.viewerRole)) return
    panel.h2(tr("Textbausteine")) { addCssClass("h5") }
    d.textBlocks.forEach { block ->
        val card = panel.div(className = "border rounded p-2 mb-2")
        card.untrustedDiv(block.title, className = "fw-bold")
        card.untrustedDiv(block.body, className = "lapis-pre-line")
        val actions = card.div(className = "d-flex gap-2 mt-1")
        actions.actionButton(ActionIcon.COPY, tr("Kopieren"), small = true) {
            onClick {
                val clipboard: dynamic = window.navigator.asDynamic().clipboard
                if (clipboard != null && clipboard != undefined) clipboard.writeText(block.body)
            }
        }
        if (ActivismLogic.canEdit(d.viewerRole)) {
            actions.actionButton(ActionIcon.DELETE, tr("Entfernen"), ButtonStyle.OUTLINEDANGER, small = true) {
                onClick {
                    runGuardedAction(this) {
                        guarded { rpcService<IActivismService>().deleteTextBlock(block.id) }
                        reload()
                    }
                }
            }
        }
    }
    if (ActivismLogic.canEdit(d.viewerRole)) {
        val form = panel.lapisForm()
        val title = form.textField(label = tr("Titel des Textbausteins"), required = true)
        val body = form.textAreaField(label = tr("Text"), rows = 3, required = true)
        val add = newActionButton(ActionIcon.ADD, tr("Textbaustein hinzufügen"))
        form.buttons(primary = add)
        add.onClick {
            form.submit(add) {
                guarded { rpcService<IActivismService>().addTextBlock(d.id, title.value, body.value) } ?: return@submit
                reload()
            }
        }
    }
}

private fun renderMaterial(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
) {
    if (d.material.isEmpty()) return
    panel.h2(tr("Material")) { addCssClass("h5") }
    d.material.forEach { m ->
        val row = panel.div(className = "d-flex flex-wrap align-items-center gap-2")
        row.untrustedSpan(m.title)
        row.actionButton(
            ActionIcon.OPEN_EXTERNAL,
            tr("Im Dokumentenbereich öffnen"),
            small = true,
        ) { onClick { navigateTo(Routes.DOCUMENTS) } }
    }
}

private fun renderNotice(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
) {
    panel.h2(tr("Hinweis zu den Regeln")) { addCssClass("h5") }
    val box = panel.div(className = "alert alert-info small mb-2") { setAttribute("role", "note") }
    box.untrustedDiv(ActivismLogic.noticeFor(d), className = "lapis-pre-line")
    d.ownNotice?.let { box.untrustedDiv(it, className = "lapis-pre-line mt-1") }
    box.div(ActivismTexts.legalDisclaimer(), className = "fw-bold mt-1")
}

private fun renderResult(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
    reload: () -> Unit,
) {
    val result = d.result
    if (result == null && !(d.status == ActivismStatus.COMPLETED && ActivismLogic.canEdit(d.viewerRole))) return
    panel.h2(tr("Ergebnis")) { addCssClass("h5") }
    result?.participants?.let { panel.div(gettext("Beteiligte: %1", ActivismLogic.count(it))) }
    result?.quantity?.let { panel.div(gettext("Menge: %1", ActivismLogic.count(it))) }
    result?.text?.let { panel.untrustedDiv(it, className = "lapis-pre-line") }
    if (d.status == ActivismStatus.COMPLETED && ActivismLogic.canEdit(d.viewerRole)) {
        val form = panel.lapisForm()
        val participants = form.textField(label = tr("Beteiligte"), value = result?.participants?.toString())
        val quantity = form.textField(label = tr("Menge"), value = result?.quantity?.toString())
        val text = form.textAreaField(label = tr("Was ist herausgekommen?"), rows = 3, value = result?.text)
        val save = newActionButton(ActionIcon.SAVE, tr("Ergebnis speichern"), ButtonStyle.PRIMARY)
        form.buttons(primary = save)
        save.onClick {
            form.submit(save) {
                val input =
                    ActivismResultInput(
                        participants = ActivismLogic.parseWhole(participants.value, 0, ActivismLimits.RESULT_NUMBER_MAX),
                        quantity = ActivismLogic.parseWhole(quantity.value, 0, ActivismLimits.RESULT_NUMBER_MAX),
                        text = text.value,
                    )
                guarded { rpcService<IActivismService>().recordResult(d.id, input) } ?: return@submit
                reload()
            }
        }
    }
}

/** "Melden" is the last, secondary action of the page. */
private fun renderReport(
    panel: SimplePanel,
    d: ActivismActionDetailDto,
) {
    inlineReasonForm(
        panel,
        tr("Melden"),
        tr("Warum melden Sie diese Aktion?"),
        ActivismLimits.REPORT_REASON_MIN,
        ActivismLimits.REPORT_REASON_MAX,
    ) { reason ->
        guarded {
            rpcService<IActivismService>().reportAction(d.id, reason)
        }?.let { notifySuccess(gettext("Danke. Die Meldung wurde weitergegeben.")) }
    }
}

/** A closed form behind a button: the reason is a mandatory text with a minimum and maximum length. */
private fun inlineReasonForm(
    panel: Container,
    buttonLabel: String,
    fieldLabel: String,
    min: Int,
    max: Int,
    onSubmit: suspend (String) -> Unit,
) {
    val host = panel.div(className = "mt-2")
    val open = host.actionButton(ActionIcon.EDIT, buttonLabel, small = true)
    var shown = false
    open.onClick {
        if (shown) return@onClick
        shown = true
        open.hide()
        val form = host.lapisForm()
        val reason =
            form.textAreaField(
                label = fieldLabel,
                rows = 3,
                required = true,
                rule = {
                    val length = it.trim().length
                    if (length < min ||
                        length > max
                    ) {
                        FieldCheck.Invalid(gettext("Bitte schreiben Sie zwischen %1 und %2 Zeichen.", min.toString(), max.toString()))
                    } else {
                        FieldCheck.Ok
                    }
                },
            )
        val send = newActionButton(ActionIcon.SEND, buttonLabel, ButtonStyle.PRIMARY)
        form.buttons(primary = send)
        send.onClick { form.submit(send) { onSubmit(reason.value) } }
    }
}
