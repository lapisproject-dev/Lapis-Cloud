package network.lapis.cloud.server.payment.sepa

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaSequenceType
import org.w3c.dom.Document
import java.math.BigDecimal
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Pure tests of [SepaPain008Writer] -- no DB access anywhere in this file.
 *
 * **Known limitation (plan sepa_v1.2.2_plan.md § 13.2 fallback clause)**: the official
 * `pain.008.001.08` XSD could not be sourced and license-cleared within this implementation session
 * (would require a live fetch from iso20022.org or the Deutsche Kreditwirtschaft's DFÜ-Abkommen
 * Anlage 3, plus a license review before checking a third-party schema file into this repository).
 * Per the plan's own explicit fallback ("den Test NICHT weglassen, sondern ... stattdessen einen
 * strukturellen Test schreiben"), this suite validates the emitted file structurally instead
 * (well-formedness via [javax.xml.parsers.DocumentBuilderFactory], element paths via
 * [javax.xml.xpath.XPath]) rather than against the real schema. TODO(follow-up wave): source
 * `lapis-server/src/test/resources/sepa/pain.008.001.08.xsd` from iso20022.org's "Message
 * Definitions -> Payments Initiation -> CustomerDirectDebitInitiationV08" and switch this suite to
 * real `javax.xml.validation.SchemaFactory` validation. This is a genuine, documented gap in this
 * wave's coverage, not a silent omission -- see the CHANGELOG "Known limitations" entry.
 */
