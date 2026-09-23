package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberTable
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
 * V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern" -- exercises
 * [KeycloakLinkPersonalData] directly (no HTTP layer needed), same house style
 * [MemberFamilyPersonalDataTest] establishes.
 */
class KeycloakLinkPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId inList createdMemberIds }
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
                    it[displayName] = "Keycloak-PersonalData Testmitglied"
                    it[MemberTable.email] = email
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

        fun link(memberId: Uuid): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                KeycloakAccountLinkTable.insert {
                    it[KeycloakAccountLinkTable.id] = id
                    it[KeycloakAccountLinkTable.memberId] = memberId
                    it[keycloakIssuer] = "https://keycloak.example.org/realms/lapis"
                    it[keycloakSubject] = "subject-${Uuid.random()}"
                    it[linkedAt] = now
                    it[linkedBy] = null
                    it[lastLoginAt] = now
                }
            }
            return id
        }

        test("coveredTables covers exactly keycloak_account_link") {
            KeycloakLinkPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("keycloak_account_link")
        }

        test("exportMember includes the link's issuer, timestamps, but never the subject") {
            val memberId = createMember(email = "keycloak-export-${Uuid.random()}@example.org")
            link(memberId)

            val exported = transaction { KeycloakLinkPersonalData.exportMember(memberId) }
            val linkJson = exported["keycloakLink"]!!.jsonObject
            linkJson["keycloakIssuer"]!!.jsonPrimitive.content shouldBe "https://keycloak.example.org/realms/lapis"
            linkJson.containsKey("keycloakSubject") shouldBe false
        }

        test("exportMember for a member without a link produces no keycloakLink entry") {
            val memberId = createMember(email = "keycloak-export-nolink-${Uuid.random()}@example.org")

            val exported = transaction { KeycloakLinkPersonalData.exportMember(memberId) }
            exported.containsKey("keycloakLink") shouldBe false
        }

        test("eraseMember hard-deletes the link row") {
            val memberId = createMember(email = "keycloak-erase-${Uuid.random()}@example.org")
            link(memberId)

            val outcomes =
                transaction {
                    KeycloakLinkPersonalData.eraseMember(
                        memberId = memberId,
                        mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                    )
                }
            val outcome = outcomes.single { it.table == "keycloak_account_link" }
            outcome.rowsDeleted shouldBe 1

            transaction {
                (KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq memberId }.count()) shouldBe 0
            }
        }
    })
