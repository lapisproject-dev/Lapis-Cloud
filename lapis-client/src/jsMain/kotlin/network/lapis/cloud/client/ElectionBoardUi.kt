package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.ElectionBoardMemberDto
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IMemberService

/*
 * V1.9.22 -- the election board ("Wahlausschuss") and the candidacies of an election's detail view.
 */

/** The server's own bounds of an election board (`ElectionService.appointElectionBoard`). */
private const val MAX_ELECTION_BOARD_SIZE = 25

/** `election_candidacy.motivation_text` is `VARCHAR(1000)`; the server checks the same bound. */
private const val MAX_MOTIVATION_LENGTH = 1000

/** A motivation longer than this is shortened in the table and expands on request. */
private const val MOTIVATION_PREVIEW_LENGTH = 200

internal fun renderElectionBoardSection(
    panel: SimplePanel,
    data: ElectionDetailData,
    roles: ElectionRoles,
    reload: () -> Unit,
) {
    panel.h2(tr("Wahlausschuss")) { addCssClass("h5") }
    if (data.board.isEmpty()) {
        panel.p(tr("Noch kein Wahlausschuss bestellt."))
    } else {
        panel.dataTable(
            columns =
                listOf(
                    textColumn<ElectionBoardMemberDto>(title = tr("Name"), primary = true) {
                        it.memberDisplayName
                    },
                    textColumn(title = tr("Bestellt am")) { formatDateTime(it.appointedAt) },
                ),
            rows = data.board,
        )
    }
    if (canAppointBoard(data.election, roles)) renderBoardForm(panel, data, reload)
}

private fun renderBoardForm(
    panel: SimplePanel,
    data: ElectionDetailData,
    reload: () -> Unit,
) {
    val holder = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    holder.p(tr("Wahlausschuss bestellen")) { addCssClass("fw-bold") }
    holder.p(tr("Das Bestellen ersetzt den bisherigen Ausschuss vollständig. Der Ausschuss besteht aus 3 bis 25 Mitgliedern.")) {
        addCssClasses("text-muted small mb-0")
    }
    holder
        .dataSection<List<MemberSummaryDto>>(
            emptyText = gettext("Keine Mitglieder vorhanden."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IMemberService>().listMembers() } },
            render = { host, members -> buildBoardForm(host, members, data, reload) },
        ).reload()
}

