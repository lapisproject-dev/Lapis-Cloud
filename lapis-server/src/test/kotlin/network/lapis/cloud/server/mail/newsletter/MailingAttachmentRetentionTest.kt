package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** V1.9.232 -- retention of attachment FILES (180 days after sending) and the store's list/delete hooks. */
class MailingAttachmentRetentionTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val listIds = mutableListOf<Uuid>()
        val messageIds = mutableListOf<Uuid>()
        val root: File = Files.createTempDirectory("mailing-retention").toFile()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                MailingMessageAttachmentTable.deleteWhere { mailingMessageId inList messageIds }
                MailingMessageTable.deleteWhere { MailingMessageTable.id inList messageIds }
                MailingListTable.deleteWhere { MailingListTable.id inList listIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
            root.deleteRecursively()
        }

        val author: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Aufbewahrung"
                    it[email] = "retention-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            memberIds += id
            id
        }
        val listId: Uuid by lazy {
            val id = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = id
                    it[name] = "Aufbewahrung"
                    it[description] = null
                    it[createdBy] = author
                }
            }
            listIds += id
            id
        }

        fun daysAgo(days: Int): LocalDateTime {
            val now = DbClock.nowLocalDateTime()
            return LocalDateTime(now.date.minus(DatePeriod(days = days)), now.time)
        }

        fun message(
            status: MailingMessageStatus,
            sentAt: LocalDateTime?,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MailingMessageTable.insert {
                    it[MailingMessageTable.id] = id
                    it[mailingListId] = listId
                    it[subject] = "Aufbewahrung"
                    it[bodyText] = "Text"
                    it[bodyHtml] = null
                    it[sentBy] = author
                    it[MailingMessageTable.status] = status
                    it[MailingMessageTable.sentAt] = sentAt
                }
            }
            messageIds += id
            return id
        }

        fun attach(
            messageId: Uuid,
            name: String,
            uploadedAt: LocalDateTime = DbClock.nowLocalDateTime(),
        ): String {
            val id = Uuid.random()
            val key = "mailing/$messageId/$id.bin"
            root.resolve(key).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
            transaction {
                MailingAttachmentStore.insertInTx(
                    attachmentId = id,
                    messageId = messageId,
                    storageKey = key,
                    fileName = name,
                    nameKey = name.lowercase(),
                    mimeType = "application/pdf",
                    textCharset = null,
                    sizeBytes = 3,
                    sha256 = "0".repeat(64),
                    uploadedBy = author,
                    uploadedAt = uploadedAt,
                )
            }
            return key
        }

        fun deletedAtOf(key: String): LocalDateTime? =
            transaction {
                MailingMessageAttachmentTable.selectAll().where { MailingMessageAttachmentTable.storageKey eq key }.single()[
                    MailingMessageAttachmentTable.fileDeletedAt,
                ]
            }

        test("a SENT or FAILED message older than the retention period loses its files; the metadata row stays and says so") {
            val sent = message(MailingMessageStatus.SENT, daysAgo(MailingHtmlPolicy.RETENTION_DAYS + 5))
            val failed = message(MailingMessageStatus.FAILED, daysAgo(MailingHtmlPolicy.RETENTION_DAYS + 1))
            val keySent = attach(sent, "alt.pdf")
            val keyFailed = attach(failed, "alt2.pdf")
            MailingAttachmentRetention.purgeDue(now = DbClock.nowLocalDateTime(), storageRoot = root) shouldBe 2
            root.resolve(keySent).exists() shouldBe false
            root.resolve(keyFailed).exists() shouldBe false
            (deletedAtOf(keySent) != null) shouldBe true
            transaction { MailingAttachmentStore.listForMessage(sent) }.single().fileAvailable shouldBe false
        }

        test("younger messages, drafts and queued messages are never touched") {
            val young = message(MailingMessageStatus.SENT, daysAgo(MailingHtmlPolicy.RETENTION_DAYS - 2))
            val draft = message(MailingMessageStatus.DRAFT, null)
            val queued = message(MailingMessageStatus.QUEUED, null)
            val keys = listOf(attach(young, "jung.pdf"), attach(draft, "entwurf.pdf"), attach(queued, "queue.pdf"))
            MailingAttachmentRetention.purgeDue(now = DbClock.nowLocalDateTime(), storageRoot = root)
            keys.forEach { key ->
                root.resolve(key).exists() shouldBe true
                deletedAtOf(key) shouldBe null
            }
        }

        test("batching and idempotence: batches of 2 over 5 files, then nothing left") {
            val old = message(MailingMessageStatus.SENT, daysAgo(MailingHtmlPolicy.RETENTION_DAYS + 30))
            val keys = (1..5).map { attach(old, "b$it.pdf") }
            val now = DbClock.nowLocalDateTime()
            val batches =
                generateSequence {
                    MailingAttachmentRetention.purgeDue(
                        now = now,
                        storageRoot = root,
                        batchSize = 2,
                    )
                }.take(4).toList()
            batches shouldContainExactly listOf(2, 2, 1, 0)
            keys.forEach { root.resolve(it).exists() shouldBe false }
            MailingAttachmentRetention.purgeDue(now = now, storageRoot = root) shouldBe 0
        }

        test("a file that is already gone does not break the job") {
            val old = message(MailingMessageStatus.SENT, daysAgo(MailingHtmlPolicy.RETENTION_DAYS + 40))
            val key = attach(old, "schon-weg.pdf")
            root.resolve(key).delete()
            MailingAttachmentRetention.purgeDue(now = DbClock.nowLocalDateTime(), storageRoot = root) shouldBe 1
            (deletedAtOf(key) != null) shouldBe true
        }

        test("deleteAllForMessageInTx removes the rows and returns the keys (the hook for any future message deletion)") {
            val draft = message(MailingMessageStatus.DRAFT, null)
            val keys = listOf(attach(draft, "x.pdf"), attach(draft, "y.pdf"))
            val returned = transaction { MailingAttachmentStore.deleteAllForMessageInTx(draft) }
            returned.toSet() shouldBe keys.toSet()
            transaction { MailingAttachmentStore.listForMessage(draft) } shouldBe emptyList()
            MailingAttachmentStore.deleteFilesAfterCommit(root = root, keys = returned)
            keys.forEach { root.resolve(it).exists() shouldBe false }
        }

        test("listForMessages returns every message's attachments in upload order with ONE statement") {
            val a = message(MailingMessageStatus.DRAFT, null)
            val b = message(MailingMessageStatus.DRAFT, null)
            val c = message(MailingMessageStatus.DRAFT, null)
            val base = DbClock.nowLocalDateTime()
            attach(a, "zweite.pdf", uploadedAt = LocalDateTime(base.date, kotlinx.datetime.LocalTime(12, 0, 2)))
            attach(a, "erste.pdf", uploadedAt = LocalDateTime(base.date, kotlinx.datetime.LocalTime(12, 0, 1)))
            attach(c, "c1.pdf")
            transaction {
                val before = statementCount
                val grouped = MailingAttachmentStore.listForMessages(listOf(a, b, c))
                (statementCount - before) shouldBe 1
                grouped.getValue(a).map { it.fileName } shouldContainExactly listOf("erste.pdf", "zweite.pdf")
                grouped.containsKey(b) shouldBe false
                grouped.getValue(c).single().fileName shouldBe "c1.pdf"
            }
        }
    })
