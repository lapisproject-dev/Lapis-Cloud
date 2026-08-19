package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.domain.SocialCommentInput
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * Welle V1.1.4, Plan Teil 5.3 -- **das Herzstück der Security-Runde**: eine Regressions-Wall, die
 * gegen JEDE ACTIVE-only-Domäne (Governance, Crowdfunding, Systemisches Konsensieren, Wahlen,
 * Auktion, Peer-Transfer-Senderseite, Direktnachrichten, Mailinglisten, Dokumentenzugriff,
 * OIDC-Föderation) mit einem echten FRIEND-Testmitglied (mit gemintetem LTR-Guthaben) prüft, dass
 * exakt [ForbiddenException] geworfen wird -- niemals ein stiller Durchlass. Wird der neue
 * [requireLtrEligibleMembership]-Guard versehentlich an einer dieser Stellen statt an einer der
 * fünf legitimen (siehe [MembershipGuards.requireLtrEligibleMembership] KDoc) eingesetzt, geht
 * GENAU eine Zeile hier rot -- das ist laut Plan "der schwerste denkbare Fehler dieser Welle".
 *
 * **Ehrlicher Geltungsbereich (Review Runde 1, Fund 5, 2026-08-19):** "JEDE" heißt hier
 * repräsentativ, nicht buchstäblich lückenlos. Bewusst NICHT eigens abgedeckt sind
 * `ElectionService.appointElectionBoard` (`:260`) und der Sieger-Sitz-Zweig in `.tally` (`:885`)
 * sowie `ConferenceRecordingService`s/`ConferenceStreamingService`s interne Gates -- alle vier
 * prüfen ein ZIEL-Mitglied (nicht den aufrufenden Status) innerhalb eines mehrstufigen
 * Vorbedingungs-Setups (laufende Wahl in PREPARATION-Status bzw. abgeschlossene Kandidatur-Auszählung
 * bzw. eine bereits laufende Aufnahme/Übertragung), dessen Fixture-Aufbau nichts mit der
 * FRIEND-Frage selbst zu tun hätte -- exakt die in der Klassen-KDoc oben schon für andere
 * Governance-/Wahl-Methoden beschriebene Ausnahme. [MembershipGuardsTest] pinnt das zugrunde
 * liegende [requireActiveMembership]-Primitiv ohnehin erschöpfend über alle sechs Status; das
 * Restrisiko eines versehentlich geöffneten Ziel-Gates ist dadurch strukturell klein, auch ohne
 * eigenen End-to-End-Testfall hier.
 *
 * Jeder Testfall ruft die jeweilige Service-Methode über eine geworfene, throwaway HTTP-Route auf
 * (Hausstil, mirror von [SocialNetworkServiceTest]/[GovernanceServiceTest]) mit einer syntaktisch
 * gültigen, aber ABSICHTLICH NICHT EXISTIERENDEN Ziel-Id (`Uuid.random()`), damit der
 * Membership-Gate -- der in JEDER dieser Methoden als ERSTE Anweisung der Transaktion läuft, VOR
 * jedem Ressourcen-Lookup (siehe die Grep-Verifikation in der Implementierungssession) -- die
 * einzig mögliche Fehlerquelle ist: eine [ForbiddenException] beweist den Gate-Treffer, jeder
 * andere Status (v.a. [NotFoundException]) wäre ein test-eigener Aufbaufehler und nicht
 * akzeptabel.
 *
 * **Gegenprobe im selben Spec** (letzter Testfall): derselbe FRIEND KANN
 * `createPost`/`createComment`/`boostPost`/`getMyBalance`/`listMyEntries`/`hideOwnPost` -- die
 * fünf legitimen Öffnungen dieser Welle bleiben offen, während alles andere zu bleibt.
 *
 * [afterSpec] hard-deletes every row this file created and resets the auction feature flag.
 */
class FriendCapabilityBoundaryTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // Nur die Gegenprobe legt echte SocialPostTable-Zeilen an (createPost/createComment).
                // Depth-first loeschen (Stolperfalle 12, mirror SocialNetworkServiceTest afterSpec) --
                // ein root_id/parent_id-Self-FK verbietet einen einzelnen Bulk-Delete, wenn ein
                // Kommentar existiert.
                SocialPostBoostTable.deleteWhere { SocialPostBoostTable.postId inList selectSocialPostIdsOf(createdMemberIds) }
                var remaining = selectSocialPostIdsOf(createdMemberIds)
                while (remaining.isNotEmpty()) {
                    val depthById =
                        SocialPostTable
                            .select(SocialPostTable.id, SocialPostTable.depth)
                            .where { SocialPostTable.id inList remaining }
                            .associate { it[SocialPostTable.id] to it[SocialPostTable.depth] }
                    if (depthById.isEmpty()) break
                    val maxDepth = depthById.values.max()
                    val toDelete = depthById.filterValues { it == maxDepth }.keys.toList()
                    SocialPostTable.deleteWhere { SocialPostTable.id inList toDelete }
                    remaining = remaining - toDelete.toSet()
                }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionEnabled] = false
                }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.FRIEND,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Boundary-Wall Testmitglied"
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
                    it[note] = "Boundary-Wall Test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        // Ein einziges, gut ausgestattetes FRIEND-Testmitglied für die gesamte Wall -- reale
        // Ausstattung (Guthaben) ist wichtig, damit ein etwaiges Durchrutschen NICHT zufällig an
        // einer Guthaben- statt einer Status-Prüfung hängen bleibt.
        lateinit var friend: Uuid

        beforeTest {
            friend = createTestMember("friend-wall-${Uuid.random()}@example.org")
            mintLtr(friend, BigDecimal("50.00"))
        }

        fun garbageId() = Uuid.random().toString()

        test("GovernanceService.castVoteBallot: FRIEND is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val response =
                    client.post("/test/cast-vote-ballot?voteId=${garbageId()}&optionId=${garbageId()}&stake=0.01") {
                        header("X-Member-Id", friend.toString())
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("GovernanceService.addCommitteeMember: seating a FRIEND (target status, not caller) is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                // Caller is BOARD (privileged) -- the gate under test is on the SEATED member's
                // status (input.memberId = friend), not the caller's, see MembershipGuards.kt:221's
                // own KDoc "checked on the SEATED member".
                val response =
                    client.post("/test/add-committee-member?committeeId=${garbageId()}&memberId=$friend") {
                        header("X-Member-Id", BOARD_ID)
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("CrowdfundingService.submitProject: FRIEND is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val response =
                    client.post("/test/submit-project?title=X&description=Y&weight=1.00") {
                        header("X-Member-Id", friend.toString())
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("CrowdfundingService.castReaction: FRIEND is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val response =
                    client.post("/test/cast-reaction?projectId=${garbageId()}&value=LIKE") {
                        header("X-Member-Id", friend.toString())
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("CrowdfundingService.retractReaction: FRIEND is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val response =
                    client.post("/test/retract-reaction?projectId=${garbageId()}") { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("SystemicConsensusService.castResistanceBallot: FRIEND is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val response =
                    client.post("/test/cast-resistance-ballot?id=${garbageId()}") { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("ElectionService.castElectionBallot: FRIEND is Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val response =
                    client.post("/test/cast-election-ballot?electionId=${garbageId()}") { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("AuctionService: createListing/placeBid/buyNow/settleAuction all Forbidden for FRIEND (auctionEnabled=true)") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[auctionEnabled] = true
                    }
                }

                client
                    .post("/test/create-listing?title=X&description=Y&startingBid=1.00&durationHours=24") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/place-bid?auctionId=${garbageId()}&maxBid=1.00") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/buy-now?auctionId=${garbageId()}") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/settle-auction?id=${garbageId()}") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("PeerTransferService.transferLtr: FRIEND sender is Forbidden (senderseite bleibt ACTIVE-only)") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                val recipient = createTestMember("boundary-pt-recipient-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
                val response =
                    client.post("/test/transfer-ltr?recipientId=$recipient&amount=1.00") { header("X-Member-Id", friend.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("DirectMessageService: all five methods Forbidden for FRIEND") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                client
                    .post("/test/send-dm?recipientId=${garbageId()}&body=hi") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/list-inbox") { header("X-Member-Id", friend.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/list-conversation?otherMemberId=${garbageId()}") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/mark-read?messageId=${garbageId()}") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/unread-count") { header("X-Member-Id", friend.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("MailingService: listMailingLists/subscribe/unsubscribe all Forbidden for FRIEND") {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing { registerBoundaryTestRoutes() }
                }
                client
                    .get("/test/list-mailing-lists") { header("X-Member-Id", friend.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/subscribe?mailingListId=${garbageId()}") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/unsubscribe?mailingListId=${garbageId()}") {
                        header("X-Member-Id", friend.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("RequestContext.canAccessDocumentAtLevel(PUBLIC_MEMBERS): false for FRIEND, false for GUEST, true for ACTIVE") {
            val friendMember = CurrentMember(memberId = Uuid.random(), role = AccountRole.MEMBER, status = MemberStatus.FRIEND)
            val guestMember = CurrentMember(memberId = Uuid.random(), role = AccountRole.MEMBER, status = MemberStatus.GUEST)
            val activeMember = CurrentMember(memberId = Uuid.random(), role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
            friendMember.canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe false
            guestMember.canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe false
            activeMember.canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe true
        }

        test(
            "OidcRoutes.issueTokens: source-scan regression guard -- the ORGANIZATION_MEMBER ID-token gate must still exist verbatim " +
                "(V0.11.0 security fix; not touched by V1.1.4, no functional HTTP test here -- see MembershipGuards.kt § 2.4 Regressions-Wall)",
        ) {
            val mainSourceDir =
                File("src/main/kotlin").let { relative -> if (relative.exists()) relative else File("lapis-server/src/main/kotlin") }
            val oidcRoutesFile = File(mainSourceDir, "network/lapis/cloud/server/routes/OidcRoutes.kt")
            require(oidcRoutesFile.exists()) { "OidcRoutes.kt not found at ${oidcRoutesFile.absolutePath}" }
            val content = oidcRoutesFile.readText()
            content.contains("memberRow[MemberTable.status] !in MemberStatusSets.ORGANIZATION_MEMBER") shouldBe true
        }

        // ── Gegenprobe: derselbe FRIEND KANN die fünf legitimen V1.1.4-Öffnungen ────────────

        test(
            "Gegenprobe: the SAME FRIEND CAN createPost/createComment/boostPost/getMyBalance/listMyEntries/hideOwnPost",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installBoundaryExceptionHandlers() }
                    routing {
                        registerBoundaryTestRoutes()
                        registerSocialAndLedgerGegenprobeRoutes()
                    }
                }
                val balanceBefore = client.get("/test/my-balance") { header("X-Member-Id", friend.toString()) }
                balanceBefore.status shouldBe HttpStatusCode.OK
                BigDecimal(balanceBefore.bodyAsText()).compareTo(BigDecimal("50.00")) shouldBe 0

                val entriesBefore = client.get("/test/my-entries") { header("X-Member-Id", friend.toString()) }
                entriesBefore.status shouldBe HttpStatusCode.OK

                val postResponse =
                    client.post("/test/create-post?content=GegenprobePost&weight=1.00&visibility=MEMBERS_AND_EXTERNAL") {
                        header("X-Member-Id", friend.toString())
                    }
                postResponse.status shouldBe HttpStatusCode.OK
                val postId = postResponse.bodyAsText().substringBefore(":")

                val commentResponse =
                    client.post("/test/create-comment?parentId=$postId&content=GegenprobeKommentar&weight=1.00") {
                        header("X-Member-Id", friend.toString())
                    }
                commentResponse.status shouldBe HttpStatusCode.OK

                val boostResponse =
                    client.post("/test/boost/$postId?amount=1.00") { header("X-Member-Id", friend.toString()) }
                boostResponse.status shouldBe HttpStatusCode.OK

                val hideResponse = client.post("/test/hide-post/$postId") { header("X-Member-Id", friend.toString()) }
                hideResponse.status shouldBe HttpStatusCode.OK
                // Cleanup: afterSpec deletes every social_post authored by a member in
                // createdMemberIds (depth-first) and every ledger entry for those members, so no
                // extra teardown is needed here.
            }
        }
    })

private fun StatusPagesConfig.installBoundaryExceptionHandlers() {
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

/** All social_post ids ever authored by one of [memberIds] -- used only for afterSpec teardown. */
private fun selectSocialPostIdsOf(memberIds: List<Uuid>): List<Uuid> {
    if (memberIds.isEmpty()) return emptyList()
    return SocialPostTable.select(SocialPostTable.id).where { SocialPostTable.authorMemberId inList memberIds }.map {
        it[SocialPostTable.id]
    }
}

/** Throwaway routes -- ONE per Aufrufstelle aus Plan § 2.4/5.3, no wire format to reverse-engineer. */
private fun Route.registerBoundaryTestRoutes() {
    post("/test/cast-vote-ballot") {
        val service = GovernanceService(call = call)
        val q = call.request.queryParameters
        val r =
            service.castVoteBallot(
                VoteBallotInput(voteId = q["voteId"]!!, optionId = q["optionId"]!!, stakeLtr = BigDecimal(q["stake"] ?: "0.01")),
            )
        call.respondText(r.voteId)
    }
    post("/test/add-committee-member") {
        val service = GovernanceService(call = call)
        val q = call.request.queryParameters
        val r =
            service.addCommitteeMember(
                committeeId = q["committeeId"]!!,
                input = CommitteeMembershipInput(memberId = q["memberId"]!!, role = CommitteeRole.MEMBER, since = LocalDate(2026, 1, 1)),
            )
        call.respondText(r.id)
    }
    post("/test/submit-project") {
        val service = CrowdfundingService(call = call)
        val q = call.request.queryParameters
        val r =
            service.submitProject(
                CrowdfundingProjectInput(
                    title = q["title"] ?: "Title",
                    description = q["description"] ?: "Description",
                    initialWeightLtr = BigDecimal(q["weight"] ?: "1.00"),
                ),
            )
        call.respondText(r.id)
    }
    post("/test/cast-reaction") {
        val service = CrowdfundingService(call = call)
        val q = call.request.queryParameters
        val r = service.castReaction(projectId = q["projectId"]!!, value = CrowdfundingReactionValue.valueOf(q["value"] ?: "LIKE"))
        call.respondText(r.id)
    }
    post("/test/retract-reaction") {
        val service = CrowdfundingService(call = call)
        val q = call.request.queryParameters
        service.retractReaction(projectId = q["projectId"]!!)
        call.respondText("ok")
    }
    post("/test/cast-resistance-ballot") {
        val service = SystemicConsensusService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val q = call.request.queryParameters
        val r = service.castResistanceBallot(SystemicConsensusBallotInput(systemicConsensusId = q["id"]!!, resistances = emptyMap()))
        call.respondText(r.id)
    }
    post("/test/cast-election-ballot") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val q = call.request.queryParameters
        val r = service.castElectionBallot(ElectionBallotInput(electionId = q["electionId"]!!))
        call.respondText(r.id)
    }
    post("/test/create-listing") {
        val service = AuctionService(call = call)
        val q = call.request.queryParameters
        val r =
            service.createListing(
                CreateAuctionListingInput(
                    title = q["title"]!!,
                    description = q["description"]!!,
                    startingBidLtr = BigDecimal(q["startingBid"] ?: "1.00"),
                    durationHours = q["durationHours"]?.toInt() ?: 24,
                ),
            )
        call.respondText(r.id)
    }
    post("/test/place-bid") {
        val service = AuctionService(call = call)
        val q = call.request.queryParameters
        val r = service.placeBid(auctionId = q["auctionId"]!!, maxBidLtr = BigDecimal(q["maxBid"] ?: "1.00"))
        call.respondText(r.auctionId)
    }
    post("/test/buy-now") {
        val service = AuctionService(call = call)
        val q = call.request.queryParameters
        val r = service.buyNow(q["auctionId"]!!)
        call.respondText(r.id)
    }
    post("/test/settle-auction") {
        val service = AuctionService(call = call)
        val q = call.request.queryParameters
        val r = service.settleAuction(q["id"]!!)
        call.respondText(r.id)
    }
    post("/test/transfer-ltr") {
        val service = PeerTransferService(call = call)
        val q = call.request.queryParameters
        val r =
            service.transferLtr(
                PeerTransferInput(
                    recipientMemberId = q["recipientId"]!!,
                    amountLtr = BigDecimal(q["amount"] ?: "1.00"),
                    characterization = PeerTransferCharacterization.SONSTIGES,
                    purpose = null,
                ),
            )
        call.respondText(r.transferId)
    }
    post("/test/send-dm") {
        val service = DirectMessageService(call = call)
        val q = call.request.queryParameters
        val r = service.sendDirectMessage(recipientId = q["recipientId"]!!, body = q["body"] ?: "hi")
        call.respondText(r.id)
    }
    get("/test/list-inbox") {
        val service = DirectMessageService(call = call)
        call.respondText(service.listInbox().size.toString())
    }
    get("/test/list-conversation") {
        val service = DirectMessageService(call = call)
        val q = call.request.queryParameters
        call.respondText(service.listConversation(otherMemberId = q["otherMemberId"]!!).size.toString())
    }
    post("/test/mark-read") {
        val service = DirectMessageService(call = call)
        val q = call.request.queryParameters
        service.markRead(messageId = q["messageId"]!!)
        call.respondText("ok")
    }
    get("/test/unread-count") {
        val service = DirectMessageService(call = call)
        call.respondText(service.unreadCount().toString())
    }
    get("/test/list-mailing-lists") {
        val service = MailingService(call = call)
        call.respondText(service.listMailingLists().size.toString())
    }
    post("/test/subscribe") {
        val service = MailingService(call = call)
        val q = call.request.queryParameters
        service.subscribe(mailingListId = q["mailingListId"]!!)
        call.respondText("ok")
    }
    post("/test/unsubscribe") {
        val service = MailingService(call = call)
        val q = call.request.queryParameters
        service.unsubscribe(mailingListId = q["mailingListId"]!!)
        call.respondText("ok")
    }
}

/** Gegenprobe-only routes -- the five legitimate V1.1.4 openings, minimal shape (Hausstil). */
private fun Route.registerSocialAndLedgerGegenprobeRoutes() {
    fun socialService(callCtx: io.ktor.server.application.ApplicationCall) =
        SocialNetworkService(
            call = callCtx,
            createRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
            readRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
            boostRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
        )
    post("/test/create-post") {
        val service = socialService(call)
        val q = call.request.queryParameters
        val p =
            service.createPost(
                SocialPostInput(
                    content = q["content"] ?: "Testinhalt",
                    visibility = SocialPostVisibility.valueOf(q["visibility"] ?: "MEMBERS_AND_EXTERNAL"),
                    initialWeightLtr = BigDecimal(q["weight"] ?: "1.00"),
                ),
            )
        call.respondText("${p.id}:${p.state}")
    }
    post("/test/create-comment") {
        val service = socialService(call)
        val q = call.request.queryParameters
        val p =
            service.createComment(
                SocialCommentInput(
                    parentId = q["parentId"]!!,
                    content = q["content"] ?: "Testkommentar",
                    initialWeightLtr = BigDecimal(q["weight"] ?: "1.00"),
                ),
            )
        call.respondText(p.id)
    }
    post("/test/boost/{id}") {
        val service = socialService(call)
        val amount = BigDecimal(call.request.queryParameters["amount"] ?: "1.00")
        val p = service.boostPost(postId = call.parameters["id"]!!, amountLtr = amount)
        call.respondText(p.id)
    }
    post("/test/hide-post/{id}") {
        val service = socialService(call)
        val p = service.hideOwnPost(call.parameters["id"]!!)
        call.respondText(p.state.name)
    }
    get("/test/my-balance") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.getMyBalance().freeBalanceLtr.toString())
    }
    get("/test/my-entries") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.listMyEntries().joinToString(",") { it.id })
    }
}
