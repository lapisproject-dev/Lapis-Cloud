package network.lapis.cloud.server.pdf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.governance.session.ProtocolSnapshotV1
import network.lapis.cloud.server.governance.session.ProtocolSnapshots
import network.lapis.cloud.server.governance.session.SnapshotItem
import network.lapis.cloud.server.governance.session.SnapshotTask
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.ProtocolAttendanceDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolHeaderDto
import network.lapis.cloud.shared.domain.ProtocolPersonDto
import network.lapis.cloud.shared.domain.ProtocolResolutionDto
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

class MeetingProtocolPdfTest :
    FunSpec({
        fun resolution(
            secret: Boolean = false,
            withheld: Boolean = false,
        ) = ProtocolResolutionDto(
            resolutionId = "r1",
            number = "B-1",
            title = "Haushalt",
            text = "Der Haushalt wird beschlossen.",
            yes = if (withheld) 0 else 7,
            no = if (withheld) 0 else 2,
            abstain = if (withheld) 0 else 1,
            status = ResolutionStatus.ADOPTED,
            quorumMet = true,
            decidedAt = LocalDateTime(2030, 5, 4, 20, 0),
            secretBallot = secret,
            figuresWithheld = withheld,
        )

        fun snapshot(
            body: String = "Verlauf des ersten Punkts",
            items: Int = 1,
            resolutions: List<ProtocolResolutionDto> = emptyList(),
        ) = ProtocolSnapshotV1(
            meetingId = "m",
            versionNo = 2,
            correctionOfVersionNo = 1,
            correctionNote = "Korrektur wegen eines Zahlendrehers",
            minutesRequired = true,
            header =
                ProtocolHeaderDto(
                    committeeName = "Vorstand",
                    committeeType = CommitteeType.EXECUTIVE_BOARD,
                    title = "Ordentliche Sitzung",
                    scheduledAt = LocalDateTime(2030, 5, 4, 19, 0),
                    format = MeetingFormat.ONLINE,
                    location = null,
                    openedAt = null,
                    endedAt = null,
                    chairName = "Anna Leitung",
                    minuteTakerName = "Bernd Schrift",
                ),
            attendance = ProtocolAttendanceDto(present = listOf(ProtocolPersonDto("Anna Leitung"), ProtocolPersonDto("Bernd Schrift"))),
            quorum = null,
            items =
                (1..items).map {
                    SnapshotItem(
                        agendaItemId = "i$it",
                        position = it,
                        title = "TOP $it",
                        body = body,
                        resolutions = if (it == 1) resolutions else emptyList(),
                        tasks =
                            if (it ==
                                1
                            ) {
                                listOf(
                                    SnapshotTask(
                                        taskKey = "k",
                                        assigneeName = "Carla Aufgabe",
                                        dueDate = LocalDate(2030, 6, 1),
                                        description = "Bericht schreiben",
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        approval = null,
                    )
                },
            unassignedResolutions = emptyList(),
        )

        val hash = "ab".repeat(32)
        val confirmations =
            listOf(
                PdfConfirmation(
                    role = ProtocolConfirmationRole.LEAD,
                    name = "Anna Leitung",
                    confirmedAt = LocalDateTime(2030, 5, 4, 18, 0),
                ),
                PdfConfirmation(
                    role = ProtocolConfirmationRole.MINUTES,
                    name = "Bernd Schrift",
                    confirmedAt = LocalDateTime(2030, 5, 4, 18, 5),
                ),
            )

        fun render(
            s: ProtocolSnapshotV1,
            page: Boolean = true,
        ) = MeetingProtocolPdfGenerator.generate(
            snapshot = s,
            contentSha256 = hash,
            confirmations = confirmations,
            organizationName = "Testverein",
            organizationAddressLines = listOf("Weg 1"),
            zone = TimeZone.of("Europe/Berlin"),
            includeConfirmationPage = page,
        )

        fun text(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

        test(
            "the PDF is plain: not encrypted, no form, no JavaScript, no open action; the last page holds the confirmations and the full hash",
        ) {
            val bytes = render(snapshot())
            Loader.loadPDF(bytes).use { doc ->
                doc.isEncrypted shouldBe false
                doc.documentCatalog.acroForm shouldBe null
                doc.documentCatalog.openAction shouldBe null
                doc.documentCatalog.names?.javaScript shouldBe null
                doc.documentInformation.producer shouldBe "Lapis Cloud"
            }
            val t = text(bytes)
            t shouldContain "Digital bestätigt von"
            t shouldContain "Anna Leitung"
            t shouldContain "Bernd Schrift"
            t shouldContain hash.take(32)
            t shouldContain "keine qualifizierte Signatur"
            t shouldContain "Korrekturvermerk"
        }

        test("a secret ballot shows the counts only; a withheld result shows no number and no person is tied to a vote") {
            val shown = text(render(snapshot(resolutions = listOf(resolution(secret = true)))))
            shown shouldContain "Ja 7, Nein 2, Enthaltungen 1"
            shown shouldContain "geheime Abstimmung"
            val withheld = text(render(snapshot(resolutions = listOf(resolution(secret = true, withheld = true)))))
            withheld shouldContain "Ergebnis zurückgehalten"
            withheld shouldNotContain "Ja 0"
        }

        test("strings that look like PDF syntax or bidi controls appear as plain text and cannot break the file") {
            val hostile = ")Tj ET /JavaScript <script>alert(1)</script> %PDF-1.4 ‮\u0000 end"
            val bytes = render(snapshot(body = hostile))
            Loader.loadPDF(bytes).use { it.documentCatalog.names?.javaScript shouldBe null }
            text(bytes) shouldContain "<script>"
        }

        test("a text beyond the page budget is refused instead of rendering without limit") {
            val long = "wort ".repeat(9_000)
            shouldThrow<PdfLimitExceeded> { render(snapshot(body = long, items = 300)) }
        }

        test("an expired deadline stops the rendering") {
            val builder =
                LetterPdfBuilder(
                    deadline =
                        java.time.Instant
                            .now()
                            .minusSeconds(1),
                )
            shouldThrow<PdfLimitExceeded> { builder.paragraph("x") }
        }

        test("the file is saved whole, so a later PAdES signature can be appended as an incremental update that keeps the original bytes") {
            val original = render(snapshot())
            val signed = ByteArrayOutputStream()
            Loader.loadPDF(original).use { doc ->
                val signature = PDSignature()
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                signature.name = "Test"
                doc.addSignature(signature, SignatureInterface { ByteArray(0) })
                doc.saveIncremental(signed)
            }
            val result = signed.toByteArray()
            (result.size > original.size) shouldBe true
            result.copyOfRange(0, original.size).contentEquals(original) shouldBe true
            Loader.loadPDF(result).use { it.numberOfPages shouldBe Loader.loadPDF(original).use { o -> o.numberOfPages } }
        }

        test("the probe without the confirmation page is one page shorter than the page limit allows") {
            ProtocolSnapshots.canonicalJson(snapshot()).isNotEmpty() shouldBe true
            val withPage = Loader.loadPDF(render(snapshot(), page = true)).use(PDDocument::getNumberOfPages)
            val without = Loader.loadPDF(render(snapshot(), page = false)).use(PDDocument::getNumberOfPages)
            withPage shouldBe without + 1
        }
    })
