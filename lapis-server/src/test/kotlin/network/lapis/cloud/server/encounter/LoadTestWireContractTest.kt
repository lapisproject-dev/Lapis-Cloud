package network.lapis.cloud.server.encounter

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.applyRoutes
import dev.kilua.rpc.getAllServiceManagers
import dev.kilua.rpc.initRpc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.rpc.installRpcErrorSanitizer
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterFloorDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleDto
import network.lapis.cloud.shared.domain.EncounterTableDto
import network.lapis.cloud.shared.domain.EncounterTableTokenAnswer
import network.lapis.cloud.shared.domain.EncounterTableTokenDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.ServiceBusyException
import java.io.File

/**
 * V1.9.149 -- the wire contract of the load-test tool (`scripts/loadtest`). The tool speaks Kilua RPC by hand; this test posts the very same
 * committed fixtures (`scripts/loadtest/test/fixtures/request-*.json`, also encoded and compared by the Node tests) to the REAL Kilua route
 * of [IEncounterSpaceService] (with a stub service behind it) and checks (1) that every route index lands on the intended method with the
 * intended arguments, so a reordering of the interface fails here and not in front of a real instance, and (2) that the real answers have
 * exactly the shape of `response-*.json`, which the Node decoder is tested against.
 */
class LoadTestWireContractTest :
    FunSpec({
        val spaceId = "00000000-0000-4000-8000-0000000000a1"
        val routes = Json.parseToJsonElement(LoadTestFiles.read("scripts/loadtest/lib/routes.json")).jsonObject["methods"]!!.jsonObject

        fun fixture(name: String): JsonElement = Json.parseToJsonElement(LoadTestFiles.read("scripts/loadtest/test/fixtures/$name.json"))

        suspend fun HttpClient.rpc(
            method: String,
            body: String,
        ): String {
            val index = routes[method]!!.jsonPrimitive.int
            return post("/rpc/routeEncounterSpaceServiceManager$index") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
        }

        val expectedCalls: Map<String, List<Any?>> =
            mapOf(
                "enterSpace" to listOf(spaceId, null),
                "leaveSpace" to listOf(spaceId),
                "listPresent" to listOf(spaceId),
                "selectSeat" to listOf(spaceId, 5),
                "joinTable" to listOf(spaceId, 2, 3),
                "leaveTable" to listOf(spaceId),
                "tableToken" to listOf(spaceId),
                "listTables" to listOf(spaceId),
            )

        test("every request fixture reaches the intended method with the intended arguments through the real Kilua route") {
            val stub = RecordingEncounterStub()
            testApplication {
                application {
                    installRpcErrorSanitizer()
                    initRpc { registerService(IEncounterSpaceService::class) { stub } }
                    routing { getAllServiceManagers().forEach { applyRoutes(it) } }
                }
                for ((method, args) in expectedCalls) {
                    stub.calls.clear()
                    val body = LoadTestFiles.read("scripts/loadtest/test/fixtures/request-$method.json")
                    val answer = Json.parseToJsonElement(client.rpc(method = method, body = body)).jsonObject
                    (answer["error"] == null || answer["error"] is kotlinx.serialization.json.JsonNull) shouldBe true
                    stub.calls shouldBe listOf(method to args)
                    answer shouldBe fixture("response-$method")
                }
            }
        }

        test("typed refusals travel as exceptionType + exceptionJson exactly as the fixtures say") {
            val stub = RecordingEncounterStub()
            testApplication {
                application {
                    installRpcErrorSanitizer()
                    initRpc { registerService(IEncounterSpaceService::class) { stub } }
                    routing { getAllServiceManagers().forEach { applyRoutes(it) } }
                }
                val cases =
                    listOf(
                        Triple("selectSeat", ConflictException(message = "Seat taken"), "Conflict"),
                        Triple(
                            "joinTable",
                            ServiceBusyException(message = "Too many table changes -- please wait a moment"),
                            "ServiceBusy",
                        ),
                        Triple("enterSpace", ForbiddenException(), "Forbidden"),
                        Triple("joinTable", BadRequestException(message = "Table or seat out of range"), "BadRequest"),
                    )
                for ((method, failure, short) in cases) {
                    stub.failWith = failure
                    val body = LoadTestFiles.read("scripts/loadtest/test/fixtures/request-$method.json")
                    val answer = Json.parseToJsonElement(client.rpc(method = method, body = body)).jsonObject
                    answer["exceptionType"]!!.jsonPrimitive.content shouldBe "network.lapis.cloud.shared.rpc.${failure::class.simpleName}"
                    answer shouldBe fixture("response-exception-$short")
                }
            }
        }

        test("the request fixtures are in the shape of the real wire: every parameter is a JSON document inside a JSON string") {
            for (method in expectedCalls.keys) {
                val request = fixture("request-$method").jsonObject
                request["method"]!!.jsonPrimitive.content shouldBe method
                val params: JsonArray = request["params"]!!.jsonArray
                params.forEach { (it as JsonPrimitive).isString shouldBe true }
            }
        }
    })

/** Reads a repository file from the module directory (`lapis-server`) or from the repository root, like the other file-reading tests. */
internal object LoadTestFiles {
    fun read(relative: String): String = (File("../$relative").takeIf { it.exists() } ?: File(relative)).readText()

    fun exists(relative: String): Boolean = File("../$relative").exists() || File(relative).exists()

