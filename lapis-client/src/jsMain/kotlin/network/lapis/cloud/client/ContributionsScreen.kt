package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
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
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IPostalMailService
import kotlin.time.Clock

/**
 * Screen 5 of the V0.7.3 plan -- the `IContributionService` backend has existed since V0.1/V0.4;
 * only the UI was missing. Every caller sees their own summary; TREASURER/BOARD/ADMIN additionally
 * see the org-wide table (mirrors `listContributions`'s own `isPrivileged || TREASURER`
 * authorization, see that method's KDoc), with "als bezahlt markieren" limited to TREASURER/ADMIN
 * and "als erlassen markieren" limited to BOARD/ADMIN (TREASURER may pay but not waive, per
 * `markContributionWaived`'s own role check) -- the tier-administration sub-panel
 * (`generateContributionsForPeriod`) is TREASURER/ADMIN only, matching `createMembershipTier`'s
 * own role check.
 *
 * Mail-merge/Postal-Dispatch UI wave, design decision D3: [renderContributionRow] additionally
 * renders a "Rechnung (PDF)" download link ([MailmergeHttp.invoiceUrl]) -- only inside
 * [renderOrgWideContributions] (already TREASURER/BOARD/ADMIN-gated by the caller, matching
 * `MailmergeRoutes.kt`'s `FINANCIAL_DOC_ROLES` exactly), never inside [renderOwnSummary]. A member
 * cannot self-serve their own invoice this wave -- see [MailmergeHttp] KDoc for the verified
 * server-side access tier this deliberately mirrors.
 *
 * Design decision D5: the same row additionally renders a "Per Post versenden" postal-dispatch
 * trigger (`IPostalMailService.dispatchBeitragsrechnungByPost`, matching `FINANCIAL_DISPATCH_ROLES`
 * -- the same TREASURER/BOARD/ADMIN tier as the row itself, no extra in-row gating needed) next to
 * the PDF link, gated by [isPostalMailEnabled] (D7) and confirmed via [postalDispatchConfirmDialog]
 * (D5) -- see `PostalMailScreen.kt`'s file KDoc for the "address never touches the browser"
 * load-bearing finding that shapes that dialog's copy.
 */
fun renderContributionsScreen(container: SimplePanel) {
    val session =
        AppState.session ?: run {
            navigateTo(Routes.LOGIN)
            return
        }
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 760.px
            marginTop = 24.px
        }
    root.h1(tr("Beitragsübersicht"))

    // V1.2.2 SEPA-Client-UI wave -- see SepaMandateSection.kt file KDoc "K1". Owns its own panel,
    // renders nothing at all for a plain MEMBER when SEPA is disabled for this organization.
    renderSepaMandateSection(root)

    // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- see PspCheckoutSection.kt file
    // KDoc. Owns its own panel, renders nothing at all for a plain MEMBER when online payment is
    // disabled for this organization -- same S-16/K1 treatment as renderSepaMandateSection above.
    renderPspCheckoutSection(root)

    renderOwnSummary(root, session.memberId)

    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)) {
        renderTierAdministration(root)
    }
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN, AccountRole.BOARD)) {
        renderOrgWideContributions(root)
    }
}

