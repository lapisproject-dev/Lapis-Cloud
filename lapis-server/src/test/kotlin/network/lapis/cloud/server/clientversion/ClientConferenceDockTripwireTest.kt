package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Tripwire of V1.9.70 (conference dock): a running conference survives a route change. The conference page cannot be mounted with a real
 * LiveKit call in a jsTest, so the WIRING that makes this true (and keeps it true) is guarded as text:
 *
 * - the destroy hook of the conference screen only DETACHES the view -- no disconnect, no vote-runtime disposal, no call-presence reset;
 * - the remote audio lives in the permanent container outside the KVision root (a media element pauses when it leaves the document);
 * - sign-out, an identity change and a language change end the dock BEFORE the session / the root goes;
 * - the dock and its bar make no RPC read (ledger of `ClientDataStateTripwireTest`), show no personal data and own the single state field,
 *   the single unload guard and the single re-join guard.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private fun source(relative: String): String = File(CLIENT_DIR, relative).readText()

/** The source without whole-line comments (`//`, KDoc and block-comment lines). */
private fun code(text: String): String =
    text.lines().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }.joinToString("\n")

private fun clientFiles(): List<File> = CLIENT_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

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

class ClientConferenceDockTripwireTest :
    FunSpec({
        test("the destroy hook of the conference screen only detaches the view") {
            val screen = code(source("ConferenceScreen.kt"))
            val hook = screen.between(start = "container.conferenceScreenRoot {", end = "    val header =")
            hook shouldContain "ConferenceDock.detachView("
            hook shouldContain "ConferenceLobbyPort.clear("
            hook shouldNotContain "disconnect"
            hook shouldNotContain "disposeActive"
            hook shouldNotContain "ConferenceCallPresence"
            hook shouldNotContain "uninstall"
        }

        test("the unload guard, the rejoin guard and the call state live in the dock, not in the screen") {
            val screen = code(source("ConferenceScreen.kt"))
            screen shouldNotContain "ConferenceUnloadGuard("
            screen shouldNotContain "var activeSession"
            val unloadGuards =
                clientFiles().sumOf { Regex("""(?<!class )ConferenceUnloadGuard\(""").findAll(code(it.readText())).count() }
            withClue("exactly one unload guard in the whole client, in ConferenceDock.kt") { unloadGuards shouldBe 1 }
            Regex("""(?<!class )ConferenceUnloadGuard\(""").findAll(code(source("ConferenceDock.kt"))).count() shouldBe 1
            Regex("""\bvar state\b""").findAll(code(source("ConferenceDock.kt"))).count() shouldBe 1
        }

        test("disposeBackgroundEffects is called only by the five known ways out of a call, never by the screen hook") {
            val screen = code(source("ConferenceScreen.kt"))
            val calls = Regex("""(?<!fun )disposeBackgroundEffects\(\)""").findAll(screen).count()
            withClue("onDisconnected, leave, back to main room, end for all, and the dock's own terminate") { calls shouldBe 5 }
            val terminate = screen.between(start = "override suspend fun terminate(", end = "override fun toggleMic()")
            terminate shouldContain "disposeBackgroundEffects()"
            // B1 (audit): disconnect first, effect clean-up after -- the camera must stop first
            (terminate.indexOf("session.disconnect()") < terminate.indexOf("disposeBackgroundEffects()")) shouldBe true
            terminate shouldNotContain "leaveRoom"
        }

        test("remote audio goes to the permanent container outside the KVision root, never into a tile") {
            val screen = code(source("ConferenceScreen.kt"))
            screen shouldContain "ConferenceDock.audioContainer.appendChild(mediaElement)"
            screen shouldNotContain "entry.element.appendChild(mediaElement)"
            val dock = code(source("ConferenceDock.kt"))
            dock shouldContain "document.body?.appendChild(created)"
            dock shouldContain "CONFERENCE_AUDIO_CONTAINER_ID"
            dock shouldNotContain "Div("
        }

        test("an identity change ends the dock before any screen re-renders, a sign-out before the session goes") {
            val state = code(source("AppState.kt")).between(start = "fun setSession(", end = "onSessionChange()")
            state shouldContain "ConferenceDock.onAuthSessionChanged("
            val appState = code(source("AppState.kt"))
            (appState.indexOf("ConferenceDock.onAuthSessionChanged(") < appState.indexOf("        onSessionChange()")) shouldBe true
            val app = code(source("App.kt"))
            (app.indexOf("ConferenceDock.terminate(DockTerminateReason.LOGOUT)") in 0 until app.indexOf("AuthHttp.logout()")) shouldBe true
        }

        test("a language change ends the dock hard before the root restarts") {
            val language = code(source("LanguageChange.kt"))
            language shouldContain "ConferenceDock.terminate(DockTerminateReason.LANGUAGE_CHANGE)"
            (
                language.indexOf(
                    "ConferenceDock.terminate(",
                ) < language.indexOf("apply()", language.indexOf("ConferenceDock.terminate("))
            ) shouldBe
                true
        }

        test("the dock and its bar make no RPC read and show no personal data") {
            for (name in listOf("ConferenceDock.kt", "ConferenceDockBar.kt")) {
                val text = code(source(name))
                withClue("$name must not read through rpcService (ClientDataStateTripwireTest ledger)") {
                    text shouldNotContain "rpcService<"
                }
                text shouldNotContain "room.title"
                text shouldNotContain "displayName"
                text shouldNotContain "AppState.session"
            }
            val bar = code(source("ConferenceDockBar.kt"))
            bar shouldNotContain "roomId"
            bar shouldNotContain "identity"
        }

        test("the dock host is permanent: routing never clears it, and nothing transforms it") {
            val app = code(source("App.kt"))
            app shouldContain "ConferenceDock.bindHost(dockHost)"
            app shouldContain "initRouting(pageContainer)"
            val css =
                File(CLIENT_DIR, "../../../../../resources/theme.css").let {
                    if (it.exists()) it else File(CLIENT_DIR, "../../../../../../resources/theme.css")
                }
            val bar = Regex("""\.lapis-conference-dock-bar\s*\{[^}]*\}""").find(css.readText())?.value ?: ""
            withClue("the bar is fixed and has no transform/transition/animation") {
                bar shouldContain "position: fixed"
                bar shouldNotContain "transform"
                bar shouldNotContain "transition"
                bar shouldNotContain "animation"
                bar shouldNotContain "overflow: auto"
            }
        }

        test("entering the encounter room asks the dock first") {
            val view = code(source("encounter/EncounterServiceView.kt"))
            val enter = view.between(start = "private suspend fun enter(", end = "enterSpace(")
            enter shouldContain "ConferenceDock.canJoin()"
        }

        test("every entry into a call goes through beginJoin, and an automatic re-entry passes the last device wish") {
            val screen = code(source("ConferenceScreen.kt"))
            Regex("""ConferenceDock\.beginJoin\(""").findAll(screen).count() shouldBe 3
            // breakout, recall, "back to the main room" and the manual resume: four re-entries, none of them switches both devices on by default
            Regex("""conferenceInitialDevices\(ConferenceDock\.lastDeviceIntent\)""").findAll(screen).count() shouldBe 4
        }
    })
