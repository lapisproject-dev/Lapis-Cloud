package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.client.livekit.HandAnnouncementThrottle
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.127 -- the raised-hand controller with a fake gateway: own toggle, the epoch rule, moderation, announcements, the server restart. */
class ConferenceHandControllerTest {
    private val me = "11111111-1111-1111-1111-111111111111"
    private val ben = "22222222-2222-2222-2222-222222222222"
    private val cora = "33333333-3333-3333-3333-333333333333"
    private val e1 = "0123456789abcdef"
    private val e2 = "fedcba9876543210"

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private class FakeGateway : ConferenceHandGateway {
        var next: ConferenceHandQueueDto? = null
        var failRaise = false
        val calls = mutableListOf<String>()

        /** Runs inside lower()/lowerAll(), before the answer: the server's broadcast arrives before the RPC answer. */
        var duringLower: (() -> Unit)? = null

        override suspend fun raise(): ConferenceHandQueueDto? {
            calls += "raise"
            return if (failRaise) null else next
        }

        override suspend fun lower(): ConferenceHandQueueDto? {
            calls += "lower"
            duringLower?.invoke()
            return next
        }

        override suspend fun queue(): ConferenceHandQueueDto? {
            calls += "queue"
            return next
        }

        override suspend fun lowerOf(identity: String): ConferenceHandQueueDto? {
            calls += "lowerOf:$identity"
            return next
        }

        override suspend fun lowerAll(): ConferenceHandQueueDto? {
            calls += "lowerAll"
            duringLower?.invoke()
            return next
        }
    }

    private class Rig(
        val gateway: FakeGateway = FakeGateway(),
        moderator: Boolean = false,
        announce: Boolean = true,
        var now: Double = 100_000.0,
    ) {
        var changes = 0
        val self = mutableListOf<String>()
        val moderators = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val controller =
            ConferenceHandController(
                self = "11111111-1111-1111-1111-111111111111",
                gateway = gateway,
                scope = CoroutineScope(SupervisorJob()),
                onChanged = { changes++ },
                announceSelf = { self += it },
                announceModerators = { moderators += it },
                notifyError = { errors += it },
                isModerator = moderator,
                announceEnabled = { announce },
                nameOf = { id ->
                    mapOf("22222222-2222-2222-2222-222222222222" to "Ben", "33333333-3333-3333-3333-333333333333" to "Cora")[id]
                        ?: "?"
                },
                throttle = HandAnnouncementThrottle({ now }),
            )
    }

    private fun dto(
        epoch: String,
        seq: Long,
        vararg ids: String,
    ) = ConferenceHandQueueDto(epoch = epoch, seq = seq, identities = ids.toList())

    @Test
    fun raisingAndLowering_followTheServersAnswer() =
        test {
            val rig = Rig()
            rig.gateway.next = dto(e1, 1, me)
            rig.controller.toggleSelf()
            assertTrue(rig.controller.selfRaised)
            assertEquals(1, rig.controller.selfPosition)
            rig.gateway.next = dto(e1, 2, ben, me)
            rig.controller.onBroadcast(dto(e1, 2, ben, me))
            assertEquals(2, rig.controller.selfPosition)
            rig.gateway.next = dto(e1, 3, ben)
            rig.controller.toggleSelf()
            assertFalse(rig.controller.selfRaised)
            assertNull(rig.controller.selfPosition)
            assertEquals(listOf("raise", "lower"), rig.gateway.calls)
            assertTrue(rig.changes > 0)
        }

    @Test
    fun aFailedRaise_isReported_andLeavesTheHandDown() =
        test {
            val rig = Rig()
            rig.gateway.failRaise = true
            rig.controller.toggleSelf()
            assertFalse(rig.controller.selfRaised)
            assertEquals(listOf("Die Hand konnte nicht gehoben werden. Bitte versuchen Sie es erneut."), rig.errors)
            assertFalse(rig.controller.busy)
        }

    @Test
    fun loweringMyOwnHand_isNeverToldAsLoweredBySomebodyElse_evenWhenTheBroadcastComesFirst() =
        test {
            val rig = Rig()
            rig.gateway.next = dto(e1, 1, me)
            rig.controller.toggleSelf()
            rig.gateway.next = dto(e1, 2, ben)
            rig.gateway.duringLower = { rig.controller.onBroadcast(dto(e1, 2, ben)) }
            rig.controller.toggleSelf()
            assertFalse(rig.controller.selfRaised)
            assertNull(rig.controller.selfPosition)
            assertTrue(rig.self.isEmpty(), "no 'lowered' notice for a self-initiated lowering: ${rig.self}")
        }

    @Test
    fun aModeratorClearingTheListWithTheOwnHandUp_isNotToldItsHandWasLowered() =
        test {
            val rig = Rig(moderator = true)
            rig.gateway.next = dto(e1, 1, me)
            rig.controller.toggleSelf()
            rig.gateway.next = dto(e1, 2)
            rig.gateway.duringLower = { rig.controller.onBroadcast(dto(e1, 2)) }
            rig.controller.lowerAll()
            assertFalse(rig.controller.selfRaised)
            assertTrue(rig.self.isEmpty(), "no 'lowered' notice for the own lowerAll: ${rig.self}")
        }

