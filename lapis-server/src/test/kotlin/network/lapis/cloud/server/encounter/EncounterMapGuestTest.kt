package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.ktor.server.testing.ApplicationTestBuilder
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.166 -- a guest or friend (a non-member) never stands on the map, never sees a position and never receives a packet: every map
 * method answers them with ONE bare [ForbiddenException], also for an unknown space and for a space they could not see (no existence oracle).
 */
class EncounterMapGuestTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun Result<*>.bareForbidden() {
            val e = failure<ForbiddenException>()
            e.message shouldBe ForbiddenException().message
        }

        suspend fun ApplicationTestBuilder.enterAsGuest(
            w: TableWorld,
            guest: Uuid,
        ) {
            val info = w.rig.asMember(client = client, member = guest) { it.getEntryInfo(w.spaceId) }.getOrThrow()
            info.consentRequired shouldBe true
            val disclaimer = info.disclaimer!!
            disclaimer.version shouldEndWith EncounterConsentLayout.MAP_SUFFIX
            disclaimer.keyPoints.last() shouldBe EncounterConsentLayout.mapParagraph(AgreementTextKind.ENC_ASSEM)
            info.mapRoomNotice shouldBe EncounterConsentLayout.mapParagraph(AgreementTextKind.ENC_ASSEM)
            w.rig
                .asMember(client = client, member = guest) {
                    it.enterSpace(
                        spaceId = w.spaceId,
                        consent = EncounterConsentInput(consentVersion = disclaimer.version, consentSha256 = disclaimer.sha256),
                    )
                }.getOrThrow()
        }

        test("all five map methods answer a guest and a friend with the same bare Forbidden") {
            val w = fx.tableWorld(mapEnabled = true, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            val membersOnly = fx.tableWorld(rig = w.rig, mapEnabled = true)
            val guest = fx.createMember(status = MemberStatus.GUEST, name = "Gast")
            val friend = fx.createMember(status = MemberStatus.FRIEND, name = "Freund")
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                openAndEnter(w = membersOnly, membersOnly.steward)
                enterAsGuest(w, guest)
                enterAsGuest(w, friend)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                val unknown = Uuid.random().toString()
                listOf(guest, friend).forEach { outsider ->
                    listOf(w.spaceId, membersOnly.spaceId, unknown).forEach { space ->
                        w.rig.asMember(client = client, member = outsider) { it.mapEnter(space) }.bareForbidden()
                        w.rig
                            .asMember(
                                client = client,
                                member = outsider,
                            ) { it.mapStep(spaceId = space, direction = EncounterMapFacing.WEST) }
                            .bareForbidden()
                        w.rig
                            .asMember(
                                client = client,
                                member = outsider,
                            ) { it.mapTeleport(spaceId = space, anchor = EncounterMapAnchor.CENTER) }
                            .bareForbidden()
                        w.rig.asMember(client = client, member = outsider) { it.mapLeave(space) }.bareForbidden()
                        w.rig.asMember(client = client, member = outsider) { it.mapSnapshot(space) }.bareForbidden()
                    }
                }
            }
        }

        test("a guest never receives a packet: not as a recipient, not through the presence list") {
            val w = fx.tableWorld(mapEnabled = true, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            val guest = fx.createMember(status = MemberStatus.GUEST, name = "Gast")
            encounterApp {
                openAndEnter(w = w, w.steward, w.a, w.b)
                enterAsGuest(w, guest)
                w.rig.asMember(client = client, member = w.a) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig.asMember(client = client, member = w.b) { it.mapEnter(w.spaceId) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.a,
                    ) { it.mapStep(spaceId = w.spaceId, direction = EncounterMapFacing.WEST) }
                    .getOrThrow()
                w.rig.asMember(client = client, member = w.steward) { it.mapSnapshot(w.spaceId) }.getOrThrow()
                // the guest tries everything; none of it registers them
                w.rig.asMember(client = client, member = guest) { it.mapSnapshot(w.spaceId) }.bareForbidden()
                EncounterMapBroadcaster(liveKit = w.rig.liveKit, state = w.rig.mapState, liveKitEnabled = true).tick()
                val destinations =
                    w.rig.liveKit.sentDataTo
                        .flatMap { it.destinations }
                (destinations.isNotEmpty()) shouldBe true
                destinations.contains(guest.toString()) shouldBe false
                destinations.toSet() shouldContainExactlyInAnyOrder
                    setOf(w.a.toString(), w.b.toString(), w.steward.toString())
                // the presence list shows the guest nothing about positions: the DTO has no such field
                val present = w.rig.asMember(client = client, member = guest) { it.listPresent(w.spaceId) }.getOrThrow()
                (present.size >= 3) shouldBe true
                EncounterPresentDto.serializer().descriptor.let { d ->
                    (0 until d.elementsCount).map { d.getElementName(it) }.toSet() shouldBe
                        setOf("memberId", "displayName", "role", "isGuest", "seat", "table", "tableSeat")
                }
            }
        }

        test("a guest who has to acknowledge the notice again after the map was switched on gets consentChanged") {
            val off = fx.tableWorld(guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            val guest = fx.createMember(status = MemberStatus.GUEST, name = "Gast")
            encounterApp {
                openAndEnter(w = off, off.steward)
                val before = off.rig.asMember(client = client, member = guest) { it.getEntryInfo(off.spaceId) }.getOrThrow()
                before.disclaimer!!.version.endsWith(EncounterConsentLayout.MAP_SUFFIX) shouldBe false
                before.mapRoomNotice shouldBe null
                off.rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = off.spaceId,
                            consent =
                                EncounterConsentInput(
                                    consentVersion = before.disclaimer!!.version,
                                    consentSha256 = before.disclaimer!!.sha256,
                                ),
                        )
                    }.getOrThrow()
                off.rig.asMember(client = client, member = guest) { it.leaveSpace(off.spaceId) }.getOrThrow()
                off.rig.asMember(client = client, member = off.steward) { it.closeSpace(off.spaceId) }.getOrThrow()
                transactionSetMap(space = off.space, on = true)
                off.rig.asMember(client = client, member = off.steward) { it.openSpace(off.spaceId) }.getOrThrow()
                val after = off.rig.asMember(client = client, member = guest) { it.getEntryInfo(off.spaceId) }.getOrThrow()
                after.consentRequired shouldBe true
                after.consentChanged shouldBe true
                after.disclaimer!!.version shouldEndWith EncounterConsentLayout.MAP_SUFFIX
                // without the new acknowledgment the guest is not let in
                off.rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = off.spaceId,
                            consent =
                                EncounterConsentInput(
                                    consentVersion = before.disclaimer!!.version,
                                    consentSha256 = before.disclaimer!!.sha256,
                                ),
                        )
                    }.failure<ConflictException>()
                off.rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = off.spaceId,
                            consent =
                                EncounterConsentInput(
                                    consentVersion = after.disclaimer!!.version,
                                    consentSha256 = after.disclaimer!!.sha256,
                                ),
                        )
                    }.getOrThrow()
            }
        }
    })

private fun transactionSetMap(
    space: Uuid,
    on: Boolean,
) {
    transaction { EncounterSpaceTable.update({ EncounterSpaceTable.id eq space }) { it[mapEnabled] = on } }
}
