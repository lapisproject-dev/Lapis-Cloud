package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private class SubjectTransport : MailTransport {
    val subjects = Channel<String>(Channel.UNLIMITED)

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        subjects.send(subject)
        return MailSendOutcome.Sent
    }
}

class SmtpTenderPayoutNotificationMailerLanguageTest :
    FunSpec({
        val branding = MailBranding(fromDisplayName = "Partei", replyTo = null, publicBaseUrl = "https://pzb.example.org")
        val paid =
            TenderPayoutPaidNotification(
                tenderId = Uuid.random(),
                winnerEmail = "w@example.org",
                tenderTitle = "Titel",
                amount = BigDecimal("3"),
                executedAt = LocalDateTime(2026, 11, 4, 9, 5),
            )
        val failed =
            TenderPayoutFailedNotification(tenderId = Uuid.random(), tenderTitle = "Titel", recipientEmails = listOf("b@example.org"))

        fun withMailer(
            language: () -> String,
            block: (SmtpTenderPayoutNotificationMailer, SubjectTransport) -> Unit,
        ) {
            val transport = SubjectTransport()
            val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            try {
                block(
                    SmtpTenderPayoutNotificationMailer(
                        dispatcher = dispatcher,
                        branding = branding,
                        smtpConfigured = true,
                        brandTitle = "Partei",
                        mailLanguage = language,
                    ),
                    transport,
                )
            } finally {
                dispatcher.shutdown()
            }
        }

        fun SubjectTransport.next(): String = runBlocking { withTimeout(10.seconds) { subjects.receive() } }

        test("the language is read at send time: a change between two mails shows") {
            var current = "de"
            withMailer({ current }) { mailer, transport ->
                mailer.sendPaidForWinner(paid)
                transport.next() shouldBe "Partei: LTR gutgeschrieben"
                current = "en"
                mailer.sendPaidForWinner(paid)
                transport.next() shouldBe "Partei: LTR credited"
                mailer.sendFailedForBoard(failed)
                transport.next() shouldBe "Partei: LTR payout failed"
            }
        }

        test("a provider that throws falls back to German and the mail still goes out") {
            withMailer({ error("db down") }) { mailer, transport ->
                mailer.sendPaidForWinner(paid)
                transport.next() shouldContain "gutgeschrieben"
                mailer.sendFailedForBoard(failed)
                transport.next() shouldContain "fehlgeschlagen"
            }
        }
    })
