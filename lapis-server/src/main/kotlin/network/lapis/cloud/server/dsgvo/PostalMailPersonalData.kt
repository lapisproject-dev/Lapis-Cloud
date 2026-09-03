package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [PostalDeliveryLogTable] -- the only member-FK-bearing table of the V0.4.2 Letterxpress
 * postal-mail dispatch domain (`postal_delivery_log.recipient_member_id`).
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- no field is ever cleared or
 * anonymized**, same "retain-with-reason" shape as [AccountingPersonalData]'s own
 * `journal_entry` handling: a record that a specific document containing this member's personal
 * data was shared with a named third-party processor (Letterxpress) is itself an accountability-
 * relevant fact under Art. 5(2) DSGVO ("Rechenschaftspflicht") -- deleting it would remove the
 * organization's own evidence of what was disclosed to whom and when. The table also stores no
 * free-text member-authored content to redact beyond the FK itself, which already resolves
 * through the anonymized [network.lapis.cloud.server.db.generated.MemberTable] row post-erasure,
 * same as every other retain-with-reason contributor (see [FoundationPersonalData]).
 */
object PostalMailPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "postalMail"
    override val displayName = "Briefpostversand"
    override val coveredTables = setOf(PostalDeliveryLogTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonArray {
            PostalDeliveryLogTable
                .selectAll()
                .where { PostalDeliveryLogTable.recipientMemberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[PostalDeliveryLogTable.id].toString())
                            put("documentReference", row[PostalDeliveryLogTable.documentReference])
                            put("dispatchedAt", row[PostalDeliveryLogTable.dispatchedAt].toString())
                            put("status", row[PostalDeliveryLogTable.status].name)
                            put("providerReference", row[PostalDeliveryLogTable.providerReference])
                        },
                    )
                }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total = PostalDeliveryLogTable.selectAll().where { PostalDeliveryLogTable.recipientMemberId eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "postal_delivery_log",
                rowsRetained = total.toInt(),
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht -- Nachweis, dass ein Dokument mit " +
                        "personenbezogenen Daten dieses Mitglieds an einen benannten Auftragsverarbeiter " +
                        "(Letterxpress) uebermittelt wurde, bleibt unveraendert erhalten.",
            ),
        )
    }
}
