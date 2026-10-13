package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.carpool.CarpoolFriendConfig
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPrivacy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException

/**
 * V1.9.125 security follow-up: a guest or friend must not learn member names from the feed or from their own participations before the ride
 * is confirmed, the driver sees the account kind of every invitee/participant, and an unverified FRIEND is neither findable nor invitable
 * while the email-verification duty is on.
 */
class CarpoolPrivacyAndVerificationTest :
    FunSpec({
        val data = CarpoolTestData()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        test("listMyParticipations hides the driver name until CONFIRMED, except when the driver invited") {
            carpoolTestApplication {
                val driver = data.member(label = "priv-driver", displayName = "Fahrer Privat")
                val friend = data.member(label = "priv-friend", status = MemberStatus.FRIEND)
                val offerA = data.posting(author = driver, daysAhead = 11)
                val offerB = data.posting(author = driver, daysAhead = 12)
                val offerC = data.posting(author = driver, daysAhead = 13)
                data.participation(postingId = offerA, personId = friend, status = CarpoolParticipationStatus.OPEN)
                data.participation(postingId = offerB, personId = friend, status = CarpoolParticipationStatus.CONFIRMED)
                data.participation(
                    postingId = offerC,
                    personId = friend,
                    status = CarpoolParticipationStatus.OPEN,
                    initiator = CarpoolParticipationInitiator.DRIVER,
                )
                val mine = call(member = friend) { listMyParticipations() }.associateBy { it.postingId }
                mine.getValue(offerA.toString()).driverDisplayName shouldBe CarpoolPrivacy.HIDDEN_NAME
                mine.getValue(offerB.toString()).driverDisplayName shouldBe "Fahrer Privat"
                mine.getValue(offerC.toString()).driverDisplayName shouldBe "Fahrer Privat"
            }
        }

        test("the driver sees the account kind of search hits and participants") {
            carpoolTestApplication {
                val driver = data.member(label = "kind-driver")
                val real = data.member(label = "kind-real", displayName = "Doppelgaenger Person")
                val fake = data.member(label = "kind-fake", displayName = "Doppelgaenger Person", status = MemberStatus.FRIEND)
                val offer = data.posting(author = driver)
                val hits = call(member = driver) { findInvitee(firstName = "Doppelgaenger", lastName = "Person") }
                hits.map { it.memberStatus }.toSet() shouldBe setOf(MemberStatus.ACTIVE, MemberStatus.FRIEND)
                data.participation(postingId = offer, personId = fake, status = CarpoolParticipationStatus.OPEN)
                data.participation(postingId = offer, personId = real, status = CarpoolParticipationStatus.OPEN)
                val statuses = call(member = driver) { listParticipants(offer.toString()) }.map { it.memberStatus }
                statuses.toSet() shouldBe setOf(MemberStatus.ACTIVE, MemberStatus.FRIEND)
            }
        }

        test("with the verification duty on, an unverified FRIEND is not found and cannot be invited; a verified one is") {
            val previous = CarpoolFriendConfig.provider
            val dutyOn = FriendRegistrationConfig.load { key -> if (key == "LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION") "true" else null }
            try {
                carpoolTestApplication {
                    val driver = data.member(label = "ver-driver")
                    data.member(label = "ver-un", displayName = "Unverifiziert Freund", status = MemberStatus.FRIEND, emailVerified = false)
                    data.member(label = "ver-ok", displayName = "Verifiziert Freund", status = MemberStatus.FRIEND)
                    val offer = data.posting(author = driver)
                    // Duty off: the unverified friend is found; keep its token.
                    val staleHit = call(member = driver) { findInvitee(firstName = "Unverifiziert", lastName = "Freund") }.single()
                    CarpoolFriendConfig.provider = { dutyOn }
                    call(member = driver) { findInvitee(firstName = "Unverifiziert", lastName = "Freund") }.shouldBeEmpty()
                    val verifiedHit = call(member = driver) { findInvitee(firstName = "Verifiziert", lastName = "Freund") }.single()
                    attempt(member = driver) { inviteParticipant(postingId = offer.toString(), personRef = staleHit.personRef) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    call(member = driver) { inviteParticipant(postingId = offer.toString(), personRef = verifiedHit.personRef) }
                }
            } finally {
                CarpoolFriendConfig.provider = previous
            }
        }
    })
