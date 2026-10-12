package network.lapis.cloud.shared.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** V1.9.111 -- the new DTO fields are additive: payloads from before the wave (stale tabs, stored audit text) still decode. */
class MailingListDtoWireCompatTest {
    @Test
    fun oldMailingListPayloadDecodesWithDefaults() {
        val dto =
            Json.decodeFromString(
                MailingListDto.serializer(),
                """{"id":"a","name":"n","description":null,"createdBy":"b","subscriberCount":3,"isSubscribedByCurrentMember":true}""",
            )
        assertEquals(MailingListKind.MANUAL, dto.kind)
        assertFalse(dto.mandatory)
        assertNull(dto.ruleJson)
        assertNull(dto.ruleExplanation)
        assertNull(dto.currentMemberOptedOutAt)
    }

    @Test
    fun oldSendProgressPayloadDecodesWithDefaults() {
        val dto =
            Json.decodeFromString(
                MailingSendProgressDto.serializer(),
                """{"messageId":"m","total":1,"sent":0,"failed":0,"interrupted":0,"skipped":0,"pending":1,"pausedUntil":null,"pauseReason":null,"remainingSeconds":null}""",
            )
        assertFalse(dto.awaitingRecipientResolution)
    }

    @Test
    fun snapshotsRoundtripWithoutPersonalData() {
        val list = MailingListRuleSnapshot(kind = MailingListKind.DYNAMIC, mandatory = true, ruleJson = "{\"v\":1}")
        assertEquals(
            list,
            Json.decodeFromString(MailingListRuleSnapshot.serializer(), Json.encodeToString(MailingListRuleSnapshot.serializer(), list)),
        )
        val send = MailingDynamicSendSnapshot(listId = "l", ruleJson = "{}", recipientCount = 7, mandatory = false)
        assertEquals(
            send,
            Json.decodeFromString(
                MailingDynamicSendSnapshot.serializer(),
                Json.encodeToString(MailingDynamicSendSnapshot.serializer(), send),
            ),
        )
    }
}
