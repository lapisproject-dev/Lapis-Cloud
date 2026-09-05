package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.EventTicketCode

/** Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [EventTicketPolicy]'s JVM-only surface (minting, URL building). Canonicalization itself is tested in `lapis-shared`'s `EventTicketCodeTest`. */
class EventTicketPolicyTest :
    FunSpec({
        test("newRawCode returns EventTicketCode.CANONICAL_LENGTH characters from EventTicketCode.ALPHABET") {
            val code = EventTicketPolicy.newRawCode()
            code.length shouldBe EventTicketCode.CANONICAL_LENGTH
            code.all { it in EventTicketCode.ALPHABET } shouldBe true
        }

        test("newRawCode is already canonical -- round-trips through EventTicketCode.canonicalize unchanged") {
            val code = EventTicketPolicy.newRawCode()
            EventTicketCode.canonicalize(code) shouldBe code
        }

        test("10000 draws are pairwise distinct") {
            val codes = (1..10_000).map { EventTicketPolicy.newRawCode() }
            codes.toSet().size shouldBe codes.size
        }

        test("ticketUrl embeds the code exactly once") {
            val url = EventTicketPolicy.ticketUrl(baseUrl = "https://example.org", slug = "sommerfest", rawCode = "ABCD1234EFGH5678")
            url shouldBe "https://example.org/veranstaltung/sommerfest/ticket?code=ABCD1234EFGH5678"
        }

        test("ticketUrl trims a trailing slash off baseUrl") {
            val url = EventTicketPolicy.ticketUrl(baseUrl = "https://example.org/", slug = "sommerfest", rawCode = "CODE")
            url shouldBe "https://example.org/veranstaltung/sommerfest/ticket?code=CODE"
        }

        test("ticketPdfUrl embeds the code exactly once") {
            val url = EventTicketPolicy.ticketPdfUrl(baseUrl = "https://example.org", slug = "sommerfest", rawCode = "CODE")
            url shouldBe "https://example.org/veranstaltung/sommerfest/ticket.pdf?code=CODE"
        }
    })
