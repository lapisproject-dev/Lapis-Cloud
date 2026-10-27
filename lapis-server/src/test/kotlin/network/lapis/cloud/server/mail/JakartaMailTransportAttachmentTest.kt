package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Properties

private fun attachmentTestConfig(): SmtpConfig =
    (
        SmtpConfig.load(
            env = { key ->
                mapOf(
                    SmtpConfig.ENV_HOST to "mail.example.invalid",
                    SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                    SmtpConfig.ENV_PASSWORD to "s3cr3t",
                    SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                    SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
                )[key]
            },
        ) as SmtpConfigState.Configured
    ).config

private fun session(): Session = JakartaMailTransport.createSession(attachmentTestConfig())

private fun serialise(message: MimeMessage): ByteArray = ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()

/** Date, Message-ID and the random multipart boundaries differ per message; everything else must be identical. */
private fun masked(raw: ByteArray): String =
    String(raw, Charsets.ISO_8859_1)
        .replace(Regex("(?m)^Date: .*$"), "Date: X")
        .replace(Regex("(?m)^Message-ID: .*$"), "Message-ID: X")
        .replace(Regex("----=_Part_[0-9_.]+"), "BOUNDARY")

private fun tempFile(bytes: ByteArray): File = Files.createTempFile("mail-att", ".bin").toFile().also { it.writeBytes(bytes) }

