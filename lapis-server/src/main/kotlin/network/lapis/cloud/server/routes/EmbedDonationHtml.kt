package network.lapis.cloud.server.routes

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

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- the two rückkehr
 * pages a Stripe Checkout redirects an anonymous donor to (`GET /embed/v1/spende/danke`/
 * `/abgebrochen`, see `EmbedDonationRoutes.kt`). Same rendering discipline as [EmbedHtml]:
 * `kotlinx.html`'s ordinary, HTML-escaping text-node/attribute APIs only, no HTML-escape-bypassing
 * API anywhere in this file (checked by a source-text scan test, `EmbedDonationPagesTest`, same
 * pattern `EmbedHtmlTest` establishes for [EmbedHtml]).
 *
 * **[requesterOriginHost]/[canonicalOrigin] are always the HOST/VALUE of the CANONICAL allowlist
 * entry**, never the raw `?origin=` query parameter -- mirrors [EmbedHtml] KDoc point 2. Both are
 * `null` when the `?origin=` parameter is missing or does not canonicalize to an allowlisted entry
 * -- in that case the page renders WITHOUT a return link, still `HTTP 200` (never an error status:
 * the visitor has just paid, or just cancelled -- see `EmbedDonationRoutes.kt` KDoc for why an error
 * status here would be the worst possible moment).
 */
internal object EmbedDonationHtml {
    fun thanksPage(
        brandTitle: String,
        canonicalOrigin: String?,
    ): String =
        page(
            brandTitle = brandTitle,
            heading = "Vielen Dank",
            message =
                "Vielen Dank. Ihre Zahlung wurde bei unserem Zahlungsdienstleister ausgelöst. Die Bestätigung " +
                    "erhalten Sie direkt von Stripe per E-Mail. Zu dieser Spende speichern wir weder Namen noch " +
                    "E-Mail-Adresse.",
            canonicalOrigin = canonicalOrigin,
        )

    fun cancelledPage(
        brandTitle: String,
        canonicalOrigin: String?,
    ): String =
        page(
            brandTitle = brandTitle,
            heading = "Spende abgebrochen",
            message = "Spende abgebrochen. Es wurde kein Betrag abgebucht.",
            canonicalOrigin = canonicalOrigin,
        )

    private fun page(
        brandTitle: String,
        heading: String,
        message: String,
        canonicalOrigin: String?,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"$heading – $brandTitle" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
            }
            body {
                div("embed-page") {
                    h1 { +brandTitle }
                    p { +message }
                    if (canonicalOrigin != null) {
                        val host = runCatching { java.net.URI(canonicalOrigin).host }.getOrNull() ?: canonicalOrigin
                        p { a(href = canonicalOrigin) { +"Zurück zu $host" } }
                    }
                }
            }
        }
}
