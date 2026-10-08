package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
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
            mail.plainText shouldContain "https://pzb.example.org/app#/password-reset?token=TOKEN123"
            mail.html shouldContain "https://pzb.example.org/app#/password-reset?token=TOKEN123"
        }

        test("friendVerification -- plain text and HTML each contain the token, plus the base URL") {
            val mail = MailTemplates.friendVerification(rawToken = "FTOKEN456", branding = testBranding())
            mail.plainText shouldContain "FTOKEN456"
            mail.html shouldContain "FTOKEN456"
            mail.plainText shouldContain "https://pzb.example.org/app#/verify-email?token=FTOKEN456"
            mail.html shouldContain "https://pzb.example.org/app#/verify-email?token=FTOKEN456"
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

        // ── Welle V1.4.9 "Admin-Passwort-Reset" -- passwordResetByAdmin ──

        test("passwordResetByAdmin -- carries neither a password nor a token, in plainText or html") {
            val occurredAt = LocalDateTime(2026, 9, 9, 14, 30)
            val mail = MailTemplates.passwordResetByAdmin(occurredAt = occurredAt, branding = testBranding())
            mail.plainText shouldContain occurredAt.toString()
            mail.html shouldContain occurredAt.toString()
            mail.plainText.shouldNotContain("href")
            mail.html.shouldNotContain("<a ")
            mail.plainText.shouldNotContain("token")
            mail.html.shouldNotContain("token")
        }

        test("passwordResetByAdmin -- subject and footer follow the same branding conventions as the other templates") {
            val mail =
                MailTemplates.passwordResetByAdmin(
                    occurredAt = LocalDateTime(2026, 9, 9, 14, 30),
                    branding = testBranding(fromDisplayName = "Partei der Vernunft", replyTo = "kontakt@example.org"),
                )
            mail.subject shouldContain "Partei der Vernunft"
            mail.plainText shouldContain "Fragen? Antworten Sie einfach auf diese E-Mail (kontakt@example.org)."
        }

        // ── V1.7.2 security-audit fix -- keycloakLinkChangedByAdmin ──

        test("keycloakLinkChangedByAdmin -- both variants carry the timestamp, no link, and HTML-escape the branding") {
            val occurredAt = LocalDateTime(2026, 9, 23, 10, 15)
            val hostile = "<script>alert(1)</script>"
            for (change in KeycloakLinkChange.entries) {
                val mail =
                    MailTemplates.keycloakLinkChangedByAdmin(
                        change = change,
                        occurredAt = occurredAt,
                        branding = testBranding(fromDisplayName = hostile),
                    )
                mail.plainText shouldContain occurredAt.toString()
                mail.html shouldContain occurredAt.toString()
                mail.html.shouldNotContain(hostile)
                mail.html.shouldNotContain("<a ")
                mail.plainText.shouldNotContain("href")
            }
            MailTemplates
                .keycloakLinkChangedByAdmin(change = KeycloakLinkChange.LINKED, occurredAt = occurredAt, branding = testBranding())
                .plainText shouldContain "verknüpft"
            MailTemplates
                .keycloakLinkChangedByAdmin(change = KeycloakLinkChange.UNLINKED, occurredAt = occurredAt, branding = testBranding())
                .plainText shouldContain "Sitzungen wurden beendet"
        }

        // ── Welle V1.9.76: the anonymous entry notice ─────────────────────

        fun entryNotice(
            title: String = "Sonntagsgottesdienst",
            kind: EncounterEntryNotice.Kind = EncounterEntryNotice.Kind.FIRST_GUEST,
            entries: Int = 1,
        ) = EncounterEntryNotice(
            recipients = listOf("amt@example.org"),
            spaceTitle = title,
            kind = kind,
            at = LocalDateTime(2026, 10, 8, 10, 0),
            windowEnd = if (kind == EncounterEntryNotice.Kind.WINDOW) LocalDateTime(2026, 10, 8, 10, 5) else null,
            entries = entries,
            presentCount = 7,
        )

        test(
            "encounterEntryNotice -- a title with CR/LF, U+2028 and control characters yields a one-line subject, the title cut at 80 characters",
        ) {
            val hostile = "Raum\r\nBcc: x@evil.example\u2028zwei\u0007" + "y".repeat(200)
            val mail = MailTemplates.encounterEntryNotice(notice = entryNotice(title = hostile), branding = testBranding())
            mail.subject.any { it == '\r' || it == '\n' || it == '\u2028' || it == '\u0085' || it.isISOControl() } shouldBe false
            val quoted = mail.subject.substringAfter("„").substringBefore("“")
            (quoted.length <= 80) shouldBe true
            mail.subject shouldContain "jemand ist eingetroffen / someone has arrived"
        }

        test("encounterEntryNotice -- the window mail names the number and the grid bounds, never single entry times") {
            val mail =
                MailTemplates.encounterEntryNotice(
                    notice = entryNotice(kind = EncounterEntryNotice.Kind.WINDOW, entries = 4),
                    branding = testBranding(),
                )
            mail.subject shouldContain "4 Personen sind eingetroffen / 4 people have arrived"
            mail.plainText shouldContain "zwischen 10:00 und 10:05 Uhr"
            mail.plainText shouldContain "between 10:00 and 10:05"
            mail.plainText shouldContain "7 Personen"
            mail.plainText shouldContain "Diese Nachricht nennt bewusst keine Namen"
        }

        test("encounterEntryNotice -- German before English, no link anywhere, the room title is HTML-escaped") {
            val mail =
                MailTemplates.encounterEntryNotice(
                    notice = entryNotice(title = "<script>alert(1)</script>"),
                    branding = testBranding(replyTo = "kontakt@example.org"),
                )
            (mail.plainText.indexOf("Im Begegnungsraum") < mail.plainText.indexOf("Someone has arrived")) shouldBe true
            mail.html.shouldNotContain("href")
            mail.plainText.shouldNotContain("http")
            mail.html.shouldNotContain("<script>")
        }
    })
