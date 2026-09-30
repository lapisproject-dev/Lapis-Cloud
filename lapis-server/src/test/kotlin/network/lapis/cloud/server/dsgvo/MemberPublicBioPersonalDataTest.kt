package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- [MemberPublicBioPersonalData]: the export carries the text,
 * the publication state, the consent version and the timestamps (Art. 15/20); erasure is a hard delete
 * of the row in EVERY [ErasureMode]. The politician-listing consent events are covered by the
 * existing table-based consent contributor, which this spec also pins.
 */
class MemberPublicBioPersonalDataTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        test("section key, display name and covered table are registered in the PersonalDataRegistry") {
            MemberPublicBioPersonalData.sectionKey shouldBe "memberPublicBio"
            MemberPublicBioPersonalData.displayName shouldBe "Öffentliche Kurzvorstellung"
            (MemberPublicBioPersonalData in PersonalDataRegistry.contributors) shouldBe true
            MemberPublicBioPersonalData.coveredTables shouldBe setOf(MemberPublicBioTable)
        }

        test("export: the text, the publication state, the version and the timestamps -- a private bio says published=false") {
            val member = fixtures.newMember()
            fixtures.seedBio(memberId = member, text = "Mein Exporttext", publish = true)
            val json = transaction { MemberPublicBioPersonalData.exportMember(member) }.jsonObject
            json.getValue("hasPublicBio").jsonPrimitive.content shouldBe "true"
            json.getValue("text").jsonPrimitive.content shouldBe "Mein Exporttext"
            json.getValue("published").jsonPrimitive.content shouldBe "true"
            json.getValue("consentTextVersion").jsonPrimitive.content shouldBe "member-bio-public-v1"
            json.containsKey("consentGrantedAt") shouldBe true
            json.containsKey("updatedAt") shouldBe true

            val privateMember = fixtures.newMember()
            fixtures.seedBio(memberId = privateMember, text = "Privat", publish = false)
            val priv = transaction { MemberPublicBioPersonalData.exportMember(privateMember) }.jsonObject
            priv.getValue("published").jsonPrimitive.content shouldBe "false"
            priv.containsKey("consentGrantedAt") shouldBe false
        }

        test("export for a member without a bio says so") {
            val none = fixtures.newMember()
            transaction { MemberPublicBioPersonalData.exportMember(none) }
                .jsonObject
                .getValue("hasPublicBio")
                .jsonPrimitive.content shouldBe "false"
        }

        test("erase: the row is hard-deleted in EVERY erasure mode, another member's bio is untouched") {
            ErasureMode.entries.forEach { mode ->
                val member = fixtures.newMember()
                val other = fixtures.newMember()
                fixtures.seedBio(memberId = member, text = "Weg damit", publish = true)
                fixtures.seedBio(memberId = other, text = "Bleibt")
                val outcomes = transaction { MemberPublicBioPersonalData.eraseMember(memberId = member, mode = mode) }
                outcomes.single().table shouldBe "member_public_bio"
                outcomes.single().rowsDeleted shouldBe 1
                fixtures.bioRowCount(member) shouldBe 0L
                fixtures.bioRowCount(other) shouldBe 1L
            }
        }

        test("the POLITICIAN_LISTING consent events are exported and erased by the existing consent contributor (no new code needed)") {
            val member = fixtures.newMember()
            fixtures.makePolitician(memberId = member)
            fixtures.grantConsent(memberId = member)
            val exported = transaction { PublicRankingConsentPersonalData.exportMember(member) }.toString()
            exported.contains("POLITICIAN_LISTING") shouldBe true

            transaction { PublicRankingConsentPersonalData.eraseMember(memberId = member, mode = ErasureMode.entries.first()) }
            transaction {
                PublicRankingConsentEventTable
                    .selectAll()
                    .where {
                        (PublicRankingConsentEventTable.memberId eq member) and
                            (PublicRankingConsentEventTable.rankingKind eq PublicRankingKind.POLITICIAN_LISTING)
                    }.count()
            } shouldBe 0L
            transaction { PublicRankingConsentStore.currentState(member) }
                .single { it.kind == PublicRankingKind.POLITICIAN_LISTING }
                .effective shouldBe false
        }
    })
