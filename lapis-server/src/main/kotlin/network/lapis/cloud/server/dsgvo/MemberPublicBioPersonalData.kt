package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- owns [MemberPublicBioTable]. See
 * `60-member-public-bio.kuml.kts` file header "DSGVO".
 *
 * **Export** (Art. 15/20) carries the text, whether it is currently shown publicly, the consent
 * wording version and the timestamps -- the subject is entitled to get their own text back.
 * **Erasure** is a hard DELETE of the row in EVERY [ErasureMode]: a self-written profile text has no
 * accountability or retention interest of its own.
 *
 * The politician-listing consent events live in `public_ranking_consent_event` (kind
 * `POLITICIAN_LISTING`) and are exported/erased by `PublicRankingConsentPersonalData` -- that
 * contributor is table-based, so the new kind needed no change there.
 */
object MemberPublicBioPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberPublicBio"
    override val displayName = "Öffentliche Kurzvorstellung"
    override val coveredTables = setOf(MemberPublicBioTable)

    override fun exportMember(memberId: Uuid): JsonElement {
        val row =
            MemberPublicBioTable
                .selectAll()
                .where { MemberPublicBioTable.memberId eq memberId }
                .singleOrNull()
        return buildJsonObject {
            if (row == null) {
                put("hasPublicBio", false)
                return@buildJsonObject
            }
            put("hasPublicBio", true)
            put("text", row[MemberPublicBioTable.bioText])
            put("updatedAt", row[MemberPublicBioTable.updatedAt].toString())
            put("published", row[MemberPublicBioTable.consentGrantedAt] != null)
            row[MemberPublicBioTable.consentGrantedAt]?.let { put("consentGrantedAt", it.toString()) }
            row[MemberPublicBioTable.consentTextVersion]?.let { put("consentTextVersion", it) }
        }
    }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = MemberPublicBioTable.deleteWhere { MemberPublicBioTable.memberId eq memberId }
        return listOf(TableErasureOutcome(table = "member_public_bio", rowsDeleted = deleted, retentionReason = null))
    }
}
