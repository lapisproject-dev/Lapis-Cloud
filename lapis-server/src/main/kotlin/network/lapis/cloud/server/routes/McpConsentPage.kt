package network.lapis.cloud.server.routes

import network.lapis.cloud.server.federation.OidcScopes

/**
 * Welle V1.8.1 MCP-Server -- the consent screen rendered ONLY for an MCP-scoped `/authorize`
 * request, a deliberately SEPARATE screen from [consentPageHtml] (the guest-federation consent
 * page in `OidcRoutes.kt`, which stays byte-identical -- see design-team requirement "Gast-Consent
 * unangetastet").
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
    nonce: String,
    resource: String,
): String {
    val safeClientName = htmlEscape(clientName.filterNot { it.isISOControl() }.take(80))
    val requestedScopes = scopeParam.split(" ").filter { it.isNotBlank() }.toSet()
    val requestsWrite = OidcScopes.MCP_MEMBER_WRITE in requestedScopes
    val writeBlock =
        if (requestsWrite) {
            """
            <p>Bei Zustimmung kann der Agent zusätzlich in Ihrem Namen:</p>
            <ul>
              <li>Sich selbst für eine <strong>gebührenfreie</strong> Veranstaltung anmelden --
              kostenpflichtige Anmeldungen sind über einen Agenten nicht möglich</li>
              <li>Einen unveröffentlichten Beitrags-Entwurf anlegen</li>
            </ul>
            """.trimIndent()
        } else {
            ""
        }
    return """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>KI-Agent-Zugriff genehmigen</title></head>
        <body>
        <h1>Ein KI-Agent möchte auf Ihre Daten zugreifen.</h1>
        <p>Der Agent nennt sich &bdquo;<strong>$safeClientName</strong>&ldquo;. Diesen Namen hat der Agent selbst angegeben -- er ist nicht geprüft.</p>
        <p>Bei Zustimmung kann der Agent lesend auf folgende eigene Daten zugreifen:</p>
        <ul>
          <li>Eigener Beitragsstand</li>
          <li>Eigener LTR-Kontostand</li>
          <li>Satzung und veröffentlichte Dokumente (Volltextsuche)</li>
          <li>Bevorstehende Veranstaltungen</li>
          <li>Eigene abgegebene Stimmzettel</li>
        </ul>
        $writeBlock
        <p><strong>Was der Agent nicht kann:</strong> nicht veröffentlichen (jeder Entwurf braucht Ihre Freigabe in der Weboberfläche), nicht abstimmen, keine Daten anderer Mitglieder sehen, keine Stimmgewichte berechnen.</p>
        <form method="post" action="/federation/oidc/authorize/consent">
          <input type="hidden" name="client_id" value="${htmlEscape(clientId)}">
          <input type="hidden" name="redirect_uri" value="${htmlEscape(redirectUri)}">
          <input type="hidden" name="scope" value="${htmlEscape(scopeParam)}">
          <input type="hidden" name="state" value="${htmlEscape(state)}">
          <input type="hidden" name="code_challenge" value="${htmlEscape(codeChallenge)}">
          <input type="hidden" name="nonce" value="${htmlEscape(nonce)}">
          <input type="hidden" name="resource" value="${htmlEscape(resource)}">
          <label for="connection_label">Name dieser Verbindung (frei wählbar, z. B. &bdquo;Claude Desktop&ldquo;)</label>
          <input id="connection_label" name="connection_label" required maxlength="60">
          <button type="submit" name="decision" value="allow">Erlauben</button>
          <button type="submit" name="decision" value="deny">Ablehnen</button>
        </form>
        </body></html>
        """.trimIndent()
}
