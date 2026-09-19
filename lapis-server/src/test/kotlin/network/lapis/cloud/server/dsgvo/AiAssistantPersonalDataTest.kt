package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.ai.audit.AiCallAuditEntry
import network.lapis.cloud.server.ai.audit.AiCallOutcome
import network.lapis.cloud.server.ai.audit.DbAiCallAuditRecorder
import network.lapis.cloud.server.ai.audit.sha256Hex
import network.lapis.cloud.server.ai.optin.AiMemberOptInStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AiCallAuditTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.AiMemberOptInTable
import network.lapis.cloud.shared.domain.AiFeature
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * [PersonalDataCoverageTest] only proves the three AI tables with a member FK are covered by SOME
 * contributor. This file pins [AiAssistantPersonalData]'s own claims: hashes never exported, opt-in
 * rows hard-deleted, audit and release rows retained with their member reference nulled.
 */
class AiAssistantPersonalDataTest :
    FunSpec({
        val fixtures = AiTestFixtures()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { fixtures.dispose() }

        fun seed(): Pair<kotlin.uuid.Uuid, network.lapis.cloud.server.ai.TestDocument> {
            val member = fixtures.member()
            val doc = fixtures.document(author = member)
            fixtures.release(document = doc, by = member)
            AiMemberOptInStore.set(memberId = member, feature = AiFeature.STATUTE_QA, enabled = true)
            DbAiCallAuditRecorder().record(
                AiCallAuditEntry(
                    memberId = member,
                    agentType = "STATUTE_QA",
                    provider = "anthropic",
                    model = "m",
                    inputHash = sha256Hex("in"),
                    outputHash = sha256Hex("out"),
                    tokensIn = 5,
                    tokensOut = 6,
                    toolsCalled = listOf("KNOWLEDGE_SEARCH"),
                    outcome = AiCallOutcome.ANSWERED,
                    retrievedChunkCount = 2,
                ),
            )
            return member to doc
        }

        test("the contributor is registered and covers exactly the three member-FK tables") {
            (AiAssistantPersonalData in PersonalDataRegistry.contributors) shouldBe true
            AiAssistantPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("ai_member_opt_in", "ai_call_audit", "ai_knowledge_release")
        }

        test("export lists opt-ins, calls and released documents but never a hash") {
            val (member, doc) = seed()
            val export = transaction { AiAssistantPersonalData.exportMember(member) }.shouldBeInstanceOf<JsonObject>()
            val optIn = export["optIns"]!!.jsonArray.single().jsonObject
            optIn["feature"].toString() shouldBe "\"STATUTE_QA\""
            val call = export["aiCalls"]!!.jsonArray.single().jsonObject
            call["outcome"].toString() shouldBe "\"ANSWERED\""
            listOf("inputHash", "outputHash", "input_hash", "output_hash", "hash").forEach { call.containsKey(it) shouldBe false }
            export["releasedDocuments"]!!
                .jsonArray
                .single()
                .jsonObject["documentTitle"]
                .toString() shouldBe "\"${doc.title}\""
        }

        test("erasure hard-deletes opt-ins and nulls the member reference on audit and release rows") {
            val (member, doc) = seed()
            val outcomes = transaction { AiAssistantPersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }
            val byTable = outcomes.associateBy { it.table }
            byTable.getValue("ai_member_opt_in").rowsDeleted shouldBe 1
            byTable.getValue("ai_call_audit").rowsAnonymized shouldBe 1
            byTable.getValue("ai_knowledge_release").rowsAnonymized shouldBe 1
            transaction {
                AiMemberOptInTable.selectAll().where { AiMemberOptInTable.memberId eq member }.count() shouldBe 0L
                AiCallAuditTable.selectAll().where { AiCallAuditTable.memberId eq member }.count() shouldBe 0L
                // The rows themselves are retained, only de-identified.
                val release = AiKnowledgeReleaseTable.selectAll().where { AiKnowledgeReleaseTable.documentId eq doc.id }.single()
                release[AiKnowledgeReleaseTable.releasedBy] shouldBe null
                (AiCallAuditTable.selectAll().where { AiCallAuditTable.memberId.isNull() }.count() >= 1L) shouldBe true
            }
        }
    })
