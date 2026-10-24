package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.csv.SpreadsheetCsv
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EventRegistrationPaymentState
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import java.math.BigDecimal
import java.math.RoundingMode

/** V1.9.204 -- one line of the registration export; plain values, built inside the read transaction. */
internal data class EventRegistrationCsvRow(
    val name: String,
    val isMember: Boolean,
    val status: EventRegistrationStatus,
    val waitlistPosition: Int?,
    /** System time (UTC), converted to the organisation zone on output. */
    val registeredAt: LocalDateTime,
    val fee: BigDecimal,
    val paymentState: EventRegistrationPaymentState,
    val guestEmail: String?,
    val message: String?,
)

/**
 * V1.9.204 -- renders the registrations of ONE event as a spreadsheet-ready CSV: UTF-8 with BOM, `;` separator, CRLF line ends,
 * German headings. Every cell goes through [SpreadsheetCsv.field] (formula guard). Rows come in the order given.
 */
internal object EventRegistrationCsv {
    val HEADER: List<String> =
        listOf("Name", "Art", "Status", "Wartelistenplatz", "Angemeldet am", "Gebühr", "Zahlungsstatus", "E-Mail (Gast)", "Nachricht")

    fun render(
        rows: List<EventRegistrationCsvRow>,
        zone: TimeZone,
    ): ByteArray {
        val text =
            buildString {
                append(HEADER.joinToString(";") { SpreadsheetCsv.field(value = it) })
                append("\r\n")
                rows.forEach { row ->
                    val cells =
                        listOf(
                            row.name,
                            if (row.isMember) "Mitglied" else "Gast",
                            statusText(row.status),
                            row.waitlistPosition?.toString().orEmpty(),
                            formatWall(ServerClock.systemToWall(utc = row.registeredAt, orgZone = zone)),
                            amountText(row.fee),
                            paymentText(row.paymentState),
                            row.guestEmail.orEmpty(),
                            row.message.orEmpty(),
                        )
                    append(cells.joinToString(";") { SpreadsheetCsv.field(value = it) })
                    append("\r\n")
                }
            }
        return SpreadsheetCsv.UTF8_BOM + text.toByteArray(Charsets.UTF_8)
    }

    fun statusText(status: EventRegistrationStatus): String =
        when (status) {
            EventRegistrationStatus.CONFIRMED -> "Angemeldet"
            EventRegistrationStatus.PENDING_PAYMENT -> "Zahlung offen"
            EventRegistrationStatus.WAITLISTED -> "Warteliste"
            EventRegistrationStatus.CANCELLED -> "Abgesagt"
            EventRegistrationStatus.EXPIRED -> "Abgelaufen"
        }

    private fun paymentText(state: EventRegistrationPaymentState): String =
        when (state) {
            EventRegistrationPaymentState.FREE -> "Kostenlos"
            EventRegistrationPaymentState.OPEN -> "Offen"
            EventRegistrationPaymentState.PAID -> "Bezahlt"
            EventRegistrationPaymentState.REFUNDED -> "Erstattet"
            EventRegistrationPaymentState.UNPAID -> "Nicht bezahlt"
        }

    /** `12,50`, `1234,50` -- two decimals, comma, no thousands separator. */
    fun amountText(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    /** `dd.MM.yyyy HH:mm`. */
    fun formatWall(wall: LocalDateTime): String {
        val date = wall.date.toString().split('-')
        val day = if (date.size == 3) "${date[2]}.${date[1]}.${date[0]}" else wall.date.toString()
        return "$day %02d:%02d".format(wall.hour, wall.minute)
    }
}
