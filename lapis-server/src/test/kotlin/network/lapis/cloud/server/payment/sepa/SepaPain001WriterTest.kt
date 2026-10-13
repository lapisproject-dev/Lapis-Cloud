package network.lapis.cloud.server.payment.sepa

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.validation.SchemaFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private const val BATCH_UUID = "01234567-89ab-cdef-0123-456789abcdef"
private const val REPORT_UUID_1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
private const val REPORT_UUID_2 = "11111111-2222-3333-4444-555555555555"
private const val REPORT_UUID_3 = "99999999-8888-7777-6666-555555555555"

/**
 * Pure tests of [SepaPain001Writer] -- no database. Validation is layered:
 * 1. a byte-exact golden file (`sepa/pain001-golden.xml`, reviewed by hand against the `pain.001.001.09` element order),
 * 2. a hand-written structure checker ([structureOf]) that pins the exact child order of every complex element of the schema,
 * 3. an OPTIONAL validation against the official ISO 20022 XSD, run only when `LAPIS_TEST_PAIN001_XSD` points at a locally stored
 *    copy -- the XSD is deliberately NOT checked in (licence not reviewed), same situation as `SepaPain008WriterTest`.
 *
 * To regenerate the golden file after an intended change: run with `UPDATE_PAIN001_GOLDEN=1`, then REVIEW the diff.
 */
