package network.lapis.cloud.server.mail

import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordResetTokenStore
import kotlin.time.Duration

/**
 * Deutsch, Sie-Ansprache (Vault-/Nutzer-Konvention) plain-text + HTML Doppel-Templates für die
 * beiden Mailtypen dieser Welle. HTML über [createHTML] (kotlinx-html, Auto-Escaping) --
 * dieselbe Begründung wie `network.lapis.cloud.server.social.SocialPublicHtml`: ein
 * handgeschriebenes `htmlEscape()` wäre hier der falsche Sicherheitsmechanismus, weil der Token
 * selbst zwar serverseitig erzeugt und opak ist, aber [FederationConfig.publicBaseUrl] letztlich
 * deployment-konfigurierbar ist.
 *
 * **Client-Deep-Links (Option B)**: beide Mails verlinken auf `#/password-reset?token=...` bzw.
 * `#/verify-email?token=...` -- der Hash-Fragment-Teil verlässt den Browser nie (siehe
 * `network.lapis.cloud.client.Routing` KDoc), der Token landet also nie in einem
 * Server-Zugriffslog oder Referer-Header. Beide URLs werden ausschließlich hier gebaut -- ein
 * späterer Wechsel des Link-Ziels ist eine Ein-Zeilen-Änderung.
 */
object MailTemplates {
    data class RenderedMail(
        val subject: String,
        val plainText: String,
        val html: String,
    )

    fun passwordReset(
        rawToken: String,
        branding: MailBranding,
    ): RenderedMail {
        val link = "${branding.publicBaseUrl}/#/password-reset?token=$rawToken"
        val ttl = formatTtl(PasswordResetTokenStore.RESET_TTL)
        val subject = "Passwort zurücksetzen – ${branding.fromDisplayName}"
        val plainText =
            "Sie haben ein Zurücksetzen Ihres Passworts angefordert.\n\n" +
                "Öffnen Sie diesen Link, um ein neues Passwort zu vergeben:\n$link\n\n" +
                "Alternativ können Sie folgenden Code manuell im Anmeldeformular eingeben:\n$rawToken\n\n" +
                "Dieser Link/Code ist $ttl gültig. Wenn Sie diese Änderung nicht angefordert haben, " +
                "können Sie diese E-Mail ignorieren – es wurde noch nichts verändert.\n\n" +
                footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +"Passwort zurücksetzen" }
                    p { +"Sie haben ein Zurücksetzen Ihres Passworts angefordert." }
                    p {
                        +"Öffnen Sie diesen Link, um ein neues Passwort zu vergeben: "
                        a(href = link) { +"Link zum Zurücksetzen öffnen" }
                    }
                    p { +"Alternativ können Sie folgenden Code manuell im Anmeldeformular eingeben: $rawToken" }
                    p { +"Dieser Link/Code ist $ttl gültig." }
                    p {
                        +(
                            "Wenn Sie diese Änderung nicht angefordert haben, können Sie diese E-Mail ignorieren – " +
                                "es wurde noch nichts verändert."
                        )
                    }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    fun friendVerification(
        rawToken: String,
        branding: MailBranding,
    ): RenderedMail {
        val link = "${branding.publicBaseUrl}/#/verify-email?token=$rawToken"
        val ttl = formatTtl(FriendEmailVerificationTokenStore.VERIFICATION_TTL)
        val subject = "E-Mail-Adresse bestätigen – ${branding.fromDisplayName}"
        val plainText =
            "Bitte bestätigen Sie Ihre E-Mail-Adresse für Ihr Konto bei ${branding.fromDisplayName}.\n\n" +
                "Öffnen Sie diesen Link, um die Bestätigung abzuschließen:\n$link\n\n" +
                "Alternativ können Sie folgenden Code manuell eingeben:\n$rawToken\n\n" +
                "Dieser Link/Code ist $ttl gültig. Wenn Sie dieses Konto nicht angelegt haben, " +
                "können Sie diese E-Mail ignorieren.\n\n" +
                footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +"E-Mail-Adresse bestätigen" }
                    p { +"Bitte bestätigen Sie Ihre E-Mail-Adresse für Ihr Konto bei ${branding.fromDisplayName}." }
                    p {
                        +"Öffnen Sie diesen Link, um die Bestätigung abzuschließen: "
                        a(href = link) { +"E-Mail-Adresse jetzt bestätigen" }
                    }
                    p { +"Alternativ können Sie folgenden Code manuell eingeben: $rawToken" }
                    p { +"Dieser Link/Code ist $ttl gültig." }
                    p { +"Wenn Sie dieses Konto nicht angelegt haben, können Sie diese E-Mail ignorieren." }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * Letzte Zeile in beiden Templates, Plaintext UND HTML identisch (V1.2.3 Design-Review, Punkt
     * 4b: keine Mail ohne Rückweg). Geht im HTML-Zweig durch kotlinx-html's Auto-Escaping ([p]-Block
     * mit `+`-Operator) -- [MailBranding.replyTo]/[MailBranding.publicBaseUrl] sind beide
     * deployment-konfigurierbar und werden hier deshalb nicht als vertrauenswürdig behandelt.
     */
    private fun footer(branding: MailBranding): String =
        branding.replyTo?.let { "Fragen? Antworten Sie einfach auf diese E-Mail ($it)." }
            ?: "Diese Adresse wird nicht gelesen. Fragen: ${branding.publicBaseUrl}"

    private fun formatTtl(ttl: Duration): String {
        val hours = ttl.inWholeHours
        return if (hours == 1L) "1 Stunde" else "$hours Stunden"
    }
}
