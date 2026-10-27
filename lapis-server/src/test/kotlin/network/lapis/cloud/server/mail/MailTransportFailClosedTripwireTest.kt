package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/** V1.9.232 -- the bulk worker reaches the transport only through the fail-closed `sendWithAttachments`, never through a bare `send`. */
class MailTransportFailClosedTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        test("MailingDeliveryWorker calls transport.sendWithAttachments and never transport.send") {
            val worker = SourceScan.blank(files.single { it.name == "MailingDeliveryWorker.kt" }.readText())
            worker.contains("transport.sendWithAttachments(") shouldBe true
            Regex("""transport\.send\(""").containsMatchIn(worker) shouldBe false
        }

        test("the default sendWithAttachments of the MailTransport interface refuses a mail with attachments") {
            val code = files.single { it.name == "MailTransport.kt" }.readText()
            code.contains("ATTACHMENTS_UNSUPPORTED") shouldBe true
            code.contains("suspend fun sendWithAttachments(") shouldBe true
        }

        test("the system-mail path (MailDispatcher, outbox) never learns about attachments") {
            val offenders =
                files
                    .filter {
                        it.parentFile.name == "outbox" || it.name == "MailDispatcher.kt"
                    }.filter { it.readText().contains("MailAttachment") }
            offenders.map { it.name } shouldBe emptyList()
        }
    })
