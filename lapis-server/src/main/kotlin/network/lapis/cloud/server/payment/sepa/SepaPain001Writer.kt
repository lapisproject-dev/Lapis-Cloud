package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.io.ByteArrayOutputStream
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Welle V1.9.123 "SEPA-Ueberweisungsdatei fuer genehmigte Reisekosten". Produces a `pain.001.001.09` SEPA credit-transfer
 * file (`CstmrCdtTrfInitn`, ONE `PmtInf`, `PmtMtd = TRF`, `SvcLvl = SEPA`). A pure, deterministic function over [Pain001Batch] --
 * no database, no clock, no state -- the same idiom as [SepaPain008Writer], and written through [XMLStreamWriter] only (it escapes
 * exactly once; no hand-built XML).
 *
 * **Version choice.** `pain.001.001.09` is the generation the German DK implementation guidelines use since November 2023 and the
 * same ISO-20022 generation as the `pain.008.001.08` this repository already writes (BIC element `BICFI`). `pain.001.001.03` is NOT
 * supported (different element names). A bank may still require its own profile -- see `docs/features/travel-expenses.adoc`.
 *
 * **Structural decisions that deviate from a naive reading of the message definition:**
 * - `DbtrAgt` is mandatory (1..1) in `.09`. Without a debtor BIC it is written as `FinInstnId/Othr/Id = NOTPROVIDED` (the same
 *   convention [SepaPain008Writer] uses for `CdtrAgt`). The creditor agent (`CdtrAgt`, 0..1) is always omitted -- members give no BIC.
 * - `MsgId`/`PmtInfId`/`EndToEndId` are `Max35Text`; the callers derive them with [messageIdOf]/[paymentInfoIdOf]/[endToEndIdOf],
 *   which are guaranteed to be <= 35 characters.
 * - Amounts are whole cents (`Long`) and formatted without floating point ([SepaXmlSupport.formatCents]); the control sum is formed
 *   with [Math.addExact] so an overflow throws instead of silently wrapping.
 *
 * **Privacy.** [validate] reports field names and indexes only, NEVER a value (an IBAN or a name must not surface in a log or an
 * exception message). [Pain001Batch.toString]/[Pain001Transaction.toString] are overridden to omit IBANs and names.
 */
object SepaPain001Writer {
    const val PAIN001_VERSION: String = "pain.001.001.09"
    const val NAMESPACE: String = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"
    const val MAX_TRANSACTIONS: Int = 500

    /** 999 999 999.99 EUR -- the SEPA instruction amount ceiling. */
    const val MAX_AMOUNT_CENTS: Long = 99_999_999_999L

    const val MAX_ID_LENGTH: Int = 35
    const val MAX_NAME_LENGTH: Int = 70
    const val MAX_REMITTANCE_LENGTH: Int = 140

    private const val HEX_CHARS_IN_ID = 30
    private const val MAX_REEXPORT_SUFFIX = 99

    /** `LCRT-` + 30 hex characters of the batch id (35 characters). */
    fun messageIdOf(batchId: String): String = "LCRT-" + hex(batchId).take(HEX_CHARS_IN_ID)

    /** `LCRTP` + 30 hex characters of the batch id (35 characters). */
    fun paymentInfoIdOf(batchId: String): String = "LCRTP" + hex(batchId).take(HEX_CHARS_IN_ID)

    /** `TE` + 28 hex characters of the report id (30), plus `-R<n>` (n in 1..99) for the n-th re-export (at most 34). */
    fun endToEndIdOf(
        reportId: String,
        reexportCount: Int,
    ): String {
        require(reexportCount in 0..MAX_REEXPORT_SUFFIX) { "reexportCount out of range" }
        val base = "TE" + hex(reportId).take(END_TO_END_HEX_CHARS)
        return if (reexportCount == 0) base else "$base-R$reexportCount"
    }

    private const val END_TO_END_HEX_CHARS = 28

    private fun hex(id: String): String = id.filter { it != '-' }.lowercase()

    /** @throws IllegalArgumentException on any field-rule violation (see [validate]); never with a value in the message. */
    fun write(batch: Pain001Batch): ByteArray {
        validate(batch)
        val out = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8")
        writer.writeStartDocument("UTF-8", "1.0")
        writeDocument(writer = writer, batch = batch)
        writer.writeEndDocument()
        writer.flush()
        writer.close()
        return out.toByteArray()
    }

