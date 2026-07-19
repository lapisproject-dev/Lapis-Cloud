package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CostCenterTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CostCenterInput
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.ReserveType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Exercises [AccountingService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [ElectionServiceTest]/[SystemicConsensusServiceTest]. Uses its own
 * freshly created members/accounts throughout (never [DevSeedData]'s shared demo fixtures), for
 * the same order-independence reasoning those files document. [afterSpec] hard-deletes every row
 * this file created.
 */
class AccountingServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdCostCenterIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpAccountingTestData(createdMemberIds, createdLedgerAccountIds, createdCostCenterIds) }

        fun createTestMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Accounting Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
            accountClass: Int = 0,
            reserveType: ReserveType? = null,
            isCashRegister: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[LedgerAccountTable.accountClass] = accountClass
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[LedgerAccountTable.reserveType] = reserveType
                    it[LedgerAccountTable.isCashRegister] = isCashRegister
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        /**
         * Directly inserts a [CostCenterTable] row, bypassing the RPC service -- same "direct DB
         * insert + tracked for cleanup" idiom as [createLedgerAccount], used by tests that need a
         * pre-existing cost center without exercising `createCostCenter` itself.
         */
        fun createCostCenterDirect(
            code: String,
            name: String = "Testkostenstelle $code",
            active: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                CostCenterTable.insert {
                    it[CostCenterTable.id] = id
                    it[CostCenterTable.code] = code
                    it[CostCenterTable.name] = name
                    it[CostCenterTable.description] = null
                    it[CostCenterTable.active] = active
                }
            }
            createdCostCenterIds += id
            return id
        }

        // Trailing 5th field is costCenterId, empty when null -- kept backward-compatible with
        // every call site above that never sets PostingInput.costCenterId.
        fun postingsParam(postings: List<PostingInput>): String =
            postings.joinToString(",") { "${it.ledgerAccountId}:${it.side}:${it.amount}:${it.sphere}:${it.costCenterId ?: ""}" }

        fun entryParams(
            date: LocalDate,
            description: String,
            postings: List<PostingInput>,
            voucher: String? = null,
            donorMemberId: String? = null,
        ): String =
            buildString {
                append("date=$date&description=$description&postings=${postingsParam(postings)}")
                if (voucher != null) append("&voucher=$voucher")
                if (donorMemberId != null) append("&donorMemberId=$donorMemberId")
            }

        test("treasurer can create a LedgerAccount; a plain member is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-create@example.org", AccountRole.TREASURER)
                val plainMember = createTestMember("acct-plain-create@example.org", AccountRole.MEMBER)

                val created =
                    client
                        .post("/test/create-ledger-account?number=0930&name=Sparkonto&class=0&type=ASSET") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                created.split(":")[1] shouldBe "0930"
                createdLedgerAccountIds += Uuid.parse(created.split(":")[0])

                val forbidden =
                    client.post("/test/create-ledger-account?number=0931&name=X&class=0&type=ASSET") {
                        header("X-Member-Id", plainMember.toString())
                    }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/create-ledger-account?number=0932&name=X&class=0&type=ASSET")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("duplicate accountNumber is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-dup@example.org", AccountRole.TREASURER)

                val first =
                    client.post("/test/create-ledger-account?number=0940&name=Erstkonto&class=0&type=ASSET") {
                        header("X-Member-Id", treasurer.toString())
                    }
                first.status shouldBe HttpStatusCode.OK
                createdLedgerAccountIds += Uuid.parse(first.bodyAsText().split(":")[0])

                val duplicate =
                    client.post("/test/create-ledger-account?number=0940&name=Zweitkonto&class=0&type=ASSET") {
                        header("X-Member-Id", treasurer.toString())
                    }
                duplicate.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("postJournalEntry with a balanced entry succeeds and writes POSTED with N postings") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-post@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0921", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2111", LedgerAccountType.INCOME, accountClass = 2)

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("50.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("50.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Mitgliedsbeitrag", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText().split(":")
                body[1] shouldBe "POSTED"
                body[2] shouldBe "2"
            }
        }

        test("postJournalEntry with donorMemberId + an INCOME posting succeeds and round-trips donorMemberId/donorMemberDisplayName") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-donor@example.org", AccountRole.TREASURER)
                val donor = createTestMember("acct-donor@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("9001", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9101", LedgerAccountType.INCOME, accountClass = 2)

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            spenden.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post(
                        "/test/post-entry?${
                            entryParams(LocalDate(2026, 3, 5), "Spende", postings, donorMemberId = donor.toString())
                        }",
                    ) {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val entryId = response.bodyAsText().split(":")[0]

                val donorRoundTrip =
                    client.get("/test/get-entry-donor/$entryId") { header("X-Member-Id", treasurer.toString()) }
                donorRoundTrip.bodyAsText() shouldBe "$entryId:$donor:Accounting Testmitglied"
            }
        }

        test("postJournalEntry with donorMemberId referencing a nonexistent member is rejected with NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-donor-404@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("9002", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9102", LedgerAccountType.INCOME, accountClass = 2)
                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            spenden.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post(
                        "/test/post-entry?${
                            entryParams(
                                LocalDate(2026, 3, 6),
                                "Spende",
                                postings,
                                donorMemberId = "00000000-0000-0000-0000-00000000dead",
                            )
                        }",
                    ) {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("postJournalEntry with donorMemberId set but no INCOME posting is rejected with BadRequest") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-donor-noincome@example.org", AccountRole.TREASURER)
                val donor = createTestMember("acct-donor-noincome@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("9003", LedgerAccountType.ASSET)
                val bank = createLedgerAccount("9004", LedgerAccountType.ASSET)
                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            bank.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post(
                        "/test/post-entry?${
                            entryParams(LocalDate(2026, 3, 7), "Umbuchung", postings, donorMemberId = donor.toString())
                        }",
                    ) {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("listJournal(donorMemberId=X) returns only that donor's entries; omitted argument is fully backward compatible") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-donor-list@example.org", AccountRole.TREASURER)
                val donorA = createTestMember("acct-donor-a@example.org", AccountRole.MEMBER)
                val donorB = createTestMember("acct-donor-b@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("9005", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9103", LedgerAccountType.INCOME, accountClass = 2)

                suspend fun postDonation(
                    date: LocalDate,
                    donorMemberId: String,
                ): String {
                    val postings =
                        listOf(
                            PostingInput(
                                kasse.toString(),
                                PostingSide.DEBIT,
                                BigDecimal("5.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                            PostingInput(
                                spenden.toString(),
                                PostingSide.CREDIT,
                                BigDecimal("5.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                        )
                    return client
                        .post("/test/post-entry?${entryParams(date, "Spende", postings, donorMemberId = donorMemberId)}") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                        .split(":")[0]
                }

                val entryA = postDonation(LocalDate(2026, 4, 1), donorA.toString())
                postDonation(LocalDate(2026, 4, 2), donorB.toString())

                val filtered =
                    client.get("/test/list-journal?donorMemberId=$donorA") { header("X-Member-Id", treasurer.toString()) }
                val filteredIds = filtered.bodyAsText().split(";").map { it.split(":")[0] }
                filteredIds shouldBe listOf(entryA)

                // Regression: no donorMemberId argument at all -- from/to/status filters unaffected,
                // both donations (and everything else this spec created) are still listed.
                val unfiltered =
                    client.get("/test/list-journal?from=2026-04-01&to=2026-04-02") { header("X-Member-Id", treasurer.toString()) }
                val unfilteredIds = unfiltered.bodyAsText().split(";").map { it.split(":")[0] }
                (entryA in unfilteredIds) shouldBe true
            }
        }

        test("postJournalEntry with an unbalanced entry is rejected and nothing is persisted") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-unbalanced@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0922", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2112", LedgerAccountType.INCOME, accountClass = 2)

                val countBefore = transaction { JournalEntryTable.selectAll().count() }

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("90.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Unbalanced", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict

                val countAfter = transaction { JournalEntryTable.selectAll().count() }
                countAfter shouldBe countBefore
            }
        }

        test("posting to an inactive or nonexistent LedgerAccount is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-inactive@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0923", LedgerAccountType.ASSET)
                val inactive = createLedgerAccount("0924", LedgerAccountType.ASSET)
                transaction { LedgerAccountTable.update({ LedgerAccountTable.id eq inactive }) { it[active] = false } }

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            inactive.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Inaktiv", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict

                val nonexistentPostings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            Uuid.random().toString(),
                            PostingSide.CREDIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val notFoundResponse =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Unbekannt", nonexistentPostings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                notFoundResponse.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("saveDraftEntry allows an unbalanced entry; postDraftEntry rejects until it balances, then succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-draft@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0925", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2113", LedgerAccountType.INCOME, accountClass = 2)

                val unbalancedPostings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("75.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val draftResponse =
                    client.post("/test/save-draft?${entryParams(LocalDate(2026, 4, 1), "Entwurf", unbalancedPostings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                draftResponse.status shouldBe HttpStatusCode.OK
                val draft = draftResponse.bodyAsText().split(":")
                draft[1] shouldBe "DRAFT"
                val entryId = draft[0]

                // Still unbalanced -- postDraftEntry must reject.
                val rejectedPost = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                rejectedPost.status shouldBe HttpStatusCode.Conflict

                // Balance it by inserting the missing credit line directly (simulating a later edit).
                transaction {
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[journalEntryId] = Uuid.parse(entryId)
                        it[ledgerAccountId] = beitraege
                        it[side] = PostingSide.CREDIT
                        it[amount] = BigDecimal("75.00")
                        it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    }
                }
                val postedResponse = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                postedResponse.status shouldBe HttpStatusCode.OK
                postedResponse.bodyAsText().split(":")[1] shouldBe "POSTED"

                // Once POSTED, a further postDraftEntry attempt (immutability) is rejected.
                val alreadyPosted = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                alreadyPosted.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("listJournal is chronological and filters by date range and status") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-journal@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0926", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2114", LedgerAccountType.INCOME, accountClass = 2)

                suspend fun postBalanced(date: LocalDate) {
                    val postings =
                        listOf(
                            PostingInput(
                                kasse.toString(),
                                PostingSide.DEBIT,
                                BigDecimal("10.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                            PostingInput(
                                beitraege.toString(),
                                PostingSide.CREDIT,
                                BigDecimal("10.00"),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                        )
                    client.post("/test/post-entry?${entryParams(date, "Buchung-$date", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                postBalanced(LocalDate(2026, 5, 3))
                postBalanced(LocalDate(2026, 5, 1))
                postBalanced(LocalDate(2026, 5, 2))

                val all =
                    client
                        .get("/test/list-journal?from=2026-05-01&to=2026-05-03") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val dates = all.split(";").filter { it.isNotBlank() }.map { it.split(":")[1] }
                dates shouldBe listOf("2026-05-01", "2026-05-02", "2026-05-03")

                val narrowed =
                    client
                        .get("/test/list-journal?from=2026-05-02&to=2026-05-02") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                narrowed.split(";").filter { it.isNotBlank() }.size shouldBe 1

                val postedOnly =
                    client
                        .get("/test/list-journal?status=POSTED") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                postedOnly.split(";").all { it.isBlank() || it.contains("POSTED") } shouldBe true
            }
        }

        // The three tests below exercise report-wide aggregations (GuV sums every INCOME/EXPENSE
        // account in the requested date range; Bilanz sums every ASSET/LIABILITY/EQUITY account
        // cumulatively since inception through asOf) -- unlike getGeneralLedgerAccount, they are
        // NOT scoped to a single ledgerAccountId, so they are NOT isolated from postings any other
        // test in this file creates. Every other test in this Spec dates its fixtures in 2026
        // (Jan-Jun); the GuV test below therefore uses a distinct year (2030) so its date-range
        // query never picks up unrelated postings. The Bilanz is cumulative-from-inception by
        // design (see BalanceSheetDto KDoc) and can never be isolated by choice of date range alone
        // -- so that test (and the Bilanz half of the Jahresabschluss test) asserts a *delta*
        // between two calls instead of an absolute total, which is exact and robust regardless of
        // what other tests contribute.

        test("getIncomeStatement sums income/expense over a date range, excludes DRAFT and out-of-range entries") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-guv@example.org", AccountRole.TREASURER)
                val board = createTestMember("acct-board-guv@example.org", AccountRole.BOARD)
                val plainMember = createTestMember("acct-plain-guv@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("0928", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4000", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6310", LedgerAccountType.EXPENSE, accountClass = 6)

                suspend fun post(
                    date: LocalDate,
                    description: String,
                    postings: List<PostingInput>,
                ) {
                    client.post("/test/post-entry?${entryParams(date, description, postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                // In range: 100 income, 40 expense. Year 2030 -- see comment above the test group.
                post(
                    LocalDate(2030, 3, 1),
                    "Beitrag",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
                post(
                    LocalDate(2030, 3, 15),
                    "Miete",
                    listOf(
                        PostingInput(
                            miete.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("40.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            kasse.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("40.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
                // Out of range -- must not contribute.
                post(
                    LocalDate(2030, 4, 1),
                    "Spaeter",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("500.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("500.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
                // DRAFT in range -- must not contribute.
                client.post(
                    "/test/save-draft?${
                        entryParams(
                            LocalDate(2030, 3, 10),
                            "Entwurf-GuV",
                            listOf(
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("999.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val response =
                    client
                        .get("/test/income-statement?from=2030-03-01&to=2030-03-31") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val parts = response.split(":")
                parts[0] shouldBe "100.00"
                parts[1] shouldBe "40.00"
                parts[2] shouldBe "60.00"

                // Narrower range excludes the Miete booking.
                val narrowed =
                    client
                        .get("/test/income-statement?from=2030-03-01&to=2030-03-01") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                        .split(":")
                narrowed[0] shouldBe "100.00"
                narrowed[1] shouldBe "0"

                // Authorization: BOARD may read, plain MEMBER is forbidden, unauthenticated is unauthorized.
                client
                    .get("/test/income-statement?from=2030-03-01&to=2030-03-31") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/income-statement?from=2030-03-01&to=2030-03-31") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/income-statement?from=2030-03-01&to=2030-03-31").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("getBalanceSheet is cumulative from inception through asOf and always balances") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-bilanz@example.org", AccountRole.TREASURER)
                val plainMember = createTestMember("acct-plain-bilanz@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("0929", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4001", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6311", LedgerAccountType.EXPENSE, accountClass = 6)

                suspend fun balanceSheetParts(asOf: String): List<String> =
                    client
                        .get("/test/balance-sheet?asOf=$asOf") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .split(":")

                // Baseline taken before this fixture's own postings -- cumulative from inception
                // necessarily also reflects whatever earlier tests in this Spec already posted, so
                // the assertions below use the delta between "before" and "after", not an absolute
                // total (see comment above the test group).
                val before = balanceSheetParts("2026-12-31")
                before[2] shouldBe "true" // balanced, even before this fixture's own postings

                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2026, 2, 1),
                            "Beitrag",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("300.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("300.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2026, 2, 15),
                            "Miete",
                            listOf(
                                PostingInput(
                                    miete.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("120.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("120.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }
                // DRAFT -- must not contribute.
                client.post(
                    "/test/save-draft?${
                        entryParams(
                            LocalDate(2026, 2, 20),
                            "Entwurf-Bilanz",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("999.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val after = balanceSheetParts("2026-12-31")
                // totalAssets delta (kasse = 300 - 120 = 180) must equal the totalEquityAndLiabilities delta.
                (BigDecimal(after[0]) - BigDecimal(before[0])).compareTo(BigDecimal("180.00")) shouldBe 0
                (BigDecimal(after[1]) - BigDecimal(before[1])).compareTo(BigDecimal("180.00")) shouldBe 0
                after[2] shouldBe "true"
                // accumulatedResult delta = 300 income - 120 expense = 180, same magnitude as the
                // GuV result would be for just this fixture's postings.
                (BigDecimal(after[3]) - BigDecimal(before[3])).compareTo(BigDecimal("180.00")) shouldBe 0

                client
                    .get("/test/balance-sheet?asOf=2026-12-31") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/balance-sheet?asOf=2026-12-31").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("getAnnualFinancialStatement: periodResult diverges from accumulatedResult across fiscal years") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-jahresabschluss@example.org", AccountRole.TREASURER)
                val plainMember = createTestMember("acct-plain-jahresabschluss@example.org", AccountRole.MEMBER)
                // Distinct, far-future years (unused by any other test in this Spec) so periodResult
                // -- a date-range-scoped GuV flow -- is exactly isolated, same reasoning as the GuV
                // test's year 2030 above.
                val kasse = createLedgerAccount("0933", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4002", LedgerAccountType.INCOME, accountClass = 4)

                // Baseline cumulative result strictly before either fixture posting -- see the
                // Bilanz test above for why cumulative-from-inception figures must be diffed, not
                // asserted as absolute totals.
                val before2040 =
                    client
                        .get("/test/balance-sheet?asOf=2039-12-31") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .split(":")

                // Year 2040: 200 income.
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2040, 6, 1),
                            "Beitrag-2040",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("200.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("200.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }
                // Year 2041: 50 income.
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2041, 6, 1),
                            "Beitrag-2041",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("50.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("50.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val statement2041 =
                    client
                        .get("/test/annual-statement?year=2041") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .split(":")
                statement2041[0] shouldBe "2041-12-31" // periodEnd == balanceSheet.asOf
                statement2041[1] shouldBe "50.00" // periodResult: 2041-only flow, exactly isolated
                // accumulatedResult delta since the 2039 baseline = both fixture years' income
                // (200 + 50 = 250), demonstrating the cumulative Bilanz figure legitimately
                // diverges from the single-year GuV periodResult (50.00) once more than one fiscal
                // year has activity.
                (BigDecimal(statement2041[2]) - BigDecimal(before2040[3])).compareTo(BigDecimal("250.00")) shouldBe 0

                client
                    .get("/test/annual-statement?year=2041") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/annual-statement?year=2041").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("getAnnualFinancialStatement rejects an out-of-range fiscalYear with 400 instead of crashing") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-fiscalyear@example.org", AccountRole.TREASURER)

                client
                    .get("/test/annual-statement?year=${Int.MAX_VALUE}") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .get("/test/annual-statement?year=0") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("getGeneralLedgerAccount computes correct running balances for both normal-balance sides") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-ledger@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0927", LedgerAccountType.ASSET) // debit-normal
                val beitraege = createLedgerAccount("2115", LedgerAccountType.INCOME, accountClass = 2) // credit-normal

                suspend fun post(
                    date: LocalDate,
                    kasseSide: PostingSide,
                    amount: String,
                ) {
                    val beitraegeSide = if (kasseSide == PostingSide.DEBIT) PostingSide.CREDIT else PostingSide.DEBIT
                    val postings =
                        listOf(
                            PostingInput(
                                kasse.toString(),
                                kasseSide,
                                BigDecimal(amount),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                            PostingInput(
                                beitraege.toString(),
                                beitraegeSide,
                                BigDecimal(amount),
                                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            ),
                        )
                    client.post("/test/post-entry?${entryParams(date, "GL-$date", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                post(LocalDate(2026, 6, 1), PostingSide.DEBIT, "100.00")
                post(LocalDate(2026, 6, 2), PostingSide.DEBIT, "20.00")

                val kasseLedger =
                    client.get("/test/general-ledger/$kasse") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                // ASSET is debit-normal -- two DEBIT postings of 100 and 20 both increase the balance.
                kasseLedger.split(":")[2] shouldBe "120.00"

                val beitraegeLedger =
                    client.get("/test/general-ledger/$beitraege") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                // INCOME is credit-normal -- two CREDIT postings of 100 and 20 both increase the balance.
                beitraegeLedger.split(":")[2] shouldBe "120.00"
            }
        }

        test("posting.sphere round-trips exactly as sent -- postJournalEntry then getJournalEntry") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-sphere-roundtrip@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0934", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4003", LedgerAccountType.INCOME, accountClass = 4)

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB,
                        ),
                    )
                val posted =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 7, 1), "Sphaere-Roundtrip", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                posted.status shouldBe HttpStatusCode.OK
                val entryId = posted.bodyAsText().split(":")[0]

                val fetched =
                    client.get("/test/get-entry/$entryId") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                val postingSpheres = fetched.split(":", limit = 4)[3].split("|").toSet()
                postingSpheres shouldBe
                    setOf(
                        "DEBIT:${GemeinnuetzigkeitSphere.ZWECKBETRIEB}",
                        "CREDIT:${GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB}",
                    )
            }
        }

        test("a single journal entry whose debit and credit lines carry different spheres posts successfully (inter-sphere transfer)") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cross-sphere@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0935", LedgerAccountType.ASSET)
                val bank = createLedgerAccount("0936", LedgerAccountType.ASSET)

                val postings =
                    listOf(
                        PostingInput(
                            bank.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("25.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            kasse.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("25.00"),
                            sphere = GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 7, 2), "Umbuchung", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[1] shouldBe "POSTED"
            }
        }

        test("getFourSphereIncomeStatement splits income/expense by sphere, all-four-present, excludes DRAFT and out-of-range") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-vier-sphaeren@example.org", AccountRole.TREASURER)
                val board = createTestMember("acct-board-vier-sphaeren@example.org", AccountRole.BOARD)
                val plainMember = createTestMember("acct-plain-vier-sphaeren@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("0937", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4004", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6312", LedgerAccountType.EXPENSE, accountClass = 6)

                suspend fun post(
                    date: LocalDate,
                    description: String,
                    postings: List<PostingInput>,
                ) {
                    client.post("/test/post-entry?${entryParams(date, description, postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                // Distinct far-future year, same date-range-isolation idiom as the GuV test (2030) above.
                // IDEELLER_BEREICH: 200 income.
                post(
                    LocalDate(2035, 3, 1),
                    "Spende",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("200.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("200.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
                // ZWECKBETRIEB: 80 income, 30 expense.
                post(
                    LocalDate(2035, 3, 5),
                    "Zweckbetrieb-Erloes",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("80.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("80.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                    ),
                )
                post(
                    LocalDate(2035, 3, 6),
                    "Zweckbetrieb-Miete",
                    listOf(
                        PostingInput(
                            miete.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                        PostingInput(
                            kasse.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                        ),
                    ),
                )
                // Out of range -- must not contribute.
                post(
                    LocalDate(2035, 4, 1),
                    "Spaeter",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("999.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("999.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
                // DRAFT in range -- must not contribute.
                client.post(
                    "/test/save-draft?${
                        entryParams(
                            LocalDate(2035, 3, 10),
                            "Entwurf-VierSphaeren",
                            listOf(
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("777.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val response =
                    client
                        .get("/test/four-sphere-income-statement?from=2035-03-01&to=2035-03-31") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val (perSphereRaw, overallRaw) = response.split("#")
                val perSphere =
                    perSphereRaw.split(";").associate {
                        val parts = it.split(":")
                        parts[0] to Triple(parts[1], parts[2], parts[3])
                    }
                // All four spheres present (zero-filled where no activity).
                perSphere.keys shouldBe
                    setOf(
                        "${GemeinnuetzigkeitSphere.IDEELLER_BEREICH}",
                        "${GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG}",
                        "${GemeinnuetzigkeitSphere.ZWECKBETRIEB}",
                        "${GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB}",
                    )
                perSphere.getValue("${GemeinnuetzigkeitSphere.IDEELLER_BEREICH}") shouldBe Triple("200.00", "0", "200.00")
                perSphere.getValue("${GemeinnuetzigkeitSphere.ZWECKBETRIEB}") shouldBe Triple("80.00", "30.00", "50.00")
                perSphere.getValue("${GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG}") shouldBe Triple("0", "0", "0")
                perSphere.getValue("${GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB}") shouldBe Triple("0", "0", "0")

                val overallParts = overallRaw.split(":")
                overallParts[0] shouldBe "280.00" // 200 + 80
                overallParts[1] shouldBe "30.00"
                overallParts[2] shouldBe "250.00"

                // Authorization: BOARD may read, plain MEMBER is forbidden, unauthenticated is unauthorized.
                client
                    .get("/test/four-sphere-income-statement?from=2035-03-01&to=2035-03-31") {
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .get("/test/four-sphere-income-statement?from=2035-03-01&to=2035-03-31") {
                        header("X-Member-Id", plainMember.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/four-sphere-income-statement?from=2035-03-01&to=2035-03-31")
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("treasurer can create an EQUITY LedgerAccount with a reserveType; it round-trips in the DTO") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-reserve-create@example.org", AccountRole.TREASURER)

                val created =
                    client
                        .post(
                            "/test/create-ledger-account?number=0950&name=Testruecklage&class=2&type=EQUITY" +
                                "&reserveType=PROJEKTRUECKLAGE",
                        ) { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val parts = created.split(":")
                parts[2] shouldBe "EQUITY"
                parts[4] shouldBe "PROJEKTRUECKLAGE"
                createdLedgerAccountIds += Uuid.parse(parts[0])
            }
        }

        test("reserveType on a non-EQUITY LedgerAccount is rejected with BadRequest; nothing persisted") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-reserve-invalid@example.org", AccountRole.TREASURER)
                val countBefore = transaction { LedgerAccountTable.selectAll().count() }

                val response =
                    client.post(
                        "/test/create-ledger-account?number=0951&name=Falsch&class=1&type=ASSET&reserveType=FREIE_RUECKLAGE",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest

                val countAfter = transaction { LedgerAccountTable.selectAll().count() }
                countAfter shouldBe countBefore
            }
        }

        test(
            "getUseOfFundsStatement: income/reserve-allocation/expense across two fiscal years, " +
                "excludes DRAFT, reserve keyed by account not posting.sphere",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-use-of-funds@example.org", AccountRole.TREASURER)
                val board = createTestMember("acct-board-use-of-funds@example.org", AccountRole.BOARD)
                val plainMember = createTestMember("acct-plain-use-of-funds@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("0952", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4010", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6320", LedgerAccountType.EXPENSE, accountClass = 6)
                val vereinsvermoegen = createLedgerAccount("2010", LedgerAccountType.EQUITY, accountClass = 2)
                val projektruecklage =
                    createLedgerAccount("2110", LedgerAccountType.EQUITY, accountClass = 2, reserveType = ReserveType.PROJEKTRUECKLAGE)

                // Baseline timelyUseObligationRemaining strictly before this test's own fixtures --
                // the §55 AO clock is inception-anchored (rolled forward from the *whole DB's*
                // earliest activity, not just this test's), so other tests' postings in earlier
                // years already contribute to the pot by year 2050. Diffing against this baseline
                // (same "delta, not absolute total" idiom the Bilanz/Jahresabschluss tests use for
                // cumulative-since-inception figures) keeps the assertions below exact and
                // order-independent regardless of what those other tests contributed.
                val obligationBefore2050 =
                    BigDecimal(
                        client
                            .get("/test/use-of-funds?from=2049&to=2049") { header("X-Member-Id", treasurer.toString()) }
                            .bodyAsText()
                            .split("#")[0]
                            .split(":")[4],
                    )

                // Fiscal year 2050: 1000 income, plus a 300 reserve allocation whose two lines
                // deliberately carry DIFFERENT spheres -- the reserve-movement loader must key off
                // the ledger account's reserveType, not posting.sphere (see
                // AccountingService.loadYearFacts KDoc).
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2050, 2, 1),
                            "Spende-2050",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("1000.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("1000.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2050, 3, 1),
                            "Ruecklagenzufuehrung-2050",
                            listOf(
                                PostingInput(
                                    vereinsvermoegen.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("300.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    projektruecklage.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("300.00"),
                                    sphere = GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }
                // DRAFT in the same year -- must not move any figure.
                client.post(
                    "/test/save-draft?${
                        entryParams(
                            LocalDate(2050, 4, 1),
                            "Entwurf-UseOfFunds",
                            listOf(
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("9999.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                // Fiscal year 2051: 400 expense.
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2051, 5, 1),
                            "Miete-2051",
                            listOf(
                                PostingInput(
                                    miete.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("400.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("400.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val response =
                    client
                        .get("/test/use-of-funds?from=2050&to=2051") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val (yearsRaw, reserveMovementsRaw, totalsRaw) = response.split("#")
                val years =
                    yearsRaw.split(";").associate { row ->
                        val p = row.split(":")
                        p[0].toInt() to p
                    }
                years.keys shouldBe setOf(2050, 2051)

                val year2050 = years.getValue(2050)
                BigDecimal(year2050[1]).compareTo(BigDecimal("1000.00")) shouldBe 0 // fundsReceived
                BigDecimal(year2050[2]).compareTo(BigDecimal.ZERO) shouldBe 0 // fundsUsed
                BigDecimal(year2050[3]).compareTo(BigDecimal("300.00")) shouldBe 0 // fundsAllocatedToReserves
                // obligation delta: +1000 income, -300 reserve allocation = +700 vs. the baseline.
                (BigDecimal(year2050[4]) - obligationBefore2050).compareTo(BigDecimal("700.00")) shouldBe 0

                val year2051 = years.getValue(2051)
                BigDecimal(year2051[1]).compareTo(BigDecimal.ZERO) shouldBe 0
                BigDecimal(year2051[2]).compareTo(BigDecimal("400.00")) shouldBe 0
                BigDecimal(year2051[3]).compareTo(BigDecimal.ZERO) shouldBe 0
                // obligation delta: 2050's +700 minus this year's 400 expense = +300 vs. the baseline.
                (BigDecimal(year2051[4]) - obligationBefore2050).compareTo(BigDecimal("300.00")) shouldBe 0

                // Reserve movement counted once, under PROJEKTRUECKLAGE, keyed by the account, not the sphere.
                val reserveRows = reserveMovementsRaw.split("|").flatMap { it.split(",") }
                val projektRows2050 = reserveRows.filter { it.startsWith("2050:${ReserveType.PROJEKTRUECKLAGE}:") }
                projektRows2050.size shouldBe 1
                val projektParts2050 = projektRows2050.single().split(":")
                BigDecimal(projektParts2050[2]).compareTo(BigDecimal("300.00")) shouldBe 0 // allocated
                BigDecimal(projektParts2050[3]).compareTo(BigDecimal("300.00")) shouldBe 0 // closingBalance
                val projektRows2051 = reserveRows.filter { it.startsWith("2051:${ReserveType.PROJEKTRUECKLAGE}:") }
                val projektParts2051 = projektRows2051.single().split(":")
                BigDecimal(projektParts2051[2]).compareTo(BigDecimal.ZERO) shouldBe 0 // allocated (nothing new)
                BigDecimal(projektParts2051[3]).compareTo(BigDecimal("300.00")) shouldBe 0 // closingBalance carried forward

                val totalsParts = totalsRaw.split(":")
                BigDecimal(totalsParts[0]).compareTo(BigDecimal("1000.00")) shouldBe 0
                BigDecimal(totalsParts[1]).compareTo(BigDecimal("400.00")) shouldBe 0
                BigDecimal(totalsParts[2]).compareTo(BigDecimal("300.00")) shouldBe 0
                // closingTimelyUseObligation is cumulative-since-inception (== year2051's own
                // obligation figure) -- delta vs. the baseline, same reasoning as above.
                (BigDecimal(totalsParts[3]) - obligationBefore2050).compareTo(BigDecimal("300.00")) shouldBe 0
                totalsParts[5] shouldBe "2"

                // Authorization: BOARD may read, plain MEMBER is forbidden, unauthenticated is unauthorized.
                client
                    .get("/test/use-of-funds?from=2050&to=2051") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/use-of-funds?from=2050&to=2051") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/use-of-funds?from=2050&to=2051").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("getUseOfFundsStatement: a later single-year window still reflects the earlier year's carried-forward obligation") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-use-of-funds-inception@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0953", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4011", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6321", LedgerAccountType.EXPENSE, accountClass = 6)

                // Baseline strictly before this test's own fixtures -- see the analogous comment in
                // the scenario test above for why a delta (not an absolute total) is required here.
                val obligationBefore2060 =
                    BigDecimal(
                        client
                            .get("/test/use-of-funds?from=2059&to=2059") { header("X-Member-Id", treasurer.toString()) }
                            .bodyAsText()
                            .split("#")[0]
                            .split(":")[4],
                    )

                // Year 2060: 800 income. Year 2061: 300 expense. Only 2061 is requested.
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2060, 2, 1),
                            "Spende-2060",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("800.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("800.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2061, 3, 1),
                            "Miete-2061",
                            listOf(
                                PostingInput(
                                    miete.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("300.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("300.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val response =
                    client
                        .get("/test/use-of-funds?from=2061&to=2061") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val year2061 = response.split("#")[0].split(":")
                year2061[0] shouldBe "2061"
                // 800 - 300 = +500 vs. the baseline, reflecting the 2060 carry-forward even though
                // only 2061 was requested.
                (BigDecimal(year2061[4]) - obligationBefore2060).compareTo(BigDecimal("500.00")) shouldBe 0
            }
        }

        test("getUseOfFundsStatement rejects fromFiscalYear > toFiscalYear and out-of-range years with 400") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-use-of-funds-range@example.org", AccountRole.TREASURER)

                client
                    .get("/test/use-of-funds?from=2055&to=2050") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .get("/test/use-of-funds?from=0&to=2050") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .get("/test/use-of-funds?from=2050&to=${Int.MAX_VALUE}") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── Kassenbuch (V0.3.5) ──────────────────────────────────────────────

        test("treasurer can create an ASSET LedgerAccount with isCashRegister=true; it round-trips in the DTO") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-create@example.org", AccountRole.TREASURER)

                val created =
                    client
                        .post(
                            "/test/create-ledger-account?number=0960&name=Testkasse&class=1&type=ASSET&isCashRegister=true",
                        ) { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val parts = created.split(":")
                parts[2] shouldBe "ASSET"
                parts[5] shouldBe "true"
                createdLedgerAccountIds += Uuid.parse(parts[0])
            }
        }

        test("isCashRegister=true on a non-ASSET LedgerAccount is rejected with BadRequest; nothing persisted") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-invalid@example.org", AccountRole.TREASURER)
                val countBefore = transaction { LedgerAccountTable.selectAll().count() }

                val response =
                    client.post(
                        "/test/create-ledger-account?number=0961&name=Falsch&class=4&type=INCOME&isCashRegister=true",
                    ) { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest

                val countAfter = transaction { LedgerAccountTable.selectAll().count() }
                countAfter shouldBe countBefore
            }
        }

        test(
            "postJournalEntry touching a cash-register account without a voucherReference is rejected with Conflict; " +
                "succeeds once given one",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-voucher@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0962", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4020", LedgerAccountType.INCOME, accountClass = 4)

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("20.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("20.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val countBefore = transaction { JournalEntryTable.selectAll().count() }

                val withoutVoucher =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 8, 1), "Ohne-Beleg", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                withoutVoucher.status shouldBe HttpStatusCode.Conflict
                val countAfter = transaction { JournalEntryTable.selectAll().count() }
                countAfter shouldBe countBefore

                val withVoucher =
                    client.post(
                        "/test/post-entry?${entryParams(LocalDate(2026, 8, 1), "Mit-Beleg", postings, voucher = "BELEG-001")}",
                    ) { header("X-Member-Id", treasurer.toString()) }
                withVoucher.status shouldBe HttpStatusCode.OK
            }
        }

        test("postJournalEntry touching a non-cash ASSET account without a voucherReference still succeeds (regression guard)") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-noncash-voucher@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("0963", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4021", LedgerAccountType.INCOME, accountClass = 4)

                val postings =
                    listOf(
                        PostingInput(
                            bank.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("15.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("15.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 8, 2), "Bank-Ohne-Beleg", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "postJournalEntry that would drive a cash-register account negative is rejected with Conflict; " +
                "the account balance is unchanged",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-negative@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0964", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4022", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6330", LedgerAccountType.EXPENSE, accountClass = 6)

                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2026, 8, 3),
                            "Einzahlung",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("50.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("50.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                            voucher = "BELEG-010",
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val ledgerBefore =
                    client.get("/test/general-ledger/$kasse") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()

                // Attempt to withdraw 80 -- would drive the account to -30.
                val overdraw =
                    client.post(
                        "/test/post-entry?${
                            entryParams(
                                LocalDate(2026, 8, 4),
                                "Ueberzug",
                                listOf(
                                    PostingInput(
                                        miete.toString(),
                                        PostingSide.DEBIT,
                                        BigDecimal("80.00"),
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                    PostingInput(
                                        kasse.toString(),
                                        PostingSide.CREDIT,
                                        BigDecimal("80.00"),
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                ),
                                voucher = "BELEG-011",
                            )
                        }",
                    ) { header("X-Member-Id", treasurer.toString()) }
                overdraw.status shouldBe HttpStatusCode.Conflict

                val ledgerAfter =
                    client.get("/test/general-ledger/$kasse") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                ledgerAfter shouldBe ledgerBefore
            }
        }

        test("postJournalEntry that drains a cash-register account to exactly zero succeeds (only strictly negative is rejected)") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-zero@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0965", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4023", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6331", LedgerAccountType.EXPENSE, accountClass = 6)

                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2026, 8, 5),
                            "Einzahlung",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("40.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("40.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                            voucher = "BELEG-020",
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val drainToZero =
                    client.post(
                        "/test/post-entry?${
                            entryParams(
                                LocalDate(2026, 8, 6),
                                "Vollstaendige-Auszahlung",
                                listOf(
                                    PostingInput(
                                        miete.toString(),
                                        PostingSide.DEBIT,
                                        BigDecimal("40.00"),
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                    PostingInput(
                                        kasse.toString(),
                                        PostingSide.CREDIT,
                                        BigDecimal("40.00"),
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                ),
                                voucher = "BELEG-021",
                            )
                        }",
                    ) { header("X-Member-Id", treasurer.toString()) }
                drainToZero.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "concurrency: N simultaneous postJournalEntry withdrawals against the same cash-register " +
                "account -- only as many succeed as the balance allows, it never goes negative",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-concurrency@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0971", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4027", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6333", LedgerAccountType.EXPENSE, accountClass = 6)

                // Seed 50.00 into the cash account.
                client.post(
                    "/test/post-entry?${
                        entryParams(
                            LocalDate(2036, 9, 1),
                            "Einzahlung",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("50.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("50.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                            voucher = "BELEG-300",
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                // Fire 5 concurrent withdrawals of 30.00 each -- only ONE can succeed without driving
                // the account negative (50 - 30 = 20; a second 30 would take it to -10). Without the
                // SELECT ... FOR UPDATE row lock in requireNonNegativeCashBalances, two concurrent
                // calls could each read the pre-withdrawal balance of 50 under READ_COMMITTED,
                // independently compute a non-negative projected balance, and both commit.
                val attempts = 5
                val results =
                    coroutineScope {
                        (1..attempts)
                            .map { i ->
                                async {
                                    client
                                        .post(
                                            "/test/post-entry?${
                                                entryParams(
                                                    LocalDate(2036, 9, 2),
                                                    "Auszahlung-$i",
                                                    listOf(
                                                        PostingInput(
                                                            miete.toString(),
                                                            PostingSide.DEBIT,
                                                            BigDecimal("30.00"),
                                                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                                        ),
                                                        PostingInput(
                                                            kasse.toString(),
                                                            PostingSide.CREDIT,
                                                            BigDecimal("30.00"),
                                                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                                        ),
                                                    ),
                                                    voucher = "BELEG-30$i",
                                                )
                                            }",
                                        ) { header("X-Member-Id", treasurer.toString()) }
                                        .status
                                }
                            }.map { it.await() }
                    }
                results.count { it == HttpStatusCode.OK } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe attempts - 1

                val finalBalance =
                    client
                        .get("/test/kassenbuch/$kasse") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                        .substringBefore("#")
                        .split(":")[3] // closingBalance
                finalBalance shouldBe "20.00"
            }
        }

        test(
            "saveDraftEntry allows an unbalanced/voucher-less draft touching a cash account; " +
                "postDraftEntry enforces both guards at DRAFT->POSTED",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cash-draft@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0966", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4024", LedgerAccountType.INCOME, accountClass = 4)

                // Unbalanced, voucher-less draft touching the cash account -- must be freely saveable.
                val draftPostings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("60.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val draftResponse =
                    client.post("/test/save-draft?${entryParams(LocalDate(2026, 8, 7), "Kassen-Entwurf", draftPostings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                draftResponse.status shouldBe HttpStatusCode.OK
                val entryId = draftResponse.bodyAsText().split(":")[0]

                // Balance it by inserting the missing credit line directly (simulating a later
                // edit) -- still voucher-less, so postDraftEntry must still reject.
                transaction {
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[journalEntryId] = Uuid.parse(entryId)
                        it[ledgerAccountId] = beitraege
                        it[side] = PostingSide.CREDIT
                        it[amount] = BigDecimal("60.00")
                        it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    }
                }
                val rejectedForVoucher = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                rejectedForVoucher.status shouldBe HttpStatusCode.Conflict

                // Add the voucherReference directly (simulating a later edit) -- now it succeeds.
                transaction {
                    JournalEntryTable.update({ JournalEntryTable.id eq Uuid.parse(entryId) }) {
                        it[voucherReference] = "BELEG-030"
                    }
                }
                val posted = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                posted.status shouldBe HttpStatusCode.OK
                posted.bodyAsText().split(":")[1] shouldBe "POSTED"
            }
        }

        test(
            "getKassenbuch returns chronologically-numbered lines with correct amountIn/amountOut/runningBalance, " +
                "excludes DRAFT and out-of-range",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-kassenbuch@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0967", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4025", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6332", LedgerAccountType.EXPENSE, accountClass = 6)

                suspend fun post(
                    date: LocalDate,
                    description: String,
                    postings: List<PostingInput>,
                    voucher: String,
                ) {
                    client.post("/test/post-entry?${entryParams(date, description, postings, voucher)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                // In range: +100 Einnahme, -30 Ausgabe.
                post(
                    LocalDate(2035, 9, 1),
                    "Spende",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                    "BELEG-100",
                )
                post(
                    LocalDate(2035, 9, 2),
                    "Miete",
                    listOf(
                        PostingInput(
                            miete.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            kasse.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                    "BELEG-101",
                )
                // Out of range -- must not appear.
                post(
                    LocalDate(2035, 10, 1),
                    "Spaeter",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("999.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("999.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                    "BELEG-102",
                )
                // DRAFT in range -- must not appear.
                client.post(
                    "/test/save-draft?${
                        entryParams(
                            LocalDate(2035, 9, 3),
                            "Entwurf-Kassenbuch",
                            listOf(
                                PostingInput(
                                    kasse.toString(),
                                    PostingSide.DEBIT,
                                    BigDecimal("777.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val response =
                    client
                        .get("/test/kassenbuch/$kasse?from=2035-09-01&to=2035-09-30") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val (headerRaw, linesRaw) = response.split("#")
                val headerParts = headerRaw.split(":")
                headerParts[4] shouldBe "2" // lines.size
                val lines = linesRaw.split(";").map { it.split(":") }
                lines.size shouldBe 2
                lines[0][0] shouldBe "1" // kassenbuchNumber
                lines[0][3] shouldBe "BELEG-100"
                lines[0][4] shouldBe "100.00" // amountIn
                lines[0][5] shouldBe "0" // amountOut
                lines[0][6] shouldBe "100.00" // runningBalance
                lines[1][0] shouldBe "2"
                lines[1][3] shouldBe "BELEG-101"
                lines[1][4] shouldBe "0"
                lines[1][5] shouldBe "30.00"
                lines[1][6] shouldBe "70.00"
            }
        }

        test(
            "getKassenbuch numbering/openingBalance is stable across a from filter -- the same physical " +
                "posting keeps its kassenbuchNumber and the filtered window's openingBalance reflects prior history",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-kassenbuch-stable@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0970", LedgerAccountType.ASSET, isCashRegister = true)
                val beitraege = createLedgerAccount("4026", LedgerAccountType.INCOME, accountClass = 4)

                suspend fun post(
                    date: LocalDate,
                    description: String,
                    amount: BigDecimal,
                    voucher: String,
                ) {
                    client.post(
                        "/test/post-entry?${
                            entryParams(
                                date,
                                description,
                                listOf(
                                    PostingInput(
                                        kasse.toString(),
                                        PostingSide.DEBIT,
                                        amount,
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                    PostingInput(
                                        beitraege.toString(),
                                        PostingSide.CREDIT,
                                        amount,
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                ),
                                voucher = voucher,
                            )
                        }",
                    ) { header("X-Member-Id", treasurer.toString()) }
                }

                // Two postings BEFORE the query window (July), one INSIDE it (August).
                post(LocalDate(2036, 7, 1), "Juli-1", BigDecimal("40.00"), "BELEG-200")
                post(LocalDate(2036, 7, 15), "Juli-2", BigDecimal("25.00"), "BELEG-201")
                post(LocalDate(2036, 8, 1), "August-1", BigDecimal("10.00"), "BELEG-202")

                val unfiltered =
                    client.get("/test/kassenbuch/$kasse") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                val (unfilteredHeader, unfilteredLinesRaw) = unfiltered.split("#")
                unfilteredHeader.split(":")[2] shouldBe "0" // openingBalance since inception is ZERO
                val unfilteredLines = unfilteredLinesRaw.split(";").map { it.split(":") }
                unfilteredLines.size shouldBe 3
                unfilteredLines[2][0] shouldBe "3" // the August posting is the 3rd line since inception

                val filtered =
                    client
                        .get("/test/kassenbuch/$kasse?from=2036-08-01&to=2036-08-31") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val (filteredHeader, filteredLinesRaw) = filtered.split("#")
                val filteredHeaderParts = filteredHeader.split(":")
                filteredHeaderParts[2] shouldBe "65.00" // openingBalance = 40.00 + 25.00 from the two July postings
                filteredHeaderParts[4] shouldBe "1" // lines.size
                val filteredLines = filteredLinesRaw.split(";").map { it.split(":") }
                filteredLines.size shouldBe 1
                // Same physical posting keeps the SAME kassenbuchNumber ("3") it has unfiltered -- the
                // fixed bug previously restarted numbering at "1" for a filtered window.
                filteredLines[0][0] shouldBe "3"
                filteredLines[0][3] shouldBe "BELEG-202"
                filteredLines[0][6] shouldBe "75.00" // runningBalance = 65.00 opening + 10.00
            }
        }

        test(
            "getKassenbuch on a non-cash-register LedgerAccount is rejected with Conflict; " +
                "on a nonexistent LedgerAccount is rejected with NotFound",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-kassenbuch-invalid@example.org", AccountRole.TREASURER)
                val bank = createLedgerAccount("0968", LedgerAccountType.ASSET)

                client
                    .get("/test/kassenbuch/$bank") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.Conflict

                client
                    .get("/test/kassenbuch/${Uuid.random()}") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("getKassenbuch authorization: TREASURER/BOARD/ADMIN may read, plain MEMBER is Forbidden, unauthenticated is Unauthorized") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-kassenbuch-auth@example.org", AccountRole.TREASURER)
                val board = createTestMember("acct-board-kassenbuch-auth@example.org", AccountRole.BOARD)
                val plainMember = createTestMember("acct-plain-kassenbuch-auth@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("0969", LedgerAccountType.ASSET, isCashRegister = true)

                client
                    .get("/test/kassenbuch/$kasse") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/kassenbuch/$kasse") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/kassenbuch/$kasse") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/kassenbuch/$kasse").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── Kostenstellen-/Projektbuchhaltung (V0.3.6) ──────────────────────

        test("treasurer can create a CostCenter; duplicate/blank code rejected; non-treasury forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-create@example.org", AccountRole.TREASURER)
                val plainMember = createTestMember("acct-plain-cc-create@example.org", AccountRole.MEMBER)

                val created =
                    client
                        .post("/test/create-cost-center?code=SOMMERFEST-2027&name=Sommerfest2027") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val parts = created.split(":")
                parts[1] shouldBe "SOMMERFEST-2027"
                parts[3] shouldBe "true"
                createdCostCenterIds += Uuid.parse(parts[0])

                val duplicate =
                    client.post("/test/create-cost-center?code=SOMMERFEST-2027&name=Zweitversuch") {
                        header("X-Member-Id", treasurer.toString())
                    }
                duplicate.status shouldBe HttpStatusCode.Conflict

                val blank =
                    client.post("/test/create-cost-center?code=&name=Leer") { header("X-Member-Id", treasurer.toString()) }
                blank.status shouldBe HttpStatusCode.BadRequest

                val forbidden =
                    client.post("/test/create-cost-center?code=SOMMERFEST-2028&name=X") {
                        header("X-Member-Id", plainMember.toString())
                    }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/create-cost-center?code=SOMMERFEST-2029&name=X")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("deactivateCostCenter sets active=false; unknown id NotFound; non-treasury forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-deactivate@example.org", AccountRole.TREASURER)
                val plainMember = createTestMember("acct-plain-cc-deactivate@example.org", AccountRole.MEMBER)
                val costCenter = createCostCenterDirect("WINTERHILFE-2027")

                val forbidden =
                    client.post("/test/deactivate-cost-center/$costCenter") { header("X-Member-Id", plainMember.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val deactivated =
                    client.post("/test/deactivate-cost-center/$costCenter") { header("X-Member-Id", treasurer.toString()) }
                deactivated.status shouldBe HttpStatusCode.OK
                deactivated.bodyAsText().split(":")[2] shouldBe "false"

                val notFound =
                    client.post("/test/deactivate-cost-center/${Uuid.random()}") { header("X-Member-Id", treasurer.toString()) }
                notFound.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("listCostCenters filters by activeOnly; BOARD may read, plain MEMBER is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-list@example.org", AccountRole.TREASURER)
                val board = createTestMember("acct-board-cc-list@example.org", AccountRole.BOARD)
                val plainMember = createTestMember("acct-plain-cc-list@example.org", AccountRole.MEMBER)
                val active = createCostCenterDirect("AKTIV-2027")
                val inactive = createCostCenterDirect("INAKTIV-2027", active = false)

                val activeOnly =
                    client.get("/test/list-cost-centers") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                activeOnly.contains(active.toString()) shouldBe true
                activeOnly.contains(inactive.toString()) shouldBe false

                val all =
                    client
                        .get("/test/list-cost-centers?activeOnly=false") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                all.contains(active.toString()) shouldBe true
                all.contains(inactive.toString()) shouldBe true

                client
                    .get("/test/list-cost-centers") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/list-cost-centers") { header("X-Member-Id", plainMember.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("postJournalEntry: cost center is optional -- a mix of tagged and untagged postings round-trips correctly") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-roundtrip@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0980", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4030", LedgerAccountType.INCOME, accountClass = 4)
                val costCenter = createCostCenterDirect("PROJEKT-ROUNDTRIP")

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("45.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = costCenter.toString(),
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("45.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            // No cost center -- the common case.
                        ),
                    )
                val posted =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 9, 1), "Projekt-Buchung", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                posted.status shouldBe HttpStatusCode.OK
                val entryId = posted.bodyAsText().split(":")[0]

                val fetched =
                    client
                        .get("/test/get-entry-cost-centers/$entryId") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val lines =
                    fetched.split("|").associate {
                        val p = it.split(":")
                        p[0] to p.drop(1)
                    }
                lines.getValue(kasse.toString()) shouldBe
                    listOf(costCenter.toString(), "PROJEKT-ROUNDTRIP", "Testkostenstelle PROJEKT-ROUNDTRIP")
                lines.getValue(beitraege.toString()) shouldBe listOf("null", "null", "null")
            }
        }

        test("postJournalEntry referencing a nonexistent costCenterId is rejected with NotFound; nothing persisted") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-missing@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0981", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4031", LedgerAccountType.INCOME, accountClass = 4)
                val countBefore = transaction { JournalEntryTable.selectAll().count() }

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = Uuid.random().toString(),
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 9, 2), "Unbekannte-Kostenstelle", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.NotFound

                val countAfter = transaction { JournalEntryTable.selectAll().count() }
                countAfter shouldBe countBefore
            }
        }

        test("postJournalEntry referencing a deactivated costCenter is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-inactive@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0982", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4032", LedgerAccountType.INCOME, accountClass = 4)
                val inactiveCostCenter = createCostCenterDirect("INAKTIV-BUCHUNG", active = false)

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = inactiveCostCenter.toString(),
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("10.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 9, 3), "Inaktive-Kostenstelle", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "saveDraftEntry allows a deactivated costCenter (no active check at draft-save time); " +
                "postDraftEntry rejects it once posting is attempted",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-draft@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0983", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4033", LedgerAccountType.INCOME, accountClass = 4)
                val inactiveCostCenter = createCostCenterDirect("ENTWURF-INAKTIV", active = false)

                val postings =
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("15.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = inactiveCostCenter.toString(),
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("15.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    )
                val draftResponse =
                    client.post("/test/save-draft?${entryParams(LocalDate(2026, 9, 4), "Entwurf-Kostenstelle", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                draftResponse.status shouldBe HttpStatusCode.OK
                val entryId = draftResponse.bodyAsText().split(":")[0]

                val postAttempt = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                postAttempt.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "getCostCenterReport: per-cost-center totals, an untouched cost center is absent (not zero-filled), " +
                "unassigned bucket + costCenters reconciles with getIncomeStatement, sorted by code",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-cc-report@example.org", AccountRole.TREASURER)
                val board = createTestMember("acct-board-cc-report@example.org", AccountRole.BOARD)
                val plainMember = createTestMember("acct-plain-cc-report@example.org", AccountRole.MEMBER)
                val kasse = createLedgerAccount("0984", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("4034", LedgerAccountType.INCOME, accountClass = 4)
                val miete = createLedgerAccount("6340", LedgerAccountType.EXPENSE, accountClass = 6)
                val sommerfest = createCostCenterDirect("Z-SOMMERFEST-2044")
                val winterhilfe = createCostCenterDirect("A-WINTERHILFE-2044")
                // Never referenced by any posting -- must be absent from the report, not zero-filled.
                createCostCenterDirect("UNBENUTZT-2044")

                suspend fun post(
                    date: LocalDate,
                    description: String,
                    postings: List<PostingInput>,
                ) {
                    client.post("/test/post-entry?${entryParams(date, description, postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                // Distinct far-future year, same date-range-isolation idiom as the other report tests.
                // sommerfest: 100 income.
                post(
                    LocalDate(2044, 3, 1),
                    "Sommerfest-Einnahme",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = sommerfest.toString(),
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("100.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = sommerfest.toString(),
                        ),
                    ),
                )
                // winterhilfe: 50 income, 20 expense.
                post(
                    LocalDate(2044, 3, 2),
                    "Winterhilfe-Spende",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("50.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = winterhilfe.toString(),
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("50.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = winterhilfe.toString(),
                        ),
                    ),
                )
                post(
                    LocalDate(2044, 3, 3),
                    "Winterhilfe-Material",
                    listOf(
                        PostingInput(
                            miete.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("20.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = winterhilfe.toString(),
                        ),
                        PostingInput(
                            kasse.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("20.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                            costCenterId = winterhilfe.toString(),
                        ),
                    ),
                )
                // Unassigned: 30 income, no cost center -- the common case.
                post(
                    LocalDate(2044, 3, 4),
                    "Mitgliedsbeitrag",
                    listOf(
                        PostingInput(
                            kasse.toString(),
                            PostingSide.DEBIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            beitraege.toString(),
                            PostingSide.CREDIT,
                            BigDecimal("30.00"),
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
                // DRAFT in range, tagged with a cost center -- must not contribute.
                client.post(
                    "/test/save-draft?${
                        entryParams(
                            LocalDate(2044, 3, 5),
                            "Entwurf-Kostenstellenbericht",
                            listOf(
                                PostingInput(
                                    beitraege.toString(),
                                    PostingSide.CREDIT,
                                    BigDecimal("999.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    costCenterId = sommerfest.toString(),
                                ),
                            ),
                        )
                    }",
                ) { header("X-Member-Id", treasurer.toString()) }

                val response =
                    client
                        .get("/test/cost-center-report?from=2044-03-01&to=2044-03-31") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                val (perCostCenterRaw, unassignedRaw, overallRaw) = response.split("#")
                val rows = perCostCenterRaw.split(";")
                // UNBENUTZT-2044 never appears -- absent, not zero-filled.
                rows.none { it.startsWith("UNBENUTZT-2044:") } shouldBe true
                // Sorted by code: "A-WINTERHILFE-2044" before "Z-SOMMERFEST-2044".
                rows.map { it.split(":")[0] } shouldBe listOf("A-WINTERHILFE-2044", "Z-SOMMERFEST-2044")

                val winterhilfeParts = rows[0].split(":")
                winterhilfeParts[1] shouldBe "50.00"
                winterhilfeParts[2] shouldBe "20.00"
                winterhilfeParts[3] shouldBe "30.00"

                val sommerfestParts = rows[1].split(":")
                sommerfestParts[1] shouldBe "100.00"
                sommerfestParts[2] shouldBe "0"
                sommerfestParts[3] shouldBe "100.00"

                val unassignedParts = unassignedRaw.split(":")
                unassignedParts[0] shouldBe "30.00"
                unassignedParts[1] shouldBe "0"
                unassignedParts[2] shouldBe "30.00"

                val overallParts = overallRaw.split(":")
                overallParts[0] shouldBe "180.00" // 100 + 50 + 30
                overallParts[1] shouldBe "20.00"
                overallParts[2] shouldBe "160.00"

                // Reconciles with the plain, cost-center-agnostic getIncomeStatement for the same window.
                val incomeStatement =
                    client
                        .get("/test/income-statement?from=2044-03-01&to=2044-03-31") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                        .split(":")
                incomeStatement[0] shouldBe overallParts[0]
                incomeStatement[1] shouldBe overallParts[1]
                incomeStatement[2] shouldBe overallParts[2]

                // Authorization: BOARD may read, plain MEMBER is forbidden, unauthenticated is unauthorized.
                client
                    .get("/test/cost-center-report?from=2044-03-01&to=2044-03-31") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/cost-center-report?from=2044-03-01&to=2044-03-31") {
                        header("X-Member-Id", plainMember.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/cost-center-report?from=2044-03-01&to=2044-03-31")
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })

private fun StatusPagesConfig.installAccountingExceptionHandlers() {
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
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

/** Shared throwaway routes for [AccountingServiceTest] -- string encodings kept deliberately simple/parseable. */
private fun Route.registerAccountingTestRoutes() {
    post("/test/create-ledger-account") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.createLedgerAccount(
                LedgerAccountInput(
                    accountNumber = q["number"]!!,
                    name = q["name"]!!,
                    accountClass = q["class"]!!.toInt(),
                    type = LedgerAccountType.valueOf(q["type"]!!),
                    reserveType = q["reserveType"]?.let { ReserveType.valueOf(it) },
                    isCashRegister = q["isCashRegister"]?.toBoolean() ?: false,
                ),
            )
        call.respondText("${dto.id}:${dto.accountNumber}:${dto.type}:${dto.active}:${dto.reserveType}:${dto.isCashRegister}")
    }
    post("/test/create-cost-center") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.createCostCenter(
                CostCenterInput(
                    code = q["code"]!!,
                    name = q["name"]!!,
                    description = q["description"],
                    active = q["active"]?.toBoolean() ?: true,
                ),
            )
        call.respondText("${dto.id}:${dto.code}:${dto.name}:${dto.active}")
    }
    post("/test/deactivate-cost-center/{id}") {
        val service = AccountingService(call)
        val dto = service.deactivateCostCenter(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.code}:${dto.active}")
    }
    get("/test/list-cost-centers") {
        val service = AccountingService(call)
        val activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: true
        val list = service.listCostCenters(activeOnly)
        call.respondText(list.joinToString(";") { "${it.id}:${it.code}:${it.active}" })
    }
    get("/test/cost-center-report") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.getCostCenterReport(
                from = q["from"]?.let { LocalDate.parse(it) },
                to = LocalDate.parse(q["to"]!!),
            )
        val perCostCenter =
            dto.costCenters.joinToString(";") { "${it.code}:${it.totalIncome}:${it.totalExpense}:${it.result}" }
        call.respondText(
            "$perCostCenter#${dto.unassignedIncome}:${dto.unassignedExpense}:${dto.unassignedResult}" +
                "#${dto.totalIncome}:${dto.totalExpense}:${dto.result}",
        )
    }
    get("/test/get-entry-cost-centers/{id}") {
        val service = AccountingService(call)
        val dto = service.getJournalEntry(call.parameters["id"]!!)
        // Per posting: ledgerAccountId:costCenterId:costCenterCode:costCenterName, pipe-joined.
        val postingCostCenters =
            dto.postings.joinToString("|") { "${it.ledgerAccountId}:${it.costCenterId}:${it.costCenterCode}:${it.costCenterName}" }
        call.respondText(postingCostCenters)
    }
    get("/test/list-ledger-accounts") {
        val service = AccountingService(call)
        val activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: true
        val list = service.listLedgerAccounts(activeOnly)
        call.respondText(list.joinToString(";") { "${it.id}:${it.accountNumber}:${it.active}" })
    }
    post("/test/save-draft") {
        val service = AccountingService(call)
        val dto = service.saveDraftEntry(readJournalEntryInput(call))
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    post("/test/post-entry") {
        val service = AccountingService(call)
        val dto = service.postJournalEntry(readJournalEntryInput(call))
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    post("/test/post-draft/{id}") {
        val service = AccountingService(call)
        val dto = service.postDraftEntry(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    get("/test/get-entry/{id}") {
        val service = AccountingService(call)
        val dto = service.getJournalEntry(call.parameters["id"]!!)
        // Trailing 4th field lists each posting's "side:sphere" pair, pipe-separated, in the
        // order returned -- used by the sphere round-trip test.
        val postingSpheres = dto.postings.joinToString("|") { "${it.side}:${it.sphere}" }
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}:$postingSpheres")
    }
    // V0.4.1 donor-attribution round-trip -- separate route from /test/get-entry so that
    // existing route's response format (and every test asserting on it) never has to change.
    get("/test/get-entry-donor/{id}") {
        val service = AccountingService(call)
        val dto = service.getJournalEntry(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.donorMemberId}:${dto.donorMemberDisplayName}")
    }
    get("/test/list-journal") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val list =
            service.listJournal(
                from = q["from"]?.let { LocalDate.parse(it) },
                to = q["to"]?.let { LocalDate.parse(it) },
                status = q["status"]?.let { JournalEntryStatus.valueOf(it) },
                donorMemberId = q["donorMemberId"],
            )
        call.respondText(list.joinToString(";") { "${it.id}:${it.entryDate}:${it.status}" })
    }
    get("/test/general-ledger/{ledgerAccountId}") {
        val service = AccountingService(call)
        val dto = service.getGeneralLedgerAccount(call.parameters["ledgerAccountId"]!!)
        call.respondText("${dto.ledgerAccountId}:${dto.openingBalance}:${dto.closingBalance}:${dto.lines.size}")
    }
    get("/test/kassenbuch/{ledgerAccountId}") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.getKassenbuch(
                call.parameters["ledgerAccountId"]!!,
                from = q["from"]?.let { LocalDate.parse(it) },
                to = q["to"]?.let { LocalDate.parse(it) },
            )
        // Per-line: kassenbuchNumber:journalEntryId:entryDate:voucherReference:amountIn:amountOut:runningBalance, semicolon-joined.
        val lines =
            dto.lines.joinToString(";") { line ->
                "${line.kassenbuchNumber}:${line.journalEntryId}:${line.entryDate}:${line.voucherReference}:" +
                    "${line.amountIn}:${line.amountOut}:${line.runningBalance}"
            }
        call.respondText("${dto.ledgerAccountId}:${dto.accountNumber}:${dto.openingBalance}:${dto.closingBalance}:${dto.lines.size}#$lines")
    }
    get("/test/income-statement") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto = service.getIncomeStatement(from = q["from"]?.let { LocalDate.parse(it) }, to = LocalDate.parse(q["to"]!!))
        call.respondText("${dto.totalIncome}:${dto.totalExpense}:${dto.result}")
    }
    get("/test/balance-sheet") {
        val service = AccountingService(call)
        val asOf = LocalDate.parse(call.request.queryParameters["asOf"]!!)
        val dto = service.getBalanceSheet(asOf)
        call.respondText("${dto.totalAssets}:${dto.totalEquityAndLiabilities}:${dto.balanced}:${dto.accumulatedResult}")
    }
    get("/test/annual-statement") {
        val service = AccountingService(call)
        val year = call.request.queryParameters["year"]!!.toInt()
        val dto = service.getAnnualFinancialStatement(year)
        call.respondText("${dto.periodEnd}:${dto.periodResult}:${dto.accumulatedResult}")
    }
    get("/test/four-sphere-income-statement") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.getFourSphereIncomeStatement(
                from = q["from"]?.let { LocalDate.parse(it) },
                to = LocalDate.parse(q["to"]!!),
            )
        val perSphere = dto.spheres.joinToString(";") { "${it.sphere}:${it.totalIncome}:${it.totalExpense}:${it.result}" }
        call.respondText("$perSphere#${dto.totalIncome}:${dto.totalExpense}:${dto.result}")
    }
    get("/test/use-of-funds") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto = service.getUseOfFundsStatement(q["from"]!!.toInt(), q["to"]!!.toInt())
        // Per-year rows: fiscalYear:fundsReceived:fundsUsed:fundsAllocatedToReserves:obligation:overdue, semicolon-joined.
        val years =
            dto.years.joinToString(";") { year ->
                "${year.fiscalYear}:${year.fundsReceived}:${year.fundsUsed}:${year.fundsAllocatedToReserves}:" +
                    "${year.timelyUseObligationRemaining}:${year.overdueAmount}"
            }
        // Per-year reserve movements: fiscalYear:reserveType:allocated:closingBalance, pipe-joined across all years/types.
        val reserveMovements =
            dto.years.joinToString("|") { year ->
                year.reserveMovements.joinToString(",") { "${year.fiscalYear}:${it.reserveType}:${it.allocated}:${it.closingBalance}" }
            }
        call.respondText(
            "$years#$reserveMovements#${dto.totalFundsReceived}:${dto.totalFundsUsed}:${dto.totalFundsAllocatedToReserves}:" +
                "${dto.closingTimelyUseObligation}:${dto.closingOverdue}:${dto.timelyUseYears}",
        )
    }
}

private suspend fun readJournalEntryInput(call: ApplicationCall): JournalEntryInput {
    val q = call.request.queryParameters
    val postings =
        (q["postings"] ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .map { entry ->
                val parts = entry.split(":")
                PostingInput(
                    ledgerAccountId = parts[0],
                    side = PostingSide.valueOf(parts[1]),
                    amount = BigDecimal(parts[2]),
                    sphere = GemeinnuetzigkeitSphere.valueOf(parts[3]),
                    costCenterId = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
                )
            }
    return JournalEntryInput(
        entryDate = LocalDate.parse(q["date"]!!),
        description = q["description"]!!,
        voucherReference = q["voucher"],
        postings = postings,
        donorMemberId = q["donorMemberId"],
    )
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpElectionTestData]/[cleanUpSystemicConsensusTestData].
 */
private fun cleanUpAccountingTestData(
    memberIds: List<Uuid>,
    ledgerAccountIds: List<Uuid>,
    costCenterIds: List<Uuid> = emptyList(),
) {
    if (memberIds.isEmpty() && ledgerAccountIds.isEmpty() && costCenterIds.isEmpty()) return
    transaction {
        val journalEntryIds =
            if (memberIds.isNotEmpty()) {
                JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList memberIds }.map { it[JournalEntryTable.id] }
            } else {
                emptyList()
            }
        if (journalEntryIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.ledgerAccountId inList ledgerAccountIds }
        }
        if (costCenterIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.costCenterId inList costCenterIds }
        }
        if (journalEntryIds.isNotEmpty()) {
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList ledgerAccountIds }
        }
        if (costCenterIds.isNotEmpty()) {
            CostCenterTable.deleteWhere { CostCenterTable.id inList costCenterIds }
        }
        if (memberIds.isNotEmpty()) {
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
