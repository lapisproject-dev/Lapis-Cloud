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
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatus
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
    root.h1("Beitragsübersicht")

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
    root.h2("Meine Beiträge")
    val panel = root.vPanel(spacing = 4)
    AppScope.launch {
        val summary = guarded { rpcService<IContributionService>().getMemberContributionSummary(memberId) } ?: return@launch
        panel.div("Offen: ${summary.totalOpen} | Bezahlt: ${summary.totalPaid} | Gesamt: ${summary.totalDue}")
        if (summary.contributions.isEmpty()) {
            panel.p("Keine Beiträge vorhanden.")
        } else {
            summary.contributions.forEach { contribution ->
                panel.div("${contribution.periodStart} bis ${contribution.periodEnd}: ${contribution.amountDue} (${contribution.status})") {
                    addCssClasses("border-bottom py-1")
                }
            }
        }
    }
}

private fun renderTierAdministration(root: SimplePanel) {
    root.h2("Beitragssätze und Beitragsgenerierung")
    val tiersPanel = root.vPanel(spacing = 4)
    val formPanel = root.vPanel(spacing = 6)

    AppScope.launch {
        val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() } ?: return@launch
        if (tiers.isEmpty()) {
            tiersPanel.p("Keine Beitragssätze vorhanden.")
            return@launch
        }
        tiers.forEach { tier ->
            val activeLabel = if (tier.active) "aktiv" else "inaktiv"
            tiersPanel.div("${tier.name}: ${tier.contributionAmount} (${tier.billingInterval}, $activeLabel)")
        }

        val tierOptions = tiers.map { it.id to it.name }
        val tierSelect = formPanel.select(options = tierOptions, value = tierOptions.firstOrNull()?.first, label = "Beitragssatz")
        val periodStartInput = formPanel.text(label = "Periodenbeginn (JJJJ-MM-TT)")
        val periodEndInput = formPanel.text(label = "Periodenende (JJJJ-MM-TT)")
        val errorBox =
            formPanel.div().apply {
                addCssClass("text-danger")
                hide()
            }

        val generateButton = formPanel.button("Beiträge generieren", style = ButtonStyle.PRIMARY)
        generateButton.onClick {
            errorBox.hide()
            val tierId = tierSelect.value
            val periodStart = runCatching { LocalDate.parse(periodStartInput.value.orEmpty().trim()) }.getOrNull()
            val periodEnd = runCatching { LocalDate.parse(periodEndInput.value.orEmpty().trim()) }.getOrNull()
            if (tierId == null || periodStart == null || periodEnd == null) {
                errorBox.content = "Bitte Beitragssatz sowie gültiges Beginn-/Enddatum (JJJJ-MM-TT) angeben."
                errorBox.show()
                return@onClick
            }
            generateButton.disabled = true
            AppScope.launch {
                val created = guarded { rpcService<IContributionService>().generateContributionsForPeriod(tierId, periodStart, periodEnd) }
                generateButton.disabled = false
                if (created != null) notifySuccess("$created neue Beiträge erzeugt (bereits vorhandene wurden übersprungen).")
            }
        }
    }
}

private fun renderOrgWideContributions(root: SimplePanel) {
    root.h2("Alle Beiträge")
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
                listPanel.p("Keine offenen Beiträge.")
                return@launch
            }
            contributions.forEach { contribution ->
                renderContributionRow(listPanel, contribution, canMarkPaid, canWaive, postalMailEnabled, ::refresh)
            }
        }
    }
    refresh()
}

private fun renderContributionRow(
    parent: SimplePanel,
    contribution: ContributionDto,
    canMarkPaid: Boolean,
    canWaive: Boolean,
    postalMailEnabled: Boolean,
    onChanged: () -> Unit,
) {
    val container = parent.vPanel(spacing = 2)
    val row = container.hPanel(spacing = 8) { addCssClasses("border rounded p-2 align-items-center") }
    row.div(
        "${contribution.memberDisplayName}: ${contribution.periodStart}–${contribution.periodEnd}: " +
            "${contribution.amountDue} (${contribution.status})",
    ) { addCssClass("flex-grow-1") }

    // D3: only rendered here (renderOrgWideContributions, TREASURER/BOARD/ADMIN-gated by the
    // caller) -- never on renderOwnSummary. See MailmergeHttp KDoc for why.
    row.link("Rechnung (PDF)", url = MailmergeHttp.invoiceUrl(contribution.id), target = "_blank")

    val outcomePanel = container.vPanel(spacing = 2)
    if (postalMailEnabled) {
        val postalButton = row.button("Per Post versenden", style = ButtonStyle.OUTLINEDANGER)
        postalButton.onClick {
            postalDispatchConfirmDialog(
                caption = "Beitragsrechnung per Post versenden",
                recipientDisplayName = contribution.memberDisplayName,
                documentLabel = "Beitragsrechnung ${contribution.periodStart}–${contribution.periodEnd}",
            ) {
                postalButton.disabled = true
                outcomePanel.removeAll()
                AppScope.launch {
                    val result = guarded { rpcService<IPostalMailService>().dispatchBeitragsrechnungByPost(contribution.id) }
                    postalButton.disabled = false
                    if (result != null) {
                        if (result.status == PostalDeliveryStatus.SENT) {
                            notifySuccess("Brief an ${result.recipientDisplayName} wurde an Letterxpress übergeben.")
                        } else {
                            notifyError("Postversand fehlgeschlagen.")
                        }
                        outcomePanel.renderPostalDispatchOutcome(result)
                    }
                }
            }
        }
    } else {
        row.postalMailDisabledNotice()
    }

    if (canMarkPaid) {
        val payButton = row.button("Als bezahlt markieren", style = ButtonStyle.SUCCESS)
        payButton.onClick {
            AppScope.launch {
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val result =
                    guarded {
                        rpcService<IContributionService>().markContributionPaid(contribution.id, now, contribution.amountDue, null)
                    }
                if (result != null) {
                    notifySuccess("Als bezahlt markiert.")
                    onChanged()
                }
            }
        }
    }
    if (canWaive) {
        val waiveButton = row.button("Erlassen", style = ButtonStyle.OUTLINEWARNING)
        waiveButton.onClick {
            confirmDialog(
                title = "Beitrag erlassen",
                message = "Beitrag von ${contribution.memberDisplayName} über ${contribution.amountDue} wirklich erlassen?",
                confirmLabel = "Erlassen",
            ) {
                AppScope.launch {
                    val result = guarded { rpcService<IContributionService>().markContributionWaived(contribution.id, null) }
                    if (result != null) {
                        notifySuccess("Beitrag erlassen.")
                        onChanged()
                    }
                }
            }
        }
    }
}