class SepaPain001WriterTest :
    FunSpec({
        val ns =
            object : NamespaceContext {
                override fun getNamespaceURI(prefix: String?): String = if (prefix == "p") SepaPain001Writer.NAMESPACE else ""

                override fun getPrefix(namespaceURI: String?): String? = null

                override fun getPrefixes(namespaceURI: String?): Iterator<String>? = null
            }

        fun parse(bytes: ByteArray): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            return factory.newDocumentBuilder().parse(bytes.inputStream())
        }

        fun xpath(
            document: Document,
            expression: String,
        ): String {
            val xp = XPathFactory.newInstance().newXPath()
            xp.namespaceContext = ns
            return xp.evaluate(expression, document, XPathConstants.STRING) as String
        }

        fun tx(
            report: String = REPORT_UUID_1,
            cents: Long = 1250,
            name: String = "Müller, Jörg",
            iban: String = MEMBER_IBAN_A,
            remittance: String = "Reisekosten RK-AAAAAAAA vom 05.10.2026",
            reexport: Int = 0,
        ) = Pain001Transaction(
            endToEndId = SepaPain001Writer.endToEndIdOf(reportId = report, reexportCount = reexport),
            amountCents = cents,
            creditorName = name,
            creditorIban = iban,
            remittanceInformation = remittance,
        )

        fun batch(
            transactions: List<Pain001Transaction> = listOf(tx()),
            debtorBic: String? = "COBADEFFXXX",
            debtorName: String = "Partei der Vernunft",
        ) = Pain001Batch(
            messageId = SepaPain001Writer.messageIdOf(BATCH_UUID),
            paymentInfoId = SepaPain001Writer.paymentInfoIdOf(BATCH_UUID),
            creationDateTime = LocalDateTime(2026, 10, 13, 9, 5, 0),
            initiatingPartyName = "Partei der Vernunft",
            debtorName = debtorName,
            debtorIban = "DE89370400440532013000",
            debtorBic = debtorBic,
            requestedExecutionDate = LocalDate(2026, 10, 14),
            transactions = transactions,
        )

        fun goldenBatch() =
            batch(
                transactions =
                    listOf(
                        tx(report = REPORT_UUID_1, cents = 1250, name = "Müller, Jörg", iban = MEMBER_IBAN_A),
                        tx(
                            report = REPORT_UUID_2,
                            cents = 5,
                            name = "Zoë O'Neil",
                            iban = MEMBER_IBAN_B,
                            remittance = "Reisekosten RK-11111111 vom 01.10.2026",
                        ),
                        tx(report = REPORT_UUID_3, cents = 99_999_999_999, name = "Jean-Luc Picard", iban = MEMBER_IBAN_A, reexport = 2),
                    ),
            )

        /** Local-name child order of an element, ignoring text nodes. */
        fun structureOf(element: Element): List<String> =
            (0 until element.childNodes.length)
                .map { element.childNodes.item(it) }
                .filter { it.nodeType == Node.ELEMENT_NODE }
                .map { it.localName }

        fun child(
            element: Element,
            name: String,
        ): Element = (0 until element.childNodes.length).map { element.childNodes.item(it) }.first { it.localName == name } as Element

        test("generated ids stay within Max35Text") {
            SepaPain001Writer.messageIdOf(BATCH_UUID).length shouldBe 35
            SepaPain001Writer.paymentInfoIdOf(BATCH_UUID).length shouldBe 35
            SepaPain001Writer.endToEndIdOf(reportId = REPORT_UUID_1, reexportCount = 0).length shouldBe 30
            SepaPain001Writer.endToEndIdOf(reportId = REPORT_UUID_1, reexportCount = 99).length shouldBe 34
        }

        test("golden file: the output of a three-transaction batch is byte-identical") {
            val bytes = SepaPain001Writer.write(goldenBatch())
            val golden = File("src/test/resources/sepa/pain001-golden.xml")
            if (System.getenv("UPDATE_PAIN001_GOLDEN") == "1") golden.writeBytes(bytes)
            String(bytes, Charsets.UTF_8) shouldBe golden.readText(Charsets.UTF_8)
        }

        test("the element order of every complex element follows pain.001.001.09") {
            val document = parse(SepaPain001Writer.write(goldenBatch()))
            val root = document.documentElement
            root.localName shouldBe "Document"
            root.namespaceURI shouldBe "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"
            structureOf(root) shouldBe listOf("CstmrCdtTrfInitn")
            val init = child(root, "CstmrCdtTrfInitn")
            structureOf(init) shouldBe listOf("GrpHdr", "PmtInf")
            structureOf(child(init, "GrpHdr")) shouldBe listOf("MsgId", "CreDtTm", "NbOfTxs", "CtrlSum", "InitgPty")
            val pmtInf = child(init, "PmtInf")
            structureOf(pmtInf) shouldBe
                listOf(
                    "PmtInfId",
                    "PmtMtd",
                    "BtchBookg",
                    "NbOfTxs",
                    "CtrlSum",
                    "PmtTpInf",
                    "ReqdExctnDt",
                    "Dbtr",
                    "DbtrAcct",
                    "DbtrAgt",
                    "ChrgBr",
                    "CdtTrfTxInf",
                    "CdtTrfTxInf",
                    "CdtTrfTxInf",
                )
            structureOf(child(pmtInf, "ReqdExctnDt")) shouldBe listOf("Dt")
            structureOf(child(child(pmtInf, "PmtTpInf"), "SvcLvl")) shouldBe listOf("Cd")
            structureOf(child(child(pmtInf, "DbtrAgt"), "FinInstnId")) shouldBe listOf("BICFI")
            val first = child(pmtInf, "CdtTrfTxInf")
            structureOf(first) shouldBe listOf("PmtId", "Amt", "Cdtr", "CdtrAcct", "RmtInf")
            structureOf(child(first, "Amt")) shouldBe listOf("InstdAmt")
            child(child(first, "Amt"), "InstdAmt").getAttribute("Ccy") shouldBe "EUR"
            // The creditor agent is never written (members give no BIC).
            xpath(document, "count(//p:CdtrAgt)") shouldBe "0"
        }

        test("amounts are exact: 1 cent, 100 cents and the maximum, the control sum is the exact sum") {
            val document =
                parse(
                    SepaPain001Writer.write(
                        batch(
                            listOf(
                                tx(report = REPORT_UUID_1, cents = 1),
                                tx(report = REPORT_UUID_2, cents = 100),
                                tx(report = REPORT_UUID_3, cents = SepaPain001Writer.MAX_AMOUNT_CENTS),
                            ),
                        ),
                    ),
                )
            xpath(document, "(//p:InstdAmt)[1]") shouldBe "0.01"
            xpath(document, "(//p:InstdAmt)[2]") shouldBe "1.00"
            xpath(document, "(//p:InstdAmt)[3]") shouldBe "999999999.99"
            xpath(document, "//p:GrpHdr/p:CtrlSum") shouldBe "1000000001.00"
            xpath(document, "//p:PmtInf/p:CtrlSum") shouldBe "1000000001.00"
            xpath(document, "//p:GrpHdr/p:NbOfTxs") shouldBe "3"
            xpath(document, "//p:PmtInf/p:NbOfTxs") shouldBe "3"
        }

        test("a missing debtor BIC becomes the mandatory DbtrAgt with NOTPROVIDED") {
            val document = parse(SepaPain001Writer.write(batch(debtorBic = null)))
            xpath(document, "//p:DbtrAgt/p:FinInstnId/p:Othr/p:Id") shouldBe "NOTPROVIDED"
            xpath(document, "count(//p:DbtrAgt/p:FinInstnId/p:BICFI)") shouldBe "0"
        }

        test("CreDtTm never loses its seconds and has no fraction") {
            val document = parse(SepaPain001Writer.write(batch()))
            xpath(document, "//p:GrpHdr/p:CreDtTm") shouldBe "2026-10-13T09:05:00"
        }

        test("text is transliterated to the SEPA character set and escaped exactly once") {
            val name = "Müller & Söhne <GmbH> \"Alt\" ß é"
            val document = parse(SepaPain001Writer.write(batch(listOf(tx(name = name)), debtorName = "A&B <Verein>")))
            xpath(document, "//p:Cdtr/p:Nm") shouldBe "Mueller . Soehne .GmbH. .Alt. ss e"
            xpath(document, "//p:Dbtr/p:Nm") shouldBe "A.B .Verein."
            val apostrophe = parse(SepaPain001Writer.write(batch(listOf(tx(name = "Zoë O'Neil"))))) // the apostrophe is allowed
            xpath(apostrophe, "//p:Cdtr/p:Nm") shouldBe "Zoe O'Neil"
            // No injected element: angle brackets never reach the output unescaped.
            val raw = String(SepaPain001Writer.write(batch(listOf(tx(remittance = "</Ustrd><Evil/>")))), Charsets.UTF_8)
            raw shouldNotContain "<Evil"
        }

        test("long names and remittance text are truncated, never rejected") {
            val document =
                parse(SepaPain001Writer.write(batch(listOf(tx(name = "N".repeat(200), remittance = "R".repeat(400))))))
            xpath(document, "//p:Cdtr/p:Nm").length shouldBe 70
            xpath(document, "//p:RmtInf/p:Ustrd").length shouldBe 140
        }

        test("rule violations are refused and the message never contains a value") {
            fun refused(b: Pain001Batch): String = shouldThrow<IllegalArgumentException> { SepaPain001Writer.write(b) }.message.orEmpty()

            refused(batch(emptyList())) shouldBe "transactions must not be empty"
            refused(batch(List(501) { index -> tx(report = "%08x-0000-0000-0000-000000000000".format(index)) }))
                .startsWith("transactions exceed") shouldBe true
            refused(batch(listOf(tx(cents = 0)))).startsWith("transactions[0].amountCents") shouldBe true
            refused(batch(listOf(tx(cents = -5)))).startsWith("transactions[0].amountCents") shouldBe true
            refused(batch(listOf(tx(cents = SepaPain001Writer.MAX_AMOUNT_CENTS + 1)))).startsWith("transactions[0].amountCents") shouldBe
                true
            // Checksum failure, non-SEPA country (US, BR), GB (not in the SEPA set here).
            val badIbans =
                listOf("DE89370400440532013001", "US64SVBKUS6S3300958879", "BR1800360305000010009795493C1", "GB82WEST12345698765432")
            badIbans.forEach { bad ->
                val message = refused(batch(listOf(tx(iban = bad))))
                message shouldNotContain bad
            }
            refused(batch(listOf(tx(report = REPORT_UUID_1), tx(report = REPORT_UUID_1)))) shouldBe
                "transactions[1].endToEndId is not unique within the batch"
            refused(batch(listOf(tx(name = "   ")))) shouldBe "transactions[0].creditorName must not be blank"
            refused(batch(listOf(tx(remittance = " ")))) shouldBe "transactions[0].remittanceInformation must not be blank"
            refused(batch(debtorBic = "NOPE")) shouldBe "debtorBic is not a formally valid BIC"
        }

        test("the overflow of the control sum is an exception, not a wrapped number") {
            val many = List(2) { index -> tx(report = if (index == 0) REPORT_UUID_1 else REPORT_UUID_2, cents = Long.MAX_VALUE / 2 + 1) }
            // The per-transaction maximum already refuses this; the addExact in the sum is the second line of defence.
            shouldThrow<IllegalArgumentException> { SepaPain001Writer.write(batch(many)) }
        }

        test("toString of the batch and of a transaction carries no IBAN and no name") {
            val b = goldenBatch()
            val text = b.toString() + b.transactions.joinToString { it.toString() }
            listOf("DE89", "AT61", "DE02", "Mueller", "Müller", "Partei", "Picard").forEach { text shouldNotContain it }
        }

        test("optional: validation against the official XSD when LAPIS_TEST_PAIN001_XSD is set") {
            val path = System.getenv("LAPIS_TEST_PAIN001_XSD")
            if (path.isNullOrBlank()) return@test
            val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(File(path))
            schema.newValidator().validate(
                javax.xml.transform.stream
                    .StreamSource(SepaPain001Writer.write(goldenBatch()).inputStream()),
            )
        }
    })

private const val MEMBER_IBAN_A = "DE02120300000000202051"
private const val MEMBER_IBAN_B = "AT611904300234573201"
