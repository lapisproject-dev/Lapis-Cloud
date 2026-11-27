package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.hr
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import network.lapis.cloud.server.carpool.CarpoolNoticeKind
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.PartgReviewerRules
import network.lapis.cloud.shared.domain.PrivilegedActionKind
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
        language: String = "de",
    ): RenderedMail {
        val t = PasswordResetTexts
        val link = "${branding.publicBaseUrl}/app#/password-reset?token=$rawToken"
        val intro = txt(text = t.openLinkIntro, language = language)
        return single(
            subject = subjectWithBrand(lead = txt(text = t.heading, language = language), branding = branding),
            heading = txt(text = t.heading, language = language),
            blocks =
                listOf(
                    Block(plain = txt(text = t.requested, language = language)),
                    Block(plain = "$intro\n$link", html = intro, link = link, linkLabel = txt(text = t.linkLabel, language = language)),
                    Block(
                        plain = "${txt(text = t.codeIntro, language = language)}\n$rawToken",
                        html = "${txt(text = t.codeIntro, language = language)} $rawToken",
                    ),
                    Block(
                        plain =
                            txt(
                                text = t.validity,
                                language = language,
                                "ttl" to formatTtl(ttl = PasswordResetTokenStore.RESET_TTL, language = language),
                            ),
                    ),
                    Block(plain = txt(text = t.ignoreHint, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * V1.9.310 (R6b): the code of an auditor's read access. A FIXED subject and body: the code, its life span and a hint; no link, no name, nothing
     * a person typed. The code is shown as "482 913". V1.9.334: in the instance mail language.
     */
    fun partgReviewerCode(
        code: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PartgReviewerCodeTexts
        val shown = PartgReviewerRules.formatCode(code)
        val subject = txt(text = t.subject, language = language)
        return single(
            subject = subject,
            heading = subject,
            blocks =
                listOf(
                    Block(plain = txt(text = t.intro, language = language, "brand" to branding.fromDisplayName)),
                    Block(plain = shown, bold = true),
                    Block(
                        plain =
                            txt(text = t.validity, language = language, "minutes" to PartgReviewerRules.CODE_VALID_MINUTES.toString()) +
                                " " + txt(text = t.ignoreHint, language = language),
                    ),
                ),
            branding = branding,
            language = language,
        )
    }

    fun friendVerification(
        rawToken: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = FriendVerificationTexts
        val link = "${branding.publicBaseUrl}/app#/verify-email?token=$rawToken"
        val intro = txt(text = t.openLinkIntro, language = language)
        return single(
            subject = subjectWithBrand(lead = txt(text = t.heading, language = language), branding = branding),
            heading = txt(text = t.heading, language = language),
            blocks =
                listOf(
                    Block(plain = txt(text = t.intro, language = language, "brand" to branding.fromDisplayName)),
                    Block(plain = "$intro\n$link", html = intro, link = link, linkLabel = txt(text = t.linkLabel, language = language)),
                    Block(
                        plain = "${txt(text = t.codeIntro, language = language)}\n$rawToken",
                        html = "${txt(text = t.codeIntro, language = language)} $rawToken",
                    ),
                    Block(
                        plain =
                            txt(
                                text = PasswordResetTexts.validity,
                                language = language,
                                "ttl" to formatTtl(ttl = FriendEmailVerificationTokenStore.VERIFICATION_TTL, language = language),
                            ),
                    ),
                    Block(plain = txt(text = t.ignoreHint, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * Welle V1.3.2 "Webhooks" (ausgehend) -- `network.lapis.cloud.server.webhook
     * .WebhookDeactivationNotifier`'s one mail, sent to every BOARD/ADMIN member (capped, see that
     * class KDoc) after the poller auto-deactivates an endpoint. **Never the signature secret,
     * never the endpoint's full URL** -- only [urlHost] (Design-Team decision, plan §5.6: path/
     * query segments can carry a receiver-chosen secret token of their own). A `null` host is written as "unknown" in [language].
     */
    fun webhookEndpointDeactivated(
        apiKeyLabel: String,
        urlHost: String?,
        eventTypeLabel: String,
        attemptCount: Int,
        lastHttpStatus: Int?,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = WebhookDeactivatedTexts
        val link = "${branding.publicBaseUrl}/app#/api-keys"
        val status =
            if (lastHttpStatus !=
                null
            ) {
                txt(text = t.statusLine, language = language, "code" to lastHttpStatus.toString())
            } else {
                txt(text = t.noStatus, language = language)
            }
        val intro = txt(text = t.linkIntro, language = language)
        return single(
            subject = subjectWithBrand(lead = txt(text = t.heading, language = language), branding = branding),
            heading = txt(text = t.heading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.body,
                                language = language,
                                "key" to apiKeyLabel,
                                "host" to (urlHost ?: txt(text = t.unknownHost, language = language)),
                            ),
                    ),
                    Block(
                        plain =
                            txt(
                                text = t.lastEvent,
                                language = language,
                                "event" to eventTypeLabel,
                                "attempt" to attemptCount.toString(),
                                "status" to status,
                            ),
                    ),
                    Block(plain = "$intro\n$link", html = intro, link = link, linkLabel = txt(text = t.linkLabel, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- an das BOARD/ADMIN, wenn ein Kontos
     * Live-Abruf-Status auf `REAUTH_REQUIRED` wechselt (siehe
     * `network.lapis.cloud.server.payment.fints.FinTsPoller` KDoc "Benachrichtigung nur beim
     * Übergang"). **Enthält niemals PIN, Benutzerkennung oder eine rohe Bank-Meldung** -- nur
     * [accountLabel]/[ibanMasked]/[occurredAt] und ein Link zur Bankkonten-Verwaltung. The timestamp stays in its raw form.
     */
    fun finTsReauthRequired(
        accountLabel: String,
        ibanMasked: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = FinTsReauthTexts
        val link = "${branding.publicBaseUrl}/app#/bank-accounts"
        val intro = txt(text = t.linkIntro, language = language)
        return single(
            subject = subjectWithBrand(lead = txt(text = t.subjectLead, language = language), branding = branding),
            heading = txt(text = t.heading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.body,
                                language = language,
                                "account" to accountLabel,
                                "iban" to ibanMasked,
                                "when" to occurredAt.toString(),
                            ),
                    ),
                    Block(plain = "$intro\n$link", html = intro, link = link, linkLabel = txt(text = t.linkLabel, language = language)),
                ),
            branding = branding,
            language = language,
        )
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
        language: String = "de",
    ): RenderedMail {
        val t = AdminPasswordResetTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.subjectLead, language = language), branding = branding),
            heading = txt(text = t.heading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.sentence,
                                language = language,
                                "brand" to branding.fromDisplayName,
                                "when" to occurredAt.toString(),
                            ),
                    ),
                    Block(plain = txt(text = t.advice, language = language)),
                ),
            branding = branding,
            language = language,
        )
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
        language: String = "de",
    ): RenderedMail {
        val t = KeycloakLinkTexts
        val (heading, sentence) =
            when (change) {
                KeycloakLinkChange.LINKED -> t.linkedHeading to t.linkedSentence
                KeycloakLinkChange.UNLINKED -> t.unlinkedHeading to t.unlinkedSentence
            }
        return single(
            subject = subjectWithBrand(lead = txt(text = heading, language = language), branding = branding),
            heading = txt(text = heading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = sentence,
                                language = language,
                                "brand" to branding.fromDisplayName,
                                "when" to occurredAt.toString(),
                            ),
                    ),
                    Block(plain = txt(text = t.advice, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * Welle V1.9.73 -- to an administrator: a member was created on the first Keycloak login. The subject is STATIC (no name,
     * so no header injection); [newMemberName] is free text from the identity provider and goes through [safeName]
     * (control characters stripped, 120 characters) -- the HTML part is escaped by kotlinx-html. No address, no credential.
     */
    fun keycloakMemberProvisioned(
        newMemberName: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = KeycloakProvisioningTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.provisionedHeading, language = language), branding = branding),
            heading = txt(text = t.provisionedHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.provisionedSentence,
                                language = language,
                                "name" to safeName(newMemberName),
                                "when" to formatUtc(at = occurredAt, language = language),
                                "brand" to branding.fromDisplayName,
                            ),
                    ),
                    Block(plain = txt(text = MailCommonTexts.notExpectedReview, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /** Welle V1.9.73 -- to the OLD address: the sign-in address was taken over from the identity provider; the new one only [maskedNewEmail]. */
    fun keycloakEmailSyncedNotice(
        maskedNewEmail: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = KeycloakProvisioningTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.syncedSubjectLead, language = language), branding = branding),
            heading = txt(text = t.syncedHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.syncedSentence,
                                language = language,
                                "brand" to branding.fromDisplayName,
                                "when" to formatUtc(at = occurredAt, language = language),
                                "masked" to maskedNewEmail,
                            ),
                    ),
                    Block(plain = txt(text = MailCommonTexts.notExpectedContact, language = language)),
                ),
            branding = branding,
            language = language,
        )
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
        language: String = "de",
    ): RenderedMail {
        val t = ArticleReviewTexts
        val safeTitle = sanitizeSubjectFragment(notification.title)
        val (subjectText, heading, intro) =
            when (notification.outcome) {
                ArticleReviewOutcome.APPROVED -> Triple(t.approvedSubject, t.approvedHeading, t.approvedIntro)
                ArticleReviewOutcome.REJECTED -> Triple(t.rejectedSubject, t.rejectedHeading, t.rejectedIntro)
                ArticleReviewOutcome.UNPUBLISHED -> Triple(t.unpublishedSubject, t.unpublishedHeading, t.unpublishedIntro)
            }
        val reasonSentence =
            when (notification.outcome) {
                ArticleReviewOutcome.APPROVED -> null
                ArticleReviewOutcome.REJECTED, ArticleReviewOutcome.UNPUBLISHED ->
                    notification.reason?.let { txt(text = t.reasonLine, language = language, "reason" to it) }
                        ?: txt(text = t.noReason, language = language)
            }
        val blocks = mutableListOf(Block(plain = txt(text = intro, language = language, "title" to notification.title)))
        if (reasonSentence != null) blocks += Block(plain = reasonSentence)
        if (notification.publicUrl != null) {
            val findHere = txt(text = t.findHere, language = language)
            blocks +=
                Block(
                    plain = "$findHere\n${notification.publicUrl}",
                    html = findHere,
                    link = notification.publicUrl,
                    linkLabel = txt(text = t.linkLabel, language = language),
                )
        }
        blocks += Block(plain = brandTitle)
        return single(
            subject = "${txt(text = subjectText, language = language, "title" to safeTitle)} – $brandTitle",
            heading = txt(text = heading, language = language),
            blocks = blocks,
            branding = branding,
            language = language,
        )
    }

    /**
     * V1.9.330 / V1.9.332 -- receipt to the winner of a tender after a committed LTR payout, in ONE language ([language], see [MailLanguage]).
     * The subject is fixed (instance name only): the tender title is free text and only ever appears, sanitised and shortened, in the body.
     * Names neither the paying member nor a ledger id.
     */
    fun tenderPayoutPaid(
        notification: TenderPayoutPaidNotification,
        brandTitle: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val texts = TenderPayoutPaidTexts.forLanguage(language)
        val safeBrand = sanitizeSubjectFragment(brandTitle)
        val subject = fillTenderPayout(template = texts.subject, values = mapOf("brand" to safeBrand))
        val amount =
            notification.amount
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString()
                .replace('.', ',')
        val link = "${branding.publicBaseUrl}/app#/tenders/${notification.tenderId}"
        val sentence =
            fillTenderPayout(
                template = texts.sentence,
                values =
                    mapOf(
                        "amount" to amount,
                        "title" to safeTenderTitle(notification.tenderTitle),
                        "when" to formatUtc(at = notification.executedAt, language = language),
                    ),
            )
        val plainText =
            buildString {
                append(sentence)
                append("\n\n").append(texts.linkIntro).append("\n")
                append(link)
                append("\n\n")
                append(brandTitle)
                append("\n\n")
                append(footer(branding = branding, language = language))
            }
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +texts.heading }
                    p { +sentence }
                    p {
                        +"${texts.linkIntro} "
                        a(href = link) { +texts.linkLabel }
                    }
                    p { +brandTitle }
                    p { +footer(branding = branding, language = language) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /**
     * V1.9.331 / V1.9.332 -- notice to the board that a tender payout failed, in ONE language ([language]; before V1.9.332 German and
     * English in one mail). The subject is fixed (instance name only, the tender title never enters a header); the title appears sanitised
     * and shortened in the body. No amount, no winner, no reason, no payer.
     */
    fun tenderPayoutFailed(
        notification: TenderPayoutFailedNotification,
        brandTitle: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val texts = TenderPayoutFailedTexts.forLanguage(language)
        val subject = fillTenderPayout(template = texts.subject, values = mapOf("brand" to sanitizeSubjectFragment(brandTitle)))
        val title = mapOf("title" to safeTenderTitle(notification.tenderTitle))
        val link = "${branding.publicBaseUrl}/app#/tenders/${notification.tenderId}"
        val heading = texts.heading
        val paragraphs = listOf(fillTenderPayout(template = texts.p1, values = title), texts.p2)
        val plainText =
            buildString {
                append(heading).append("\n\n")
                paragraphs.forEach { append(it).append("\n\n") }
                append(texts.linkLabel).append(":\n").append(link).append("\n\n")
                append(footer(branding = branding, language = language))
            }
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +heading }
                    paragraphs.forEach { paragraph -> p { +paragraph } }
                    p { a(href = link) { +texts.linkLabel } }
                    p { +footer(branding = branding, language = language) }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /** Tender title for a mail BODY: control characters and line breaks flattened, whitespace collapsed, at most 120 characters. */
    private fun safeTenderTitle(title: String): String {
        val clean = sanitizeSubjectFragment(title).replace(Regex("\\s+"), " ")
        return if (clean.length > 120) clean.take(119).trimEnd() + "…" else clean
    }

    /**
     * Welle V1.9.56 "E-Mail-Änderung absichern" -- link to the NEW address. [EmailChangeKind.PROPOSAL]: accept with the
     * password (`/app#/confirm-email?token=...`); the other proposal kinds: prove the address (`/app#/verify-new-email`),
     * effective no earlier than [effectiveAt]. One language ([language], the instance mail language since V1.9.334). The mail names no
     * member and no initiator.
     */
    fun emailChangeConfirm(
        rawToken: String,
        kind: EmailChangeKind,
        effectiveAt: LocalDateTime?,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = EmailChangeTexts
        val needsPassword = kind == EmailChangeKind.PROPOSAL
        val route = if (needsPassword) "confirm-email" else "verify-new-email"
        val link = "${branding.publicBaseUrl}/app#/$route?token=$rawToken"
        val brand = "brand" to branding.fromDisplayName
        val heading = txt(text = if (needsPassword) t.proposalHeading else t.verifyHeading, language = language)
        val paragraphs =
            if (needsPassword) {
                listOf(
                    txt(text = t.proposalIntro, language = language, brand),
                    txt(text = t.proposalAction, language = language),
                    txt(text = MailCommonTexts.ignoreNothingChanged, language = language),
                )
            } else {
                listOf(
                    txt(text = t.verifyIntro, language = language, brand),
                    txt(text = t.verifyAction, language = language, "when" to formatUtc(at = effectiveAt, language = language)),
                    txt(text = MailCommonTexts.ignoreNothingChanged, language = language),
                )
            }
        return single(
            subject = subjectWithBrand(lead = txt(text = t.subjectLead, language = language), branding = branding),
            heading = heading,
            blocks =
                paragraphs.map {
                    Block(
                        plain = it,
                    )
                } +
                    linkBlock(
                        label = txt(text = if (needsPassword) t.proposalLinkLabel else t.verifyLinkLabel, language = language),
                        link = link,
                    ),
            branding = branding,
            language = language,
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
        language: String = "de",
    ): RenderedMail {
        val t = EmailChangeTexts
        val link = "${branding.publicBaseUrl}/app#/revoke-email-change?token=$rawRevokeToken"
        val effect =
            if (kind == EmailChangeKind.PROPOSAL) {
                txt(text = t.effectPassword, language = language)
            } else {
                txt(text = t.effectTimed, language = language, "when" to formatUtc(at = effectiveAt, language = language))
            }
        return single(
            subject = subjectWithBrand(lead = txt(text = t.warningSubjectLead, language = language), branding = branding),
            heading = txt(text = t.warningHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.warningIntro,
                                language = language,
                                "brand" to branding.fromDisplayName,
                                "masked" to maskedNewEmail,
                            ),
                    ),
                    Block(plain = effect),
                    Block(plain = txt(text = t.warningAdvice, language = language)),
                    linkBlock(label = txt(text = t.warningLinkLabel, language = language), link = link),
                ),
            branding = branding,
            language = language,
        )
    }

    /** Welle V1.9.56 -- info to the OLD address after the OWNER changed the address with their password (path A). */
    fun emailChangeSelfInfo(
        maskedNewEmail: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = EmailChangeTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.changedSubjectLead, language = language), branding = branding),
            heading = txt(text = t.changedHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.selfChanged,
                                language = language,
                                "brand" to branding.fromDisplayName,
                                "masked" to maskedNewEmail,
                            ),
                    ),
                    Block(plain = txt(text = t.selfAdvice, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /** Welle V1.9.56 -- info to the OLD address after a third-party change became effective (warning period elapsed). */
    fun emailChangeAppliedInfo(
        maskedNewEmail: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = EmailChangeTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.changedSubjectLead, language = language), branding = branding),
            heading = txt(text = t.changedHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.appliedChanged,
                                language = language,
                                "brand" to branding.fromDisplayName,
                                "masked" to maskedNewEmail,
                            ),
                    ),
                    Block(plain = txt(text = MailCommonTexts.notExpectedContact, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * Welle V1.9.76 -- anonymous entry notice to the office holders of an encounter space. Names nobody (see [EncounterEntryNotice]):
     * room title, time, number of persons without an office, number of people present. No link, no logo. The room title is
     * sanitised and cut before it reaches the `Subject:` header (header injection); the HTML part is an unformatted mirror of the plain
     * text (the transport always sends multipart/alternative). V1.9.334: one language ([language]).
     */
    fun encounterEntryNotice(
        notice: EncounterEntryNotice,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = EncounterEntryTexts
        val title = sanitizeSubjectFragment(notice.spaceTitle).take(80).ifBlank { "?" }

        fun clock(at: LocalDateTime): String = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
        val isSingle = notice.kind == EncounterEntryNotice.Kind.FIRST_GUEST
        val whenText =
            if (isSingle || notice.windowEnd == null) {
                txt(text = t.whenAt, language = language, "at" to clock(notice.at))
            } else {
                txt(text = t.whenBetween, language = language, "at" to clock(notice.at), "end" to clock(notice.windowEnd))
            }
        val values =
            arrayOf(
                "title" to title,
                "entries" to notice.entries.toString(),
                "when" to whenText,
                "present" to notice.presentCount.toString(),
            )
        return single(
            subject = txt(text = if (isSingle) t.subjectSingle else t.subjectWindow, language = language, *values),
            heading = txt(text = if (isSingle) t.headingSingle else t.headingWindow, language = language, *values),
            blocks =
                listOf(
                    Block(plain = txt(text = if (isSingle) t.detailSingle else t.detailWindow, language = language, *values)),
                    Block(plain = txt(text = t.confidentiality, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * Welle V1.9.125 -- generic carpool news mail. **Names nobody and carries no meeting note**: only that something changed on a ride
     * (date, from, to) and a link to the carpool centre -- the details are read in the app after signing in. Places are free text typed by
     * a person: sanitised and cut before they reach the `Subject:` header (header injection); the HTML part is auto-escaped.
     */
    fun carpoolNotice(
        kind: CarpoolNoticeKind,
        date: kotlinx.datetime.LocalDate,
        fromPlace: String,
        toPlace: String,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = CarpoolNoticeTexts
        val heading =
            txt(
                text =
                    when (kind) {
                        CarpoolNoticeKind.REQUESTED -> t.requested
                        CarpoolNoticeKind.INVITED -> t.invited
                        CarpoolNoticeKind.CONFIRMED -> t.confirmed
                        CarpoolNoticeKind.DECLINED -> t.declined
                        CarpoolNoticeKind.WITHDRAWN -> t.withdrawn
                        CarpoolNoticeKind.POSTING_CHANGED -> t.postingChanged
                        CarpoolNoticeKind.POSTING_DELETED -> t.postingDeleted
                        CarpoolNoticeKind.MESSAGE -> t.message
                    },
                language = language,
            )
        // V1.9.139: the message mail names no date, no place, no person and none of the text -- it only says that there is something to read.
        val paragraphs =
            if (kind == CarpoolNoticeKind.MESSAGE) {
                listOf(txt(text = t.messageLine, language = language), txt(text = t.messagePrivacy, language = language))
            } else {
                listOf(
                    txt(
                        text = t.newsLine,
                        language = language,
                        "date" to date.toString(),
                        "from" to safeName(fromPlace),
                        "to" to safeName(toPlace),
                    ),
                    txt(text = t.newsPrivacy, language = language),
                )
            }
        val link = branding.publicBaseUrl.trimEnd('/') + "/app#/carpool"
        return single(
            subject = "${txt(text = t.subjectPrefix, language = language)}: $heading".take(CARPOOL_SUBJECT_MAX),
            heading = heading,
            blocks = paragraphs.map { Block(plain = it) } + linkBlock(label = txt(text = t.linkLabel, language = language), link = link),
            branding = branding,
            language = language,
        )
    }

    private const val CARPOOL_SUBJECT_MAX = 150

    // ------------------------------------------------------------------------------------------------------------
    // Welle V1.9.57 "Admin-Peer-Schutz" -- receipts: what happened, who (display name), when, one thing to do.
    // ------------------------------------------------------------------------------------------------------------

    /** A display name is free text: strip control characters (headers/plain text); the HTML part is escaped by kotlinx-html. */
    private fun safeName(name: String): String = sanitizeSubjectFragment(name).take(120).ifBlank { "?" }

    /** To the TARGET: a temporary password was requested; a second administrator must approve. The objection link is the one thing to do. */
    fun peerRequestForTarget(
        rawVetoToken: String,
        actorName: String,
        notBefore: LocalDateTime?,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PeerNotificationTexts
        val link = "${branding.publicBaseUrl}/app#/privileged-action-veto?token=$rawVetoToken"
        return single(
            subject = subjectWithBrand(lead = txt(text = t.requestSubjectLead, language = language), branding = branding),
            heading = txt(text = t.requestHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.requestBody,
                                language = language,
                                "actor" to safeName(actorName),
                                "brand" to branding.fromDisplayName,
                                "when" to formatUtc(at = notBefore, language = language),
                            ),
                    ),
                    Block(plain = txt(text = t.requestAdvice, language = language)),
                    linkBlock(label = txt(text = t.requestLinkLabel, language = language), link = link),
                ),
            branding = branding,
            language = language,
        )
    }

    /** To an eligible approver: a request awaits their decision. */
    fun peerApprovalNeeded(
        actorName: String,
        targetName: String,
        action: PrivilegedActionKind,
        expiresAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PeerNotificationTexts
        val body =
            when (action) {
                PrivilegedActionKind.TEMP_PASSWORD -> t.approvalTempPassword
                PrivilegedActionKind.DEMOTE -> t.approvalDemote
                PrivilegedActionKind.SUSPEND -> t.approvalSuspend
            }
        return single(
            subject = subjectWithBrand(lead = txt(text = t.approvalSubjectLead, language = language), branding = branding),
            heading = txt(text = t.approvalHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = body,
                                language = language,
                                "actor" to safeName(actorName),
                                "target" to safeName(targetName),
                                "when" to formatUtc(at = expiresAt, language = language),
                            ),
                    ),
                    Block(plain = txt(text = t.approvalWhere, language = language)),
                    linkBlock(label = txt(text = t.approvalLinkLabel, language = language), link = "${branding.publicBaseUrl}/app"),
                ),
            branding = branding,
            language = language,
        )
    }

    /** To the TARGET: the action was carried out. */
    fun peerExecutedForTarget(
        event: PeerExecutedEvent,
        actorName: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PeerNotificationTexts
        val sentence =
            when (event) {
                PeerExecutedEvent.TEMPORARY_PASSWORD_SET -> t.executedTempPassword
                PeerExecutedEvent.ROLE_CHANGED -> t.executedRoleChanged
                PeerExecutedEvent.ACCESS_SUSPENDED -> t.executedSuspended
                PeerExecutedEvent.STATUS_CHANGED -> t.executedStatusChanged
            }
        val trigger =
            txt(
                text = t.executedTrigger,
                language = language,
                "actor" to safeName(actorName),
                "when" to formatUtc(at = occurredAt, language = language),
            )
        return single(
            subject = subjectWithBrand(lead = txt(text = t.executedSubjectLead, language = language), branding = branding),
            heading = txt(text = t.executedSubjectLead, language = language),
            blocks =
                listOf(
                    Block(plain = "${txt(text = sentence, language = language)} $trigger"),
                    Block(plain = txt(text = MailCommonTexts.notExpectedContact, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /** To the TARGET administrator: an administrator triggered a password-reset mail for the account. */
    fun peerResetMailTriggered(
        actorName: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PeerNotificationTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.resetSubjectLead, language = language), branding = branding),
            heading = txt(text = t.resetSubjectLead, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.resetBody,
                                language = language,
                                "actor" to safeName(actorName),
                                "when" to formatUtc(at = occurredAt, language = language),
                                "brand" to branding.fromDisplayName,
                            ),
                    ),
                    Block(plain = txt(text = t.resetAdvice, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /** To the TARGET administrator: an administrator changed their address or beneficial-owner data. */
    fun peerProtectedDataChanged(
        actorName: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PeerNotificationTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.protectedSubjectLead, language = language), branding = branding),
            heading = txt(text = t.protectedHeading, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.protectedBody,
                                language = language,
                                "actor" to safeName(actorName),
                                "when" to formatUtc(at = occurredAt, language = language),
                                "brand" to branding.fromDisplayName,
                            ),
                    ),
                    Block(plain = txt(text = MailCommonTexts.notExpectedContact, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /** To every OTHER administrator: a new administrator appeared. */
    fun peerNewAdministrator(
        newAdminName: String,
        actorName: String,
        occurredAt: LocalDateTime,
        branding: MailBranding,
        language: String = "de",
    ): RenderedMail {
        val t = PeerNotificationTexts
        return single(
            subject = subjectWithBrand(lead = txt(text = t.newAdminSubjectLead, language = language), branding = branding),
            heading = txt(text = t.newAdminSubjectLead, language = language),
            blocks =
                listOf(
                    Block(
                        plain =
                            txt(
                                text = t.newAdminBody,
                                language = language,
                                "actor" to safeName(actorName),
                                "newAdmin" to safeName(newAdminName),
                                "when" to formatUtc(at = occurredAt, language = language),
                                "brand" to branding.fromDisplayName,
                            ),
                    ),
                    Block(plain = txt(text = MailCommonTexts.notExpectedReview, language = language)),
                ),
            branding = branding,
            language = language,
        )
    }

    /**
     * V1.9.118 -- "your membership application was approved". Subject in [language]; body block in [language] first, then German
     * (or English when [language] is German). Link: the plain `/app` URL, no parameters. Does not use [footer] (German only, and it
     * would name the base URL as the contact when no reply-to exists) -- every block carries its own contact line.
     */
    fun membershipApproved(
        displayName: String,
        language: String,
        branding: MailBranding,
    ): RenderedMail {
        val link = membershipLoginLink(branding)
        return membershipDecision(language = language, branding = branding, link = link) { t ->
            DecisionBlock(
                subject = t.subjectApproved,
                paragraphs =
                    listOf(
                        MembershipDecisionTexts.fill(template = t.greeting, name = displayName),
                        MembershipDecisionTexts.fill(template = t.approvedBodyWithOrg, org = branding.fromDisplayName),
                        t.approvedLogin,
                    ),
                linkLabel = t.loginLabelApproved,
                withLink = true,
            )
        }
    }

    /**
     * V1.9.118 -- neutral "your membership application was not approved" notice. Never contains the rejection reason or any board
     * member's name. The [RejectionVariant.FRIEND] variant keeps the friend access visible and links to the login.
     */
    fun membershipRejected(
        displayName: String,
        variant: RejectionVariant,
        language: String,
        branding: MailBranding,
    ): RenderedMail {
        val link = membershipLoginLink(branding)
        return membershipDecision(language = language, branding = branding, link = link) { t ->
            val friend = variant == RejectionVariant.FRIEND
            DecisionBlock(
                subject = t.subjectRejected,
                paragraphs =
                    listOfNotNull(
                        MembershipDecisionTexts.fill(template = t.greeting, name = displayName),
                        MembershipDecisionTexts.fill(template = t.rejectedBodyWithOrg, org = branding.fromDisplayName),
                        if (friend) t.friendKept else null,
                    ),
                linkLabel = t.loginLabelFriend,
                withLink = friend,
            )
        }
    }

    private fun membershipLoginLink(branding: MailBranding): String = branding.publicBaseUrl.trimEnd('/') + "/app"

    private class DecisionBlock(
        val subject: String,
        val paragraphs: List<String>,
        val linkLabel: String,
        val withLink: Boolean,
    )

    private fun membershipDecision(
        language: String,
        branding: MailBranding,
        link: String,
        block: (MembershipDecisionTexts) -> DecisionBlock,
    ): RenderedMail {
        val primary = MembershipDecisionTexts.normalize(language)
        val secondary = if (primary == MembershipDecisionTexts.DEFAULT_LANGUAGE) "en" else MembershipDecisionTexts.DEFAULT_LANGUAGE
        val sections =
            listOf(primary, secondary).map { code ->
                val texts = MembershipDecisionTexts.forLanguage(code)
                val b = block(texts)
                val contact =
                    branding.replyTo?.let { MembershipDecisionTexts.fill(template = texts.contactReplyTo, address = it) }
                        ?: texts.contactBoard
                MailSection(
                    heading = b.subject,
                    paragraphs = b.paragraphs + contact,
                    linkLabel = if (b.withLink) b.linkLabel else null,
                    link = if (b.withLink) link else null,
                )
            }
        val subject = sanitizeSubjectFragment(sections.first().heading)
        return renderSections(subject = subject, sections = sections)
    }

    /** N language blocks, each with its own contact line, no shared footer -- plain text and HTML (auto-escaped) carry identical content. */
    private fun renderSections(
        subject: String,
        sections: List<MailSection>,
    ): RenderedMail {
        fun plain(section: MailSection): String =
            buildString {
                append(section.heading).append("\n\n")
                section.paragraphs.forEach { append(it).append("\n\n") }
                if (section.link != null) append(section.linkLabel).append(":\n").append(section.link).append("\n\n")
            }
        val plainText = sections.joinToString("----\n\n") { plain(it) }
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) hr()
                        h1 { +section.heading }
                        section.paragraphs.forEach { paragraph -> p { +paragraph } }
                        if (section.link != null) {
                            p { a(href = section.link) { +(section.linkLabel ?: section.link) } }
                        }
                    }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /** One language block of a bilingual mail ([link] and [linkLabel] are both set or both null). */
    private data class MailSection(
        val heading: String,
        val paragraphs: List<String>,
        val linkLabel: String?,
        val link: String?,
    )

    /** `{token}` text of [text] in [language], filled in ONE pass ([fillMail]). */
    private fun txt(
        text: MailText,
        language: String,
        vararg values: Pair<String, String>,
    ): String = fillMail(template = text.forLanguage(language), values = values.toMap())

    private fun subjectWithBrand(
        lead: String,
        branding: MailBranding,
    ): String = "$lead – ${branding.fromDisplayName}"

    /**
     * One block (paragraph) of a single-language mail: [plain] for the plain-text part, [html] for the HTML part (default: the same text).
     * With a [link] the HTML block is `html + " " + <a>`; a [bold] block is rendered in `<b>`.
     */
    private class Block(
        val plain: String,
        val html: String = plain,
        val link: String? = null,
        val linkLabel: String? = null,
        val bold: Boolean = false,
    )

    private fun linkBlock(
        label: String,
        link: String,
    ): Block = Block(plain = "$label:\n$link", html = "", link = link, linkLabel = label)

    /** ONE language, ONE footer -- plain text and HTML (kotlinx-html auto-escaping) carry identical content. */
    private fun single(
        subject: String,
        heading: String,
        blocks: List<Block>,
        branding: MailBranding,
        language: String,
    ): RenderedMail {
        val footer = footer(branding = branding, language = language)
        val plainText = heading + "\n\n" + blocks.joinToString("") { it.plain + "\n\n" } + footer
        val html =
            createHTML().html {
                head { title { +subject } }
                body {
                    h1 { +heading }
                    blocks.forEach { block ->
                        p {
                            if (block.bold) {
                                b { +block.html }
                            } else {
                                +block.html
                            }
                            if (block.link != null) {
                                if (block.html.isNotEmpty()) +" "
                                a(href = block.link) { +(block.linkLabel ?: block.link) }
                            }
                        }
                    }
                    p { +footer }
                }
            }
        return RenderedMail(subject = subject, plainText = plainText, html = html)
    }

    /** `2026-10-08 14:30 UTC` -- class-A system timestamps are UTC, a mail has no viewer zone to convert to. A `null` is "unknown" in [language]. */
    private fun formatUtc(
        at: LocalDateTime?,
        language: String,
    ): String {
        if (at == null) return MailCommonTexts.unknownTime.forLanguage(language)
        return "${at.date} ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')} UTC"
    }

    /**
     * Strips CR/LF and the Unicode NEL/LINE SEPARATOR/PARAGRAPH SEPARATOR characters (`\r`, `\n`,
     * `\u0085`, ` `, ` `) plus any other control character from [fragment], replacing each
     * with a single space, then trims -- a `Subject:` header value must never contain a raw line
     * break (mail-header-injection), and an article title is author-supplied free text that has
     * never been checked for this.
     */
    internal fun sanitizeSubjectFragment(fragment: String): String =
        fragment
            .map { c -> if (c == '\r' || c == '\n' || c == '\u0085' || c == ' ' || c == ' ' || c.isISOControl()) ' ' else c }
            .joinToString("")
            .trim()

    /**
     * Last line of every mail, plain text AND HTML identical (V1.2.3 design review, point 4b: no mail without a way back), in [language]
     * (V1.9.334). Goes through kotlinx-html's auto-escaping in the HTML part -- [MailBranding.replyTo]/[MailBranding.publicBaseUrl] are both
     * deployment-configurable and are therefore not trusted here.
     */
    private fun footer(
        branding: MailBranding,
        language: String,
    ): String =
        branding.replyTo?.let { fillMail(template = MailCommonTexts.footerReplyTo.forLanguage(language), values = mapOf("address" to it)) }
            ?: fillMail(template = MailCommonTexts.footerNoReply.forLanguage(language), values = mapOf("url" to branding.publicBaseUrl))

    private fun formatTtl(
        ttl: Duration,
        language: String,
    ): String = hoursText(n = ttl.inWholeHours, language = language)
}
