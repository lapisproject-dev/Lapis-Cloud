package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterMapPositionDto
import network.lapis.cloud.shared.domain.EncounterMapSelfDto
import network.lapis.cloud.shared.domain.EncounterMapSnapshotDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.166 -- the grid map inside the real room: built for a member only, the packets of the session reach it, entering the map reloads the list. */
class EncounterMapRoomDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val mapSpace = testSpace(profile = EncounterProfile.ASSEMBLY, roomLayout = EncounterRoomLayout.HALL, mapEnabled = true)
    private val clock = 1_000_000.0

    private val me = testPerson("me", name = "Ich Selbst")
    private val berta = testPerson("c2", name = "Berta Beispiel")

    private fun pos(
        id: String,
        x: Int,
        y: Int,
    ) = EncounterMapPositionDto(id, x, y, EncounterMapFacing.NORTH, "AB")

    private class Routes(
        val snapshot: String,
        val enter: String,
        val step: String,
    )

    private suspend fun routes() =
        Routes(
            snapshot = routeOf { rpcService<IEncounterSpaceService>().mapSnapshot("space-1") },
            enter = routeOf { rpcService<IEncounterSpaceService>().mapEnter("space-1") },
            step = routeOf { rpcService<IEncounterSpaceService>().mapStep("space-1", EncounterMapFacing.NORTH) },
        )

    @Test
    fun aMemberGetsTheMap_withTheNotice_theSnapshotIsFetchedOnce_andAPacketOfTheSessionMovesAFigure(): Promise<Unit> =
        formTest {
            val r = routes()
            var snapshotCalls = 0
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(me, berta) },
                clock = { clock },
                space = mapSpace,
                mapRoomNotice = "Karte und Position. In diesem Raum gibt es eine Karte.",
                extraRespond = { request ->
                    when (request.rpcRoute) {
                        r.snapshot -> {
                            snapshotCalls++
                            request.answerWith(
                                jsonOf(
                                    EncounterMapSnapshotDto.serializer(),
                                    EncounterMapSnapshotDto("aa", 1, listOf(pos("c2", 3, 3)), EncounterMapSelfDto(onMap = false)),
                                ),
                            )
                        }
                        else -> null
                    }
                },
            ) { rig, element ->
                awaitUntil("the map section") { element.querySelector(".lapis-encounter-map") != null }
                awaitUntil("the snapshot was fetched") { snapshotCalls == 1 }
                awaitUntil("one figure") { element.allOf(".lapis-encounter-map__figure").size == 1 }
                assertTrue(
                    element
                        .querySelector(".lapis-encounter-map__notice")!!
                        .textContent
                        .orEmpty()
                        .startsWith("Karte und Position."),
                )
                assertEquals("Sie stehen nicht auf der Karte.", element.querySelector("#lapis-encounter-map-status")!!.textContent)
                // a packet of the session
                rig.room.callbacks.onMap(EncounterMapPacket.Delta("aa", 2, listOf(pos("c2", 4, 3)), emptyList()))
                awaitUntil("the figure moved") {
                    element
                        .querySelector(".lapis-encounter-map__figure")!!
                        .getAttribute("style")
                        .orEmpty()
                        .contains("--x: 4")
                }
                assertEquals(1, snapshotCalls)
                // V1.9.167: the map is a layer of the room (a sibling of the dock), no longer a block of the stage
                val overlay = element.querySelector(".lapis-encounter-map-overlay")!!
                assertTrue(overlay.parentElement!!.classList.contains("lapis-encounter-room"))
                assertEquals(null, element.querySelector(".lapis-encounter-stage .lapis-encounter-map-overlay"))
                assertTrue(overlay.hasAttribute("hidden"), "the layer is closed until it is asked for")
                assertTrue(overlay.contains(element.querySelector(".lapis-encounter-map")), "the board lives in the layer")
                // the notice sits behind the disclosure "Mehr" and stays text
                val details = element.querySelector(".lapis-encounter-map-overlay details")!!
                assertEquals("Mehr", details.querySelector("summary")!!.textContent)
                assertTrue(details.querySelector(".lapis-encounter-map__notice") != null)
            }
        }

    @Test
    fun aGuest_getsNoMap_andCallsNoMapMethod(): Promise<Unit> =
        formTest {
            val r = routes()
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(testPerson("me", name = "Ich Selbst", isGuest = true), berta) },
                clock = { clock },
                space = mapSpace,
                mapRoomNotice = "Karte",
            ) { rig, element ->
                delay(200)
                rig.room.callbacks.onMap(EncounterMapPacket.Reset("aa", 1))
                delay(100)
                assertEquals(null, element.querySelector(".lapis-encounter-map"))
                assertFalse(
                    rig.requests.any { it.isRpc && it.rpcRoute in setOf(r.snapshot, r.enter, r.step) },
                    "a guest never calls a map method",
                )
                // V1.9.167: no layer, no dock button and no twin in the "Mehr" sheet for a guest
                assertEquals(null, element.querySelector(".lapis-encounter-map-overlay"))
                assertEquals(null, element.querySelector(".lapis-encounter-map-open"))
                assertEquals(null, element.querySelector("[aria-controls='lapis-encounter-map-overlay']"))
                assertTrue(
                    element
                        .allOf(
                            "button",
                        ).none { (it.getAttribute("aria-label").orEmpty() + it.textContent.orEmpty()).contains("Karte öffnen") },
                    "no button that opens the map",
                )
                assertEquals(null, rig.room.mapOverlayView)
                assertEquals(null, rig.room.mapOpenButton)
            }
        }

    @Test
    fun aRoomWithoutAMap_hasNone(): Promise<Unit> =
        formTest {
            val r = routes()
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(me, berta) },
                clock = { clock },
                space = testSpace(profile = EncounterProfile.ASSEMBLY),
            ) { rig, element ->
                delay(200)
                assertEquals(null, element.querySelector(".lapis-encounter-map"))
                assertEquals(null, element.querySelector(".lapis-encounter-map-overlay"))
                assertEquals(null, element.querySelector(".lapis-encounter-map-open"))
                assertFalse(rig.requests.any { it.isRpc && it.rpcRoute == r.snapshot })
            }
        }

    @Test
    fun anOfficeHolder_seesTheMapReadOnly(): Promise<Unit> =
        formTest {
            val r = routes()
            withEncounterRoom(
                entry = testEntry(role = EncounterPresenceRole.STEWARD),
                peopleOf = { listOf(testPerson("me", role = EncounterPresenceRole.STEWARD, name = "Ich Selbst"), berta) },
                clock = { clock },
                space = mapSpace,
                extraRespond = { request ->
                    if (request.rpcRoute == r.snapshot) {
                        request.answerWith(
                            jsonOf(
                                EncounterMapSnapshotDto.serializer(),
                                EncounterMapSnapshotDto("aa", 1, listOf(pos("c2", 3, 3)), EncounterMapSelfDto(onMap = false)),
                            ),
                        )
                    } else {
                        null
                    }
                },
            ) { rig, element ->
                awaitUntil("one figure") { element.allOf(".lapis-encounter-map__figure").size == 1 }
                val buttons = element.querySelector(".lapis-encounter-map")!!.querySelectorAll("button")
                val labels =
                    (0 until buttons.length).map {
                        buttons
                            .item(it)!!
                            .textContent
                            .orEmpty()
                            .trim()
                    }
                assertFalse(
                    labels.any {
                        it.contains("Auf die Karte") &&
                            window.getComputedStyle(buttons.item(labels.indexOf(it)) as org.w3c.dom.HTMLElement).display != "none"
                    },
                )
                assertFalse(rig.requests.any { it.isRpc && it.rpcRoute == r.enter })
            }
        }

    @Test
    fun enteringTheMap_reloadsThePresenceList_andNudgesTheOthers(): Promise<Unit> =
        formTest {
            val r = routes()
            var onMap = false
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(me.copy(seat = if (onMap) null else 4), berta) },
                clock = { clock },
                space = mapSpace,
                extraRespond = { request ->
                    when (request.rpcRoute) {
                        r.snapshot ->
                            request.answerWith(
                                jsonOf(
                                    EncounterMapSnapshotDto.serializer(),
                                    EncounterMapSnapshotDto(
                                        "aa",
                                        1,
                                        if (onMap) listOf(pos("me", 5, 7)) else emptyList(),
                                        EncounterMapSelfDto(onMap = onMap),
                                    ),
                                ),
                            )
                        r.enter -> {
                            onMap = true
                            request.answerWith(jsonOf(EncounterMapSelfDto.serializer(), EncounterMapSelfDto(onMap = true, x = 5, y = 7)))
                        }
                        else -> null
                    }
                },
            ) { rig, element ->
                awaitUntil("the map section") { element.querySelector(".lapis-encounter-map") != null }
                val nudgesBefore = rig.session.seatNudges
                val button = element.allOf(".lapis-encounter-map button").first { it.textContent.orEmpty().contains("Auf die Karte") }
                button.click()
                awaitUntil("the figure of the viewer") { element.allOf(".lapis-encounter-map__figure.is-self").size == 1 }
                awaitUntil("the seat nudge") { rig.session.seatNudges > nudgesBefore }
                assertTrue(rig.requests.count { it.isRpc && it.rpcRoute == r.enter } == 1)
                assertTrue(element.allOf(".lapis-encounter-map button").any { it.textContent.orEmpty().contains("Zum Podium") })
                assertFalse(element.allOf(".lapis-encounter-map button").any { it.textContent.orEmpty().contains("Zur Kanzel") })
                // the announcement is a fixed sentence without a name
                val live = element.allOf("[role=status]").map { it.textContent.orEmpty() }
                assertTrue(live.none { it.contains("Berta") })
            }
        }
}
