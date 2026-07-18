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
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
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

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpAccountingTestData(createdMemberIds, createdLedgerAccountIds) }

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
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun postingsParam(postings: List<PostingInput>): String =
            postings.joinToString(",") { "${it.ledgerAccountId}:${it.side}:${it.amount}:${it.sphere}" }

        fun entryParams(
            date: LocalDate,
            description: String,
            postings: List<PostingInput>,
            voucher: String? = null,
        ): String =
            buildString {
                append("date=$date&description=$description&postings=${postingsParam(postings)}")
                if (voucher != null) append("&voucher=$voucher")
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
                ),
            )
        call.respondText("${dto.id}:${dto.accountNumber}:${dto.type}:${dto.active}:${dto.reserveType}")
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
    get("/test/list-journal") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val list =
            service.listJournal(
                from = q["from"]?.let { LocalDate.parse(it) },
                to = q["to"]?.let { LocalDate.parse(it) },
                status = q["status"]?.let { JournalEntryStatus.valueOf(it) },
            )
        call.respondText(list.joinToString(";") { "${it.id}:${it.entryDate}:${it.status}" })
    }
    get("/test/general-ledger/{ledgerAccountId}") {
        val service = AccountingService(call)
        val dto = service.getGeneralLedgerAccount(call.parameters["ledgerAccountId"]!!)
        call.respondText("${dto.ledgerAccountId}:${dto.openingBalance}:${dto.closingBalance}:${dto.lines.size}")
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
                )
            }
    return JournalEntryInput(
        entryDate = LocalDate.parse(q["date"]!!),
        description = q["description"]!!,
        voucherReference = q["voucher"],
        postings = postings,
    )
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpElectionTestData]/[cleanUpSystemicConsensusTestData].
 */
private fun cleanUpAccountingTestData(
    memberIds: List<Uuid>,
    ledgerAccountIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && ledgerAccountIds.isEmpty()) return
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
        if (journalEntryIds.isNotEmpty()) {
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList ledgerAccountIds }
        }
        if (memberIds.isNotEmpty()) {
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
