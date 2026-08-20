package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaSequenceType
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.Locale
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/** `NOTPROVIDED` placeholder used for [SepaBatchItemSpec.debtorBic]/`SepaBatchSpec.creditorBic` == `null` -- see M-3 KDoc below. */
private const val BIC_NOT_PROVIDED = "NOTPROVIDED"

/**
 * V1.2.2 "SEPA-Lastschriftmandate". Produces a pain.008 SEPA-Basislastschrift (`CORE`) collective
 * direct-debit file. A pure function over [SepaBatchSpec] -- no database, no state -- so it is
 * independently testable against the official XSD (`SepaPain008WriterTest`), same "pure logic in a
 * sibling file" idiom as `JournalEntryBalance`/`PartyDonationComplianceCalculator`.
 *
 * **Writes via [javax.xml.stream.XMLStreamWriter], NEVER via string concatenation.** The stream
 * writer escapes exactly once. This is deliberately BETTER than this codebase's only pre-existing XML
 * write path (`SocialPublicSitemap.kt` hand-rolls escaping over a `StringBuilder`) -- escaping
 * belongs in exactly one layer, never two. NO new dependency -- `javax.xml.stream` is JDK-native.
 *
 * **Structurally, exactly `pain.008.001.08` is emitted.** The namespace URI is configurable
 * ([SepaConfig.pain008Version]), but only within [STRUCTURALLY_COMPATIBLE_VERSIONS] -- not a
 * convenience, but a necessity: between `.02` and `.08` the BIC element is named `BIC` vs. `BICFI`, so
 * a bare namespace swap would produce a file that is invalid against its own declared schema.
 * `pain.008.001.02` is therefore REJECTED with a message naming exactly this reason, rather than
 * silently writing a broken file.
 *
 * **For human review before production use** (same discipline as
 * `LetterxpressPostalMailProvider`'s "wire format not verified" disclosure): the exact sub-version and
 * bank-specific application subset differ between institutions. The organization's actual house bank
 * must confirm its own specification/test tool before the first real file is submitted.
 */
object SepaPain008Writer {
    const val DEFAULT_VERSION: String = "pain.008.001.08"

    /**
     * Versions whose element structure is identical to the one emitted here. `.02` is deliberately
     * NOT included (BIC vs. BICFI, see class KDoc). Only extend after checking against that version's
     * own XSD -- an entry here is a claim about schema compatibility.
     */
    val STRUCTURALLY_COMPATIBLE_VERSIONS: Set<String> = setOf("pain.008.001.08")

    private const val MAX_MESSAGE_ID_LENGTH = 35
    private const val MAX_NAME_LENGTH = 70
    private const val MAX_REMITTANCE_LENGTH = 140

    /** @throws IllegalArgumentException on any field-rule violation (see [validate]). */
    fun write(spec: SepaBatchSpec): ByteArray {
        validate(spec)
        val out = ByteArrayOutputStream()
        val factory = XMLOutputFactory.newInstance()
        val writer = factory.createXMLStreamWriter(out, "UTF-8")
        writer.writeStartDocument("UTF-8", "1.0")
        writeDocument(writer = writer, spec = spec)
        writer.writeEndDocument()
        writer.flush()
        writer.close()
        return out.toByteArray()
    }

