package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.server.mail.budget.MailLane
import kotlin.time.Duration.Companion.seconds

private class CapturingTransport : MailTransport {
    val received = CompletableDeferred<List<String>>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        received.complete(listOf(to, subject, plainTextBody, htmlBody))
        return MailSendOutcome.Sent
    }
}

class SmtpMembershipDecisionMailerTest :
    FunSpec({
        val branding = MailBranding(fromDisplayName = "Beispielverein", replyTo = null, publicBaseUrl = "https://cloud.example.org")

        test("both purposes are registered, priority 1, lane SYSTEM, no TTL") {
            listOf("membership-approved", "membership-rejected").forEach { purpose ->
                MailPurpose.isKnown(purpose) shouldBe true
                (purpose in MailPurpose.PRIORITY_ZERO_PURPOSES) shouldBe false
                val policy = MailPurpose.policyFor(purpose)
                policy.priority shouldBe 1
                policy.lane shouldBe MailLane.SYSTEM
                policy.outboxTtl shouldBe null
            }
        }

        test("sendApproved hands the rendered mail to the dispatcher and reports acceptance") {
            val transport = CapturingTransport()
            val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            try {
                val mailer = SmtpMembershipDecisionMailer(dispatcher = dispatcher, branding = branding)
                mailer.deliveryConfigured shouldBe true
                mailer.sendApproved(email = "erika@example.org", displayName = "Erika", language = "en") shouldBe true
                val received = runBlocking { withTimeout(10.seconds) { transport.received.await() } }
                received[0] shouldBe "erika@example.org"
                received[1] shouldBe MembershipDecisionTexts.forLanguage("en").subjectApproved
                received[2] shouldContain "https://cloud.example.org/app"
            } finally {
                dispatcher.shutdown()
            }
        }

        test("sendRejected passes the dispatcher's refusal through as false") {
            val transport = CapturingTransport()
            val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            dispatcher.shutdown() // closes the channel: every enqueue is now refused
            val mailer = SmtpMembershipDecisionMailer(dispatcher = dispatcher, branding = branding)
            mailer.sendRejected(
                email = "erika@example.org",
                displayName = "Erika",
                variant = RejectionVariant.REJECTED,
                language = "de",
            ) shouldBe
                false
        }

        test("the no-op mailer reports delivery as not configured and sends nothing") {
            NoOpMembershipDecisionMailer.deliveryConfigured shouldBe false
            NoOpMembershipDecisionMailer.sendApproved(email = "a@example.org", displayName = "A", language = "de") shouldBe false
            NoOpMembershipDecisionMailer.sendRejected(
                email = "a@example.org",
                displayName = "A",
                variant = RejectionVariant.FRIEND,
                language = "de",
            ) shouldBe
                false
        }
    })
