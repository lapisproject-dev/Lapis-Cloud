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
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArbitrationTransferInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [PeerTransferService] end to end, mirroring [CrowdfundingServiceTest]'s house style
 * (throwaway routes calling the service class directly, no wire format to reverse-engineer).
 * DevSeedData's BOARD/TREASURER accounts are used only as the *actors* performing the privileged
 * [PeerTransferService.executeArbitrationTransfer] -- every member that SENDS/RECEIVES a transfer
 * is a fresh test member, same discipline [CrowdfundingServiceTest]/[GovernanceServiceTest]
 * document for their own fixtures. [afterSpec] hard-deletes every row this file created.
 */
class PeerTransferServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpPeerTransferTestData(createdMemberIds) }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Peer-Transfer Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
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

        /** Direct-DB MINT seed, mirroring [CrowdfundingServiceTest]'s own `mintLtr` idiom. */
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

        fun freeBalanceOf(memberId: Uuid): BigDecimal =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { LtrLedgerEntryTable.memberId eq memberId }
                    .fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[LtrLedgerEntryTable.amountLtr] }
            }

        test(
            "transferLtr: happy path debits sender, credits recipient, writes exactly one PEER_TRANSFER_OUT/IN pair, visible in both members' ledger history",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val sender = createTestMember("pt-happy-sender@example.org")
                val recipient = createTestMember("pt-happy-recipient@example.org")
                mintLtr(sender, BigDecimal("10.00"))

                val response =
                    client
                        .post("/test/transfer?recipientId=$recipient&amount=3.50&characterization=PRIVATVERKAUF&purpose=Testkauf") {
                            header("X-Member-Id", sender.toString())
                        }
                response.status shouldBe HttpStatusCode.OK
                val (transferId, respSender, respRecipient, respAmount, initiatedBy) = response.bodyAsText().split(":")
                respSender shouldBe sender.toString()
                respRecipient shouldBe recipient.toString()
                BigDecimal(respAmount).compareTo(BigDecimal("3.50")) shouldBe 0
                initiatedBy shouldBe "null"

                freeBalanceOf(sender).compareTo(BigDecimal("6.50")) shouldBe 0
                freeBalanceOf(recipient).compareTo(BigDecimal("3.50")) shouldBe 0

                val transferUuid = Uuid.parse(transferId)
                val peerTransferRow = transaction { PeerTransferTable.selectAll().where { PeerTransferTable.id eq transferUuid }.single() }
                peerTransferRow[PeerTransferTable.senderMemberId] shouldBe sender
                peerTransferRow[PeerTransferTable.recipientMemberId] shouldBe recipient
                peerTransferRow[PeerTransferTable.initiatedBy] shouldBe null

                val outEntry =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq sender) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.PEER_TRANSFER_OUT)
                            }.single()
                    }
                outEntry[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("-3.50")) shouldBe 0
                outEntry[LtrLedgerEntryTable.referenceId] shouldBe transferUuid

                val inEntry =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq recipient) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.PEER_TRANSFER_IN)
                            }.single()
                    }
                inEntry[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("3.50")) shouldBe 0
                inEntry[LtrLedgerEntryTable.referenceId] shouldBe transferUuid

                // No new read path -- the existing ILtrLedgerService history already surfaces both entries.
                val senderEntries = client.get("/test/my-entries") { header("X-Member-Id", sender.toString()) }.bodyAsText()
                (senderEntries.contains("PEER_TRANSFER_OUT")) shouldBe true
                val recipientEntries = client.get("/test/my-entries") { header("X-Member-Id", recipient.toString()) }.bodyAsText()
                (recipientEntries.contains("PEER_TRANSFER_IN")) shouldBe true
            }
        }

        test("transferLtr: insufficient balance is rejected and rolls back fully; exact-balance transfer succeeds down to zero") {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val sender = createTestMember("pt-insufficient-sender@example.org")
                val recipient = createTestMember("pt-insufficient-recipient@example.org")
                mintLtr(sender, BigDecimal("5.00"))

                val tooMuch =
                    client.post("/test/transfer?recipientId=$recipient&amount=5.01&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                tooMuch.status shouldBe HttpStatusCode.Conflict
                freeBalanceOf(sender).compareTo(BigDecimal("5.00")) shouldBe 0
                transaction { PeerTransferTable.selectAll().where { PeerTransferTable.senderMemberId eq sender }.count() } shouldBe 0L

                val exact =
                    client.post("/test/transfer?recipientId=$recipient&amount=5.00&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                exact.status shouldBe HttpStatusCode.OK
                freeBalanceOf(sender).compareTo(BigDecimal("0.00")) shouldBe 0
                freeBalanceOf(recipient).compareTo(BigDecimal("5.00")) shouldBe 0
            }
        }

        test(
            "transferLtr: amountLtr validation -- zero rejected, >2 decimals rejected, exactly the 0.01 minimum with sufficient balance succeeds",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val sender = createTestMember("pt-minimum-sender@example.org")
                val recipient = createTestMember("pt-minimum-recipient@example.org")
                mintLtr(sender, BigDecimal("1.00"))

                val zero =
                    client.post("/test/transfer?recipientId=$recipient&amount=0.00&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                zero.status shouldBe HttpStatusCode.Conflict

                val tooManyDecimals =
                    client.post("/test/transfer?recipientId=$recipient&amount=0.005&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                tooManyDecimals.status shouldBe HttpStatusCode.Conflict

                val minimum =
                    client.post("/test/transfer?recipientId=$recipient&amount=0.01&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                minimum.status shouldBe HttpStatusCode.OK
                freeBalanceOf(recipient).compareTo(BigDecimal("0.01")) shouldBe 0
            }
        }

        test("transferLtr: rejects a self-transfer and an unknown recipient with NotFound, not a raw error") {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val sender = createTestMember("pt-self-sender@example.org")
                mintLtr(sender, BigDecimal("5.00"))

                val self =
                    client.post("/test/transfer?recipientId=$sender&amount=1.00&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                self.status shouldBe HttpStatusCode.Conflict

                val unknownRecipient = Uuid.random()
                val unknown =
                    client.post("/test/transfer?recipientId=$unknownRecipient&amount=1.00&characterization=SONSTIGES") {
                        header("X-Member-Id", sender.toString())
                    }
                unknown.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "executeArbitrationTransfer: MEMBER forbidden, blank/whitespace purpose rejected, TREASURER moves LTR between two third-party members with initiatedBy recorded",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val victim = createTestMember("pt-arbitration-victim@example.org")
                val fraudster = createTestMember("pt-arbitration-fraudster@example.org")
                mintLtr(fraudster, BigDecimal("20.00"))

                val forbidden =
                    client.post(
                        "/test/arbitration-transfer?senderId=$fraudster&recipientId=$victim&amount=15.00" +
                            "&characterization=SONSTIGES&purpose=Schiedsanordnung%20Az.%2042",
                    ) { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val blankPurpose =
                    client.post(
                        "/test/arbitration-transfer?senderId=$fraudster&recipientId=$victim&amount=15.00&characterization=SONSTIGES&purpose=",
                    ) {
                        header("X-Member-Id", TREASURER_ID)
                    }
                blankPurpose.status shouldBe HttpStatusCode.Conflict

                val whitespacePurpose =
                    client.post(
                        "/test/arbitration-transfer?senderId=$fraudster&recipientId=$victim&amount=15.00&characterization=SONSTIGES&purpose=%20%20",
                    ) { header("X-Member-Id", TREASURER_ID) }
                whitespacePurpose.status shouldBe HttpStatusCode.Conflict
                freeBalanceOf(fraudster).compareTo(BigDecimal("20.00")) shouldBe 0

                val corrected =
                    client.post(
                        "/test/arbitration-transfer?senderId=$fraudster&recipientId=$victim&amount=15.00" +
                            "&characterization=SONSTIGES&purpose=Schiedsanordnung%20Az.%2042",
                    ) { header("X-Member-Id", TREASURER_ID) }
                corrected.status shouldBe HttpStatusCode.OK
                val (_, respSender, respRecipient, _, initiatedBy) = corrected.bodyAsText().split(":")
                respSender shouldBe fraudster.toString()
                respRecipient shouldBe victim.toString()
                initiatedBy shouldBe TREASURER_ID

                freeBalanceOf(fraudster).compareTo(BigDecimal("5.00")) shouldBe 0
                freeBalanceOf(victim).compareTo(BigDecimal("15.00")) shouldBe 0

                val outEntryCreatedBy =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq fraudster) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.PEER_TRANSFER_OUT)
                            }.single()[LtrLedgerEntryTable.createdBy]
                    }
                outEntryCreatedBy shouldBe Uuid.parse(TREASURER_ID)
            }
        }

        test("executeArbitrationTransfer: senderMemberId == recipientMemberId is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val member = createTestMember("pt-arbitration-self@example.org")
                mintLtr(member, BigDecimal("5.00"))

                val response =
                    client.post(
                        "/test/arbitration-transfer?senderId=$member&recipientId=$member&amount=1.00" +
                            "&characterization=SONSTIGES&purpose=Schiedsanordnung",
                    ) { header("X-Member-Id", TREASURER_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "transferLtr: two concurrent, opposite-direction transfers between the same two members complete without deadlock, final balances exact",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPeerTransferExceptionHandlers() }
                    routing { registerPeerTransferTestRoutes() }
                }
                val a = createTestMember("pt-deadlock-a@example.org")
                val b = createTestMember("pt-deadlock-b@example.org")
                mintLtr(a, BigDecimal("100.00"))
                mintLtr(b, BigDecimal("100.00"))

                runConcurrentOppositeTransfers(client, a, b, aToB = BigDecimal("10.00"), bToA = BigDecimal("7.00"))

                freeBalanceOf(a).compareTo(BigDecimal("97.00")) shouldBe 0
                freeBalanceOf(b).compareTo(BigDecimal("103.00")) shouldBe 0
                val outCount =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId inList listOf(a, b)) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.PEER_TRANSFER_OUT)
                            }.count()
                    }
                outCount shouldBe 2L
            }
        }
    })

