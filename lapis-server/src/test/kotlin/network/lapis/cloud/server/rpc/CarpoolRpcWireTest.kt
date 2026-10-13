package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.uuid.Uuid

/** One Kilua RPC parameter that is a JSON string: the element is itself a JSON document (`"\"value\""`). */
private fun str(value: String) = "\"\\\"$value\\\"\""

/** The JSON document `null` as a parameter. */
private const val NULL = "\"null\""

/**
 * V1.9.125 -- the Kilua RPC wire of the carpool service against the FULL `module()`: the route index (`/rpc/routeCarpoolServiceManagerN`) is the
 * 0-based position of the method in `ICarpoolService`. New methods were appended at the END (indices 6..15) and the six originals did not move;
 * a reordering would silently re-point every deployed client, so the indices are pinned here against observable behaviour (a guest is let
 * through exactly where the allowlist says, and refused everywhere else).
 */
class CarpoolRpcWireTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        suspend fun io.ktor.client.HttpClient.rpc(
            index: Int,
            method: String,
            member: Uuid,
            params: String,
        ): String =
            post("/rpc/routeCarpoolServiceManager$index") {
                header("X-Member-Id", member.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"id":1,"jsonrpc":"2.0","method":"$method","params":$params}""")
            }.bodyAsText()

        test("the route indices 0..15 belong to the methods in declaration order: a guest passes exactly the allowlist") {
            testApplication {
                application { module() }
                val driver = data.member(label = "wire-driver", displayName = "Wire Fahrer")
                val guest = data.member(label = "wire-guest", status = MemberStatus.GUEST)
                data.posting(author = driver, seats = 2, notes = "Telefon 0171 123")
                val bogus = str(Uuid.random().toString())
                // Allowlist: answered normally (a result or a not-found for the bogus id), never a ForbiddenException.
                val feed = client.rpc(0, "listPostings", guest, "[$NULL]")
                feed shouldNotContain "ForbiddenException"
                feed shouldNotContain "Wire Fahrer" // the member name is hidden from a guest before a confirmed seat
                feed shouldContain "Mitglied"
                feed shouldNotContain "Telefon 0171 123" // the free text never reaches a guest
                listOf("\"memberId\"", "\"personId\"", "\"email\"", "\"authorMemberId\"").forEach { feed shouldNotContain it }
                client.rpc(6, "requestParticipation", guest, "[$bogus]") shouldContain "NotFoundException"
                client.rpc(9, "confirmParticipation", guest, "[$bogus]") shouldContain "NotFoundException"
                client.rpc(10, "declineParticipation", guest, "[$bogus]") shouldContain "NotFoundException"
                client.rpc(11, "withdrawParticipation", guest, "[$bogus]") shouldContain "NotFoundException"
                client.rpc(12, "listMyParticipations", guest, "[]") shouldNotContain "ForbiddenException"
                // Everything else: Forbidden.
                client.rpc(1, "listMyPostings", guest, "[]") shouldContain "ForbiddenException"
                client.rpc(7, "findInvitee", guest, "[${str("Maria")},${str("Muster")}]") shouldContain "ForbiddenException"
                client.rpc(13, "listParticipants", guest, "[$bogus]") shouldContain "ForbiddenException"
                client.rpc(14, "setMeetingNote", guest, "[$bogus,$NULL]") shouldContain "ForbiddenException"
                client.rpc(15, "getPassengerHint", guest, "[${str("2026-10-13")},${str("2026-10-14")}]") shouldContain "ForbiddenException"
            }
        }

        test("a JSON written by the pre-V1.9.125 server (without the new fields) still decodes, with defaults") {
            val old =
                """{"id":"a","type":"OFFER","fromPlace":"A","toPlace":"B","departureDate":"2026-11-01","departureTime":null,""" +
                    """"seatsOffered":3,"notes":null,"authorDisplayName":"X","isOwn":false,"isPast":false,"createdAt":"2026-10-01T10:00:00"}"""
            val dto = Json.decodeFromString(CarpoolPostingDto.serializer(), old)
            dto.type shouldBe CarpoolPostingType.OFFER
            dto.seatsFree shouldBe null
            dto.myParticipation shouldBe null
            dto.meetingNote shouldBe null
            dto.openRequests shouldBe 0
        }
    })