    /** Every field rule, callable separately so the service can validate BEFORE writing. */
    fun validate(spec: SepaBatchSpec) {
        require(spec.version in STRUCTURALLY_COMPATIBLE_VERSIONS) {
            "pain.008 version '${spec.version}' is not structurally compatible with this writer " +
                "(element name differs, e.g. BIC vs. BICFI) -- only ${STRUCTURALLY_COMPATIBLE_VERSIONS} are supported"
        }
        require(spec.items.isNotEmpty()) { "SepaBatchSpec.items must not be empty" }
        require(spec.messageId.length <= MAX_MESSAGE_ID_LENGTH) { "messageId exceeds $MAX_MESSAGE_ID_LENGTH characters" }
        require(spec.paymentInfoId.length <= MAX_MESSAGE_ID_LENGTH) { "paymentInfoId exceeds $MAX_MESSAGE_ID_LENGTH characters" }
        require(spec.initiatingPartyName.length <= MAX_NAME_LENGTH) { "initiatingPartyName exceeds $MAX_NAME_LENGTH characters" }
        require(spec.creditorName.length <= MAX_NAME_LENGTH) { "creditorName exceeds $MAX_NAME_LENGTH characters" }
        require(spec.creditorSchemeId.length <= MAX_MESSAGE_ID_LENGTH) { "creditorSchemeId exceeds $MAX_MESSAGE_ID_LENGTH characters" }
        IbanValidator.requireValid(spec.creditorIban)

        val endToEndIds = mutableSetOf<String>()
        spec.items.forEach { item ->
            require(item.endToEndId.length <= MAX_MESSAGE_ID_LENGTH) { "endToEndId exceeds $MAX_MESSAGE_ID_LENGTH characters" }
            require(item.mandateReference.length <= MAX_MESSAGE_ID_LENGTH) { "mandateReference exceeds $MAX_MESSAGE_ID_LENGTH characters" }
            require(item.debtorName.length <= MAX_NAME_LENGTH) { "debtorName exceeds $MAX_NAME_LENGTH characters" }
            require(
                item.remittanceInformation.length <= MAX_REMITTANCE_LENGTH,
            ) { "remittanceInformation exceeds $MAX_REMITTANCE_LENGTH characters" }
            require(item.amount.signum() > 0) { "item amount must be positive, was ${item.amount}" }
            require(item.amount.scale() <= 2) { "item amount must have at most 2 decimal places, was ${item.amount}" }
            IbanValidator.requireValid(item.debtorIban)
            require(endToEndIds.add(item.endToEndId)) { "duplicate endToEndId '${item.endToEndId}' within the same batch" }
        }
    }

    private fun writeDocument(
        writer: XMLStreamWriter,
        spec: SepaBatchSpec,
    ) {
        writer.writeStartElement("Document")
        writer.writeDefaultNamespace("urn:iso:std:iso:20022:tech:xsd:${spec.version}")

        writer.writeStartElement("CstmrDrctDbtInitn")
        writeGroupHeader(writer = writer, spec = spec)
        writePaymentInfo(writer = writer, spec = spec)
        writer.writeEndElement() // CstmrDrctDbtInitn

        writer.writeEndElement() // Document
    }

    private fun writeGroupHeader(
        writer: XMLStreamWriter,
        spec: SepaBatchSpec,
    ) {
        val ctrlSum = totalAmount(spec)
        writer.writeStartElement("GrpHdr")
        writeTextElement(writer = writer, localName = "MsgId", text = spec.messageId)
        writeTextElement(writer = writer, localName = "CreDtTm", text = formatDateTime(spec.creationDateTime))
        writeTextElement(writer = writer, localName = "NbOfTxs", text = spec.items.size.toString())
        writeTextElement(writer = writer, localName = "CtrlSum", text = formatAmount(ctrlSum))
        writer.writeStartElement("InitgPty")
        writeTextElement(
            writer = writer,
            localName = "Nm",
            text = SepaCharacterSet.sanitize(raw = spec.initiatingPartyName, maxLength = MAX_NAME_LENGTH),
        )
        writer.writeEndElement() // InitgPty
        writer.writeEndElement() // GrpHdr
    }

