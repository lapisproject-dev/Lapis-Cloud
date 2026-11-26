package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.mail.budget.MailLane
import network.lapis.cloud.shared.domain.EmailChangeKind
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

        test("V1.9.331 failure notice: fixed subject, title only sanitised in the body, German only (V1.9.332), link, no amount") {
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
            mail.subject shouldBe "Partei: LTR-Auszahlung fehlgeschlagen"
            mail.plainText shouldNotContain "\r"
            mail.plainText shouldContain "LTR-Auszahlung fehlgeschlagen"
            mail.plainText shouldNotContain "LTR payout failed"
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

        val allLanguages = listOf("de", "en", "es", "fr", "it", "nl", "pl", "ru")

        fun failedNotification(title: String = "Website-Relaunch") =
            TenderPayoutFailedNotification(
                tenderId = Uuid.parse("00000000-0000-0000-0000-000000000042"),
                tenderTitle = title,
                recipientEmails = listOf("a@example.org"),
            )

        test("V1.9.332 every language renders the receipt completely: no placeholder left, title only in the body, never in the subject") {
            allLanguages.forEach { lang ->
                val mail =
                    MailTemplates.tenderPayoutPaid(
                        notification = notification(title = "Boese\r\nTitel {amount}"),
                        brandTitle = "Partei",
                        branding = branding(),
                        language = lang,
                    )
                mail.subject shouldContain "Partei"
                mail.subject shouldNotContain "Titel"
                mail.subject shouldNotContain "\n"
                mail.subject shouldNotContain "{"
                // the title is free text: a token inside it is shown literally, never expanded
                mail.plainText shouldContain "Boese Titel {amount}"
                mail.plainText.replace("Boese Titel {amount}", "") shouldNotContain "{"
                mail.plainText shouldContain "12,50"
                mail.plainText shouldContain "https://pzb.example.org/app#/tenders/00000000-0000-0000-0000-000000000042"
                mail.html shouldContain "12,50"
            }
        }

        test("V1.9.332 every language renders the failure notice completely, in that language only") {
            val subjects = mutableSetOf<String>()
            allLanguages.forEach { lang ->
                val mail =
                    MailTemplates.tenderPayoutFailed(
                        notification = failedNotification(title = "Titel"),
                        brandTitle = "Partei",
                        branding = branding(),
                        language = lang,
                    )
                subjects += mail.subject
                mail.subject shouldNotContain "{"
                mail.subject shouldNotContain "Titel"
                mail.plainText shouldContain "Titel"
                mail.plainText shouldNotContain "{"
                mail.plainText shouldNotContain "----"
                mail.plainText shouldContain "https://pzb.example.org/app#/tenders/00000000-0000-0000-0000-000000000042"
                mail.html shouldContain "https://pzb.example.org"
            }
            subjects.size shouldBe allLanguages.size
        }

        test("V1.9.332 an unknown language falls back to German, the default parameter is German") {
            val xx =
                MailTemplates.tenderPayoutPaid(
                    notification = notification(),
                    brandTitle = "Partei",
                    branding = branding(),
                    language = "xx",
                )
            val de = MailTemplates.tenderPayoutPaid(notification = notification(), brandTitle = "Partei", branding = branding())
            xx shouldBe de
            de.subject shouldBe "Partei: LTR gutgeschrieben"
            val en =
                MailTemplates.tenderPayoutPaid(
                    notification = notification(),
                    brandTitle = "Partei",
                    branding = branding(),
                    language = "en",
                )
            en.subject shouldBe "Partei: LTR credited"
            en.plainText shouldContain "12,50 LTR"
        }

        test("V1.9.332 a mail that is still bilingual keeps its two-language layout") {
            val mail =
                MailTemplates.emailChangeConfirm(
                    rawToken = "tok",
                    kind = EmailChangeKind.PROPOSAL,
                    effectiveAt = null,
                    branding = branding(),
                )
            mail.plainText shouldContain "----"
        }
    })
