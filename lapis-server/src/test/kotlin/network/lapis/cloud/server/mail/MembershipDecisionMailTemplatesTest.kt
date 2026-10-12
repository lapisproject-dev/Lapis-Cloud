package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private val LANGUAGES = listOf("de", "en", "es", "fr", "it", "nl", "pl", "ru")

private fun branding(
    replyTo: String? = "vorstand@example.org",
    baseUrl: String = "https://cloud.example.org",
) = MailBranding(fromDisplayName = "Beispielverein", replyTo = replyTo, publicBaseUrl = baseUrl)

class MembershipDecisionMailTemplatesTest :
    FunSpec({
        test("every language renders subject, text and HTML for all three mail kinds, with no empty or unfilled value") {
            LANGUAGES.forEach { lang ->
                val mails =
                    listOf(
                        MailTemplates.membershipApproved(displayName = "Erika Muster", language = lang, branding = branding()),
                        MailTemplates.membershipRejected(
                            displayName = "Erika Muster",
                            variant = RejectionVariant.REJECTED,
                            language = lang,
                            branding = branding(),
                        ),
                        MailTemplates.membershipRejected(
                            displayName = "Erika Muster",
                            variant = RejectionVariant.FRIEND,
                            language = lang,
                            branding = branding(),
                        ),
                    )
                mails.forEach { mail ->
                    mail.subject.isNotBlank() shouldBe true
                    mail.plainText.isNotBlank() shouldBe true
                    mail.html.isNotBlank() shouldBe true
                    listOf(mail.subject, mail.plainText, mail.html).forEach {
                        it shouldNotContain "{name}"
                        it shouldNotContain "{org}"
                        it shouldNotContain "{address}"
                    }
                    mail.plainText shouldContain "Erika Muster"
                    mail.plainText shouldContain "Beispielverein"
                    mail.plainText shouldContain "vorstand@example.org"
                }
            }
        }

        test("all language tables have the same keys: every field of every table is non-blank and has the placeholders it needs") {
            LANGUAGES.forEach { lang ->
                val t = MembershipDecisionTexts.forLanguage(lang)
                listOf(
                    t.subjectApproved,
                    t.greeting,
                    t.approvedBodyWithOrg,
                    t.approvedBody,
                    t.approvedLogin,
                    t.loginLabelApproved,
                    t.subjectRejected,
                    t.rejectedBodyWithOrg,
                    t.rejectedBody,
                    t.friendKept,
                    t.loginLabelFriend,
                    t.contactReplyTo,
                    t.contactBoard,
                ).forEach { it.isNotBlank() shouldBe true }
                t.greeting shouldContain "{name}"
                t.approvedBodyWithOrg shouldContain "{org}"
                t.rejectedBodyWithOrg shouldContain "{org}"
                t.contactReplyTo shouldContain "{address}"
            }
            MembershipDecisionTexts.SUPPORTED shouldBe LANGUAGES.toSet()
        }

        test("the chosen language comes first; German follows a foreign language, English follows German") {
            val pl = MailTemplates.membershipApproved(displayName = "X", language = "pl", branding = branding())
            pl.subject shouldBe MembershipDecisionTexts.forLanguage("pl").subjectApproved
            pl.plainText.indexOf(MembershipDecisionTexts.forLanguage("pl").subjectApproved) shouldBe 0
            pl.plainText shouldContain MembershipDecisionTexts.forLanguage("de").subjectApproved
            pl.plainText shouldNotContain MembershipDecisionTexts.forLanguage("en").subjectApproved

            val de = MailTemplates.membershipApproved(displayName = "X", language = "de", branding = branding())
            de.plainText shouldStartWith MembershipDecisionTexts.forLanguage("de").subjectApproved
            de.plainText shouldContain MembershipDecisionTexts.forLanguage("en").subjectApproved
        }

        test("an unknown language falls back to German") {
            listOf("xx", "", "  ", "DE-AT", "<script>").forEach { odd ->
                MailTemplates.membershipApproved(displayName = "X", language = odd, branding = branding()).subject shouldBe
                    MembershipDecisionTexts.forLanguage("de").subjectApproved
            }
            MailTemplates.membershipApproved(displayName = "X", language = " FR ", branding = branding()).subject shouldBe
                MembershipDecisionTexts.forLanguage("fr").subjectApproved
        }

        test("the rejection reason never appears: the template has no input for it, and the text stays neutral") {
            val marker = "STRENG-GEHEIMER-ABLEHNUNGSGRUND"
            // The API takes no reason at all -- guard the rendered output anyway.
            LANGUAGES.forEach { lang ->
                RejectionVariant.entries.forEach { variant ->
                    val mail =
                        MailTemplates.membershipRejected(
                            displayName = "Erika",
                            variant = variant,
                            language = lang,
                            branding = branding(),
                        )
                    listOf(mail.subject, mail.plainText, mail.html).forEach { it shouldNotContain marker }
                }
            }
        }

        test("a name with markup is escaped in HTML and stays out of the subject") {
            val name = "<script>alert(1)</script>"
            val mail = MailTemplates.membershipApproved(displayName = name, language = "de", branding = branding())
            mail.html shouldNotContain "<script>"
            mail.html shouldContain "&lt;script&gt;"
            mail.subject shouldNotContain "script"
            mail.plainText shouldContain name
        }

        test("the approval link is exactly publicBaseUrl + /app, without parameters, also with a trailing slash") {
            listOf("https://cloud.example.org", "https://cloud.example.org/").forEach { base ->
                val mail = MailTemplates.membershipApproved(displayName = "X", language = "de", branding = branding(baseUrl = base))
                mail.html shouldContain "href=\"https://cloud.example.org/app\""
                mail.plainText shouldContain "\nhttps://cloud.example.org/app\n"
                mail.plainText shouldNotContain "app?"
                mail.plainText shouldNotContain "app#"
                mail.plainText shouldNotContain "token"
            }
        }

        test("the plain rejection has no link, the FRIEND variant says the access stays and links to the login") {
            val plain =
                MailTemplates.membershipRejected(
                    displayName = "X",
                    variant = RejectionVariant.REJECTED,
                    language = "de",
                    branding = branding(),
                )
            plain.plainText shouldNotContain "https://"
            plain.html shouldNotContain "href"
            plain.plainText shouldNotContain MembershipDecisionTexts.forLanguage("de").friendKept

            val friend =
                MailTemplates.membershipRejected(
                    displayName = "X",
                    variant = RejectionVariant.FRIEND,
                    language = "de",
                    branding = branding(),
                )
            friend.plainText shouldContain MembershipDecisionTexts.forLanguage("de").friendKept
            friend.html shouldContain "href=\"https://cloud.example.org/app\""
            plain.subject shouldBe friend.subject
        }

        test("contact line: reply-to when set, otherwise the board; the sender address and the base URL as contact never appear") {
            val withReply =
                MailTemplates.membershipApproved(
                    displayName = "X",
                    language = "de",
                    branding = branding(replyTo = "vorstand@example.org"),
                )
            withReply.plainText shouldContain "antworten Sie einfach auf diese E-Mail (vorstand@example.org)"
            val without =
                MailTemplates.membershipRejected(
                    displayName = "X",
                    variant = RejectionVariant.REJECTED,
                    language = "de",
                    branding = branding(replyTo = null),
                )
            without.plainText shouldContain "wenden Sie sich bitte an den Vorstand"
            without.plainText shouldNotContain "Diese Adresse wird nicht gelesen"
            without.plainText shouldNotContain "cloud.example.org"
        }

        test("German texts do not use gender markers") {
            val t = MembershipDecisionTexts.forLanguage("de")
            val all =
                listOf(
                    t.subjectApproved,
                    t.greeting,
                    t.approvedBodyWithOrg,
                    t.approvedBody,
                    t.approvedLogin,
                    t.loginLabelApproved,
                    t.subjectRejected,
                    t.rejectedBodyWithOrg,
                    t.rejectedBody,
                    t.friendKept,
                    t.loginLabelFriend,
                    t.contactReplyTo,
                    t.contactBoard,
                )
            all.forEach { Regex("""\*|:in\b|:innen|_in\b|_innen|/in\b|/innen""").containsMatchIn(it) shouldBe false }
        }

        test("the subject never contains a line break, even with a hostile organisation name or name") {
            LANGUAGES.forEach { lang ->
                val mail = MailTemplates.membershipApproved(displayName = "A\r\nBcc: x@y.z", language = lang, branding = branding())
                mail.subject shouldNotContain "\r"
                mail.subject shouldNotContain "\n"
            }
        }
    })