/**
 * Fires an A->B and a B->A [PeerTransferService.transferLtr] call from two independent OS
 * threads, synchronized via [CountDownLatch] so both are issued as close to simultaneously as
 * possible, each blocking on its own thread via `runBlocking` (real thread-level parallelism, not
 * two coroutines cooperatively sharing one thread) -- see [PeerTransferService.lockBothAccounts]
 * KDoc for the lock-order this exercises. Both threads must complete within [timeoutSeconds];
 * exceeding it fails the test with an explicit deadlock diagnosis rather than hanging the whole
 * suite.
 */
private fun runConcurrentOppositeTransfers(
    client: HttpClient,
    memberA: Uuid,
    memberB: Uuid,
    aToB: BigDecimal,
    bToA: BigDecimal,
    timeoutSeconds: Long = 20,
) {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val failures = mutableListOf<Throwable>()

    fun transferThread(
        senderId: Uuid,
        recipientId: Uuid,
        amount: BigDecimal,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post("/test/transfer?recipientId=$recipientId&amount=$amount&characterization=SONSTIGES") {
                            header("X-Member-Id", senderId.toString())
                        }
                    check(response.status == HttpStatusCode.OK) { "Unexpected status ${response.status}: ${response.bodyAsText()}" }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val threadAtoB = transferThread(memberA, memberB, aToB)
    val threadBtoA = transferThread(memberB, memberA, bToA)
    threadAtoB.start()
    threadBtoA.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent opposite-direction transfers did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
}

private fun cleanUpPeerTransferTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        PeerTransferTable.deleteWhere {
            (PeerTransferTable.senderMemberId inList memberIds) or
                (PeerTransferTable.recipientMemberId inList memberIds)
        }
        LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installPeerTransferExceptionHandlers() {
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

/** Shared throwaway routes for [PeerTransferService] -- mirrors [CrowdfundingServiceTest]'s `registerCrowdfundingTestRoutes` style. */
private fun Route.registerPeerTransferTestRoutes() {
    post("/test/transfer") {
        val service = PeerTransferService(call)
        val q = call.request.queryParameters
        val r =
            service.transferLtr(
                PeerTransferInput(
                    recipientMemberId = q["recipientId"]!!,
                    amountLtr = BigDecimal(q["amount"] ?: "1.00"),
                    characterization = PeerTransferCharacterization.valueOf(q["characterization"] ?: "SONSTIGES"),
                    purpose = q["purpose"],
                ),
            )
        call.respondText("${r.transferId}:${r.senderMemberId}:${r.recipientMemberId}:${r.amountLtr}:${r.initiatedById ?: "null"}")
    }
    post("/test/arbitration-transfer") {
        val service = PeerTransferService(call)
        val q = call.request.queryParameters
        val r =
            service.executeArbitrationTransfer(
                ArbitrationTransferInput(
                    senderMemberId = q["senderId"]!!,
                    recipientMemberId = q["recipientId"]!!,
                    amountLtr = BigDecimal(q["amount"] ?: "1.00"),
                    characterization = PeerTransferCharacterization.valueOf(q["characterization"] ?: "SONSTIGES"),
                    purpose = q["purpose"] ?: "",
                ),
            )
        call.respondText("${r.transferId}:${r.senderMemberId}:${r.recipientMemberId}:${r.amountLtr}:${r.initiatedById ?: "null"}")
    }
    get("/test/my-entries") {
        val service = LtrLedgerService(call)
        call.respondText(service.listMyEntries().joinToString(",") { it.entryType.name })
    }
}