    private fun writePaymentInfo(
        writer: XMLStreamWriter,
        spec: SepaBatchSpec,
    ) {
        val ctrlSum = totalAmount(spec)
        writer.writeStartElement("PmtInf")
        writeTextElement(writer = writer, localName = "PmtInfId", text = spec.paymentInfoId)
        writeTextElement(writer = writer, localName = "PmtMtd", text = "DD")
        writeTextElement(writer = writer, localName = "BtchBookg", text = "true")
        writeTextElement(writer = writer, localName = "NbOfTxs", text = spec.items.size.toString())
        writeTextElement(writer = writer, localName = "CtrlSum", text = formatAmount(ctrlSum))

        writer.writeStartElement("PmtTpInf")
        writer.writeStartElement("SvcLvl")
        writeTextElement(writer = writer, localName = "Cd", text = "SEPA")
        writer.writeEndElement() // SvcLvl
        writer.writeStartElement("LclInstrm")
        writeTextElement(writer = writer, localName = "Cd", text = "CORE")
        writer.writeEndElement() // LclInstrm
        writeTextElement(writer = writer, localName = "SeqTp", text = spec.sequenceType.name)
        writer.writeEndElement() // PmtTpInf

        writeTextElement(writer = writer, localName = "ReqdColltnDt", text = spec.requestedCollectionDate.toString())

        writer.writeStartElement("Cdtr")
        writeTextElement(
            writer = writer,
            localName = "Nm",
            text = SepaCharacterSet.sanitize(raw = spec.creditorName, maxLength = MAX_NAME_LENGTH),
        )
        writer.writeEndElement() // Cdtr

        writer.writeStartElement("CdtrAcct")
        writer.writeStartElement("Id")
        writeTextElement(writer = writer, localName = "IBAN", text = IbanValidator.normalize(spec.creditorIban))
        writer.writeEndElement() // Id
        writer.writeEndElement() // CdtrAcct

        // M-3 (Review Round 1, 2026-08-19, MAJOR): CdtrAgt is a mandatory element of the
        // pain.008.001.08 message definition (PaymentInstruction29) -- it must never be omitted, even
        // though German domestic practice does not require a real BIC (IBAN-only). When creditorBic
        // is null, the NOTPROVIDED placeholder pattern is emitted instead of skipping the element
        // entirely. See class KDoc "For human review before production use" -- this convention should
        // be confirmed against the organization's actual house bank before the first real file ships.
        writer.writeStartElement("CdtrAgt")
        writer.writeStartElement("FinInstnId")
        if (spec.creditorBic != null) {
            writeTextElement(writer = writer, localName = "BICFI", text = spec.creditorBic)
        } else {
            writer.writeStartElement("Othr")
            writeTextElement(writer = writer, localName = "Id", text = BIC_NOT_PROVIDED)
            writer.writeEndElement() // Othr
        }
        writer.writeEndElement() // FinInstnId
        writer.writeEndElement() // CdtrAgt

        writeTextElement(writer = writer, localName = "ChrgBr", text = "SLEV")

        writer.writeStartElement("CdtrSchmeId")
        writer.writeStartElement("Id")
        writer.writeStartElement("PrvtId")
        writer.writeStartElement("Othr")
        writeTextElement(writer = writer, localName = "Id", text = spec.creditorSchemeId)
        writer.writeStartElement("SchmeNm")
        writeTextElement(writer = writer, localName = "Prtry", text = "SEPA")
        writer.writeEndElement() // SchmeNm
        writer.writeEndElement() // Othr
        writer.writeEndElement() // PrvtId
        writer.writeEndElement() // Id
        writer.writeEndElement() // CdtrSchmeId

        spec.items.forEach { item -> writeDirectDebitTransaction(writer = writer, item = item) }

        writer.writeEndElement() // PmtInf
    }

    private fun writeDirectDebitTransaction(
        writer: XMLStreamWriter,
        item: SepaBatchItemSpec,
    ) {
        writer.writeStartElement("DrctDbtTxInf")

        writer.writeStartElement("PmtId")
        writeTextElement(writer = writer, localName = "EndToEndId", text = item.endToEndId)
        writer.writeEndElement() // PmtId

        writer.writeStartElement("InstdAmt")
        writer.writeAttribute("Ccy", "EUR")
        writer.writeCharacters(formatAmount(item.amount))
        writer.writeEndElement() // InstdAmt

        writer.writeStartElement("DrctDbtTx")
        writer.writeStartElement("MndtRltdInf")
        writeTextElement(writer = writer, localName = "MndtId", text = item.mandateReference)
        writeTextElement(writer = writer, localName = "DtOfSgntr", text = item.mandateSignatureDate.toString())
        writeTextElement(writer = writer, localName = "AmdmntInd", text = "false")
        writer.writeEndElement() // MndtRltdInf
        writer.writeEndElement() // DrctDbtTx

        // M-3 -- DbtrAgt (DirectDebitTransactionInformation23) is likewise mandatory; see writePaymentInfo's CdtrAgt for the full rationale.
        writer.writeStartElement("DbtrAgt")
        writer.writeStartElement("FinInstnId")
        if (item.debtorBic != null) {
            writeTextElement(writer = writer, localName = "BICFI", text = item.debtorBic)
        } else {
            writer.writeStartElement("Othr")
            writeTextElement(writer = writer, localName = "Id", text = BIC_NOT_PROVIDED)
            writer.writeEndElement() // Othr
        }
        writer.writeEndElement() // FinInstnId
        writer.writeEndElement() // DbtrAgt

        writer.writeStartElement("Dbtr")
        writeTextElement(
            writer = writer,
            localName = "Nm",
            text = SepaCharacterSet.sanitize(raw = item.debtorName, maxLength = MAX_NAME_LENGTH),
        )
        writer.writeEndElement() // Dbtr

        writer.writeStartElement("DbtrAcct")
        writer.writeStartElement("Id")
        writeTextElement(writer = writer, localName = "IBAN", text = IbanValidator.normalize(item.debtorIban))
        writer.writeEndElement() // Id
        writer.writeEndElement() // DbtrAcct

        writer.writeStartElement("RmtInf")
        writeTextElement(
            writer = writer,
            localName = "Ustrd",
            text = SepaCharacterSet.sanitize(raw = item.remittanceInformation, maxLength = MAX_REMITTANCE_LENGTH),
        )
        writer.writeEndElement() // RmtInf

        writer.writeEndElement() // DrctDbtTxInf
    }

