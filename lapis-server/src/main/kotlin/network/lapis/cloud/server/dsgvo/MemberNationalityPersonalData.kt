package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.geo.CountryDisplayNames
import network.lapis.cloud.server.member.MemberCountryStore
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.121 -- owns [MemberNationalityTable], the 0..10 nationalities a member chose from the selection list.
 *
 * Nationality is GwG beneficial-owner data, i.e. personal data: the rows are DELETED in every [ErasureMode] (there is no retention
 * duty -- the Transparenzregister filing keeps its own copy outside this system). The selected country code (`member.country_code`)
 * is nulled by [FoundationPersonalData] together with the rest of the member row; the legacy texts likewise.
 *
 * The export (Art. 15) lists code and German name per nationality, the legacy text as stored, and explains the two internal codes.
 */
object MemberNationalityPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberNationality"
    override val displayName = "Staatsangehoerigkeiten"
    override val coveredTables = setOf(MemberNationalityTable)

    const val CODE_EXPLANATION =
        "XS = staatenlos (ICAO-Dokumentencode XXA), ZZ = Staatsangehoerigkeit ungeklaert; beides sind interne Werte und stehen immer allein."

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val codes = MemberCountryStore.loadNationalities(memberId)
            put(
                "nationalities",
                buildJsonArray {
                    codes.forEach { code ->
                        add(
                            buildJsonObject {
                                put("code", code)
                                put("name", CountryDisplayNames.germanName(code))
                            },
                        )
                    }
                },
            )
            put(
                "nationalityLegacy",
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq memberId }
                    .singleOrNull()
                    ?.get(MemberTable.nationality),
            )
            put("codeExplanation", CODE_EXPLANATION)
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = MemberCountryStore.clearAll(memberId)
        return listOf(TableErasureOutcome(table = "member_nationality", rowsDeleted = deleted, retentionReason = null))
    }
}
