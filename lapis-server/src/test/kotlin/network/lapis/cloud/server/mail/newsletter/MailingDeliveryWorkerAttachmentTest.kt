package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailAttachment
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Records every attachment send together with the bytes it read from disk at send time. */
private class AttachmentRecordingTransport(
    private val supportsAttachments: Boolean = true,
) : MailTransport {
    class Call(
        val to: String,
        val names: List<String>,
        val types: List<String>,
        val bytes: List<ByteArray>,
    )

    val calls = mutableListOf<Call>()
    var plainSends = 0

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        plainSends++
        return MailSendOutcome.Sent
    }

    override suspend fun sendWithAttachments(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
        attachments: List<MailAttachment>,
    ): MailSendOutcome {
        if (!supportsAttachments) {
            return super.sendWithAttachments(
                to = to,
                subject = subject,
                plainTextBody = plainTextBody,
                htmlBody = htmlBody,
                attachments = attachments,
            )
        }
        calls +=
            Call(
                to = to,
                names = attachments.map { it.fileName },
                types = attachments.map { it.contentType },
                bytes = attachments.map { it.file.readBytes() },
            )
        return MailSendOutcome.Sent
    }
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** V1.9.232 -- the delivery worker puts the attachments into EVERY mail and refuses to send when they are missing or changed. */
class MailingDeliveryWorkerAttachmentTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdListIds = mutableListOf<Uuid>()
        val createdMessageIds = mutableListOf<Uuid>()
        val storageRoot: File = Files.createTempDirectory("mailing-worker-attachments").toFile()
        val workers = mutableListOf<MailingDeliveryWorker>()

        beforeSpec { DatabaseConfig.connect() }
        afterTest { workers.forEach { it.shutdown() } }
        afterSpec {
            transaction {
                MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.mailingMessageId inList createdMessageIds }
                MailingMessageAttachmentTable.deleteWhere { MailingMessageAttachmentTable.mailingMessageId inList createdMessageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList createdMessageIds }
                MailingListTable.deleteWhere { MailingListTable.id inList createdListIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            storageRoot.deleteRecursively()
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Anhang-Empfänger"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        class Fixture(
            val messageId: Uuid,
            val deliveryIds: List<Uuid>,
            val author: Uuid,
        )

        fun fixture(recipients: Int = 2): Fixture {
            val author = createMember("author-${Uuid.random()}@example.org")
            val listId = Uuid.random()
            val messageId = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = listId
                    it[name] = "Anhang-Worker-Liste"
                    it[description] = null
                    it[createdBy] = author
                }
                MailingMessageTable.insert {
                    it[MailingMessageTable.id] = messageId
                    it[mailingListId] = listId
                    it[subject] = "Mit Anhang"
                    it[bodyText] = "Hallo"
                    it[bodyHtml] = null
                    it[sentBy] = author
                    it[status] = MailingMessageStatus.QUEUED
                }
            }
            createdListIds += listId
            createdMessageIds += messageId
            val deliveries =
                (1..recipients).map { index ->
                    val memberId = createMember("empfaenger$index-${Uuid.random()}@example.org")
                    val deliveryId = Uuid.random()
                    transaction {
                        MailingDeliveryLogTable.insert {
                            it[MailingDeliveryLogTable.id] = deliveryId
                            it[mailingMessageId] = messageId
                            it[MailingDeliveryLogTable.memberId] = memberId
                            it[deliveredAt] = DbClock.nowLocalDateTime()
                            it[deliveryStatus] = DeliveryStatus.PENDING
                        }
                    }
                    deliveryId
                }
            return Fixture(messageId, deliveries, author)
        }

        /** Stores a real file and its row; returns the storage key. */
        fun addAttachment(
            f: Fixture,
            name: String,
            bytes: ByteArray,
            mime: String = "application/pdf",
            charset: String? = null,
        ): Uuid {
            val id = Uuid.random()
            val key = "mailing/${f.messageId}/$id.bin"
            storageRoot.resolve(key).also { it.parentFile.mkdirs() }.writeBytes(bytes)
            transaction {
                MailingAttachmentStore.insertInTx(
                    attachmentId = id,
                    messageId = f.messageId,
                    storageKey = key,
                    fileName = name,
                    nameKey = name.lowercase(),
                    mimeType = mime,
                    textCharset = charset,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256Hex(bytes),
                    uploadedBy = f.author,
                    uploadedAt = DbClock.nowLocalDateTime(),
                )
            }
            return id
        }

        fun worker(
            transport: MailTransport,
            mode: MailingDeliveryMode = MailingDeliveryMode.SMTP,
            root: File? = storageRoot,
        ) = MailingDeliveryWorker(
            transport = transport,
            branding = MailBranding.notConfigured(),
            mode = mode,
            trackingToken = testTrackingToken(),
            baseUrl = TEST_TRACKING_BASE_URL,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sendDelay = 0.milliseconds,
            maxWait = 20.milliseconds,
            attachmentStorageRoot = root,
        ).also { workers += it }

        fun deliveryStatuses(f: Fixture): List<DeliveryStatus> =
            transaction {
                MailingDeliveryLogTable
                    .selectAll()
                    .where { MailingDeliveryLogTable.mailingMessageId eq f.messageId }
                    .map { it[MailingDeliveryLogTable.deliveryStatus] }
            }

        fun messageStatus(f: Fixture): MailingMessageStatus =
            transaction {
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq f.messageId }.single()[MailingMessageTable.status]
            }

        test("every recipient gets every attachment, in upload order, with the stored bytes and server content types") {
            val f = fixture(recipients = 2)
            val pdf = ByteArray(3000) { (it % 200).toByte() }
            val txt = "Grüße".toByteArray(Charsets.UTF_8)
            addAttachment(f, "Einladung.pdf", pdf)
            addAttachment(f, "notiz.txt", txt, mime = "text/plain", charset = "UTF-8")
            val transport = AttachmentRecordingTransport()
            runBlocking { worker(transport).processMessage(f.messageId) }
            transport.calls.size shouldBe 2
            transport.calls.forEach { call ->
                call.names shouldContainExactly listOf("Einladung.pdf", "notiz.txt")
                call.types shouldContainExactly listOf("application/pdf", "text/plain; charset=UTF-8")
                call.bytes[0].contentEquals(pdf) shouldBe true
                call.bytes[1].contentEquals(txt) shouldBe true
            }
            transport.plainSends shouldBe 0
            deliveryStatuses(f) shouldContainExactly listOf(DeliveryStatus.SENT, DeliveryStatus.SENT)
            messageStatus(f) shouldBe MailingMessageStatus.SENT
        }

        test("a message without attachments still sends exactly as before (no attachments in the call)") {
            val f = fixture(recipients = 1)
            val transport = AttachmentRecordingTransport()
            runBlocking { worker(transport).processMessage(f.messageId) }
            transport.calls.single().names shouldBe emptyList()
            messageStatus(f) shouldBe MailingMessageStatus.SENT
        }

        suspend fun assertRefusedWithoutSending(
            f: Fixture,
            transport: AttachmentRecordingTransport,
        ) {
            worker(transport).processMessage(f.messageId)
            transport.calls.size shouldBe 0
            transport.plainSends shouldBe 0
            deliveryStatuses(f).toSet() shouldBe setOf(DeliveryStatus.FAILED)
            messageStatus(f) shouldBe MailingMessageStatus.FAILED
        }

        test("a missing file fails the message before the first recipient; the open rows are closed") {
            val f = fixture()
            val id = addAttachment(f, "weg.pdf", ByteArray(100) { 1 })
            storageRoot.resolve(transaction { MailingAttachmentStore.find(id)!!.storageKey }).delete()
            runBlocking { assertRefusedWithoutSending(f, AttachmentRecordingTransport()) }
        }

        test("a changed file (wrong SHA-256, same size) fails the message") {
            val f = fixture()
            val id = addAttachment(f, "manipuliert.pdf", ByteArray(100) { 1 })
            storageRoot.resolve(transaction { MailingAttachmentStore.find(id)!!.storageKey }).writeBytes(ByteArray(100) { 2 })
            runBlocking { assertRefusedWithoutSending(f, AttachmentRecordingTransport()) }
        }

        test("a file of a different size fails the message") {
            val f = fixture()
            val id = addAttachment(f, "kuerzer.pdf", ByteArray(100) { 1 })
            storageRoot.resolve(transaction { MailingAttachmentStore.find(id)!!.storageKey }).writeBytes(ByteArray(10) { 1 })
            runBlocking { assertRefusedWithoutSending(f, AttachmentRecordingTransport()) }
        }

        test("a file that retention already removed (file_deleted_at) fails the message") {
            val f = fixture()
            val id = addAttachment(f, "retention.pdf", ByteArray(100) { 1 })
            transaction {
                MailingMessageAttachmentTable.update({ MailingMessageAttachmentTable.id eq id }) {
                    it[fileDeletedAt] =
                        DbClock.nowLocalDateTime()
                }
            }
            runBlocking { assertRefusedWithoutSending(f, AttachmentRecordingTransport()) }
        }

        test("no attachment storage configured: a message WITH attachments fails closed instead of going out without them") {
            val f = fixture()
            addAttachment(f, "a.pdf", ByteArray(100) { 1 })
            val transport = AttachmentRecordingTransport()
            runBlocking {
                worker(transport, root = null).processMessage(f.messageId)
            }
            transport.calls.size shouldBe 0
            messageStatus(f) shouldBe MailingMessageStatus.FAILED
        }

        test(
            "a transport without attachment support fails closed per recipient (ATTACHMENTS_UNSUPPORTED), nothing is sent without the file",
        ) {
            val f = fixture(recipients = 2)
            addAttachment(f, "a.pdf", ByteArray(100) { 1 })
            val transport = AttachmentRecordingTransport(supportsAttachments = false)
            runBlocking { worker(transport).processMessage(f.messageId) }
            transport.calls.size shouldBe 0
            transport.plainSends shouldBe 0
            deliveryStatuses(f).toSet() shouldBe setOf(DeliveryStatus.FAILED)
            messageStatus(f) shouldBe MailingMessageStatus.FAILED
        }

        test("LOG mode sends nothing but still checks the integrity of the attachments") {
            val ok = fixture(recipients = 1)
            addAttachment(ok, "ok.pdf", ByteArray(100) { 1 })
            val okTransport = AttachmentRecordingTransport()
            runBlocking { worker(okTransport, mode = MailingDeliveryMode.LOG).processMessage(ok.messageId) }
            okTransport.calls.size shouldBe 0
            messageStatus(ok) shouldBe MailingMessageStatus.SENT

            val broken = fixture(recipients = 1)
            val id = addAttachment(broken, "kaputt.pdf", ByteArray(100) { 1 })
            storageRoot.resolve(transaction { MailingAttachmentStore.find(id)!!.storageKey }).delete()
            runBlocking { worker(AttachmentRecordingTransport(), mode = MailingDeliveryMode.LOG).processMessage(broken.messageId) }
            messageStatus(broken) shouldBe MailingMessageStatus.FAILED
        }

        test("a storage key that would leave the root is not followed (hand-edited row)") {
            val f = fixture(recipients = 1)
            val id = addAttachment(f, "a.pdf", ByteArray(100) { 1 })
            transaction {
                MailingMessageAttachmentTable.update({ MailingMessageAttachmentTable.id eq id }) {
                    it[storageKey] =
                        "../outside-${Uuid.random()}.bin"
                }
            }
            runBlocking { assertRefusedWithoutSending(f, AttachmentRecordingTransport()) }
        }

        test("the per-send timeout allowance grows with the attachment bytes and is capped at 10 minutes") {
            MailingDeliveryWorker.attachmentSendAllowance(0) shouldBe kotlin.time.Duration.ZERO
            val small = MailingDeliveryWorker.attachmentSendAllowance(1024 * 1024)
            (small > 1.seconds && small < 30.seconds) shouldBe true
            val twenty = MailingDeliveryWorker.attachmentSendAllowance(20L * 1024 * 1024)
            (twenty > 60.seconds) shouldBe true
            MailingDeliveryWorker.attachmentSendAllowance(10L * 1024 * 1024 * 1024) shouldBe 10.minutes
            // a 28 MB wire size at the assumed rate still fits into default timeout + allowance
            (MailingDeliveryWorker.DEFAULT_PER_SEND_TIMEOUT + twenty >= (28L * 1024 * 1024 / (256 * 1024)).seconds) shouldBe true
        }

        test("the failure of one message does not touch another queued message with the same attachment store") {
            val broken = fixture(recipients = 1)
            val id = addAttachment(broken, "kaputt.pdf", ByteArray(100) { 1 })
            storageRoot.resolve(transaction { MailingAttachmentStore.find(id)!!.storageKey }).delete()
            val good = fixture(recipients = 1)
            addAttachment(good, "gut.pdf", ByteArray(100) { 1 })
            val transport = AttachmentRecordingTransport()
            val w = worker(transport)
            runBlocking {
                w.processMessage(broken.messageId)
                w.processMessage(good.messageId)
            }
            messageStatus(broken) shouldBe MailingMessageStatus.FAILED
            messageStatus(good) shouldBe MailingMessageStatus.SENT
            transport.calls.size shouldBe 1
        }
    })