private fun buildBoardForm(
    holder: SimplePanel,
    members: List<MemberSummaryDto>,
    data: ElectionDetailData,
    reload: () -> Unit,
) {
    val e = data.election
    run {
        val nameById = members.associate { member -> member.id to member.displayName }
        // The chosen members, in the order they were added; pre-filled with the current board.
        val chosen = LinkedHashMap<String, String>()
        data.board.forEach { chosen[it.memberId] = it.memberDisplayName }
        val candidateIds =
            data.candidacies
                .filter { it.withdrawnAt == null }
                .map { it.memberId }
                .toSet()
        val boardLockedIds = data.targetRoster.map { it.memberId }.toSet()

        val form = holder.lapisForm()
        val pickField =
            form.searchableSelectField(
                label = tr("Mitglied hinzufügen"),
                options = untrustedOptions(members.map { it.id to it.displayName }),
            )
        val chosenPanel = form.panel.vPanel(spacing = 4)
        val counter =
            form.panel.div("") {
                addCssClasses("small")
                setAttribute("role", "status")
                setAttribute("aria-live", "polite")
            }
        val warnings = form.panel.vPanel(spacing = 2)

        fun conflicting(): List<String> = chosen.filterKeys { it in boardLockedIds }.values.toList()

        fun refresh() {
            (pickField.control as SearchableSelect).options =
                untrustedOptions(members.filter { it.id !in chosen }.map { it.id to it.displayName })
            chosenPanel.removeAll()
            chosen.forEach { (id, name) ->
                val row = chosenPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                row.untrustedSpan(name, className = "flex-grow-1")
                val remove = row.button(tr("Entfernen"), style = ButtonStyle.OUTLINESECONDARY)
                remove.addCssClass("btn-sm")
                remove.onClick {
                    chosen.remove(id)
                    refresh()
                }
            }
            counter.content = gettext("Ausgewählt: %1 (erlaubt sind 3 bis 25 Mitglieder).", chosen.size)
            warnings.removeAll()
            if (chosen.size < e.tallyThreshold) {
                warnings.div(
                    gettext(
                        "Der Ausschuss ist kleiner als die erforderliche Zahl von %1 Freigaben. Die Abstimmung kann so nicht geöffnet werden.",
                        e.tallyThreshold,
                    ),
                ) { addCssClasses("alert alert-warning mb-0") }
            }
            val overlap = chosen.filterKeys { it in candidateIds }.values.toList()
            if (overlap.isNotEmpty()) {
                warnings.div(
                    sanitizeUntrustedI18nText(gettext("Zugleich als Kandidatur eingetragen: %1.", overlap.joinToString(", "))),
                ) { addCssClasses("alert alert-warning mb-0") }
            }
            val locked = conflicting()
            if (locked.isNotEmpty()) {
                warnings.div(
                    sanitizeUntrustedI18nText(
                        gettext("Mitglieder des Vorstands-Gremiums dürfen nicht im Wahlausschuss sitzen: %1.", locked.joinToString(", ")),
                    ),
                ) { addCssClasses("alert alert-danger mb-0") }
            }
        }

        val addButton = Button(tr("Hinzufügen"), style = ButtonStyle.OUTLINESECONDARY)
        form.panel.add(addButton)
        addButton.onClick {
            val id = pickField.value
            if (id.isNotBlank() && id !in chosen && chosen.size < MAX_ELECTION_BOARD_SIZE) {
                chosen[id] = nameById[id].orEmpty()
                pickField.reset()
                refresh()
            }
        }
        form.crossFieldRule {
            when {
                chosen.size < MIN_ELECTION_BOARD_SIZE || chosen.size > MAX_ELECTION_BOARD_SIZE ->
                    FieldCheck.Invalid(gettext("Der Wahlausschuss braucht 3 bis 25 Mitglieder."))
                conflicting().isNotEmpty() ->
                    FieldCheck.Invalid(gettext("Mitglieder des Vorstands-Gremiums dürfen nicht im Wahlausschuss sitzen."))
                else -> FieldCheck.Ok
            }
        }
        val saveButton = Button(tr("Wahlausschuss speichern"), style = ButtonStyle.PRIMARY)
        form.buttons(primary = saveButton)
        refresh()
        saveButton.onClick {
            form.submit(saveButton) {
                val result =
                    electionGuarded(
                        gettext(
                            "Der Ausschuss konnte nicht gespeichert werden: Der Status hat sich geändert oder ein Mitglied sitzt im Vorstands-Gremium.",
                        ),
                    ) {
                        rpcService<IElectionService>().appointElectionBoard(e.id, chosen.keys.toList())
                    }
                if (result != null) {
                    notifySuccess(tr("Wahlausschuss gespeichert."))
                    reload()
                }
            }
        }
    }
}

