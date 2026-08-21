package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties

private fun testConfig(
    fromName: String = "Partei der Vernunft",
    replyTo: String? = null,
): SmtpConfig =
    (
        SmtpConfig.load(
            env = { key ->
                mapOf(
                    SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
                    SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                    SmtpConfig.ENV_PASSWORD to "s3cr3t",
                    SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                    SmtpConfig.ENV_FROM_NAME to fromName,
                    SmtpConfig.ENV_REPLY_TO to replyTo,
                ).filterValues { it != null }[key]
            },
        ) as SmtpConfigState.Configured
    ).config

/** A bare, un-authenticated [Session] good enough to build (never send) a [MimeMessage] with. */
private fun bareSession(): Session = Session.getInstance(Properties())

/**
 * Exercises [JakartaMailTransport] purely through its [JakartaMailTransport.sendMessage]/
 * [JakartaMailTransport.sessionFactory] test seams -- NEVER a real network connection, same
 * discipline `LetterxpressPostalMailProviderTest` establishes for outbound third-party calls.
 */
class JakartaMailTransportTest :
    FunSpec({
        test("happy path -- From/To/Subject correct, multipart/alternative, text/plain before text/html, both UTF-8") {
            var captured: MimeMessage? = null
            val transport =
                JakartaMailTransport(
                    config = testConfig(),
                    sendMessage = { message -> captured = message },
                    sessionFactory = { bareSession() },
                )

            val outcome =
                kotlinx.coroutines.runBlocking {
                    transport.send(
                        to = "member@example.org",
                        subject = "Passwort zurücksetzen",
                        plainTextBody = "plain body",
                        htmlBody = "<p>html body</p>",
                    )
                }

            outcome.shouldBeInstanceOf<MailSendOutcome.Sent>()
            val message = requireNotNull(captured)
            message.from.single().toString() shouldContainAddress "no_reply@example.org"
            message.getRecipients(Message.RecipientType.TO).single().toString() shouldContainAddress "member@example.org"
            message.subject shouldBe "Passwort zurücksetzen"

            val content = message.content
            val multipart = content.shouldBeInstanceOf<MimeMultipart>()
            multipart.contentType shouldContainAddress "multipart/alternative"
            multipart.count shouldBe 2
            val plainPart = multipart.getBodyPart(0)
            val htmlPart = multipart.getBodyPart(1)
            plainPart.contentType.lowercase() shouldContainAddress "text/plain"
            plainPart.contentType.lowercase() shouldContainAddress "utf-8"
            htmlPart.contentType.lowercase() shouldContainAddress "text/html"
            htmlPart.contentType.lowercase() shouldContainAddress "utf-8"
        }

        test("recipient containing a line break -- Failed, sendMessage never called") {
            var sendCalled = false
            val transport =
                JakartaMailTransport(
                    config = testConfig(),
                    sendMessage = { sendCalled = true },
                    sessionFactory = { bareSession() },
                )

            val outcome =
                kotlinx.coroutines.runBlocking {
                    transport.send(
                        to = "evil@example.org\r\nBcc: attacker@example.org",
                        subject = "subject",
                        plainTextBody = "plain",
                        htmlBody = "<p>html</p>",
                    )
                }

            outcome.shouldBeInstanceOf<MailSendOutcome.Failed>()
            sendCalled shouldBe false
        }

        test("subject containing a line break -- Failed, sendMessage never called") {
            var sendCalled = false
            val transport =
                JakartaMailTransport(
                    config = testConfig(),
                    sendMessage = { sendCalled = true },
                    sessionFactory = { bareSession() },
                )

            val outcome =
                kotlinx.coroutines.runBlocking {
                    transport.send(
                        to = "member@example.org",
                        subject = "subject\r\nX-Injected: true",
                        plainTextBody = "plain",
                        htmlBody = "<p>html</p>",
                    )
                }

            outcome.shouldBeInstanceOf<MailSendOutcome.Failed>()
            sendCalled shouldBe false
        }

        test(
            "sendMessage throwing a credential-adjacent MessagingException -- Failed, sanitized, never leaks the raw message",
        ) {
            val transport =
                JakartaMailTransport(
                    config = testConfig(),
                    sendMessage = { throw MessagingException("535 Authentication failed for user no_reply@example.org password geheim") },
                    sessionFactory = { bareSession() },
                )

            val outcome =
                kotlinx.coroutines.runBlocking {
                    transport.send(
                        to = "member@example.org",
                        subject = "subject",
                        plainTextBody = "plain",
                        htmlBody = "<p>html</p>",
                    )
                }

            val failed = outcome.shouldBeInstanceOf<MailSendOutcome.Failed>()
            failed.sanitizedErrorMessage.shouldNotContain("geheim")
            failed.sanitizedErrorMessage.shouldNotContain("password")
            failed.sanitizedErrorMessage.shouldNotContain("no_reply@example.org")
        }

        test("sendMessage throwing ANY exception never propagates out of send()") {
            val transport =
                JakartaMailTransport(
                    config = testConfig(),
                    sendMessage = { throw IllegalStateException("boom") },
                    sessionFactory = { bareSession() },
                )

            val outcome =
                kotlinx.coroutines.runBlocking {
                    transport.send(
                        to = "member@example.org",
                        subject = "subject",
                        plainTextBody = "plain",
                        htmlBody = "<p>html</p>",
                    )
                }

            outcome.shouldBeInstanceOf<MailSendOutcome.Failed>()
        }

        // ── ServiceLoader / provider-lookup guard ──────────────────────────────────────────────
        // Every test above replaces `sendMessage`/`sessionFactory` (see class KDoc) and therefore
        // never touches jakarta.mail's own `META-INF/services/jakarta.mail.Provider` ServiceLoader
        // lookup at all. angus-mail is `runtimeOnly` specifically because it registers itself
        // through that file (see build.gradle.kts KDoc "a naive jar-merge clobbers that services
        // file") -- these two tests are the only ones in this class that exercise the REAL
        // `JakartaMailTransport.createSession` + `Session.getTransport(...)` path, still with zero
        // network I/O (no `.connect()`/`.send()` call), so a future shadow-jar or dependency change
        // that silently drops angus-mail from the runtime classpath fails loudly here instead of
        // only in production with a generic "SMTP send failed (NoSuchProviderException)" log line.
        test("createSession resolves a real \"smtp\" Transport via the Provider ServiceLoader") {
            // getTransport() itself is the assertion -- it throws NoSuchProviderException (not a
            // null return) when no provider is registered for the protocol, which is exactly the
            // failure mode a clobbered META-INF/services file would produce.
            val session = JakartaMailTransport.createSession(testConfig())
            val transport = session.getTransport("smtp")
            transport.javaClass.name.lowercase() shouldContainAddress "smtp"
        }

        test("createSession resolves a real \"smtps\" Transport via the Provider ServiceLoader") {
            val session = JakartaMailTransport.createSession(testConfig())
            val transport = session.getTransport("smtps")
            transport.javaClass.name.lowercase() shouldContainAddress "smtp"
        }

        // ── V1.2.3 Design-Review: Reply-To header, display name ────────────────────────────────

        test("replyTo set -- the captured MimeMessage has exactly one Reply-To header with that address") {
            var captured: MimeMessage? = null
            val transport =
                JakartaMailTransport(
                    config = testConfig(replyTo = "kontakt@example.org"),
                    sendMessage = { message -> captured = message },
                    sessionFactory = { bareSession() },
                )

            kotlinx.coroutines.runBlocking {
                transport.send(to = "member@example.org", subject = "subject", plainTextBody = "plain", htmlBody = "<p>html</p>")
            }

            val message = requireNotNull(captured)
            val replyTo = message.replyTo
            replyTo.size shouldBe 1
            replyTo.single().toString() shouldContainAddress "kontakt@example.org"
        }

        test("replyTo == null -- getReplyTo falls back to the From address, no explicit Reply-To header set") {
            var captured: MimeMessage? = null
            val transport =
                JakartaMailTransport(
                    config = testConfig(replyTo = null),
                    sendMessage = { message -> captured = message },
                    sessionFactory = { bareSession() },
                )

            kotlinx.coroutines.runBlocking {
                transport.send(to = "member@example.org", subject = "subject", plainTextBody = "plain", htmlBody = "<p>html</p>")
            }

            val message = requireNotNull(captured)
            message.getHeader("Reply-To") shouldBe null
            message.replyTo.single().toString() shouldContainAddress "no_reply@example.org"
        }

        test("From header carries the configured display name, including an umlaut case") {
            var captured: MimeMessage? = null
            val transport =
                JakartaMailTransport(
                    config = testConfig(fromName = "Bürgerbüro"),
                    sendMessage = { message -> captured = message },
                    sessionFactory = { bareSession() },
                )

            kotlinx.coroutines.runBlocking {
                transport.send(to = "member@example.org", subject = "subject", plainTextBody = "plain", htmlBody = "<p>html</p>")
            }

            val message = requireNotNull(captured)
            (message.from.single() as InternetAddress).personal shouldBe "Bürgerbüro"
        }

        test("createSession -- port 465 sets ssl.enable=true/starttls.enable=false, port 587 sets starttls.required=true") {
            val implicit = JakartaMailTransport.createSession(testConfig())
            implicit.getProperty("mail.smtp.ssl.enable") shouldBe "true"
            implicit.getProperty("mail.smtp.starttls.enable") shouldBe "false"

            val starttlsConfig =
                (
                    SmtpConfig.load(
                        env = { key ->
                            mapOf(
                                SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
                                SmtpConfig.ENV_PORT to "587",
                                SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                                SmtpConfig.ENV_PASSWORD to "s3cr3t",
                                SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                                SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
                            )[key]
                        },
                    ) as SmtpConfigState.Configured
                ).config
            val starttls = JakartaMailTransport.createSession(starttlsConfig)
            starttls.getProperty("mail.smtp.starttls.required") shouldBe "true"
            starttls.getProperty("mail.smtp.ssl.enable") shouldBe "false"
        }
    })

private infix fun String.shouldContainAddress(expected: String) {
    (this.contains(expected)) shouldBe true
}
