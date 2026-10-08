package network.lapis.cloud.server.encounter

import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.db.DatabaseConfig
import kotlin.time.Duration.Companion.seconds

/** Welle V1.9.80 -- what the table token actually grants (decoded from the signed JWT) and how it differs from the plenum token. */
class EncounterTablePermissionTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        test("the table token is room-pinned, microphone-only, without data channel, 30 seconds, never hidden/recorder/admin") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                val dto =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                        .getOrThrow()
                val grant = videoGrantOf(dto.join.token)
                grant["room"] shouldBe dto.join.livekitRoomName
                grant["roomJoin"] shouldBe true
                grant["canSubscribe"] shouldBe true
                grant["canPublish"] shouldBe true
                grant["canPublishData"] shouldBe false
                grant["canUpdateOwnMetadata"] shouldBe false
                (grant["canPublishSources"] as List<*>) shouldBe listOf("microphone")
                listOf("hidden", "recorder", "roomAdmin", "roomCreate", "roomList", "roomRecord", "ingressAdmin").forEach {
                    grant.containsKey(it) shouldBe false
                }
                tokenLifetimeSeconds(dto.join.token) shouldBe 30
                SignedJWT.parse(dto.join.token).jwtClaimsSet.subject shouldBe w.a.toString()
                // the name claim is the initials, not the display name
                val name = SignedJWT.parse(dto.join.token).jwtClaimsSet.getStringClaim("name")
                (name.length <= 2) shouldBe true
            }
        }

        test("the plenum token of the congregation is unchanged by tables: listen only; the table token never leaks into it") {
            val w = fx.tableWorld()
            encounterApp {
                w.rig.asMember(client = client, member = w.steward) { it.openSpace(w.spaceId) }.getOrThrow()
                val entry =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.enterSpace(spaceId = w.spaceId, consent = null) }
                        .getOrThrow()
                entry.canPublish shouldBe false
                val grant = videoGrantOf(entry.join.token)
                grant["canPublish"] shouldBe false
                grant.containsKey("canPublishSources") shouldBe false
                val table =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 1, seat = 1) }
                        .getOrThrow()
                (table.join.livekitRoomName != entry.join.livekitRoomName) shouldBe true
                // the office holder's plenum token keeps its publish right, and he gets no table token
                val steward =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.steward,
                        ) { it.enterSpace(spaceId = w.spaceId, consent = null) }
                        .getOrThrow()
                steward.canPublish shouldBe true
            }
        }

        test("after leaving, a token request returns null; a new sitting yields a new room, never the old name") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                val old =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                        .getOrThrow()
                w.rig.asMember(client = client, member = w.a) { it.leaveTable(w.spaceId) }.getOrThrow()
                w.rig.liveKit.deletedRooms shouldContain old.join.livekitRoomName
                w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow() shouldBe null
                val again =
                    w.rig
                        .asMember(
                            client = client,
                            member = w.a,
                        ) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }
                        .getOrThrow()
                (again.join.livekitRoomName != old.join.livekitRoomName) shouldBe true
            }
        }

        test("a quieted table's token cannot publish; releasing it grants the right again") {
            val w = fx.tableWorld()
            encounterApp {
                openAndEnter(w = w, w.steward, w.a)
                w.rig.asMember(client = client, member = w.a) { it.joinTable(spaceId = w.spaceId, table = 0, seat = 0) }.getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = true) }
                    .getOrThrow()
                val quiet = w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow()!!
                quiet.canPublish shouldBe false
                videoGrantOf(quiet.join.token)["canPublish"] shouldBe false
                w.rig
                    .asMember(client = client, member = w.steward) { it.listTables(w.spaceId) }
                    .getOrThrow()
                    .single { it.table == 0 }
                    .quieted shouldBe true
                w.rig
                    .asMember(
                        client = client,
                        member = w.steward,
                    ) { it.quietTable(spaceId = w.spaceId, table = 0, quiet = false) }
                    .getOrThrow()
                val open = w.rig.asMember(client = client, member = w.a) { it.tableToken(w.spaceId).token }.getOrThrow()!!
                open.canPublish shouldBe true
                (open.join.livekitRoomName != quiet.join.livekitRoomName) shouldBe true
            }
        }

        test("the minting function itself fixes the sources and the data channel regardless of the caller") {
            val minted =
                LiveKitAccessToken.mintTableParticipantToken(
                    apiKey = "k",
                    apiSecret = "test-livekit-secret-at-least-32-bytes-long!!",
                    roomName = "lc-et-x",
                    identity = "i",
                    displayName = "AB",
                    ttl = 30.seconds,
                    canPublish = false,
                )
            val grant = SignedJWT.parse(minted.jwt).jwtClaimsSet.getJSONObjectClaim("video")
            grant["canPublish"] shouldBe false
            grant["canPublishData"] shouldBe false
            (grant["canPublishSources"] as List<*>) shouldBe listOf("microphone")
        }
    })
