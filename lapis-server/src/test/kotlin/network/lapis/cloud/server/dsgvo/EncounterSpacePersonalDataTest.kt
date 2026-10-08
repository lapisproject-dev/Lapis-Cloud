package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.encounter.EncounterConsentDisclaimer
import network.lapis.cloud.server.encounter.EncounterFixtures
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.openAndEnter
import network.lapis.cloud.server.encounter.tableWorld
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.61 -- [EncounterSpacePersonalData]: the Art. 15 export carries the subject's own rows only; erasure DELETES the office rows
 * and RETAINS (with the reason) the space they created and the consent proof.
 */
class EncounterSpacePersonalDataTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun consent(member: kotlin.uuid.Uuid) {
            transaction {
                EncounterConsentAcknowledgmentTable.insert {
                    it[memberId] = member
                    it[consentVersion] = EncounterConsentDisclaimer.VERSION
                    it[consentSha256] = EncounterConsentDisclaimer.SHA256
                    it[acknowledgedOn] = OrganizationTimeZone.today()
                }
            }
        }

        test("export: the subject's spaces, offices and consent proofs -- and nobody else's") {
            val creator = fx.createMember()
            val other = fx.createMember()
            val space = fx.createSpace(createdBy = creator, title = "Meine Andacht")
            val foreign = fx.createSpace(createdBy = other, title = "Fremde Andacht")
            fx.setRole(spaceId = space, memberId = creator, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = foreign, memberId = other, role = EncounterSpaceRole.STEWARD)
            consent(creator)
            consent(other)
            val json = transaction { EncounterSpacePersonalData.exportMember(creator) } as JsonObject
            (json.getValue("spacesCreated") as JsonArray).map { (it as JsonObject).getValue("title").jsonPrimitive.content } shouldBe
                listOf("Meine Andacht")
            (json.getValue("spaceRoles") as JsonArray).map { (it as JsonObject).getValue("role").jsonPrimitive.content } shouldBe
                listOf("PULPIT")
            val consents = json.getValue("encounterConsents") as JsonArray
            consents.size shouldBe 1
            (consents[0] as JsonObject).getValue("consentVersion").jsonPrimitive.content shouldBe EncounterConsentDisclaimer.VERSION
            (consents[0] as JsonObject).getValue("acknowledgedOn").jsonPrimitive.content shouldBe OrganizationTimeZone.today().toString()
            json.toString() shouldNotContain other.toString()
            json.toString() shouldNotContain "Fremde Andacht"
        }

        test("V1.9.80: the export of a person who sat at a table knows no table, no seat and no table room") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }.getOrThrow()
                val text = transaction { EncounterSpacePersonalData.exportMember(w.a) }.toString()
                text shouldNotContain "lc-et-"
                text shouldNotContain "tableSeat"
                text shouldNotContain "\"table\""
            }
        }

        test("erasure: office rows are deleted, the space and the consent proof are retained with their reasons -- in both modes") {
            ErasureMode.entries.forEach { mode ->
                val member = fx.createMember()
                val space = fx.createSpace(createdBy = member)
                fx.setRole(spaceId = space, memberId = member, role = EncounterSpaceRole.STEWARD)
                consent(member)
                val outcomes =
                    transaction {
                        EncounterSpacePersonalData.eraseMember(
                            memberId = member,
                            mode = mode,
                        )
                    }.associateBy { it.table }
                outcomes.keys shouldBe setOf("encounter_space", "encounter_space_role", "encounter_consent_acknowledgment")
                outcomes.getValue("encounter_space_role").rowsDeleted shouldBe 1
                outcomes.getValue("encounter_space").rowsRetained shouldBe 1
                outcomes.getValue("encounter_space").retentionReason!! shouldContain "Organisationskonfiguration"
                outcomes.getValue("encounter_consent_acknowledgment").rowsRetained shouldBe 1
                outcomes.getValue("encounter_consent_acknowledgment").retentionReason!! shouldContain "Art. 7"
                transaction { EncounterSpaceRoleTable.selectAll().where { EncounterSpaceRoleTable.memberId eq member }.count() } shouldBe 0L
                transaction { EncounterSpaceTable.selectAll().where { EncounterSpaceTable.createdByMemberId eq member }.count() } shouldBe
                    1L
                transaction {
                    EncounterConsentAcknowledgmentTable.selectAll().where { EncounterConsentAcknowledgmentTable.memberId eq member }.count()
                } shouldBe 1L
            }
        }

        test(
            "the contributor is registered and covers exactly the three tables; a member with nothing exports empty lists and retains nothing",
        ) {
            PersonalDataRegistry.contributors.any { it === EncounterSpacePersonalData } shouldBe true
            EncounterSpacePersonalData.coveredTables shouldBe
                setOf(EncounterSpaceTable, EncounterSpaceRoleTable, EncounterConsentAcknowledgmentTable)
            val lonely = fx.createMember()
            val json = transaction { EncounterSpacePersonalData.exportMember(lonely) } as JsonObject
            listOf("spacesCreated", "spaceRoles", "encounterConsents").forEach { (json.getValue(it) as JsonArray).size shouldBe 0 }
            transaction { EncounterSpacePersonalData.eraseMember(memberId = lonely, mode = ErasureMode.ANONYMIZE) }.sumOf {
                it.rowsRetained +
                    it.rowsDeleted
            } shouldBe
                0
        }
    })
