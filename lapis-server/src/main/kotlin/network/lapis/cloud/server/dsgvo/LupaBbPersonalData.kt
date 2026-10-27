package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.server.db.generated.LupaBbSyncSuppressionTable
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.lupabb.LupaBbSuppression
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.226 "LupaBB-Abgleich" -- owns [MemberLupaBbProfileTable] and the LupaBB columns of `member` (`lupabb_person_number`, `lupabb_missing_since`),
 * and covers the setter / starter columns of [LupaBbSyncConfigTable] and [LupaBbSyncRunTable] (see `77-lupabb-sync.kuml.kts`).
 *
 * **Export** (Art. 15/20): the person number, the "missing since" day and every field of the profile side table.
 *
 * **Erasure** (every [ErasureMode]): the profile row is DELETED, the person number and the "missing since" day are cleared, and the person number
 * (or, for a CSV-imported member that was never linked, the numeric `external_reference`, which is the LupaBB person number) is put on the suppression list as an HMAC ([LupaBbSuppression]) so that the next sync does not create the person again. This contributor is listed
 * BEFORE [FoundationPersonalData] because it must read the person number of the member row before that row is anonymised. The sync run log and the
 * credentials row are RETAINED: they record that an administrator ran or configured the sync (accountability for a personal-data process) and only
 * point to the member row, whose display is anonymised.
 */
object LupaBbPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "lupabbSync"
    override val displayName = "LupaBB-Abgleich"
    override val coveredTables =
        setOf(MemberLupaBbProfileTable, LupaBbSyncConfigTable, LupaBbSyncRunTable, LupaBbSyncSuppressionTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val member = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
            put("lupabbPersonNumber", member?.get(MemberTable.lupabbPersonNumber))
            put("lupabbMissingSince", member?.get(MemberTable.lupabbMissingSince)?.toString())
            val profile = MemberLupaBbProfileTable.selectAll().where { MemberLupaBbProfileTable.memberId eq memberId }.singleOrNull()
            put(
                "profile",
                if (profile == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("givenName", profile[MemberLupaBbProfileTable.givenName])
                        put("familyName", profile[MemberLupaBbProfileTable.familyName])
                        put("title", profile[MemberLupaBbProfileTable.title])
                        put("nameAffix", profile[MemberLupaBbProfileTable.nameAffix])
                        put("company", profile[MemberLupaBbProfileTable.company])
                        put("personType", profile[MemberLupaBbProfileTable.personType])
                        put("phone", profile[MemberLupaBbProfileTable.phone])
                        put("mobile", profile[MemberLupaBbProfileTable.mobile])
                        put("addressSupplement", profile[MemberLupaBbProfileTable.addressSupplement])
                        put("poBox", profile[MemberLupaBbProfileTable.poBox])
                        put("lastSyncedAt", profile[MemberLupaBbProfileTable.lastSyncedAt].toString())
                    }
                },
            )
            put(
                "syncRunsStartedByYou",
                buildJsonArray {
                    LupaBbSyncRunTable
                        .selectAll()
                        .where { LupaBbSyncRunTable.startedBy eq memberId }
                        .toList()
                        .sortedBy { it[LupaBbSyncRunTable.finishedAt] }
                        .forEach { add(buildJsonObject { put("finishedAt", it[LupaBbSyncRunTable.finishedAt].toString()) }) }
                },
            )
            put(
                "credentialsSetByYouAt",
                LupaBbSyncConfigTable
                    .selectAll()
                    .where { LupaBbSyncConfigTable.setBy eq memberId }
                    .singleOrNull()
                    ?.get(LupaBbSyncConfigTable.setAt)
                    ?.toString(),
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val member = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
        val personNumber = member?.get(MemberTable.lupabbPersonNumber)
        if (personNumber != null) LupaBbSuppression.add(personNumber = personNumber, now = ServerClock.now())
        // a CSV-imported member that was never linked: the import stored the LupaBB person number as reference
        val referenceNumber = LupaBbSuppression.referenceNumber(member?.get(MemberTable.externalReference))
        if (referenceNumber != null && referenceNumber != personNumber) {
            LupaBbSuppression.add(personNumber = referenceNumber, now = ServerClock.now())
        }
        val profilesDeleted = MemberLupaBbProfileTable.deleteWhere { MemberLupaBbProfileTable.memberId eq memberId }
        val cleared =
            if (member?.get(MemberTable.lupabbPersonNumber) != null || member?.get(MemberTable.lupabbMissingSince) != null) {
                MemberTable.update({ MemberTable.id eq memberId }) {
                    it[lupabbPersonNumber] = null
                    it[lupabbMissingSince] = null
                }
            } else {
                0
            }
        val reason =
            "Nachweis, dass ein Administrator den LupaBB-Abgleich eingerichtet oder ausgefuehrt hat " +
                "(Rechenschaft ueber einen Verarbeitungsvorgang) -- " +
                "kein Inhalt, nur Zaehler und Zeitpunkt; die Anzeige des Bearbeiters wird ueber das anonymisierte Mitglied aufgeloest."
        return listOf(
            TableErasureOutcome(table = "member_lupabb_profile", rowsDeleted = profilesDeleted),
            TableErasureOutcome(table = "member", rowsAnonymized = cleared),
            TableErasureOutcome(
                table = "lupabb_sync_run",
                rowsRetained =
                    LupaBbSyncRunTable
                        .selectAll()
                        .where { LupaBbSyncRunTable.startedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "lupabb_sync_config",
                rowsRetained =
                    LupaBbSyncConfigTable
                        .selectAll()
                        .where { LupaBbSyncConfigTable.setBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
        )
    }
}
