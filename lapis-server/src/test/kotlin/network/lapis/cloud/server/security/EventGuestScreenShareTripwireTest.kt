package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan
import java.io.File

/**
 * V1.9.282 -- structural guards of the guest screen share: an event guest's token never lists a screen share, the share is granted by the
 * moderator RPC only (no guest route, no guest action token), guest permissions are never built with the member helper, and LiveKit is never
 * called inside a transaction.
 */
class EventGuestScreenShareTripwireTest :
    FunSpec({
        fun main(path: String) = EncounterSourceScan.mainFile(path).readText()

        fun sharedFile(path: String): File {
            val base = File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared")
            return File(if (base.exists()) base else File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared"), path)
        }

        test(
            "the guest join mints its token with EVENT_GUEST, the member join with MEMBER, and the EVENT_GUEST branch has no screen share literal",
        ) {
            val join = main("events/EventOnlineJoin.kt")
            Regex("""sourcePolicy = LiveKitAccessToken\.ParticipantSourcePolicy\.EVENT_GUEST""").findAll(join).count() shouldBe 1
            Regex("""sourcePolicy = LiveKitAccessToken\.ParticipantSourcePolicy\.MEMBER""").findAll(join).count() shouldBe 1
            val token = main("conference/LiveKitAccessToken.kt")
            val branch = token.substringAfter("ParticipantSourcePolicy.EVENT_GUEST ->").substringBefore("val claims")
            branch shouldNotContain "screen"
            branch shouldContain "canPublishSources"
        }

        test("no guest route and no guest action token can grant or touch the screen share") {
            listOf("routes/EventOnlineGuestRoutes.kt", "events/EventGuestActionToken.kt").forEach { name ->
                val code = main(name)
                listOf(
                    "setGuestScreenShare",
                    "guestScreenShareState",
                    "EventGuestScreenShareGrants",
                    "guestConferencePermission",
                    "muteScreenShareTrack",
                ).forEach { forbidden -> code shouldNotContain forbidden }
            }
        }

        test("the two RPC methods are the LAST ones of IConferenceModerationService") {
            val names =
                Regex("""suspend fun (\w+)""")
                    .findAll(sharedFile("rpc/IConferenceModerationService.kt").readText())
                    .map { it.groupValues[1] }
                    .toList()
            names.takeLast(3) shouldBe listOf("resumeRoomCapture", "setGuestScreenShare", "guestScreenShareState")
        }

        test("LiveKit is never called inside a transaction in the new guest screen share code") {
            listOf("rpc/ConferenceModerationService.kt", "conference/EventGuestScreenShareReconciler.kt").forEach { name ->
                val blanked = SourceScan.blank(main(name))
                var from = 0
                while (true) {
                    val start = blanked.indexOf("transaction {", from)
                    if (start < 0) break
                    var depth = 0
                    var i = blanked.indexOf('{', start)
                    val open = i
                    while (i < blanked.length) {
                        if (blanked[i] == '{') depth++
                        if (blanked[i] == '}') {
                            depth--
                            if (depth == 0) break
                        }
                        i++
                    }
                    val inside = blanked.substring(open, i)
                    listOf("liveKitAdminClient", "liveKitCall", "withRoomLock").forEach { inside shouldNotContain it }
                    from = i
                }
            }
        }

        test("the production admin client overrides muteScreenShareTrack (the interface default throws)") {
            val code = main("conference/LiveKitAdminClient.kt")
            code shouldContain "override suspend fun muteScreenShareTrack"
            code.substringAfter("class HttpLiveKitAdminClient") shouldContain "override suspend fun muteScreenShareTrack"
        }

        test("a guest permission is never built with the member helper: lock, enforce and heal all have a guest branch") {
            main("rpc/ConferenceModerationService.kt").substringAfter("private suspend fun applyLock") shouldContain
                "guestConferencePermission("
            val reconciler = main("conference/ConferenceAudioPolicyReconciler.kt")
            reconciler shouldContain "guestConferencePermission("
            reconciler.substringAfter("private fun permissionFor") shouldContain "memberId == null"
            // both write-backs of the audio reconciler go through permissionFor, never through the member helper directly
            reconciler.substringAfter("private suspend fun healUnlock").substringBefore("private fun permissionFor") shouldNotContain
                "permission = conferencePermission("
            reconciler.substringAfter("private suspend fun enforceLock") shouldNotContain "permission = conferencePermission("
            // the screen share reconciler never touches the microphone: it only writes the guest helper
            val screen = main("conference/EventGuestScreenShareReconciler.kt")
            screen shouldContain "guestConferencePermission("
            screen shouldNotContain "permission = conferencePermission("
        }

        test("the new guest screen share code logs counts and exception class names only") {
            val allowed = setOf("counts.corrected", "counts.muted", "counts.removed", "counts.failed", "failedRooms", "e::class.simpleName")
            val offenders = mutableListOf<String>()
            val service = main("rpc/ConferenceModerationService.kt")
            val serviceSection =
                service
                    .substringAfter(
                        "// -- V1.9.282 guest screen share",
                    ).substringBefore("// -- LiveKit-side application of a lock change")
            val sections =
                mapOf(
                    "EventGuestScreenShareReconciler.kt" to main("conference/EventGuestScreenShareReconciler.kt"),
                    "EventGuestScreenShareGrants.kt" to main("conference/EventGuestScreenShareGrants.kt"),
                    "ConferenceModerationService.kt(guest section)" to serviceSection,
                )
            sections.forEach { (name, text) ->
                text.lines().forEachIndexed { index, line ->
                    if (!Regex("""logger\.(trace|debug|info|warn|error)\b""").containsMatchIn(line)) return@forEachIndexed
                    Regex("""\$\{([^}]*)}|\$([A-Za-z_]\w*)""").findAll(line).forEach { match ->
                        val interpolated = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        if (interpolated !in allowed) offenders += "$name:${index + 1}: $interpolated"
                    }
                }
                listOf("println(", "e.message", "printStackTrace").forEach { text shouldNotContain it }
            }
            offenders.shouldBeEmpty()
            serviceSection shouldContain "setGuestScreenShare"
        }
    })
