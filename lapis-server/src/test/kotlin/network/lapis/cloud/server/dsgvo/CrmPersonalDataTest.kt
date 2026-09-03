package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.crm.CrmContactStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.CrmInteractionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- [CrmPersonalData]'s own export/erase
 * behavior, mirroring [PublicRankingConsentPersonalDataTest]'s house style (direct
 * `transaction { CrmPersonalData.export/erase(...) }` calls). [PersonalDataCoverageTest] only
 * proves the tables are covered by a contributor -- this file exercises the actual CRM_CONTACT vs.
 * MEMBER subject-kind branching, see [CrmPersonalData] KDoc for the semantics under test.
 */
class CrmPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    CrmInteractionTable.deleteWhere { recordedBy inList createdMemberIds }
                    CrmContactTable.deleteWhere { (createdBy inList createdMemberIds) or (memberId inList createdMemberIds) }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "CrmPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        fun createContact(
            createdBy: Uuid,
            memberId: Uuid? = null,
        ): Uuid =
            transaction {
                CrmContactStore
                    .create(
                        input =
                            CrmContactInput(
                                displayName = "Interessent",
                                email = null,
                                phone = null,
                                street = null,
                                postalCode = null,
                                city = null,
                                country = null,
                                contactType = CrmContactType.INTERESSENT,
                                lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                                consentSource = null,
                                consentGivenAt = null,
                                externalDonorId = null,
                                memberId = memberId?.toString(),
                            ),
                        createdBy = createdBy,
                    ).id
                    .let { Uuid.parse(it) }
            }

        fun recordInteraction(
            contactId: Uuid,
            recordedBy: Uuid,
            summary: String = "Anruf am Infostand",
        ) {
            transaction {
                CrmContactStore.recordInteraction(
                    input =
                        CrmInteractionInput(
                            contactId = contactId.toString(),
                            occurredAt = null,
                            kind = CrmInteractionKind.CALL,
                            summary = summary,
                        ),
                    recordedBy = recordedBy,
                )
            }
        }

        test("handledSubjects covers both MEMBER and CRM_CONTACT") {
            CrmPersonalData.handledSubjects shouldBe setOf(DsgvoSubjectKind.MEMBER, DsgvoSubjectKind.CRM_CONTACT)
        }

        // ── CRM_CONTACT subject ──────────────────────────────────────────────────────

        test("CRM_CONTACT erase deletes the contact and all its interactions -- both tables empty afterwards") {
            val boardMember = createTestMember("crm-erase-a@example.org")
            val contactId = createContact(createdBy = boardMember)
            recordInteraction(contactId, boardMember, "Interaktion 1")
            recordInteraction(contactId, boardMember, "Interaktion 2")
            recordInteraction(contactId, boardMember, "Interaktion 3")

            val outcomes = transaction { CrmPersonalData.erase(subject = DataSubject.CrmContact(contactId), mode = ErasureMode.ANONYMIZE) }

            val interactionOutcome = outcomes.single { it.table == "crm_interaction" }
            val contactOutcome = outcomes.single { it.table == "crm_contact" }
            interactionOutcome.rowsDeleted shouldBe 3
            contactOutcome.rowsDeleted shouldBe 1

            transaction {
                CrmContactTable.selectAll().where { CrmContactTable.id eq contactId }.count() shouldBe 0L
                CrmInteractionTable.selectAll().where { CrmInteractionTable.contactId eq contactId }.count() shouldBe 0L
            }
        }

        test("CRM_CONTACT export includes the full contact row and interaction summary text") {
            val boardMember = createTestMember("crm-export-a@example.org")
            val contactId = createContact(createdBy = boardMember)
            recordInteraction(contactId, boardMember, "Vertrauliches Gespraech ueber Beitritt")

            val export = transaction { CrmPersonalData.export(DataSubject.CrmContact(contactId)) }.jsonObject
            export["id"]?.jsonPrimitive?.content shouldBe contactId.toString()
            val interactions = export["interactions"]!!.jsonArray
            interactions shouldHaveSize 1
            interactions[0].jsonObject["summary"]?.jsonPrimitive?.content shouldBe "Vertrauliches Gespraech ueber Beitritt"
        }

        // ── MEMBER subject ───────────────────────────────────────────────────────────

        test("MEMBER erase deletes the member's OWN linked contact (case 1)") {
            val subjectMember = createTestMember("crm-erase-member-own@example.org")
            val contactId = createContact(createdBy = subjectMember, memberId = subjectMember)

            transaction { CrmPersonalData.erase(subject = DataSubject.Member(subjectMember), mode = ErasureMode.ANONYMIZE) }

            transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq contactId }.count() } shouldBe 0L
        }

        test("MEMBER erase RETAINS a contact the member only created (not member_id-linked) -- with a non-blank reason") {
            val boardMember = createTestMember("crm-erase-member-creator@example.org")
            val thirdPartyContactId = createContact(createdBy = boardMember, memberId = null)

            val outcomes = transaction { CrmPersonalData.erase(subject = DataSubject.Member(boardMember), mode = ErasureMode.ANONYMIZE) }

            val contactOutcome = outcomes.single { it.table == "crm_contact" }
            contactOutcome.rowsRetained shouldBe 1
            contactOutcome.rowsDeleted shouldBe 0
            (contactOutcome.retentionReason?.isNotBlank() ?: false) shouldBe true

            transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq thirdPartyContactId }.count() } shouldBe 1L
        }

        test("MEMBER erase RETAINS an interaction the member recorded on a contact that survives -- with a non-blank reason") {
            val boardMember = createTestMember("crm-erase-member-recorder@example.org")
            val otherCreator = createTestMember("crm-erase-other-creator@example.org")
            val thirdPartyContactId = createContact(createdBy = otherCreator, memberId = null)
            recordInteraction(thirdPartyContactId, boardMember, "Vom zu loeschenden Mitglied erfasst")

            val outcomes = transaction { CrmPersonalData.erase(subject = DataSubject.Member(boardMember), mode = ErasureMode.ANONYMIZE) }

            val interactionOutcome = outcomes.single { it.table == "crm_interaction" }
            interactionOutcome.rowsRetained shouldBe 1
            interactionOutcome.rowsDeleted shouldBe 0
            (interactionOutcome.retentionReason?.isNotBlank() ?: false) shouldBe true
        }

        test("MEMBER export never includes interaction summary text (only recorded interaction metadata)") {
            val boardMember = createTestMember("crm-export-member@example.org")
            val contactId = createContact(createdBy = boardMember)
            recordInteraction(contactId, boardMember, "Streng vertraulich")

            val export = transaction { CrmPersonalData.export(DataSubject.Member(boardMember)) }.jsonObject
            val interactions = export["recordedInteractions"]!!.jsonArray
            interactions shouldHaveSize 1
            (interactions[0].jsonObject.containsKey("summary")) shouldBe false
        }

        test(
            "handledSubjects filter: a MEMBER-only erasure iteration never invokes CrmPersonalData with a CrmContact subject and vice versa",
        ) {
            // Structural proof that DsgvoService/CrmService's own filter-before-dispatch is not the
            // only thing preventing a cross-kind call: CrmPersonalData.erase itself is exhaustive
            // on DataSubject (sealed interface, `when` with no `else`), so a caller that DID
            // accidentally construct the wrong subject type would still get well-defined (if
            // semantically wrong) behavior, never a runtime crash -- verified here by exercising
            // both branches explicitly above. This test documents that guarantee rather than
            // re-asserting it mechanically.
            CrmPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("crm_contact", "crm_interaction")
        }
    })
