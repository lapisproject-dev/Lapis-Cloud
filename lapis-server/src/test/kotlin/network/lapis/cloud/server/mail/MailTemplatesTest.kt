package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordResetTokenStore

private fun String.countOccurrences(substring: String): Int {
    var count = 0
    var index = 0
    while (true) {
        index = indexOf(substring, index)
        if (index < 0) break
        count++
        index += substring.length
    }
    return count
}

private fun testBranding(
    fromDisplayName: String = "Partei der Vernunft",
    replyTo: String? = null,
    publicBaseUrl: String = "https://pzb.example.org",
): MailBranding =
    MailBranding(
        fromDisplayName = fromDisplayName,
        replyTo = replyTo,
        publicBaseUrl = publicBaseUrl,
    )

class MailTemplatesTest :
    FunSpec({
        test("passwordReset -- plain text and HTML each contain the token exactly once, plus the base URL") {
            val mail = MailTemplates.passwordReset(rawToken = "TOKEN123", branding = testBranding())
            mail.plainText.countOccurrences("TOKEN123") shouldBe 2 // once in the link, once as a copy/paste code
            mail.html.countOccurrences("TOKEN123") shouldBe 2
            mail.plainText shouldContain "https://pzb.example.org/#/password-reset?token=TOKEN123"
            mail.html shouldContain "https://pzb.example.org/#/password-reset?token=TOKEN123"
        }

        test("friendVerification -- plain text and HTML each contain the token, plus the base URL") {
            val mail = MailTemplates.friendVerification(rawToken = "FTOKEN456", branding = testBranding())
            mail.plainText shouldContain "FTOKEN456"
            mail.html shouldContain "FTOKEN456"
            mail.plainText shouldContain "https://pzb.example.org/#/verify-email?token=FTOKEN456"
            mail.html shouldContain "https://pzb.example.org/#/verify-email?token=FTOKEN456"
        }

        test("passwordReset HTML escapes a hostile base URL -- no raw <script> tag") {
            val hostile = "https://example.org/\"><script>alert(1)</script>"
            val mail = MailTemplates.passwordReset(rawToken = "TOKEN123", branding = testBranding(publicBaseUrl = hostile))
            mail.html.shouldNotContain("<script>alert(1)</script>")
        }

        test("friendVerification HTML escapes a hostile base URL -- no raw <script> tag") {
            val hostile = "https://example.org/\"><script>alert(1)</script>"
            val mail =
                MailTemplates.friendVerification(rawToken = "FTOKEN456", branding = testBranding(publicBaseUrl = hostile))
            mail.html.shouldNotContain("<script>alert(1)</script>")
        }

        test("passwordReset mentions the RESET_TTL (1 hour)") {
            PasswordResetTokenStore.RESET_TTL.inWholeHours shouldBe 1L
            val mail = MailTemplates.passwordReset(rawToken = "T", branding = testBranding())
            mail.plainText shouldContain "1 Stunde"
            mail.html shouldContain "1 Stunde"
        }

        test("friendVerification mentions the VERIFICATION_TTL (24 hours)") {
            FriendEmailVerificationTokenStore.VERIFICATION_TTL.inWholeHours shouldBe 24L
            val mail = MailTemplates.friendVerification(rawToken = "T", branding = testBranding())
            mail.plainText shouldContain "24 Stunden"
            mail.html shouldContain "24 Stunden"
        }

        // ── V1.2.3 Design-Review: white-label branding, dash, footer ──────────────────────────

        test("passwordReset subject contains U+2013, never a double-hyphen") {
            val mail = MailTemplates.passwordReset(rawToken = "T", branding = testBranding())
            mail.subject shouldContain "–"
            mail.subject.shouldNotContain("--")
        }

        test("friendVerification subject contains U+2013, never a double-hyphen") {
            val mail = MailTemplates.friendVerification(rawToken = "T", branding = testBranding())
            mail.subject shouldContain "–"
            mail.subject.shouldNotContain("--")
        }

        test("passwordReset subject ends on the configured fromDisplayName") {
            val mail = MailTemplates.passwordReset(rawToken = "T", branding = testBranding(fromDisplayName = "Partei der Vernunft"))
            mail.subject shouldBe "Passwort zurücksetzen – Partei der Vernunft"
        }

        test("friendVerification subject ends on the configured fromDisplayName") {
            val mail =
                MailTemplates.friendVerification(rawToken = "T", branding = testBranding(fromDisplayName = "Partei der Vernunft"))
            mail.subject shouldBe "E-Mail-Adresse bestätigen – Partei der Vernunft"
        }

        test("passwordReset never mentions the product name \"Lapis Cloud\" anywhere") {
            val mail = MailTemplates.passwordReset(rawToken = "T", branding = testBranding())
            mail.subject.shouldNotContain("Lapis Cloud")
            mail.plainText.shouldNotContain("Lapis Cloud")
            mail.html.shouldNotContain("Lapis Cloud")
        }

        test("friendVerification never mentions the product name \"Lapis Cloud\" anywhere") {
            val mail = MailTemplates.friendVerification(rawToken = "T", branding = testBranding())
            mail.subject.shouldNotContain("Lapis Cloud")
            mail.plainText.shouldNotContain("Lapis Cloud")
            mail.html.shouldNotContain("Lapis Cloud")
        }

        test("replyTo == null -> footer points at publicBaseUrl, in plainText and html") {
            val mail =
                MailTemplates.passwordReset(
                    rawToken = "T",
                    branding = testBranding(replyTo = null, publicBaseUrl = "https://pzb.example.org"),
                )
            val expected = "Diese Adresse wird nicht gelesen. Fragen: https://pzb.example.org"
            mail.plainText shouldContain expected
            mail.html shouldContain expected
        }

        test("replyTo set -> footer names the reply-to address, not the fallback hint") {
            val mail =
                MailTemplates.passwordReset(rawToken = "T", branding = testBranding(replyTo = "kontakt@example.org"))
            val expected = "Fragen? Antworten Sie einfach auf diese E-Mail (kontakt@example.org)."
            mail.plainText shouldContain expected
            mail.html shouldContain expected
            mail.plainText.shouldNotContain("Diese Adresse wird nicht gelesen")
        }

        test("hostile replyTo is escaped in the HTML footer -- no raw <script> tag") {
            val hostile = "\"><script>alert(1)</script>@x"
            val mail = MailTemplates.passwordReset(rawToken = "T", branding = testBranding(replyTo = hostile))
            mail.html.shouldNotContain("<script>alert(1)</script>")
        }
    })
