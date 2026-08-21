package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [maskEmailForLogging] -- see its own KDoc "CRLF-log-injection guard" for the full
 * writeup. [RegistrationService][network.lapis.cloud.server.rpc.RegistrationService] now rejects a
 * malformed `email` at registration time (see [isValidMailboxAddress] reuse there), but this
 * function is tested independently because it is the last line of defense: any address that DOES
 * reach it -- a pre-existing row from before that guard existed, a future caller that forgets it --
 * must still never be able to forge a second log line.
 */
class MailTransportTest :
    FunSpec({
        test("masks the local part and preserves the domain unchanged for a well-formed address") {
            maskEmailForLogging("max@example.org") shouldBe "m***@example.org"
        }

        test(
            "strips embedded CR/LF so a forged address can never start a second, fake log line (CRLF-log-injection regression)",
        ) {
            // Same attack shape as the finding: a genuine recipient followed by a CRLF and a
            // forged log line impersonating a real ERROR entry.
            val forged = "a@evil.tld\r\n16:05:11.000 [main] ERROR n.l.c.s.security.SessionStore - Admin-Session durch ADMIN widerrufen"

            val masked = maskEmailForLogging(forged)

            masked.contains('\r') shouldBe false
            masked.contains('\n') shouldBe false
            masked shouldBe "a***@evil.tld??16:05:11.000 [main] ERROR n.l.c.s.security.SessionStore - Admin-Session durch ADMIN widerrufen"
        }

        test("strips ISO control characters beyond just CR/LF (e.g. NUL, vertical tab)") {
            val withControl = "x@example.org" + Char(0) + Char(11) + "tail"
            val masked = maskEmailForLogging(withControl)
            masked.any { it.isISOControl() } shouldBe false
            masked shouldBe "x***@example.org??tail"
        }

        test("falls back to *** for an address with no @ at all, even one carrying embedded CR/LF") {
            maskEmailForLogging("not-an-email\r\nfaked-log-line") shouldBe "***"
        }
    })
