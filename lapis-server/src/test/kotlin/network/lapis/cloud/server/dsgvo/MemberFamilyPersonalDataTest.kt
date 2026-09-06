package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.FamilyMemberRole
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
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- exercises
 * [MemberFamilyPersonalData] directly (no HTTP layer needed), same house style
 * [MemberHonorPersonalDataTest] establishes.
 */
class MemberFamilyPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                MemberFamilyLinkTable.deleteAll()
                MemberFamilyTable.deleteAll()
            }
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

        fun createFamily(
            createdBy: Uuid,
            name: String = "Test-Familie",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberFamilyTable.insert {
                    it[MemberFamilyTable.id] = id
                    it[MemberFamilyTable.name] = name
                    it[MemberFamilyTable.createdBy] = createdBy
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            return id
        }

        fun link(
            familyId: Uuid,
            memberId: Uuid,
            role: FamilyMemberRole,
            linkedBy: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberFamilyLinkTable.insert {
                    it[MemberFamilyLinkTable.id] = id
                    it[MemberFamilyLinkTable.familyId] = familyId
                    it[MemberFamilyLinkTable.memberId] = memberId
                    it[MemberFamilyLinkTable.role] = role
                    it[payerFamilyId] = if (role == FamilyMemberRole.PAYER) familyId else null
                    it[linkedAt] = DbClock.nowLocalDateTime()
                    it[MemberFamilyLinkTable.linkedBy] = linkedBy
                }
            }
            return id
        }

        test("coveredTables covers exactly member_family and member_family_link") {
            MemberFamilyPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("member_family", "member_family_link")
        }

        test("exportMember includes the own link and families created, correctly flagged") {
            val actor = createMember(email = "family-export-actor-${Uuid.random()}@example.org")
            val payer = createMember(email = "family-export-payer-${Uuid.random()}@example.org")
            val familyId = createFamily(createdBy = actor)
            link(familyId = familyId, memberId = payer, role = FamilyMemberRole.PAYER, linkedBy = actor)

            val exported = transaction { MemberFamilyPersonalData.exportMember(payer) }
            val links = exported["memberFamilyLinks"]!!.jsonArray
            val ownLink = links.single { it.jsonObject["familyId"]!!.jsonPrimitive.content == familyId.toString() }
            ownLink.jsonObject["subjectRoleMember"]!!.jsonPrimitive.content shouldBe "true"

            val exportedActor = transaction { MemberFamilyPersonalData.exportMember(actor) }
            val createdFamilies = exportedActor["memberFamiliesCreated"]!!.jsonArray
            (createdFamilies.any { it.jsonObject["id"]!!.jsonPrimitive.content == familyId.toString() }) shouldBe true
        }

        test("eraseMember hard-deletes the own link -- rowsDeleted = 1") {
            val actor = createMember(email = "family-erase-actor-${Uuid.random()}@example.org")
            val payer = createMember(email = "family-erase-payer-${Uuid.random()}@example.org")
            val dependent = createMember(email = "family-erase-dependent-${Uuid.random()}@example.org")
            val familyId = createFamily(createdBy = actor)
            link(familyId = familyId, memberId = payer, role = FamilyMemberRole.PAYER, linkedBy = actor)
            link(familyId = familyId, memberId = dependent, role = FamilyMemberRole.DEPENDENT, linkedBy = actor)

            val outcomes =
                transaction {
                    MemberFamilyPersonalData.eraseMember(
                        memberId = dependent,
                        mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                    )
                }
            val linkOutcome = outcomes.single { it.table == "member_family_link" }
            linkOutcome.rowsDeleted shouldBe 1

            transaction {
                (MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.memberId eq dependent }.count()) shouldBe 0
                // Familie bleibt bestehen -- payer ist noch verlinkt.
                (MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq familyId }.count()) shouldBe 1
            }
        }

        test("eraseMember cascades to a fully-linkless family -- second TableErasureOutcome with rowsDeleted") {
            val actor = createMember(email = "family-erase-lastlink-actor-${Uuid.random()}@example.org")
            val payer = createMember(email = "family-erase-lastlink-payer-${Uuid.random()}@example.org")
            val familyId = createFamily(createdBy = actor)
            link(familyId = familyId, memberId = payer, role = FamilyMemberRole.PAYER, linkedBy = actor)

            val outcomes =
                transaction { MemberFamilyPersonalData.eraseMember(memberId = payer, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) }
            val familyOutcome = outcomes.single { it.table == "member_family" }
            familyOutcome.rowsDeleted shouldBe 1

            transaction {
                (MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq familyId }.count()) shouldBe 0
            }
        }

        test("eraseMember on created_by/linked_by role alone deletes nothing, retains with a non-blank reason") {
            val actor = createMember(email = "family-erase-creator-${Uuid.random()}@example.org")
            val payer = createMember(email = "family-erase-creator-payer-${Uuid.random()}@example.org")
            val familyId = createFamily(createdBy = actor)
            link(familyId = familyId, memberId = payer, role = FamilyMemberRole.PAYER, linkedBy = actor)

            val outcomes =
                transaction { MemberFamilyPersonalData.eraseMember(memberId = actor, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) }
            val linkOutcome = outcomes.single { it.table == "member_family_link" }
            linkOutcome.rowsDeleted shouldBe 0
            (linkOutcome.rowsRetained >= 1) shouldBe true
            (linkOutcome.retentionReason?.isNotBlank() ?: false) shouldBe true

            val familyOutcome = outcomes.single { it.table == "member_family" }
            familyOutcome.rowsDeleted shouldBe 0
            (familyOutcome.rowsRetained >= 1) shouldBe true
            (familyOutcome.retentionReason?.isNotBlank() ?: false) shouldBe true

            transaction {
                (MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq familyId }.count()) shouldBe 1
                (MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.familyId eq familyId }.count()) shouldBe 1
            }
        }
    })
