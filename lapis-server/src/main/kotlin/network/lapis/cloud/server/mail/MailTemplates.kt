package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.hr
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.shared.domain.EmailChangeKind
import kotlin.time.Duration

/**
 * Deutsch, Sie-Ansprache (Vault-/Nutzer-Konvention) plain-text + HTML Doppel-Templates für die
 * beiden Mailtypen dieser Welle. HTML über [createHTML] (kotlinx-html, Auto-Escaping) --
 * dieselbe Begründung wie `network.lapis.cloud.server.social.SocialPublicHtml`: ein
 * handgeschriebenes `htmlEscape()` wäre hier der falsche Sicherheitsmechanismus, weil der Token
 * selbst zwar serverseitig erzeugt und opak ist, aber [FederationConfig.publicBaseUrl] letztlich
 * deployment-konfigurierbar ist.
 *
 * **Client-Deep-Links (Option B)**: beide Mails verlinken auf `/app#/password-reset?token=...` bzw.
 * `/app#/verify-email?token=...` (Welle V1.4.6: `/app`-Präfix, seit die SPA nicht mehr unter `/`
 * läuft, siehe `network.lapis.cloud.server.routes.PublicLandingRoutes` KDoc) -- der Hash-Fragment-Teil
 * verlässt den Browser nie (siehe `network.lapis.cloud.client.Routing` KDoc), der Token landet also
 * nie in einem Server-Zugriffslog oder Referer-Header. Beide URLs werden ausschließlich hier gebaut --
 * ein späterer Wechsel des Link-Ziels ist eine Ein-Zeilen-Änderung.
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
        val link = "${branding.publicBaseUrl}/app#/password-reset?token=$rawToken"
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
        val link = "${branding.publicBaseUrl}/app#/verify-email?token=$rawToken"
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
     * Welle V1.3.2 "Webhooks" (ausgehend) -- `network.lapis.cloud.server.webhook
     * .WebhookDeactivationNotifier`'s one mail, sent to every BOARD/ADMIN member (capped, see that
     * class KDoc) after the poller auto-deactivates an endpoint. **Never the signature secret,
     * never the endpoint's full URL** -- only [urlHost] (Design-Team decision, plan §5.6: path/
     * query segments can carry a receiver-chosen secret token of their own, e.g.
     * `https://example.com/webhooks/<their-own-secret>`, which this org's mail transport has no
     * business retaining).
     */
    fun webhookEndpointDeactivated(
        apiKeyLabel: String,
        urlHost: String,
        eventTypeLabel: String,
        attemptCount: Int,
        lastHttpStatus: Int?,
        branding: MailBranding,
    ): RenderedMail {
        val link = "${branding.publicBaseUrl}/app#/api-keys"
        val statusLine = if (lastHttpStatus != null) "letzter HTTP-Status: $lastHttpStatus" else "kein HTTP-Status erhalten"
        val subject = "Webhook deaktiviert – ${branding.fromDisplayName}"
        val plainText =
            "Der Webhook für den API-Schlüssel „$apiKeyLabel“ (Ziel-Host: $urlHost) wurde nach " +
                "wiederholten Zustellfehlern automatisch deaktiviert.\n\n" +
                "Letztes Ereignis: $eventTypeLabel, Versuch $attemptCount, $statusLine.\n\n" +
                "Öffnen Sie die API-Schlüssel-Verwaltung, um den Webhook zu prüfen und ggf. wieder zu " +
                "aktivieren:\n$link\n\n" +
                footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +"Webhook deaktiviert" }
                    p {
                        +(
                            "Der Webhook für den API-Schlüssel „$apiKeyLabel“ (Ziel-Host: $urlHost) wurde nach " +
                                "wiederholten Zustellfehlern automatisch deaktiviert."
                        )
                    }
                    p { +"Letztes Ereignis: $eventTypeLabel, Versuch $attemptCount, $statusLine." }
                    p {
                        +"Öffnen Sie die API-Schlüssel-Verwaltung, um den Webhook zu prüfen und ggf. wieder zu aktivieren: "
                        a(href = link) { +"API-Schlüssel-Verwaltung öffnen" }
                    }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- an das BOARD/ADMIN, wenn ein Kontos
     * Live-Abruf-Status auf `REAUTH_REQUIRED` wechselt (siehe
     * `network.lapis.cloud.server.payment.fints.FinTsPoller` KDoc "Benachrichtigung nur beim
     * Übergang"). **Enthält niemals PIN, Benutzerkennung oder eine rohe Bank-Meldung** -- nur
     * [accountLabel]/[ibanMasked]/[occurredAt] und ein Link zur Bankkonten-Verwaltung.
     */
    fun finTsReauthRequired(
        accountLabel: String,
        ibanMasked: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
    ): RenderedMail {
        val link = "${branding.publicBaseUrl}/app#/bank-accounts"
        val subject = "FinTS-Live-Abruf muss neu angemeldet werden – ${branding.fromDisplayName}"
        val plainText =
            "Der Live-Abruf für das Bankkonto „$accountLabel“ ($ibanMasked) benötigt eine erneute " +
                "Anmeldung (Datum/Uhrzeit: $occurredAt). Bis dahin werden keine neuen Kontoumsätze " +
                "mehr automatisch abgerufen.\n\n" +
                "Öffnen Sie die Bankkonten-Verwaltung, um sich erneut anzumelden:\n$link\n\n" +
                footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +"FinTS-Live-Abruf: erneute Anmeldung erforderlich" }
                    p {
                        +(
                            "Der Live-Abruf für das Bankkonto „$accountLabel“ ($ibanMasked) benötigt eine " +
                                "erneute Anmeldung (Datum/Uhrzeit: $occurredAt). Bis dahin werden keine neuen " +
                                "Kontoumsätze mehr automatisch abgerufen."
                        )
                    }
                    p {
                        +"Öffnen Sie die Bankkonten-Verwaltung, um sich erneut anzumelden: "
                        a(href = link) { +"Bankkonten-Verwaltung öffnen" }
                    }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * Welle V1.4.9 "Admin-Passwort-Reset" -- reine Transparenz-Benachrichtigung an das Mitglied,
     * nachdem ein ADMIN dessen Passwort gesetzt hat. **Enthält weder Passwort noch Token** -- das
     * temporäre Passwort geht ausschließlich den direkten Weg (Betreiber -> Person), siehe
     * `network.lapis.cloud.shared.rpc.IMemberService.grantMemberAccount` KDoc "nicht e-gemailt".
     * Diese Mail sagt nur DASS es passiert ist, WANN, und was zu tun ist, wenn das unerwartet
     * kommt. Kein Link mit Nebenwirkung, deshalb auch kein TTL-Satz.
     */
    fun passwordResetByAdmin(
        occurredAt: LocalDateTime,
        branding: MailBranding,
    ): RenderedMail {
        val subject = "Ihr Passwort wurde zurückgesetzt – ${branding.fromDisplayName}"
        val plainText =
            "Ihr Passwort bei ${branding.fromDisplayName} wurde am $occurredAt von einer " +
                "administrativen Person zurückgesetzt.\n\n" +
                "Wenn Sie das erwartet haben (z. B. weil Sie telefonisch um Hilfe gebeten haben), " +
                "müssen Sie nichts weiter tun. Wenn Sie das NICHT erwartet haben, melden Sie sich " +
                "bitte umgehend bei uns.\n\n" +
                footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +"Passwort zurückgesetzt" }
                    p {
                        +(
                            "Ihr Passwort bei ${branding.fromDisplayName} wurde am $occurredAt von einer " +
                                "administrativen Person zurückgesetzt."
                        )
                    }
                    p {
                        +(
                            "Wenn Sie das erwartet haben (z. B. weil Sie telefonisch um Hilfe gebeten haben), " +
                                "müssen Sie nichts weiter tun. Wenn Sie das NICHT erwartet haben, melden Sie " +
                                "sich bitte umgehend bei uns."
                        )
                    }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * V1.7.2 security-audit fix -- see [KeycloakLinkNotificationMailer] KDoc. Same shape and tone
     * as [passwordResetByAdmin]; deliberately contains neither the Keycloak subject nor the name of
     * the acting administrator (the audit trail holds both, the mail only has to trigger "that was
     * not me").
     */
    fun keycloakLinkChangedByAdmin(
        change: KeycloakLinkChange,
        occurredAt: LocalDateTime,
        branding: MailBranding,
    ): RenderedMail {
        val (heading, sentence) =
            when (change) {
                KeycloakLinkChange.LINKED ->
                    "Anmeldekonto verknüpft" to
                        "Ihr Mitgliedskonto bei ${branding.fromDisplayName} wurde am $occurredAt von einer " +
                        "administrativen Person mit einem Keycloak-Anmeldekonto verknüpft. Wer sich mit " +
                        "diesem Keycloak-Konto anmeldet, handelt ab sofort in Ihrem Namen."
                KeycloakLinkChange.UNLINKED ->
                    "Anmeldekonto-Verknüpfung entfernt" to
                        "Die Verknüpfung Ihres Mitgliedskontos bei ${branding.fromDisplayName} mit einem " +
                        "Keycloak-Anmeldekonto wurde am $occurredAt von einer administrativen Person entfernt; " +
                        "bestehende Sitzungen wurden beendet."
            }
        val subject = "$heading – ${branding.fromDisplayName}"
        val advice =
            "Wenn Sie das erwartet haben, müssen Sie nichts weiter tun. Wenn Sie das NICHT erwartet " +
                "haben, melden Sie sich bitte umgehend bei uns."
        val plainText = "$sentence\n\n$advice\n\n" + footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +heading }
                    p { +sentence }
                    p { +advice }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- author-facing notification for the
     * three board decisions on a submitted/published article. **Never names the reviewing person**
     * (mirrors `ArticleReviewDto` KDoc "keine Namen nach aussen") -- the signature is always
     * [brandTitle], never a board member's name. [notification].title flows through
     * [sanitizeSubjectFragment] before it ever reaches the `Subject:` header -- an article title is
     * author-supplied free text, so a literal `\r`/`\n` (or a Unicode line/paragraph separator) in
     * it must never be allowed to inject an extra mail header (classic header-injection vector).
     */
    fun articleReview(
        notification: ArticleReviewNotification,
        brandTitle: String,
        branding: MailBranding,
    ): RenderedMail {
        val safeTitle = sanitizeSubjectFragment(notification.title)
        val (heading, subjectVerb) =
            when (notification.outcome) {
                ArticleReviewOutcome.APPROVED -> "Ihr Artikel wurde veröffentlicht" to "veröffentlicht"
                ArticleReviewOutcome.REJECTED -> "Ihr Artikel wurde abgelehnt" to "abgelehnt"
                ArticleReviewOutcome.UNPUBLISHED -> "Ihr Artikel wurde depubliziert" to "depubliziert"
            }
        val subject = "Artikel „$safeTitle“ $subjectVerb – $brandTitle"
        val reasonSentence =
            when (notification.outcome) {
                ArticleReviewOutcome.APPROVED -> null
                ArticleReviewOutcome.REJECTED, ArticleReviewOutcome.UNPUBLISHED ->
                    notification.reason?.let { "Begründung: $it" }
                        ?: "Der Vorstand hat keine Begründung angegeben."
            }
        val introSentence =
            when (notification.outcome) {
                ArticleReviewOutcome.APPROVED -> "Ihr Artikel „${notification.title}“ wurde vom Vorstand geprüft und veröffentlicht."
                ArticleReviewOutcome.REJECTED -> "Ihr Artikel „${notification.title}“ wurde vom Vorstand geprüft und nicht veröffentlicht."
                ArticleReviewOutcome.UNPUBLISHED -> "Ihr Artikel „${notification.title}“ wurde vom Vorstand von der Webseite genommen."
            }
        val plainText =
            buildString {
                append(introSentence)
                if (reasonSentence != null) {
                    append("\n\n")
                    append(reasonSentence)
                }
                if (notification.publicUrl != null) {
                    append("\n\nSie finden Ihren Artikel hier:\n")
                    append(notification.publicUrl)
                }
                append("\n\n")
                append(brandTitle)
                append("\n\n")
                append(footer(branding))
            }
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +heading }
                    p { +introSentence }
                    if (reasonSentence != null) p { +reasonSentence }
                    if (notification.publicUrl != null) {
                        p {
                            +"Sie finden Ihren Artikel hier: "
                            a(href = notification.publicUrl) { +"Artikel öffnen" }
                        }
                    }
                    p { +brandTitle }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * Welle V1.9.56 "E-Mail-Änderung absichern" -- link to the NEW address. [EmailChangeKind.PROPOSAL]: accept with the
     * password (`/app#/confirm-email?token=...`); the other proposal kinds: prove the address (`/app#/verify-new-email`),
     * effective no earlier than [effectiveAt]. German first, English second, one message (members have no language
     * preference). The mail names no member and no initiator.
     */
    fun emailChangeConfirm(
        rawToken: String,
        kind: EmailChangeKind,
        effectiveAt: LocalDateTime?,
        branding: MailBranding,
    ): RenderedMail {
        val needsPassword = kind == EmailChangeKind.PROPOSAL
        val route = if (needsPassword) "confirm-email" else "verify-new-email"
        val link = "${branding.publicBaseUrl}/app#/$route?token=$rawToken"
        val de =
            if (needsPassword) {
                MailSection(
                    heading = "Neue Anmeldeadresse annehmen",
                    paragraphs =
                        listOf(
                            "Für ein Konto bei ${branding.fromDisplayName} wurde vorgeschlagen, diese E-Mail-Adresse als Anmeldeadresse zu verwenden.",
                            "Wenn das Ihr Konto ist, öffnen Sie den Link und bestätigen Sie mit Ihrem Passwort. Der Link ist 7 Tage gültig.",
                            "Wenn Sie das nicht erwartet haben, können Sie diese E-Mail ignorieren – es wurde nichts verändert.",
                        ),
                    linkLabel = "Neue Adresse annehmen",
                    link = link,
                )
            } else {
                MailSection(
                    heading = "E-Mail-Adresse bestätigen",
                    paragraphs =
                        listOf(
                            "Für ein Konto bei ${branding.fromDisplayName} wurde beantragt, diese E-Mail-Adresse als Anmeldeadresse zu verwenden.",
                            "Bitte bestätigen Sie, dass diese Adresse Ihnen gehört. Die Änderung wird frühestens ${formatUtc(
                                effectiveAt,
                            )} " +
                                "wirksam; bis dahin kann sie über die bisherige Adresse abgelehnt werden. Der Link ist 7 Tage gültig.",
                            "Wenn Sie das nicht erwartet haben, können Sie diese E-Mail ignorieren – es wurde nichts verändert.",
                        ),
                    linkLabel = "Adresse bestätigen",
                    link = link,
                )
            }
        val en =
            if (needsPassword) {
                MailSection(
                    heading = "Accept the new sign-in address",
                    paragraphs =
                        listOf(
                            "Using this e-mail address as the sign-in address of an account at ${branding.fromDisplayName} was proposed.",
                            "If this is your account, open the link and confirm with your password. The link is valid for 7 days.",
                            "If you did not expect this, you can ignore this e-mail – nothing has been changed.",
                        ),
                    linkLabel = "Accept the new address",
                    link = link,
                )
            } else {
                MailSection(
                    heading = "Confirm your e-mail address",
                    paragraphs =
                        listOf(
                            "Using this e-mail address as the sign-in address of an account at ${branding.fromDisplayName} was requested.",
                            "Please confirm that this address is yours. The change takes effect no earlier than ${formatUtc(
                                effectiveAt,
                            )}; " +
                                "until then it can be rejected through the previous address. The link is valid for 7 days.",
                            "If you did not expect this, you can ignore this e-mail – nothing has been changed.",
                        ),
                    linkLabel = "Confirm the address",
                    link = link,
                )
            }
        return bilingual(
            subject = "E-Mail-Adresse ändern / Change e-mail address – ${branding.fromDisplayName}",
            de = de,
            en = en,
            branding = branding,
        )
    }

    /**
     * Welle V1.9.56 -- warning with the reject link to the OLD address. Only the MASKED new address, no name and no role
     * of the initiator (the audit trail holds that, the mail only has to trigger "that was not me").
     */
    fun emailChangeWarningOld(
        rawRevokeToken: String,
        kind: EmailChangeKind,
        maskedNewEmail: String,
        effectiveAt: LocalDateTime?,
        branding: MailBranding,
    ): RenderedMail {
        val link = "${branding.publicBaseUrl}/app#/revoke-email-change?token=$rawRevokeToken"
        val needsPassword = kind == EmailChangeKind.PROPOSAL
        val deEffect =
            if (needsPassword) {
                "Die Änderung wird nur wirksam, wenn Sie sie mit Ihrem Passwort annehmen."
            } else {
                "Die Änderung wird frühestens ${formatUtc(
                    effectiveAt,
                )} wirksam, sofern die neue Adresse bestätigt wurde und Sie nicht ablehnen."
            }
        val enEffect =
            if (needsPassword) {
                "The change only takes effect if you accept it with your password."
            } else {
                "The change takes effect no earlier than ${formatUtc(
                    effectiveAt,
                )}, provided the new address has been confirmed and you do not reject it."
            }
        return bilingual(
            subject = "Änderung Ihrer Anmeldeadresse / Change of your sign-in address – ${branding.fromDisplayName}",
            de =
                MailSection(
                    heading = "Änderung Ihrer Anmeldeadresse beantragt",
                    paragraphs =
                        listOf(
                            "Für Ihr Konto bei ${branding.fromDisplayName} hat eine administrative Person beantragt, die Anmeldeadresse " +
                                "auf $maskedNewEmail zu ändern.",
                            deEffect,
                            "Wenn Sie das NICHT erwartet haben, lehnen Sie die Änderung jetzt ab und melden Sie sich bitte umgehend bei uns.",
                        ),
                    linkLabel = "Änderung ablehnen",
                    link = link,
                ),
            en =
                MailSection(
                    heading = "Change of your sign-in address requested",
                    paragraphs =
                        listOf(
                            "For your account at ${branding.fromDisplayName}, an administrative person requested to change the sign-in " +
                                "address to $maskedNewEmail.",
                            enEffect,
                            "If you did NOT expect this, reject the change now and please contact us immediately.",
                        ),
                    linkLabel = "Reject the change",
                    link = link,
                ),
            branding = branding,
        )
    }

    /** Welle V1.9.56 -- info to the OLD address after the OWNER changed the address with their password (path A). */
    fun emailChangeSelfInfo(
        maskedNewEmail: String,
        branding: MailBranding,
    ): RenderedMail =
        bilingual(
            subject = "Ihre Anmeldeadresse wurde geändert / Your sign-in address was changed – ${branding.fromDisplayName}",
            de =
                MailSection(
                    heading = "Anmeldeadresse geändert",
                    paragraphs =
                        listOf(
                            "Die Anmeldeadresse Ihres Kontos bei ${branding.fromDisplayName} wurde soeben auf $maskedNewEmail geändert. " +
                                "Alle anderen Sitzungen wurden beendet.",
                            "Wenn Sie das NICHT selbst getan haben, melden Sie sich bitte umgehend bei uns.",
                        ),
                    linkLabel = null,
                    link = null,
                ),
            en =
                MailSection(
                    heading = "Sign-in address changed",
                    paragraphs =
                        listOf(
                            "The sign-in address of your account at ${branding.fromDisplayName} was just changed to $maskedNewEmail. " +
                                "All other sessions were ended.",
                            "If you did NOT do this yourself, please contact us immediately.",
                        ),
                    linkLabel = null,
                    link = null,
                ),
            branding = branding,
        )

    /** Welle V1.9.56 -- info to the OLD address after a third-party change became effective (warning period elapsed). */
    fun emailChangeAppliedInfo(
        maskedNewEmail: String,
        branding: MailBranding,
    ): RenderedMail =
        bilingual(
            subject = "Ihre Anmeldeadresse wurde geändert / Your sign-in address was changed – ${branding.fromDisplayName}",
            de =
                MailSection(
                    heading = "Anmeldeadresse geändert",
                    paragraphs =
                        listOf(
                            "Die Anmeldeadresse Ihres Kontos bei ${branding.fromDisplayName} wurde nach Ablauf der Warnfrist auf " +
                                "$maskedNewEmail geändert. Alle Sitzungen wurden beendet.",
                            "Wenn Sie das nicht erwartet haben, melden Sie sich bitte umgehend bei uns.",
                        ),
                    linkLabel = null,
                    link = null,
                ),
            en =
                MailSection(
                    heading = "Sign-in address changed",
                    paragraphs =
                        listOf(
                            "The sign-in address of your account at ${branding.fromDisplayName} was changed to $maskedNewEmail after the " +
                                "warning period elapsed. All sessions were ended.",
                            "If you did not expect this, please contact us immediately.",
                        ),
                    linkLabel = null,
                    link = null,
                ),
            branding = branding,
        )

    /** One language block of a bilingual mail ([link] and [linkLabel] are both set or both null). */
    private data class MailSection(
        val heading: String,
        val paragraphs: List<String>,
        val linkLabel: String?,
        val link: String?,
    )

    /** German block first, English second, ONE footer -- plain text and HTML (kotlinx-html auto-escaping) carry identical content. */
    private fun bilingual(
        subject: String,
        de: MailSection,
        en: MailSection,
        branding: MailBranding,
    ): RenderedMail {
        fun plain(section: MailSection): String =
            buildString {
                append(section.heading).append("\n\n")
                section.paragraphs.forEach { append(it).append("\n\n") }
                if (section.link != null) append(section.linkLabel).append(":\n").append(section.link).append("\n\n")
            }
        val plainText = plain(de) + "----\n\n" + plain(en) + footer(branding)
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    listOf(de, en).forEachIndexed { index, section ->
                        if (index > 0) hr()
                        h1 { +section.heading }
                        section.paragraphs.forEach { paragraph -> p { +paragraph } }
                        if (section.link != null) {
                            p { a(href = section.link) { +(section.linkLabel ?: section.link) } }
                        }
                    }
                    p { +footer(branding) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /** `2026-10-08 14:30 UTC` -- class-A system timestamps are UTC, a mail has no viewer zone to convert to. */
    private fun formatUtc(at: LocalDateTime?): String {
        if (at == null) return "(unbekannt / unknown)"
        return "${at.date} ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')} UTC"
    }

    /**
     * Strips CR/LF and the Unicode NEL/LINE SEPARATOR/PARAGRAPH SEPARATOR characters (`\r`, `\n`,
     * `\u0085`, ` `, ` `) plus any other control character from [fragment], replacing each
     * with a single space, then trims -- a `Subject:` header value must never contain a raw line
     * break (mail-header-injection), and an article title is author-supplied free text that has
     * never been checked for this.
     */
    private fun sanitizeSubjectFragment(fragment: String): String =
        fragment
            .map { c -> if (c == '\r' || c == '\n' || c == '\u0085' || c == ' ' || c == ' ' || c.isISOControl()) ' ' else c }
            .joinToString("")
            .trim()

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
