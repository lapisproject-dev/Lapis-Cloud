package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.34 -- [DirectMessageService.listConversation] returns at most [MAX_CONVERSATION_MESSAGES] (the NEWEST), and a third
 * party still sees nothing of a conversation it is not part of. Uses its own throwaway members so the seeded accounts' unread counters
 * (asserted exactly in `ServiceIntegrationTest`) stay untouched.
 */
class DirectMessageConversationLimitTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                DirectMessageTable.deleteWhere { (senderId inList createdMemberIds) or (recipientId inList createdMemberIds) }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createActiveMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DM-Limit-Test Mitglied"
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

        test("listConversation returns only the newest 200 messages, and a third party sees none") {
            val alice = createActiveMember("dm-limit-alice@example.test")
            val bob = createActiveMember("dm-limit-bob@example.test")
            val carol = createActiveMember("dm-limit-carol@example.test")
            val total = MAX_CONVERSATION_MESSAGES + 5
            transaction {
                DirectMessageTable.batchInsert((0 until total).toList()) { index ->
                    this[DirectMessageTable.id] = Uuid.random()
                    this[DirectMessageTable.senderId] = if (index % 2 == 0) alice else bob
                    this[DirectMessageTable.recipientId] = if (index % 2 == 0) bob else alice
                    this[DirectMessageTable.body] = "msg-$index"
                    this[DirectMessageTable.sentAt] = LocalDateTime(2026, 1, 1, index / 60, index % 60, 0)
                }
            }

            testApplication {
                application {
                    routing {
                        get("/test/conversation/{other}") {
                            val messages = DirectMessageService(call).listConversation(call.parameters["other"]!!)
                            call.respondText("${messages.size}:${messages.first().body}:${messages.last().body}")
                        }
                        get("/test/conversation-size/{other}") {
                            call.respondText(DirectMessageService(call).listConversation(call.parameters["other"]!!).size.toString())
                        }
                    }
                }
                val own = client.get("/test/conversation/$bob") { header("X-Member-Id", alice.toString()) }.bodyAsText()
                own shouldBe "$MAX_CONVERSATION_MESSAGES:msg-${total - 1}:msg-5"
                val third = client.get("/test/conversation-size/$bob") { header("X-Member-Id", carol.toString()) }.bodyAsText()
                third shouldBe "0"
            }
        }
    })
