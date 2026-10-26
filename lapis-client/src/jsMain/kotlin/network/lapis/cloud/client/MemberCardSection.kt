package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets

/** Milliseconds the card trigger stays disabled after a confirmed issue (see the click handler). */
internal const val MEMBER_CARD_REISSUE_COOLDOWN_MS = 2500L

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- Selbstbedienung fuer den eigenen Ausweis.
 *
 * **Warum hier und nicht in `ContributionsScreen`.** Der urspruengliche Wellen-Auftrag verwies auf
 * die Stelle, an der Rechnungs-/Spendenbescheinigungs-Downloads verlinkt sind. Die gibt es fuer ein
 * einfaches Mitglied nicht: `MailmergeHttp` ist server-seitig auf TREASURER/BOARD/ADMIN begrenzt
 * (siehe dessen KDoc, "Access note"), die Links erscheinen ausschliesslich in den
 * Vorstands-/Kassen-Ansichten. Der Ausweis ist die ERSTE echte Selbstbedienungs-PDF dieses
 * Produkts und gehoert damit zu "Meine Daten" (bis V1.9.219 im "Konto"-Block des Dashboards) -- neben Passwort
 * aendern und Austritt -- und nicht in eine Beitragsuebersicht, mit der er fachlich nichts zu tun
 * hat.
 *
 * **Warum ein Bestaetigungsschritt vor einem blossen Download.** Jeder Download praegt einen neuen
 * Ausweis-Code und entwertet den vorherigen (`MemberCardRoutes` KDoc). Ein Knopf, der still einen
 * bereits ausgedruckten Ausweis ungueltig macht, waere genau die Art unsichtbarer Nebenwirkung,
 * gegen die die Hausregel "keine folgenreiche Aktion ohne sichtbare Ansage" steht. Der Dialog sagt
 * die Folge vorher an, nicht hinterher.
 *
 * Rendert fuer Nicht-Mitglieder (Gast/Freund/Spender/Antragsteller/Ausgetretene) GAR NICHTS -- der
 * Server lehnt deren Ausstellung ohnehin mit 409 ab (`MemberCardEligibility`), und ein Knopf fuer
 * eine serverseitig abgelehnte Aktion ist dieselbe Hausregelverletzung wie in
 * `MemberAdministrationScreen`.
 */

internal fun renderMemberCard(
    root: SimplePanel,
    status: MemberStatus,
) {
    if (status !in MemberStatusSets.ORGANIZATION_MEMBER) return
    val memberId = AppState.session?.memberId ?: return
    val panel = root.vPanel(spacing = 6)
    panel.div(
        tr(
            "Der Ausweis ist im Scheckkartenformat und traegt einen QR-Code, ueber den die Mitgliedschaft " +
                "geprueft werden kann.",
        ),
    ) { addCssClasses("text-muted small") }
    val downloadButton = panel.actionButton(ActionIcon.DOWNLOAD, tr("Mitgliedsausweis herunterladen"), style = ButtonStyle.OUTLINESECONDARY)
    downloadButton.onClick {
        // Audit fix M9: issuing a card INVALIDATES the previous one -- the trigger stays disabled for a moment after a confirmed issue, so a
        // second click cannot stack a second issuing dialog on the first one's download (a second card would kill the one just downloaded).
        if (downloadButton.disabled) return@onClick
        confirmDialog(
            title = tr("Neuen Mitgliedsausweis ausstellen"),
            message =
                tr(
                    "Beim Herunterladen wird ein neuer Ausweis ausgestellt. Ein frueher heruntergeladener oder " +
                        "ausgedruckter Ausweis verliert damit seine Gueltigkeit.",
                ),
            confirmLabel = tr("Ausstellen und herunterladen"),
        ) {
            runGuardedAction(downloadButton) {
                MemberCardHttp.submitCardPdfDownload(memberId)
                delay(MEMBER_CARD_REISSUE_COOLDOWN_MS)
            }
        }
    }
}

/**
 * V1.9.220 -- the "Mitgliedsausweis" block of "Meine Daten": ONE heading, then the download and, below it, the revocation of a lost card.
 * Both parts exist for the same people (active organization members), so the heading never stands alone for a guest or a friend.
 */
internal fun renderMemberCardSection(
    root: SimplePanel,
    status: MemberStatus,
) {
    if (status !in MemberStatusSets.ORGANIZATION_MEMBER) return
    root.h2(tr("Mitgliedsausweis")) { addCssClass("h5") }
    renderMemberCard(root, status)
    renderMemberCardRevokeSection(root, heading = false)
}
