package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberHonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- exercises [MemberHonorPersonalData]
 * directly (no HTTP layer needed), same house style [DsgvoCompliancePersonalDataTest] establishes.
 * Own fixtures (fresh members per test), cleaned up in `afterSpec`.
 */
class MemberHonorPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction { MemberHonorTable.deleteAll() }
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Fixture Mitglied ${id.toString().take(6)}"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[anonymizedAt] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertHonor(
            honoreeId: Uuid,
            recordedById: Uuid,
            title: String = "Test-Ehrung",
            note: String? = "Vertrauliche Vorstandsnotiz",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberHonorTable.insert {
                    it[MemberHonorTable.id] = id
                    it[memberId] = honoreeId
                    it[category] = MemberHonorCategory.SERVICE_AWARD
                    it[MemberHonorTable.title] = title
                    it[awardedAt] = LocalDate(2025, 1, 1)
                    it[awardedBy] = "Vorstand"
                    it[MemberHonorTable.note] = note
                    it[recordedBy] = recordedById
                    it[recordedAt] = LocalDateTime(2025, 1, 1, 0, 0, 0)
                }
            }
            return id
        }

        test("coveredTables covers exactly member_honor") {
            MemberHonorPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("member_honor")
        }

        test("exportMember includes rows in both the honoree and the recorder role, correctly flagged") {
            val honoree = createMember(email = "honor-export-honoree-${Uuid.random()}@example.org")
            val recorder = createMember(email = "honor-export-recorder-${Uuid.random()}@example.org")
            val honorId = insertHonor(honoreeId = honoree, recordedById = recorder, title = "Export-Ehrung")

            val exportedForHonoree = transaction { MemberHonorPersonalData.exportMember(honoree) }
            val honoreeEntries = exportedForHonoree["memberHonors"]!!.jsonArray
            val honoreeEntry = honoreeEntries.single { it.jsonObject["id"]!!.jsonPrimitive.content == honorId.toString() }
            honoreeEntry.jsonObject["subjectRoleHonoree"]!!.jsonPrimitive.content shouldBe "true"
            honoreeEntry.jsonObject["subjectRoleRecorder"]!!.jsonPrimitive.content shouldBe "false"

            val exportedForRecorder = transaction { MemberHonorPersonalData.exportMember(recorder) }
            val recorderEntries = exportedForRecorder["memberHonors"]!!.jsonArray
            val recorderEntry = recorderEntries.single { it.jsonObject["id"]!!.jsonPrimitive.content == honorId.toString() }
            recorderEntry.jsonObject["subjectRoleHonoree"]!!.jsonPrimitive.content shouldBe "false"
            recorderEntry.jsonObject["subjectRoleRecorder"]!!.jsonPrimitive.content shouldBe "true"

            val unrelated = createMember(email = "honor-export-unrelated-${Uuid.random()}@example.org")
            val exportedForUnrelated = transaction { MemberHonorPersonalData.exportMember(unrelated) }
            val unrelatedEntries = exportedForUnrelated["memberHonors"]!!.jsonArray
            (unrelatedEntries.any { it.jsonObject["id"]!!.jsonPrimitive.content == honorId.toString() }) shouldBe false
        }

        test("eraseMember nulls note for the honoree role, retains the row, leaves recorder-only rows untouched") {
            val honoree = createMember(email = "honor-erase-honoree-${Uuid.random()}@example.org")
            val recorder = createMember(email = "honor-erase-recorder-${Uuid.random()}@example.org")
            val honorId = insertHonor(honoreeId = honoree, recordedById = recorder, note = "Wird geleert")

            val outcomes = transaction { MemberHonorPersonalData.eraseMember(memberId = honoree, mode = ErasureMode.ANONYMIZE) }
            outcomes.size shouldBe 1
            val outcome = outcomes.single()
            outcome.table shouldBe "member_honor"
            outcome.rowsAnonymized shouldBe 1
            outcome.rowsRetained shouldBe 0
            (outcome.retentionReason?.isNotBlank() ?: false) shouldBe true

            val row = transaction { MemberHonorTable.selectAll().where { MemberHonorTable.id eq honorId }.single() }
            row[MemberHonorTable.note] shouldBe null
            row[MemberHonorTable.title] shouldBe "Test-Ehrung"
            row[MemberHonorTable.memberId] shouldBe honoree

            // Erasing the RECORDER does not touch the note -- they are not the honoree.
            val secondHonoreeId = createMember(email = "honor-erase-honoree2-${Uuid.random()}@example.org")
            val secondHonorId = insertHonor(honoreeId = secondHonoreeId, recordedById = recorder, note = "Bleibt stehen")
            val recorderOutcomes = transaction { MemberHonorPersonalData.eraseMember(memberId = recorder, mode = ErasureMode.ANONYMIZE) }
            val recorderOutcome = recorderOutcomes.single()
            recorderOutcome.rowsAnonymized shouldBe 0
            (recorderOutcome.rowsRetained >= 1) shouldBe true
            val secondRow = transaction { MemberHonorTable.selectAll().where { MemberHonorTable.id eq secondHonorId }.single() }
            secondRow[MemberHonorTable.note] shouldBe "Bleibt stehen"
        }

        test("eraseMember counts an overlapping row (member_id == recorded_by, self-honor) exactly once") {
            val selfHonoree = createMember(email = "honor-selfhonor-${Uuid.random()}@example.org")
            insertHonor(honoreeId = selfHonoree, recordedById = selfHonoree, note = "Selbst geehrt")

            val outcome = transaction { MemberHonorPersonalData.eraseMember(memberId = selfHonoree, mode = ErasureMode.ANONYMIZE) }.single()
            // Exactly one row total (member_id == recorded_by == selfHonoree, counted once via the
            // OR condition, never twice via a naive memberIdCount + recordedByCount sum). The row
            // is anonymized (matches on member_id), so rowsRetained is 0, not -1.
            (outcome.rowsAnonymized + outcome.rowsRetained) shouldBe 1
            outcome.rowsAnonymized shouldBe 1
            outcome.rowsRetained shouldBe 0
        }
    })
