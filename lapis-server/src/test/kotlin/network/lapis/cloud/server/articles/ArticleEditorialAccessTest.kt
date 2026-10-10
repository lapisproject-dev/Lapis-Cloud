package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

class ArticleEditorialAccessTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        fun member(
            role: AccountRole,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Redaktion Test"
                    it[email] = "editorial-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            memberIds += id
            return id
        }

        fun allowed(id: Uuid): Boolean = runCatching { transaction { ArticleEditorialAccess.requireEditorialMember(id) } }.isSuccess

        test("REVIEW_ROLES is exactly BOARD and ADMIN") {
            ArticleEditorialAccess.REVIEW_ROLES shouldBe setOf(AccountRole.BOARD, AccountRole.ADMIN)
        }

        test("ArticleService takes its review roles from ArticleEditorialAccess, not from a second literal") {
            val dir =
                File("src/main/kotlin/network/lapis/cloud/server/rpc").let {
                    if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc")
                }
            val source = File(dir, "ArticleService.kt").readText()
            source.contains("ArticleEditorialAccess.REVIEW_ROLES") shouldBe true
            source.contains("arrayOf(AccountRole.BOARD") shouldBe false
        }

        test("only an ACTIVE member with BOARD or ADMIN passes; MEMBER, non-ACTIVE and unknown ids are Forbidden") {
            allowed(member(AccountRole.BOARD)) shouldBe true
            allowed(member(AccountRole.ADMIN)) shouldBe true
            allowed(member(AccountRole.MEMBER)) shouldBe false
            allowed(member(AccountRole.BOARD, MemberStatus.WITHDRAWN)) shouldBe false
            allowed(member(AccountRole.ADMIN, MemberStatus.FRIEND)) shouldBe false
            allowed(Uuid.random()) shouldBe false
            val denied =
                runCatching {
                    transaction {
                        ArticleEditorialAccess.requireEditorialMember(
                            member(AccountRole.MEMBER),
                        )
                    }
                }.exceptionOrNull()
            (denied is ForbiddenException) shouldBe true
        }
    })
