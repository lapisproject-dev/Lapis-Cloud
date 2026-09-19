package network.lapis.cloud.server.ai.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.ai.FakeLlmClient
import network.lapis.cloud.server.ai.llm.LlmResult
import network.lapis.cloud.server.ai.operationalAiConfig
import network.lapis.cloud.server.ai.qa.StatuteQaPipeline
import network.lapis.cloud.server.ai.retrieval.RetrievedChunk
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AiCallAuditTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val SECRET_QUESTION = "Geheimfrage zum Sonderbeitrag Ostfriesland?"

class AiCallAuditRecorderTest :
    FunSpec({
        val fixtures = AiTestFixtures()
        val recorder = DbAiCallAuditRecorder()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { fixtures.dispose() }

        fun rows(memberId: Uuid) = transaction { AiCallAuditTable.selectAll().where { AiCallAuditTable.memberId eq memberId }.toList() }

        test("one row is written for every outcome") {
            val member = fixtures.member()
            AiCallOutcome.entries.forEach { outcome ->
                recorder.record(
                    AiCallAuditEntry(
                        memberId = member,
                        agentType = "STATUTE_QA",
                        provider = "anthropic",
                        model = "m",
                        inputHash = sha256Hex("in-$outcome"),
                        outputHash = if (outcome == AiCallOutcome.ANSWERED) sha256Hex("out") else null,
                        tokensIn = 1,
                        tokensOut = 2,
                        toolsCalled = listOf("KNOWLEDGE_SEARCH"),
                        outcome = outcome,
                        retrievedChunkCount = 3,
                    ),
                )
            }
            val stored = rows(member)
            stored shouldHaveSize AiCallOutcome.entries.size
            stored.map { it[AiCallAuditTable.outcome] }.toSet() shouldBe AiCallOutcome.entries.toSet()
        }

        test("a full pipeline run stores hashes of the REDACTED question and never any clear text") {
            val member = fixtures.member()
            val chunk =
                RetrievedChunk(
                    chunkId = Uuid.random(),
                    documentId = Uuid.random(),
                    documentVersionId = Uuid.random(),
                    documentTitle = "Satzung",
                    versionNumber = 1,
                    sectionLabel = "§ 1",
                    pageNumber = null,
                    text = "Inhalt",
                    score = 1.0,
                )
            val pipeline =
                StatuteQaPipeline(
                    retriever = { _, _, _ -> listOf(chunk) },
                    llmClient = FakeLlmClient { LlmResult.Success(text = "Antwort.\nQUELLEN: 1", tokensIn = 4, tokensOut = 2) },
                    auditSink = recorder,
                    config = operationalAiConfig(),
                )
            pipeline.answer(
                memberId = member,
                question = "$SECRET_QUESTION Kontakt: a.b@example.org",
                allowedLevels = listOf(DocumentAccessLevel.PUBLIC_MEMBERS),
            )
            val row = rows(member).single()
            row[AiCallAuditTable.inputHash] shouldBe sha256Hex("$SECRET_QUESTION Kontakt: [EMAIL]")
            row[AiCallAuditTable.outputHash] shouldBe sha256Hex("Antwort.\nQUELLEN: 1")
            // No column contains the question text, the address or the answer.
            val everything = AiCallAuditTable.columns.joinToString("|") { row[it].toString() }
            everything shouldNotContain "Geheimfrage"
            everything shouldNotContain "example.org"
            everything shouldNotContain "Antwort"
            row[AiCallAuditTable.provider] shouldBe "anthropic"
            row[AiCallAuditTable.retrievedChunkCount] shouldBe 1
        }

        test("hashing is stable under concurrency (a fresh MessageDigest per call)") {
            val pool = Executors.newFixedThreadPool(16)
            try {
                val results = (1..200).map { pool.submit<String> { sha256Hex("same input") } }.map { it.get(10, TimeUnit.SECONDS) }
                results.toSet() shouldHaveSize 1
                results.first() shouldBe sha256Hex("same input")
                results.first().length shouldBe 64
            } finally {
                pool.shutdownNow()
            }
        }

        test("an audit failure never propagates to the caller") {
            // member id that violates the FK -> the insert fails, record() must swallow it.
            recorder.record(
                AiCallAuditEntry(
                    memberId = Uuid.random(),
                    agentType = "STATUTE_QA",
                    provider = "p",
                    model = "m",
                    inputHash = sha256Hex("x"),
                    outputHash = null,
                    tokensIn = null,
                    tokensOut = null,
                    toolsCalled = emptyList(),
                    outcome = AiCallOutcome.NOTHING_FOUND,
                    retrievedChunkCount = 0,
                ),
            )
        }
    })
