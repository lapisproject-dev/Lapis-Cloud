package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.CarpoolNoticeKind
import kotlin.uuid.Uuid

/** V1.9.334 -- header injection, placeholder recursion and HTML escaping in a NON-German language. */
class MailLanguageSecurityTest :
    FunSpec({
        val branding = MailBranding(fromDisplayName = "Partei", replyTo = null, publicBaseUrl = "https://pzb.example.org")
        val at = LocalDateTime(2026, 10, 8, 14, 30)
        val hostile = "Raum\r\nBcc: x@evil.example zwei\u0085drei\u0007" + "y".repeat(200)

        fun noLineBreak(subject: String) {
            subject.any { it == '\r' || it == '\n' || it == ' ' || it == ' ' || it == '\u0085' || it.isISOControl() } shouldBe false
        }

        test("an encounter title with line breaks cannot add a header in pl / ru / fr") {
            listOf("pl", "ru", "fr").forEach { lang ->
                val notice =
                    EncounterEntryNotice(
                        recipients = listOf("a@example.org"),
                        spaceTitle = hostile,
                        kind = EncounterEntryNotice.Kind.FIRST_GUEST,
                        at = at,
                        windowEnd = null,
                        entries = 1,
                        presentCount = 2,
                    )
                noLineBreak(MailTemplates.encounterEntryNotice(notice = notice, branding = branding, language = lang).subject)
            }
        }

        test("carpool places and an article title with line breaks cannot add a header in it / nl") {
            listOf("it", "nl").forEach { lang ->
                CarpoolNoticeKind.entries.forEach { kind ->
                    noLineBreak(
                        MailTemplates
                            .carpoolNotice(
                                kind = kind,
                                date = LocalDate(2026, 11, 1),
                                fromPlace = hostile,
                                toPlace = hostile,
                                branding = branding,
                                language = lang,
                            ).subject,
                    )
                }
                ArticleReviewOutcome.entries.forEach { outcome ->
                    val mail =
                        MailTemplates.articleReview(
                            notification =
                                ArticleReviewNotification(
                                    articleId = Uuid.random(),
                                    authorEmail = "a@example.org",
                                    title = hostile,
                                    outcome = outcome,
                                    reason = hostile,
                                    publicUrl = null,
                                ),
                            brandTitle = "Partei",
                            branding = branding,
                            language = lang,
                        )
                    noLineBreak(mail.subject)
                }
            }
        }

        test("an actor name with line breaks cannot add a header in es") {
            noLineBreak(
                MailTemplates
                    .peerNewAdministrator(
                        newAdminName = hostile,
                        actorName = hostile,
                        occurredAt = at,
                        branding = branding,
                        language = "es",
                    ).subject,
            )
            noLineBreak(
                MailTemplates
                    .peerRequestForTarget(
                        rawVetoToken = "V",
                        actorName = hostile,
                        notBefore = at,
                        branding = branding,
                        language = "es",
                    ).subject,
            )
        }

        test("a value containing a placeholder is never expanded a second time") {
            val mail =
                MailTemplates.articleReview(
                    notification =
                        ArticleReviewNotification(
                            articleId = Uuid.random(),
                            authorEmail = "a@example.org",
                            title = "{reason} {brand}",
                            outcome = ArticleReviewOutcome.REJECTED,
                            reason = "{title}",
                            publicUrl = null,
                        ),
                    brandTitle = "Partei",
                    branding = branding,
                    language = "fr",
                )
            mail.plainText shouldContain "Motif : {title}"
            mail.plainText shouldContain "« {reason} {brand} »"
            mail.subject shouldContain "{reason} {brand}"
            val peer =
                MailTemplates.peerNewAdministrator(
                    newAdminName = "{when}",
                    actorName = "{brand}",
                    occurredAt = at,
                    branding = branding,
                    language = "pl",
                )
            peer.plainText shouldContain "{brand} nadał(a) dnia 2026-10-08 14:30 UTC użytkownikowi {when} rolę administratora w Partei."
        }

        test("a hostile base URL and reply-to are escaped in ru and fr") {
            val evil = "https://example.org/\"><script>alert(1)</script>"
            listOf("ru", "fr").forEach { lang ->
                MailTemplates
                    .passwordReset(
                        rawToken = "T",
                        branding = branding.copy(publicBaseUrl = evil),
                        language = lang,
                    ).html
                    .shouldNotContain("<script>alert(1)</script>")
                MailTemplates
                    .passwordReset(
                        rawToken = "T",
                        branding = branding.copy(replyTo = "<script>x</script>@example.org"),
                        language = lang,
                    ).html
                    .shouldNotContain("<script>x</script>")
                MailTemplates
                    .carpoolNotice(
                        kind = CarpoolNoticeKind.REQUESTED,
                        date = LocalDate(2026, 11, 1),
                        fromPlace = "<b>",
                        toPlace = "<i>",
                        branding = branding.copy(publicBaseUrl = evil),
                        language = lang,
                    ).html
                    .shouldNotContain("<script>alert(1)</script>")
            }
        }

        test("the security mails keep the ignore / contact hint in every language") {
            MembershipDecisionTexts.SUPPORTED.forEach { lang ->
                MailTemplates.passwordReset(rawToken = "T", branding = branding, language = lang).plainText shouldContain
                    PasswordResetTexts.ignoreHint.forLanguage(lang)
                MailTemplates.friendVerification(rawToken = "T", branding = branding, language = lang).plainText shouldContain
                    FriendVerificationTexts.ignoreHint.forLanguage(lang)
                MailTemplates
                    .emailChangeWarningOld(
                        rawRevokeToken = "T",
                        kind = network.lapis.cloud.shared.domain.EmailChangeKind.PROPOSAL,
                        maskedNewEmail = "m***",
                        effectiveAt = null,
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain EmailChangeTexts.warningAdvice.forLanguage(lang)
                MailTemplates
                    .peerResetMailTriggered(
                        actorName = "A",
                        occurredAt = at,
                        branding = branding,
                        language = lang,
                    ).plainText shouldContain
                    PeerNotificationTexts.resetAdvice.forLanguage(lang)
                MailTemplates.passwordResetByAdmin(occurredAt = at, branding = branding, language = lang).plainText shouldContain
                    AdminPasswordResetTexts.advice.forLanguage(lang)
                // The admin reset mail names neither a password nor an actor.
                MailTemplates
                    .passwordResetByAdmin(
                        occurredAt = at,
                        branding = branding,
                        language = lang,
                    ).plainText
                    .shouldNotContain("TOKEN")
            }
        }
    })
