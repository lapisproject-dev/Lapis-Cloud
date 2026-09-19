package network.lapis.cloud.server.ai.audit

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AiCallAuditTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** SHA-256 hex digest with a FRESH [MessageDigest] per call ([MessageDigest] is not thread-safe). */
internal fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

/**
 * Writes [AiCallAuditEntry] rows to `ai_call_audit` in their own transaction.
 *
 * **Deliberately NOT routed through `AuditLogRecorder`.** That is the GoBD hash-chained mutation
 * trail; this is a usage/traceability log, and pushing every model call through the global chain
 * lock would serialize all AI traffic behind it for no benefit. Retention/DSGVO: see
 * `AiAssistantPersonalData` (member reference is nulled on erasure, the hashes stay).
 */
internal class DbAiCallAuditRecorder : AiCallAuditSink {
    override fun record(entry: AiCallAuditEntry) {
        val now = DbClock.nowLocalDateTime()
        try {
            transaction {
                AiCallAuditTable.insert {
                    it[id] = Uuid.random()
                    it[occurredAt] = now
                    it[memberId] = entry.memberId
                    it[agentType] = entry.agentType.take(40)
                    it[provider] = entry.provider.take(30)
                    it[model] = entry.model.take(120)
                    it[inputHash] = entry.inputHash
                    it[outputHash] = entry.outputHash
                    it[tokensIn] = entry.tokensIn
                    it[tokensOut] = entry.tokensOut
                    it[toolsCalled] = entry.toolsCalled.joinToString(separator = ",").take(200)
                    it[outcome] = entry.outcome
                    it[retrievedChunkCount] = entry.retrievedChunkCount
                }
            }
        } catch (e: Exception) {
            logger.warn { "AI-Audit-Zeile konnte nicht geschrieben werden (${e::class.simpleName})." }
        }
    }
}