    private fun writeTextElement(
        writer: XMLStreamWriter,
        localName: String,
        text: String,
    ) {
        writer.writeStartElement(localName)
        writer.writeCharacters(text)
        writer.writeEndElement()
    }

    /** Formed from the SAME BigDecimal values as the items, never from a separately maintained figure. */
    private fun totalAmount(spec: SepaBatchSpec): BigDecimal = spec.items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount }

    /** `setScale(2).toPlainString()` -- never `String.format`/locale-dependent (`60,00` instead of `60.00` would break the file). */
    private fun formatAmount(amount: BigDecimal): String = amount.setScale(2).toPlainString()

    /**
     * M-2 (Review Round 1, 2026-08-19, MAJOR): `LocalDateTime.toString()` has two format defects for
     * an `xs:dateTime` (`GrpHdr/CreDtTm`): it DROPS seconds entirely when `second == 0 && nano == 0`
     * (`2026-08-19T14:22` is not a valid `xs:dateTime`), and it INCLUDES fractional/microsecond
     * seconds otherwise (`2026-08-19T14:22:05.123456`) -- the DK/EPC implementation guideline
     * specifies exactly `YYYY-MM-DDThh:mm:ss`, no fractional seconds, and many bank validators reject
     * either deviation. Formatted manually here rather than via a fractional-second-stripping regex
     * over the stdlib output, so both defects are fixed at their source in one place.
     *
     * Review Round 2 (2026-08-20, N-3, MINOR): `Locale.ROOT` added explicitly, same reasoning
     * [formatAmount] above already documents for BigDecimal -- without it, `%d` renders under the
     * JVM's default locale, which under certain locale configurations (e.g. Arabic-Indic digit
     * extensions) can produce non-ASCII digits in a bank-facing XML file.
     */
    private fun formatDateTime(dateTime: LocalDateTime): String =
        "%04d-%02d-%02dT%02d:%02d:%02d".format(
            Locale.ROOT,
            dateTime.year,
            dateTime.monthNumber,
            dateTime.dayOfMonth,
            dateTime.hour,
            dateTime.minute,
            dateTime.second,
        )
}

/**
 * [creationDateTime] is emitted as ISO-8601 without a zone (`GrpHdr/CreDtTm`) -- the container must
 * run on Europe/Berlin, see the operator notes.
 */
data class SepaBatchSpec(
    val version: String,
    val messageId: String,
    val creationDateTime: LocalDateTime,
    val initiatingPartyName: String,
    val paymentInfoId: String,
    val sequenceType: SepaSequenceType,
    val requestedCollectionDate: LocalDate,
    val creditorName: String,
    val creditorIban: String,
    val creditorBic: String?,
    val creditorSchemeId: String,
    val items: List<SepaBatchItemSpec>,
)

data class SepaBatchItemSpec(
    val endToEndId: String,
    val amount: BigDecimal,
    val mandateReference: String,
    val mandateSignatureDate: LocalDate,
    val debtorName: String,
    val debtorIban: String,
    val debtorBic: String?,
    val remittanceInformation: String,
)
