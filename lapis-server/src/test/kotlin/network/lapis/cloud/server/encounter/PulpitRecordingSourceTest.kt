package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- [PulpitRecordingSources], the one place that decides whose camera and microphone are recorded: an ACTIVE PULPIT holder of the space, present in
 * the session and connected to LiveKit -- never a steward, never somebody without an office (a person with a temporary floor included), never a person
 * whose membership ended; the per-room cap keeps a stable, deterministic choice; the abort guard recognises a source that is no pulpit any more.
 */
class PulpitRecordingSourceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun world() = fx.tableWorld(tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE)

        fun TableWorld.session(vararg present: Uuid): Uuid {
            val room = fx.insertSession(spaceId = space, openedBy = steward)
            present.forEach { fx.insertParticipation(roomId = room, memberId = it) }
            return room
        }

        fun select(
            w: TableWorld,
            room: Uuid,
            live: Set<Uuid>,
            confirmed: Set<Uuid> = emptySet(),
            requireConfirmation: Boolean = false,
            recorded: Set<Uuid> = emptySet(),
        ) = transaction {
            PulpitRecordingSources.select(
                spaceId = w.space,
                roomId = room,
                liveIdentities =
                    live
                        .map {
                            it.toString()
                        }.toSet(),
                confirmed = confirmed,
                requireConfirmation = requireConfirmation,
                alreadyRecorded = recorded,
            )
        }

        test("only an ACTIVE, present, connected PULPIT holder is a source -- never the steward, never the congregation") {
            val w = world()
            val room = w.session(w.steward, w.pulpit, w.a, w.b)
            val selection = select(w, room, live = setOf(w.steward, w.pulpit, w.a, w.b))
            selection.sources shouldBe listOf(w.pulpit)
            selection.capped shouldBe false
        }

        test("a pulpit holder who is not connected, not present or no longer an active member is not a source") {
            val w = world()
            val withdrawn = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            val absent = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            val room = w.session(w.pulpit, withdrawn)
            fx.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
            select(w, room, live = setOf(w.pulpit, withdrawn, absent)).sources shouldBe listOf(w.pulpit)
            select(w, room, live = setOf(withdrawn, absent)).sources shouldBe emptyList()
            select(w, room, live = emptySet()).sources shouldBe emptyList()
        }

        test("with confirmation required only those who agreed are sources and the others are reported; without it everybody present is") {
            val w = world()
            val second = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            val room = w.session(w.pulpit, second)
            val live = setOf(w.pulpit, second)
            val required = select(w, room, live, confirmed = setOf(second), requireConfirmation = true)
            required.sources shouldBe listOf(second)
            required.unconfirmed shouldBe listOf(w.pulpit)
            select(w, room, live, requireConfirmation = false).sources.toSet() shouldBe live
        }

        test("the cap of two keeps those already recorded first and otherwise a stable order, and says that it capped") {
            val w = world()
            val second = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            val third = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            val room = w.session(w.pulpit, second, third)
            val live = setOf(w.pulpit, second, third)
            val all = listOf(w.pulpit, second, third).sortedBy { it.toString() }
            val first = select(w, room, live)
            first.sources shouldBe all.take(2)
            first.capped shouldBe true
            select(w, room, live) shouldBe first
            // the third in the order keeps its slot once it is recorded
            val kept = select(w, room, live, recorded = setOf(all[2]))
            kept.sources.first() shouldBe all[2]
            kept.sources.size shouldBe 2
        }

        test("the abort guard: a PULPIT holder passes, a steward, a stranger, a withdrawn holder and a non-UUID identity do not") {
            val w = world()
            val withdrawn = fx.createMember().also { fx.setRole(spaceId = w.space, memberId = it, role = EncounterSpaceRole.PULPIT) }
            fx.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
            transaction {
                PulpitRecordingSources.isStillPulpit(spaceId = w.space, identity = w.pulpit.toString()) shouldBe true
                PulpitRecordingSources.isStillPulpit(spaceId = w.space, identity = w.steward.toString()) shouldBe false
                PulpitRecordingSources.isStillPulpit(spaceId = w.space, identity = w.a.toString()) shouldBe false
                PulpitRecordingSources.isStillPulpit(spaceId = w.space, identity = withdrawn.toString()) shouldBe false
                PulpitRecordingSources.isStillPulpit(spaceId = w.space, identity = "EG_RECORDER") shouldBe false
                PulpitRecordingSources.isStillPulpit(spaceId = w.space, identity = "") shouldBe false
            }
        }
    })
