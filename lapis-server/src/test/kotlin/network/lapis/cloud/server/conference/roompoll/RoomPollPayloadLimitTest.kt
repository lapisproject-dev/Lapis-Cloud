package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.conference.sendDataPayloadLimit
import network.lapis.cloud.shared.domain.ROOM_POLL_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ROOM_POLL_TOPIC

/** V1.9.304 -- the room-poll topic has its own explicit SendData limit and fits its empty packet. */
class RoomPollPayloadLimitTest :
    FunSpec({
        test("the topic has an explicit limit that holds the empty packet") {
            sendDataPayloadLimit(ROOM_POLL_TOPIC) shouldBe ROOM_POLL_MAX_PAYLOAD_BYTES
            ("{}".toByteArray().size <= sendDataPayloadLimit(ROOM_POLL_TOPIC)) shouldBe true
            ROOM_POLL_TOPIC shouldBe "lapis-room-poll"
        }
    })
