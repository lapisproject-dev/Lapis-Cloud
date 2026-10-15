package network.lapis.cloud.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.input
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import network.lapis.cloud.server.branding.ResolvedBranding
import java.net.URI

/**
 * Welle V1.9.135 -- shared shell and headers of the five sign-in / consent / error pages
 * (guest consent, home-server form, OIDC error page, Keycloak error page, MCP consent).
 *
 * Rules (also in the repo `CLAUDE.md`): every auth HTML page is rendered through [renderAuthPage] and answered after
 * [applyAuthPageHeaders]; there is no `<script>`, no inline style and no `unsafe {}` in this file -- all escaping is
 * kotlinx.html's own. Texts shown on error pages are fixed constants, never user-controlled values.
 */
internal enum class AuthFormAction {
    /** The CSP carries `form-action 'self'` (error pages: they contain no form at all). */
    SELF,

    /**
     * The CSP carries NO `form-action` (the two consent pages and the home-server form): Chrome checks the 302 that
     * follows a form POST against `form-action`. The consent POST answers with a 302 to the foreign `redirect_uri`
     * (validated server-side against the client registration, `OidcRedirectUriMatcher`, before it is issued); the
     * home-server form POST answers with a 302 to the discovered `authorization_endpoint` of the foreign home server
     * (SSRF-checked). These pages deliberately build no CSP allowlist of their own.
     */
    UNRESTRICTED,
}

/**
 * The security headers of the auth pages. NEWLY INTRODUCED in V1.9.135 -- none of these pages set any of them before.
 *
 * `Referrer-Policy` is `same-origin`, NOT `no-referrer` (unlike `applyPublicPageHeaders`): `POST /federation/oidc/rp/login`
 * checks `isSameOriginRequest`, which answers 403 for `Origin: null`; browsers send `Origin: null` for a form POST from a
 * document with `no-referrer`, which would break the guest login. `same-origin` sends the real Origin to ourselves and no
 * Referer to anybody else.
 */
internal fun ApplicationCall.applyAuthPageHeaders(
    formAction: AuthFormAction,
    imgSrcSelf: Boolean,
) {
    val img = if (imgSrcSelf) "img-src 'self' data:;" else "img-src data:;"
    val fa = if (formAction == AuthFormAction.SELF) " form-action 'self';" else ""
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'self'; $img base-uri 'none';$fa frame-ancestors 'none'",
    )
    response.header("X-Frame-Options", "DENY")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "same-origin")
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
}

/** The common page shell: brand-only chrome (no navigation), a card, the public footer. The doctype is added by hand (kotlinx.html emits none). */
internal fun renderAuthPage(
    title: String,
    branding: ResolvedBranding,
    baseUrl: String,
    content: DIV.() -> Unit,
): String =
    "<!doctype html>\n" +
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                meta(name = "robots", content = "noindex, nofollow")
                title { +"$title – ${branding.title}" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
            }
            body(classes = "has-chrome auth-page") {
                with(PublicChrome) { renderAuthChrome(lang = PublicLanguage.DEFAULT, baseUrl = baseUrl, branding = branding) }
                main {
                    attributes["id"] = "main"
                    div(classes = "auth-card") { content() }
                }
                with(PublicChrome) { renderPublicFooter(lang = PublicLanguage.DEFAULT, baseUrl = baseUrl, branding = branding) }
            }
        }

/** The host of [redirectUri] (no port, never the whole URL), or `null` when it does not parse or has no host. */
internal fun redirectHostOrNull(redirectUri: String): String? =
    runCatching { URI(redirectUri).host }.getOrNull()?.takeIf { it.isNotBlank() }

internal fun FlowContent.authActions(block: DIV.() -> Unit) {
    div(classes = "auth-actions") { block() }
}

internal fun FlowContent.authErrorCallout(message: String) {
    div(classes = "auth-callout auth-callout--error") {
        attributes["role"] = "alert"
        +message
    }
}

/** The shared error page. [message] must be a fixed constant, never a user-controlled value. */
internal fun authErrorPageHtml(
    message: String,
    branding: ResolvedBranding,
    baseUrl: String,
): String =
    renderAuthPage(title = "Anmeldung fehlgeschlagen", branding = branding, baseUrl = baseUrl) {
        h1 { +"Anmeldung fehlgeschlagen" }
        authErrorCallout(message)
        authActions {
            a(href = baseUrl, classes = "auth-secondary") { +"Zur Startseite" }
        }
    }

/** A hidden form field. Attribute order is `type`, `name`, `value` -- existing route tests match on exactly that. */
internal fun FORM.hiddenField(
    name: String,
    value: String,
) {
    input(type = InputType.hidden, name = name) { this.value = value }
}
