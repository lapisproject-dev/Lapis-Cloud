package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- direct unit coverage of
 * [memberVisibility], the one new authorization boundary this wave adds. Matrix: role x status x
 * officer-grant state x own-chapter-match.
 */
class RegionalChapterVisibilityTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdChapterIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                RegionalChapterOfficerTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.update({ MemberTable.id inList createdMemberIds }) { it[regionalChapterId] = null }
                RegionalChapterTable.deleteWhere { id inList createdChapterIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createChapter(name: String): Uuid {
            val id = Uuid.random()
            transaction {
                RegionalChapterTable.insert {
                    it[RegionalChapterTable.id] = id
                    it[RegionalChapterTable.name] = name
                    it[nameKey] = name.lowercase()
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdChapterIds += id
            return id
        }

        fun createMember(
            status: MemberStatus,
            role: AccountRole,
            chapterId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Visibility-Test"
                    it[email] = "rcv-${Uuid.random()}@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[regionalChapterId] = chapterId
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun grantActiveOfficer(
            memberId: Uuid,
            chapterId: Uuid,
        ) {
            transaction {
                RegionalChapterOfficerTable.insert {
                    it[id] = Uuid.random()
                    it[RegionalChapterOfficerTable.memberId] = memberId
                    it[regionalChapterId] = chapterId
                    it[grantedAt] = DbClock.nowLocalDateTime()
                    it[grantedByMemberId] = null
                    it[revokedAt] = null
                    it[activeForMemberId] = memberId
                }
            }
        }

        test("BOARD/TREASURER/ADMIN always get All, even with an (irrelevant) officer grant") {
            val chapterId = createChapter("Visibility-Board-${Uuid.random()}")
            for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)) {
                val memberId = createMember(status = MemberStatus.ACTIVE, role = role, chapterId = chapterId)
                grantActiveOfficer(memberId, chapterId)
                transaction {
                    val current = CurrentMember(memberId = memberId, role = role, status = MemberStatus.ACTIVE)
                    current.memberVisibility() shouldBe MemberVisibility.All
                }
            }
        }

        test("plain MEMBER with no officer grant gets None") {
            val memberId = createMember(status = MemberStatus.ACTIVE, role = AccountRole.MEMBER)
            transaction {
                val current = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
                current.memberVisibility() shouldBe MemberVisibility.None
            }
        }

        test("plain MEMBER, ACTIVE, with a matching active grant gets Chapter") {
            val chapterId = createChapter("Visibility-Match-${Uuid.random()}")
            val memberId = createMember(status = MemberStatus.ACTIVE, role = AccountRole.MEMBER, chapterId = chapterId)
            grantActiveOfficer(memberId, chapterId)
            transaction {
                val current = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
                current.memberVisibility() shouldBe MemberVisibility.Chapter(chapterId)
            }
        }

        test("plain MEMBER, non-ACTIVE status, gets None even with an active grant") {
            val chapterId = createChapter("Visibility-Withdrawn-${Uuid.random()}")
            val memberId = createMember(status = MemberStatus.WITHDRAWN, role = AccountRole.MEMBER, chapterId = chapterId)
            grantActiveOfficer(memberId, chapterId)
            transaction {
                val current = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.WITHDRAWN)
                current.memberVisibility() shouldBe MemberVisibility.None
            }
        }

        test("grant for a DIFFERENT chapter than the member's own current assignment gets None") {
            val grantedChapterId = createChapter("Visibility-Granted-${Uuid.random()}")
            val ownChapterId = createChapter("Visibility-Own-${Uuid.random()}")
            val memberId = createMember(status = MemberStatus.ACTIVE, role = AccountRole.MEMBER, chapterId = ownChapterId)
            grantActiveOfficer(memberId, grantedChapterId)
            transaction {
                val current = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
                current.memberVisibility() shouldBe MemberVisibility.None
            }
        }

        test("memberVisibility throws IllegalStateException when called outside a transaction") {
            val memberId = Uuid.random()
            val current = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
            try {
                current.memberVisibility()
                error("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                // expected
            }
        }
    })
