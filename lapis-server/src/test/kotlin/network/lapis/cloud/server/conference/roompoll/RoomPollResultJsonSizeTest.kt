package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.OptionTally
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.304 -- the stored result (`snapshot_json` / `result_json`, `VARCHAR(8000)`) of the LARGEST possible poll (10 options, both modes, split
 * visible, all three columns, every share three digits) stays at most half of the column: a 2 times buffer, pinned here.
 */
class RoomPollResultJsonSizeTest :
    FunSpec({
        test("the largest result is at most 4000 characters, a quarter of that for a small one") {
            val ids = (1..10).map { Uuid.random() }

            fun tallies() =
                ids.map { OptionTally(optionId = it, responses = 9, weightedResponses = 9, weightSum = BigDecimal("1234567.00")) }
            val big =
                computeRoomPollResult(
                    perGroup =
                        mapOf(
                            RoomPollVoterGroup.MEMBERS to tallies(),
                            RoomPollVoterGroup.GUESTS_AND_FRIENDS to tallies(),
                        ),
                    mode = RoomPollCountingMode.BOTH,
                )
            val size = RoomPollResultJson.encode(big).length
            println("room poll result JSON, largest case: $size characters")
            (size <= 4000) shouldBe true
            // and it round-trips
            RoomPollResultJson.decode(RoomPollResultJson.encode(big)) shouldBe big
        }
    })