internal fun renderCandidacySection(
    panel: SimplePanel,
    data: ElectionDetailData,
    roles: ElectionRoles,
    me: String,
    reload: () -> Unit,
) {
    val e = data.election
    panel.h2(tr("Kandidaturen")) { addCssClass("h5") }
    if (data.candidacies.isEmpty()) {
        panel.p(tr("Noch keine Kandidaturen."))
    } else {
        panel.dataTable(
            columns =
                listOf(
                    textColumn<CandidacyDto>(title = tr("Name"), primary = true, cssClasses = "fw-bold") { it.memberDisplayName },
                    DataColumn(
                        title = tr("Motivation"),
                        cell = { container, c -> renderMotivationCell(container, c.motivationText) },
                    ),
                    textColumn(title = tr("Eingereicht am")) { formatDateTime(it.submittedAt) },
                    textColumn(title = tr("Status")) { if (it.withdrawnAt == null) gettext("Aktiv") else gettext("Zurückgezogen") },
                ),
            rows = data.candidacies,
            actions = { container, c ->
                if (canWithdrawOwn(e, c, me) || canWithdrawForeign(e, c, me, roles)) {
                    val withdraw = container.button(tr("Zurückziehen"), style = ButtonStyle.OUTLINEDANGER)
                    withdraw.addCssClass("btn-sm")
                    withdraw.onClick {
                        confirmDialog(
                            title = tr("Kandidatur zurückziehen"),
                            message =
                                sanitizeUntrustedI18nText(
                                    gettext("Die Kandidatur von %1 wirklich zurückziehen?", c.memberDisplayName),
                                ),
                            confirmLabel = tr("Zurückziehen"),
                        ) {
                            runGuardedAction(withdraw) {
                                val result =
                                    electionGuarded(
                                        gettext("Die Kandidatur konnte nicht zurückgezogen werden. Bitte Ansicht aktualisieren."),
                                    ) {
                                        rpcService<IElectionService>().withdrawCandidacy(c.id)
                                    }
                                if (result != null) {
                                    notifyInfo(tr("Kandidatur zurückgezogen."))
                                    reload()
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    if (canSubmitCandidacy(e, data.candidacies, me)) renderCandidacyForm(panel, e.id, reload)
}

/** The motivation, shortened to [MOTIVATION_PREVIEW_LENGTH] characters with a toggle when it is longer. */
private fun renderMotivationCell(
    container: Container,
    text: String?,
) {
    val full = text.orEmpty()
    if (full.isBlank()) {
        container.untrustedSpan("–", className = "text-muted")
        return
    }
    if (full.length <= MOTIVATION_PREVIEW_LENGTH) {
        container.untrustedSpan(full)
        return
    }
    val shortText = full.take(MOTIVATION_PREVIEW_LENGTH) + "…"
    val textSpan = container.untrustedSpan(shortText)
    val toggle = container.button(tr("mehr"), style = ButtonStyle.LINK)
    toggle.addCssClass("btn-sm")
    toggle.setAttribute("aria-expanded", "false")
    var expanded = false
    toggle.onClick {
        expanded = !expanded
        untrustedContent(textSpan, if (expanded) full else shortText)
        toggle.text = if (expanded) tr("weniger") else tr("mehr")
        toggle.setAttribute("aria-expanded", expanded.toString())
    }
}

private fun renderCandidacyForm(
    panel: SimplePanel,
    electionId: String,
    reload: () -> Unit,
) {
    val holder = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    holder.p(tr("Ich kandidiere")) { addCssClass("fw-bold") }
    val form = holder.lapisForm()
    val motivationField =
        form.textAreaField(
            label = tr("Motivation (optional)"),
            rows = 4,
            rule = { FormRules.maxLength(it, MAX_MOTIVATION_LENGTH) },
            init = { area -> (area.input as? Widget)?.setAttribute("maxlength", MAX_MOTIVATION_LENGTH.toString()) },
        )
    val counter = form.panel.div(gettext("%1 von %2 Zeichen", 0, MAX_MOTIVATION_LENGTH)) { addCssClasses("text-muted small") }
    motivationField.subscribe { counter.content = gettext("%1 von %2 Zeichen", it.length, MAX_MOTIVATION_LENGTH) }
    val submit = Button(tr("Kandidatur einreichen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submit)
    submit.onClick {
        form.submit(submit) {
            val result =
                electionGuarded(gettext("Die Kandidatur konnte nicht eingereicht werden. Bitte Ansicht aktualisieren.")) {
                    rpcService<IElectionService>().submitCandidacy(
                        electionId,
                        CandidacyInput(motivationText = motivationField.value.trim().takeIf { it.isNotBlank() }),
                    )
                }
            if (result != null) {
                notifySuccess(tr("Kandidatur eingereicht."))
                reload()
            }
        }
    }
}