    /** Every field rule, callable separately so the export can validate BEFORE it writes anything. */
    fun validate(batch: Pain001Batch) {
        require(batch.transactions.isNotEmpty()) { "transactions must not be empty" }
        require(batch.transactions.size <= MAX_TRANSACTIONS) { "transactions exceed the maximum of $MAX_TRANSACTIONS" }
        requireId(value = batch.messageId, field = "messageId")
        requireId(value = batch.paymentInfoId, field = "paymentInfoId")
        requireName(value = batch.initiatingPartyName, field = "initiatingPartyName")
        requireName(value = batch.debtorName, field = "debtorName")
        IbanValidator.requireValid(batch.debtorIban)
        batch.debtorBic?.let { require(BicValidator.isValid(it)) { "debtorBic is not a formally valid BIC" } }

        val endToEndIds = mutableSetOf<String>()
        var sum = 0L
        batch.transactions.forEachIndexed { index, tx ->
            requireId(value = tx.endToEndId, field = "transactions[$index].endToEndId")
            require(tx.amountCents in 1..MAX_AMOUNT_CENTS) { "transactions[$index].amountCents is outside 1..$MAX_AMOUNT_CENTS" }
            requireName(value = tx.creditorName, field = "transactions[$index].creditorName")
            IbanValidator.requireValid(tx.creditorIban)
            require(SepaCharacterSet.sanitize(raw = tx.remittanceInformation, maxLength = MAX_REMITTANCE_LENGTH).isNotEmpty()) {
                "transactions[$index].remittanceInformation must not be blank"
            }
            require(endToEndIds.add(tx.endToEndId)) { "transactions[$index].endToEndId is not unique within the batch" }
            sum = Math.addExact(sum, tx.amountCents)
        }
    }

    private fun requireId(
        value: String,
        field: String,
    ) {
        require(value.isNotEmpty() && value.length <= MAX_ID_LENGTH) { "$field must be 1..$MAX_ID_LENGTH characters" }
        require(SepaCharacterSet.isSepaSafe(value)) { "$field contains characters outside the SEPA character set" }
        require(
            !value.startsWith("/") && !value.endsWith("/") && !value.contains("//"),
        ) { "$field must not start/end with or contain '//'" }
    }

    private fun requireName(
        value: String,
        field: String,
    ) {
        require(SepaCharacterSet.sanitize(raw = value, maxLength = MAX_NAME_LENGTH).isNotEmpty()) { "$field must not be blank" }
    }

    private fun writeDocument(
        writer: XMLStreamWriter,
        batch: Pain001Batch,
    ) {
        val sumCents = batch.transactions.fold(0L) { acc, tx -> Math.addExact(acc, tx.amountCents) }
        val count = batch.transactions.size.toString()
        val sum = SepaXmlSupport.formatCents(sumCents)

        writer.writeStartElement("Document")
        writer.writeDefaultNamespace(NAMESPACE)
        writer.writeStartElement("CstmrCdtTrfInitn")

        writer.writeStartElement("GrpHdr")
        text(writer = writer, localName = "MsgId", value = batch.messageId)
        text(writer = writer, localName = "CreDtTm", value = SepaXmlSupport.formatDateTime(batch.creationDateTime))
        text(writer = writer, localName = "NbOfTxs", value = count)
        text(writer = writer, localName = "CtrlSum", value = sum)
        writer.writeStartElement("InitgPty")
        text(writer = writer, localName = "Nm", value = name(batch.initiatingPartyName))
        writer.writeEndElement() // InitgPty
        writer.writeEndElement() // GrpHdr

        writer.writeStartElement("PmtInf")
        text(writer = writer, localName = "PmtInfId", value = batch.paymentInfoId)
        text(writer = writer, localName = "PmtMtd", value = "TRF")
        text(writer = writer, localName = "BtchBookg", value = "true")
        text(writer = writer, localName = "NbOfTxs", value = count)
        text(writer = writer, localName = "CtrlSum", value = sum)
        writer.writeStartElement("PmtTpInf")
        writer.writeStartElement("SvcLvl")
        text(writer = writer, localName = "Cd", value = "SEPA")
        writer.writeEndElement() // SvcLvl
        writer.writeEndElement() // PmtTpInf
        writer.writeStartElement("ReqdExctnDt")
        text(writer = writer, localName = "Dt", value = batch.requestedExecutionDate.toString())
        writer.writeEndElement() // ReqdExctnDt
        writer.writeStartElement("Dbtr")
        text(writer = writer, localName = "Nm", value = name(batch.debtorName))
        writer.writeEndElement() // Dbtr
        writer.writeStartElement("DbtrAcct")
        writer.writeStartElement("Id")
        text(writer = writer, localName = "IBAN", value = IbanValidator.normalize(batch.debtorIban))
        writer.writeEndElement() // Id
        writer.writeEndElement() // DbtrAcct
        writer.writeStartElement("DbtrAgt")
        writer.writeStartElement("FinInstnId")
        if (batch.debtorBic != null) {
            text(writer = writer, localName = "BICFI", value = batch.debtorBic)
        } else {
            writer.writeStartElement("Othr")
            text(writer = writer, localName = "Id", value = SepaXmlSupport.BIC_NOT_PROVIDED)
            writer.writeEndElement() // Othr
        }
        writer.writeEndElement() // FinInstnId
        writer.writeEndElement() // DbtrAgt
        text(writer = writer, localName = "ChrgBr", value = "SLEV")

        batch.transactions.forEach { tx -> writeTransaction(writer = writer, tx = tx) }

        writer.writeEndElement() // PmtInf
        writer.writeEndElement() // CstmrCdtTrfInitn
        writer.writeEndElement() // Document
    }

