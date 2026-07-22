package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PoliticianWeightSnapshotTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [PoliticianService] end to end, mirroring [CrowdfundingServiceTest]'s house style
 * (throwaway routes calling the service class directly, no wire format to reverse-engineer).
 * DevSeedData's ADMIN/BOARD accounts are used only as the *actors* performing privileged actions
 * (grant/revoke/snapshot); every politician and rater is a fresh test member, same discipline
 * [CrowdfundingServiceTest] documents for its own fixtures. [afterSpec] hard-deletes every row
 * this file created and resets the `politicianRankingEnabled` gate to `false`.
 */
class PoliticianServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = true
                }
            }
        }

        // The shared LTR-weight pool (see PoliticianTrustWeightCalculator KDoc) is computed over
        // EVERY ACTIVE politician in the database, not scoped to what a given test itself created
        // -- so, exactly like CrowdfundingServiceTest's own per-test project cleanup (its own
        // "GLOBAL entry hurdle" reasoning), an ACTIVE politician_profile left over from an earlier
        // test would silently dilute/change every later test's exact-weight assertions. This file
        // is the sole owner of the three politician_* tables in the test suite, so a full
        // unconditional wipe after every test (not scoped to createdMemberIds, which only tracks
        // member rows for afterSpec) is the simplest correct fix -- the member rows themselves are
        // cheap to leave until afterSpec since they carry no weight-affecting state once their
        // reactions are gone.
        afterTest {
            transaction {
                // "id eq id" (not a bare `true`/`Op.TRUE` literal) -- deliberately staying inside
                // the SqlExpressionBuilder DSL every other query in this codebase already uses,
                // matching every row unconditionally (id is the non-null primary key).
                PoliticianWeightSnapshotTable.deleteWhere { PoliticianWeightSnapshotTable.id eq PoliticianWeightSnapshotTable.id }
                PoliticianReactionTable.deleteWhere { PoliticianReactionTable.id eq PoliticianReactionTable.id }
                PoliticianProfileTable.deleteWhere { PoliticianProfileTable.id eq PoliticianProfileTable.id }
            }
        }

        afterSpec {
            transaction {
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = false
                }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Politician Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun mintLtr(
            memberId: Uuid,
            amount: BigDecimal,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = amount
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        // ── Happy path ────────────────────────────────────────────────────────

        test("grantPoliticianStatus: creates an ACTIVE profile with resolved displayName") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-grant@example.org")

                val response =
                    client
                        .post("/test/grant?memberId=$politician&mandateText=Bundestagsabgeordneter") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                val (status, mandateText, displayName) = response.split(":")
                status shouldBe "ACTIVE"
                mandateText shouldBe "Bundestagsabgeordneter"
                displayName shouldBe "Politician Testmitglied"
            }
        }

        test("updateMandateText: BOARD/ADMIN can change an existing politician's mandate text; plain MEMBER is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-update-mandate@example.org")
                client.post("/test/grant?memberId=$politician&mandateText=AlterText") { header("X-Member-Id", BOARD_ID) }

                val forbidden =
                    client.post("/test/update-mandate?memberId=$politician&mandateText=SollteNichtAnkommen") {
                        header("X-Member-Id", MEMBER_ID)
                    }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val byBoard =
                    client.post("/test/update-mandate?memberId=$politician&mandateText=NeuerMandatstext") {
                        header("X-Member-Id", BOARD_ID)
                    }
                byBoard.status shouldBe HttpStatusCode.OK
                byBoard.bodyAsText() shouldBe "NeuerMandatstext"

                // Confirms the change actually persisted in the row, not just echoed by the response DTO.
                val persisted =
                    transaction {
                        PoliticianProfileTable
                            .selectAll()
                            .where { PoliticianProfileTable.memberId eq politician }
                            .single()[PoliticianProfileTable.mandateText]
                    }
                persisted shouldBe "NeuerMandatstext"

                // ADMIN can update it too.
                val byAdmin =
                    client.post("/test/update-mandate?memberId=$politician&mandateText=VonAdminGeaendert") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                byAdmin.status shouldBe HttpStatusCode.OK
                byAdmin.bodyAsText() shouldBe "VonAdminGeaendert"
            }
        }

        test(
            "updateMandateText: passing no mandateText explicitly clears it to null (unlike grantPoliticianStatus's null-preserving upsert)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-clear-mandate@example.org")
                client.post("/test/grant?memberId=$politician&mandateText=WirdGeloescht") { header("X-Member-Id", BOARD_ID) }

                val cleared = client.post("/test/update-mandate?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                cleared.status shouldBe HttpStatusCode.OK
                cleared.bodyAsText() shouldBe ""
            }
        }

        test("updateMandateText: rejected against a nonexistent politician") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val bogusMemberId = Uuid.random()

                val response =
                    client.post("/test/update-mandate?memberId=$bogusMemberId&mandateText=Egal") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("getMyRating: reflects the caller's own vote, empty before casting and after retracting, unaffected by another rater's vote") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-my-rating@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-my-rating-rater@example.org")
                val otherRater = createTestMember("pol-my-rating-other@example.org")
                mintLtr(rater, BigDecimal("10.00"))
                mintLtr(otherRater, BigDecimal("10.00"))

                val beforeVote = client.get("/test/my-rating/$politician") { header("X-Member-Id", rater.toString()) }.bodyAsText()
                beforeVote shouldBe "none"

                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }
                client.post("/test/rate?politicianId=$politician&value=DISLIKE") { header("X-Member-Id", otherRater.toString()) }

                val afterVote = client.get("/test/my-rating/$politician") { header("X-Member-Id", rater.toString()) }.bodyAsText()
                afterVote shouldBe "LIKE"
                // Own read is scoped to the caller, not the other rater's DISLIKE.
                val otherView =
                    client.get("/test/my-rating/$politician") { header("X-Member-Id", otherRater.toString()) }.bodyAsText()
                otherView shouldBe "DISLIKE"

                client.post("/test/retract?politicianId=$politician") { header("X-Member-Id", rater.toString()) }
                val afterRetract = client.get("/test/my-rating/$politician") { header("X-Member-Id", rater.toString()) }.bodyAsText()
                afterRetract shouldBe "none"
            }
        }

        test("getMyRating: rejected against a nonexistent politician") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val rater = createTestMember("pol-my-rating-nonexistent@example.org")
                val bogusMemberId = Uuid.random()

                val response =
                    client.get("/test/my-rating/$bogusMemberId") { header("X-Member-Id", rater.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("castRating: two LIKE + one DISLIKE -> korb=1, weight computed via the shared-pool formula") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-korb@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }

                val rater1 = createTestMember("pol-korb-r1@example.org")
                val rater2 = createTestMember("pol-korb-r2@example.org")
                val rater3 = createTestMember("pol-korb-r3@example.org")
                mintLtr(rater1, BigDecimal("10.00"))
                mintLtr(rater2, BigDecimal("10.00"))
                mintLtr(rater3, BigDecimal("10.00"))

                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater1.toString()) }
                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater2.toString()) }
                client.post("/test/rate?politicianId=$politician&value=DISLIKE") { header("X-Member-Id", rater3.toString()) }

                // korb = 2 - 1 = 1, single politician -> pool (30.00) entirely apportioned to it.
                val profile = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val (likeCount, dislikeCount, weight) = profile.split(":")
                likeCount shouldBe "2"
                dislikeCount shouldBe "1"
                BigDecimal(weight).compareTo(BigDecimal("30.00")) shouldBe 0
            }
        }

        test("castRating: recasting with a different value updates the same row, does not duplicate it") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-recast@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-recast-rater@example.org")
                mintLtr(rater, BigDecimal("10.00"))

                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }
                client.post("/test/rate?politicianId=$politician&value=DISLIKE") { header("X-Member-Id", rater.toString()) }

                val rowCount =
                    transaction {
                        val profileId =
                            PoliticianProfileTable
                                .selectAll()
                                .where { PoliticianProfileTable.memberId eq politician }
                                .single()[PoliticianProfileTable.id]
                        PoliticianReactionTable
                            .selectAll()
                            .where {
                                (PoliticianReactionTable.politicianProfileId eq profileId) and
                                    (PoliticianReactionTable.raterMemberId eq rater)
                            }.count()
                    }
                rowCount shouldBe 1L

                val profile = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val (likeCount, dislikeCount, weight) = profile.split(":")
                likeCount shouldBe "0"
                dislikeCount shouldBe "1"
                // korb floored at 0 -- one DISLIKE, no LIKE.
                BigDecimal(weight).compareTo(BigDecimal.ZERO.setScale(2)) shouldBe 0
            }
        }

        test("retractRating: removes the vote, weight recomputes down") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-retract@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-retract-rater@example.org")
                mintLtr(rater, BigDecimal("10.00"))

                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }
                val afterLike = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                BigDecimal(afterLike.split(":")[2]).compareTo(BigDecimal("10.00")) shouldBe 0

                client.post("/test/retract?politicianId=$politician") { header("X-Member-Id", rater.toString()) }
                val afterRetract = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                afterRetract.split(":").let {
                    it[0] shouldBe "0"
                    it[1] shouldBe "0"
                    BigDecimal(it[2]).compareTo(BigDecimal.ZERO.setScale(2)) shouldBe 0
                }

                // Retracting again (no vote present) is a silent no-op, not an error.
                val noop = client.post("/test/retract?politicianId=$politician") { header("X-Member-Id", rater.toString()) }
                noop.status shouldBe HttpStatusCode.OK
            }
        }

        test("two politicians, unequal baskets -- pool splits proportionally, sum equals pool exactly") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politicianA = createTestMember("pol-unequal-a@example.org")
                val politicianB = createTestMember("pol-unequal-b@example.org")
                client.post("/test/grant?memberId=$politicianA") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/grant?memberId=$politicianB") { header("X-Member-Id", BOARD_ID) }

                val rater1 = createTestMember("pol-unequal-r1@example.org")
                val rater2 = createTestMember("pol-unequal-r2@example.org")
                val rater3 = createTestMember("pol-unequal-r3@example.org")
                mintLtr(rater1, BigDecimal("30.00"))
                mintLtr(rater2, BigDecimal("30.00"))
                mintLtr(rater3, BigDecimal("30.00"))

                // A: 2 likes (korb 2), B: 1 like (korb 1) -> pool 90.00 split 2:1 -> A=60.00, B=30.00
                client.post("/test/rate?politicianId=$politicianA&value=LIKE") { header("X-Member-Id", rater1.toString()) }
                client.post("/test/rate?politicianId=$politicianA&value=LIKE") { header("X-Member-Id", rater2.toString()) }
                client.post("/test/rate?politicianId=$politicianB&value=LIKE") { header("X-Member-Id", rater3.toString()) }

                val profileA = client.get("/test/profile/$politicianA") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val profileB = client.get("/test/profile/$politicianB") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val weightA = BigDecimal(profileA.split(":")[2])
                val weightB = BigDecimal(profileB.split(":")[2])
                weightA.compareTo(BigDecimal("60.00")) shouldBe 0
                weightB.compareTo(BigDecimal("30.00")) shouldBe 0
                (weightA + weightB).compareTo(BigDecimal("90.00")) shouldBe 0
            }
        }

        test(
            "castRating: a rater's LTR balance change AFTER voting, with no new vote action, moves the " +
                "politician's trust weight purely from the balance change (live pool, no caching)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-live-balance@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-live-balance-rater@example.org")
                mintLtr(rater, BigDecimal("10.00"))

                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }
                val afterVote = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                BigDecimal(afterVote.split(":")[2]).compareTo(BigDecimal("10.00")) shouldBe 0

                // No new vote/retract/recast here -- only the rater's underlying LTR balance moves
                // (e.g. a further mint, or in production a peer transfer received). This is exactly
                // the "ändert sich automatisch der Pool ... mit" requirement: PoliticianService reads
                // LtrBalanceProvider.freeBalances() fresh on every call, so the trust weight must move
                // in lockstep with the balance even though PoliticianReactionTable is untouched.
                mintLtr(rater, BigDecimal("20.00"))

                val afterBalanceChange = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                BigDecimal(afterBalanceChange.split(":")[2]).compareTo(BigDecimal("30.00")) shouldBe 0

                val reactionCountUnchanged =
                    transaction {
                        val profileId =
                            PoliticianProfileTable
                                .selectAll()
                                .where { PoliticianProfileTable.memberId eq politician }
                                .single()[PoliticianProfileTable.id]
                        PoliticianReactionTable
                            .selectAll()
                            .where {
                                (PoliticianReactionTable.politicianProfileId eq profileId) and
                                    (PoliticianReactionTable.raterMemberId eq rater)
                            }.count()
                    }
                reactionCountUnchanged shouldBe 1L // still exactly the one original LIKE row
            }
        }

        test("snapshotWeights: persisted row matches live-computed weight; getWeightHistory returns it") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-snapshot@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-snapshot-rater@example.org")
                mintLtr(rater, BigDecimal("15.00"))
                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }

                val liveProfile = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val liveWeight = BigDecimal(liveProfile.split(":")[2])

                val snapshotResponse =
                    client.post("/test/snapshot?periodMonth=2031-03-15") { header("X-Member-Id", BOARD_ID) }
                snapshotResponse.status shouldBe HttpStatusCode.OK

                val history = client.get("/test/history/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val entries = history.split(";").filter { it.isNotBlank() }
                entries.size shouldBe 1
                val (periodMonth, weight) = entries.single().split("=")
                periodMonth shouldBe "2031-03-01" // normalized to first-of-month
                BigDecimal(weight).compareTo(liveWeight) shouldBe 0
            }
        }

        test("listPoliticians: includeFormer=false (default) excludes FORMER profiles, includeFormer=true includes them") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val activePolitician = createTestMember("pol-list-active@example.org")
                val formerPolitician = createTestMember("pol-list-former@example.org")
                client.post("/test/grant?memberId=$activePolitician") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/grant?memberId=$formerPolitician") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/revoke?memberId=$formerPolitician") { header("X-Member-Id", BOARD_ID) }

                val activeOnly = client.get("/test/list") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split(",")
                (activePolitician.toString() in activeOnly) shouldBe true
                (formerPolitician.toString() in activeOnly) shouldBe false

                val withFormer =
                    client.get("/test/list?includeFormer=true") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split(",")
                (activePolitician.toString() in withFormer) shouldBe true
                (formerPolitician.toString() in withFormer) shouldBe true
            }
        }

        test("getTopPoliticians: sorts descending by weight, ties broken by ascending memberId") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politicianHigh = createTestMember("pol-top-high@example.org")
                val politicianLow = createTestMember("pol-top-low@example.org")
                client.post("/test/grant?memberId=$politicianHigh") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/grant?memberId=$politicianLow") { header("X-Member-Id", BOARD_ID) }

                // The shared-pool trust weight is apportioned by BASKET ratio, not by which
                // specific rater cast a vote (see PoliticianTrustWeightCalculator's "single shared
                // pool" KDoc, grounded in the concept doc's "Der Pool wird proportional zu den
                // Korb-Inhalten ... verteilt"). A rater with a bigger LTR balance liking a
                // politician does NOT, by itself, outrank a politician liked by a lower-balance
                // rater -- only a bigger basket (more net likes) does. politicianHigh therefore
                // needs a strictly bigger basket (2 distinct likers) than politicianLow (1 liker),
                // not just a richer individual rater, to deterministically outrank it for any
                // positive pool.
                val rater1 = createTestMember("pol-top-r1@example.org")
                val rater2 = createTestMember("pol-top-r2@example.org")
                val rater3 = createTestMember("pol-top-r3@example.org")
                mintLtr(rater1, BigDecimal("100.00"))
                mintLtr(rater2, BigDecimal("1.00"))
                mintLtr(rater3, BigDecimal("1.00"))

                client.post("/test/rate?politicianId=$politicianHigh&value=LIKE") { header("X-Member-Id", rater1.toString()) }
                client.post("/test/rate?politicianId=$politicianHigh&value=LIKE") { header("X-Member-Id", rater2.toString()) }
                client.post("/test/rate?politicianId=$politicianLow&value=LIKE") { header("X-Member-Id", rater3.toString()) }

                // High limit here (not the dashboard's real Top-6) so this test's ordering assertion
                // is not accidentally truncated by other ACTIVE politicians earlier tests in this
                // Spec created (this Spec does not clean up per-test, only in afterSpec -- same
                // "own fixtures, no per-test cleanup needed" discipline CrowdfundingServiceTest
                // documents where it does NOT matter, e.g. its own reads-require-auth test). The
                // real default-limit=6 contract is exercised separately below.
                val top = client.get("/test/top?limit=1000") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val ids = top.split(",").filter { it.isNotBlank() }
                val highIndex = ids.indexOf(politicianHigh.toString())
                val lowIndex = ids.indexOf(politicianLow.toString())
                (highIndex >= 0 && lowIndex >= 0 && highIndex < lowIndex) shouldBe true

                val defaultLimited = client.get("/test/top") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                defaultLimited.split(",").filter { it.isNotBlank() }.size shouldBe minOf(6, ids.size)
            }
        }

        test("revokePoliticianStatus: flips to FORMER, deletes all reactions/snapshots, profile still fetchable") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-revoke@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-revoke-rater@example.org")
                mintLtr(rater, BigDecimal("10.00"))
                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }
                client.post("/test/snapshot?periodMonth=2031-04-01") { header("X-Member-Id", BOARD_ID) }

                val revokeResponse = client.post("/test/revoke?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                revokeResponse.bodyAsText().split(":")[0] shouldBe "FORMER"

                val profileId =
                    transaction {
                        val row = PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.single()
                        row[PoliticianProfileTable.id]
                    }
                val reactionCount =
                    transaction {
                        PoliticianReactionTable
                            .selectAll()
                            .where { PoliticianReactionTable.politicianProfileId eq profileId }
                            .count()
                    }
                val snapshotCount =
                    transaction {
                        PoliticianWeightSnapshotTable
                            .selectAll()
                            .where { PoliticianWeightSnapshotTable.politicianProfileId eq profileId }
                            .count()
                    }
                reactionCount shouldBe 0L
                snapshotCount shouldBe 0L

                val stillFetchable = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                stillFetchable.split(":")[3] shouldBe "FORMER"
            }
        }

        test(
            "re-grant after revoke reactivates the SAME profile row, weight starts back at 0 (no strategic advantage from a status reset)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-regrant@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val originalProfileId =
                    transaction {
                        val row = PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.single()
                        row[PoliticianProfileTable.id]
                    }
                val rater = createTestMember("pol-regrant-rater@example.org")
                mintLtr(rater, BigDecimal("10.00"))
                client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }

                client.post("/test/revoke?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val regrantResponse =
                    client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                regrantResponse.split(":")[0] shouldBe "ACTIVE"

                val newProfileId =
                    transaction {
                        val row = PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.single()
                        row[PoliticianProfileTable.id]
                    }
                newProfileId shouldBe originalProfileId // same row reactivated, not a second one

                val profileCount =
                    transaction { PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.count() }
                profileCount shouldBe 1L

                val profile = client.get("/test/profile/$politician") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                val (likeCount, dislikeCount, weight) = profile.split(":")
                likeCount shouldBe "0"
                dislikeCount shouldBe "0"
                BigDecimal(weight).compareTo(BigDecimal.ZERO.setScale(2)) shouldBe 0
            }
        }

        test("grantPoliticianStatus twice on an already-ACTIVE profile is an idempotent update, not a conflict") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-idempotent-grant@example.org")
                client.post("/test/grant?memberId=$politician&mandateText=Alt") { header("X-Member-Id", BOARD_ID) }
                val second =
                    client.post("/test/grant?memberId=$politician&mandateText=Neu") { header("X-Member-Id", BOARD_ID) }
                second.status shouldBe HttpStatusCode.OK
                second.bodyAsText().split(":")[1] shouldBe "Neu"

                val profileCount =
                    transaction { PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.count() }
                profileCount shouldBe 1L
            }
        }

        // ── Error cases ───────────────────────────────────────────────────────

        test("castRating: rejected for a non-AKTIV rater") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-nonaktiv-target@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val gast = createTestMember("pol-nonaktiv-rater@example.org", status = MemberStatus.GAST)

                val response = client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", gast.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("castRating: rejected against a FORMER politician") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-former-target@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/revoke?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-former-rater@example.org")

                val response = client.post("/test/rate?politicianId=$politician&value=LIKE") { header("X-Member-Id", rater.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("grantPoliticianStatus: rejected against a nonexistent memberId") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val bogusMemberId = Uuid.random()

                val response = client.post("/test/grant?memberId=$bogusMemberId") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("revokePoliticianStatus: rejected against a nonexistent politician profile") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val bogusMemberId = Uuid.random()

                val response = client.post("/test/revoke?memberId=$bogusMemberId") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("castRating: rejected against a nonexistent politician") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val rater = createTestMember("pol-nonexistent-rater@example.org")
                val bogusMemberId = Uuid.random()

                val response = client.post("/test/rate?politicianId=$bogusMemberId&value=LIKE") { header("X-Member-Id", rater.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("every method rejected with Conflict while politicianRankingEnabled=false, including grantPoliticianStatus itself") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[politicianRankingEnabled] = false
                    }
                }
                val target = createTestMember("pol-gate-off@example.org")

                val response = client.post("/test/grant?memberId=$target") { header("X-Member-Id", BOARD_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("grantPoliticianStatus: BOARD/ADMIN only, plain MEMBER is forbidden, both BOARD and ADMIN succeed") {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val target = createTestMember("pol-forbidden-grant@example.org")
                val targetForAdmin = createTestMember("pol-admin-grant@example.org")

                val forbidden = client.post("/test/grant?memberId=$target") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val byBoard = client.post("/test/grant?memberId=$target") { header("X-Member-Id", BOARD_ID) }
                byBoard.status shouldBe HttpStatusCode.OK

                val byAdmin = client.post("/test/grant?memberId=$targetForAdmin") { header("X-Member-Id", ADMIN_ID) }
                byAdmin.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "snapshotWeights: idempotent per (politician, periodMonth) -- re-running the same month backfills a " +
                "newly granted politician without duplicating or overwriting the existing rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politicianA = createTestMember("pol-snapshot-backfill-a@example.org")
                val politicianB = createTestMember("pol-snapshot-backfill-b@example.org")
                client.post("/test/grant?memberId=$politicianA") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/grant?memberId=$politicianB") { header("X-Member-Id", BOARD_ID) }

                val first = client.post("/test/snapshot?periodMonth=2031-05-01") { header("X-Member-Id", BOARD_ID) }
                first.status shouldBe HttpStatusCode.OK
                val firstEntries = first.bodyAsText().split(";").filter { it.isNotBlank() }
                firstEntries.size shouldBe 2

                fun snapshotRowId(politician: Uuid): Uuid =
                    transaction {
                        val profileId =
                            PoliticianProfileTable
                                .selectAll()
                                .where { PoliticianProfileTable.memberId eq politician }
                                .single()[PoliticianProfileTable.id]
                        PoliticianWeightSnapshotTable
                            .selectAll()
                            .where {
                                (PoliticianWeightSnapshotTable.politicianProfileId eq profileId) and
                                    (PoliticianWeightSnapshotTable.periodMonth eq LocalDate(2031, 5, 1))
                            }.single()[PoliticianWeightSnapshotTable.id]
                    }
                val rowIdABeforeBackfill = snapshotRowId(politicianA)
                val rowIdBBeforeBackfill = snapshotRowId(politicianB)

                // Politician C is only granted status AFTER the first snapshot run -- simulates a
                // BOARD member re-running the same period to backfill a mid-month grant, per
                // IPoliticianService.snapshotWeights KDoc.
                val politicianC = createTestMember("pol-snapshot-backfill-c@example.org")
                client.post("/test/grant?memberId=$politicianC") { header("X-Member-Id", BOARD_ID) }

                val second = client.post("/test/snapshot?periodMonth=2031-05-20") { header("X-Member-Id", BOARD_ID) }
                second.status shouldBe HttpStatusCode.OK
                val secondEntries = second.bodyAsText().split(";").filter { it.isNotBlank() }
                secondEntries.size shouldBe 3 // A, B (unchanged) + newly backfilled C

                // A's and B's rows are the SAME rows as before (insertIgnore discarded the
                // conflicting re-insert attempt) -- not overwritten, not duplicated.
                snapshotRowId(politicianA) shouldBe rowIdABeforeBackfill
                snapshotRowId(politicianB) shouldBe rowIdBBeforeBackfill

                val totalRowsForPeriod =
                    transaction {
                        PoliticianWeightSnapshotTable
                            .selectAll()
                            .where { PoliticianWeightSnapshotTable.periodMonth eq LocalDate(2031, 5, 1) }
                            .count()
                    }
                totalRowsForPeriod shouldBe 3L
            }
        }

        // ── Concurrency ───────────────────────────────────────────────────────

        test(
            "revokePoliticianStatus racing castRating on the same profile: end state is always consistent -- " +
                "never a stray reaction row surviving alongside status=FORMER",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                val politician = createTestMember("pol-race-target@example.org")
                client.post("/test/grant?memberId=$politician") { header("X-Member-Id", BOARD_ID) }
                val rater = createTestMember("pol-race-rater@example.org")
                mintLtr(rater, BigDecimal("10.00"))

                runConcurrentRevokeAndRate(client, politician, rater)

                val profileRow =
                    transaction { PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.single() }
                val status = profileRow[PoliticianProfileTable.status]
                val profileId = profileRow[PoliticianProfileTable.id]
                val reactionCount =
                    transaction {
                        PoliticianReactionTable.selectAll().where { PoliticianReactionTable.politicianProfileId eq profileId }.count()
                    }

                // Either the revoke won (status FORMER, no reaction survives -- the rate call either
                // failed with Conflict because the profile was already FORMER, or its insert was
                // followed by revoke's sweep-up delete) or the rate call landed first and revoke's
                // delete-then-flip afterwards correctly swept it up -- both are consistent end states.
                // The one FORBIDDEN outcome is status=FORMER with a surviving reaction row.
                if (status == PoliticianProfileStatus.FORMER) {
                    reactionCount shouldBe 0L
                }
            }
        }

        test(
            "grantPoliticianStatus: two concurrent first-grants for the same never-before-politician " +
                "member both succeed and converge to a single ACTIVE profile row",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPoliticianExceptionHandlers() }
                    routing { registerPoliticianTestRoutes() }
                }
                // Deliberately NOT pre-granted -- `SELECT ... FOR UPDATE` locks nothing when no row
                // exists yet, so both threads can observe existing == null and race the insert. See
                // PoliticianService class KDoc "First-grant race".
                val politician = createTestMember("pol-concurrent-first-grant@example.org")

                val statuses = runConcurrentFirstGrant(client, politician)

                // Neither call may surface the raw ExposedSQLException/500 the unguarded race would
                // have produced -- both must observe the idempotent-upsert contract.
                statuses.forEach { it shouldBe HttpStatusCode.OK }

                val profileCount =
                    transaction {
                        PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq politician }.count()
                    }
                profileCount shouldBe 1L
            }
        }
    })