    fun root(relative: String): File = File("../$relative").takeIf { it.exists() } ?: File(relative)
}

/** The service behind the real Kilua route: records `(method, arguments)` and answers canned values, or throws [failWith]. */
private class RecordingEncounterStub : IEncounterSpaceService {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    var failWith: AbstractServiceException? = null

    private fun <T> answer(
        name: String,
        args: List<Any?>,
        value: () -> T,
    ): T {
        calls += name to args
        failWith?.let { throw it }
        return value()
    }

    private fun unused(): Nothing = error("not part of the load-test contract")

    private fun join() =
        ConferenceJoinTokenDto(
            roomId = "room-1",
            livekitRoomName = "lk-room-1",
            serverUrl = "wss://livekit.example.test",
            token = "TOKEN-PLACEHOLDER",
            identity = "identity-1",
            displayName = "LT-01",
            role = ConferenceRole.PARTICIPANT,
            expiresAt = LocalDateTime(year = 2026, month = 10, day = 17, hour = 12, minute = 0),
            turnServers =
                listOf(
                    ConferenceTurnServer(
                        urls = listOf("turn:turn.example.test:3478"),
                        username = "user-placeholder",
                        credential = "CREDENTIAL-PLACEHOLDER",
                    ),
                ),
        )

    private fun tableToken() = EncounterTableTokenDto(join = join(), canPublish = true, table = 2, tableSeat = 3)

    override suspend fun enterSpace(
        spaceId: String,
        consent: EncounterConsentInput?,
    ): EncounterEntryDto =
        answer(name = "enterSpace", args = listOf(spaceId, consent?.consentVersion)) {
            EncounterEntryDto(join = join(), presenceRole = EncounterPresenceRole.CONGREGATION, canPublish = false, canPublishData = true)
        }

    override suspend fun leaveSpace(spaceId: String) = answer(name = "leaveSpace", args = listOf(spaceId)) { Unit }

    override suspend fun listPresent(spaceId: String): List<EncounterPresentDto> =
        answer(name = "listPresent", args = listOf(spaceId)) {
            listOf(
                EncounterPresentDto(
                    memberId = "member-1",
                    displayName = "LT-01",
                    role = EncounterPresenceRole.CONGREGATION,
                    isGuest = false,
                    seat = 5,
                ),
            )
        }

    override suspend fun selectSeat(
        spaceId: String,
        seat: Int?,
    ): List<EncounterPresentDto> =
        answer(name = "selectSeat", args = listOf(spaceId, seat)) {
            listOf(
                EncounterPresentDto(
                    memberId = "member-1",
                    displayName = "LT-01",
                    role = EncounterPresenceRole.CONGREGATION,
                    isGuest = false,
                    seat = seat,
                ),
            )
        }

    override suspend fun joinTable(
        spaceId: String,
        table: Int,
        seat: Int,
    ): EncounterTableTokenDto = answer(name = "joinTable", args = listOf(spaceId, table, seat)) { tableToken() }

    override suspend fun leaveTable(spaceId: String) = answer(name = "leaveTable", args = listOf(spaceId)) { Unit }

    override suspend fun tableToken(spaceId: String): EncounterTableTokenAnswer =
        answer(name = "tableToken", args = listOf(spaceId)) { EncounterTableTokenAnswer(token = tableToken()) }

    override suspend fun listTables(spaceId: String): List<EncounterTableDto> =
        answer(name = "listTables", args = listOf(spaceId)) {
            listOf(EncounterTableDto(table = 0, seats = 6, occupiedSeats = listOf(1, 2), quieted = false))
        }

    override suspend fun listSpaces(): List<EncounterSpaceDto> = unused()

    override suspend fun getSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun getEntryInfo(spaceId: String): EncounterEntryInfoDto = unused()

    override suspend fun createSpace(input: EncounterSpaceInput): EncounterSpaceDto = unused()

    override suspend fun updateSpace(
        spaceId: String,
        input: EncounterSpaceInput,
    ): EncounterSpaceDto = unused()

    override suspend fun archiveSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun listSpaceRoles(spaceId: String): List<EncounterSpaceRoleDto> = unused()

    override suspend fun setSpaceRoles(
        spaceId: String,
        assignments: List<EncounterSpaceRoleAssignmentInput>,
    ): List<EncounterSpaceRoleDto> = unused()

    override suspend fun openSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun closeSpace(spaceId: String): EncounterSpaceDto = unused()

    override suspend fun removeFromSpace(
        spaceId: String,
        memberId: String,
    ): Unit = unused()

    override suspend fun silenceInSpace(
        spaceId: String,
        memberId: String,
    ): Unit = unused()

    override suspend fun quietTable(
        spaceId: String,
        table: Int,
        quiet: Boolean,
    ): Unit = unused()

    override suspend fun sendToPlenum(
        spaceId: String,
        memberId: String,
    ): Unit = unused()

    override suspend fun blessSpace(spaceId: String): Unit = unused()

    override suspend fun ringBell(spaceId: String): Unit = unused()

    override suspend fun grantFloor(
        spaceId: String,
        memberId: String,
    ): EncounterFloorDto = unused()

    override suspend fun extendFloor(
        spaceId: String,
        seconds: Int,
    ): EncounterFloorDto = unused()

    override suspend fun endFloor(spaceId: String): Unit = unused()

    override suspend fun floorState(spaceId: String): EncounterFloorDto = unused()
}
