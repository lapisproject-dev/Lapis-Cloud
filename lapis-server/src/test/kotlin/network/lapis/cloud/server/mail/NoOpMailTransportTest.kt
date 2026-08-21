package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

class NoOpMailTransportTest :
    FunSpec({
        test("always returns Skipped, never Sent, never throws") {
            val transport = NoOpMailTransport()
            val outcome =
                runBlocking {
                    transport.send(
                        to = "member@example.org",
                        subject = "subject",
                        plainTextBody = "plain",
                        htmlBody = "<p>html</p>",
                    )
                }
            // Skipped, not Sent -- NoOpMailTransport never actually hands the mail to a relay, and
            // MailDispatcher.sendOne logs the two outcomes differently (see MailSendOutcome.Skipped
            // KDoc for why conflating them was a bug: it produced a "Mail delivered" log line for a
            // mail that was never sent).
            outcome.shouldBeInstanceOf<MailSendOutcome.Skipped>()
        }
    })
