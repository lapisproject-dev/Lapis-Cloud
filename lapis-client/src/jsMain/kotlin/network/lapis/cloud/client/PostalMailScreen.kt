package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PostalDeliveryLogDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService

/**
 * Mail-merge/Postal-Dispatch UI wave, part 3 -- "Postversand" (design decisions D7/D8/D11), the
 * final piece of the wave: a read-only audit trail (`listPostalDeliveryLog`) plus the shared
 * confirm-dialog/outcome-rendering machinery every dispatch trigger elsewhere in this client
 * reuses (`ContributionsScreen.renderContributionRow`, `LedgerScreen.renderDonorInfo`,
 * `MeetingsScreen`'s Einladung section).
 *
 * **Load-bearing finding that shapes every dispatch confirm dialog below (approved design
 * decisions, "Load-bearing finding")**: no RPC or route in this wave's scope ever returns a
 * member's raw postal address to the client -- [network.lapis.cloud.shared.domain.MemberSummaryDto]
 * is `{id, displayName}` only, [PostalDeliveryLogDto] carries [PostalDeliveryLogDto.recipientDisplayName],
 * never street/postalCode/city/country, and the two mail-merge PDF routes stream bytes, never JSON
 * with address fields. The full [network.lapis.cloud.shared.domain.MemberDto] address fields are
 * server-internal only (`network.lapis.cloud.server.routes.loadMailmergeMember`, never serialized
 * out). This resolves the task's "member's postal address data is only shown to appropriately-
 * privileged staff" concern **by construction** -- the address cannot leak through this UI because
 * it is never sent to the browser at all. Do not add a fetch/display of it anywhere in this client;
 * that would require a new backend endpoint out of scope for this "standard frontend" wave.
 * Consequently every confirm dialog below shows only the recipient's display name and a
 * plain-language statement that a letter goes "to the address on file, resolved server-side" --
 * never a fabricated or fetched address line.
 *
 * Route `Routes.POSTAL_MAIL`, `requireRole(TREASURER, BOARD, ADMIN)` -- matches
 * `listPostalDeliveryLog`'s own tier (`PostalMailService.kt`'s `FINANCIAL_DISPATCH_ROLES`), the
 * narrowest tier that needs to *reach* this screen at all (Einladung dispatch, BOARD/ADMIN-only,
 * is gated narrower still inside `MeetingsScreen.kt`'s Einladung section).
 */
fun renderPostalMailScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Postversand"))
    root.div(
        tr(
            "Protokoll aller bisherigen postalischen Versandvorgänge -- rein informativ, keine Aktionen auf " +
                "dieser Liste. Neue Versandvorgänge werden direkt bei der jeweiligen Beitragsrechnung, " +
                "Spendenbescheinigung oder Sitzungseinladung ausgelöst.",
        ),
    ) { addCssClasses("text-muted small") }

    val bannerPanel = root.vPanel(spacing = 4)
    AppScope.launch {
        if (!isPostalMailEnabled()) {
            bannerPanel.div(
                tr(
                    "Postversand ist derzeit deaktiviert (`OrganizationSettings.postalMailEnabled = false`) -- " +
                        "alle Versandaktionen (Beitragsrechnung, Spendenbescheinigung, Einladung) schlagen fehl, " +
                        "bis eine Administratorin oder ein Administrator dies aktiviert. Diese Einstellung hat in " +
                        "dieser Version noch keine eigene Oberfläche.",
                ),
            ) { addCssClasses("alert alert-warning") }
        }
    }

    root.h2(tr("Verlauf"))
    val logPanel = root.vPanel(spacing = 6)
    AppScope.launch {
        val log = guarded { rpcService<IPostalMailService>().listPostalDeliveryLog() } ?: return@launch
        if (log.isEmpty()) {
            logPanel.p(tr("Noch keine postalischen Versandvorgänge protokolliert."))
        } else {
            log.forEach { entry -> renderPostalDeliveryLogRow(logPanel, entry) }
        }
    }
}

/**
 * D8: [PostalDeliveryLogDto] has no structured back-reference to the originating
 * contribution/journal-entry/meeting -- only the free-text [PostalDeliveryLogDto.documentReference].
 * Accepted as-is, no fabricated "jump to source" link -- same honesty call as
 * `DsgvoRightsScreen`'s `dsgvoAuditActorDisplayText` precedent ("no display-name resolution exists,
 * show the raw value").
 */
