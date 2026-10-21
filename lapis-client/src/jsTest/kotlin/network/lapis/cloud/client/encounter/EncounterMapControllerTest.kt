package network.lapis.cloud.client.encounter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapMove
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterMapPositionDto
import network.lapis.cloud.shared.domain.EncounterMapSelfDto
import network.lapis.cloud.shared.domain.EncounterMapSnapshotDto
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.166 -- the DOM-free logic of the grid map: server authority, resync, pace, click-to-walk, announcements. No network, a scripted service. */
class EncounterMapControllerTest {
    private val me = "me"

    private fun pos(
        id: String,
        x: Int,
        y: Int,
        initials: String = "AB",
    ) = EncounterMapPositionDto(id, x, y, EncounterMapFacing.NORTH, initials)

    private class Rig(
        val service: FakeMapService,
        var clock: Double,
    ) {
        val models = mutableListOf<EncounterMapModel>()
        val announcements = mutableListOf<String>()
        var entered = 0
        lateinit var controller: EncounterMapController

        val last: EncounterMapModel get() = models.last()
    }

    private suspend fun rig(
        canWalk: Boolean = true,
        names: Map<String, String> = emptyMap(),
        busyRetryMs: Long = ENCOUNTER_MAP_BUSY_RETRY_MS,
        block: suspend (Rig) -> Unit,
    ) {
        val service = FakeMapService()
        val rig = Rig(service, clock = 100_000.0)
        val scope = CoroutineScope(SupervisorJob())
        rig.controller =
            EncounterMapController(
                spaceId = "space-1",
                roomLayout = EncounterRoomLayout.HALL,
                selfId = me,
                canWalk = canWalk,
                rpc = service,
                scope = scope,
                onRender = { rig.models += it },
                announce = { rig.announcements += it },
                nameOf = { names[it] },
                onEnteredMap = { rig.entered++ },
                now = { rig.clock },
                busyRetryMs = busyRetryMs,
            )
        try {
            block(rig)
        } finally {
            rig.controller.dispose()
        }
    }

    private fun snapshot(
        seq: Long = 0,
        epoch: String = "aa",
        vararg positions: EncounterMapPositionDto,
    ) = EncounterMapSnapshotDto(epoch = epoch, seq = seq, positions = positions.toList(), self = EncounterMapSelfDto(onMap = false))

