package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.CheckBox
import io.kvision.form.check.Radio
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.modal.ModalSize
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgDonorProposalAction
import network.lapis.cloud.shared.domain.PartgDonorProposalDto
import network.lapis.cloud.shared.domain.PartgDonorProposalPickDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRowDto
import network.lapis.cloud.shared.domain.PartgDonorProposalStatus
import network.lapis.cloud.shared.rpc.PartyReportProposalStaleException
import org.w3c.dom.HTMLInputElement

private fun Container.proposalCell(
    label: String,
    init: Container.() -> Unit,
) {
    div(className = "lapis-partg-prop-cell") {
        setAttribute("data-label", label)
        init()
    }
}

/**
 * V1.9.293 (R5b) -- the dialog "Vorschlag für Ausweis B aus Buchungen". One row per person whose bookings of the federal unit add up to more than
 * 10,000 EUR in the year, plus the rows entered by hand (shown, never changed). Only NEW rows are ticked at the start; a different amount, a
 * similar row and every link are the person's decision. The server recomputes everything at the moment of taking over and refuses when the
 * proposal changed in between (the person reloads). No second modal: the button says what will happen.
 */
internal fun openPartgDonorProposalDialog(
    proposal: PartgDonorProposalDto,
    rowNumbers: Map<String, Int>,
    onApplied: (PartgAnnexDto) -> Unit,
    onReload: () -> Unit,
) {
    val modal = Modal(caption = gettext("Vorschlag für Ausweis B aus Buchungen: %1", proposal.year), size = ModalSize.XLARGE)
    modal.addCssClass("lapis-partg-proposal-modal")
    modal.setAttribute("data-partg-donor-proposal", "1")
    modal.div(partgDonorProposalNote(), className = "small text-muted mb-2") { setAttribute("data-partg-q40", "1") }
    val picks = LinkedHashMap(partgDonorInitialPicks(proposal.rows))
    val applyButton = newActionButton(ActionIcon.SAVE, tr("Übernehmen"), ButtonStyle.PRIMARY)

    fun paintApply() {
        val label = partgDonorPicksLabel(picks.values)
        applyButton.text = label ?: gettext("Übernehmen")
        applyButton.disabled = label == null
    }

    val usedTargets = HashMap<String, String>()

    if (proposal.rows.isEmpty()) {
        modal.div(tr("Keine Person liegt über 10.000 € und es ist keine Zeile erfasst."), className = "text-muted")
    } else {
        modal.div(className = "lapis-partg-prop-head") {
            setAttribute("aria-hidden", "true")
            listOf(
                tr("Status"),
                tr("Name"),
                tr("Art"),
                tr("Laut Buchungen"),
                tr("Erfasst"),
                tr("Differenz"),
                tr("Übernahme"),
            ).forEach { span(it) }
        }
        proposal.rows.forEachIndexed { index, row ->
            modal.renderProposalRow(
                index = index,
                row = row,
                rowNumbers = rowNumbers,
                picks = picks,
                usedTargets = usedTargets,
                onChanged = ::paintApply,
            )
        }
    }
    if (proposal.anonymisedCount > 0) {
        modal.div(
            gettext("%1 anonymisierte Spender sind nicht aufgeführt.", proposal.anonymisedCount),
            className = "small text-muted mt-2",
        ) { setAttribute("data-partg-anonymised", "1") }
    }
    if (proposal.withoutDonorCount > 0) {
        modal.div(
            gettext("%1 Spendenbuchungen ohne Spender sind nicht enthalten.", proposal.withoutDonorCount),
            className = "small text-muted",
        ) { setAttribute("data-partg-without-donor", "1") }
    }
    val failure = modal.div(className = "alert alert-warning mt-2") { hide() }
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    paintApply()
    applyButton.onClick {
        val chosen = picks.values.toList()
        if (chosen.isEmpty()) return@onClick
        applyButton.disabled = true
        AppScope.launch {
            try {
                val annex = partyReportRpc().applyLargeDonorProposal(proposal.year, proposal.checksum, chosen)
                modal.hide()
                notifySuccess(tr("Der Vorschlag wurde übernommen."))
                onApplied(annex)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PartyReportProposalStaleException) {
                failure.content = tr("Der Vorschlag ist veraltet. Bitte neu laden.")
                failure.show()
                failure.actionButton(ActionIcon.REFRESH, tr("Neu laden")) {
                    onClick {
                        modal.hide()
                        onReload()
                    }
                }
            } catch (e: Throwable) {
                applyButton.disabled = false
                guarded<Unit> { throw e }
            }
        }
    }
    modal.addButton(cancel)
    modal.addButton(applyButton)
    modal.show()
}

