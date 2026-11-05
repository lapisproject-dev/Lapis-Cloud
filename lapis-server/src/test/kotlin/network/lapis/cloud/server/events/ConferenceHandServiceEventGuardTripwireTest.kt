package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan

/**
 * V1.9.127 -- same shape as [EventConferenceGuardTripwireTest] for the raised-hand service: every `roomId` method decides about event rooms
 * (the shared `authorize` refers to the guard, or the method is allowlisted with its reason), the guest identity of an event is never
 * minted here, and the state is touched only by the service and the reconciler.
 */
class ConferenceHandServiceEventGuardTripwireTest :
    FunSpec({
        /** method -> why it is safe without referring to the guard itself. Empty on purpose: today every method goes through `authorize`. */
        val allowlist = emptyMap<String, String>()

        test("every roomId method of ConferenceHandService authorizes through the shared function that carries the event guard") {
            val file = EncounterSourceScan.mainFile("rpc/ConferenceHandService.kt")
            val fns = EncounterSourceScan.functions(file)
            val roomIdMethods = fns.filter { it.isOverride && Regex("""\broomId\b""").containsMatchIn(it.params) }
            roomIdMethods.size shouldBe 5
            roomIdMethods
                .filter { it.name !in allowlist && !it.body.contains("authorize(roomId = roomId") }
                .map { it.name }
                .shouldBeEmpty()
            val authorize = fns.first { it.name == "authorize" }
            authorize.body shouldContain "EventConferenceGuard.requireParticipantOrModerator"
            authorize.body shouldContain "EncounterRoomGuard.isEncounterRoom"
            authorize.body shouldContain "requireConferenceEligibleMembership"
            // no allowlist entry may be stale
            allowlist.keys.filterNot { k -> fns.any { it.name == k } }.shouldBeEmpty()
        }

        test("the moderation methods check the moderator flag before any state is touched") {
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceHandService.kt"))
            listOf("lowerHandOf", "lowerAllHands").forEach { name ->
                val body = fns.first { it.name == name }.body
                body shouldContain "!room.isModerator"
                (body.indexOf("!room.isModerator") < body.indexOf("mutate(")) shouldBe true
            }
            fns.first { it.name == "authorize" }.body shouldContain "ConferenceModeratorAuthority.isModerator"
        }

        test(
            "the hand state is written by the hand service and the reconciler only; the hands topic is sent by those two and nobody else (the size limit of SendData merely names it)",
        ) {
            val stateUsers =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("ConferenceHandQueueState") }
                    .map { it.name }
                    .toSet()
            stateUsers shouldBe
                setOf(
                    "ConferenceHandQueueState.kt",
                    "ConferenceHandQueueReconciler.kt",
                    "ConferenceHandService.kt",
                    "EventGuestHands.kt",
                    "Application.kt",
                )
            val topicUsers =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("CONFERENCE_HANDS_TOPIC") }
                    .map { it.name }
                    .toSet()
            // LiveKitAdminClient.kt only names the topic in `sendDataPayloadLimit` (V1.9.133: one SendData size limit per topic)
            topicUsers shouldBe
                setOf("ConferenceHandQueueReconciler.kt", "ConferenceHandService.kt", "EventGuestHands.kt", "LiveKitAdminClient.kt")
            EncounterSourceScan
                .functions(EncounterSourceScan.mainFile("rpc/ConferenceHandService.kt"))
                .size shouldBeGreaterThan 5
        }

        test("the hand service writes no audit entry and no database row (the list is volatile by design)") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("rpc/ConferenceHandService.kt").readText())
            listOf("AuditLogRecorder", ".insert", ".update(", "deleteWhere", "logger.info", "logger.debug").forEach {
                (code.contains(it)) shouldBe false
            }
            val reconciler = SourceScan.blank(EncounterSourceScan.mainFile("conference/ConferenceHandQueueReconciler.kt").readText())
            listOf("AuditLogRecorder", ".insert", ".update(", "deleteWhere").forEach { (reconciler.contains(it)) shouldBe false }
        }

        test(
            "V1.9.281: EventGuestHands verifies event, room and token before it acts, takes LiveKit only inside the lock and writes nothing",
        ) {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("events/EventGuestHands.kt").readText())
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("events/EventGuestHands.kt"))
            val verify = fns.first { it.name == "verify" }.body
            listOf(
                "EventConferenceGuard.eventIdOfRoom",
                "EncounterRoomGuard.isEncounterRoom",
                "endedAt",
                "EventGuestActionToken.verify",
            ).forEach {
                verify shouldContain it
            }
            // the token is checked OUTSIDE the DB transaction, after it
            (verify.indexOf("EventGuestActionToken.verify") > verify.lastIndexOf("transaction {")) shouldBe true
            val act = fns.first { it.name == "act" }.body
            act shouldContain "withRoomLock"
            (act.indexOf("listParticipants") > act.indexOf("withRoomLock")) shouldBe true
            act.contains("transaction {") shouldBe false
            listOf("AuditLogRecorder", ".insert", ".update(", "deleteWhere", "logger.info", "logger.debug").forEach {
                code.contains(it) shouldBe false
            }
        }

        test("V1.9.281: the guest route names neither the hand state nor the hands topic; it talks to EventGuestHands only") {
            val route = SourceScan.blank(EncounterSourceScan.mainFile("routes/EventOnlineGuestRoutes.kt").readText())
            route.contains("ConferenceHandQueueState") shouldBe false
            route.contains("CONFERENCE_HANDS_TOPIC") shouldBe false
            route shouldContain "EventGuestHands"
        }
    })
