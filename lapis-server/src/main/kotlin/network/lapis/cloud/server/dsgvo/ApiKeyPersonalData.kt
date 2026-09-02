package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.ApiKeyTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [ApiKeyTable] -- the two member-FK-bearing columns V1.3.1 "API-Fundament, lesend" adds
 * (`created_by_member_id`/`revoked_by_member_id`). Retain-with-reason (like [AuditLogPersonalData]),
 * NOT hard-delete (unlike [SessionPersonalData]) -- an `api_key` row is an organizational
 * administrative artefact ("who issued/revoked this org-wide API access, and when"), not a
 * per-member access-control note. The row itself carries no PII beyond the two member references
 * -- [export] therefore surfaces `label`/`keyPrefix`/timestamps, **never `tokenHash`** (see
 * `network.lapis.cloud.server.security.ApiKeyStore.ApiKeyRow` KDoc -- the hash stays a secret-
 * adjacent artefact, not personal data to disclose even to the subject themselves).
 */
object ApiKeyPersonalData : PersonalDataContributor {
    override val sectionKey = "apiKeys"
    override val displayName = "API-Schlüssel"
    override val coveredTables = setOf(ApiKeyTable)

    override fun export(memberId: Uuid) =
        buildJsonArray {
            ApiKeyTable
                .selectAll()
                .where { (ApiKeyTable.createdByMemberId eq memberId) or (ApiKeyTable.revokedByMemberId eq memberId) }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[ApiKeyTable.id].toString())
                            put("label", row[ApiKeyTable.label])
                            put("keyPrefix", row[ApiKeyTable.keyPrefix])
                            put("createdAt", row[ApiKeyTable.createdAt].toString())
                            put("createdByMemberId", row[ApiKeyTable.createdByMemberId].toString())
                            put("expiresAt", row[ApiKeyTable.expiresAt]?.toString())
                            put("revokedAt", row[ApiKeyTable.revokedAt]?.toString())
                            put("revokedByMemberId", row[ApiKeyTable.revokedByMemberId]?.toString())
                        },
                    )
                }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total =
            ApiKeyTable
                .selectAll()
                .where { (ApiKeyTable.createdByMemberId eq memberId) or (ApiKeyTable.revokedByMemberId eq memberId) }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "api_key",
                rowsRetained = total.toInt(),
                retentionReason =
                    "Organisatorische Nachvollziehbarkeit, wer Org-weite API-Zugaenge ausgestellt/" +
                        "widerrufen hat -- die Zeile selbst traegt keine PII ausser der Mitglieds-Referenz.",
            ),
        )
    }
}
