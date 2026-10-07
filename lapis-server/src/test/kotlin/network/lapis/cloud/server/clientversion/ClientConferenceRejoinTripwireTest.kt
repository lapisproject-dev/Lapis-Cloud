package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Tripwire of V1.9.69: a second sign-in with the SAME account made two devices evict each other endlessly, because the client threw away
 * the disconnect reason and re-joined by itself every time. The sources are scanned as text (the conference page cannot be mounted in a
 * jsTest and a real eviction needs a LiveKit server, so this is the only automatic guard of the WIRING).
 *
 * What must stay true:
 * - the `RoomEvent.Disconnected` handler hands its reason on ([disconnectCauseOf]) instead of dropping it,
 * - `rejoinMainRoomToken` is called in exactly the four known places, and the automatic one sits in the `RejoinMain` branch that is only
 *   reached through [decideAfterDisconnect] with the shared guard,
 * - the displaced path neither says `leaveRoom` nor `leaveSpace` (both would close the participation / presence of the MEMBER and with it
 *   that of the device that is legitimately still in the call),
 * - the encounter view decides `DuplicateIdentity` BEFORE it reads the space or counts a re-entry.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private fun source(relative: String): String = File(CLIENT_DIR, relative).readText()

/** The source without whole-line comments (`//`, KDoc and block-comment lines). */
private fun code(text: String): String =
    text.lines().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }.joinToString("\n")

/** The text from the first [start] occurrence up to (excluding) the next [end] after it. */
private fun String.between(
    start: String,
    end: String,
): String {
    val from = indexOf(start)
    check(from >= 0) { "'$start' not found" }
    val to = indexOf(end, from + start.length)
    check(to >= 0) { "'$end' not found after '$start'" }
    return substring(from, to)
}

class ClientConferenceRejoinTripwireTest :
    FunSpec({
        test("the Disconnected handler hands its reason on and no longer drops it") {
            val session = code(source("livekit/LiveKitRoomSession.kt"))
            session shouldNotContain "onOwned(RoomEvent.Disconnected) { _, _, _, _ ->"
            val handler = session.between(start = "onOwned(RoomEvent.Disconnected)", end = "\n")
            handler shouldContain "disconnectCauseOf("
        }

        test("rejoinMainRoomToken is called in exactly the four known places, the automatic one in the RejoinMain branch") {
            val screen = code(source("ConferenceScreen.kt"))
            val calls = Regex("""rejoinMainRoomToken\(""").findAll(screen).count()
            withClue("automatic re-join (RejoinMain), the 'Zurück in den Hauptraum' button, and the manual resume (main + breakout)") {
                calls shouldBe 4
            }
            val branchStart = screen.indexOf("is PostDisconnectAction.RejoinMain ->")
            (branchStart >= 0) shouldBe true
            val branch = screen.substring(branchStart, minOf(screen.length, branchStart + 1200))
            branch shouldContain "rejoinMainRoomToken("
            // the deliberate resume is a click handler, never the automatic path
            screen.between(start = "suspend fun resumeToken()", end = "fun stopAndShowNotice") shouldContain "rejoinMainRoomToken("
        }

        test("the automatic decision goes through decideAfterDisconnect with the shared guard, and DuplicateIdentity skips the RPCs") {
            val screen = code(source("ConferenceScreen.kt"))
            screen shouldContain "decideAfterDisconnect(cause, resolved, rejoinGuard::tryConsume)"
            screen shouldContain "if (cause == DisconnectCause.DuplicateIdentity) null else resolvePostDisconnectDestination(room.id)"
            // ONE guard per screen, created next to activeSession -- never inside enterCall (each recursion would reset it)
            Regex("""AutoRejoinGuard\(""").findAll(screen).count() shouldBe 1
            val enterCall = screen.substring(screen.indexOf("private fun enterCall("))
            enterCall shouldNotContain "AutoRejoinGuard("
        }

        test("the displaced and loop-stopped paths never leave the room on the server and do not toast") {
            val screen = code(source("ConferenceScreen.kt"))
            val body = screen.between(start = "suspend fun stopAndShowNotice(", end = "    session =\n")
            body shouldNotContain "leaveRoom"
            body shouldNotContain "leaveSpace"
            body shouldNotContain "notifyInfo"
            body shouldContain "ResolvedAsEnded"
            // tracks off and the session gone BEFORE the card renders
            (body.indexOf("session.disconnect()") >= 0) shouldBe true
            (body.indexOf("setActiveSession(null)") >= 0) shouldBe true
            val resume = screen.between(start = "suspend fun resumeToken()", end = "fun stopAndShowNotice")
            resume shouldContain "ConferenceCallTarget.MainRoom(target.parentRoom)"
            body.indexOf("session.disconnect()") shouldBeLessThan body.indexOf("conferenceConnectionStoppedNotice(")
            body.indexOf("setActiveSession(null)") shouldBeLessThan body.indexOf("conferenceConnectionStoppedNotice(")
        }

        test(
            "the encounter view decides DuplicateIdentity before it reads the space or counts a re-entry, and never calls leaveSpace there",
        ) {
            val view = code(source("encounter/EncounterServiceView.kt"))
            val body = view.between(start = "private fun onConnectionLost(", end = "private fun showEntryAgain(")
            val duplicate = body.indexOf("DisconnectCause.DuplicateIdentity")
            (duplicate >= 0) shouldBe true
            duplicate shouldBeLessThan body.indexOf("getSpace(")
            duplicate shouldBeLessThan body.indexOf("reentryAllowed()")
            val displaced = body.substring(duplicate, body.indexOf("val fresh ="))
            displaced shouldNotContain "leaveSpace"
        }
    })

private infix fun Int.shouldBeLessThan(other: Int) {
    withClue("expected index $this < $other") { (this < other) shouldBe true }
}
