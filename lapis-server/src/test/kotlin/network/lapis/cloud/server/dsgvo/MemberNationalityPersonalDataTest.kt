package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.member.MemberCountryStore
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Welle V1.9.121 -- [MemberNationalityPersonalData] and the country code in [FoundationPersonalData]: the Art. 15 export carries code,
 * German name, legacy text and the explanation of the internal codes; erasure removes the rows (and the code) in every mode.
 * `PersonalDataCoverageTest` separately proves that the table is covered at all.
 */
class MemberNationalityPersonalDataTest :
    FunSpec({
        val fx = PeerFixture()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        test("the nationality export lists code and German name, the legacy text and explains XS / ZZ") {
            val m = fx.member()
            transaction {
                MemberTable.update({ MemberTable.id eq m }) { it[nationality] = "Altwert" }
                MemberCountryStore.replaceNationalities(memberId = m, codes = listOf("PL", "DE"), clearLegacy = false)
            }
            val json = transaction { MemberNationalityPersonalData.exportMember(m) } as JsonObject
            val list = json.getValue("nationalities") as JsonArray
            list.map { (it as JsonObject).getValue("code").jsonPrimitive.content } shouldBe listOf("DE", "PL")
            list.map { (it as JsonObject).getValue("name").jsonPrimitive.content } shouldBe listOf("Deutschland", "Polen")
            json.getValue("nationalityLegacy").jsonPrimitive.content shouldBe "Altwert"
            json.getValue("codeExplanation").jsonPrimitive.content shouldContain "XXA"
            val other = fx.member()
            transaction { MemberCountryStore.replaceNationalities(memberId = other, codes = listOf("GE")) }
            json.toString() shouldNotContain "GE"
        }

        test("the foundation export carries the country code and its German name") {
            val m = fx.member()
            transaction { MemberCountryStore.setCountry(memberId = m, code = "PL") }
            val json = transaction { FoundationPersonalData.exportMember(m) } as JsonObject
            json.getValue("countryCode").jsonPrimitive.content shouldBe "PL"
            json.getValue("countryName").jsonPrimitive.content shouldBe "Polen"
        }

        test("erasure deletes the rows and nulls the code in every mode") {
            ErasureMode.entries.forEach { mode ->
                val m = fx.member()
                transaction {
                    MemberCountryStore.setCountry(memberId = m, code = "PL")
                    MemberCountryStore.replaceNationalities(memberId = m, codes = listOf("PL", "DE"))
                }
                val nat = transaction { MemberNationalityPersonalData.eraseMember(memberId = m, mode = mode) }.single()
                nat.table shouldBe "member_nationality"
                nat.rowsDeleted shouldBe 2
                nat.rowsRetained shouldBe 0
                transaction { FoundationPersonalData.eraseMember(memberId = m, mode = mode) }
                transaction { MemberNationalityTable.selectAll().where { MemberNationalityTable.memberId eq m }.count() } shouldBe 0L
                transaction { MemberTable.selectAll().where { MemberTable.id eq m }.single()[MemberTable.countryCode] } shouldBe null
            }
        }

        test("V1.9.141: an applicant (status APPLICATION) is exported and erased like any other member") {
            val m = fx.member(status = MemberStatus.APPLICATION)
            transaction {
                MemberCountryStore.setCountry(memberId = m, code = "PL")
                MemberCountryStore.replaceNationalities(memberId = m, codes = listOf("PL", "DE"), clearLegacy = false)
            }
            val nationalityJson = transaction { MemberNationalityPersonalData.exportMember(m) } as JsonObject
            ((nationalityJson.getValue("nationalities")) as JsonArray).map {
                (it as JsonObject).getValue("code").jsonPrimitive.content
            } shouldBe listOf("DE", "PL")
            val foundationJson = transaction { FoundationPersonalData.exportMember(m) } as JsonObject
            foundationJson.getValue("countryCode").jsonPrimitive.content shouldBe "PL"

            val erased = transaction { MemberNationalityPersonalData.eraseMember(memberId = m, mode = ErasureMode.ANONYMIZE) }.single()
            erased.rowsDeleted shouldBe 2
            transaction { FoundationPersonalData.eraseMember(memberId = m, mode = ErasureMode.ANONYMIZE) }
            transaction { MemberNationalityTable.selectAll().where { MemberNationalityTable.memberId eq m }.count() } shouldBe 0L
            transaction { MemberTable.selectAll().where { MemberTable.id eq m }.single()[MemberTable.countryCode] } shouldBe null
        }

        test("the contributor is registered and covers the table") {
            PersonalDataRegistry.contributors.any { it === MemberNationalityPersonalData } shouldBe true
            MemberNationalityPersonalData.coveredTables shouldBe setOf(MemberNationalityTable)
        }
    })
