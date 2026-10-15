package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDate
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h1
import kotlinx.html.p
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.pdf.formatGermanDate

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the unauthenticated verification page behind the
 * card's QR code (`GET /ausweis?code=...`). Same rendering discipline as [EventPublicHtml]:
 * `kotlinx.html`'s escaping APIs only, no `unsafe { }`, no `<script>` anywhere.
 *
 * **One negative page for every negative outcome.** [invalidPage] is what an unknown code, a
 * revoked code, a syntactically broken code and a code belonging to someone who is no longer a
 * member all render -- byte-identical text, identical HTTP status. Distinguishing them would hand
 * an unauthenticated caller an oracle: "this code exists but was revoked" already confirms that a
 * card with that value was once issued, and "this person is no longer a member" leaks a membership
 * history about a named individual to anyone holding an old card. The negative answer this page
 * gives is therefore deliberately uninformative: not valid, ask the organization.
 *
 * **What the positive page shows, and why it shows a name at all.** Name, member number, status
 * and joining date -- the same four fields printed on the card itself. This is the one place where
 * this codebase's usual "a public page reveals nothing about a person" posture (see
 * [EventPublicHtml]'s ticket page, which deliberately carries no name) does NOT apply: a
 * verification page exists to let a human compare the screen against the person standing in front
 * of them, and a page that answers only "yes, some valid code" verifies nothing -- a stolen card
 * would pass. The disclosure is narrow (four fields, no address, no email, no member UUID) and its
 * key is an 80-bit code the subject themselves carries.
 */
internal object MemberCardPublicHtml {
    data class ValidCard(
        val displayName: String,
        val memberNumber: String,
        val statusLabel: String,
        val joinedAt: LocalDate,
    )

    fun validPage(
        branding: ResolvedBranding,
        baseUrl: String,
        card: ValidCard,
    ): String =
        renderAuthPage(title = "Gültiger Mitgliedsausweis", branding = branding, baseUrl = baseUrl) {
            h1 { +"Gültiger Mitgliedsausweis" }
            authSuccessCallout { p("lead") { +card.displayName } }
            dl(classes = "auth-facts") {
                dt { +"Mitgliedsnummer" }
                dd { +card.memberNumber }
                dt { +"Status" }
                dd { +card.statusLabel }
                dt { +"Mitglied seit" }
                dd { +formatGermanDate(card.joinedAt) }
            }
            p { +"Bitte gleichen Sie Name und Mitgliedsnummer mit dem vorgelegten Ausweis ab." }
        }

    /**
     * The ONE negative page -- see the class KDoc for why there is exactly one. Deliberately a NEUTRAL callout (no
     * `--error`, no `role="alert"`): the result concerns a person, it is not a system alarm. Nothing input-dependent
     * may ever enter this page: for equal [branding]/[baseUrl] it is byte-identical.
     */
    fun invalidPage(
        branding: ResolvedBranding,
        baseUrl: String,
    ): String =
        renderAuthPage(title = "Kein gültiger Mitgliedsausweis", branding = branding, baseUrl = baseUrl) {
            h1 { +"Kein gültiger Mitgliedsausweis" }
            div(classes = "auth-callout") {
                +(
                    "Zu diesem Ausweis-Code liegt keine gültige Mitgliedschaft vor. Der Ausweis kann abgelaufen, " +
                        "ersetzt oder gesperrt worden sein. Bitte wenden Sie sich an die Geschäftsstelle."
                )
            }
            homeAction(baseUrl)
        }

    fun tooManyRequestsPage(
        branding: ResolvedBranding,
        baseUrl: String,
    ): String =
        renderAuthPage(title = "Zu viele Anfragen", branding = branding, baseUrl = baseUrl) {
            h1 { +"Zu viele Anfragen" }
            p { +"Bitte versuchen Sie es in einer Minute erneut." }
            homeAction(baseUrl)
        }

    fun errorPage(
        branding: ResolvedBranding,
        baseUrl: String,
    ): String =
        renderAuthPage(title = "Vorübergehend nicht verfügbar", branding = branding, baseUrl = baseUrl) {
            h1 { +"Vorübergehend nicht verfügbar" }
            p { +"Die Ausweisprüfung ist gerade nicht erreichbar. Bitte versuchen Sie es später erneut." }
            homeAction(baseUrl)
        }

    private fun FlowContent.homeAction(baseUrl: String) {
        authActions { a(href = baseUrl, classes = "auth-secondary") { +"Zur Startseite" } }
    }
}
