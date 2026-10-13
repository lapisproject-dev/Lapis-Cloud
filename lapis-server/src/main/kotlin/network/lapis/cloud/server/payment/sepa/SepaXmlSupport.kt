package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import java.math.BigDecimal
import java.util.Locale
import javax.xml.stream.XMLStreamWriter

/**
 * Welle V1.9.123 -- the pieces `SepaPain008Writer` (direct debit) and `SepaPain001Writer` (credit transfer) share. Extracted
 * from pain.008 without any change of behaviour (`SepaPain008WriterTest` and its golden output are unchanged).
 *
 * Everything is written through [XMLStreamWriter], which escapes exactly once -- never concatenate XML by hand.
 */
internal object SepaXmlSupport {
    /** `NOTPROVIDED` placeholder for a mandatory agent element when no BIC is known (IBAN-only domestic practice). */
    const val BIC_NOT_PROVIDED: String = "NOTPROVIDED"

    fun writeTextElement(
        writer: XMLStreamWriter,
        localName: String,
        text: String,
    ) {
        writer.writeStartElement(localName)
        writer.writeCharacters(text)
        writer.writeEndElement()
    }

    /** `setScale(2).toPlainString()` -- never `String.format`/locale-dependent (`60,00` instead of `60.00` would break the file). */
    fun formatAmount(amount: BigDecimal): String = amount.setScale(2).toPlainString()

    /** Whole cents to `"<euros>.<2 digits>"` without any floating point or locale dependence. [cents] must not be negative. */
    fun formatCents(cents: Long): String {
        require(cents >= 0) { "cents must not be negative" }
        val fraction = cents % 100
        return "${cents / 100}.${if (fraction < 10) "0" else ""}$fraction"
    }

    /**
     * `xs:dateTime` exactly as `YYYY-MM-DDThh:mm:ss` (no fractional seconds, seconds never dropped). `LocalDateTime.toString()` has
     * both defects (M-2, review round 1 of V1.2.2); `Locale.ROOT` keeps the digits ASCII under any JVM locale (N-3).
     */
    fun formatDateTime(dateTime: LocalDateTime): String =
        "%04d-%02d-%02dT%02d:%02d:%02d".format(
            Locale.ROOT,
            dateTime.year,
            dateTime.month.number,
            dateTime.day,
            dateTime.hour,
            dateTime.minute,
            dateTime.second,
        )
}
