package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.agreementtexts.EncounterAssemblyConsentDefault
import network.lapis.cloud.server.agreementtexts.EncounterChurchConsentDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val LEGACY_CHURCH_LABEL = "2026-10-06.v1"
private const val PUBLISHED_LABEL = "2026-10-18.v1"
private val PUBLISHED_BODY = "Eigene Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz."

/**
 * V1.9.148 -- the entry notice is versioned by its LABEL: a guest who acknowledged an earlier notice must acknowledge the corrected one again, is told
 * why, a room with a recording is another text (label + "+rec"), a published version replaces the placeholder, and a tampered or colliding proof
 * never lets anybody in silently.
 */
class EncounterConsentVersioningTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec {
            AgreementTextTestState.reset()
            fx.cleanUp()
        }

        fun board() = fx.createMember(role = AccountRole.BOARD)

        fun insertAck(
            member: Uuid,
            label: String,
            sha: String,
        ) {
            transaction {
                EncounterConsentAcknowledgmentTable.insert {
                    it[memberId] = member
                    it[consentVersion] = label
                    it[consentSha256] = sha
                    it[acknowledgedOn] = OrganizationTimeZone.today()
                }
            }
        }

        fun acks(member: Uuid): List<Pair<String, String>> =
            transaction {
                EncounterConsentAcknowledgmentTable
                    .selectAll()
                    .where { EncounterConsentAcknowledgmentTable.memberId eq member }
                    .map { it[EncounterConsentAcknowledgmentTable.consentVersion] to it[EncounterConsentAcknowledgmentTable.consentSha256] }
            }

        fun consentFor(
            profile: EncounterProfile,
            recording: Boolean = false,
        ) = transaction { encounterConsentFor(profile = profile, pulpitRecordingAllowed = recording) }

        fun inputOf(c: EncounterConsentText) = EncounterConsentInput(consentVersion = c.version, consentSha256 = c.sha256)

        /** An open church space with a steward, plus a guest. */
        fun roomWithGuest(
            profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
            recording: Boolean = false,
        ): Triple<Uuid, Uuid, Uuid> {
            val steward = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            val space =
                fx.createSpace(
                    createdBy = board(),
                    guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS,
                    profile = profile,
                    pulpitRecordingAllowed = recording,
                )
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            return Triple(space, steward, guest)
        }

        test("a guest who acknowledged the earlier notice is asked again and told that the notice has changed") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            insertAck(guest, LEGACY_CHURCH_LABEL, "bd0356b664bb37b4866ebf31ac844b575a19592ba7922a8d5608ad2bb7467319")
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val info = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow()
                info.consentRequired shouldBe true
                info.consentChanged shouldBe true
                info.disclaimer!!.version shouldBe EncounterChurchConsentDefault.VERSION
                info.disclaimer!!.keyPoints.size shouldBe 3
            }
        }

        test("a first-time guest is asked but is NOT told that anything changed") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val info = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow()
                info.consentRequired shouldBe true
                info.consentChanged shouldBe false
            }
        }

        test("an acknowledgment of the OTHER profile's notice is another purpose: consent is required, but nothing 'changed'") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest(profile = EncounterProfile.CHURCH_SERVICE)
            val assembly = consentFor(EncounterProfile.ASSEMBLY)
            insertAck(guest, assembly.version, assembly.sha256)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val info = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow()
                info.consentRequired shouldBe true
                info.consentChanged shouldBe false
            }
        }

        test("entering with the earlier version is a conflict; entering with the new one works and keeps the old proof row") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            insertAck(guest, LEGACY_CHURCH_LABEL, "bd0356b664bb37b4866ebf31ac844b575a19592ba7922a8d5608ad2bb7467319")
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = space.toString(),
                            consent =
                                EncounterConsentInput(
                                    consentVersion = LEGACY_CHURCH_LABEL,
                                    consentSha256 = "bd0356b664bb37b4866ebf31ac844b575a19592ba7922a8d5608ad2bb7467319",
                                ),
                        )
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = null) }
                    .failure<ConflictException>()
                val current = consentFor(EncounterProfile.CHURCH_SERVICE)
                rig
                    .asMember(
                        client = client,
                        member = guest,
                    ) { it.enterSpace(spaceId = space.toString(), consent = inputOf(current)) }
                    .getOrThrow()
                acks(guest).toSet() shouldBe
                    setOf(
                        LEGACY_CHURCH_LABEL to "bd0356b664bb37b4866ebf31ac844b575a19592ba7922a8d5608ad2bb7467319",
                        EncounterChurchConsentDefault.VERSION to EncounterChurchConsentDefault.SHA256,
                    )
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow().consentRequired shouldBe
                    false
            }
        }

        test("a room with a recording is another text: the plain acknowledgment does not carry over, the label has the system suffix") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest(recording = true)
            val plain = consentFor(EncounterProfile.CHURCH_SERVICE)
            insertAck(guest, plain.version, plain.sha256)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val info = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow()
                info.consentRequired shouldBe true
                info.consentChanged shouldBe true
                info.disclaimer!!.version shouldBe plain.version + "+rec"
                info.disclaimer!!.keyPoints.last() shouldContain "kann die Kanzel aufgezeichnet werden"
                // the plain proof is no proof for this text
                rig
                    .asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = inputOf(plain)) }
                    .failure<ConflictException>()
                val rec = consentFor(EncounterProfile.CHURCH_SERVICE, recording = true)
                rig
                    .asMember(
                        client = client,
                        member = guest,
                    ) { it.enterSpace(spaceId = space.toString(), consent = inputOf(rec)) }
                    .getOrThrow()
                acks(guest).map { it.first }.toSet() shouldBe setOf(plain.version, plain.version + "+rec")
            }
        }

        test("a published version replaces the placeholder: the placeholder acknowledgment no longer suffices") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            val placeholder = consentFor(EncounterProfile.CHURCH_SERVICE)
            insertAck(guest, placeholder.version, placeholder.sha256)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow().consentRequired shouldBe
                    false

                val sha =
                    AgreementTextTestState.publishDirect(
                        kind = AgreementTextKind.ENC_CHURCH,
                        label = PUBLISHED_LABEL,
                        body = PUBLISHED_BODY,
                    )
                val info = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow()
                info.consentRequired shouldBe true
                info.consentChanged shouldBe true
                info.disclaimer!!.version shouldBe PUBLISHED_LABEL
                info.disclaimer!!.sha256 shouldBe sha
                info.disclaimer!!.headline shouldBe "Eigene Überschrift"
                info.disclaimer!!.keyPoints shouldBe listOf("Punkt eins", "Punkt zwei", "Punkt drei")
                info.disclaimer!!.text shouldBe PUBLISHED_BODY

                // the placeholder's proof is rejected, the published one accepted
                rig
                    .asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = inputOf(placeholder)) }
                    .failure<ConflictException>()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = space.toString(),
                            consent = EncounterConsentInput(consentVersion = PUBLISHED_LABEL, consentSha256 = sha),
                        )
                    }.getOrThrow()
            }
        }

        test("the assembly notice is administered separately from the church notice") {
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.ENC_CHURCH, label = PUBLISHED_LABEL, body = PUBLISHED_BODY)
            consentFor(EncounterProfile.CHURCH_SERVICE).version shouldBe PUBLISHED_LABEL
            consentFor(EncounterProfile.ASSEMBLY).version shouldBe EncounterAssemblyConsentDefault.VERSION
        }

        test("a published recording variant keeps the admin's text and appends the code's sentence as the last key point") {
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.ENC_CHURCH, label = PUBLISHED_LABEL, body = PUBLISHED_BODY)
            val rec = consentFor(EncounterProfile.CHURCH_SERVICE, recording = true)
            rec.version shouldBe "$PUBLISHED_LABEL+rec"
            rec.keyPoints.take(3) shouldBe listOf("Punkt eins", "Punkt zwei", "Punkt drei")
            rec.keyPoints.last() shouldContain "kann die Kanzel aufgezeichnet werden"
            rec.text shouldBe PUBLISHED_BODY + "\n\n" + rec.keyPoints.last()
            rec.sha256 shouldBe AgreementTextHash.of(versionLabel = rec.version, body = rec.text)
        }

        test("a stored version whose hash no longer matches is a ServiceBusyException; nobody enters on it") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.ENC_CHURCH, label = PUBLISHED_LABEL, body = PUBLISHED_BODY)
            transaction {
                AgreementTextVersionTable.update({ AgreementTextVersionTable.versionLabel eq PUBLISHED_LABEL }) {
                    it[sha256] =
                        "0".repeat(64)
                }
            }
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.failure<ServiceBusyException>()
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = space.toString(),
                            consent = EncounterConsentInput(consentVersion = PUBLISHED_LABEL, consentSha256 = "0".repeat(64)),
                        )
                    }.failure<ServiceBusyException>()
                acks(guest) shouldBe emptyList()
            }
        }

        test("a published body that breaks the paragraph rule (old row, manual edit) is never served half-way") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.ENC_CHURCH, label = PUBLISHED_LABEL, body = "Nur ein Absatz.")
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.failure<ServiceBusyException>()
            }
        }

        test("a malformed hash is a plain mismatch (conflict), never an exception of another kind") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                listOf("abc", "zz".repeat(32), "", "0".repeat(64)).forEach { bad ->
                    rig
                        .asMember(client = client, member = guest) {
                            it.enterSpace(
                                spaceId = space.toString(),
                                consent =
                                    EncounterConsentInput(
                                        consentVersion = EncounterChurchConsentDefault.VERSION,
                                        consentSha256 = bad,
                                    ),
                            )
                        }.failure<ConflictException>()
                }
            }
        }

        test("a label collision in the proof table (same label, other hash) is refused instead of passing as 'already on file'") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            // somebody holds a row under the very label of the current notice, but for a DIFFERENT text
            insertAck(guest, EncounterChurchConsentDefault.VERSION, "1".repeat(64))
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val current = consentFor(EncounterProfile.CHURCH_SERVICE)
                rig
                    .asMember(client = client, member = guest) { it.enterSpace(spaceId = space.toString(), consent = inputOf(current)) }
                    .failure<ServiceBusyException>()
                // the existing row is untouched
                acks(guest) shouldBe listOf(EncounterChurchConsentDefault.VERSION to "1".repeat(64))
            }
        }

        test("a guest who was in the door while the notice changed gets a conflict and can reload (existing behavior, pinned)") {
            val rig = EncounterRig()
            val (space, steward, guest) = roomWithGuest()
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                val shown = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow().disclaimer!!
                AgreementTextTestState.publishDirect(kind = AgreementTextKind.ENC_CHURCH, label = PUBLISHED_LABEL, body = PUBLISHED_BODY)
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = space.toString(),
                            consent = EncounterConsentInput(consentVersion = shown.version, consentSha256 = shown.sha256),
                        )
                    }.failure<ConflictException>()
                val reloaded = rig.asMember(client = client, member = guest) { it.getEntryInfo(space.toString()) }.getOrThrow().disclaimer!!
                reloaded.version shouldBe PUBLISHED_LABEL
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = space.toString(),
                            consent = EncounterConsentInput(consentVersion = reloaded.version, consentSha256 = reloaded.sha256),
                        )
                    }.getOrThrow()
            }
        }
    })