    private fun writeTransaction(
        writer: XMLStreamWriter,
        tx: Pain001Transaction,
    ) {
        writer.writeStartElement("CdtTrfTxInf")
        writer.writeStartElement("PmtId")
        text(writer = writer, localName = "EndToEndId", value = tx.endToEndId)
        writer.writeEndElement() // PmtId
        writer.writeStartElement("Amt")
        writer.writeStartElement("InstdAmt")
        writer.writeAttribute("Ccy", "EUR")
        writer.writeCharacters(SepaXmlSupport.formatCents(tx.amountCents))
        writer.writeEndElement() // InstdAmt
        writer.writeEndElement() // Amt
        writer.writeStartElement("Cdtr")
        text(writer = writer, localName = "Nm", value = name(tx.creditorName))
        writer.writeEndElement() // Cdtr
        writer.writeStartElement("CdtrAcct")
        writer.writeStartElement("Id")
        text(writer = writer, localName = "IBAN", value = IbanValidator.normalize(tx.creditorIban))
        writer.writeEndElement() // Id
        writer.writeEndElement() // CdtrAcct
        writer.writeStartElement("RmtInf")
        text(
            writer = writer,
            localName = "Ustrd",
            value = SepaCharacterSet.sanitize(raw = tx.remittanceInformation, maxLength = MAX_REMITTANCE_LENGTH),
        )
        writer.writeEndElement() // RmtInf
        writer.writeEndElement() // CdtTrfTxInf
    }

    private fun name(raw: String): String = SepaCharacterSet.sanitize(raw = raw, maxLength = MAX_NAME_LENGTH)

    private fun text(
        writer: XMLStreamWriter,
        localName: String,
        value: String,
    ) = SepaXmlSupport.writeTextElement(writer = writer, localName = localName, text = value)
}

/**
 * [creationDateTime] is informative (`GrpHdr/CreDtTm`, `xs:dateTime` without a zone); the caller passes the organization wall clock
 * (`OrganizationTimeZone.wallNow()`), exactly as for pain.008 -- the writer itself never reads a clock.
 */
data class Pain001Batch(
    val messageId: String,
    val paymentInfoId: String,
    val creationDateTime: LocalDateTime,
    val initiatingPartyName: String,
    val debtorName: String,
    val debtorIban: String,
    val debtorBic: String?,
    val requestedExecutionDate: LocalDate,
    val transactions: List<Pain001Transaction>,
) {
    /** No IBAN and no name -- a batch must be safe to log by accident. */
    override fun toString(): String = "Pain001Batch(msgId=$messageId, n=${transactions.size})"
}

data class Pain001Transaction(
    val endToEndId: String,
    val amountCents: Long,
    val creditorName: String,
    val creditorIban: String,
    val remittanceInformation: String,
) {
    /** No IBAN and no name. */
    override fun toString(): String = "Pain001Transaction(e2e=$endToEndId, cents=$amountCents)"
}
