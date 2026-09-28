package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.uuid.Uuid

private fun testBranding(): MailBranding =
    MailBranding(fromDisplayName = "Partei der Vernunft", replyTo = null, publicBaseUrl = "https://pzb.example.org")

private fun notification(
    outcome: ArticleReviewOutcome,
    title: String = "Ein Testartikel",
    reason: String? = null,
    publicUrl: String? = null,
): ArticleReviewNotification =
    ArticleReviewNotification(
        articleId = Uuid.random(),
        authorEmail = "author@example.org",
        title = title,
        outcome = outcome,
        reason = reason,
        publicUrl = publicUrl,
    )

class ArticleReviewMailTemplatesTest :
    FunSpec({
        test("APPROVED: subject/heading mention veroeffentlicht, body links the public URL, no reason sentence") {
            val mail =
                MailTemplates.articleReview(
                    notification =
                        notification(
                            outcome = ArticleReviewOutcome.APPROVED,
                            publicUrl = "https://pzb.example.org/aktuelles/mein-artikel",
                        ),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.subject shouldContain "veröffentlicht"
            mail.subject shouldContain "Ein Testartikel"
            mail.plainText shouldContain "https://pzb.example.org/aktuelles/mein-artikel"
            mail.html shouldContain "https://pzb.example.org/aktuelles/mein-artikel"
            mail.plainText shouldNotContain "Begründung"
        }

        test("REJECTED with a reason: reason appears verbatim, no public URL") {
            val mail =
                MailTemplates.articleReview(
                    notification = notification(outcome = ArticleReviewOutcome.REJECTED, reason = "Thema ist bereits abgedeckt"),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.subject shouldContain "abgelehnt"
            mail.plainText shouldContain "Thema ist bereits abgedeckt"
            mail.html shouldContain "Thema ist bereits abgedeckt"
            mail.plainText shouldNotContain "aktuelles"
        }

        test("REJECTED without a reason: 'Der Vorstand hat keine Begründung angegeben.'") {
            val mail =
                MailTemplates.articleReview(
                    notification = notification(outcome = ArticleReviewOutcome.REJECTED, reason = null),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.plainText shouldContain "Der Vorstand hat keine Begründung angegeben."
        }

        test("UNPUBLISHED: subject mentions depubliziert, reason present") {
            val mail =
                MailTemplates.articleReview(
                    notification = notification(outcome = ArticleReviewOutcome.UNPUBLISHED, reason = "Ausreichend langer Grund"),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.subject shouldContain "depubliziert"
            mail.plainText shouldContain "Ausreichend langer Grund"
        }

        test("no reviewer name ever appears -- signature is only the brandTitle") {
            val mail =
                MailTemplates.articleReview(
                    notification = notification(outcome = ArticleReviewOutcome.APPROVED, publicUrl = "https://pzb.example.org/aktuelles/x"),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.plainText shouldContain "Partei der Vernunft"
            // No board-member-name placeholder of any kind leaks into the rendered text.
            mail.plainText shouldNotContain "Vorstandsmitglied:"
        }

        test("WICHTIG: a title with \\r\\n is sanitized in the subject") {
            val mail =
                MailTemplates.articleReview(
                    notification =
                        notification(
                            outcome = ArticleReviewOutcome.APPROVED,
                            title = "Titel\r\nBcc: attacker@evil.example",
                            publicUrl = "https://pzb.example.org/aktuelles/x",
                        ),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.subject.contains("\r") shouldBe false
            mail.subject.contains("\n") shouldBe false
        }

        test("WICHTIG: a title with U+2028 LINE SEPARATOR is sanitized in the subject") {
            val mail =
                MailTemplates.articleReview(
                    notification =
                        notification(
                            outcome = ArticleReviewOutcome.APPROVED,
                            title = "Titel Injected",
                            publicUrl = "https://pzb.example.org/aktuelles/x",
                        ),
                    brandTitle = "Partei der Vernunft",
                    branding = testBranding(),
                )
            mail.subject.contains(' ') shouldBe false
        }

        test("without SMTP configured, SmtpArticleReviewNotificationMailer never calls MailDispatcher.enqueue") {
            val fakeTransport =
                object : MailTransport {
                    var sendCalls = 0

                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sendCalls++
                        return MailSendOutcome.Sent
                    }
                }
            val dispatcher = MailDispatcher(transport = fakeTransport)
            val mailer =
                SmtpArticleReviewNotificationMailer(
                    dispatcher = dispatcher,
                    branding = MailBranding.notConfigured(),
                    smtpConfigured = false,
                    brandTitle = "Partei der Vernunft",
                )
            mailer.send(notification(outcome = ArticleReviewOutcome.APPROVED, publicUrl = "https://pzb.example.org/aktuelles/x"))
            // Give the dispatcher's worker coroutines a beat -- if enqueue had been called (bug),
            // this would race a real send; smtpConfigured=false must short-circuit BEFORE enqueue.
            Thread.sleep(50)
            fakeTransport.sendCalls shouldBe 0
            dispatcher.shutdown()
        }
    })
