package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.csv.SpreadsheetCsv
import network.lapis.cloud.shared.domain.EventRegistrationPaymentState
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import java.math.BigDecimal

/** V1.9.204 -- the CSV of the registrations (format, formula guard, time zone, money) and the shared field writer. */
class EventRegistrationCsvTest :
    FunSpec({
        fun row(
            name: String = "Anna Muster",
            member: Boolean = true,
            status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
            wait: Int? = null,
            at: LocalDateTime = LocalDateTime(2030, 1, 15, 10, 30),
            fee: BigDecimal = BigDecimal.ZERO,
            pay: EventRegistrationPaymentState = EventRegistrationPaymentState.FREE,
            email: String? = null,
            message: String? = null,
        ) = EventRegistrationCsvRow(
            name = name,
            isMember = member,
            status = status,
            waitlistPosition = wait,
            registeredAt = at,
            fee = fee,
            paymentState = pay,
            guestEmail = email,
            message = message,
        )

        fun render(
            vararg rows: EventRegistrationCsvRow,
            zone: String = "Europe/Berlin",
        ): String {
            val bytes = EventRegistrationCsv.render(rows = rows.toList(), zone = TimeZone.of(zone))
            bytes.take(3) shouldBe SpreadsheetCsv.UTF8_BOM.toList()
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }

        test("BOM, heading, semicolon separator and CRLF line ends") {
            val text = render(row())
            text shouldStartWith "Name;Art;Status;Wartelistenplatz;Angemeldet am;Gebühr;Zahlungsstatus;E-Mail (Gast);Nachricht\r\n"
            text.endsWith("\r\n") shouldBe true
            text.lines().filter { it.isNotEmpty() }.size shouldBe 2
        }

        test("a member and a guest row with status texts and the waiting list position") {
            val text =
                render(
                    row(),
                    row(
                        name = "Gast Eins",
                        member = false,
                        status = EventRegistrationStatus.WAITLISTED,
                        wait = 3,
                        email = "g@example.org",
                    ),
                )
            text shouldContain "Anna Muster;Mitglied;Angemeldet;;"
            text shouldContain "Gast Eins;Gast;Warteliste;3;"
            text shouldContain ";g@example.org;"
        }

        test("the time is converted from UTC to the organisation zone (winter and summer) and written dd.MM.yyyy HH:mm") {
            render(row(at = LocalDateTime(2030, 1, 15, 10, 30))) shouldContain ";15.01.2030 11:30;"
            render(row(at = LocalDateTime(2030, 7, 15, 10, 30))) shouldContain ";15.07.2030 12:30;"
            render(row(at = LocalDateTime(2030, 7, 15, 10, 30)), zone = "UTC") shouldContain ";15.07.2030 10:30;"
        }

        test("money has two decimals, a comma and no thousands separator") {
            EventRegistrationCsv.amountText(BigDecimal("12.5")) shouldBe "12,50"
            EventRegistrationCsv.amountText(BigDecimal("1234.5")) shouldBe "1234,50"
            EventRegistrationCsv.amountText(BigDecimal.ZERO) shouldBe "0,00"
            render(row(fee = BigDecimal("1234.5"), pay = EventRegistrationPaymentState.PAID)) shouldContain ";1234,50;Bezahlt;"
        }

        test("a message with a line break, a quote and a semicolon is quoted and stays one cell") {
            val text = render(row(message = "Zeile 1\nZeile \"2\"; Ende"))
            text shouldContain "\"Zeile 1\nZeile \"\"2\"\"; Ende\"\r\n"
        }

        test("formula injection: name, message and guest e-mail all get the guard") {
            listOf("=1+1", "+cmd", "-2", "@SUM(A1)", "\tx", "\rx").forEach { evil ->
                val text = render(row(name = evil, message = evil, email = evil, member = false))
                text shouldContain "\"'$evil\""
                // every cell that started with the trigger is guarded and quoted: name, e-mail and message
                text.split("\"'$evil\"").size - 1 shouldBe 3
            }
        }

        test("SpreadsheetCsv.field quotes only when needed and guards a leading trigger") {
            SpreadsheetCsv.field(value = "plain") shouldBe "plain"
            SpreadsheetCsv.field(value = "a;b") shouldBe "\"a;b\""
            SpreadsheetCsv.field(value = "a\"b") shouldBe "\"a\"\"b\""
            SpreadsheetCsv.field(value = "=x") shouldBe "\"'=x\""
            SpreadsheetCsv.field(value = "a=b") shouldBe "a=b"
            SpreadsheetCsv.field(value = "a,b", separator = ',') shouldBe "\"a,b\""
            SpreadsheetCsv.field(value = "") shouldBe ""
        }
    })
