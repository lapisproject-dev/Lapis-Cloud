package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- owns [MemberCardCodeTable]. See
 * `52-member-card.kuml.kts` file header "DSGVO" for the full rationale.
 *
 * **Hard DELETE, not retain-and-redact** -- posture [CrmPersonalData] already establishes for
 * `crm_interaction`, not the retain-with-redaction posture most of this codebase's other tables
 * take. An issued card code has no accountability/retention interest of its own (unlike e.g. a
 * board honor or an audit-log entry) and must become invalid immediately on Art. 17 erasure --
 * retaining a hash of a now-erased member's bearer code would serve no purpose while still being
 * a credential derivative.
 *
 * **`code_hash` is never exported** -- a hash gives the data subject no readable information
 * about their own data (Art. 15 is about intelligible access, not "every byte we store"), and
 * exposing it back to the subject would leak the ONE thing this table exists to protect (see
 * `MemberCardStore` KDoc "The raw bearer code is never persisted").
 */
object MemberCardPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberCard"
    override val displayName = "Mitgliedsausweis"
    override val coveredTables = setOf(MemberCardCodeTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("memberCardCodes") {
                MemberCardCodeTable
                    .selectAll()
                    .where { MemberCardCodeTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MemberCardCodeTable.id].toString())
                                put("issuedAt", row[MemberCardCodeTable.issuedAt].toString())
                                put("revokedAt", row[MemberCardCodeTable.revokedAt]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = MemberCardCodeTable.deleteWhere { MemberCardCodeTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(
                table = "member_card_code",
                rowsDeleted = deleted,
                retentionReason = null,
            ),
        )
    }
}
