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
 *
 * V1.9.71 (floating window) adds: the window lends pictures and never creates or attaches one, the loan is settled before the call view
 * clears a slot, the one `localStorage` access holds only a whitelisted preference, and the window's CSS is fixed, not transformed and
 * never above a dialog. `ClientDataStateTripwireTest` is deliberately UNCHANGED: the wave has no RPC, so its ledger (153/46) cannot move.
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

        // ── V1.9.71: the floating conference window ──────────────────────────────────────────────────────────────────────────

        val floatFiles =
            listOf(
                "ConferenceFloatGeometry.kt",
                "ConferenceFloatStore.kt",
                "ConferenceFloatSelection.kt",
                "ConferenceFloatWindow.kt",
                "ConferenceVideoLedger.kt",
            )

        test("the floating window never reads data, joins, attaches a track, builds markup from text or shows personal data") {
            for (name in floatFiles) {
                val text = code(source(name))
                withClue("$name must not read through rpcService (ClientDataStateTripwireTest ledger, wave has no RPC)") {
                    text shouldNotContain "rpcService<"
                }
                withClue("$name: one track, one <video> -- the window lends, the call view attaches") { text shouldNotContain ".attach(" }
                text shouldNotContain "beginJoin"
                text shouldNotContain "LiveKitRoomSession("
                text shouldNotContain "connect("
                text shouldNotContain "innerHTML"
                text shouldNotContain "displayName"
                text shouldNotContain "identity"
                text shouldNotContain "room.title"
                text shouldNotContain "AppState.session"
                text shouldNotContain "addAfterInsertHook"
                text shouldNotContain "addAfterDestroyHook"
            }
        }

        test("localStorage is touched only by ConferenceFloatStore, with the whitelisted key") {
            val users = clientFiles().filter { code(it.readText()).contains("localStorage") }.map { it.name }
            withClue("files that use localStorage for the floating window: $users") {
                (users.contains("ConferenceFloatStore.kt")) shouldBe true
                (users.intersect(floatFiles.toSet()) - "ConferenceFloatStore.kt") shouldBe emptySet()
            }
            val store = code(source("ConferenceFloatStore.kt"))
            store shouldContain "FLOAT_STORAGE_KEY"
            code(source("ConferenceFloatGeometry.kt")) shouldContain "lapis.conferenceFloat.v1"
        }

        test("the call view settles a loan BEFORE it clears a slot (setTileVideo, removeTile, the share stage)") {
            val screen = code(source("ConferenceScreen.kt"))
            for (function in listOf(
                "fun setTileVideo(",
                "fun removeTile(",
                "fun showScreenShareStage(",
                "fun hideScreenShareStageIfCurrent(",
            )) {
                val from = screen.indexOf(function)
                withClue("$function not found") { (from >= 0) shouldBe true }
                val body =
                    screen.substring(
                        from,
                        screen.indexOf("\n    fun ", from + function.length).let {
                            if (it <
                                0
                            ) {
                                screen.length
                            } else {
                                it
                            }
                        },
                    )
                val reclaim = body.indexOf("videoLedger.reclaimSlot(")
                withClue("$function must reclaim the slot") { (reclaim >= 0) shouldBe true }
                val clear = body.indexOf("clearElement(")
                if (clear >= 0) withClue("$function: reclaimSlot before clearElement") { (reclaim < clear) shouldBe true }
            }
        }

        test("the dock hands every loan back before the call's own teardown, on terminate, on a run's end and in the test reset") {
            val dock = code(source("ConferenceDock.kt"))
            val terminate = dock.between(start = "suspend fun terminate(", end = "fun onAuthSessionChanged(")
            (terminate.indexOf("videoLedger.returnAll()") in 0 until terminate.indexOf("current?.terminate(")) shouldBe true
            dock.between(start = "private fun clearRun()", end = "private fun sideEffects(") shouldContain "videoLedger.returnAll()"
            dock shouldContain "val videoLedger = ConferenceVideoLedger()"
            Regex("""\bvar state\b""").findAll(dock).count() shouldBe 1
        }

        test("the window, its announcer and the controller are mounted from the shell, outside the route outlet and the dock host") {
            val app = code(source("App.kt"))
            app shouldContain "shell.conferenceFloatWindow()"
            app shouldContain "shell.conferenceDockAnnouncer()"
            app shouldContain "ConferenceFloatController.install()"
            (app.indexOf("shell.conferenceDockBar()") < app.indexOf("shell.conferenceFloatWindow()")) shouldBe true
            // the live regions are not inside the bar (it is display:none while the window floats)
            val bar = code(source("ConferenceDockBar.kt"))
            bar shouldNotContain "aria-live"
            bar shouldNotContain "role\", \"alert"
            val window = code(source("ConferenceFloatWindow.kt"))
            window shouldContain "aria-live"
            window shouldContain "setAttribute(\"role\", \"alert\")"
        }

        test("every entry into a call still goes through beginJoin exactly three times -- the window adds none") {
            clientFiles().sumOf { Regex("""ConferenceDock\.beginJoin\(""").findAll(code(it.readText())).count() } shouldBe 3
        }

        test("the window's CSS is fixed, never transformed, clipped or above a dialog, and hidden below 768 px") {
            val css =
                File(CLIENT_DIR, "../../../../../resources/theme.css")
                    .let {
                        if (it.exists()) it else File(CLIENT_DIR, "../../../../../../resources/theme.css")
                    }.readText()
            val rule = Regex("""\.lapis-conference-float\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("main rule: $rule") {
                rule shouldContain "position: fixed"
                rule shouldContain "var(--lapis-z-conference-float)"
                rule shouldNotContain "transform"
                rule shouldNotContain "filter"
                rule shouldNotContain "overflow"
            }
            val token =
                Regex("""--lapis-z-conference-float:\s*(\d+)""")
                    .find(css)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            withClue("the window must stay below the update pill (1035), the offcanvas and every dialog") {
                (token != null && token < 1035) shouldBe true
            }
            val narrow =
                Regex(
                    """@media \(max-width: 767\.98px\)\s*\{\s*\.lapis-conference-float\s*\{[^}]*display:\s*none""",
                ).containsMatchIn(css)
            withClue("second guard: no window on a viewport narrower than 768 px") { narrow shouldBe true }
        }

        test("the window is mounted with addWithLifecycle, never with a late hook") {
            val window = code(source("ConferenceFloatWindow.kt"))
            Regex("""addWithLifecycle\(""").findAll(window).count() shouldBe 2
        }

        test("V1.9.92: applyTileGrid measures and writes numbers only, never moves a node") {
            val screen = code(source("ConferenceScreen.kt"))
            val apply = screen.between(start = "fun applyTileGrid(", end = "fun scheduleTileGrid(")
            for (forbidden in listOf(
                "appendChild",
                "removeChild",
                "replaceChild",
                "insertBefore",
                "insertAdjacent",
                "innerHTML",
                "removeAll",
                "remove()",
            )) {
                withClue(forbidden) { apply shouldNotContain forbidden }
            }
            val written = Regex("""setProperty\("([^"]+)"""").findAll(apply).map { it.groupValues[1] }.toSet()
            withClue("the only properties the function writes") {
                written shouldBe setOf("--lapis-rail-inset", "--lapis-tile-w", "--lapis-tile-h", "--lapis-tiles-max-w", "min-height")
            }
            apply shouldContain "computeConferenceTileGrid("
            apply shouldContain "conferenceTileAreaHeight("
            apply shouldContain "conferenceRailInset("
            withClue("no name or identity reaches a CSS variable: the values are formatted numbers") {
                apply shouldNotContain "displayName"
                apply shouldNotContain "identity"
                apply shouldNotContain "console"
                apply shouldNotContain "println"
                apply shouldNotContain "logger"
            }
        }

        test("V1.9.92: the tile grid is pure arithmetic, has one rAF-coalesced trigger and ends with the call") {
            val grid = code(source("ConferenceTileGrid.kt"))
            for (forbidden in listOf("document.", "window.", "kotlinx.browser", "org.w3c", "localStorage")) {
                withClue(forbidden) { grid shouldNotContain forbidden }
            }
            val screen = code(source("ConferenceScreen.kt"))
            val schedule = screen.between(start = "fun scheduleTileGrid(", end = "val tileGridObserver")
            schedule shouldContain "requestAnimationFrame"
            schedule shouldContain "tileGridFramePending"
            val dispose = screen.between(start = "fun disposeTileGrid(", end = "val stageDiv =")
            dispose shouldContain "tileGridObserver.disconnect()"
            Regex("""removeEventListener""").findAll(dispose).count() shouldBe 3
            screen.between(start = "fun cleanupFullscreen()", end = "fun refreshRoster()") shouldContain "disposeTileGrid()"
            withClue("the observer is re-attached to a replaced container in the same task as the adoption") {
                screen.between(
                    start = "conferenceAdoptChildren(oldRoot = previousRoot, newRoot = root)",
                    end = "val priority = document.createElement",
                ) shouldContain
                    "tileGridObserver.observe(root)"
            }
            withClue("the reflow calls the grid ONCE, at its end; the grid never calls the reflow (no loop)") {
                screen
                    .between(start = "fun applyConferenceGridReflow()", end = "suspend fun sweepGridReflow()")
                    .let { Regex("""applyTileGrid\(""").findAll(it).count() shouldBe 1 }
                screen.between(start = "fun applyTileGrid(", end = "fun scheduleTileGrid(") shouldNotContain "applyConferenceGridReflow"
            }
        }
    })
