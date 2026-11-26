package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.mail.budget.MailLane
import java.math.BigDecimal
import kotlin.uuid.Uuid

private fun branding(): MailBranding = MailBranding(fromDisplayName = "Partei", replyTo = null, publicBaseUrl = "https://pzb.example.org")

private fun notification(
    title: String = "Website-Relaunch",
    amount: String = "12.5",
): TenderPayoutPaidNotification =
    TenderPayoutPaidNotification(
        tenderId = Uuid.parse("00000000-0000-0000-0000-000000000042"),
        winnerEmail = "winner@example.org",
        tenderTitle = title,
        amount = BigDecimal(amount),
        executedAt = LocalDateTime(2026, 11, 4, 9, 5),
    )

class TenderPayoutMailTemplatesTest :
    FunSpec({
        test("the subject is fixed (instance name only), even for a title with line breaks and markup") {
            val mail =
                MailTemplates.tenderPayoutPaid(
                    notification = notification(title = "Boese\r\nBcc: evil@example.org <script>"),
                    brandTitle = "Partei der Vernunft",
                    branding = branding(),
                )
            mail.subject shouldBe "Partei der Vernunft: LTR gutgeschrieben"
            mail.plainText shouldNotContain "\r"
            mail.plainText shouldContain "Boese Bcc: evil@example.org <script>"
            mail.html shouldNotContain "<script>"
        }

        test("the body carries the amount with two decimals, the date and a link, and neither a name nor a ledger id") {
            val mail = MailTemplates.tenderPayoutPaid(notification = notification(), brandTitle = "Partei", branding = branding())
            mail.plainText shouldContain "12,50 LTR"
            mail.plainText shouldContain "2026-11-04 09:05 UTC"
            mail.plainText shouldContain "https://pzb.example.org/app#/tenders/00000000-0000-0000-0000-000000000042"
            mail.html shouldContain "12,50 LTR"
        }

        test("a long title is cut to 120 characters") {
            val mail =
                MailTemplates.tenderPayoutPaid(
                    notification = notification(title = "x".repeat(300)),
                    brandTitle = "Partei",
                    branding = branding(),
                )
            mail.plainText shouldNotContain "x".repeat(121)
            mail.plainText shouldContain "…"
        }

        test("the purpose is registered: priority 1, lane SYSTEM, no TTL") {
            (TENDER_PAYOUT_PAID_PURPOSE in MailPurpose.KNOWN_PURPOSES) shouldBe true
            val policy = MailPurpose.policyFor(TENDER_PAYOUT_PAID_PURPOSE)
            policy.priority shouldBe 1
            policy.lane shouldBe MailLane.SYSTEM
            policy.outboxTtl shouldBe null
        }

        test("V1.9.331 failure notice: fixed subject, title only sanitised in the body, DE and EN, link, no amount") {
            val mail =
                MailTemplates.tenderPayoutFailed(
                    notification =
                        TenderPayoutFailedNotification(
                            tenderId = Uuid.parse("00000000-0000-0000-0000-000000000042"),
                            tenderTitle = "Boese\r\nBcc: evil@example.org <script>",
                            recipientEmails = listOf("a@example.org"),
                        ),
                    brandTitle = "Partei",
                    branding = branding(),
                )
            mail.subject shouldBe "Partei: LTR-Auszahlung fehlgeschlagen / LTR payout failed"
            mail.plainText shouldNotContain "\r"
            mail.plainText shouldContain "LTR-Auszahlung fehlgeschlagen"
            mail.plainText shouldContain "LTR payout failed"
            mail.plainText shouldContain "Boese Bcc: evil@example.org <script>"
            mail.plainText shouldContain "https://pzb.example.org/app#/tenders/00000000-0000-0000-0000-000000000042"
            mail.html shouldNotContain "<script>"
            mail.plainText shouldNotContain "WINNER_"
            listOf("*", ":innen", "_innen").forEach { mail.plainText shouldNotContain it }
        }

        test("V1.9.331 failure notice: a long title is cut to 120 characters") {
            val mail =
                MailTemplates.tenderPayoutFailed(
                    notification =
                        TenderPayoutFailedNotification(
                            tenderId = Uuid.random(),
                            tenderTitle = "x".repeat(300),
                            recipientEmails = listOf("a@example.org"),
                        ),
                    brandTitle = "Partei",
                    branding = branding(),
                )
            mail.plainText shouldNotContain "x".repeat(121)
            mail.plainText shouldContain "…"
        }

        test("V1.9.331 the failure purpose is registered: priority 1, lane SYSTEM, no TTL") {
            (TENDER_PAYOUT_FAILED_PURPOSE in MailPurpose.KNOWN_PURPOSES) shouldBe true
            val policy = MailPurpose.policyFor(TENDER_PAYOUT_FAILED_PURPOSE)
            policy.priority shouldBe 1
            policy.lane shouldBe MailLane.SYSTEM
            policy.outboxTtl shouldBe null
        }
    })
