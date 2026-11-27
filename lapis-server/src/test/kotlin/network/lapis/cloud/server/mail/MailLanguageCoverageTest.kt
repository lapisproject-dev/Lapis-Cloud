package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.CarpoolNoticeKind
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.PrivilegedActionKind

/**
 * V1.9.334 -- every system mail in scope, rendered in each of the eight languages: single-language (no `----`, no `<hr`, no " / " pair in the
 * subject), the heading in the mail's own language and not in German/English, no unresolved `{token}`, tokens and links unchanged.
 */
private val branding = MailBranding(fromDisplayName = "Partei", replyTo = null, publicBaseUrl = "https://pzb.example.org")
private val at = LocalDateTime(2026, 10, 8, 14, 30)

private class MailCase(
    val name: String,
    /** The heading of this mail in a language (the `<h1>` text) -- the own-language marker. */
    val heading: (String) -> String,
    val render: (String) -> MailTemplates.RenderedMail,
)

private fun h(text: MailText): (String) -> String = { text.forLanguage(it) }

private val encounterNotice =
    EncounterEntryNotice(
        recipients = listOf("a@example.org"),
        spaceTitle = "Raum",
        kind = EncounterEntryNotice.Kind.FIRST_GUEST,
        at = at,
        windowEnd = null,
        entries = 1,
        presentCount = 3,
    )