    @Test
    fun aModeratorLoweringTheirOwnHandFromTheList_isNotToldItWasLowered() =
        test {
            val rig = Rig(moderator = true)
            rig.gateway.next = dto(e1, 1, me)
            rig.controller.toggleSelf()
            rig.gateway.next = dto(e1, 2)
            rig.gateway.duringLower = { rig.controller.onBroadcast(dto(e1, 2)) }
            rig.controller.lowerOf(me)
            assertFalse(rig.controller.selfRaised)
            assertTrue(rig.self.isEmpty())
        }

    @Test
    fun aModeratorLoweringMyHand_isAcceptedAndTold_neverRaisedAgain() =
        test {
            val rig = Rig()
            rig.gateway.next = dto(e1, 1, me)
            rig.controller.toggleSelf()
            rig.gateway.calls.clear()
            // same epoch, my hand missing: lowered by somebody else
            rig.controller.onBroadcast(dto(e1, 2, ben))
            assertFalse(rig.controller.selfRaised)
            assertEquals(listOf("Ihre Hand wurde gesenkt."), rig.self)
            assertTrue(rig.gateway.calls.isEmpty(), "no automatic raise: it would overrule the moderation")
        }

    @Test
    fun aServerRestart_raisesTheHandAgain_atTheEnd() =
        test {
            val rig = Rig()
            rig.gateway.next = dto(e1, 5, ben, me)
            rig.controller.toggleSelf()
            rig.gateway.calls.clear()
            // restart: the server answers with a new epoch (the queue is empty, then my raise lands first)
            rig.gateway.next = dto(e2, 1, me)
            rig.controller.onBroadcast(dto(e2, 0))
            // the re-raise runs in the controller's own scope
            kotlinx.coroutines.delay(50)
            assertEquals(listOf("raise"), rig.gateway.calls)
            assertTrue(rig.controller.selfRaised)
            assertEquals(1, rig.controller.selfPosition)
            assertTrue(rig.self.isEmpty(), "a restart is no moderation, nothing is announced to the person")
        }

    @Test
    fun aLateJoinerAdoptsAHandThatIsAlreadyUp() =
        test {
            val rig = Rig()
            rig.gateway.next = dto(e1, 9, ben, me)
            rig.controller.resync()
            assertTrue(rig.controller.selfRaised)
            assertEquals(2, rig.controller.selfPosition)
        }

    @Test
    fun anOlderSnapshotChangesNothing() =
        test {
            val rig = Rig()
            rig.controller.onBroadcast(dto(e1, 5, ben))
            val before = rig.changes
            rig.controller.onBroadcast(dto(e1, 4, cora))
            assertEquals(listOf(ben), rig.controller.order)
            assertEquals(before, rig.changes)
        }

    @Test
    fun moderationLowersOneOrAll_butAParticipantCannot() =
        test {
            val moderator = Rig(moderator = true)
            moderator.gateway.next = dto(e1, 2, cora)
            moderator.controller.lowerOf(ben)
            moderator.controller.lowerAll()
            assertEquals(listOf("lowerOf:$ben", "lowerAll"), moderator.gateway.calls)
            val participant = Rig(moderator = false)
            participant.controller.lowerOf(ben)
            participant.controller.lowerAll()
            assertTrue(participant.gateway.calls.isEmpty())
        }

    @Test
    fun aModeratorLoweringTheirOwnHandThroughTheList_isNoSuchNotice() =
        test {
            val rig = Rig(moderator = true)
            rig.gateway.next = dto(e1, 1, me)
            rig.controller.toggleSelf()
            rig.gateway.next = dto(e1, 2)
            rig.controller.lowerOf(me)
            assertFalse(rig.controller.selfRaised)
            assertTrue(rig.self.isEmpty())
            assertEquals("lower", rig.gateway.calls.last())
        }

    @Test
    fun moderatorsHearNewHands_throttled_andNeverTheFirstSnapshot_neverOwnHand() =
        test {
            val rig = Rig(moderator = true)
            // the first snapshot of an epoch is the room as it was, nothing "new"
            rig.controller.onBroadcast(dto(e1, 1, ben))
            assertTrue(rig.moderators.isEmpty())
            rig.controller.onBroadcast(dto(e1, 2, ben, cora))
            assertEquals(listOf("Hand gehoben: Cora"), rig.moderators)
            // my own hand is not announced to me
            rig.gateway.next = dto(e1, 3, ben, cora, me)
            rig.controller.toggleSelf()
            assertEquals(1, rig.moderators.size)
        }

    @Test
    fun noAnnouncementWhenTheModeratorSwitchedItOff_orForAnOrdinaryParticipant() =
        test {
            val off = Rig(moderator = true, announce = false)
            off.controller.onBroadcast(dto(e1, 1))
            off.controller.onBroadcast(dto(e1, 2, ben))
            assertTrue(off.moderators.isEmpty())
            val participant = Rig(moderator = false)
            participant.controller.onBroadcast(dto(e1, 1))
            participant.controller.onBroadcast(dto(e1, 2, ben))
            assertTrue(participant.moderators.isEmpty())
        }
}