private fun renderPostalDeliveryLogRow(
    panel: SimplePanel,
    entry: PostalDeliveryLogDto,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.statusBadge(postalDeliveryStatusLabel(entry.status), postalDeliveryStatusColor(entry.status))
    headerRow.div(entry.recipientDisplayName) { addCssClasses("flex-grow-1") }
    headerRow.div(gettext("%1", entry.dispatchedAt)) { addCssClasses("text-muted small") }
    row.div(entry.documentReference) { addCssClasses("small") }

    // Never both -- SENT carries a providerReference, FAILED carries an errorMessage, QUEUED (dead
    // today) carries neither.
    entry.providerReference?.takeIf { entry.status == PostalDeliveryStatus.SENT }?.let { reference ->
        row.div(gettext("Sendungsreferenz: %1", reference)) { addCssClasses("text-muted small") }
    }
    entry.errorMessage?.takeIf { entry.status == PostalDeliveryStatus.FAILED }?.let { message ->
        row.div(gettext("Fehler: %1", message)) { addCssClasses("text-danger small") }
    }
}

/**
 * [PostalDeliveryStatus] label/color table (D8/D11). `QUEUED` is dead today --
 * `PostalMailService.dispatchAndLog` only ever writes `SENT` or `FAILED` synchronously (see
 * `IPostalMailService`/`PostalMailService.kt` KDoc "Delivery status") -- reserved for a future
 * async/webhook-based delivery-status-callback follow-up. Kept and documented, not deleted as
 * unreachable, same posture as `DsgvoRightsScreen.legalHoldIndicator`.
 */
fun postalDeliveryStatusLabel(status: PostalDeliveryStatus): String =
    when (status) {
        PostalDeliveryStatus.SENT -> gettext("Versendet")
        PostalDeliveryStatus.FAILED -> gettext("Fehlgeschlagen")
        PostalDeliveryStatus.QUEUED -> gettext("In Bearbeitung")
    }

fun postalDeliveryStatusColor(status: PostalDeliveryStatus): String =
    when (status) {
        PostalDeliveryStatus.SENT -> "success"
        PostalDeliveryStatus.FAILED -> "danger"
        PostalDeliveryStatus.QUEUED -> "secondary"
    }

/**
 * D7: single shared "is postal dispatch currently possible" check, used by every dispatch-trigger
 * call site (`ContributionsScreen`, `LedgerScreen`, `MeetingsScreen`, and this screen's own banner)
 * purely to disable/explain the UI ahead of time -- never to gate more strictly than the server
 * already does. `PostalMailService.requirePostalMailEnabled` remains the sole authority; this is a
 * courtesy pre-check that avoids a confusing `ConflictException` toast being a caller's only signal,
 * not a security boundary.
 */
suspend fun isPostalMailEnabled(): Boolean =
    guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }?.postalMailEnabled ?: false

/** D7: replaces a dispatch trigger button at every call site when [isPostalMailEnabled] is false. */
fun SimplePanel.postalMailDisabledNotice() {
    val row = hPanel(spacing = 4) { addCssClasses("align-items-center") }
    row.div(tr("Postversand ist derzeit deaktiviert --")) { addCssClasses("text-muted small") }
    row.link(tr("Postversand-Übersicht"), url = "#${Routes.POSTAL_MAIL}") { addCssClasses("small") }
}

/**
 * D5: shared single-log-entry outcome rendering, reused by every dispatch trigger call site
 * (`ContributionsScreen` row, `LedgerScreen` donor block, `MeetingsScreen`'s batch Einladung
 * dispatch, one call per recipient). Every branch renders inline, distinctly for SENT vs. FAILED --
 * a bare success toast would misreport a real per-letter failure as if it went fine, since all
 * three dispatch RPCs return [PostalDeliveryLogDto] normally even on failure (a
 * `PostalDispatchOutcome.Failed` is a legitimate business outcome server-side, not a thrown
 * exception -- see `PostalMailService.kt` KDoc "Delivery status").
 */
