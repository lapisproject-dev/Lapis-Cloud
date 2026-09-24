package network.lapis.cloud.server.routes

/**
 * Welle V1.8.1 MCP-Server -- the consent screen rendered ONLY for an `mcp:member_read` `/authorize`
 * request, a deliberately SEPARATE screen from [consentPageHtml] (the guest-federation consent
 * page in `OidcRoutes.kt`, which stays byte-identical -- see design-team requirement "Gast-Consent
 * unangetastet").
 *
 * Design requirements this screen must satisfy (Design-Team review, Kare/Norman/Jobs):
 * - A FIXED heading, never influenced by the client's self-declared name.
 * - The self-declared `clientName` appears only as a quoted, explicitly-flagged-as-unverified
 *   attribute -- filtered of control characters and hard-capped at 80 characters before escaping.
 * - No raw OAuth scope string is ever shown -- a plain-language list of the five data areas the
 *   grant covers, plus an explicit "what the agent cannot do" paragraph.
 * - A REQUIRED `connection_label` field (max 60 chars) -- the name the member gives this
 *   connection, later shown in their own revocation list (`rpc.McpAccessService
 *   .getMcpAccessState` -- the member-facing screen consuming it is a follow-up, not built this
 *   session, see `docs/architecture/mcp-server.adoc` "What doesn't work yet").
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
        <p><strong>Was der Agent nicht kann:</strong> nichts ändern, nicht abstimmen, keine Daten anderer Mitglieder sehen, keine Stimmgewichte berechnen.</p>
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
