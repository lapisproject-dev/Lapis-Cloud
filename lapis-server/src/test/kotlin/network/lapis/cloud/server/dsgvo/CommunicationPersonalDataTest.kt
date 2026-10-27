package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.DirectMessageAttachmentTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.mail.newsletter.MailingAttachmentStore
import network.lapis.cloud.server.mail.newsletter.MailingTrackingData
import network.lapis.cloud.server.mail.newsletter.TrackingFixture
import network.lapis.cloud.server.messaging.DirectMessageAttachmentStore
import network.lapis.cloud.server.rpc.insertDirectMessage
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.15 -- Art. 15 export and Art. 17 erasure of the mailing tracking data. The erasure case
 * is the load-bearing one: `mailing_link_click` has a FK onto the delivery log (no CASCADE by repo
 * convention), so without the explicit click-first delete the whole erasure transaction would abort
 * as soon as a single click row exists.
 */
class CommunicationPersonalDataTest :
    FunSpec({
        val fx = TrackingFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        // V1.9.233: members used by the direct-message attachment tests; their rows must go BEFORE the fixture removes the members.
        val dmMembers = mutableListOf<Uuid>()
        val dmStorageRoot = java.io.File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")
        afterSpec {
            transaction {
                DirectMessageAttachmentTable.deleteWhere { (uploaderId inList dmMembers) or (recipientId inList dmMembers) }
                DirectMessageTable.deleteWhere { (senderId inList dmMembers) or (recipientId inList dmMembers) }
            }
            fx.cleanup()
            dmMembers.forEach { dmStorageRoot.resolve("dm/$it").deleteRecursively() }
        }

        test("export lists consent timestamps, deliveries, open counts and clicks per link -- and never the token hash") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            fx.link(messageId = msg, index = 0, url = "https://example.org/a")
            val subject = fx.member()
            fx.subscribe(listId = list, memberId = subject, openConsent = true, clickConsent = true)
            val (delivery, _) = fx.delivery(messageId = msg, memberId = subject, openTracked = true, clickTracked = true)
            val now = DbClock.nowLocalDateTime()
            repeat(2) { MailingTrackingData.recordClick(deliveryLogId = delivery, linkIndex = 0, now = now) }
            MailingTrackingData.recordOpen(deliveryLogId = delivery, now = now)

            val export = transaction { CommunicationPersonalData.exportMember(subject) }
            val subscription = export["subscriptions"]!!.jsonArray.single().jsonObject
            (subscription["openTrackingConsentedAt"].toString() != "null") shouldBe true
            (subscription["clickTrackingConsentedAt"].toString() != "null") shouldBe true

            val deliveries = export["mailingDeliveries"]!!.jsonArray
            deliveries.size shouldBe 1
            val entry = deliveries.single().jsonObject
            entry["openTracked"]!!.let { (it as JsonPrimitive).boolean } shouldBe true
            (entry["openCount"] as JsonPrimitive).int shouldBe 1
            val click = entry["clicks"]!!.jsonArray.single().jsonObject
            (click["clickCount"] as JsonPrimitive).int shouldBe 2
            click["targetUrl"].toString() shouldBe "\"https://example.org/a\""
            entry.keys.none { it.contains("hash", ignoreCase = true) || it.contains("token", ignoreCase = true) } shouldBe true
        }

        test("export contains nothing of other members") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            val a = fx.member()
            val b = fx.member()
            fx.delivery(messageId = msg, memberId = a, clickTracked = true)
            fx.delivery(messageId = msg, memberId = b, clickTracked = true)
            transaction { CommunicationPersonalData.exportMember(a) }["mailingDeliveries"]!!.jsonArray.size shouldBe 1
        }

        test("erasure with existing click rows runs without an FK violation and removes clicks, deliveries and the outcome is reported") {
            val sender = fx.member()
            val list = fx.list(sender)
            val msg = fx.message(listId = list, sentBy = sender)
            fx.link(messageId = msg, index = 0, url = "https://example.org/a")
            fx.link(messageId = msg, index = 1, url = "https://example.org/b")
            val subject = fx.member()
            val bystander = fx.member()
            fx.subscribe(listId = list, memberId = subject, clickConsent = true)
            val (subjectDelivery, _) = fx.delivery(messageId = msg, memberId = subject, clickTracked = true)
            val (bystanderDelivery, _) = fx.delivery(messageId = msg, memberId = bystander, clickTracked = true)
            val now = DbClock.nowLocalDateTime()
            MailingTrackingData.recordClick(deliveryLogId = subjectDelivery, linkIndex = 0, now = now)
            MailingTrackingData.recordClick(deliveryLogId = subjectDelivery, linkIndex = 1, now = now)
            MailingTrackingData.recordClick(deliveryLogId = bystanderDelivery, linkIndex = 0, now = now)

            val outcomes =
                transaction {
                    CommunicationPersonalData.eraseMember(
                        memberId = subject,
                        mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                    )
                }

            outcomes.single { it.table == "mailing_link_click" }.rowsDeleted shouldBe 2
            outcomes.single { it.table == "mailing_delivery_log" }.rowsDeleted shouldBe 1
            fx.clicksOf(subjectDelivery) shouldBe emptyMap()
            fx.clicksOf(bystanderDelivery) shouldBe mapOf(0 to 1)
            transaction {
                MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.memberId eq subject }.count()
            } shouldBe 0
            transaction {
                MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.memberId eq bystander }.count()
            } shouldBe 1
        }

        context("V1.9.232: attachments uploaded to mailing messages") {
            fun attach(
                messageId: Uuid,
                uploader: Uuid,
                name: String,
            ) = transaction {
                MailingAttachmentStore.insertInTx(
                    attachmentId = Uuid.random(),
                    messageId = messageId,
                    storageKey = "mailing/$messageId/${Uuid.random()}.bin",
                    fileName = name,
                    nameKey = name.lowercase(),
                    mimeType = "application/pdf",
                    textCharset = null,
                    sizeBytes = 42,
                    sha256 = "0".repeat(64),
                    uploadedBy = uploader,
                    uploadedAt = DbClock.nowLocalDateTime(),
                )
            }

            fun cleanup(messageId: Uuid) = transaction { MailingMessageAttachmentTable.deleteWhere { mailingMessageId eq messageId } }

            test("the table is a covered table of the contributor") {
                (CommunicationPersonalData.coveredTables.contains(MailingMessageAttachmentTable)) shouldBe true
            }

            test("export lists only the member's own uploads (name, type, size, instant) and never the file or its location") {
                val sender = fx.member()
                val other = fx.member()
                val list = fx.list(sender)
                val msg = fx.message(listId = list, sentBy = sender)
                attach(msg, sender, "meine.pdf")
                attach(msg, other, "fremd.pdf")
                try {
                    val entries = transaction { CommunicationPersonalData.exportMember(sender) }["uploadedMailingAttachments"]!!.jsonArray
                    entries.size shouldBe 1
                    val entry = entries.single().jsonObject
                    entry["fileName"]!!.jsonPrimitive.content shouldBe "meine.pdf"
                    entry["mimeType"]!!.jsonPrimitive.content shouldBe "application/pdf"
                    entry["sizeBytes"]!!.jsonPrimitive.content shouldBe "42"
                    entry.keys.none { it.contains("storage", ignoreCase = true) || it.contains("sha", ignoreCase = true) } shouldBe true
                } finally {
                    cleanup(msg)
                }
            }

            test("erasure keeps the rows (part of the communication others received) and reports them as retained") {
                val sender = fx.member()
                val list = fx.list(sender)
                val msg = fx.message(listId = list, sentBy = sender)
                attach(msg, sender, "bleibt.pdf")
                try {
                    val outcomes =
                        transaction {
                            CommunicationPersonalData.eraseMember(memberId = sender, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED)
                        }
                    val outcome = outcomes.single { it.table == "mailing_message_attachment" }
                    outcome.rowsRetained shouldBe 1
                    outcome.rowsDeleted shouldBe 0
                    (outcome.retentionReason != null) shouldBe true
                    transaction {
                        MailingMessageAttachmentTable.selectAll().where { MailingMessageAttachmentTable.mailingMessageId eq msg }.count()
                    } shouldBe 1L
                } finally {
                    cleanup(msg)
                }
            }
        }
        context("V1.9.233: attachments of direct messages") {
            val storageRoot = dmStorageRoot

            fun dmMember(): Uuid = fx.member().also { dmMembers += it }

            /** A sent message from [sender] to [recipient] with one attachment uploaded by [sender]; returns (message, key). */
            fun sentWithAttachment(
                sender: Uuid,
                recipient: Uuid,
                name: String,
            ): Pair<Uuid, String> {
                val key = "dm/$sender/${Uuid.random()}.bin"
                storageRoot.resolve(key).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
                val messageId =
                    transaction {
                        val dto = insertDirectMessage(senderId = sender, recipientId = recipient, body = "Hallo")
                        Uuid.parse(dto.id).also { id ->
                            DirectMessageAttachmentStore.insertPendingInTx(
                                attachmentId = Uuid.random(),
                                uploader = sender,
                                recipient = recipient,
                                storageKey = key,
                                fileName = name,
                                nameKey = name.lowercase(),
                                mimeType = "application/pdf",
                                textCharset = null,
                                sizeBytes = 3,
                                sha256 = "0".repeat(64),
                                createdAt = DbClock.nowLocalDateTime(),
                            )
                            DirectMessageAttachmentStore.attachPendingInTx(
                                ids =
                                    DirectMessageAttachmentTable
                                        .selectAll()
                                        .where {
                                            DirectMessageAttachmentTable.storageKey eq key
                                        }.map { it[DirectMessageAttachmentTable.id] },
                                uploader = sender,
                                recipient = recipient,
                                messageId = id,
                            )
                        }
                    }
                return messageId to key
            }

            fun pending(
                uploader: Uuid,
                recipient: Uuid,
                name: String,
            ): String {
                val key = "dm/$uploader/${Uuid.random()}.bin"
                storageRoot.resolve(key).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(9))
                transaction {
                    DirectMessageAttachmentStore.insertPendingInTx(
                        attachmentId = Uuid.random(),
                        uploader = uploader,
                        recipient = recipient,
                        storageKey = key,
                        fileName = name,
                        nameKey = name.lowercase(),
                        mimeType = "application/pdf",
                        textCharset = null,
                        sizeBytes = 1,
                        sha256 = "0".repeat(64),
                        createdAt = DbClock.nowLocalDateTime(),
                    )
                }
                return key
            }

            fun rowCount(key: String) =
                transaction { DirectMessageAttachmentTable.selectAll().where { DirectMessageAttachmentTable.storageKey eq key }.count() }

            test("the table is a covered table of the contributor") {
                (CommunicationPersonalData.coveredTables.contains(DirectMessageAttachmentTable)) shouldBe true
            }

            test("export lists SENT, RECEIVED and PENDING with name, type, size and instant -- never the file, the key or the hash") {
                val me = dmMember()
                val other = dmMember()
                val (sentMessage, _) = sentWithAttachment(sender = me, recipient = other, name = "gesendet.pdf")
                val (receivedMessage, _) = sentWithAttachment(sender = other, recipient = me, name = "empfangen.pdf")
                pending(uploader = me, recipient = other, name = "offen.pdf")
                pending(uploader = other, recipient = me, name = "fremd-offen.pdf")
                val entries = transaction { CommunicationPersonalData.exportMember(me) }["directMessageAttachments"]!!.jsonArray
                val byName = entries.associateBy { it.jsonObject["fileName"]!!.jsonPrimitive.content }
                byName.keys shouldBe setOf("gesendet.pdf", "empfangen.pdf", "offen.pdf")
                byName
                    .getValue("gesendet.pdf")
                    .jsonObject["direction"]!!
                    .jsonPrimitive.content shouldBe "SENT"
                byName
                    .getValue("gesendet.pdf")
                    .jsonObject["messageId"]!!
                    .jsonPrimitive.content shouldBe sentMessage.toString()
                byName
                    .getValue("empfangen.pdf")
                    .jsonObject["direction"]!!
                    .jsonPrimitive.content shouldBe "RECEIVED"
                byName
                    .getValue("empfangen.pdf")
                    .jsonObject["messageId"]!!
                    .jsonPrimitive.content shouldBe receivedMessage.toString()
                byName
                    .getValue("offen.pdf")
                    .jsonObject["direction"]!!
                    .jsonPrimitive.content shouldBe "PENDING"
                byName
                    .getValue("gesendet.pdf")
                    .jsonObject["sizeBytes"]!!
                    .jsonPrimitive.content shouldBe "3"
                entries.all { e -> e.jsonObject.keys.none { it.contains("storage", true) || it.contains("sha", true) } } shouldBe true
            }

            test("default mode: pending uploads are deleted (row and file), sent attachments stay with the recipient") {
                val me = dmMember()
                val other = dmMember()
                val (_, sentKey) = sentWithAttachment(sender = me, recipient = other, name = "bleibt.pdf")
                val pendingKey = pending(uploader = me, recipient = other, name = "weg.pdf")
                val outcomes = transaction { CommunicationPersonalData.eraseMember(memberId = me, mode = ErasureMode.ANONYMIZE) }
                val outcome = outcomes.single { it.table == "direct_message_attachment" }
                outcome.rowsDeleted shouldBe 1
                outcome.rowsRetained shouldBe 1
                rowCount(pendingKey) shouldBe 0L
                storageRoot.resolve(pendingKey).exists() shouldBe false
                rowCount(sentKey) shouldBe 1L
                storageRoot.resolve(sentKey).exists() shouldBe true
            }

            test("HARD_DELETE: every own upload goes (rows AND files), received attachments of the other person stay") {
                val me = dmMember()
                val other = dmMember()
                val (_, sentKey) = sentWithAttachment(sender = me, recipient = other, name = "meins.pdf")
                val (_, receivedKey) = sentWithAttachment(sender = other, recipient = me, name = "deins.pdf")
                val pendingKey = pending(uploader = me, recipient = other, name = "offen.pdf")
                val outcomes =
                    transaction { CommunicationPersonalData.eraseMember(memberId = me, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) }
                val outcome = outcomes.single { it.table == "direct_message_attachment" }
                outcome.rowsDeleted shouldBe 2
                outcome.rowsRetained shouldBe 0
                listOf(sentKey, pendingKey).forEach {
                    rowCount(it) shouldBe 0L
                    storageRoot.resolve(it).exists() shouldBe false
                }
                rowCount(receivedKey) shouldBe 1L
                storageRoot.resolve(receivedKey).exists() shouldBe true
            }
        }
    })