fun SimplePanel.renderPostalDispatchOutcome(log: PostalDeliveryLogDto) {
    when (log.status) {
        PostalDeliveryStatus.SENT -> {
            val box = vPanel(spacing = 2) { addCssClasses("alert alert-success mt-2") }
            box.div(gettext("Brief an %1 wurde an Letterxpress übergeben.", log.recipientDisplayName)) { addCssClass("fw-bold") }
            log.providerReference?.let { reference ->
                box.div(gettext("Sendungsreferenz: %1", reference)) { addCssClasses("text-muted small") }
            }
        }
        PostalDeliveryStatus.FAILED -> {
            val box = vPanel(spacing = 2) { addCssClasses("alert alert-danger mt-2") }
            box.div(
                gettext("Postversand an %1 ist fehlgeschlagen: %2", log.recipientDisplayName, log.errorMessage.orEmpty()),
            ) { addCssClass("fw-bold") }
        }
        PostalDeliveryStatus.QUEUED -> {
            // Dead today, see postalDeliveryStatusLabel KDoc -- kept and documented, not deleted.
            val box = vPanel(spacing = 2) { addCssClasses("alert alert-light border mt-2") }
            box.div(gettext("Brief an %1 wird verarbeitet.", log.recipientDisplayName))
        }
    }
}

/**
 * D5: bespoke confirm modal for single-recipient postal dispatch (Beitragsrechnung/
 * Spendenbescheinigung) -- matches-or-exceeds `BackupScreen.restoreConfirmDialog`'s /
 * `DsgvoRightsScreen.executeErasureConfirmDialog`'s irreversibility bar, per the task's explicit
 * requirement. The recipient's address is deliberately never shown (see this file's KDoc,
 * "Load-bearing finding") -- only their display name and the resolved document reference.
 */
fun postalDispatchConfirmDialog(
    caption: String,
    recipientDisplayName: String,
    documentLabel: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = caption)
    modal.div(tr("Dieser Versand ist ENDGÜLTIG und verursacht reale Kosten.")) { addCssClasses("fw-bold text-danger") }
    modal.div(
        gettext(
            "Ein physischer Brief wird über den Postdienstleister Letterxpress an die im System hinterlegte " +
                "Anschrift von %1 verschickt. Der Versand kann nicht zurückgerufen werden.",
            recipientDisplayName,
        ),
    )

    val recipientRow = modal.hPanel(spacing = 8) { addCssClasses("border rounded p-2 mt-2 small") }
    recipientRow.div(tr("Empfänger:")) { addCssClasses("text-muted") }
    recipientRow.div(recipientDisplayName) { addCssClass("flex-grow-1") }
    val documentRow = modal.hPanel(spacing = 8) { addCssClasses("border rounded p-2 mb-2 small") }
    documentRow.div(tr("Dokument:")) { addCssClasses("text-muted") }
    documentRow.div(documentLabel) { addCssClass("flex-grow-1") }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Jetzt per Post versenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * D5: bespoke confirm modal for the batch Einladung postal dispatch (`MeetingsScreen`) -- same
 * irreversibility bar as [postalDispatchConfirmDialog], list-shaped for multiple recipients.
 */
fun postalEinladungDispatchConfirmDialog(
    recipientDisplayNames: List<String>,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Einladung per Post versenden"))
    modal.div(tr("Dieser Versand ist ENDGÜLTIG und verursacht reale Kosten pro Brief.")) { addCssClasses("fw-bold text-danger") }
    modal.div(
        gettext(
            "%1 physische Briefe werden über Letterxpress an die im System hinterlegten " +
                "Anschriften der ausgewählten Mitglieder verschickt. Fehlt bei auch nur einer Person die " +
                "vollständige Anschrift, wird der gesamte Versand abgelehnt -- kein Teilversand.",
            recipientDisplayNames.size,
        ),
    )
    val listPanel = modal.vPanel(spacing = 2) { addCssClasses("border rounded p-2 mt-2 mb-2 small text-muted") }
    recipientDisplayNames.forEach { name -> listPanel.div(name) }
    modal.div(tr("Maximal 50 Empfänger pro Versand.")) { addCssClasses("text-muted small") }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Jetzt per Post versenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}
