package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan

/**
 * Welle V1.9.61 -- tripwires that keep the ordinary conference RPC surface fenced off from encounter sessions.
 *
 * 1. Every `override suspend fun` with a `roomId`/`breakoutRoomId` parameter in the six `Conference*Service` files refers to the guard
 *    ([EncounterRoomGuard], the fenced `requireRoomExists` helper of notes/whiteboard, or the guarded `requireRoomEntryAuthorization`)
 *    or is in the allowlist below, each with its reason. A NEW room-id method cannot be added without a decision.
 * 2. The helpers those methods rely on really contain the guard.
 * 3. Every call of `LiveKitAccessToken.mintParticipantToken` names `canPublish =` and `canPublishData =` (the compiler already forces
 *    the arguments; the NAMES make a `true, true` copy-paste visible in review), and only the three known token-minting classes call it.
 */
class EncounterRoomGuardTripwireTest :
    FunSpec({
        val guardTokens = listOf("EncounterRoomGuard", "requireRoomExists(")

        /** file -> method -> why it is safe without the guard. */
        val allowlist =
            mapOf(
                "rpc/ConferenceStreamingService.kt" to
                    mapOf(
                        "getActiveStream" to
                            "calls requireRoomEntryAuthorization(allowEncounterRoom = true): the 'a stream is running' badge stays visible to those present",
                        "listStreams" to "moderator-only (ConferenceModeratorAuthority); lists streams, mints nothing",
                        "startStream" to "requireEncounterStreamIsPulpitOnly fences the layout and the participant",
                    ),
                "rpc/ConferenceBreakoutService.kt" to
                    mapOf(
                        "returnToMainRoom" to "only closes the caller's OWN assignment row; no token, no data about anybody else",
                    ),
            )
        val files =
            listOf(
                "rpc/ConferenceService.kt",
                "rpc/ConferenceBreakoutService.kt",
                "rpc/ConferenceRecordingService.kt",
                "rpc/ConferenceNotesService.kt",
                "rpc/ConferenceWhiteboardService.kt",
                "rpc/ConferenceStreamingService.kt",
            )

        test("every room-id method of the six conference services refers to the encounter guard or is allowlisted with a reason") {
            val unguarded = mutableListOf<String>()
            var inspected = 0
            files.forEach { rel ->
                val all = EncounterSourceScan.functions(EncounterSourceScan.mainFile(rel))
                val helpers = all.filter { !it.isOverride }.associateBy { it.name }

                fun guarded(body: String): Boolean =
                    guardTokens.any { body.contains(it) } ||
                        (body.contains("requireRoomEntryAuthorization(") && !body.contains("allowEncounterRoom = true"))

                all
                    .filter { it.isOverride && Regex("""\b(roomId|breakoutRoomId)\b""").containsMatchIn(it.params) }
                    .forEach { fn ->
                        inspected++
                        // one level of indirection: a private helper of the same file that the method calls (e.g. startRecordingInTransaction)
                        val viaHelper = helpers.values.any { h -> Regex("""\b${h.name}\(""").containsMatchIn(fn.body) && guarded(h.body) }
                        if (!guarded(fn.body) && !viaHelper && allowlist[rel]?.containsKey(fn.name) != true) unguarded += "$rel#${fn.name}"
                    }
            }
            inspected shouldBeGreaterThan 25
            unguarded.shouldBeEmpty()
        }

        test("the allowlist has no stale entries") {
            allowlist.forEach { (rel, methods) ->
                val names = EncounterSourceScan.functions(EncounterSourceScan.mainFile(rel)).map { it.name }.toSet()
                methods.keys.forEach { (it in names) shouldBe true }
            }
        }

        test("the helpers the notes/whiteboard methods rely on contain the guard") {
            listOf("rpc/ConferenceNotesService.kt", "rpc/ConferenceWhiteboardService.kt").forEach { rel ->
                val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile(rel))
                fns.single { it.name == "requireRoomExists" }.body shouldContain "EncounterRoomGuard.requireNotEncounterRoom"
                fns.single { it.name == "requireRoomStillOpen" }.body shouldContain "EncounterRoomGuard.requireNotEncounterRoom"
            }
        }

        test("requireRoomEntryAuthorization fences by default; only an explicit allowEncounterRoom = true opts out") {
            val fn =
                EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/MembershipGuards.kt")).single {
                    it.name ==
                        "requireRoomEntryAuthorization"
                }
            fn.params shouldContain "allowEncounterRoom: Boolean = false"
            fn.body shouldContain "if (!allowEncounterRoom) EncounterRoomGuard.requireNotEncounterRoom"
            // the only caller that opts out is getActiveStream
            val optOuts =
                SourceScan
                    .mainFiles()
                    .filter { it.readText().contains("allowEncounterRoom = true") }
                    .map { it.name }
            optOuts shouldBe listOf("ConferenceStreamingService.kt")
        }

        test(
            "the six services delegate their moderator check to ConferenceModeratorAuthority (no private copy of 'creator or privileged' remains)",
        ) {
            files.forEach { rel ->
                val text = SourceScan.blank(EncounterSourceScan.mainFile(rel).readText())
                // the pre-V1.9.61 private copy was `val isCreator = row[...createdByMemberId] == current.memberId` + `isPrivileged`
                Regex("""val\s+isCreator\s*=""").containsMatchIn(text) shouldBe false
            }
        }

        test(
            "every mintParticipantToken call names canPublish and canPublishData, and only the three known classes mint participant tokens",
        ) {
            val callers = mutableSetOf<String>()
            val unnamed = mutableListOf<String>()
            SourceScan.mainFiles().forEach { f ->
                val raw = f.readText()
                val blanked = SourceScan.blank(raw)
                Regex("""mintParticipantToken\(""").findAll(blanked).forEach { m ->
                    val before = blanked.substring(maxOf(0, m.range.first - 6), m.range.first)
                    if (before.contains("fun ")) return@forEach
                    callers += f.name
                    var depth = 0
                    var end = m.range.last
                    for (i in m.range.last until blanked.length) {
                        if (blanked[i] == '(') depth++
                        if (blanked[i] == ')') depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                    val call = raw.substring(m.range.first, end + 1)
                    if (!call.contains("canPublish =") || !call.contains("canPublishData =")) unnamed += "${f.name}: $call"
                }
            }
            unnamed.shouldBeEmpty()
            callers shouldBe setOf("ConferenceService.kt", "ConferenceBreakoutService.kt", "EncounterSpaceService.kt")
        }
    })
