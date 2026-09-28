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
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.AccountRole
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
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" review-fix test-coverage gap --
 * [RegionalChapterPersonalData] had NO test at all before this file. Exercises it directly (no
 * HTTP layer needed), same house style [MemberHonorPersonalDataTest] establishes. Own fixtures
 * (fresh members/chapter per spec), cleaned up in `afterSpec`.
 */
class RegionalChapterPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        var chapterId: Uuid? = null

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            val id = Uuid.random()
            transaction {
                RegionalChapterTable.insert {
                    it[RegionalChapterTable.id] = id
                    it[name] = "PersonalData-Test-${Uuid.random()}"
                    it[nameKey] = "personaldata-test-$id"
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            chapterId = id
        }

        afterSpec {
            transaction {
                RegionalChapterOfficerTable.deleteWhere { memberId inList createdMemberIds }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                chapterId?.let { id -> RegionalChapterTable.deleteWhere { RegionalChapterTable.id eq id } }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertGrant(
            memberId: Uuid,
            grantedByMemberId: Uuid?,
            revoked: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                RegionalChapterOfficerTable.insert {
                    it[RegionalChapterOfficerTable.id] = id
                    it[RegionalChapterOfficerTable.memberId] = memberId
                    it[regionalChapterId] = requireNotNull(chapterId)
                    it[grantedAt] = now
                    it[RegionalChapterOfficerTable.grantedByMemberId] = grantedByMemberId
                    it[revokedAt] = if (revoked) now else null
                    it[activeForMemberId] = if (revoked) null else memberId
                }
            }
            return id
        }

        test("coveredTables covers exactly regional_chapter_officer") {
            RegionalChapterPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("regional_chapter_officer")
        }

        test("exportMember includes only the subject's OWN grants, with chapterName/grantedBySelf") {
            val holder = createMember("rcpd-export-holder-${Uuid.random()}@example.org")
            val granter = createMember("rcpd-export-granter-${Uuid.random()}@example.org")
            insertGrant(memberId = holder, grantedByMemberId = granter)

            val exported = transaction { RegionalChapterPersonalData.exportMember(holder) }
            val entries = exported["officerGrants"]!!.jsonArray
            entries.size shouldBe 1
            val entry = entries.single()
            entry.jsonObject["chapterName"]!!
                .jsonPrimitive.content
                .isNotBlank() shouldBe true
            entry.jsonObject["grantedBySelf"]!!.jsonPrimitive.content shouldBe "false"

            // The GRANTER's own export does NOT include a grant they merely granted to someone
            // else -- exportMember is scoped to `memberId eq subject` (the HOLDER role), not
            // grantedByMemberId.
            val granterExport = transaction { RegionalChapterPersonalData.exportMember(granter) }
            granterExport["officerGrants"]!!.jsonArray.size shouldBe 0

            // Self-grant (holder == granter) reports grantedBySelf=true.
            val selfHolder = createMember("rcpd-export-self-${Uuid.random()}@example.org")
            insertGrant(memberId = selfHolder, grantedByMemberId = selfHolder)
            val selfExport = transaction { RegionalChapterPersonalData.exportMember(selfHolder) }
            selfExport["officerGrants"]!!
                .jsonArray
                .single()
                .jsonObject["grantedBySelf"]!!
                .jsonPrimitive.content shouldBe "true"
        }

        // Security/DSGVO fix (LOW, export completeness) coverage -- exportMember used to omit
        // grants the subject ISSUED as granter, even though eraseMember treats those rows as
        // personal data too (anonymizing grantedByMemberId). See RegionalChapterPersonalData's
        // own KDoc "Export completeness".
        test("exportMember also includes grants the subject GRANTED to someone else, WITHOUT the recipient's identity") {
            val holder = createMember("rcpd-export-issued-holder-${Uuid.random()}@example.org")
            val granter = createMember("rcpd-export-issued-granter-${Uuid.random()}@example.org")
            insertGrant(memberId = holder, grantedByMemberId = granter, revoked = true)

            val granterExport = transaction { RegionalChapterPersonalData.exportMember(granter) }
            // Not present as a HOLDER grant (unchanged existing behavior).
            granterExport["officerGrants"]!!.jsonArray.size shouldBe 0
            // But present as an ISSUED grant.
            val issued = granterExport["officerGrantsIssued"]!!.jsonArray
            issued.size shouldBe 1
            val entry = issued.single().jsonObject
            entry["chapterName"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
            entry["grantedAt"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
            entry["revokedAt"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
            // No recipient identity leaked -- see this object's own KDoc "Export completeness".
            entry.containsKey("memberId") shouldBe false
            entry.containsKey("displayName") shouldBe false

            // The HOLDER's own export does not pick up a grant issued by someone ELSE as if it
            // were their own issued grant.
            val holderExport = transaction { RegionalChapterPersonalData.exportMember(holder) }
            holderExport["officerGrantsIssued"]!!.jsonArray.size shouldBe 0
        }

        test("eraseMember hard-deletes the subject's OWN grants (holder role)") {
            val holder = createMember("rcpd-erase-holder-${Uuid.random()}@example.org")
            val granter = createMember("rcpd-erase-granter-${Uuid.random()}@example.org")
            val grantId = insertGrant(memberId = holder, grantedByMemberId = granter)

            val outcome = transaction { RegionalChapterPersonalData.eraseMember(memberId = holder, mode = ErasureMode.ANONYMIZE) }.single()
            outcome.table shouldBe "regional_chapter_officer"
            outcome.rowsDeleted shouldBe 1

            val stillExists =
                transaction { RegionalChapterOfficerTable.selectAll().where { RegionalChapterOfficerTable.id eq grantId }.count() }
            stillExists shouldBe 0L
        }

        test("eraseMember anonymizes (nulls grantedByMemberId, RETAINS the row) grants the subject granted to someone ELSE") {
            val granter = createMember("rcpd-erase-granter2-${Uuid.random()}@example.org")
            val holder = createMember("rcpd-erase-holder2-${Uuid.random()}@example.org")
            val grantId = insertGrant(memberId = holder, grantedByMemberId = granter)

            val outcome = transaction { RegionalChapterPersonalData.eraseMember(memberId = granter, mode = ErasureMode.ANONYMIZE) }.single()
            outcome.table shouldBe "regional_chapter_officer"
            outcome.rowsAnonymized shouldBe 1
            // Not the granter's own grant -- nothing of THEIRS is deleted, only the
            // granted_by_member_id column on the holder's row is nulled.
            outcome.rowsDeleted shouldBe 0

            val row = transaction { RegionalChapterOfficerTable.selectAll().where { RegionalChapterOfficerTable.id eq grantId }.single() }
            row[RegionalChapterOfficerTable.grantedByMemberId] shouldBe null
            row[RegionalChapterOfficerTable.memberId] shouldBe holder
        }

        test("eraseMember on a self-granted row (member_id == granted_by_member_id): DELETE wins, no separate anonymize count") {
            // eraseMember runs the holder-role DELETE first, THEN the granter-role anonymize
            // UPDATE -- for a self-grant (member_id == granted_by_member_id == subject) the row is
            // already gone by the time the UPDATE's `grantedByMemberId eq subject` WHERE clause
            // runs, so rowsAnonymized stays 0 rather than double-counting the same row under both
            // outcomes.
            val selfHolder = createMember("rcpd-erase-self-${Uuid.random()}@example.org")
            val grantId = insertGrant(memberId = selfHolder, grantedByMemberId = selfHolder)

            val outcome =
                transaction { RegionalChapterPersonalData.eraseMember(memberId = selfHolder, mode = ErasureMode.ANONYMIZE) }.single()
            outcome.rowsDeleted shouldBe 1
            outcome.rowsAnonymized shouldBe 0

            val stillExists =
                transaction { RegionalChapterOfficerTable.selectAll().where { RegionalChapterOfficerTable.id eq grantId }.count() }
            stillExists shouldBe 0L
        }
    })
