package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.61 "Begegnungsraum" -- owns [EncounterSpaceTable], [EncounterSpaceRoleTable] and
 * [EncounterConsentAcknowledgmentTable].
 *
 * - `encounter_space` (`created_by_member_id`): **retain-with-reason**. The space is organisational configuration (title, theme, entry
 *   policy), not personal data of its creator; the member row is anonymised, never deleted, so the reference stays valid.
 * - `encounter_space_role`: the rows are **deleted**. They are pure office configuration ("who is pulpit / steward"); a member who is
 *   erased no longer holds an office.
 * - `encounter_consent_acknowledgment`: **retain-with-reason** -- the organisation's accountability proof under Art. 5(2)/7(1) GDPR
 *   that a non-member explicitly consented before entering. The row deliberately carries no room, no space and no time of day (only a
 *   date), so retaining it reveals nothing about which service was attended. The retention period is an open legal question (see the
 *   "open questions" in `docs/architecture/encounter-space.adoc`).
 *
 * The attendance of a session ([network.lapis.cloud.server.db.generated.ConferenceParticipationTable] rows of an encounter room) exists
 * only while the session is running; see [ConferencePersonalData].
 */
object EncounterSpacePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "encounterSpace"
    override val displayName = "Begegnungsraum"
    override val coveredTables =
        setOf(
            EncounterSpaceTable,
            EncounterSpaceRoleTable,
            EncounterConsentAcknowledgmentTable,
        )

    private const val SPACE_RETENTION_REASON =
        "Organisationskonfiguration (Titel, Thema, Zugangsregel) des Begegnungsraums; die Mitgliedszeile des Erstellers wird " +
            "anonymisiert und nie geloescht"

    private const val CONSENT_RETENTION_REASON =
        "Rechenschaftsnachweis nach Art. 5 Abs. 2 und Art. 7 Abs. 1 DSGVO ueber die ausdrueckliche Einwilligung (Art. 9 Abs. 2 lit. a); " +
            "enthaelt weder Raum noch Uhrzeit, nur das Datum"

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("spacesCreated") {
                EncounterSpaceTable
                    .selectAll()
                    .where { EncounterSpaceTable.createdByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[EncounterSpaceTable.id].toString())
                                put("title", row[EncounterSpaceTable.title])
                                put("createdAt", row[EncounterSpaceTable.createdAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("spaceRoles") {
                EncounterSpaceRoleTable
                    .selectAll()
                    .where { EncounterSpaceRoleTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("spaceId", row[EncounterSpaceRoleTable.spaceId].toString())
                                put("role", row[EncounterSpaceRoleTable.role])
                            },
                        )
                    }
            }
            putJsonArray("encounterConsents") {
                EncounterConsentAcknowledgmentTable
                    .selectAll()
                    .where { EncounterConsentAcknowledgmentTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("consentVersion", row[EncounterConsentAcknowledgmentTable.consentVersion])
                                put("consentSha256", row[EncounterConsentAcknowledgmentTable.consentSha256])
                                put("acknowledgedOn", row[EncounterConsentAcknowledgmentTable.acknowledgedOn].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val spaces =
            EncounterSpaceTable
                .selectAll()
                .where { EncounterSpaceTable.createdByMemberId eq memberId }
                .count()
                .toInt()
        val deletedRoles = EncounterSpaceRoleTable.deleteWhere { EncounterSpaceRoleTable.memberId eq memberId }
        val consents =
            EncounterConsentAcknowledgmentTable
                .selectAll()
                .where {
                    EncounterConsentAcknowledgmentTable.memberId eq memberId
                }.count()
                .toInt()
        return listOf(
            TableErasureOutcome(table = "encounter_space", rowsRetained = spaces, retentionReason = SPACE_RETENTION_REASON),
            TableErasureOutcome(table = "encounter_space_role", rowsDeleted = deletedRoles),
            TableErasureOutcome(
                table = "encounter_consent_acknowledgment",
                rowsRetained = consents,
                retentionReason = CONSENT_RETENTION_REASON,
            ),
        )
    }
}
