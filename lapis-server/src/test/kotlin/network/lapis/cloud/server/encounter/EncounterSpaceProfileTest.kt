package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.67 "Begegnungsraum Stufe 1" -- the room profile and the allowed reaction set: create/update semantics (including the
 * `null` = "unchanged" contract an old cached admin client relies on), the freeze while a session is open, the audit snapshots and the
 * per-profile Art. 9 consent.
 */
class EncounterSpaceProfileTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun board() = fx.createMember(role = AccountRole.BOARD)

        fun storedReactionSet(space: Uuid): String =
            transaction {
                EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq space }.single()[EncounterSpaceTable.reactionSet]
            }

        test("createSpace: ASSEMBLY with duplicated, unordered reactions is stored canonical (HAND first, deduplicated)") {
            val rig = EncounterRig()
            val boardId = board()
            encounterApp {
                val dto =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(
                                EncounterSpaceInput(
                                    title = "Mitgliederversammlung",
                                    profile = EncounterProfile.ASSEMBLY,
                                    reactions =
                                        listOf(
                                            EncounterReactionOption.HEART,
                                            EncounterReactionOption.HAND,
                                            EncounterReactionOption.HEART,
                                        ),
                                ),
                            )
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                dto.profile shouldBe EncounterProfile.ASSEMBLY
                dto.reactions shouldContainExactly listOf(EncounterReactionOption.HAND, EncounterReactionOption.HEART)
                storedReactionSet(Uuid.parse(dto.id)) shouldBe "HAND,HEART"
            }
        }

        test("createSpace: null profile and null reactions mean the church service with HAND,AMEN; the theme stays frozen at CHURCH") {
            val rig = EncounterRig()
            val boardId = board()
            encounterApp {
                val dto =
                    rig
                        .asMember(
                            client = client,
                            member = boardId,
                        ) { it.createSpace(EncounterSpaceInput(title = "Gottesdienst")) }
                        .getOrThrow()
                fx.spaceIds += Uuid.parse(dto.id)
                dto.profile shouldBe EncounterProfile.CHURCH_SERVICE
                dto.reactions shouldContainExactly listOf(EncounterReactionOption.HAND, EncounterReactionOption.AMEN)
                dto.theme.name shouldBe "CHURCH"
            }
        }

        test("createSpace: an ASSEMBLY without explicit reactions gets HAND,APPLAUSE; an empty list is just HAND") {
            val rig = EncounterRig()
            val boardId = board()
            encounterApp {
                val defaults =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(EncounterSpaceInput(title = "Versammlung", profile = EncounterProfile.ASSEMBLY))
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(defaults.id)
                defaults.reactions shouldContainExactly listOf(EncounterReactionOption.HAND, EncounterReactionOption.APPLAUSE)
                val onlyHand =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.createSpace(
                                EncounterSpaceInput(title = "Stille", profile = EncounterProfile.ASSEMBLY, reactions = emptyList()),
                            )
                        }.getOrThrow()
                fx.spaceIds += Uuid.parse(onlyHand.id)
                onlyHand.reactions shouldContainExactly listOf(EncounterReactionOption.HAND)
                storedReactionSet(Uuid.parse(onlyHand.id)) shouldBe "HAND"
            }
        }

        test("createSpace: more than 8 reaction entries are a BadRequest (DoS guard)") {
            val rig = EncounterRig()
            val boardId = board()
            encounterApp {
                rig
                    .asMember(client = client, member = boardId) {
                        it.createSpace(EncounterSpaceInput(title = "x", reactions = List(9) { EncounterReactionOption.HAND }))
                    }.failure<BadRequestException>()
            }
        }

        test("updateSpace: null profile and reactions leave an ASSEMBLY room unchanged (an old cached admin client sends neither)") {
            val rig = EncounterRig()
            val boardId = board()
            val space = fx.createSpace(createdBy = boardId, profile = EncounterProfile.ASSEMBLY)
            encounterApp {
                val updated =
                    rig
                        .asMember(client = client, member = boardId) {
                            it.updateSpace(spaceId = space.toString(), input = EncounterSpaceInput(title = "Neuer Titel"))
                        }.getOrThrow()
                updated.profile shouldBe EncounterProfile.ASSEMBLY
                updated.reactions shouldContainExactly listOf(EncounterReactionOption.HAND, EncounterReactionOption.APPLAUSE)
            }
        }

        test("updateSpace: a profile change while closed is allowed and audited with the room values only") {
            val rig = EncounterRig()
            val boardId = board()
            val space = fx.createSpace(createdBy = boardId)
            encounterApp {
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input =
                                EncounterSpaceInput(
                                    title = "Gottesdienst",
                                    profile = EncounterProfile.ASSEMBLY,
                                    reactions = listOf(EncounterReactionOption.APPLAUSE),
                                ),
                        )
                    }.getOrThrow()
                    .profile shouldBe EncounterProfile.ASSEMBLY
                val audit = auditEntriesOf(space)
                audit.map { it.first } shouldContainExactly listOf("UPDATE")
                audit.single().second!! shouldContain "\"profile\":\"ASSEMBLY\""
                audit.single().second!! shouldContain "\"reactions\":\"HAND,APPLAUSE\""
                audit.single().second!! shouldNotContain boardId.toString()
            }
        }

        test(
            "updateSpace: a profile or reaction change while a session is open is a Conflict and changes nothing; the title and unchanged values pass",
        ) {
            val rig = EncounterRig()
            val boardId = board()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = boardId)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input = EncounterSpaceInput(title = "x", profile = EncounterProfile.ASSEMBLY),
                        )
                    }.failure<ConflictException>()
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input = EncounterSpaceInput(title = "x", reactions = listOf(EncounterReactionOption.HEART)),
                        )
                    }.failure<ConflictException>()
                storedReactionSet(space) shouldBe "HAND,AMEN"
                // unchanged explicit values while open are fine, so is a new title
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input =
                                EncounterSpaceInput(
                                    title = "Neuer Titel",
                                    profile = EncounterProfile.CHURCH_SERVICE,
                                    reactions = listOf(EncounterReactionOption.AMEN, EncounterReactionOption.HAND),
                                ),
                        )
                    }.getOrThrow()
                    .title shouldBe "Neuer Titel"
                // after closing the change goes through
                rig.asMember(client = client, member = steward) { it.closeSpace(space.toString()) }.getOrThrow()
                rig
                    .asMember(client = client, member = boardId) {
                        it.updateSpace(
                            spaceId = space.toString(),
                            input = EncounterSpaceInput(title = "x", profile = EncounterProfile.ASSEMBLY),
                        )
                    }.getOrThrow()
                    .profile shouldBe EncounterProfile.ASSEMBLY
            }
        }

        test("consent: each profile has its own text and hash; consent for the church text does not satisfy an ASSEMBLY room") {
            val rig = EncounterRig()
            val steward = fx.createMember()
            val guest = fx.createMember(status = MemberStatus.GUEST)
            val church = fx.createSpace(createdBy = board(), guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            val assembly =
                fx.createSpace(
                    createdBy = board(),
                    guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS,
                    profile = EncounterProfile.ASSEMBLY,
                )
            fx.setRole(spaceId = church, memberId = steward, role = EncounterSpaceRole.STEWARD)
            fx.setRole(spaceId = assembly, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(church.toString()) }.getOrThrow()
                rig.asMember(client = client, member = steward) { it.openSpace(assembly.toString()) }.getOrThrow()

                val churchInfo = rig.asMember(client = client, member = guest) { it.getEntryInfo(church.toString()) }.getOrThrow()
                churchInfo.disclaimer!!.version shouldBe EncounterConsentDisclaimer.VERSION
                val assemblyInfo = rig.asMember(client = client, member = guest) { it.getEntryInfo(assembly.toString()) }.getOrThrow()
                assemblyInfo.disclaimer!!.version shouldBe EncounterAssemblyConsentDisclaimer.VERSION
                assemblyInfo.disclaimer!!.sha256 shouldBe EncounterAssemblyConsentDisclaimer.SHA256

                // submitting the CHURCH text for an ASSEMBLY room is rejected
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = assembly.toString(),
                            consent =
                                EncounterConsentInput(
                                    consentVersion = EncounterConsentDisclaimer.VERSION,
                                    consentSha256 = EncounterConsentDisclaimer.SHA256,
                                ),
                        )
                    }.failure<ConflictException>()

                // consent for the church room does not carry over
                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = church.toString(),
                            consent =
                                EncounterConsentInput(
                                    consentVersion = EncounterConsentDisclaimer.VERSION,
                                    consentSha256 = EncounterConsentDisclaimer.SHA256,
                                ),
                        )
                    }.getOrThrow()
                rig.asMember(client = client, member = guest) { it.getEntryInfo(church.toString()) }.getOrThrow().consentRequired shouldBe
                    false
                rig.asMember(client = client, member = guest) { it.getEntryInfo(assembly.toString()) }.getOrThrow().consentRequired shouldBe
                    true
                rig
                    .asMember(client = client, member = guest) { it.enterSpace(spaceId = assembly.toString(), consent = null) }
                    .failure<ConflictException>()

                rig
                    .asMember(client = client, member = guest) {
                        it.enterSpace(
                            spaceId = assembly.toString(),
                            consent =
                                EncounterConsentInput(
                                    consentVersion = EncounterAssemblyConsentDisclaimer.VERSION,
                                    consentSha256 = EncounterAssemblyConsentDisclaimer.SHA256,
                                ),
                        )
                    }.getOrThrow()
                val versions =
                    transaction {
                        EncounterConsentAcknowledgmentTable
                            .selectAll()
                            .where { EncounterConsentAcknowledgmentTable.memberId eq guest }
                            .map { it[EncounterConsentAcknowledgmentTable.consentVersion] }
                    }
                versions.toSet() shouldBe setOf(EncounterConsentDisclaimer.VERSION, EncounterAssemblyConsentDisclaimer.VERSION)
            }
        }
    })
