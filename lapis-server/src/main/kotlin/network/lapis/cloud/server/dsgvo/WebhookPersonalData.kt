package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.WebhookEndpointTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [WebhookEndpointTable] -- the three member-FK-bearing columns Welle V1.3.2 "Webhooks"
 * (ausgehend) adds (`created_by_member_id`/`updated_by_member_id`/`deactivated_by_member_id`).
 * Retain-with-reason (like [ApiKeyPersonalData], which this class mirrors almost exactly) -- a
 * `webhook_endpoint` row is an organizational administrative artefact ("who configured/changed/
 * deactivated this org-wide outbound integration, and when"), not a per-member access-control note.
 * [export] surfaces `url`/`active`/timestamps, **never `secret_sealed`/`secret_prefix`** -- same
 * "the secret stays a secret-adjacent artefact, not personal data to disclose even to the subject
 * themselves" reasoning [ApiKeyPersonalData] KDoc gives for `tokenHash`.
 *
 * `webhook_delivery` carries NO member FK at all -- see `PersonalDataRegistry.noPersonalDataAllowlist`'s
 * own entry for that table.
 */
object WebhookPersonalData : PersonalDataContributor {
    override val sectionKey = "webhookEndpoints"
    override val displayName = "Webhook-Endpunkte"
    override val coveredTables = setOf(WebhookEndpointTable)

    override fun export(memberId: Uuid) =
        buildJsonArray {
            WebhookEndpointTable
                .selectAll()
                .where {
                    (WebhookEndpointTable.createdByMemberId eq memberId) or
                        (WebhookEndpointTable.updatedByMemberId eq memberId) or
                        (WebhookEndpointTable.deactivatedByMemberId eq memberId)
                }.forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[WebhookEndpointTable.id].toString())
                            put("apiKeyId", row[WebhookEndpointTable.apiKeyId].toString())
                            put("url", row[WebhookEndpointTable.url])
                            put("active", row[WebhookEndpointTable.active])
                            put("createdAt", row[WebhookEndpointTable.createdAt].toString())
                            put("createdByMemberId", row[WebhookEndpointTable.createdByMemberId].toString())
                            put("updatedAt", row[WebhookEndpointTable.updatedAt]?.toString())
                            put("updatedByMemberId", row[WebhookEndpointTable.updatedByMemberId]?.toString())
                            put("deactivatedAt", row[WebhookEndpointTable.deactivatedAt]?.toString())
                            put("deactivatedByMemberId", row[WebhookEndpointTable.deactivatedByMemberId]?.toString())
                            put("deactivationReason", row[WebhookEndpointTable.deactivationReason])
                        },
                    )
                }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total =
            WebhookEndpointTable
                .selectAll()
                .where {
                    (WebhookEndpointTable.createdByMemberId eq memberId) or
                        (WebhookEndpointTable.updatedByMemberId eq memberId) or
                        (WebhookEndpointTable.deactivatedByMemberId eq memberId)
                }.count()
        return listOf(
            TableErasureOutcome(
                table = "webhook_endpoint",
                rowsRetained = total.toInt(),
                retentionReason =
                    "Organisatorische Nachvollziehbarkeit, wer einen Org-weiten Webhook konfiguriert/geaendert/" +
                        "deaktiviert hat -- die Zeile selbst traegt keine PII ausser der Mitglieds-Referenz.",
            ),
        )
    }
}
