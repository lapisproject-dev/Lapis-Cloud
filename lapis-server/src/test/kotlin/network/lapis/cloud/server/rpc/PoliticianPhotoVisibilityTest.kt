package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PoliticianWeightSnapshotTable
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

private const val PHOTO_BOARD_ID = "00000000-0000-0000-0000-000000000002"
private val VALID_TOKEN = "A".repeat(43)

/**
 * V1.9.301 -- [PoliticianService] exposes `photoUrl` only while the member's photo is publicly servable
 * ([MemberPhotoStore.publicServableCondition], the same live rule as the delivery route). Every case is checked on all read paths
 * (`listPoliticians`, `getTopPoliticians`, `getPoliticianProfile`) and on the return value of `updateMandateText`.
 */
class PoliticianPhotoVisibilityTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        beforeTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = true
                }
            }
        }

        afterTest {
            transaction {
                PoliticianWeightSnapshotTable.deleteWhere { PoliticianWeightSnapshotTable.id eq PoliticianWeightSnapshotTable.id }
                PoliticianReactionTable.deleteWhere { PoliticianReactionTable.id eq PoliticianReactionTable.id }
                PoliticianProfileTable.deleteWhere { PoliticianProfileTable.id eq PoliticianProfileTable.id }
                MemberPhotoTable.deleteWhere { MemberPhotoTable.memberId inList createdMemberIds }
            }
        }

        afterSpec {
            transaction {
                MemberPhotoTable.deleteWhere { MemberPhotoTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = false
                }
            }
        }

        fun createMember(status: MemberStatus = MemberStatus.ACTIVE): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Foto Testmitglied"
                    it[email] = "pol-photo-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertPhoto(
            memberId: Uuid,
            visibility: MemberPhotoVisibility,
            token: String?,
        ) {
            transaction {
                MemberPhotoTable.insert {
                    it[id] = Uuid.random()
                    it[MemberPhotoTable.memberId] = memberId
                    it[storageKey] = "test-${Uuid.random()}"
                    it[contentType] = "image/jpeg"
                    it[widthPx] = 10
                    it[heightPx] = 10
                    it[sizeBytes] = 10L
                    it[uploadedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[MemberPhotoTable.visibility] = visibility
                    it[publicToken] = token
                    it[consentGrantedAt] = if (visibility == MemberPhotoVisibility.PUBLIC) LocalDateTime(2026, 1, 1, 0, 0) else null
                    it[consentTextVersion] = if (visibility == MemberPhotoVisibility.PUBLIC) "test" else null
                }
            }
        }

        fun withApp(block: suspend (HttpClient) -> Unit) =
            testApplication {
                application { routing { registerPhotoTestRoutes() } }
                block(client)
            }

        /** photoUrl of [memberId] on all four paths, in the order list / top / profile / updateMandateText. */
        suspend fun HttpClient.photoUrlsOf(memberId: Uuid): List<String> {
            val list =
                get("/test/list") {
                    header("X-Member-Id", PHOTO_BOARD_ID)
                }.bodyAsText().lines().first { it.startsWith("$memberId=") }.substringAfter("=")
            val top =
                get("/test/top") {
                    header("X-Member-Id", PHOTO_BOARD_ID)
                }.bodyAsText().lines().first { it.startsWith("$memberId=") }.substringAfter("=")
            val profile = get("/test/profile/$memberId") { header("X-Member-Id", PHOTO_BOARD_ID) }.bodyAsText()
            val updated = post("/test/update/$memberId") { header("X-Member-Id", PHOTO_BOARD_ID) }.bodyAsText()
            return listOf(list, top, profile, updated)
        }

        suspend fun HttpClient.grant(memberId: Uuid) {
            val response = post("/test/grant/$memberId") { header("X-Member-Id", PHOTO_BOARD_ID) }
            check(response.status == HttpStatusCode.OK) { "grant failed: ${response.status} ${response.bodyAsText()}" }
        }

        test("no photo: the politician still appears (LEFT JOIN, never a WHERE trap) and photoUrl is null on every path") {
            withApp { client ->
                val politician = createMember()
                client.grant(politician)
                client.photoUrlsOf(politician) shouldBe List(4) { "null" }
            }
        }

        test("PRIVATE photo: photoUrl is null") {
            withApp { client ->
                val politician = createMember()
                insertPhoto(politician, MemberPhotoVisibility.PRIVATE, token = null)
                client.grant(politician)
                client.photoUrlsOf(politician) shouldBe List(4) { "null" }
            }
        }

        test("release withdrawn: photoUrl is null again immediately") {
            withApp { client ->
                val politician = createMember()
                insertPhoto(politician, MemberPhotoVisibility.PUBLIC, token = VALID_TOKEN)
                client.grant(politician)
                client.photoUrlsOf(politician) shouldBe List(4) { "/public/member-photos/$VALID_TOKEN" }
                transaction { MemberPhotoStore.unpublish(politician) }
                client.photoUrlsOf(politician) shouldBe List(4) { "null" }
            }
        }

        test("member status outside MEMBER_PHOTO_ELIGIBLE: photoUrl is null") {
            withApp { client ->
                val politician = createMember()
                insertPhoto(politician, MemberPhotoVisibility.PUBLIC, token = VALID_TOKEN)
                client.grant(politician)
                transaction { MemberTable.update({ MemberTable.id eq politician }) { it[status] = MemberStatus.WITHDRAWN } }
                client.photoUrlsOf(politician) shouldBe List(4) { "null" }
            }
        }

        test("invalid token (does not match the public token pattern): photoUrl is null") {
            withApp { client ->
                val politician = createMember()
                insertPhoto(politician, MemberPhotoVisibility.PUBLIC, token = "not-a-valid-token")
                client.grant(politician)
                client.photoUrlsOf(politician) shouldBe List(4) { "null" }
            }
        }

        test("happy path: PUBLIC, valid token, eligible status gives exactly the relative public URL, consistent with the delivery rule") {
            withApp { client ->
                val politician = createMember()
                insertPhoto(politician, MemberPhotoVisibility.PUBLIC, token = VALID_TOKEN)
                client.grant(politician)
                client.photoUrlsOf(politician) shouldBe List(4) { "/public/member-photos/$VALID_TOKEN" }
                (transaction { MemberPhotoStore.findPublicServable(VALID_TOKEN) } != null) shouldBe true
            }
        }

        test("two politicians, one with and one without a photo: no mix-up") {
            withApp { client ->
                val withPhoto = createMember()
                val withoutPhoto = createMember()
                val otherToken = "B".repeat(43)
                insertPhoto(withPhoto, MemberPhotoVisibility.PUBLIC, token = otherToken)
                client.grant(withPhoto)
                client.grant(withoutPhoto)
                client.photoUrlsOf(withPhoto) shouldBe List(4) { "/public/member-photos/$otherToken" }
                client.photoUrlsOf(withoutPhoto) shouldBe List(4) { "null" }
            }
        }

        test(
            "tripwire: PoliticianService never touches a private photo path or the photo service, and the photo join ignores listing consent",
        ) {
            val source =
                listOf("../lapis-server/src/main/kotlin", "lapis-server/src/main/kotlin")
                    .map { File(it, "network/lapis/cloud/server/rpc/PoliticianService.kt") }
                    .first { it.exists() }
                    .readLines()
                    .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }
                    .joinToString("\n")
            listOf("storageKey", "member-photo/own", "MemberPhotoService").forEach {
                (source.contains(it)) shouldBe false
            }
            // POLITICIAN_LISTING is used by revokePoliticianStatus only; the photo join (profileJoin) must not depend on it.
            val join = source.substringAfter("private fun profileJoin()").substringBefore("private fun memberDisplayName")
            (join.contains("POLITICIAN_LISTING") || join.contains("Consent")) shouldBe false
        }
    })

