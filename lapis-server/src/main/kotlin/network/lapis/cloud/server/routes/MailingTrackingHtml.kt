package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * Welle V1.9.15 -- the two neutral pages of `GET /m/c/{token}`. ONE 404 page for every failure of
 * the click route (malformed token, wrong MAC, unknown hash, missing link row, unsafe target): it
 * says nothing about WHICH check failed, so the route cannot be used as an oracle.
 *
 * **German only, like every public server-rendered page** (see [MemberCardPublicHtml]) -- there is
 * no server-side message catalog. `kotlinx.html` escaping APIs only, no `unsafe { }`, no script.
 *
 * V1.9.136: rendered through [renderAuthPage] (brand header, card, footer). There is NO unsubscribe page -- the
 * mailing has no token-based unsubscribe route; only these two neutral pages exist.
 */
internal object MailingTrackingHtml {
    fun notFoundPage(
        branding: ResolvedBranding,
        baseUrl: String,
    ): String =
        renderAuthPage(title = "Link nicht gefunden", branding = branding, baseUrl = baseUrl) {
            h1 { +"Link nicht gefunden" }
            p { +"Dieser Link ist nicht (mehr) gültig." }
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

    private fun FlowContent.homeAction(baseUrl: String) {
        authActions { a(href = baseUrl, classes = "auth-secondary") { +"Zur Startseite" } }
    }
}
