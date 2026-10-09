package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.outbox.MailOutbox
import network.lapis.cloud.server.mail.outbox.MailRecipientHasher
import network.lapis.cloud.server.mail.outbox.OutboundMail
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.81 -- [MailOutboxPersonalData]: Art. 15 export and Art. 17 erasure of OPEN outbox rows, found through the HMAC lookup hash
 * (never by decrypting), for a member's current address, the pending address of an open e-mail change, and a CRM contact.
 */
class MailOutboxPersonalDataTest :
    FunSpec({
        val key = ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 3).toByte() }
        val hasher = MailRecipientHasher(key)
        val createdMemberIds = mutableListOf<Uuid>()
        val createdContactIds = mutableListOf<Uuid>()
        val outbox =
            MailOutbox(
                secretBox = SecretBox(key),
                lookupHasher = hasher,
                budget = MailBudgetStore(null),
                transport = NoOpMailTransport(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )

        beforeSpec {
            DatabaseConfig.connect()
            MailOutboxPersonalData.install(hasher)
        }
        beforeTest { transaction { MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() } } }
        afterSpec {
            MailOutboxPersonalData.install(null)
            transaction {
                MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() }
                MemberEmailChangeTable.deleteWhere { MemberEmailChangeTable.memberId inList createdMemberIds }
                CrmContactTable.deleteWhere { CrmContactTable.id inList createdContactIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Outbox-Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        fun queue(
            to: String,
            purpose: String = "password-reset",
        ) = runBlocking {
            outbox.persistAll(
                listOf(OutboundMail(to = to, subject = "Betreff", plainTextBody = "Text", htmlBody = "<p>Text</p>", purpose = purpose)),
            )
        }

        fun count(vararg statuses: String): Int =
            transaction {
                MailOutboxTable
                    .selectAll()
                    .where { MailOutboxTable.status inList statuses.toList() }
                    .count()
                    .toInt()
            }

        test("the contributor handles members and CRM contacts, is registered FIRST, and covers exactly mail_outbox") {
            MailOutboxPersonalData.handledSubjects shouldBe setOf(DsgvoSubjectKind.MEMBER, DsgvoSubjectKind.CRM_CONTACT)
            MailOutboxPersonalData.coveredTables.map { it.tableName } shouldBe listOf("mail_outbox")
            PersonalDataRegistry.contributors.first() shouldBe MailOutboxPersonalData
        }

        test("export: a member's open mail is listed (purpose, status, creation time) -- never a payload, never another person's mail") {
            val email = "export-${Uuid.random()}@example.org"
            val member = createMember(email)
            queue(email)
            queue("someone-else-${Uuid.random()}@example.org", purpose = "event-registration")

            val export = transaction { MailOutboxPersonalData.export(DataSubject.Member(member)) }.jsonArray

            export.size shouldBe 1
            val entry = export.single().jsonObject
            entry["purpose"]!!.jsonPrimitive.content shouldBe "password-reset"
            entry["status"]!!.jsonPrimitive.content shouldBe "QUEUED"
            entry.keys shouldBe setOf("purpose", "status", "createdAt")
            export.toString() shouldNotContain email
            export.toString() shouldNotContain "Betreff"
        }

        test("the pending address of an OPEN e-mail change is found too (its confirmation mail goes to the NEW address)") {
            val member = createMember("old-${Uuid.random()}@example.org")
            val pending = "new-${Uuid.random()}@example.org"
            val now = DbClock.nowLocalDateTime()
            transaction {
                MemberEmailChangeTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = member
                    it[openMemberId] = member
                    it[pendingEmail] = pending
                    it[kind] = "SELF"
                    it[status] = "PENDING"
                    it[createdAt] = now
                    it[expiresAt] = now
                }
            }
            queue(pending, purpose = "email-change-confirm")

            transaction { MailOutboxPersonalData.export(DataSubject.Member(member)) }.jsonArray.size shouldBe 1
            transaction { MailOutboxPersonalData.erase(subject = DataSubject.Member(member), mode = ErasureMode.ANONYMIZE) }
                .single()
                .rowsDeleted shouldBe 1
            count("QUEUED") shouldBe 0
        }

        test("erasure deletes the member's OPEN rows and leaves other people's rows and final rows alone") {
            val email = "erase-${Uuid.random()}@example.org"
            val member = createMember(email)
            queue(email)
            queue(email, purpose = "event-registration")
            queue("bystander-${Uuid.random()}@example.org")
            // a FINAL row of the same person holds no payload and no hash -- nothing to find, nothing to erase
            transaction {
                MailOutboxTable.insert {
                    it[id] = Uuid.random()
                    it[purpose] = "password-reset"
                    it[priority] = 0.toShort()
                    it[status] = "SENT"
                    it[attemptCount] = 1
                    it[nextAttemptAt] = DbClock.nowLocalDateTime()
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[finishedAt] = DbClock.nowLocalDateTime()
                }
            }

            val outcome = transaction { MailOutboxPersonalData.erase(subject = DataSubject.Member(member), mode = ErasureMode.ANONYMIZE) }

            outcome.single().table shouldBe "mail_outbox"
            outcome.single().rowsDeleted shouldBe 2
            count("QUEUED") shouldBe 1
            count("SENT") shouldBe 1
        }

        test("a CRM contact's address works the same way") {
            val email = "crm-${Uuid.random()}@example.org"
            val contactId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val creator = createMember("crm-creator-${Uuid.random()}@example.org")
            transaction {
                CrmContactTable.insert {
                    it[id] = contactId
                    it[displayName] = "Kontakt"
                    it[CrmContactTable.email] = email
                    it[createdBy] = creator
                    it[createdAt] = now
                    it[contactType] = CrmContactType.INTERESSENT
                    it[lawfulBasis] = CrmLawfulBasis.LEGITIMATE_INTEREST
                    it[retentionReviewDueAt] = now
                }
            }
            createdContactIds += contactId
            queue(email, purpose = "event-registration")

            transaction { MailOutboxPersonalData.export(DataSubject.CrmContact(contactId)) }.jsonArray.size shouldBe 1
            transaction {
                MailOutboxPersonalData.erase(
                    subject = DataSubject.CrmContact(contactId),
                    mode = ErasureMode.ANONYMIZE,
                )
            }.single().rowsDeleted shouldBe
                1
            count("QUEUED") shouldBe 0
        }

        test("without an installed hasher (no durable queue) nothing is exported and nothing is erased") {
            val email = "nohasher-${Uuid.random()}@example.org"
            val member = createMember(email)
            queue(email)
            MailOutboxPersonalData.install(null)
            try {
                transaction { MailOutboxPersonalData.export(DataSubject.Member(member)) }.jsonArray.size shouldBe 0
                transaction { MailOutboxPersonalData.erase(subject = DataSubject.Member(member), mode = ErasureMode.ANONYMIZE) } shouldBe
                    emptyList()
            } finally {
                MailOutboxPersonalData.install(hasher)
            }
            count("QUEUED") shouldBe 1
        }

        test("a member whose address was already anonymized would be missed -- which is why this contributor runs before Foundation") {
            val email = "order-${Uuid.random()}@example.org"
            val member = createMember(email)
            queue(email)
            transaction {
                MemberTable.update(
                    { MemberTable.id eq member },
                ) { it[MemberTable.email] = "anonymized-${Uuid.random()}@invalid" }
            }

            transaction {
                MailOutboxPersonalData.erase(subject = DataSubject.Member(member), mode = ErasureMode.ANONYMIZE)
            }.single().rowsDeleted shouldBe
                0
            count("QUEUED") shouldBe 1 // documented consequence; the registry order prevents it
            PersonalDataRegistry.contributors.indexOf(MailOutboxPersonalData) shouldBe 0
        }
    })