private fun Route.registerPhotoTestRoutes() {
    post("/test/grant/{memberId}") {
        val p =
            try {
                PoliticianService(call = call).grantPoliticianStatus(memberId = call.parameters["memberId"]!!, mandateText = null)
            } catch (e: Exception) {
                call.respondText(e.stackTraceToString().take(1500), status = io.ktor.http.HttpStatusCode.Conflict)
                return@post
            }
        call.respondText(p.photoUrl.toString())
    }
    get("/test/list") {
        val list = PoliticianService(call = call).listPoliticians(includeFormer = true)
        call.respondText(list.joinToString("\n") { "${it.memberId}=${it.photoUrl}" })
    }
    get("/test/top") {
        val list = PoliticianService(call = call).getTopPoliticians(limit = 1000)
        call.respondText(list.joinToString("\n") { "${it.memberId}=${it.photoUrl}" })
    }
    get("/test/profile/{memberId}") {
        call.respondText(PoliticianService(call = call).getPoliticianProfile(call.parameters["memberId"]!!).photoUrl.toString())
    }
    post("/test/update/{memberId}") {
        call.respondText(
            PoliticianService(
                call = call,
            ).updateMandateText(memberId = call.parameters["memberId"]!!, mandateText = null).photoUrl.toString(),
        )
    }
}