private fun renderOwnSummary(
    root: SimplePanel,
    memberId: String,
) {
    root.h2(tr("Meine Beiträge"))
    val panel = root.vPanel(spacing = 4)
    AppScope.launch {
        val summary = guarded { rpcService<IContributionService>().getMemberContributionSummary(memberId) } ?: return@launch
        panel.div(gettext("Offen: %1 | Bezahlt: %2 | Gesamt: %3", summary.totalOpen, summary.totalPaid, summary.totalDue))
        if (summary.contributions.isEmpty()) {
            panel.p(tr("Keine Beiträge vorhanden."))
        } else {
            // UI theme redesign wave (2026-08-20): real Bootstrap table (table-striped/table-hover),
            // replacing the previous hand-rolled "border-bottom py-1" div-per-row list -- see root
            // CLAUDE.md "UI/UX-Design-Team" review. The four interpolated values are unchanged, just
            // split across columns instead of one concatenated sentence.
            val table =
                panel.table(
                    headerNames = listOf(tr("Zeitraum"), tr("Betrag"), tr("Status")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            summary.contributions.forEach { contribution ->
                table.row {
                    cell(gettext("%1 bis %2", contribution.periodStart, contribution.periodEnd))
                    cell(contribution.amountDue.toString())
                    cell(contribution.status.toString())
                }
            }
        }
    }
}

private fun renderTierAdministration(root: SimplePanel) {
    root.h2(tr("Beitragssätze und Beitragsgenerierung"))
    val tiersPanel = root.vPanel(spacing = 4)
    val formPanel = root.vPanel(spacing = 6)

    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: return@launch
        if (tiers.isEmpty()) {
            tiersPanel.p(tr("Keine Beitragssätze vorhanden."))
            return@launch
        }
        tiers.forEach { tier ->
            val activeLabel = if (tier.active) gettext("aktiv") else gettext("inaktiv")
            tiersPanel.div(gettext("%1: %2 (%3, %4)", tier.name, tier.contributionAmount, tier.billingInterval, activeLabel))
        }

        val tierOptions = tiers.map { it.id to it.name }
        val tierSelect = formPanel.select(options = tierOptions, value = tierOptions.firstOrNull()?.first, label = tr("Beitragssatz"))
        val periodStartInput = formPanel.text(label = tr("Periodenbeginn (JJJJ-MM-TT)"))
        val periodEndInput = formPanel.text(label = tr("Periodenende (JJJJ-MM-TT)"))
        val errorBox =
            formPanel.div().apply {
                addCssClass("text-danger")
                hide()
            }

        val generateButton = formPanel.button(tr("Beiträge generieren"), style = ButtonStyle.PRIMARY)
        generateButton.onClick {
            errorBox.hide()
            val tierId = tierSelect.value
            val periodStart = runCatching { LocalDate.parse(periodStartInput.value.orEmpty().trim()) }.getOrNull()
            val periodEnd = runCatching { LocalDate.parse(periodEndInput.value.orEmpty().trim()) }.getOrNull()
            if (tierId == null || periodStart == null || periodEnd == null) {
                errorBox.content = tr("Bitte Beitragssatz sowie gültiges Beginn-/Enddatum (JJJJ-MM-TT) angeben.")
                errorBox.show()
                return@onClick
            }
            generateButton.disabled = true
            AppScope.launch {
                val created = guarded { rpcService<IContributionService>().generateContributionsForPeriod(tierId, periodStart, periodEnd) }
                generateButton.disabled = false
                if (created != null) notifySuccess(gettext("%1 neue Beiträge erzeugt (bereits vorhandene wurden übersprungen).", created))
            }
        }
    }
}

private fun renderOrgWideContributions(root: SimplePanel) {
    root.h2(tr("Alle Beiträge"))
    val canMarkPaid = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)
    val canWaive = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    val listPanel = root.vPanel(spacing = 4)

    fun refresh() {
        listPanel.removeAll()
        AppScope.launch {
            val postalMailEnabled = isPostalMailEnabled()
            val contributions =
                guarded { rpcService<IContributionService>().listContributions(status = ContributionStatus.OPEN) } ?: return@launch
            if (contributions.isEmpty()) {
                listPanel.p(tr("Keine offenen Beiträge."))
                return@launch
            }
            // UI theme redesign wave (2026-08-20): real Bootstrap table (table-striped/table-hover),
            // replacing the previous hand-rolled "border rounded p-2" hPanel-per-row layout -- see
            // root CLAUDE.md "UI/UX-Design-Team" review.
            val table =
                listPanel.table(
                    headerNames = listOf(tr("Mitglied"), tr("Zeitraum"), tr("Betrag"), tr("Status"), tr("Aktionen")),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            contributions.forEach { contribution ->
                renderContributionRow(table, contribution, canMarkPaid, canWaive, postalMailEnabled, ::refresh)
            }
        }
    }
    refresh()
}

private fun renderContributionRow(
    table: Table,
    contribution: ContributionDto,
    canMarkPaid: Boolean,
    canWaive: Boolean,
    postalMailEnabled: Boolean,
    onChanged: () -> Unit,
) {
    table.row {
        cell(contribution.memberDisplayName)
        cell(gettext("%1–%2", contribution.periodStart, contribution.periodEnd))
        cell(contribution.amountDue.toString())
        cell(contribution.status.toString())

        val actionsCell = cell()
        val actionsPanel = actionsCell.vPanel(spacing = 4)
        val row = actionsPanel.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center") }

        // D3: only rendered here (renderOrgWideContributions, TREASURER/BOARD/ADMIN-gated by the
        // caller) -- never on renderOwnSummary. See MailmergeHttp KDoc for why.
        row.link(tr("Rechnung (PDF)"), url = MailmergeHttp.invoiceUrl(contribution.id), target = "_blank")

        val outcomePanel = actionsPanel.vPanel(spacing = 2)
        if (postalMailEnabled) {
            val postalButton = row.button(tr("Per Post versenden"), style = ButtonStyle.OUTLINEDANGER)
            postalButton.onClick {
                postalDispatchConfirmDialog(
                    caption = tr("Beitragsrechnung per Post versenden"),
                    recipientDisplayName = contribution.memberDisplayName,
                    documentLabel = gettext("Beitragsrechnung %1–%2", contribution.periodStart, contribution.periodEnd),
                ) {
                    postalButton.disabled = true
                    outcomePanel.removeAll()
                    AppScope.launch {
                        val result = guarded { rpcService<IPostalMailService>().dispatchBeitragsrechnungByPost(contribution.id) }
                        postalButton.disabled = false
                        if (result != null) {
                            if (result.status == PostalDeliveryStatus.SENT) {
                                notifySuccess(gettext("Brief an %1 wurde an Letterxpress übergeben.", result.recipientDisplayName))
                            } else {
                                notifyError(tr("Postversand fehlgeschlagen."))
                            }
                            outcomePanel.renderPostalDispatchOutcome(result)
                        }
                    }
                }
            }
        } else {
            row.postalMailDisabledNotice()
        }

        // Review Round 3 (2026-08-19, SHOULD-5): canMarkPaid/canWaive are role-only gates, never
        // status-aware on their own -- an already-SETTLED (PAID/WAIVED) row must still hide both
        // buttons, or a stale-rendered row (e.g. another actor settled it between this list's fetch
        // and the click, or a race between the two buttons on the same row) surfaces a raw, English,
        // technical ConflictException to the user instead of the button simply not being there. Same
        // "hide, don't just disable, an action that would always fail" convention this codebase already
        // follows elsewhere for status-gated actions.
        val isSettled = contribution.status in ContributionStatusSets.SETTLED
        if (canMarkPaid && !isSettled) {
            val payButton = row.button(tr("Als bezahlt markieren"), style = ButtonStyle.SUCCESS)
            payButton.onClick {
                // Disabled for the duration of the in-flight RPC call -- reduces (does not replace,
                // see ContributionService.markContributionPaid's own server-side idempotency guard)
                // the chance an accidental double-click double-posts a journal entry. Same pattern as
                // LedgerScreen.kt's saveButton around the account-mapping save call. Review Round 1
                // (2026-08-19, CRITICAL-2).
                payButton.disabled = true
                AppScope.launch {
                    try {
                        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                        val result =
                            guarded {
                                rpcService<IContributionService>().markContributionPaid(
                                    contribution.id,
                                    now,
                                    contribution.amountDue,
                                    null,
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Als bezahlt markiert."))
                            onChanged()
                        }
                    } finally {
                        // Review Round 2 (2026-08-19, SHOULD-3): guarded() rethrows CancellationException
                        // (see its own KDoc/implementation) -- a plain post-guarded() re-enable never runs
                        // if this coroutine is cancelled mid-flight, leaving the button permanently
                        // disabled until a page refresh. finally runs regardless of success, a business
                        // exception guarded() swallowed, or cancellation.
                        payButton.disabled = false
                    }
                }
            }
        }
        if (canWaive && !isSettled) {
            val waiveButton = row.button(tr("Erlassen"), style = ButtonStyle.OUTLINEWARNING)
            waiveButton.onClick {
                confirmDialog(
                    title = tr("Beitrag erlassen"),
                    message =
                        gettext("Beitrag von %1 über %2 wirklich erlassen?", contribution.memberDisplayName, contribution.amountDue),
                    confirmLabel = tr("Erlassen"),
                ) {
                    AppScope.launch {
                        val result = guarded { rpcService<IContributionService>().markContributionWaived(contribution.id, null) }
                        if (result != null) {
                            notifySuccess(tr("Beitrag erlassen."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}
