package network.lapis.cloud.server.routes

import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.noScript
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.stream.createHTML
import kotlinx.html.title

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- the popup page rendered at
 * `GET /embed/v1/login` (see `EmbedRoutes.kt`). Same rendering discipline as
 * [SocialPublicHtml]/[PublicTransparencyHtml]: `kotlinx.html`'s ordinary, HTML-escaping text-node
 * and attribute-value APIs only, no HTML-escape-bypassing API anywhere in this file (checked by a
 * source-text scan test, see `EmbedHtmlTest`).
 *
 * **Sicherheits-Vertrag dieser Seite:**
 * 1. **Kein Wert wird je in JavaScript-Quelltext interpoliert.** [state]/[targetOrigin] reisen
 *    ausschließlich als `data-*`-Attribute (escaped by `kotlinx.html`'s attribute-value API exactly
 *    like every other attribute above); `/embed/v1/login.js` (`EmbedAssets.loginPopupJs`) reads them
 *    via `dataset` and is therefore a CONSTANT file -- `script-src 'self'` suffices, no per-response
 *    nonce needed (see `EmbedRoutes.kt`'s `applyEmbedPageHeaders` KDoc for the CSP this depends on).
 * 2. **[requesterOriginHost] is always the HOST of the CANONICAL allowlist entry**, never the raw
 *    `?origin=` query parameter -- output exclusively as a text node, never in an `href` or any
 *    attribute that could trigger navigation.
 * 3. The `<form>` carries no `action` (submitted via `fetch`, intercepted with
 *    `preventDefault()`); if the popup script fails to load, `form-action 'none'` in the CSP
 *    blocks the browser's own fallback submission -- a safe failure, with a `<noscript>` hint.
 *
 * This page never knows the caller's sign-in status at render time -- `GET /embed/v1/login` is a
 * cross-site top-level navigation, and `lapis_session` carries `SameSite=Strict`, so the cookie
 * structurally never arrives here (see `EmbedRoutes.kt`'s own KDoc on that route). The form is
 * therefore always rendered; `/embed/v1/login.js` decides at runtime, via a same-origin
 * `/api/embed/v1/session` probe, whether to show it or to report "already signed in" instead
 * (Review-Fund V1.4.1a: this function used to also accept `signedIn`/`displayName` parameters for a
 * cookie/DB lookup the caller made -- always `false`/`null` in production, since the same
 * `SameSite=Strict` reasoning applies just as much at request time as at the caller above it -- an
 * unreachable branch here and in `login-popup.js`; removed together with that lookup).
 */
internal object EmbedHtml {
    fun loginPage(
        baseUrl: String,
        brandTitle: String,
        requesterOriginHost: String,
        targetOrigin: String,
        state: String,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"Anmeldung – $brandTitle" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
            }
            body {
                div("embed-page") {
                    h1 { +brandTitle }
                    p { +"Sie melden sich für $requesterOriginHost an." }
                    p {
                        id = "lapis-status"
                        attributes["aria-live"] = "polite"
                    }
                    p("embed-error") { id = "lapis-error" }
                    form {
                        id = "lapis-login-form"
                        div("embed-field") {
                            label {
                                attributes["for"] = "lapis-email"
                                +"E-Mail-Adresse"
                            }
                            input(type = InputType.email) {
                                id = "lapis-email"
                                name = "email"
                                required = true
                                autoComplete = "email"
                            }
                        }
                        div("embed-field") {
                            label {
                                attributes["for"] = "lapis-password"
                                +"Passwort"
                            }
                            input(type = InputType.password) {
                                id = "lapis-password"
                                name = "password"
                                required = true
                            }
                        }
                        button(type = kotlinx.html.ButtonType.submit, classes = "embed-submit") { +"Anmelden" }
                    }
                    noScript { +"Für die Anmeldung wird JavaScript benötigt." }
                    div {
                        id = "lapis-embed-data"
                        attributes["hidden"] = ""
                        attributes["data-state"] = state
                        attributes["data-target-origin"] = targetOrigin
                    }
                }
                script(src = "/embed/v1/login.js") {}
            }
        }

    /**
     * `403`-page for an unknown/disallowed embedding origin. Deliberately generic -- never echoes
     * the offending `?origin=` value (no oracle, see [network.lapis.cloud.server.embed
     * .EmbedCors] KDoc for the same posture on the API side). No script, no form, nothing that
     * could `postMessage` -- the opener never receives any signal for this case, exactly the "kein
     * postMessage" contract in the class KDoc.
     */
    fun rejectedPage(
        baseUrl: String,
        brandTitle: String,
    ): String = genericNoticePage(brandTitle = brandTitle, message = "Diese Anmeldung ist für diese Website nicht freigeschaltet.")

    /** `400`-page for a malformed request (e.g. an invalid `state` parameter) -- same "no echo" posture as [rejectedPage]. */
    fun badRequestPage(
        baseUrl: String,
        brandTitle: String,
    ): String = genericNoticePage(brandTitle = brandTitle, message = "Ungültige Anfrage.")

    private fun genericNoticePage(
        brandTitle: String,
        message: String,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"Nicht verfügbar – $brandTitle" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
            }
            body {
                div("embed-page") {
                    h1 { +brandTitle }
                    p { +message }
                }
            }
        }
}
