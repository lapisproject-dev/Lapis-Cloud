package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.5 "Sterbefall-Workflow" -- pins [FoundationPersonalData]'s deliberate DSGVO
 * ErwG 27 exception: `date_of_death` is exported (Art. 15) alongside every other member field, but
 * -- unlike every OTHER PII field this contributor owns -- is NOT nulled by [eraseMember]. Calls
 * the contributor object directly (no HTTP routing needed for this focused a check), same posture
 * `MemberDateOfDeathConstraintTest` already takes for its own DB-level probe.
 */
class FoundationPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun newDeceasedMember(dateOfDeath: LocalDate?): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Sterbefall DSGVO Testmitglied"
                    it[email] = "dsgvo-dod-$id@example.org"
                    it[status] = MemberStatus.DECEASED
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.dateOfDeath] = dateOfDeath
                }
            }
            createdMemberIds += id
            return id
        }

        test("exportMember includes dateOfDeath alongside the other member fields") {
            val member = newDeceasedMember(LocalDate(2026, 3, 3))
            val exported = transaction { FoundationPersonalData.exportMember(member) }
            exported["dateOfDeath"]?.jsonPrimitive?.content shouldBe "2026-03-03"
        }

        test("exportMember: dateOfDeath is null in the export when not recorded") {
            val member = newDeceasedMember(null)
            val exported = transaction { FoundationPersonalData.exportMember(member) }
            exported["dateOfDeath"]?.jsonPrimitive?.isString shouldBe false
        }

        test(
            "eraseMember (ANONYMIZE): displayName/email are anonymized but dateOfDeath survives verbatim -- DSGVO ErwG 27",
        ) {
            val member = newDeceasedMember(LocalDate(2026, 3, 3))
            transaction { FoundationPersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }

            val row = transaction { MemberTable.selectAll().where { MemberTable.id eq member }.single() }
            row[MemberTable.displayName] shouldBe "Geloeschtes Mitglied"
            row[MemberTable.dateOfDeath] shouldBe LocalDate(2026, 3, 3)
        }

        test("eraseMember (HARD_DELETE_WHERE_UNCONSTRAINED): dateOfDeath still survives -- member row is anonymized, never hard-deleted") {
            val member = newDeceasedMember(LocalDate(2026, 3, 3))
            transaction { FoundationPersonalData.eraseMember(memberId = member, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) }

            val row = transaction { MemberTable.selectAll().where { MemberTable.id eq member }.single() }
            row[MemberTable.dateOfDeath] shouldBe LocalDate(2026, 3, 3)
        }
    })
