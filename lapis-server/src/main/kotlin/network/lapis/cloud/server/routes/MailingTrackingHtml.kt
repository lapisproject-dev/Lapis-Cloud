package network.lapis.cloud.server.routes

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

/**
 * Welle V1.9.15 -- the two neutral pages of `GET /m/c/{token}`. ONE 404 page for every failure of
 * the click route (malformed token, wrong MAC, unknown hash, missing link row, unsafe target): it
 * says nothing about WHICH check failed, so the route cannot be used as an oracle.
 *
 * **German only, like every public server-rendered page** (see [MemberCardPublicHtml]) -- there is
 * no server-side message catalog. `kotlinx.html` escaping APIs only, no `unsafe { }`, no script.
 */
internal object MailingTrackingHtml {
    fun notFoundPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Link nicht gefunden") {
            p { +"Dieser Link ist nicht (mehr) gültig." }
        }

    fun tooManyRequestsPage(brandTitle: String): String =
        skeleton(brandTitle = brandTitle, heading = "Zu viele Anfragen") {
            p { +"Bitte versuchen Sie es in einer Minute erneut." }
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
