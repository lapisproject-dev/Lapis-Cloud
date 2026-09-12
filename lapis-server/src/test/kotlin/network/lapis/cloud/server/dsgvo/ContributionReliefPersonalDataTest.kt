package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- exercises [ContributionReliefPersonalData] directly
 * (no HTTP layer needed), same house style [MemberHonorPersonalDataTest] establishes. Own
 * fixtures (fresh members per test), cleaned up in `afterSpec`.
 */
class ContributionReliefPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction { ContributionReliefRequestTable.deleteAll() }
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Fixture Mitglied ${id.toString().take(6)}"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[anonymizedAt] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertRequest(
            subjectId: Uuid,
            requestedById: Uuid,
            decidedById: Uuid?,
            status: ContributionReliefStatus = ContributionReliefStatus.REQUESTED,
            reasonText: String? = "Vertrauliche Angabe zur Gesundheit",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionReliefRequestTable.insert {
                    it[ContributionReliefRequestTable.id] = id
                    it[ContributionReliefRequestTable.subjectMemberId] = subjectId
                    it[kind] = ContributionReliefKind.EXEMPTION
                    it[ContributionReliefRequestTable.status] = status
                    it[reasonCategory] = ContributionReliefReason.ILLNESS_DISABILITY
                    it[ContributionReliefRequestTable.reasonText] = reasonText
                    it[exemptionFrom] = LocalDate(2027, 1, 1)
                    it[exemptionUntil] = LocalDate(2027, 12, 31)
                    it[requestedAt] = LocalDateTime(2027, 1, 1, 0, 0, 0)
                    it[requestedBy] = requestedById
                    it[decidedBy] = decidedById
                    it[decisionNote] = if (decidedById != null) "Entscheidungsnotiz" else null
                    it[activeRequestKey] = null
                }
            }
            return id
        }

        test("coveredTables covers exactly contribution_relief_request") {
            ContributionReliefPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("contribution_relief_request")
        }

        test("exportMember includes rows in all three roles (subject/requestedBy/decidedBy), correctly flagged") {
            val subject = createMember(email = "crr-export-subject-${Uuid.random()}@example.org")
            val requester = createMember(email = "crr-export-requester-${Uuid.random()}@example.org")
            val decider = createMember(email = "crr-export-decider-${Uuid.random()}@example.org")
            val requestId = insertRequest(subject, requester, decider, status = ContributionReliefStatus.EXECUTED)

            val exportedForSubject = transaction { ContributionReliefPersonalData.exportMember(subject) }
            val subjectEntry =
                exportedForSubject["contributionReliefRequests"]!!
                    .jsonArray
                    .single { it.jsonObject["id"]!!.jsonPrimitive.content == requestId.toString() }
            subjectEntry.jsonObject["subjectRoleSubject"]!!.jsonPrimitive.content shouldBe "true"
            subjectEntry.jsonObject["subjectRoleRequestedBy"]!!.jsonPrimitive.content shouldBe "false"
            subjectEntry.jsonObject["subjectRoleDecidedBy"]!!.jsonPrimitive.content shouldBe "false"

            val exportedForRequester = transaction { ContributionReliefPersonalData.exportMember(requester) }
            val requesterEntry =
                exportedForRequester["contributionReliefRequests"]!!
                    .jsonArray
                    .single { it.jsonObject["id"]!!.jsonPrimitive.content == requestId.toString() }
            requesterEntry.jsonObject["subjectRoleRequestedBy"]!!.jsonPrimitive.content shouldBe "true"

            val exportedForDecider = transaction { ContributionReliefPersonalData.exportMember(decider) }
            val deciderEntry =
                exportedForDecider["contributionReliefRequests"]!!
                    .jsonArray
                    .single { it.jsonObject["id"]!!.jsonPrimitive.content == requestId.toString() }
            deciderEntry.jsonObject["subjectRoleDecidedBy"]!!.jsonPrimitive.content shouldBe "true"

            val unrelated = createMember(email = "crr-export-unrelated-${Uuid.random()}@example.org")
            val exportedForUnrelated = transaction { ContributionReliefPersonalData.exportMember(unrelated) }
            (
                exportedForUnrelated["contributionReliefRequests"]!!.jsonArray.any {
                    it.jsonObject["id"]!!.jsonPrimitive.content == requestId.toString()
                }
            ) shouldBe false
        }

        test(
            "exportMember exposes reasonText only to the SUBJECT role, never to a requester/decider exporting " +
                "their own data (Review fix: self-service export used to leak a third party's Art. 9 health " +
                "disclosure to whoever requested/decided the request, bypassing the exact role check toDto " +
                "applies over the RPC surface)",
        ) {
            val subject = createMember(email = "crr-export-reasontext-subject-${Uuid.random()}@example.org")
            val requester = createMember(email = "crr-export-reasontext-requester-${Uuid.random()}@example.org")
            val decider = createMember(email = "crr-export-reasontext-decider-${Uuid.random()}@example.org")
            val requestId =
                insertRequest(
                    subject,
                    requester,
                    decider,
                    status = ContributionReliefStatus.EXECUTED,
                    reasonText = "seit der Chemotherapie arbeitsunfähig",
                )

            fun reasonTextFor(exportingMemberId: Uuid): kotlinx.serialization.json.JsonElement =
                transaction { ContributionReliefPersonalData.exportMember(exportingMemberId) }["contributionReliefRequests"]!!
                    .jsonArray
                    .single { it.jsonObject["id"]!!.jsonPrimitive.content == requestId.toString() }
                    .jsonObject["reasonText"]!!

            reasonTextFor(subject).jsonPrimitive.content shouldBe "seit der Chemotherapie arbeitsunfähig"
            reasonTextFor(requester) shouldBe kotlinx.serialization.json.JsonNull
            reasonTextFor(decider) shouldBe kotlinx.serialization.json.JsonNull
        }

        test("eraseMember nulls reasonText for the SUBJECT role in BOTH ErasureModes, retains decisionNote/kind/status") {
            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val subject = createMember(email = "crr-erase-subject-${Uuid.random()}@example.org")
                val requester = createMember(email = "crr-erase-requester-${Uuid.random()}@example.org")
                val decider = createMember(email = "crr-erase-decider-${Uuid.random()}@example.org")
                val requestId =
                    insertRequest(subject, requester, decider, status = ContributionReliefStatus.EXECUTED, reasonText = "Wird geleert")

                val outcomes = transaction { ContributionReliefPersonalData.eraseMember(memberId = subject, mode = mode) }
                outcomes.size shouldBe 1
                val outcome = outcomes.single()
                outcome.table shouldBe "contribution_relief_request"
                outcome.rowsAnonymized shouldBe 1

                val row =
                    transaction {
                        ContributionReliefRequestTable
                            .selectAll()
                            .where { ContributionReliefRequestTable.id eq requestId }
                            .single()
                    }
                row[ContributionReliefRequestTable.reasonText] shouldBe null
                // reasonRedactedAt is stamped here too, not just by ContributionReliefRedaction's
                // automatic 12-month sweep -- it is a factual "when was this text actually nulled"
                // timestamp, true regardless of WHICH of the two independent mechanisms did it.
                (row[ContributionReliefRequestTable.reasonRedactedAt] != null) shouldBe true
                row[ContributionReliefRequestTable.decisionNote] shouldBe "Entscheidungsnotiz"
                row[ContributionReliefRequestTable.kind] shouldBe ContributionReliefKind.EXEMPTION
                row[ContributionReliefRequestTable.status] shouldBe ContributionReliefStatus.EXECUTED
            }
        }

        test("eraseMember does NOT null reasonText when erasing the REQUESTED-BY or DECIDED-BY role only") {
            val subject = createMember(email = "crr-erase-subject2-${Uuid.random()}@example.org")
            val requester = createMember(email = "crr-erase-requester2-${Uuid.random()}@example.org")
            val decider = createMember(email = "crr-erase-decider2-${Uuid.random()}@example.org")
            val requestId = insertRequest(subject, requester, decider, reasonText = "Bleibt stehen")

            val requesterOutcome =
                transaction {
                    ContributionReliefPersonalData.eraseMember(memberId = requester, mode = ErasureMode.ANONYMIZE)
                }.single()
            requesterOutcome.rowsAnonymized shouldBe 0
            (requesterOutcome.rowsRetained >= 1) shouldBe true

            val row =
                transaction { ContributionReliefRequestTable.selectAll().where { ContributionReliefRequestTable.id eq requestId }.single() }
            row[ContributionReliefRequestTable.reasonText] shouldBe "Bleibt stehen"
        }
    })
