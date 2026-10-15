package network.lapis.cloud.server.routes

import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.ul
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.federation.OidcScopes

/**
 * Welle V1.8.1 MCP-Server -- the consent screen rendered ONLY for an MCP-scoped `/authorize`
 * request, a deliberately SEPARATE screen from `consentPageHtml` (the guest-federation consent
 * page in `OidcRoutes.kt`). Since V1.9.135 both are rendered through [renderAuthPage] and no longer
 * byte-identical to their raw-string predecessors; the CONTENT (wording, scope doctrine) is unchanged.
 *
 * Design requirements this screen must satisfy (Design-Team review, Kare/Norman/Jobs):
 * - A FIXED heading, never influenced by the client's self-declared name.
 * - The self-declared `clientName` appears only as a quoted, explicitly-flagged-as-unverified
 *   attribute -- filtered of control characters and hard-capped at 80 characters before escaping.
 * - No raw OAuth scope string is ever shown -- a plain-language list of the data areas the grant
 *   covers, plus an explicit "what the agent cannot do" paragraph.
 * - A REQUIRED `connection_label` field (max 60 chars) -- the name the member gives this
 *   connection, later shown in their own revocation list (`rpc.McpAccessService
 *   .getMcpAccessState` -- the member-facing screen consuming it is a follow-up, not built this
 *   session, see `docs/architecture/mcp-server.adoc` "What doesn't work yet").
 *
 * **Welle V1.8.2 amendment**: [scopeParam] may now additionally carry
 * [OidcScopes.MCP_MEMBER_WRITE]. When it does, a SECOND block is rendered, naming exactly the two
 * write tools' effects -- never a raw scope string, same doctrine as the read block above it. The
 * "what the agent cannot do" paragraph is corrected accordingly: this screen must never promise
 * "nichts ändern" while write scope is being requested, and even for a read-only request the old
 * wording was already a standing lie about a token minted under this exact scope literal once
 * write tools exist server-side (see plan §0.1) -- so the corrected wording always says
 * "nicht veröffentlichen" (a released draft still needs the member's own release action) rather
 * than "nichts ändern".
 *
 * **Security-Review MEDIUM fix (Welle V1.8.2 MCP write-paths), superseded (Welle V1.8.2b)**: this
 * IS the authorization boundary for the write scope -- there is no second, per-call confirmation
 * once a token is minted (see `McpToolDispatcher.dispatch` KDoc). The original fix named the money
 * consequence of `register_for_event` explicitly (a real PSP checkout session, a "please pay"
 * email, and the payment URL handed to the calling agent's own third-party LLM provider) because
 * that path used to exist. **Welle V1.8.2b closed it instead** (see `mcp.tools
 * .McpEventRequiresPaymentException` KDoc "No payment through MCP") -- `register_for_event` now
 * only ever registers for a GEBÜHRENFREIE event, so this screen no longer needs to disclose a money
 * consequence that can no longer happen; the write block below says so plainly. Keep
 * `deploy/example/README.adoc`'s operator-facing description of this same screen in sync with any
 * further change here.
 */
internal fun mcpConsentPageHtml(
    clientName: String,
    clientId: String,
    redirectUri: String,
    scopeParam: String,
    state: String,
    codeChallenge: String,
    nonce: String?,
    resource: String,
    branding: ResolvedBranding,
    baseUrl: String,
): String {
    val shownClientName = clientName.filterNot { it.isISOControl() }.take(80)
    val requestedScopes = scopeParam.split(" ").filter { it.isNotBlank() }.toSet()
    val requestsWrite = OidcScopes.MCP_MEMBER_WRITE in requestedScopes
    val host = redirectHostOrNull(redirectUri)
    return renderAuthPage(title = "KI-Agent-Zugriff genehmigen", branding = branding, baseUrl = baseUrl) {
        h1 { +"Ein KI-Agent möchte auf Ihre Daten zugreifen." }
        p {
            +"Der Agent nennt sich „"
            strong { +shownClientName }
            +"“. Diesen Namen hat der Agent selbst angegeben – er ist nicht geprüft."
        }
        if (host != null) {
            p(classes = "auth-redirect") {
                +"Sie werden danach zu "
                strong { +host }
                +" weitergeleitet."
            }
        }
        p { +"Bei Zustimmung kann der Agent lesend auf folgende eigene Daten zugreifen:" }
        ul {
            li { +"Eigener Beitragsstand" }
            li { +"Eigener LTR-Kontostand" }
            li { +"Satzung und veröffentlichte Dokumente (Volltextsuche)" }
            li { +"Bevorstehende Veranstaltungen" }
            li { +"Eigene abgegebene Stimmzettel" }
            li { +"Eigene Artikel (nur Titel und Status) – nur wenn Sie Redaktionsrechte haben (Vorstand oder Administration)" }
        }
        if (requestsWrite) {
            p { +"Bei Zustimmung kann der Agent zusätzlich in Ihrem Namen:" }
            ul {
                li {
                    +"Sich selbst für eine "
                    strong { +"gebührenfreie" }
                    +" Veranstaltung anmelden – kostenpflichtige Anmeldungen sind über einen Agenten nicht möglich"
                }
                li { +"Einen unveröffentlichten Beitrags-Entwurf anlegen" }
                li {
                    +(
                        "Einen unveröffentlichten Artikel-Entwurf anlegen – nur wenn Sie Redaktionsrechte haben " +
                            "(Vorstand oder Administration). Einreichen können nur Sie selbst; freigegeben wird wie jeder " +
                            "Artikel durch ein anderes Vorstandsmitglied."
                    )
                }
            }
        }
        p {
            strong { +"Was der Agent nicht kann:" }
            +(
                " nicht veröffentlichen (jeder Entwurf braucht Ihre Freigabe in der Weboberfläche), nicht abstimmen, " +
                    "keine Daten anderer Mitglieder sehen, keine Stimmgewichte berechnen."
            )
        }
        form(action = "/federation/oidc/authorize/consent", method = FormMethod.post) {
            hiddenField(name = "client_id", value = clientId)
            hiddenField(name = "redirect_uri", value = redirectUri)
            hiddenField(name = "scope", value = scopeParam)
            hiddenField(name = "state", value = state)
            hiddenField(name = "code_challenge", value = codeChallenge)
            if (nonce != null) hiddenField(name = "nonce", value = nonce)
            hiddenField(name = "resource", value = resource)
            div(classes = "auth-field") {
                label {
                    htmlFor = "connection_label"
                    +"Name dieser Verbindung (frei wählbar, z. B. „Claude Desktop“)"
                }
                input(type = InputType.text, name = "connection_label") {
                    id = "connection_label"
                    required = true
                    maxLength = "60"
                }
            }
            authActions {
                button(type = ButtonType.submit, classes = "auth-primary") {
                    name = "decision"
                    value = "allow"
                    +"Erlauben"
                }
                button(type = ButtonType.submit, classes = "auth-secondary") {
                    name = "decision"
                    value = "deny"
                    +"Ablehnen"
                }
            }
        }
    }
}
