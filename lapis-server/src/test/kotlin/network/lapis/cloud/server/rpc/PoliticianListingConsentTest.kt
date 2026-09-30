package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.routes.PublicProfilesReader
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the `POLITICIAN_LISTING` consent kind: its own disclosure
 * text (no ranking/cohort talk), a listing consent only for an appointed politician, revocation with
 * the politician status (so a later re-appointment never silently re-publishes), and no influence on
 * the two real leaderboards' cohorts.
 */
class PoliticianListingConsentTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = true
                }
            }
        }
        afterSpec {
            fixtures.cleanup()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = false
                }
            }
        }
        beforeTest { fixtures.neutralizeForeignProfiles() }

        fun routes(): Application.() -> Unit =
            {
                routing {
                    get("/t/disclaimer") {
                        val dto = DsgvoService(call = call).getPublicRankingConsentDisclaimer(PublicRankingKind.POLITICIAN_LISTING)
                        call.respondText(
                            listOf(dto.version, dto.sha256, dto.headline, dto.keyPoints.joinToString(), dto.text).joinToString("|"),
                        )
                    }
                    post("/t/grant") {
                        try {
                            val disclaimer = PublicRankingConsentDisclaimer.of(PublicRankingKind.POLITICIAN_LISTING)
                            val dto =
                                DsgvoService(call = call).grantPublicRankingConsent(
                                    kind = PublicRankingKind.POLITICIAN_LISTING,
                                    version = disclaimer.version,
                                    sha256 = disclaimer.sha256,
                                )
                            call.respondText(dto.effective.toString())
                        } catch (e: AbstractServiceException) {
                            call.respondText(e::class.simpleName!!, status = HttpStatusCode.Conflict)
                        }
                    }
                    post("/t/revoke") {
                        val dto = DsgvoService(call = call).revokePublicRankingConsent(PublicRankingKind.POLITICIAN_LISTING)
                        call.respondText(dto.effective.toString())
                    }
                    post("/t/revoke-status/{id}") {
                        try {
                            PoliticianService(call = call).revokePoliticianStatus(call.parameters["id"]!!)
                            call.respondText("ok")
                        } catch (e: AbstractServiceException) {
                            call.respondText(e::class.simpleName!!, status = HttpStatusCode.Conflict)
                        }
                    }
                    post("/t/appoint/{id}") {
                        PoliticianService(
                            call = call,
                        ).grantPoliticianStatus(memberId = call.parameters["id"]!!, mandateText = "Neu ernannt")
                        call.respondText("ok")
                    }
                }
            }

        suspend fun HttpClient.asMember(
            member: Uuid,
            path: String,
        ): HttpResponse = post(path) { header("X-Member-Id", member.toString()) }

        fun listedNames(): List<String> = transaction { PublicProfilesReader.loadPoliticianCards() }.map { it.name }

        test("the disclosure is its own text: no ranking, no cohort, no balance -- and a distinct hash") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                val parts = client.get("/t/disclaimer") { header("X-Member-Id", member.toString()) }.bodyAsText().split("|")
                parts[0] shouldBe "public-politician-listing-v1"
                val text = parts[4]
                text shouldContain "/politiker"
                text shouldContain "jederzeit widerrufen"
                for (forbidden in listOf("Rangliste", "Mindestkohorte", "fünf Mitglieder", "Guthaben", "Spendensumme")) {
                    text shouldNotContain forbidden
                }
                val ltr = PublicRankingConsentDisclaimer.of(PublicRankingKind.LTR_HOLDINGS)
                val donations = PublicRankingConsentDisclaimer.of(PublicRankingKind.DONATIONS)
                (parts[1] == ltr.sha256 || parts[1] == donations.sha256) shouldBe false
            }
        }

        test("a listing consent is refused without an appointed politician profile and stores nothing") {
            testApplication {
                application(routes())
                val member = fixtures.newMember()
                val response = client.asMember(member, "/t/grant")
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "BadRequestException"
                transaction { PublicRankingConsentStore.currentState(member) }
                    .single { it.kind == PublicRankingKind.POLITICIAN_LISTING }
                    .effective shouldBe false
            }
        }

        test("an appointed politician can grant and revoke; the listing follows the consent immediately") {
            testApplication {
                application(routes())
                val member = fixtures.newMember(displayName = "Paula Politikerin")
                fixtures.makePolitician(memberId = member)
                (listedNames().contains("Paula Politikerin")) shouldBe false

                client.asMember(member, "/t/grant").bodyAsText() shouldBe "true"
                (listedNames().contains("Paula Politikerin")) shouldBe true

                client.asMember(member, "/t/revoke").bodyAsText() shouldBe "false"
                (listedNames().contains("Paula Politikerin")) shouldBe false
            }
        }

        test("ending the politician status revokes the listing consent, and a later re-appointment does NOT re-publish") {
            testApplication {
                application(routes())
                val member = fixtures.newMember(displayName = "Rudi Rückruf")
                fixtures.makePolitician(memberId = member)
                client.asMember(member, "/t/grant").bodyAsText() shouldBe "true"
                (listedNames().contains("Rudi Rückruf")) shouldBe true

                val admin = Uuid.parse(ADMIN_ID)
                client.asMember(admin, "/t/revoke-status/$member").bodyAsText() shouldBe "ok"
                (listedNames().contains("Rudi Rückruf")) shouldBe false
                transaction { PublicRankingConsentStore.currentState(member) }
                    .single { it.kind == PublicRankingKind.POLITICIAN_LISTING }
                    .effective shouldBe false

                // A third party appoints the person again -- without a fresh consent they must stay unlisted.
                client.asMember(admin, "/t/appoint/$member").bodyAsText() shouldBe "ok"
                (listedNames().contains("Rudi Rückruf")) shouldBe false
            }
        }

        test("a listing consent under a stale wording does not list the politician") {
            val member = fixtures.newMember(displayName = "Sabine Stale")
            fixtures.makePolitician(memberId = member)
            fixtures.grantConsent(memberId = member, stale = true)
            (listedNames().contains("Sabine Stale")) shouldBe false
        }

        test("a FORMER politician with a leftover consent is never listed") {
            val member = fixtures.newMember(displayName = "Ferdi Former")
            fixtures.makePolitician(memberId = member, status = PoliticianProfileStatus.FORMER)
            fixtures.grantConsent(memberId = member)
            (listedNames().contains("Ferdi Former")) shouldBe false
        }

        test("the listing consent never changes the cohort or the ranking of the two real leaderboards") {
            val member = fixtures.newMember()
            fixtures.makePolitician(memberId = member)
            val ltrBefore = transaction { PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.LTR_HOLDINGS) }
            val donationsBefore = transaction { PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.DONATIONS) }
            val listingBefore = transaction { PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.POLITICIAN_LISTING) }
            fixtures.grantConsent(memberId = member, kind = PublicRankingKind.POLITICIAN_LISTING)
            transaction { PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.LTR_HOLDINGS) } shouldBe ltrBefore
            transaction { PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.DONATIONS) } shouldBe donationsBefore
            transaction { PublicRankingConsentStore.effectiveCohortSize(PublicRankingKind.POLITICIAN_LISTING) } shouldBe listingBefore + 1L
        }
    })