class SepaPain008WriterTest :
    FunSpec({
        val ns =
            object : NamespaceContext {
                override fun getNamespaceURI(prefix: String?): String =
                    if (prefix == "p") "urn:iso:std:iso:20022:tech:xsd:pain.008.001.08" else ""

                override fun getPrefix(namespaceURI: String?): String? = null

                override fun getPrefixes(namespaceURI: String?): Iterator<String>? = null
            }

        fun parse(bytes: ByteArray): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
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

        fun item(
            endToEndId: String = "E2E-${(1..999999).random()}",
            amount: BigDecimal = BigDecimal("60.00"),
            debtorName: String = "Erika Mustermann",
            debtorIban: String = "DE89370400440532013000",
            mandateReference: String = "LC-3F2A9C41-20260819-7B1E",
        ) = SepaBatchItemSpec(
            endToEndId = endToEndId,
            amount = amount,
            mandateReference = mandateReference,
            mandateSignatureDate = LocalDate(2026, 8, 19),
            debtorName = debtorName,
            debtorIban = debtorIban,
            debtorBic = "COBADEFFXXX",
            remittanceInformation = "Mitgliedsbeitrag 2026",
        )

        fun spec(items: List<SepaBatchItemSpec>) =
            SepaBatchSpec(
                version = SepaPain008Writer.DEFAULT_VERSION,
                messageId = "LC-DD-20260819120000-A1B2C3",
                creationDateTime = LocalDateTime(2026, 8, 19, 14, 22, 5),
                initiatingPartyName = "Partei der Vernunft",
                paymentInfoId = "LC-DD-20260819120000-A1B2C3-P1",
                sequenceType = SepaSequenceType.RCUR,
                requestedCollectionDate = LocalDate(2026, 9, 15),
                creditorName = "Partei der Vernunft",
                creditorIban = "DE89370400440532013000",
                creditorBic = "COBADEFFXXX",
                creditorSchemeId = "DE98ZZZ09999999999",
                items = items,
            )

        test("three items produce a well-formed document with the correct namespace") {
            val bytes = SepaPain008Writer.write(spec(listOf(item(), item(), item())))
            val document = parse(bytes)
            xpath(document, "local-name(/*)") shouldBe "Document"
            document.documentElement.namespaceURI shouldBe "urn:iso:std:iso:20022:tech:xsd:pain.008.001.08"
        }

        test("100 items with odd amounts: CtrlSum equals the sum of InstdAmt, no cent lost") {
            val amounts = (1..100).map { BigDecimal(it).movePointLeft(2) + BigDecimal("0.01") }
            val items = amounts.mapIndexed { index, amount -> item(endToEndId = "E2E-$index", amount = amount) }
            val expectedTotal = amounts.fold(BigDecimal.ZERO) { acc, a -> acc + a }.setScale(2)

            val bytes = SepaPain008Writer.write(spec(items))
            val document = parse(bytes)
            val ctrlSum = xpath(document, "//p:GrpHdr/p:CtrlSum")
            BigDecimal(ctrlSum).compareTo(expectedTotal) shouldBe 0

            val instdAmtSum =
                (0 until items.size)
                    .map { xpath(document, "(//p:DrctDbtTxInf/p:InstdAmt)[${it + 1}]") }
                    .fold(BigDecimal.ZERO) { acc, s -> acc + BigDecimal(s) }
            instdAmtSum.compareTo(expectedTotal) shouldBe 0
        }

        test("NbOfTxs matches item count in both GrpHdr and PmtInf") {
            val bytes = SepaPain008Writer.write(spec(listOf(item(), item(), item())))
            val document = parse(bytes)
            xpath(document, "//p:GrpHdr/p:NbOfTxs") shouldBe "3"
            xpath(document, "//p:PmtInf/p:NbOfTxs") shouldBe "3"
        }

        test("special characters are transliterated exactly once -- no double-escaping") {
            val bytes = SepaPain008Writer.write(spec(listOf(item(debtorName = "Müller & Söhne <GmbH> \"Alt\""))))
            val document = parse(bytes)
            val name = xpath(document, "//p:Dbtr/p:Nm")
            // "&"/"<"/">"/"\"" are not in the SEPA basic character set (SepaCharacterSet's
            // ALLOWED_PUNCTUATION) and are therefore mapped to "." like any other unsupported
            // character -- the point of this test is that the XML is well-formed and the value
            // appears EXACTLY once, unescaped a second time (no "&amp;amp;"-style double-escaping),
            // not that "&" survives transliteration.
            name shouldBe "Mueller . Soehne .GmbH. .Alt."
        }

        test("field lengths exactly at the limit are accepted") {
            val exactly70 = "A".repeat(70)
            val exactly140 = "B".repeat(140)
            val exactly35 = "C".repeat(35)
            val bytes =
                SepaPain008Writer.write(
                    spec(
                        listOf(
                            item(endToEndId = exactly35, mandateReference = exactly35, debtorName = exactly70).copy(
                                remittanceInformation = exactly140,
                            ),
                        ),
                    ),
                )
            parse(bytes) // does not throw
        }

        test("field lengths one over the limit are rejected") {
            shouldThrow<IllegalArgumentException> {
                SepaPain008Writer.write(spec(listOf(item(debtorName = "A".repeat(71)))))
            }
        }

        test("a name that grows past 70 characters through transliteration is truncated, not rejected") {
            // "ü" -> "ue" grows length; 70 raw umlauts would grow to 140 chars if not truncated.
            val bytes = SepaPain008Writer.write(spec(listOf(item(debtorName = "ü".repeat(60)))))
            val document = parse(bytes)
            xpath(document, "//p:Dbtr/p:Nm").length shouldBe 70
        }

        test("zero, negative, and over-scaled amounts are rejected") {
            shouldThrow<IllegalArgumentException> { SepaPain008Writer.write(spec(listOf(item(amount = BigDecimal.ZERO)))) }
            shouldThrow<IllegalArgumentException> { SepaPain008Writer.write(spec(listOf(item(amount = BigDecimal("-1.00"))))) }
            shouldThrow<IllegalArgumentException> { SepaPain008Writer.write(spec(listOf(item(amount = BigDecimal("1.234"))))) }
        }

        test("an empty item list is rejected") {
            shouldThrow<IllegalArgumentException> { SepaPain008Writer.write(spec(emptyList())) }
        }

        test("duplicate endToEndId within the same batch is rejected") {
            shouldThrow<IllegalArgumentException> {
                SepaPain008Writer.write(spec(listOf(item(endToEndId = "SAME"), item(endToEndId = "SAME"))))
            }
        }

        test("an invalid creditor IBAN is rejected") {
            shouldThrow<IllegalArgumentException> {
                SepaPain008Writer.write(spec(listOf(item())).copy(creditorIban = "DE00000000000000000000"))
            }
        }

        test("pain.008.001.02 is rejected, message names BIC/BICFI") {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    SepaPain008Writer.write(spec(listOf(item())).copy(version = "pain.008.001.02"))
                }
            (exception.message?.contains("BIC") == true) shouldBe true
        }

        test("SeqTp is exactly spec.sequenceType.name") {
            val bytes = SepaPain008Writer.write(spec(listOf(item())))
            xpath(parse(bytes), "//p:PmtTpInf/p:SeqTp") shouldBe "RCUR"
        }

        test(
            "creditorBic/debtorBic null: CdtrAgt/DbtrAgt are STILL emitted with the NOTPROVIDED " +
                "placeholder, never omitted (M-3, Review Round 1, 2026-08-19)",
        ) {
            val itemNoBic = item().copy(debtorBic = null)
            val bytes = SepaPain008Writer.write(spec(listOf(itemNoBic)).copy(creditorBic = null))
            val document = parse(bytes)
            xpath(document, "count(//p:CdtrAgt)") shouldBe "1"
            xpath(document, "count(//p:DbtrAgt)") shouldBe "1"
            xpath(document, "//p:CdtrAgt/p:FinInstnId/p:Othr/p:Id") shouldBe "NOTPROVIDED"
            xpath(document, "//p:DbtrAgt/p:FinInstnId/p:Othr/p:Id") shouldBe "NOTPROVIDED"
            // Neither element carries a (nonexistent) BICFI child in this case.
            xpath(document, "count(//p:CdtrAgt/p:FinInstnId/p:BICFI)") shouldBe "0"
            xpath(document, "count(//p:DbtrAgt/p:FinInstnId/p:BICFI)") shouldBe "0"
        }

        test("creditorBic/debtorBic present: CdtrAgt/DbtrAgt carry the real BICFI, not NOTPROVIDED") {
            // item() defaults debtorBic to "COBADEFFXXX" and spec() defaults creditorBic likewise.
            val bytes = SepaPain008Writer.write(spec(listOf(item())))
            val document = parse(bytes)
            xpath(document, "//p:CdtrAgt/p:FinInstnId/p:BICFI") shouldBe "COBADEFFXXX"
            xpath(document, "//p:DbtrAgt/p:FinInstnId/p:BICFI") shouldBe "COBADEFFXXX"
            xpath(document, "count(//p:CdtrAgt/p:FinInstnId/p:Othr)") shouldBe "0"
            xpath(document, "count(//p:DbtrAgt/p:FinInstnId/p:Othr)") shouldBe "0"
        }

        test(
            "CreDtTm always has exactly seconds precision, never dropped (zero-second input) and " +
                "never fractional (nonzero-nanos input) (M-2, Review Round 1, 2026-08-19)",
        ) {
            // Zero-second/zero-nano input: LocalDateTime.toString() would previously drop seconds
            // entirely ("2026-08-19T14:22"), an invalid xs:dateTime. This is the exact regression the
            // pre-fix SepaPain008WriterTest fixture (always a nonzero-second creationDateTime) never
            // caught.
            val zeroSecondBytes =
                SepaPain008Writer.write(spec(listOf(item())).copy(creationDateTime = LocalDateTime(2026, 8, 19, 14, 22, 0)))
            xpath(parse(zeroSecondBytes), "//p:GrpHdr/p:CreDtTm") shouldBe "2026-08-19T14:22:00"

            // Nonzero-second WITH nanoseconds: LocalDateTime.toString() would previously include the
            // fractional part ("...:05.123456789"), which many bank pain.008 validators reject.
            val withNanosBytes =
                SepaPain008Writer.write(
                    spec(listOf(item())).copy(
                        creationDateTime = LocalDateTime(2026, 8, 19, 14, 22, 5, 123_456_789),
                    ),
                )
            xpath(parse(withNanosBytes), "//p:GrpHdr/p:CreDtTm") shouldBe "2026-08-19T14:22:05"
        }

        test("output starts with a UTF-8 XML declaration and is UTF-8 decodable") {
            val bytes = SepaPain008Writer.write(spec(listOf(item())))
            val text = bytes.toString(Charsets.UTF_8)
            text.startsWith("<?xml") shouldBe true
            text.contains("encoding=\"UTF-8\"") shouldBe true
        }
    })
