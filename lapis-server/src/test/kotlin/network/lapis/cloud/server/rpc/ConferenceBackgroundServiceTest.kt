package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceBackgroundImageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * Welle V1.9.4. Unit-level coverage for [ConferenceBackgroundService.listMine] -- exercised via a
 * throwaway HTTP route (mirrors the house style of the other `*ServiceTest` files that avoid
 * pulling in the full Kilua RPC transport for a single method): only the caller's own rows come
 * back, sorted by upload order, and a row whose main file is missing on disk is filtered out AND
 * self-healingly deleted.
 */
class ConferenceBackgroundServiceTest :
    FunSpec({
        val storageRoot = File("build/test-conference-background-service-storage")
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            storageRoot.mkdirs()
        }

        afterSpec {
            transaction {
                ConferenceBackgroundImageTable.deleteWhere { ConferenceBackgroundImageTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            storageRoot.deleteRecursively()
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Service Testmitglied"
                    it[email] = "conference-background-service-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
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

        fun insertRow(
            memberId: Uuid,
            withFile: Boolean,
        ): Uuid {
            val id = Uuid.random()
            val storageKey = "conference-backgrounds/$memberId/$id.jpg"
            transaction {
                ConferenceBackgroundImageTable.insert {
                    it[ConferenceBackgroundImageTable.id] = id
                    it[ConferenceBackgroundImageTable.memberId] = memberId
                    it[ConferenceBackgroundImageTable.storageKey] = storageKey
                    it[thumbStorageKey] = "$storageKey.thumb.jpg"
                    it[width] = 100
                    it[height] = 100
                    it[sizeBytes] = 10L
                    it[sha256] = "0".repeat(64)
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            if (withFile) {
                val file = storageRoot.resolve(storageKey)
                file.parentFile.mkdirs()
                file.writeBytes(byteArrayOf(1))
            }
            return id
        }

        test("listMine returns only the caller's own images, sorted by upload order") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/test/conference-backgrounds/list") {
                            val service = ConferenceBackgroundService(call = call, storageRoot = storageRoot)
                            call.respond(service.listMine())
                        }
                    }
                }
                val me = newMember()
                val other = newMember()
                val first = insertRow(memberId = me, withFile = true)
                val second = insertRow(memberId = me, withFile = true)
                insertRow(memberId = other, withFile = true)

                val response =
                    client.get("/test/conference-backgrounds/list") {
                        header("X-Member-Id", me.toString())
                    }
                val ids =
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonArrayIds()
                ids shouldBe listOf(first.toString(), second.toString())
            }
        }

        test("a row whose main file is missing is filtered out AND self-healingly deleted") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/test/conference-backgrounds/list") {
                            val service = ConferenceBackgroundService(call = call, storageRoot = storageRoot)
                            call.respond(service.listMine())
                        }
                    }
                }
                val me = newMember()
                val withFile = insertRow(memberId = me, withFile = true)
                val withoutFile = insertRow(memberId = me, withFile = false)
                // Review-Befund (MINOR): the file-less row's THUMBNAIL file, written here so the
                // self-healing delete below has something real to delete -- without this, the test
                // would pass even if the `storageRoot.resolve(thumbStorageKey).delete()` block in
                // ConferenceBackgroundService.listMine were removed entirely.
                val orphanedThumb = storageRoot.resolve("conference-backgrounds/$me/$withoutFile.jpg.thumb.jpg")
                orphanedThumb.parentFile.mkdirs()
                orphanedThumb.writeBytes(byteArrayOf(2))

                val response =
                    client.get("/test/conference-backgrounds/list") {
                        header("X-Member-Id", me.toString())
                    }
                val ids = Json.parseToJsonElement(response.bodyAsText()).jsonArrayIds()
                ids shouldBe listOf(withFile.toString())

                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.id eq withoutFile }.count()
                } shouldBe 0L
                orphanedThumb.exists() shouldBe false
            }
        }
    })

private fun JsonElement.jsonArrayIds(): List<String> = this.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
