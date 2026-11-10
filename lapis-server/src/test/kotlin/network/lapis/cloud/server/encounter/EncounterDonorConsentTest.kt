package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * A donor is a non-member: it enters like a member (access, token TTL, no guest cap) but owes the same stored, explicit consent as a guest or
 * friend (DSGVO proof) -- without it, entering is a Conflict and no acknowledgment row exists.
 */
class EncounterDonorConsentTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        fun acknowledgmentCount(member: Uuid): Long =
            transaction {
                EncounterConsentAcknowledgmentTable
                    .selectAll()
                    .where { EncounterConsentAcknowledgmentTable.memberId eq member }
                    .count()
            }

        test(
            "a donor must acknowledge the consent text: entry info demands it, entering without it is a Conflict, with it one row is written",
        ) {
            val w = fx.tableWorld(mapEnabled = true)
            val donor = fx.createMember(status = MemberStatus.DONOR, name = "Spender")
            encounterApp {
                openAndEnter(w = w, w.steward)
                val info = w.rig.asMember(client = client, member = donor) { it.getEntryInfo(w.spaceId) }.getOrThrow()
                info.consentRequired shouldBe true
                val disclaimer = info.disclaimer!!

                w.rig
                    .asMember(
                        client = client,
                        member = donor,
                    ) { it.enterSpace(spaceId = w.spaceId, consent = null) }
                    .failure<ConflictException>()
                acknowledgmentCount(donor) shouldBe 0L

                w.rig
                    .asMember(client = client, member = donor) {
                        it.enterSpace(
                            spaceId = w.spaceId,
                            consent = EncounterConsentInput(consentVersion = disclaimer.version, consentSha256 = disclaimer.sha256),
                        )
                    }.getOrThrow()
                acknowledgmentCount(donor) shouldBe 1L

                // the consent is on file now: no second prompt
                val again = w.rig.asMember(client = client, member = donor) { it.getEntryInfo(w.spaceId) }.getOrThrow()
                again.consentRequired shouldBe false
            }
        }
    })
