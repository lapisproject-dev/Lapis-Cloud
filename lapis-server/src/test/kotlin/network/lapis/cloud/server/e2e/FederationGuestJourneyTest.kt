package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PoliticianWeightSnapshotTable
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.CrowdfundingService
import network.lapis.cloud.server.rpc.DocumentService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PoliticianService
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.PoliticianRaterType
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Scenario 3 of the V1.0 end-to-end integration test wave -- see [E2eSupport] KDoc for the shared
 * "real, fully-wired `module()` + real login/session + throwaway RPC test routes on top" idiom
 * every scenario in this package uses.
 *
 * Crosses FOUR waves in one continuous, real-HTTP-driven story, all through the SAME unbroken
 * guest session: V0.8.2 (a federated OIDC guest identity is minted) -> V0.6.4/v0.9.0 (the guest
 * casts a real Politician rating) -> V0.6 (the SAME session is refused an LTR-economy write) ->
 * V0.1 (the SAME session is excluded from a `PUBLIC_MEMBERS` document). The value-add over the
 * pre-existing, per-domain regression tests ([OidcGuestSessionTest], [network.lapis.cloud.server.rpc.PoliticianServiceTest]'s
 * guest-rating cases, [network.lapis.cloud.server.routes.DocumentRoutesGuestAccessTest]) is that
 * every one of these outcomes -- rating success, LTR refusal, document exclusion -- is proven for
 * the exact same guest identity/session in one continuous story, not four isolated fixtures.
 *
 * **No outbound internet egress in this sandbox** (the exact constraint
 * [network.lapis.cloud.server.routes.OidcRoutesTest] itself documents for the same reason) --
 * this scenario cannot drive the real `/oidc/rp/callback` browser-redirect flow against a real
 * home server. It mints the guest via the REAL production function
 * [OidcGuestMemberStore.resolveOrCreateGuestMember] directly (not a hand-rolled `MemberTable`
 * insert) plus [SessionStore.createSession] (the same two calls
 * [network.lapis.cloud.server.routes.OidcRoutes]' own RP-callback handler makes once ID-Token
 * verification succeeds) -- from that point on, every single call in this test is 100% real HTTP
 * through [network.lapis.cloud.server.module]'s full middleware stack (`StatusPages`,
 * session-cookie resolution, `CallLogging`), driven by a real `lapis_session` cookie, exactly like
 * every other actor in every other scenario in this package.
 *
 * **`politicianRankingEnabled` is flipped via a direct [OrganizationSettingsTable] update, not
 * through [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.updateOrganizationSettings]**
 * -- that RPC replaces the ENTIRE single settings row (see its own KDoc "no partial update"), and
 * this codebase's ~1100 tests share one row in one H2 database per JVM; round-tripping through it
 * risks clobbering `bankIban`/`taxExemptionAuthority`/etc. some OTHER already-run Spec left in
 * place. Same "flip just the one boolean column directly" idiom
 * [network.lapis.cloud.server.rpc.PoliticianServiceTest]'s own `beforeTest`/cleanup already
 * establishes for this exact shared-row hazard -- this is test-fixture setup, not part of the
 * story under test (which is the guest's session, not who is allowed to toggle organization
 * settings).
 *
 * **Deviation from the wave plan's phrasing**: the plan text says "BOARD grants Politiker-Status +
 * enables `politicianRankingEnabled`" as if one actor does both. [PoliticianService
 * .grantPoliticianStatus] genuinely is BOARD-or-ADMIN (`POLITICIAN_BOARD_ROLES`), but
 * `updateOrganizationSettings` is hard ADMIN-only (see that service's KDoc) -- there is no
 * production path for a BOARD member to flip this flag at all. This test therefore grants
 * Politiker-Status as BOARD (`X-Member-Id BOARD_ID`, matching the plan) and enables the ranking
 * flag as pure fixture setup (see previous paragraph) rather than asserting a BOARD-driven RPC
 * call that does not exist in production.
 *
 * **HIGH-VALUE ASSERTION (per the wave plan): does a guest's Like actually move
 * `guestTrustWeight`, and hence `combinedTrustWeight`?** Verified in two stages: (1) immediately
 * after the guest's LIKE, with no member rater yet, `guestTrustWeight == 1.00` and
 * `combinedTrustWeight == guestTrustWeight` (since `memberTrustWeight == 0.00`); (2) after a
 * SECOND, real-login AKTIV member also rates the same politician with real LTR behind them,
 * `combinedTrustWeight` is asserted to equal the literal sum of the (by-then nonzero)
 * `memberTrustWeight` and the (unchanged) `guestTrustWeight` -- the exact tuple
 * [network.lapis.cloud.server.rpc.PoliticianTrustWeightCalculator] KDoc documents. Both stages
 * came back correct against the current production code -- see this test's own class KDoc "no
 * bug found" note below for why this is a confirming assertion, not a fix.
 *
 * **No bug found here.** [network.lapis.cloud.server.rpc.PoliticianTrustWeightCalculator
 * .computeGuestTrustWeights] and [network.lapis.cloud.server.security.canAccessDocumentAtLevel]
 * were both already correct and already had isolated coverage
 * ([network.lapis.cloud.server.rpc.PoliticianServiceTest]'s guest-isolation-arithmetic cases,
 * [network.lapis.cloud.server.routes.DocumentRoutesGuestAccessTest]) before this wave -- this
 * scenario's contribution is combining them, end to end, behind ONE real OIDC-minted guest
 * session, not discovering a new defect in either.
 */
class FederationGuestJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdPoliticianProfileIds = mutableListOf<Uuid>()
        val createdFolderIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            // See class KDoc "politicianRankingEnabled is flipped via a direct ... update".
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = true
                }
            }
        }

        afterSpec {
            transaction {
                if (createdDocumentIds.isNotEmpty()) {
                    DocumentTable.deleteWhere { DocumentTable.id inList createdDocumentIds }
                }
                if (createdFolderIds.isNotEmpty()) {
                    DocumentFolderTable.deleteWhere { DocumentFolderTable.id inList createdFolderIds }
                }
                if (createdPoliticianProfileIds.isNotEmpty()) {
                    PoliticianWeightSnapshotTable.deleteWhere {
                        PoliticianWeightSnapshotTable.politicianProfileId inList createdPoliticianProfileIds
                    }
                    PoliticianReactionTable.deleteWhere {
                        PoliticianReactionTable.politicianProfileId inList createdPoliticianProfileIds
                    }
                    PoliticianProfileTable.deleteWhere { PoliticianProfileTable.id inList createdPoliticianProfileIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList createdMemberIds }
                }
                hardDeleteGovernanceAndMembershipFixtures(emptyList(), createdMemberIds)
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[politicianRankingEnabled] = false
                }
            }
        }

        test(
            "a real OIDC-minted guest session rates a politician (200, raterType=GAST, moves guestTrustWeight) -> " +
                "the SAME session is refused an LTR-economy write (403) -> the SAME session's listDocuments " +
                "excludes a PUBLIC_MEMBERS document -> a second real member's rating proves combinedTrustWeight " +
                "is the literal member+guest sum",
        ) {
            testApplication {
                application {
                    module()
                    routing {
                        post("/e2e3/grant-politician/{memberId}") {
                            val p =
                                PoliticianService(call).grantPoliticianStatus(
                                    call.parameters["memberId"]!!,
                                    "E2E Scenario 3 Mandat",
                                )
                            call.respondText("${p.id}:${p.status}")
                        }
                        post("/e2e3/mint-ltr/{memberId}") {
                            val dto =
                                LtrLedgerService(call).mintLtr(
                                    MintLtrInput(
                                        memberId = call.parameters["memberId"]!!,
                                        amountLtr = BigDecimal(call.request.queryParameters["amount"]!!),
                                        note = "E2E Scenario 3 Startguthaben",
                                    ),
                                )
                            call.respondText(dto.id)
                        }
                        post("/e2e3/rate/{politicianMemberId}/{value}") {
                            val r =
                                PoliticianService(call).castRating(
                                    call.parameters["politicianMemberId"]!!,
                                    PoliticianReactionValue.valueOf(call.parameters["value"]!!),
                                )
                            call.respondText("${r.id}:${r.raterType}")
                        }
                        get("/e2e3/my-rating/{politicianMemberId}") {
                            val list = PoliticianService(call).getMyRating(call.parameters["politicianMemberId"]!!)
                            call.respondText(list.joinToString(",") { "${it.value}:${it.raterType}" })
                        }
                        get("/e2e3/politician-profile/{memberId}") {
                            val p = PoliticianService(call).getPoliticianProfile(call.parameters["memberId"]!!)
                            call.respondText(
                                "${p.memberLikeCount}:${p.memberDislikeCount}:${p.memberTrustWeight}:" +
                                    "${p.guestLikeCount}:${p.guestDislikeCount}:${p.guestTrustWeight}:${p.combinedTrustWeight}",
                            )
                        }
                        post("/e2e3/submit-project") {
                            val p =
                                CrowdfundingService(call).submitProject(
                                    CrowdfundingProjectInput(
                                        title = GUEST_SUBMIT_PROJECT_TITLE,
                                        description = "Must never be reachable by a GAST-status caller",
                                        initialWeightLtr = BigDecimal("1.00"),
                                    ),
                                )
                            call.respondText("${p.id}:${p.status}")
                        }
                        post("/e2e3/create-folder") {
                            val f = DocumentService(call).createFolder("E2E Scenario 3 Ordner", null)
                            call.respondText(f.id)
                        }
                        post("/e2e3/create-document/{folderId}") {
                            val d =
                                DocumentService(call).createDocument(
                                    call.parameters["folderId"]!!,
                                    "E2E Scenario 3 Vorstandsprotokoll (PUBLIC_MEMBERS)",
                                    DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(d.id)
                        }
                        get("/e2e3/list-documents") {
                            val list = DocumentService(call).listDocuments(null)
                            call.respondText(list.joinToString(",") { it.id })
                        }
                    }
                }

                // ── Step 1: mint a real federated guest identity via the REAL production function --
                // ── see class KDoc "No outbound internet egress" for why this, and not a real ─────────
                // ── browser-redirect RP-callback round trip, is the right substitution here. ──────────
                val issuer = "https://home-${Uuid.random()}.example"
                val subject = "e2e3-guest-subject-${Uuid.random()}"
                val claims =
                    OidcGuestClaims(
                        issuer = issuer,
                        subject = subject,
                        name = "E2E Scenario 3 Foederierter Gast",
                        picture = null,
                        preferredUsername = "e2e3-guest",
                        homeserverUrl = issuer,
                        membershipStatus = "AKTIV",
                    )
                val guestId = OidcGuestMemberStore.resolveOrCreateGuestMember(claims, "openid profile_basic politician_rating")
                createdMemberIds += guestId
                val guestToken = SessionStore.createSession(guestId).rawToken
                // Confirms the session this test drives every subsequent call through really does
                // resolve as a guest -- the same [network.lapis.cloud.server.security.CurrentMember
                // .isGuest] signal every gate below is ultimately built on.
                val resolvedGuest = SessionStore.resolve(guestToken)
                requireNotNull(resolvedGuest) { "freshly created guest session did not resolve" }
                resolvedGuest.isGuest shouldBe true

                // ── Step 2: BOARD grants Politiker-Status to a fresh target member; ranking is ──────
                // ── enabled as fixture setup (see class KDoc "politicianRankingEnabled is ──────────────
                // ── flipped ..."). ──────────────────────────────────────────────────────────────────
                val politicianEmail = "e2e3-politician-${Uuid.random()}@example.org"
                val politicianMemberId = createRealMember("E2E Scenario 3 Politician", politicianEmail)
                createdMemberIds += politicianMemberId
                val grantResponse =
                    client.post("/e2e3/grant-politician/$politicianMemberId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                val politicianProfileId = grantResponse.substringBefore(":")
                createdPoliticianProfileIds += Uuid.parse(politicianProfileId)
                grantResponse.substringAfter(":") shouldBe "ACTIVE"

                // ── Step 3: cross-domain seam #1 -- the SAME guest session casts a real rating via ──
                // ── the real RPC route. 200 OK, and raterType is frozen as GAST at cast time. ───────
                val rateResponse = client.post("/e2e3/rate/$politicianMemberId/LIKE") { withSession(guestToken) }
                rateResponse.status shouldBe HttpStatusCode.OK
                rateResponse.bodyAsText().substringAfter(":") shouldBe PoliticianRaterType.GAST.name
                client.get("/e2e3/my-rating/$politicianMemberId") { withSession(guestToken) }.bodyAsText() shouldBe
                    "LIKE:${PoliticianRaterType.GAST}"

                // HIGH-VALUE ASSERTION, stage 1 (see class KDoc): with no member rater yet, the
                // guest's single LIKE alone must already have moved guestTrustWeight to exactly
                // 1.00, and combinedTrustWeight must equal it exactly (memberTrustWeight == 0.00).
                val profileAfterGuestOnly =
                    client
                        .get("/e2e3/politician-profile/$politicianMemberId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split(":")
                profileAfterGuestOnly[0] shouldBe "0" // memberLikeCount
                profileAfterGuestOnly[1] shouldBe "0" // memberDislikeCount
                BigDecimal(profileAfterGuestOnly[2]).compareTo(BigDecimal("0.00")) shouldBe 0 // memberTrustWeight
                profileAfterGuestOnly[3] shouldBe "1" // guestLikeCount
                profileAfterGuestOnly[4] shouldBe "0" // guestDislikeCount
                BigDecimal(profileAfterGuestOnly[5]).compareTo(BigDecimal("1.00")) shouldBe 0 // guestTrustWeight
                BigDecimal(profileAfterGuestOnly[6]).compareTo(BigDecimal("1.00")) shouldBe 0 // combinedTrustWeight

                // ── Step 4: cross-domain seam #2 -- the SAME guest session attempts an LTR-economy ──
                // ── write. requireActiveMembership excludes GAST -> 403, and no project row must ────
                // ── have been created (a refusal, not a silently-half-applied write). ────────────────
                val submitAttempt = client.post("/e2e3/submit-project") { withSession(guestToken) }
                submitAttempt.status shouldBe HttpStatusCode.Forbidden
                val guestProjectCount =
                    transaction {
                        CrowdfundingProjectTable
                            .selectAll()
                            .where { CrowdfundingProjectTable.title eq GUEST_SUBMIT_PROJECT_TITLE }
                            .count()
                    }
                guestProjectCount shouldBe 0L

                // ── Step 5: cross-domain seam #3 -- BOARD creates a PUBLIC_MEMBERS document; the ───
                // ── SAME guest session's listDocuments() must exclude it (closes the gap from ───────
                // ── commit c25c65b), while a real AKTIV member's listDocuments() includes it -- the ──
                // ── differential that actually proves this is access-level filtering, not an empty ──
                // ── result for everyone. ───────────────────────────────────────────────────────────
                val folderId = client.post("/e2e3/create-folder") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdFolderIds += Uuid.parse(folderId)
                val documentId = client.post("/e2e3/create-document/$folderId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdDocumentIds += Uuid.parse(documentId)

                val guestDocumentIds = client.get("/e2e3/list-documents") { withSession(guestToken) }.bodyAsText().split(",")
                (documentId in guestDocumentIds) shouldBe false

                val boardDocumentIds = client.get("/e2e3/list-documents") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split(",")
                (documentId in boardDocumentIds) shouldBe true

                // ── Step 6: HIGH-VALUE ASSERTION, stage 2 -- a SECOND, freshly created, real-login ──
                // ── AKTIV member with real LTR behind them also rates the SAME politician. ───────────
                // ── combinedTrustWeight must equal the literal sum of the now-nonzero ────────────────
                // ── memberTrustWeight and the UNCHANGED guestTrustWeight from step 3 -- proving the ──
                // ── two pools are computed independently and only ever combined by addition. ─────────
                val memberRaterEmail = "e2e3-member-rater-${Uuid.random()}@example.org"
                val memberRaterId = createRealMember("E2E Scenario 3 Member Rater", memberRaterEmail, password = E2E_STRONG_PASSWORD)
                createdMemberIds += memberRaterId
                val memberRaterToken = client.realLogin(memberRaterEmail, E2E_STRONG_PASSWORD)
                client
                    .post("/e2e3/mint-ltr/$memberRaterId?amount=30.00") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.OK
                val memberRateResponse = client.post("/e2e3/rate/$politicianMemberId/LIKE") { withSession(memberRaterToken) }
                memberRateResponse.status shouldBe HttpStatusCode.OK
                memberRateResponse.bodyAsText().substringAfter(":") shouldBe PoliticianRaterType.MEMBER.name

                val profileAfterBoth =
                    client
                        .get("/e2e3/politician-profile/$politicianMemberId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split(":")
                profileAfterBoth[0] shouldBe "1" // memberLikeCount
                profileAfterBoth[1] shouldBe "0" // memberDislikeCount
                val memberTrustWeight = BigDecimal(profileAfterBoth[2])
                // Sole member rater in this politician's active pool -> the whole pool (their free
                // balance) apportions to this one Korb entry.
                memberTrustWeight.compareTo(BigDecimal("30.00")) shouldBe 0
                profileAfterBoth[3] shouldBe "1" // guestLikeCount -- unchanged from step 3
                profileAfterBoth[4] shouldBe "0" // guestDislikeCount -- unchanged from step 3
                val guestTrustWeight = BigDecimal(profileAfterBoth[5])
                guestTrustWeight.compareTo(BigDecimal("1.00")) shouldBe 0 // unchanged from step 3
                val combinedTrustWeight = BigDecimal(profileAfterBoth[6])
                combinedTrustWeight.compareTo(memberTrustWeight + guestTrustWeight) shouldBe 0
                combinedTrustWeight.compareTo(BigDecimal("31.00")) shouldBe 0
            }
        }
    })

/**
 * Fixed (not randomized): step 4's "the refusal must have been a refusal, not a silently-half-
 * applied write" check queries for a row with exactly this title after the guest's forbidden
 * `submitProject` call -- since that call is refused before any insert, no production code path
 * can ever create a [network.lapis.cloud.server.db.generated.CrowdfundingProjectTable] row with
 * this literal title, so a fixed constant (not `Uuid.random()`-suffixed, unlike this file's member
 * emails) is safe and keeps the assertion simple.
 */
private const val GUEST_SUBMIT_PROJECT_TITLE = "E2E-Scenario-3-Guest-LTR-Write-Attempt"