private val cases: List<MailCase> =
    buildList {
        add(
            MailCase(name = "passwordReset", heading = h(PasswordResetTexts.heading)) {
                MailTemplates.passwordReset(rawToken = "TOK1", branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "partgReviewerCode", heading = h(PartgReviewerCodeTexts.subject)) {
                MailTemplates.partgReviewerCode(code = "123456", branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "friendVerification", heading = h(FriendVerificationTexts.heading)) {
                MailTemplates.friendVerification(rawToken = "TOK2", branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "webhook", heading = h(WebhookDeactivatedTexts.heading)) {
                MailTemplates.webhookEndpointDeactivated(
                    apiKeyLabel = "key",
                    urlHost = "h.example",
                    eventTypeLabel = "evt",
                    attemptCount = 3,
                    lastHttpStatus = 500,
                    branding = branding,
                    language = it,
                )
            },
        )
        add(
            MailCase(name = "webhook-nohost", heading = h(WebhookDeactivatedTexts.heading)) {
                MailTemplates.webhookEndpointDeactivated(
                    apiKeyLabel = "key",
                    urlHost = null,
                    eventTypeLabel = "evt",
                    attemptCount = 3,
                    lastHttpStatus = null,
                    branding = branding,
                    language = it,
                )
            },
        )
        add(
            MailCase(name = "finTs", heading = h(FinTsReauthTexts.heading)) {
                MailTemplates.finTsReauthRequired(
                    accountLabel = "Konto",
                    ibanMasked = "DE12****",
                    occurredAt = at,
                    branding = branding,
                    language = it,
                )
            },
        )
        add(
            MailCase(name = "adminReset", heading = h(AdminPasswordResetTexts.heading)) {
                MailTemplates.passwordResetByAdmin(occurredAt = at, branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "kcLinked", heading = h(KeycloakLinkTexts.linkedHeading)) {
                MailTemplates.keycloakLinkChangedByAdmin(
                    change = KeycloakLinkChange.LINKED,
                    occurredAt = at,
                    branding = branding,
                    language = it,
                )
            },
        )
        add(
            MailCase(name = "kcUnlinked", heading = h(KeycloakLinkTexts.unlinkedHeading)) {
                MailTemplates.keycloakLinkChangedByAdmin(
                    change = KeycloakLinkChange.UNLINKED,
                    occurredAt = at,
                    branding = branding,
                    language = it,
                )
            },
        )
        add(
            MailCase(name = "kcProvisioned", heading = h(KeycloakProvisioningTexts.provisionedHeading)) {
                MailTemplates.keycloakMemberProvisioned(newMemberName = "Max", occurredAt = at, branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "kcSynced", heading = h(KeycloakProvisioningTexts.syncedHeading)) {
                MailTemplates.keycloakEmailSyncedNotice(
                    maskedNewEmail = "m***@example.org",
                    occurredAt = at,
                    branding = branding,
                    language = it,
                )
            },
        )
        listOf(
            ArticleReviewOutcome.APPROVED to ArticleReviewTexts.approvedHeading,
            ArticleReviewOutcome.REJECTED to ArticleReviewTexts.rejectedHeading,
            ArticleReviewOutcome.UNPUBLISHED to ArticleReviewTexts.unpublishedHeading,
        ).forEach { (outcome, heading) ->
            add(
                MailCase(name = "article-$outcome", heading = h(heading)) {
                    MailTemplates.articleReview(
                        notification =
                            ArticleReviewNotification(
                                articleId = kotlin.uuid.Uuid.random(),
                                authorEmail = "a@example.org",
                                title = "Titel",
                                outcome = outcome,
                                reason = "Grund",
                                publicUrl = "https://pzb.example.org/n/1",
                            ),
                        brandTitle = "Partei",
                        branding = branding,
                        language = it,
                    )
                },
            )
        }
        EmailChangeKind.entries.forEach { kind ->
            val heading = if (kind == EmailChangeKind.PROPOSAL) EmailChangeTexts.proposalHeading else EmailChangeTexts.verifyHeading
            add(
                MailCase(name = "emailConfirm-$kind", heading = h(heading)) {
                    MailTemplates.emailChangeConfirm(rawToken = "TOK3", kind = kind, effectiveAt = at, branding = branding, language = it)
                },
            )
            add(
                MailCase(name = "emailWarning-$kind", heading = h(EmailChangeTexts.warningHeading)) {
                    MailTemplates.emailChangeWarningOld(
                        rawRevokeToken = "TOK4",
                        kind = kind,
                        maskedNewEmail = "m***@example.org",
                        effectiveAt = at,
                        branding = branding,
                        language = it,
                    )
                },
            )
        }
        add(
            MailCase(
                name = "emailSelf",
                heading = h(EmailChangeTexts.changedHeading),
            ) { MailTemplates.emailChangeSelfInfo(maskedNewEmail = "m***@example.org", branding = branding, language = it) },
        )
        add(
            MailCase(
                name = "emailApplied",
                heading = h(EmailChangeTexts.changedHeading),
            ) { MailTemplates.emailChangeAppliedInfo(maskedNewEmail = "m***@example.org", branding = branding, language = it) },
        )
        add(
            MailCase(name = "encounterSingle", heading = { l ->
                fillMail(
                    template = EncounterEntryTexts.headingSingle.forLanguage(l),
                    values =
                        mapOf(
                            "title" to "Raum",
                        ),
                )
            }) {
                MailTemplates.encounterEntryNotice(notice = encounterNotice, branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "encounterWindow", heading = { l ->
                fillMail(
                    template = EncounterEntryTexts.headingWindow.forLanguage(l),
                    values =
                        mapOf(
                            "title" to "Raum",
                        ),
                )
            }) {
                MailTemplates.encounterEntryNotice(
                    notice =
                        encounterNotice.copy(
                            kind = EncounterEntryNotice.Kind.WINDOW,
                            windowEnd = LocalDateTime(2026, 10, 8, 14, 35),
                            entries = 4,
                        ),
                    branding = branding,
                    language = it,
                )
            },
        )
        CarpoolNoticeKind.entries.forEach { kind ->
            val heading =
                when (kind) {
                    CarpoolNoticeKind.REQUESTED -> CarpoolNoticeTexts.requested
                    CarpoolNoticeKind.INVITED -> CarpoolNoticeTexts.invited
                    CarpoolNoticeKind.CONFIRMED -> CarpoolNoticeTexts.confirmed
                    CarpoolNoticeKind.DECLINED -> CarpoolNoticeTexts.declined
                    CarpoolNoticeKind.WITHDRAWN -> CarpoolNoticeTexts.withdrawn
                    CarpoolNoticeKind.POSTING_CHANGED -> CarpoolNoticeTexts.postingChanged
                    CarpoolNoticeKind.POSTING_DELETED -> CarpoolNoticeTexts.postingDeleted
                    CarpoolNoticeKind.MESSAGE -> CarpoolNoticeTexts.message
                }
            add(
                MailCase(
                    name = "carpool-$kind",
                    heading = h(heading),
                ) {
                    MailTemplates.carpoolNotice(
                        kind = kind,
                        date = LocalDate(2026, 11, 1),
                        fromPlace = "Berlin",
                        toPlace = "Hamburg",
                        branding = branding,
                        language = it,
                    )
                },
            )
        }
        add(
            MailCase(name = "peerRequest", heading = h(PeerNotificationTexts.requestHeading)) {
                MailTemplates.peerRequestForTarget(
                    rawVetoToken = "VETO1",
                    actorName = "Admin A",
                    notBefore = at,
                    branding = branding,
                    language = it,
                )
            },
        )
        PrivilegedActionKind.entries.forEach { action ->
            add(
                MailCase(name = "peerApproval-$action", heading = h(PeerNotificationTexts.approvalHeading)) {
                    MailTemplates.peerApprovalNeeded(
                        actorName = "Admin A",
                        targetName = "Admin B",
                        action = action,
                        expiresAt = at,
                        branding = branding,
                        language = it,
                    )
                },
            )
        }
        PeerExecutedEvent.entries.forEach { event ->
            add(
                MailCase(name = "peerExecuted-$event", heading = h(PeerNotificationTexts.executedSubjectLead)) {
                    MailTemplates.peerExecutedForTarget(
                        event = event,
                        actorName = "Admin A",
                        occurredAt = at,
                        branding = branding,
                        language = it,
                    )
                },
            )
        }
        add(
            MailCase(
                name = "peerReset",
                heading = h(PeerNotificationTexts.resetSubjectLead),
            ) { MailTemplates.peerResetMailTriggered(actorName = "Admin A", occurredAt = at, branding = branding, language = it) },
        )
        add(
            MailCase(name = "peerProtected", heading = h(PeerNotificationTexts.protectedHeading)) {
                MailTemplates.peerProtectedDataChanged(actorName = "Admin A", occurredAt = at, branding = branding, language = it)
            },
        )
        add(
            MailCase(name = "peerNewAdmin", heading = h(PeerNotificationTexts.newAdminSubjectLead)) {
                MailTemplates.peerNewAdministrator(
                    newAdminName = "Admin N",
                    actorName = "Admin A",
                    occurredAt = at,
                    branding = branding,
                    language = it,
                )
            },
        )
    }

class MailLanguageCoverageTest :
    FunSpec({
        val languages = MembershipDecisionTexts.SUPPORTED.toList().sorted()

        test("the case list covers every MailTemplates mail of the scope") {
            (cases.size >= 40) shouldBe true
        }

        cases.forEach { case ->
            languages.forEach { lang ->
                test("${case.name} / $lang -- single language, own-language heading, no unresolved token") {
                    val mail = case.render(lang)
                    listOf(mail.subject, mail.plainText, mail.html).forEach { it.isNotBlank() shouldBe true }
                    mail.plainText.shouldNotContain("----")
                    mail.html.shouldNotContain("<hr")
                    mail.subject.shouldNotContain(" / ")
                    mail.subject.shouldNotContain("\n")
                    // No `{token}` left anywhere.
                    Regex("\\{[a-zA-Z]+}").find(mail.plainText + mail.html + mail.subject) shouldBe null
                    // The own-language heading is in the mail ...
                    val heading = case.heading(lang)
                    mail.plainText shouldContain heading
                    // ... and (for any other language) the German and English wording of the same heading is not.
                    if (lang != "de" && case.heading("de") != heading) mail.plainText.shouldNotContain(case.heading("de"))
                    if (lang != "en" && case.heading("en") != heading) mail.plainText.shouldNotContain(case.heading("en"))
                }
            }
        }

        test("tokens and links are unchanged and appear exactly where they did, in every language") {
            languages.forEach { lang ->
                MailTemplates.passwordReset(rawToken = "TOKX", branding = branding, language = lang).also {
                    it.plainText shouldContain "https://pzb.example.org/app#/password-reset?token=TOKX"
                    Regex("TOKX").findAll(it.plainText).count() shouldBe 2
                    Regex("TOKX").findAll(it.html).count() shouldBe 2
                }
                MailTemplates.friendVerification(rawToken = "TOKY", branding = branding, language = lang).plainText shouldContain
                    "https://pzb.example.org/app#/verify-email?token=TOKY"
                MailTemplates
                    .emailChangeConfirm(
                        rawToken = "TOKC",
                        kind = EmailChangeKind.PROPOSAL,
                        effectiveAt = null,
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain
                    "https://pzb.example.org/app#/confirm-email?token=TOKC"
                MailTemplates
                    .emailChangeWarningOld(
                        rawRevokeToken = "TOKR",
                        kind = EmailChangeKind.PROPOSAL,
                        maskedNewEmail = "m***",
                        effectiveAt = null,
                        branding = branding,
                        language = lang,
                    ).also {
                        it.plainText shouldContain "https://pzb.example.org/app#/revoke-email-change?token=TOKR"
                        it.html shouldContain "revoke-email-change?token=TOKR"
                    }
                MailTemplates
                    .peerRequestForTarget(
                        rawVetoToken = "TOKV",
                        actorName = "A",
                        notBefore = at,
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain
                    "https://pzb.example.org/app#/privileged-action-veto?token=TOKV"
            }
        }

        test("fixed wording per language: German unchanged, other languages in their own words") {
            MailTemplates.passwordReset(rawToken = "T", branding = branding, language = "de").subject shouldBe
                "Passwort zurücksetzen – Partei"
            MailTemplates.passwordReset(rawToken = "T", branding = branding, language = "fr").subject shouldBe
                "Réinitialiser le mot de passe – Partei"
            MailTemplates.friendVerification(rawToken = "T", branding = branding, language = "pl").subject shouldBe
                "Potwierdzenie adresu e-mail – Partei"
            MailTemplates.friendVerification(rawToken = "T", branding = branding, language = "ru").subject shouldBe
                "Подтверждение адреса электронной почты – Partei"
            MailTemplates
                .peerNewAdministrator(
                    newAdminName = "N",
                    actorName = "A",
                    occurredAt = at,
                    branding = branding,
                    language = "it",
                ).subject shouldBe
                "Nuovo amministratore – Partei"
            MailTemplates
                .webhookEndpointDeactivated(
                    apiKeyLabel = "k",
                    urlHost = "h",
                    eventTypeLabel = "e",
                    attemptCount = 1,
                    lastHttpStatus = 502,
                    branding = branding,
                    language = "es",
                ).plainText shouldContain
                "último estado HTTP: 502"
        }

        test("hour plurals: pl/ru use one/few/many, the others one/other") {
            hoursText(n = 1, language = "pl") shouldBe "1 godzina"
            hoursText(n = 2, language = "pl") shouldBe "2 godziny"
            hoursText(n = 5, language = "pl") shouldBe "5 godzin"
            hoursText(n = 24, language = "pl") shouldBe "24 godziny"
            hoursText(n = 12, language = "pl") shouldBe "12 godzin"
            hoursText(n = 1, language = "ru") shouldBe "1 час"
            hoursText(n = 2, language = "ru") shouldBe "2 часа"
            hoursText(n = 5, language = "ru") shouldBe "5 часов"
            hoursText(n = 24, language = "ru") shouldBe "24 часа"
            hoursText(n = 11, language = "ru") shouldBe "11 часов"
            hoursText(n = 1, language = "de") shouldBe "1 Stunde"
            hoursText(n = 24, language = "de") shouldBe "24 Stunden"
            hoursText(n = 24, language = "en") shouldBe "24 hours"
            MailTemplates.passwordReset(rawToken = "T", branding = branding, language = "ru").plainText shouldContain "1 час"
            MailTemplates.friendVerification(rawToken = "T", branding = branding, language = "pl").plainText shouldContain "24 godziny"
        }

        test("an unknown language falls back to German") {
            listOf("xx", "", "  ").forEach { lang ->
                MailTemplates.passwordReset(rawToken = "T", branding = branding, language = lang).subject shouldBe
                    "Passwort zurücksetzen – Partei"
            }
            MailTemplates.passwordReset(rawToken = "T", branding = branding, language = "PL ").subject shouldBe "Resetowanie hasła – Partei"
        }

        test("the footer and the unknown time are localised in every language") {
            languages.forEach { lang ->
                val withReply =
                    MailTemplates.passwordReset(
                        rawToken = "T",
                        branding = branding.copy(replyTo = "hilfe@example.org"),
                        language = lang,
                    )
                withReply.plainText shouldContain "hilfe@example.org"
                withReply.plainText shouldContain
                    fillMail(template = MailCommonTexts.footerReplyTo.forLanguage(lang), values = mapOf("address" to "hilfe@example.org"))
                MailTemplates.passwordReset(rawToken = "T", branding = branding, language = lang).plainText shouldContain
                    fillMail(template = MailCommonTexts.footerNoReply.forLanguage(lang), values = mapOf("url" to "https://pzb.example.org"))
                MailTemplates
                    .peerRequestForTarget(
                        rawVetoToken = "V",
                        actorName = "A",
                        notBefore = null,
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain
                    MailCommonTexts.unknownTime.forLanguage(lang)
            }
        }

        test("the person counts of the encounter notice are number-neutral in pl and ru (no wrong plural)") {
            listOf("pl", "ru").forEach { lang ->
                MailTemplates
                    .encounterEntryNotice(
                        notice = encounterNotice.copy(presentCount = 1),
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain
                    ": 1."
                MailTemplates
                    .encounterEntryNotice(
                        notice = encounterNotice.copy(presentCount = 24),
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain
                    ": 24."
            }
        }
    })