private fun Container.renderProposalRow(
    index: Int,
    row: PartgDonorProposalRowDto,
    rowNumbers: Map<String, Int>,
    picks: MutableMap<String, PartgDonorProposalPickDto>,
    usedTargets: MutableMap<String, String>,
    onChanged: () -> Unit,
) {
    div(className = "lapis-partg-prop-row") {
        setAttribute("role", "group")
        setAttribute("aria-label", sanitizeUntrustedI18nText(row.name))
        setAttribute("data-partg-prop-status", row.status.name)
        proposalCell(gettext("Status")) {
            span(partgDonorStatusLabel(row.status), className = "fw-semibold")
            row.block?.let {
                div(
                    partgDonorBlockLabel(it),
                    className = "small text-danger",
                ) { setAttribute("data-partg-prop-block", it.name) }
            }
            if (row.linkedBelowThreshold) {
                div(tr("Verknüpft; die Buchungen liegen derzeit nicht über 10.000 €."), className = "small text-muted")
            }
        }
        proposalCell(gettext("Name")) {
            cellText(row.name, "fw-semibold")
            val address = listOfNotNull(row.street, listOfNotNull(row.postalCode, row.city).joinToString(" ").ifEmpty { null }, row.country)
            if (address.isNotEmpty()) div(className = "small text-muted") { cellText(address.joinToString(", ")) }
        }
        proposalCell(gettext("Art")) { span(partgPersonKindLabel(row.personKind)) }
        proposalCell(gettext("Laut Buchungen")) {
            if (row.bookedCents == null) span("–") else moneySpan(centsToDecimal(row.bookedCents!!))
        }
        proposalCell(gettext("Erfasst")) {
            if (row.enteredCents == null) span("–") else moneySpan(centsToDecimal(row.enteredCents!!))
        }
        proposalCell(gettext("Differenz")) {
            val diff = partgDonorDifference(row)
            if (diff == null) span("–") else moneySpan(centsToDecimal(diff), warnIfNegative = false)
        }
        proposalCell(gettext("Übernahme")) {
            val ref = row.ref
            when {
                ref == null || row.allowedActions.isEmpty() -> {
                    val text =
                        when (row.status) {
                            PartgDonorProposalStatus.UNCHANGED, PartgDonorProposalStatus.ENTERED_ONLY -> tr("bleibt unverändert")
                            else -> tr("keine Übernahme")
                        }
                    span(text, className = "small text-muted")
                }
                PartgDonorProposalAction.LINK in row.allowedActions ->
                    renderLinkChoice(
                        index,
                        row,
                        ref,
                        rowNumbers,
                        picks,
                        usedTargets,
                        onChanged,
                    )
                else -> {
                    val action = row.allowedActions.single()
                    val label =
                        if (action == PartgDonorProposalAction.REPLACE_AMOUNT) {
                            gettext("Betrag ersetzen: %1", sanitizeUntrustedI18nText(row.name))
                        } else {
                            gettext("Übernehmen: %1", sanitizeUntrustedI18nText(row.name))
                        }
                    val box = CheckBox(value = picks[ref]?.action == action, label = label)
                    box.setAttribute("data-partg-pick", action.name)
                    box.subscribe { checked ->
                        if (checked == true) picks[ref] = PartgDonorProposalPickDto(ref = ref, action = action) else picks.remove(ref)
                        onChanged()
                    }
                    add(box)
                }
            }
        }
    }
}

/** SIMILAR: one radio per candidate row plus "Neu anlegen"; nothing is chosen at the start; the same row can be linked only once. */
private fun Container.renderLinkChoice(
    index: Int,
    row: PartgDonorProposalRowDto,
    ref: String,
    rowNumbers: Map<String, Int>,
    picks: MutableMap<String, PartgDonorProposalPickDto>,
    usedTargets: MutableMap<String, String>,
    onChanged: () -> Unit,
) {
    val group = "partg-link-$index"
    div(className = "d-flex flex-column gap-1") {
        setAttribute("role", "radiogroup")
        setAttribute("aria-label", gettext("Übernahme") + " " + sanitizeUntrustedI18nText(row.name))
        val radios = ArrayList<Pair<String, Radio>>()
        val create = Radio(value = false, name = group, label = tr("Neu anlegen"))
        row.similarRowIds.forEach { target ->
            val number = rowNumbers[target]
            val radio =
                Radio(
                    value = false,
                    name = group,
                    label = if (number != null) gettext("Verknüpfen mit Zeile %1", number) else tr("Verknüpfen"),
                )
            radio.setAttribute("data-partg-link-target", target)
            radio.subscribe { checked ->
                if (checked != true) return@subscribe
                val owner = usedTargets[target]
                if (owner != null && owner != ref) {
                    // the same row cannot be linked twice: the choice is refused here (the server refuses it too)
                    // the browser has already unticked the earlier radio of this group: tick it again once KVision has rendered this
                    // change (it would overwrite the DOM state); the model (picks) is read at that moment, so a later click is not undone
                    fun syncToPicks() {
                        val prev = picks[ref]
                        val previous =
                            when {
                                prev == null -> null
                                prev.action == PartgDonorProposalAction.CREATE -> create
                                else -> radios.firstOrNull { it.first == prev.targetRowId }?.second
                            }
                        radios.forEach { it.second.setChecked(it.second === previous) }
                        create.setChecked(create === previous)
                    }
                    SYNC_DELAYS_MS.forEach { delay -> window.setTimeout({ syncToPicks() }, delay) }
                    return@subscribe
                }
                usedTargets.entries.removeAll { it.value == ref }
                usedTargets[target] = ref
                picks[ref] = PartgDonorProposalPickDto(ref = ref, action = PartgDonorProposalAction.LINK, targetRowId = target)
                onChanged()
            }
            radios += target to radio
            add(radio)
        }
        create.setAttribute("data-partg-link-target", "CREATE")
        create.subscribe { checked ->
            if (checked != true) return@subscribe
            usedTargets.entries.removeAll { it.value == ref }
            picks[ref] = PartgDonorProposalPickDto(ref = ref, action = PartgDonorProposalAction.CREATE)
            onChanged()
        }
        add(create)
    }
}

/** Sets the DOM state of a radio and the model value together (the browser unticks group members without telling the component). */
private fun Radio.setChecked(checked: Boolean) {
    value = checked
    val element = getElement()
    val input = (element as? HTMLInputElement) ?: element?.unsafeCast<org.w3c.dom.Element>()?.querySelector("input") as? HTMLInputElement
    input?.checked = checked
}

private val SYNC_DELAYS_MS = listOf(0, 50, 250)
