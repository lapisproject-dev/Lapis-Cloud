package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val NAME_PREFIX = "AuthzTier-"

/**
 * Welle V1.9.18 -- the role gates of the tier administration. `createMembershipTier`/
 * `updateMembershipTier`/`listMembershipTierOverview` are TREASURER/ADMIN ONLY (BOARD is deliberately
 * NOT admitted, unlike most of the finance group); `listMembershipTiers` stays open to every
 * authenticated caller because the member-facing relief form reads it -- pinned here so a future
 * "tidy-up" cannot silently close it (or, worse, open the overview).
 */
class MembershipTierAdminAuthzTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                val tierIds =
                    MembershipTierTable
                        .selectAll()
                        .where {
                            MembershipTierTable.name like "$NAME_PREFIX%"
                        }.map { it[MembershipTierTable.id] }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                MembershipTierTable.deleteWhere { id inList tierIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun newPlainMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Tier-Authz Mitglied"
                    it[email] = "tier-authz-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
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

        fun uniqueName() = "$NAME_PREFIX${Uuid.random().toString().take(8)}"

        suspend fun io.ktor.client.HttpClient.asCaller(
            caller: String,
            method: String,
            path: String,
        ): HttpResponse = if (method == "GET") get(path) { header("X-Member-Id", caller) } else post(path) { header("X-Member-Id", caller) }

        test("BOARD and a plain MEMBER get 403 on create, update and the overview") {
            testApplication {
                application {
                    install(StatusPages) { installMembershipTierExceptionHandlers() }
                    routing { registerMembershipTierTestRoutes() }
                }
                val member = newPlainMember().toString()
                // a real tier so that update is rejected by the ROLE gate, not by NotFound
                val tierPipe = client.asCaller(TREASURER_ID, "POST", "/test/tier/create?name=${uniqueName()}")
                tierPipe.status shouldBe HttpStatusCode.OK
                val tierId = tierPipe.bodyAsText().split("|")[0]

                listOf(BOARD_ID, member).forEach { caller ->
                    client.asCaller(caller, "POST", "/test/tier/create?name=${uniqueName()}").status shouldBe HttpStatusCode.Forbidden
                    client.asCaller(caller, "POST", "/test/tier/update?id=$tierId&name=${uniqueName()}").status shouldBe
                        HttpStatusCode.Forbidden
                    client.asCaller(caller, "GET", "/test/tier/overview").status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("TREASURER and ADMIN may create, update and read the overview") {
            testApplication {
                application {
                    install(StatusPages) { installMembershipTierExceptionHandlers() }
                    routing { registerMembershipTierTestRoutes() }
                }
                listOf(TREASURER_ID, ADMIN_ID).forEach { caller ->
                    val created = client.asCaller(caller, "POST", "/test/tier/create?name=${uniqueName()}")
                    created.status shouldBe HttpStatusCode.OK
                    val tierId = created.bodyAsText().split("|")[0]
                    client.asCaller(caller, "POST", "/test/tier/update?id=$tierId&name=${uniqueName()}&amount=12.50").status shouldBe
                        HttpStatusCode.OK
                    client.asCaller(caller, "GET", "/test/tier/overview").status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("listMembershipTiers stays open to every authenticated caller (member-facing relief form reads it)") {
            testApplication {
                application {
                    install(StatusPages) { installMembershipTierExceptionHandlers() }
                    routing { registerMembershipTierTestRoutes() }
                }
                val member = newPlainMember().toString()
                client.asCaller(member, "GET", "/test/tier/list").status shouldBe HttpStatusCode.OK
                client.asCaller(BOARD_ID, "GET", "/test/tier/list").status shouldBe HttpStatusCode.OK
            }
        }

        test("an unauthenticated caller gets 401 on every tier endpoint") {
            testApplication {
                application {
                    install(StatusPages) { installMembershipTierExceptionHandlers() }
                    routing { registerMembershipTierTestRoutes() }
                }
                client.get("/test/tier/overview").status shouldBe HttpStatusCode.Unauthorized
                client.post("/test/tier/create?name=x").status shouldBe HttpStatusCode.Unauthorized
                client.get("/test/tier/list").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a forbidden create writes no tier row and no audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMembershipTierExceptionHandlers() }
                    routing { registerMembershipTierTestRoutes() }
                }
                val name = uniqueName()
                val auditBefore =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                AuditLogEntryTable.entityType eq
                                    AuditEntityType.MEMBERSHIP_TIER
                            }.count()
                    }
                client.asCaller(BOARD_ID, "POST", "/test/tier/create?name=$name").status shouldBe HttpStatusCode.Forbidden
                transaction { MembershipTierTable.selectAll().where { MembershipTierTable.name eq name }.count() } shouldBe 0L
                transaction {
                    AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityType eq AuditEntityType.MEMBERSHIP_TIER }.count()
                } shouldBe
                    auditBefore
            }
        }
    })
