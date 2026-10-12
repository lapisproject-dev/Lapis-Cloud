package network.lapis.cloud.shared.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** V1.9.112 -- the new committee fields are additive: payloads from before the wave (stale tabs, stored text) still decode. */
class CommitteeRuleDtoWireCompatTest {
    @Test
    fun oldCommitteePayloadDecodesAsManualWithoutRule() {
        val dto =
            Json.decodeFromString(
                CommitteeDto.serializer(),
                """{"id":"a","name":"n","type":"WORKING_GROUP","description":"d","active":true,"quorumPercent":50,"createdAt":"2026-10-12T10:00:00"}""",
            )
        assertEquals(CommitteeMembershipMode.MANUAL, dto.membershipMode)
        assertNull(dto.ruleJson)
    }

    @Test
    fun oldCommitteeInputDecodesAsManual() {
        val input =
            Json.decodeFromString(
                CommitteeInput.serializer(),
                """{"name":"n","type":"OTHER","description":"d"}""",
            )
        assertEquals(CommitteeMembershipMode.MANUAL, input.membershipMode)
        assertNull(input.ruleJson)
    }

    @Test
    fun oldQuorumPayloadIsNotFrozen() {
        val dto =
            Json.decodeFromString(
                QuorumResultDto.serializer(),
                """{"meetingId":"m","eligibleMemberCount":4,"presentCount":2,"requiredCount":2,"quorumPercent":50,"met":true}""",
            )
        assertFalse(dto.frozen)
    }

    @Test
    fun seatEndReasonKeepsTheMemberStatusNames() {
        // stored values and JSON of seats ended before V1.9.112 must decode unchanged
        listOf("WITHDRAWN", "REJECTED", "DECEASED", "DONOR").forEach { name ->
            assertEquals(
                name,
                Json.decodeFromString(CommitteeSeatAutoEndReason.serializer(), "\"$name\"").name,
            )
        }
        assertEquals(CommitteeSeatAutoEndReason.RULE_MODE, Json.decodeFromString(CommitteeSeatAutoEndReason.serializer(), "\"RULE_MODE\""))
    }

    @Test
    fun auditSnapshotRoundtripsWithoutPersonalData() {
        val s =
            CommitteeRuleChangeSnapshot(
                event = "COMMITTEE_MODE_CHANGED",
                mode = CommitteeMembershipMode.RULE,
                eligibleCount = 3,
                endedSeats = 2,
            )
        assertEquals(
            s,
            Json.decodeFromString(
                CommitteeRuleChangeSnapshot.serializer(),
                Json.encodeToString(CommitteeRuleChangeSnapshot.serializer(), s),
            ),
        )
    }
}
