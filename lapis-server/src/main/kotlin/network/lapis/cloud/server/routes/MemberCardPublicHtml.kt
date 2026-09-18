package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDate
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.title
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
        brandTitle: String,
        card: ValidCard,
    ): String =
        skeleton(brandTitle = brandTitle, heading = "Gültiger Mitgliedsausweis") {
            p("lead") { +card.displayName }
            p { +"Mitgliedsnummer: ${card.memberNumber}" }
            p { +"Status: ${card.statusLabel}" }
            p { +"Mitglied seit: ${formatGermanDate(card.joinedAt)}" }
            p { +"Bitte gleichen Sie Name und Mitgliedsnummer mit dem vorgelegten Ausweis ab." }
        }

    /** The ONE negative page -- see the class KDoc for why there is exactly one. */
    fun invalidPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Kein gültiger Mitgliedsausweis") {
            p {
                +(
                    "Zu diesem Ausweis-Code liegt keine gültige Mitgliedschaft vor. Der Ausweis kann abgelaufen, " +
                        "ersetzt oder gesperrt worden sein. Bitte wenden Sie sich an die Geschäftsstelle."
                )
            }
        }

    fun tooManyRequestsPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Zu viele Anfragen") {
            p { +"Bitte versuchen Sie es in einer Minute erneut." }
        }

    fun errorPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Vorübergehend nicht verfügbar") {
            p { +"Die Ausweisprüfung ist gerade nicht erreichbar. Bitte versuchen Sie es später erneut." }
        }

    private fun skeleton(
        brandTitle: String,
        heading: String,
        content: FlowContent.() -> Unit,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                // `noindex`: a verification result is a per-code answer, never a search result.
                meta(name = "robots", content = "noindex,nofollow")
                title { +"$heading – $brandTitle" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
            }
            body {
                div("embed-page") {
                    h1 { +heading }
                    content()
                    div("embed-footer") {
                        a(href = "/impressum") { +"Impressum" }
                        +" · "
                        a(href = "/datenschutz") { +"Datenschutz" }
                    }
                }
            }
        }
}