    @Test
    fun start_fetchesTheSnapshot_andRendersTheServersPicture(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(3, "aa", pos(me, 5, 7), pos("other", 6, 7)) }
                rig.controller.start()
                awaitUntil("the picture") { rig.models.isNotEmpty() }
                assertEquals(listOf("snapshot"), rig.service.calls)
                assertEquals(2, rig.last.people.size)
                assertTrue(rig.last.selfOnMap)
                assertEquals("Reihe 8, Spalte 6 · Eine Person in Ihrer Nähe", rig.last.status)
            }
        }

    @Test
    fun aDelta_withTheNextSequence_isApplied_anOldOneIsIgnored(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(3, "aa", pos("other", 2, 2)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 4, listOf(pos("other", 3, 2)), emptyList()))
                assertEquals(
                    3,
                    rig.last.people
                        .single()
                        .x,
                )
                val rendered = rig.models.size
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 4, listOf(pos("other", 9, 2)), emptyList()))
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 2, listOf(pos("other", 9, 2)), emptyList()))
                assertEquals(rendered, rig.models.size, "a duplicate and an old packet change nothing")
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 5, emptyList(), listOf("other")))
                assertTrue(rig.last.people.isEmpty())
            }
        }

    @Test
    fun anotherEpoch_aGap_andAReset_fetchTheSnapshotAgain_butNotBeforeTwoSeconds(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(3, "aa", pos("other", 2, 2)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.service.calls.size == 1 }
                // another epoch (a server restart): the snapshot comes again once the two seconds are over
                rig.clock += 3_000
                rig.service.snapshot = { snapshot(0, "bb", pos("other", 4, 4)) }
                rig.controller.onPacket(EncounterMapPacket.Delta("bb", 1, emptyList(), emptyList()))
                awaitUntil("resync after the epoch change") { rig.service.calls.size == 2 }
                assertEquals(
                    4,
                    rig.last.people
                        .single()
                        .x,
                )
                // a gap in the sequence
                rig.clock += 3_000
                rig.service.snapshot = { snapshot(9, "bb", pos("other", 5, 5)) }
                rig.controller.onPacket(EncounterMapPacket.Delta("bb", 5, emptyList(), emptyList()))
                awaitUntil("resync after the gap") { rig.service.calls.size == 3 }
                // a reset
                rig.clock += 3_000
                rig.controller.onPacket(EncounterMapPacket.Reset("bb", 10))
                awaitUntil("resync after the reset") { rig.service.calls.size == 4 }
                // two resets within two seconds: only one fetch is made at once
                rig.clock += 500
                val before = rig.service.calls.size
                rig.controller.onPacket(EncounterMapPacket.Reset("bb", 11))
                rig.controller.onPacket(EncounterMapPacket.Reset("bb", 12))
                delay(50)
                assertEquals(before, rig.service.calls.size, "no second fetch inside the two seconds")
            }
        }

    @Test
    fun aPacketBeforeTheFirstSnapshot_isIgnored(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 1, listOf(pos("other", 1, 1)), emptyList()))
                assertTrue(rig.models.isEmpty())
            }
        }

    @Test
    fun aStep_sendsADirection_andShowsWhatTheServerAnswered_atMostOncePer150ms(): Promise<Unit> =
        formTest {
            rig(names = emptyMap()) { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.controller.step(EncounterMapFacing.NORTH)
                awaitUntil("answered") {
                    rig.last.people
                        .single()
                        .y == 6
                }
                rig.controller.step(EncounterMapFacing.NORTH)
                delay(30)
                assertEquals(listOf("snapshot", "step:NORTH"), rig.service.calls, "a press inside the gap does nothing at all")
                rig.clock += 150
                rig.service.onStep = { EncounterMapSelfDto(onMap = true, x = 5, y = 5, facing = it) }
                rig.controller.step(EncounterMapFacing.NORTH)
                awaitUntil("second step") { rig.service.calls.size == 3 }
            }
        }

    @Test
    fun aBlockedStep_isSpokenAtMostOncePerTwoSeconds(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.service.onStep = { EncounterMapSelfDto(onMap = true, x = 5, y = 7, facing = it, move = EncounterMapMove.BLOCKED) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                repeat(3) {
                    rig.clock += 200
                    rig.controller.step(EncounterMapFacing.SOUTH)
                    awaitUntil("answer $it") { rig.service.calls.size == it + 2 }
                    delay(20)
                }
                assertEquals(1, rig.announcements.count { it == "Weg blockiert" })
                rig.clock += 2_500
                rig.controller.step(EncounterMapFacing.SOUTH)
                awaitUntil("fourth") { rig.service.calls.size == 5 }
                delay(20)
                assertEquals(2, rig.announcements.count { it == "Weg blockiert" })
            }
        }

    @Test
    fun clickToWalk_sendsSingleSteps_andStopsAtTheFirstRefusal(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                var y = 7
                rig.service.onStep = {
                    y -= 1
                    EncounterMapSelfDto(
                        onMap = true,
                        x = 5,
                        y = y,
                        facing = it,
                        move =
                            if (y <
                                5
                            ) {
                                EncounterMapMove.BLOCKED
                            } else {
                                EncounterMapMove.MOVED
                            },
                    )
                }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.controller.walkTo(5 + 12 * 3) // (5,3): four steps north
                awaitUntil("walked until the refusal") { rig.service.calls.count { it.startsWith("step") } == 3 }
                delay(400)
                assertEquals(3, rig.service.calls.count { it.startsWith("step") }, "the walk ended at the BLOCKED answer")
                assertTrue(
                    rig.service.calls
                        .filter { it.startsWith("step") }
                        .all { it == "step:NORTH" },
                )
            }
        }

    @Test
    fun clickToWalk_toAWallOrAnOccupiedTile_walksNowhere_andSaysSo(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7), pos("other", 5, 6)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.controller.walkTo(0) // a wall
                rig.controller.walkTo(5 + 12 * 6) // occupied
                delay(50)
                assertEquals(listOf("snapshot"), rig.service.calls)
                assertEquals(1, rig.announcements.count { it == "Weg blockiert" })
            }
        }

    @Test
    fun enteringTheMap_asksTheRoomToReloadThePresenceList_andRefreshesTheSnapshot(): Promise<Unit> =
        formTest {
            rig { rig ->
                var onMap = false
                rig.service.snapshot = { snapshot(1, "aa", *(if (onMap) arrayOf(pos(me, 5, 7)) else emptyArray())) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.service.onEnter = {
                    onMap = true
                    EncounterMapSelfDto(onMap = true, x = 5, y = 7)
                }
                rig.controller.enterMap()
                awaitUntil("on the map") { rig.models.last().selfOnMap }
                assertEquals(1, rig.entered)
                assertTrue("enter" in rig.service.calls)
                assertTrue(rig.announcements.any { it == "Reihe 8, Spalte 6" }, rig.announcements.toString())
            }
        }

    private val busyNotice = "Einen Moment bitte, gleich können Sie wieder springen."

    @Test
    fun threeAnchorClicksInsideTheCooldown_sendOneRequest_andSayWhyExactlyOnce(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                rig.controller.teleport(EncounterMapAnchor.ENTRANCE)
                rig.controller.teleport(EncounterMapAnchor.PULPIT)
                rig.controller.teleport(EncounterMapAnchor.CENTER)
                awaitUntil("the jump") { rig.service.calls.contains("teleport:ENTRANCE") }
                delay(50)
                assertEquals(1, rig.service.calls.count { it.startsWith("teleport:") }, rig.service.calls.toString())
                assertEquals(1, rig.announcements.count { it == busyNotice }, rig.announcements.toString())
                assertTrue(rig.controller.anchorsBusy)
                assertTrue(rig.last.anchorsBusy)
            }
        }

    @Test
    fun afterTheCooldown_theAnchorsWorkAgain_andANewPhaseSaysItAgain(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                rig.controller.teleport(EncounterMapAnchor.ENTRANCE)
                awaitUntil("first jump") { rig.service.calls.contains("teleport:ENTRANCE") }
                delay(20)
                rig.controller.teleport(EncounterMapAnchor.PULPIT)
                assertEquals(1, rig.announcements.count { it == busyNotice })
                rig.clock += 1_100
                assertFalse(rig.controller.anchorsBusy)
                rig.controller.teleport(EncounterMapAnchor.PULPIT)
                awaitUntil("second jump") { rig.service.calls.contains("teleport:PULPIT") }
                delay(20)
                rig.controller.teleport(EncounterMapAnchor.CENTER)
                assertEquals(2, rig.announcements.count { it == busyNotice }, rig.announcements.toString())
            }
        }

    @Test
    fun anAnchorClickRightAfterARunningStep_isNotLost(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                rig.service.onStep = { EncounterMapSelfDto(onMap = true, x = 5, y = 6, facing = it) }
                rig.controller.step(EncounterMapFacing.NORTH)
                awaitUntil("the step is running") { rig.service.calls.contains("step:NORTH") }
                rig.controller.teleport(EncounterMapAnchor.CENTER)
                awaitUntil("the jump was sent") { rig.service.calls.contains("teleport:CENTER") }
            }
        }

    @Test
    fun aThrottledAnswer_saysSo_andKeepsTheButtonsCooling(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.service.onTeleport = { EncounterMapSelfDto(onMap = true, x = 5, y = 7, move = EncounterMapMove.THROTTLED) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                rig.controller.teleport(EncounterMapAnchor.CENTER)
                awaitUntil("the notice") { rig.announcements.contains(busyNotice) }
                assertTrue(rig.controller.anchorsBusy)
            }
        }

    @Test
    fun aRateLimitedJump_isNotSilent_andTheControllerSurvives(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.service.onTeleport = { throw ServiceBusyException() }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                rig.controller.teleport(EncounterMapAnchor.CENTER)
                awaitUntil("the notice") { rig.announcements.contains(busyNotice) }
                rig.clock += 1_100
                rig.service.onTeleport = { EncounterMapSelfDto(onMap = true, x = 5, y = 4) }
                rig.controller.teleport(EncounterMapAnchor.CENTER)
                awaitUntil("the next jump arrives") { rig.service.calls.count { it.startsWith("teleport:") } == 2 }
            }
        }

    @Test
    fun anOfficeHolder_looksOnly_andNeverCallsAWalkingMethod(): Promise<Unit> =
        formTest {
            rig(canWalk = false) { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos("other", 5, 6)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.controller.enterMap()
                rig.controller.step(EncounterMapFacing.NORTH)
                rig.controller.walkTo(5 + 12 * 6)
                rig.controller.teleport(network.lapis.cloud.shared.domain.EncounterMapAnchor.CENTER)
                delay(50)
                assertEquals(listOf("snapshot"), rig.service.calls)
                assertFalse(rig.last.canWalk)
            }
        }

    @Test
    fun aForbiddenStep_dropsOnlyTheOwnFigure_keepsTheBoard_andLaterDeltasApply(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7), pos("other", 6, 7)) }
                rig.service.onStep = { throw ForbiddenException() }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.people.size == 2 }
                // the server still lets the person look: the fresh snapshot no longer holds the own figure
                rig.service.snapshot = { snapshot(2, "aa", pos("other", 6, 7)) }
                rig.controller.step(EncounterMapFacing.NORTH)
                awaitUntil("own figure gone") { !rig.last.selfOnMap }
                assertEquals(listOf("other"), rig.last.people.map { it.memberId })
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 3, listOf(pos("other", 8, 7)), emptyList()))
                assertEquals(
                    8,
                    rig.last.people
                        .single()
                        .x,
                )
            }
        }

    @Test
    fun aForbiddenEnter_keepsTheOtherFigures_andTheControllerStaysLive(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos("other", 6, 7)) }
                rig.service.onEnter = { throw ForbiddenException() }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.people.size == 1 }
                rig.clock += 3_000
                rig.controller.enterMap()
                awaitUntil("second snapshot") { rig.service.calls.count { it == "snapshot" } >= 2 }
                delay(50)
                assertEquals(listOf("other"), rig.last.people.map { it.memberId })
                rig.controller.onPacket(EncounterMapPacket.Delta("aa", 2, listOf(pos("other", 9, 7)), emptyList()))
                assertEquals(
                    9,
                    rig.last.people
                        .single()
                        .x,
                )
            }
        }

    @Test
    fun aRefusalFromTheServer_dropsTheOwnFigure_withoutAnError(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7)) }
                rig.service.onStep = { throw ForbiddenException() }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                rig.service.snapshot = { snapshot(2, "aa") }
                rig.controller.step(EncounterMapFacing.NORTH)
                awaitUntil("dropped") { !rig.last.selfOnMap }
                assertTrue(rig.announcements.contains("Sie stehen nicht auf der Karte."))
            }
        }

    @Test
    fun aSnapshotForbiddenAfterAReset_clearsAllFigures(): Promise<Unit> =
        formTest {
            rig { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7), pos("other", 6, 7)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() && rig.last.people.size == 2 }
                rig.service.snapshot = { throw ForbiddenException() }
                rig.clock += 3_000
                rig.controller.onPacket(EncounterMapPacket.Reset("aa", 2))
                awaitUntil("emptied") { rig.last.people.isEmpty() }
                assertTrue(rig.last.near.isEmpty())
                assertFalse(rig.last.selfOnMap)
            }
        }

    @Test
    fun enteringTheMap_showsTheOwnFigure_evenWhenTheSnapshotIsBusy_andRetries(): Promise<Unit> =
        formTest {
            rig(busyRetryMs = 50) { rig ->
                var snapshots = 0
                rig.service.snapshot = {
                    snapshots++
                    if (snapshots ==
                        1
                    ) {
                        throw network.lapis.cloud.shared.rpc
                            .ServiceBusyException()
                    } else {
                        snapshot(2, "aa", pos(me, 5, 7), pos("other", 6, 7))
                    }
                }
                rig.controller.enterMap()
                awaitUntil("own figure") { rig.models.isNotEmpty() && rig.last.selfOnMap }
                assertTrue(rig.announcements.any { it.startsWith("Reihe 8, Spalte 6") })
                awaitUntil("retried snapshot") { rig.last.people.size == 2 }
            }
        }

    @Test
    fun theVicinity_namesPeopleWithinTwoTiles_sorted_andCountsTheRest(): Promise<Unit> =
        formTest {
            val names = (1..10).associate { "p$it" to "Person ${(10 - it).toString().padStart(2, '0')}" }
            rig(names = names) { rig ->
                val people = (1..10).map { pos("p$it", 4 + it % 3, 6 + it % 2) } + pos("far", 11, 3)
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 6), *people.toTypedArray()) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                val near = rig.controller.near()
                assertEquals(8, near.size)
                assertEquals(near.sorted(), near)
                assertEquals(2, rig.last.nearMore)
                assertFalse(near.any { it.contains("far") })
                assertTrue(rig.controller.describeWithNames().contains("und 2 weitere"))
            }
        }

    @Test
    fun theAnnouncementsOfAMove_nameNobody(): Promise<Unit> =
        formTest {
            rig(names = mapOf("other" to "Berta Beispiel")) { rig ->
                rig.service.snapshot = { snapshot(1, "aa", pos(me, 5, 7), pos("other", 6, 7)) }
                rig.controller.start()
                awaitUntil("snapshot") { rig.models.isNotEmpty() }
                rig.clock += 2_000
                rig.controller.step(EncounterMapFacing.NORTH)
                awaitUntil("moved") { rig.announcements.isNotEmpty() }
                assertTrue(rig.announcements.none { it.contains("Berta") })
                // the names are read on request only
                assertTrue(rig.controller.describeWithNames().contains("Berta Beispiel"))
                assertNotNull(rig.announcements.firstOrNull())
            }
        }
}