/** V1.9.232 -- the MIME structure of a bulk mail with attachments. Only the `sendMessage` seam is used, no network. */
class JakartaMailTransportAttachmentTest :
    FunSpec({
        fun transport(captured: MutableList<MimeMessage>) =
            JakartaMailTransport(
                config = attachmentTestConfig(),
                sendMessage = { captured += it },
                sessionFactory = { session() },
            )

        test("multipart/mixed > multipart/alternative (plain, html) + one attachment per file, in upload order, base64, server types") {
            val pdf = tempFile(ByteArray(5000) { (it * 7).toByte() })
            val txt = tempFile("Grüße aus Braunschweig".toByteArray(Charsets.UTF_8))
            val captured = mutableListOf<MimeMessage>()
            val outcome =
                runBlocking {
                    transport(captured).sendWithAttachments(
                        to = "member@example.org",
                        subject = "Einladung",
                        plainTextBody = "plain",
                        htmlBody = "<p>html</p>",
                        attachments =
                            listOf(
                                MailAttachment(fileName = "Einladung.pdf", contentType = "application/pdf", file = pdf, sizeBytes = 5000),
                                MailAttachment(
                                    fileName = "notiz.txt",
                                    contentType = "text/plain; charset=UTF-8",
                                    file = txt,
                                    sizeBytes = txt.length(),
                                ),
                            ),
                    )
                }
            outcome.shouldBeInstanceOf<MailSendOutcome.Sent>()
            val message = captured.single()
            val mixed = message.content.shouldBeInstanceOf<MimeMultipart>()
            mixed.contentType.lowercase() shouldContain "multipart/mixed"
            mixed.count shouldBe 3

            val alternative = (mixed.getBodyPart(0).content).shouldBeInstanceOf<MimeMultipart>()
            alternative.contentType.lowercase() shouldContain "multipart/alternative"
            alternative.count shouldBe 2
            alternative.getBodyPart(0).contentType.lowercase() shouldContain "text/plain"
            alternative.getBodyPart(1).contentType.lowercase() shouldContain "text/html"

            val first = mixed.getBodyPart(1) as MimeBodyPart
            first.contentType.lowercase() shouldContain "application/pdf"
            first.disposition shouldBe "attachment"
            first.fileName shouldBe "Einladung.pdf"
            first.getHeader("Content-Transfer-Encoding")!!.single() shouldBe "base64"
            first.inputStream.readBytes().contentEquals(pdf.readBytes()) shouldBe true

            val second = mixed.getBodyPart(2) as MimeBodyPart
            second.contentType.lowercase() shouldContain "text/plain"
            second.contentType.lowercase() shouldContain "utf-8"
            second.fileName shouldBe "notiz.txt"
            String(second.inputStream.readBytes(), Charsets.UTF_8) shouldBe "Grüße aus Braunschweig"
        }

        test("a non-ASCII file name travels as an RFC 2231 parameter and decodes back") {
            val file = tempFile(byteArrayOf(1, 2, 3))
            val captured = mutableListOf<MimeMessage>()
            runBlocking {
                transport(captured).sendWithAttachments(
                    to = "member@example.org",
                    subject = "s",
                    plainTextBody = "p",
                    htmlBody = "<p>h</p>",
                    attachments =
                        listOf(
                            MailAttachment(fileName = "Käse Übersicht.pdf", contentType = "application/pdf", file = file, sizeBytes = 3),
                        ),
                )
            }
            val raw = String(serialise(captured.single()), Charsets.ISO_8859_1)
            raw shouldContain "filename*="
            val reparsed = MimeMessage(session(), ByteArrayInputStream(serialise(captured.single())))
            ((reparsed.content as MimeMultipart).getBodyPart(1) as MimeBodyPart).fileName shouldBe "Käse Übersicht.pdf"
        }

        test("a CR or LF in a file name is a Failed outcome and nothing is sent (no header injection)") {
            val file = tempFile(byteArrayOf(1))
            val captured = mutableListOf<MimeMessage>()
            listOf("a\r\nBcc: evil@example.org.pdf", "a\nb.pdf").forEach { name ->
                val outcome =
                    runBlocking {
                        transport(captured).sendWithAttachments(
                            to = "member@example.org",
                            subject = "s",
                            plainTextBody = "p",
                            htmlBody = "<p>h</p>",
                            attachments =
                                listOf(
                                    MailAttachment(fileName = name, contentType = "application/pdf", file = file, sizeBytes = 1),
                                ),
                        )
                    }
                outcome.shouldBeInstanceOf<MailSendOutcome.Failed>()
            }
            captured.size shouldBe 0
        }

        test("without attachments the message is byte-identical to a plain send()") {
            val plainCaptured = mutableListOf<MimeMessage>()
            val emptyCaptured = mutableListOf<MimeMessage>()
            runBlocking {
                transport(
                    plainCaptured,
                ).send(to = "member@example.org", subject = "Betreff", plainTextBody = "plain", htmlBody = "<p>html</p>")
                transport(
                    emptyCaptured,
                ).sendWithAttachments(
                    to = "member@example.org",
                    subject = "Betreff",
                    plainTextBody = "plain",
                    htmlBody = "<p>html</p>",
                    attachments = emptyList(),
                )
            }
            masked(serialise(emptyCaptured.single())) shouldBe masked(serialise(plainCaptured.single()))
            (emptyCaptured.single().content as MimeMultipart).contentType.lowercase() shouldContain "multipart/alternative"
        }

        test("a 12 MiB attachment is written from disk with the base64 encoding fixed up front and decodes to the same bytes") {
            val bytes = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }
            val file = tempFile(bytes)
            val captured = mutableListOf<MimeMessage>()
            runBlocking {
                transport(captured).sendWithAttachments(
                    to = "member@example.org",
                    subject = "gross",
                    plainTextBody = "p",
                    htmlBody = "<p>h</p>",
                    attachments =
                        listOf(
                            MailAttachment(
                                fileName = "gross.pdf",
                                contentType = "application/pdf",
                                file = file,
                                sizeBytes = bytes.size.toLong(),
                            ),
                        ),
                )
            }
            val part = (captured.single().content as MimeMultipart).getBodyPart(1) as MimeBodyPart
            // Set before saveChanges(): MimeBodyPart.updateHeaders only scans the content for an encoding when this header is absent.
            part.getHeader("Content-Transfer-Encoding")!!.single() shouldBe "base64"
            val raw = serialise(captured.single())
            (raw.size > bytes.size * 4 / 3) shouldBe true
            val decoded = ((MimeMessage(session(), ByteArrayInputStream(raw)).content as MimeMultipart).getBodyPart(1) as MimeBodyPart)
            decoded.inputStream.readBytes().contentEquals(bytes) shouldBe true
        }

        test("every getInputStream of the data source opens a NEW stream over the file (nothing is held in memory)") {
            val file = tempFile(byteArrayOf(9, 8, 7))
            val source = FileAttachmentDataSource(file = file, contentType = "application/pdf", name = "x.pdf")
            val a = source.inputStream
            val b = source.inputStream
            (a !== b) shouldBe true
            a.readBytes().toList() shouldBe listOf<Byte>(9, 8, 7)
            b.readBytes().toList() shouldBe listOf<Byte>(9, 8, 7)
            source.contentType shouldBe "application/pdf"
        }

        test("the default MailTransport.sendWithAttachments fails closed: a transport without attachment support never drops one") {
            val onlySend =
                object : MailTransport {
                    var sends = 0

                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sends++
                        return MailSendOutcome.Sent
                    }
                }
            val file = tempFile(byteArrayOf(1))
            val withAttachment =
                runBlocking {
                    onlySend.sendWithAttachments(
                        to = "a@b.c",
                        subject = "s",
                        plainTextBody = "p",
                        htmlBody = "h",
                        attachments =
                            listOf(
                                MailAttachment(fileName = "a.pdf", contentType = "application/pdf", file = file, sizeBytes = 1),
                            ),
                    )
                }
            val failed = withAttachment.shouldBeInstanceOf<MailSendOutcome.Failed>()
            failed.errorClass shouldBe "ATTACHMENTS_UNSUPPORTED"
            failed.kind shouldBe MailFailureKind.PERMANENT
            onlySend.sends shouldBe 0
            runBlocking {
                onlySend.sendWithAttachments(to = "a@b.c", subject = "s", plainTextBody = "p", htmlBody = "h", attachments = emptyList())
            }.shouldBeInstanceOf<MailSendOutcome.Sent>()
            onlySend.sends shouldBe 1
        }

        test("NoOpMailTransport skips a mail with attachments instead of pretending to send it") {
            val file = tempFile(byteArrayOf(1))
            runBlocking {
                NoOpMailTransport().sendWithAttachments(
                    to = "a@b.c",
                    subject = "s",
                    plainTextBody = "p",
                    htmlBody = "h",
                    attachments = listOf(MailAttachment(fileName = "a.pdf", contentType = "application/pdf", file = file, sizeBytes = 1)),
                )
            }.shouldBeInstanceOf<MailSendOutcome.Skipped>()
        }

        test("a bare Properties session still builds the message (the RFC 2231 properties are optional)") {
            val file = tempFile(byteArrayOf(1))
            val captured = mutableListOf<MimeMessage>()
            val bare =
                JakartaMailTransport(
                    config = attachmentTestConfig(),
                    sendMessage = { captured += it },
                    sessionFactory = { Session.getInstance(Properties()) },
                )
            runBlocking {
                bare.sendWithAttachments(
                    to = "a@b.c",
                    subject = "s",
                    plainTextBody = "p",
                    htmlBody = "h",
                    attachments = listOf(MailAttachment(fileName = "a.pdf", contentType = "application/pdf", file = file, sizeBytes = 1)),
                )
            }.shouldBeInstanceOf<MailSendOutcome.Sent>()
        }
    })