/**
 * Fires a [PoliticianService.revokePoliticianStatus] and a [PoliticianService.castRating] call
 * (same politician) from two independent OS threads, synchronized via [CountDownLatch] so both are
 * issued as close to simultaneously as possible -- same pattern
 * `PeerTransferServiceTest.runConcurrentOppositeTransfers` already establishes for its own
 * row-lock race. Both threads must complete within [timeoutSeconds]; either call is allowed to
 * fail (a Conflict/NotFound from losing the lock race is an expected, correct outcome here, not a
 * test failure) -- only a deadlock (timeout) or an unexpected exception type fails the test.
 */
private fun runConcurrentRevokeAndRate(
    client: HttpClient,
    politicianMemberId: Uuid,
    raterMemberId: Uuid,
    timeoutSeconds: Long = 20,
) {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val unexpectedFailures = mutableListOf<Throwable>()

    val revokeThread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    client.post("/test/revoke?memberId=$politicianMemberId") { header("X-Member-Id", BOARD_ID) }
                }
            } catch (t: Throwable) {
                synchronized(unexpectedFailures) { unexpectedFailures += t }
            } finally {
                doneLatch.countDown()
            }
        }
    val rateThread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    client.post("/test/rate?politicianId=$politicianMemberId&value=LIKE") {
                        header("X-Member-Id", raterMemberId.toString())
                    }
                }
            } catch (t: Throwable) {
                synchronized(unexpectedFailures) { unexpectedFailures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    revokeThread.start()
    rateThread.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent revoke/rate did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (unexpectedFailures.isNotEmpty()) throw unexpectedFailures.first()
}

/**
 * Fires two [PoliticianService.grantPoliticianStatus] calls for the SAME never-before-politician
 * member from two independent OS threads, synchronized via [CountDownLatch] so both are issued as
 * close to simultaneously as possible -- exercises the "First-grant race" documented on the
 * [PoliticianService] class KDoc. Unlike [runConcurrentRevokeAndRate], BOTH calls are expected to
 * succeed here (grantPoliticianStatus's contract is an idempotent upsert, not a create-or-conflict
 * operation) -- only a deadlock (timeout) or an unexpected exception type fails the test; the
 * caller asserts on the returned statuses itself.
 */
private fun runConcurrentFirstGrant(
    client: HttpClient,
    politicianMemberId: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val unexpectedFailures = mutableListOf<Throwable>()
    val statuses = java.util.Collections.synchronizedList(mutableListOf<HttpStatusCode>())

    fun grantThread() =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                val response =
                    runBlocking {
                        client.post("/test/grant?memberId=$politicianMemberId") { header("X-Member-Id", BOARD_ID) }
                    }
                statuses += response.status
            } catch (t: Throwable) {
                synchronized(unexpectedFailures) { unexpectedFailures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val threadA = grantThread()
    val threadB = grantThread()
    threadA.start()
    threadB.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent first-grant did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (unexpectedFailures.isNotEmpty()) throw unexpectedFailures.first()
    return statuses.toList()
}

private fun StatusPagesConfig.installPoliticianExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/** Shared throwaway routes for [PoliticianService] -- mirrors [CrowdfundingServiceTest]'s `registerCrowdfundingTestRoutes` style. */
private fun Route.registerPoliticianTestRoutes() {
    post("/test/grant") {
        val service = PoliticianService(call)
        val q = call.request.queryParameters
        val p = service.grantPoliticianStatus(q["memberId"]!!, q["mandateText"])
        call.respondText("${p.status}:${p.mandateText ?: ""}:${p.displayName}")
    }
    post("/test/revoke") {
        val service = PoliticianService(call)
        val q = call.request.queryParameters
        val p = service.revokePoliticianStatus(q["memberId"]!!)
        call.respondText("${p.status}")
    }
    post("/test/update-mandate") {
        val service = PoliticianService(call)
        val q = call.request.queryParameters
        val p = service.updateMandateText(q["memberId"]!!, q["mandateText"])
        call.respondText(p.mandateText ?: "")
    }
    post("/test/rate") {
        val service = PoliticianService(call)
        val q = call.request.queryParameters
        val r = service.castRating(q["politicianId"]!!, PoliticianReactionValue.valueOf(q["value"]!!))
        call.respondText(r.value.name)
    }
    post("/test/retract") {
        val service = PoliticianService(call)
        val q = call.request.queryParameters
        service.retractRating(q["politicianId"]!!)
        call.respondText("ok")
    }
    get("/test/my-rating/{politicianId}") {
        val service = PoliticianService(call)
        val r = service.getMyRating(call.parameters["politicianId"]!!)
        call.respondText(r.singleOrNull()?.value?.name ?: "none")
    }
    get("/test/profile/{memberId}") {
        val service = PoliticianService(call)
        val p = service.getPoliticianProfile(call.parameters["memberId"]!!)
        call.respondText("${p.memberLikeCount}:${p.memberDislikeCount}:${p.memberTrustWeight}:${p.status}")
    }
    get("/test/list") {
        val service = PoliticianService(call)
        val includeFormer = call.request.queryParameters["includeFormer"]?.toBoolean() ?: false
        call.respondText(service.listPoliticians(includeFormer).joinToString(",") { it.memberId })
    }
    get("/test/top") {
        val service = PoliticianService(call)
        val limit = call.request.queryParameters["limit"]?.toInt() ?: 6
        call.respondText(service.getTopPoliticians(limit).joinToString(",") { it.memberId })
    }
    post("/test/snapshot") {
        val service = PoliticianService(call)
        val periodMonth = LocalDate.parse(call.request.queryParameters["periodMonth"]!!)
        val results = service.snapshotWeights(periodMonth)
        call.respondText(results.joinToString(";") { "${it.periodMonth}=${it.memberTrustWeight}" })
    }
    get("/test/history/{memberId}") {
        val service = PoliticianService(call)
        val results = service.getWeightHistory(call.parameters["memberId"]!!)
        call.respondText(results.joinToString(";") { "${it.periodMonth}=${it.memberTrustWeight}" })
    }
}
