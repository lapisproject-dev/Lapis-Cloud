package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties

/** A stand-in for Angus' `SMTPAddressFailedException`: public `getReturnCode()`, found reflectively (Angus is runtime-only). */
private class FakeSmtpAddressFailedException(
    private val code: Int,
    message: String,
) : MessagingException(message) {
    fun getReturnCode(): Int = code
}

/** The simple class name `MailConnectException` is what the classifier keys on (Angus is runtime-only, no import). */
private class MailConnectException(
    cause: Throwable,
) : MessagingException("Couldn't connect to host, secret.person@example.org", cause as Exception)

private fun sendWith(failure: Throwable): MailSendOutcome.Failed {
    val config =
        (
            SmtpConfig.load(
                env = {
                    mapOf(
                        SmtpConfig.ENV_HOST to "mail.example.invalid",
                        SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                        SmtpConfig.ENV_PASSWORD to "s3cr3t",
                        SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                        SmtpConfig.ENV_FROM_NAME to "Test",
                    )[it]
                },
            ) as SmtpConfigState.Configured
        ).config
    val transport =
        JakartaMailTransport(
            config = config,
            sendMessage = { throw failure },
            sessionFactory = { Session.getInstance(Properties()) },
        )
    return runBlocking { transport.send(to = "member@example.org", subject = "s", plainTextBody = "p", htmlBody = "<p>h</p>") }
        .shouldBeInstanceOf<MailSendOutcome.Failed>()
}

/** Welle V1.9.81 -- how [JakartaMailTransport] classifies a failed send (transient / permanent, reply code, uncertain delivery). */
class JakartaMailTransportClassificationTest :
    FunSpec({
        test("a 4xx reply (read reflectively from the next exception) is TRANSIENT with its code in the error class") {
            listOf(421, 450, 451, 452).forEach { code ->
                val failure =
                    sendWith(
                        SendFailedException(
                            "send failed",
                            FakeSmtpAddressFailedException(code = code, message = "$code 4.2.0 try again later"),
                        ),
                    )
                failure.kind shouldBe MailFailureKind.TRANSIENT
                failure.smtpReplyCode shouldBe code
                failure.errorClass shouldBe "SMTP_$code"
                failure.deliveryUncertain shouldBe false
            }
        }

        test("a 5xx reply is PERMANENT") {
            listOf(500, 550, 553, 554).forEach { code ->
                val failure =
                    sendWith(
                        SendFailedException("send failed", FakeSmtpAddressFailedException(code = code, message = "$code no such user")),
                    )
                failure.kind shouldBe MailFailureKind.PERMANENT
                failure.smtpReplyCode shouldBe code
                failure.errorClass shouldBe "SMTP_$code"
            }
        }

        test("the reply code is also found down the cause chain") {
            val failure = sendWith(RuntimeException("wrapper", FakeSmtpAddressFailedException(code = 451, message = "451 later")))
            failure.kind shouldBe MailFailureKind.TRANSIENT
            failure.smtpReplyCode shouldBe 451
        }

        test("without any getReturnCode a leading code of the message is used -- only to read the number") {
            val failure = sendWith(MessagingException("421 4.3.2 Service not available, user secret.person@example.org"))
            failure.kind shouldBe MailFailureKind.TRANSIENT
            failure.smtpReplyCode shouldBe 421
            failure.errorClass shouldBe "SMTP_421"
            failure.sanitizedErrorMessage shouldNotContain "secret.person"
            failure.errorClass shouldNotContain "secret"
        }

        test("a connection failure before anything was sent is TRANSIENT, unambiguous (not uncertain)") {
            listOf(
                MailConnectException(ConnectException("refused")),
                MailConnectException(SocketTimeoutException("connect timed out")),
                MessagingException("could not connect", UnknownHostException("mail.example.invalid")),
            ).forEach { failure ->
                val outcome = sendWith(failure)
                outcome.kind shouldBe MailFailureKind.TRANSIENT
                outcome.errorClass shouldBe "CONNECT"
                outcome.deliveryUncertain shouldBe false
                outcome.smtpReplyCode shouldBe null
            }
        }

        test("a read timeout (not during connect) is TRANSIENT but the delivery is UNCERTAIN") {
            val outcome = sendWith(MessagingException("read timed out", SocketTimeoutException("Read timed out")))
            outcome.kind shouldBe MailFailureKind.TRANSIENT
            outcome.deliveryUncertain shouldBe true
            outcome.errorClass shouldBe "TIMEOUT"
        }

        test("an authentication failure is PERMANENT, class AUTH") {
            val outcome = sendWith(AuthenticationFailedException("535 5.7.8 bad credentials for no_reply@example.org"))
            outcome.kind shouldBe MailFailureKind.PERMANENT
            outcome.errorClass shouldBe "AUTH"
        }

        test("anything unknown is conservatively PERMANENT -- never retried on a guess") {
            val outcome = sendWith(IllegalStateException("boom"))
            outcome.kind shouldBe MailFailureKind.PERMANENT
            outcome.errorClass shouldBe "UNKNOWN"
            outcome.deliveryUncertain shouldBe false
        }

        test("no address, token or message text ever reaches sanitizedErrorMessage or errorClass; the class uses the closed alphabet") {
            val hostile =
                listOf(
                    SendFailedException(
                        "550 5.1.1 <secret.person@example.org> unknown",
                        FakeSmtpAddressFailedException(code = 550, message = "550 secret.person@example.org"),
                    ),
                    MessagingException("451 token=abc123 for secret.person@example.org"),
                    MailConnectException(ConnectException("to secret.person@example.org")),
                    AuthenticationFailedException("secret.person@example.org"),
                    IllegalStateException("secret.person@example.org"),
                )
            hostile.forEach { failure ->
                val outcome = sendWith(failure)
                outcome.sanitizedErrorMessage shouldNotContain "secret.person"
                outcome.sanitizedErrorMessage shouldNotContain "abc123"
                outcome.errorClass shouldNotContain "secret"
                outcome.errorClass shouldMatch Regex("^[A-Z0-9_]{1,64}$")
            }
        }

        test("sanitizeErrorClass filters to [A-Z0-9_], caps the length, and never returns an empty class") {
            MailSendOutcome.Failed.sanitizeErrorClass("smtp 451!") shouldBe "451"
            MailSendOutcome.Failed.sanitizeErrorClass("SMTP-451") shouldBe "SMTP451"
            MailSendOutcome.Failed.sanitizeErrorClass("") shouldBe "UNKNOWN"
            MailSendOutcome.Failed.sanitizeErrorClass("öäü") shouldBe "UNKNOWN"
            MailSendOutcome.Failed.sanitizeErrorClass("A".repeat(200)).length shouldBe 64
        }
    })
