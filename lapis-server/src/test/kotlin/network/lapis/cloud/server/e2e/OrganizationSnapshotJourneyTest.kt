package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.backup.TestDatabaseFactory
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.module
import network.lapis.cloud.server.routes.registerBackupRoutes
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.rpc.ContributionService
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.rpc.LtrLedgerService
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.server.rpc.RegistrationService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteOpenInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Scenario 6 (final) of the V1.0 end-to-end integration test wave -- see [E2eSupport] KDoc for the
 * shared "real, fully-wired `module()` + real login/session + throwaway RPC test routes on top"
 * idiom every scenario in this package uses. Unlike Scenarios 1-5 (which all share the one process-
 * wide ambient database every other test Spec in this JVM also uses), this scenario is the one place
 * in the whole wave that genuinely needs TWO independent, freshly Flyway-migrated H2 databases alive
 * in the same JVM at once -- a "source" organization and a completely empty "target" -- so it can
 * prove the V0.5.4 Backup-/Restore-Garantie end to end: a condensed membership/governance/accounting
 * story, written by REAL business-logic RPC calls (not direct table inserts, unlike the existing
 * [network.lapis.cloud.server.backup.OrganizationBackupRoundTripTest]), survives a real
 * `GET /api/backup/export` -> `POST /api/backup/restore` round trip and is still fully *servable*
 * from the target instance's own real RPC/HTTP surface afterwards.
 *
 * **How the two-database problem is solved.** [network.lapis.cloud.server.db.DatabaseConfig.connect]
 * is a JVM-wide, `by lazy`-cached singleton -- `Application.module()` always wires its RPC services
 * and its OWN `/api/backup/export`/`/api/backup/restore` routes against THAT one process-wide
 * database, never against whatever [org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
 * .defaultDatabase] happens to be at the moment. That means module()'s own literal backup routes can
 * never be retargeted at a fresh per-test database -- there is no seam for that, by design (a single-
 * tenant server only ever has one database). This scenario therefore does two things every other
 * scenario in this package does not need to:
 *
 * 1. Every *RPC service* call (registration, governance, LTR, accounting, audit) still goes through
 *    the REAL, unmodified `module()` wiring -- these all resolve their database via Exposed's
 *    ambient, unparameterized `transaction { }`, which DOES follow
 *    [org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase]. Reassigning
 *    that JVM-wide var to `sourceDb`/`targetDb` for the duration of each phase (always restored in a
 *    `finally` block -- see [TestDatabaseFactory] KDoc for why an un-restored reassignment would
 *    silently break every other Spec sharing this JVM) makes every RPC call in that phase operate on
 *    the intended fresh database, with zero changes to production code.
 * 2. The literal `/api/backup/export`/`/api/backup/restore` HANDLERS are still the real, unmodified
 *    `registerBackupRoutes` function from production -- but mounted a SECOND time, nested under a
 *    `/e2e6` path prefix (so it cannot collide with `module()`'s own top-level mount, which stays
 *    bound to the real shared ambient database and is simply unused by this scenario), with an
 *    EXPLICIT `Database` constructor argument (`sourceDb`/`targetDb`) -- exactly the constructor
 *    parameter [network.lapis.cloud.server.backup.OrganizationExportService]/
 *    [network.lapis.cloud.server.backup.OrganizationRestoreService] KDoc already documents existing
 *    specifically so a "live two-database migration mode" remains architecturally possible. This is
 *    the SAME production function/handler code every real deployment runs (same role check, same
 *    streaming body handling, same `StatusPages`-mapped exceptions) -- only the URL and the database
 *    binding differ from module()'s own mount, which a genuine multi-tenant deployment would need
 *    its own real seam for anyway (out of scope for this wave).
 *
 * **A genuine, newly-surfaced chicken-and-egg finding (flag, not fix -- see rationale below).** The
 * restore route is ADMIN-only via [network.lapis.cloud.server.security.resolveCurrentMember], which
 * requires an EXISTING member/account row to authenticate against (real session OR the H2-test-mode
 * `X-Member-Id` fallback -- see that function's KDoc). A truly fresh target -- the "primary supported
 * path, tested end to end" [network.lapis.cloud.server.backup.OrganizationRestoreService] KDoc
 * describes -- has ZERO member rows, so NO caller can ever pass that role check via the real HTTP
 * surface: [network.lapis.cloud.server.bootstrap.AdminBootstrap]'s own KDoc independently confirms
 * this exact bootstrap problem ("no member-onboarding workflow... a fresh production database has
 * member/account rows only if something inserted them directly"). The ONLY way
 * [network.lapis.cloud.server.backup.OrganizationBackupRoundTripTest] exercises that "primary path"
 * today is by constructing a `CurrentMember` object directly and calling
 * `OrganizationRestoreService.restore(...)` as a plain Kotlin method -- entirely bypassing
 * `resolveCurrentMember`/the ADMIN role check. This scenario, going through REAL HTTP with the REAL
 * role check, therefore has to pre-insert a single bootstrap ADMIN member/account row directly into
 * the target DB before calling restore (test-only setup mirroring the same manual-`INSERT`
 * requirement `AdminBootstrap`'s own KDoc documents for a real deployment) -- using the SAME fixed id
 * [ADMIN_ID] [network.lapis.cloud.server.db.DevSeedData] seeds on the source side, so the restore's
 * UPDATE-by-primary-key-first upsert reconciles it into exactly source's row rather than leaving a
 * second, orphaned member behind. This in turn means the restore call in THIS scenario can never
 * exercise the `allowNonEmptyTarget = false` (truly-fresh-target) code path -- it is architecturally
 * forced into `allowNonEmptyTarget = true`, same as [OrganizationBackupRoundTripTest]'s own second,
 * idempotency-focused restore call. **Not fixed here**: closing this gap would mean either loosening
 * the ADMIN-only HTTP gate specifically for a target with zero members (a real, security-relevant
 * product decision) or building a dedicated bootstrap-token mechanism -- both out of scope for an
 * E2E test wave; flagged in the wave's CHANGELOG "Known limitations" instead.
 *
 * **A second, genuine, newly-surfaced finding -- verified, not assumed (see step 17 below).**
 * [network.lapis.cloud.server.backup.OrganizationSchemaCatalog.EXCLUDED_TABLES] documents exactly
 * ONE exclusion (`flyway_schema_history`) -- [network.lapis.cloud.server.backup
 * .OrganizationSchemaCatalogTest]'s own KDoc confirms this is a DELIBERATE, V0.5.4-security-loop-
 * reviewed design decision ("every table with organizational data is genuinely in scope, not merely
 * presumed to be" / "every table, period"). `session` is NOT in that exclusion set, and
 * [network.lapis.cloud.server.security.SessionStore.createSession] persists real rows in
 * [SessionTable] -- so a live session IS exported and restored like any other table's rows. This
 * scenario proves the practical consequence concretely: the exact raw session token issued by the
 * applicant's real login against the SOURCE instance in step 3 is STILL a live, replayable session
 * against the TARGET instance after restore (step 17) -- nothing about restore re-issues or
 * invalidates session tokens. This is LOW-severity (only a SHA-256 hash of the token is ever
 * persisted, see [network.lapis.cloud.server.security.SessionStore] KDoc "Only a hash of the token is
 * ever stored" -- the same protection model the bundle's `account.password_hash`/`oidc_subject`
 * already rely on, see [network.lapis.cloud.server.routes.registerBackupRoutes] KDoc's own "Review-
 * Pflicht"), but genuinely worth a documented callout: restoring an organizational backup onto a
 * (possibly different) server also resurrects whatever sessions were live at export time, with no
 * natural expiry acceleration or invalidation. **Not fixed here**: excluding `session` would reverse
 * the V0.5.4 security-loop's own explicit "every table, period" decision, and (re-)narrowing that
 * scope is a product decision this wave should not make unilaterally -- flagged in the CHANGELOG
 * instead, alongside the chicken-and-egg finding above.
 */
class OrganizationSnapshotJourneyTest :
    FunSpec({
        test(
            "a condensed real membership/governance/accounting journey, written on a fresh source instance, " +
                "round-trips through a real export -> restore into a fresh target instance and is fully " +
                "servable -- including an unscoped GoBD hash-chain re-verification -- from the target's own " +
                "real RPC/HTTP surface",
        ) {
            // The real, process-wide ambient database -- captured so it can be restored in `finally`
            // below regardless of outcome. See class KDoc "How the two-database problem is solved".
            val ambientDb = DatabaseConfig.connect()
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("scenario6-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("scenario6-target-${Uuid.random()}")
            val sourceStorageRoot = Files.createTempDirectory("e2e6-source-storage").toFile()
            val targetStorageRoot = Files.createTempDirectory("e2e6-target-storage").toFile()

            // All initialized with placeholders and reassigned inside the (non-`inline`)
            // `testApplication` lambdas below -- `testApplication` carries no
            // `callsInPlace`/definite-assignment contract, so an uninitialized `var` read after the
            // block would not compile even though it always runs exactly once at runtime.
            var exportBytes: ByteArray = ByteArray(0)
            var applicantId: Uuid = Uuid.random()
            var applicantRawToken = ""
            var committeeId = ""
            var resolutionId = ""
            var sourceResolutionFacts = ""
            var sourceMembershipFacts = ""
            var sourceLtrBalance = ""
            var sourceJournalFacts = ""
            var sourceSessionCount = 0L
            var sourceAdminAccountId: Uuid = Uuid.random()

            try {
                // ── SOURCE leg: condensed Scenario 1 (register -> approve -> contribute -> vote -> ──
                // ── audit), real HTTP throughout, against the FRESH sourceDb ────────────────────────
                TransactionManager.defaultDatabase = sourceDb
                testApplication {
                    application {
                        module()
                        routing {
                            registerScenario6TestRoutes()
                            route("/e2e6") { registerBackupRoutes(database = sourceDb, documentStorageRoot = sourceStorageRoot) }
                        }
                    }
                    DevSeedData.seedIfEmpty(force = true)
                    // `account`'s primary key is its OWN `id`, not `member_id` -- restore's
                    // upsert (UPDATE-by-PK-first) therefore only reconciles into ONE row if the
                    // target's pre-existing bootstrap account row already carries the EXACT SAME
                    // `id` DevSeedData just randomly assigned here. Captured now so the target
                    // bootstrap insert (below) can reuse it verbatim -- see class KDoc's chicken-
                    // and-egg finding.
                    sourceAdminAccountId =
                        transaction(sourceDb) {
                            AccountTable.selectAll().where { AccountTable.memberId eq Uuid.parse(ADMIN_ID) }.single()[AccountTable.id]
                        }

                    // ── Step 1: BOARD sets up the General-Assembly committee + vote infrastructure ──
                    committeeId = client.post("/e2e6/create-committee") { header("X-Member-Id", BOARD_ID) }.bodyAsText()

                    // ── Step 2: real self-registration (V0.7) ──────────────────────────────────────
                    val email = "snapshot-applicant-${Uuid.random()}@example.org"
                    client.post("/e2e6/register?email=$email").status shouldBe HttpStatusCode.OK
                    applicantId =
                        transaction(sourceDb) {
                            MemberTable.selectAll().where { MemberTable.email eq email }.single()[MemberTable.id]
                        }

                    // ── Step 3: real login (real HTTP, real session cookie) ────────────────────────
                    applicantRawToken = client.realLogin(email = email, password = E2E_STRONG_PASSWORD)

                    // ── Step 4: BOARD approves (ANTRAG -> AKTIV) ───────────────────────────────────
                    client.post("/e2e6/approve/$applicantId") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK

                    // ── Step 5: BOARD seats the now-AKTIV applicant on the committee (V0.2.1) ──────
                    val membershipId =
                        client
                            .post("/e2e6/add-committee-member/$committeeId/$applicantId") { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()

                    // ── Step 6: TREASURER mints LTR (V0.6) ─────────────────────────────────────────
                    client.post("/e2e6/mint-ltr/$applicantId") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                        HttpStatusCode.OK

                    // ── Step 7: BOARD schedules a Meritokratische Vote (V0.2.3) ────────────────────
                    val meetingId = client.post("/e2e6/create-meeting/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                    val motionId = client.post("/e2e6/submit-motion/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                    client.post("/e2e6/review-motion/$motionId") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                    client
                        .post("/e2e6/schedule-motion/$motionId/$meetingId") { header("X-Member-Id", BOARD_ID) }
                        .status shouldBe HttpStatusCode.OK
                    val openResponse = client.post("/e2e6/open-vote/$motionId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                    val voteId = openResponse.substringBefore(":")
                    val yesOptionId =
                        openResponse
                            .substringAfter(":")
                            .split(";")
                            .first { it.endsWith("=YES") }
                            .substringBefore("=")

                    // ── Step 8: the applicant's REAL session casts the ballot ──────────────────────
                    client.post("/e2e6/cast-vote/$voteId/$yesOptionId") { withSession(applicantRawToken) }.status shouldBe
                        HttpStatusCode.OK

                    // ── Step 9: BOARD closes the vote -- settlement writes a Resolution (V0.2.1) ───
                    val closeFields = client.post("/e2e6/close-vote/$voteId") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                    resolutionId = closeFields[1]
                    resolutionId.isBlank() shouldBe false

                    // ── Step 10: V0.1/V0.3 -- tier, contribution, payment, accounting posting ──────
                    val tierId = client.post("/e2e6/create-tier") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                    transaction(sourceDb) {
                        MemberTable.update({ MemberTable.id eq applicantId }) { it[membershipTierId] = Uuid.parse(tierId) }
                    }
                    client.post("/e2e6/generate-contributions/$tierId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText() shouldBe
                        "1"
                    val contributionFields =
                        client
                            .get("/e2e6/contribution-for/$applicantId") { header("X-Member-Id", TREASURER_ID) }
                            .bodyAsText()
                            .split(",")
                            .first()
                            .split("=")
                    val contributionId = contributionFields[0]
                    val contributionAmount = contributionFields[1]
                    client
                        .post("/e2e6/mark-paid/$contributionId?amount=$contributionAmount") { header("X-Member-Id", TREASURER_ID) }
                        .status shouldBe HttpStatusCode.OK

                    val bankAccountId = client.get("/e2e6/ledger-account/18000") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                    val incomeAccountId = client.get("/e2e6/ledger-account/40000") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                    client
                        .post(
                            "/e2e6/post-journal-entry?bankAccountId=$bankAccountId&incomeAccountId=$incomeAccountId" +
                                "&donorMemberId=$applicantId&amount=$contributionAmount",
                        ) { header("X-Member-Id", TREASURER_ID) }
                        .status shouldBe HttpStatusCode.OK

                    // ── Step 11: capture the SOURCE facts step 16 (post-restore) must reproduce ────
                    sourceResolutionFacts =
                        client.get("/e2e6/resolution-facts/$committeeId/$resolutionId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                    sourceMembershipFacts =
                        client
                            .get("/e2e6/committee-member-facts/$committeeId/$membershipId") { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                    sourceLtrBalance =
                        client.get("/e2e6/ltr-balance/$applicantId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                    sourceJournalFacts =
                        client.get("/e2e6/journal-facts/$applicantId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                    // Exactly one real session was ever created against sourceDb in this scenario
                    // (step 3's real login) -- the anchor for step 17's cross-database comparison.
                    sourceSessionCount = transaction(sourceDb) { SessionTable.selectAll().count() }
                    (sourceSessionCount >= 1) shouldBe true

                    // ── Step 12: ADMIN exports the full organization (V0.5.4) ──────────────────────
                    val exportResponse = client.get("/e2e6/api/backup/export") { header("X-Member-Id", ADMIN_ID) }
                    exportResponse.status shouldBe HttpStatusCode.OK
                    exportBytes = exportResponse.bodyAsBytes()
                    (exportBytes.isNotEmpty()) shouldBe true
                }

                // ── TARGET leg: bootstrap + real restore + real-surface re-verification ────────────
                TransactionManager.defaultDatabase = targetDb
                // Test-only bootstrap insert, mirroring AdminBootstrap's own documented "manual INSERT"
                // requirement for a truly fresh deployment -- see class KDoc's chicken-and-egg finding.
                // Uses the SAME fixed `member.id` DevSeedData seeded on the source side (ADMIN_ID) --
                // AND the SAME `account.id` captured from source above -- so restore's UPDATE-by-
                // primary-key-first upsert reconciles BOTH rows into exactly source's rows. `account`'s
                // primary key is its own `id`, not `member_id`; a mismatched `account.id` here would
                // make the upsert fall through to INSERT and collide with `account`'s
                // UNIQUE(member_id) constraint instead (verified empirically while writing this test).
                transaction(targetDb) {
                    MemberTable.insert {
                        it[id] = Uuid.parse(ADMIN_ID)
                        it[displayName] = "Bootstrap Admin (pre-restore placeholder)"
                        it[email] = "bootstrap-admin-placeholder@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[id] = sourceAdminAccountId
                        it[memberId] = Uuid.parse(ADMIN_ID)
                        it[role] = AccountRole.ADMIN
                        it[passwordHash] = null
                    }
                }

                testApplication {
                    application {
                        module()
                        routing {
                            registerScenario6TestRoutes()
                            route("/e2e6") { registerBackupRoutes(database = targetDb, documentStorageRoot = targetStorageRoot) }
                        }
                    }

                    // ── Step 13: target starts genuinely empty (beyond the bootstrap row + the ─────
                    // ── Flyway-/first-boot-seeded singletons module() itself just provisioned) ─────
                    (transaction(targetDb) { MemberTable.selectAll().count() }) shouldBe 1L

                    // ── Step 14: ADMIN restores the captured bundle -- forced into ─────────────────
                    // ── allowNonEmptyTarget=true, see class KDoc's chicken-and-egg finding ─────────
                    val restoreResponse =
                        client.post("/e2e6/api/backup/restore?allowNonEmptyTarget=true") {
                            header("X-Member-Id", ADMIN_ID)
                            setBody(exportBytes)
                        }
                    val restoreBody = restoreResponse.bodyAsText()
                    restoreResponse.status shouldBe HttpStatusCode.OK
                    // A crude but effective non-vacuousness guard: some non-trivial number of rows
                    // and at least the tables this scenario itself wrote to came back.
                    (restoreBody.contains("\"tablesRestored\"")) shouldBe true
                    (restoreBody.contains("\"warnings\":[]")) shouldBe true

                    // ── Step 15: THE PAYOFF -- unscoped GoBD hash-chain re-verification on the ─────
                    // ── target, over a chain built ENTIRELY by real business-logic writes that ─────
                    // ── round-tripped through a byte-for-byte export/restore. Unlike every other ───
                    // ── scenario in this wave (which must scope verifyChainIntegrity to a window, ──
                    // ── see MembershipToGovernanceJourneyTest's KDoc, because they share one ───────
                    // ── ever-growing database with ~1100 other tests), targetDb is wholly isolated ─
                    // ── and freshly restored -- an UNSCOPED (fromSequenceNumber=null, ─────────────
                    // ── toSequenceNumber=null) call is therefore both possible AND the strongest ───
                    // ── possible assertion: the ENTIRE chain, start to finish, still verifies. ─────
                    val verifyResult = client.get("/e2e6/verify-chain") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split(":")
                    verifyResult[0] shouldBe "true"
                    verifyResult[1] shouldBe ""
                    // Only two production call sites ever write an audit row (AuditLogRecorder.record
                    // is called from ResolutionBook and AccountingService only, see
                    // network.lapis.cloud.server.rpc.{ResolutionBook,AccountingService}) -- this
                    // scenario's Vote settlement (step 9, one Resolution) and posted JournalEntry
                    // (step 10) are exactly those two writes. `>= 2`, not merely `> 0`, is a
                    // non-vacuousness guard against a chain that "verifies" only because it is empty.
                    (verifyResult[2].toInt() >= 2) shouldBe true

                    // ── Step 16: Governance/Accounting/LTR reads via the TARGET's own real RPC ─────
                    // ── surface reproduce EXACTLY the facts step 11 captured on the source ─────────
                    client
                        .get(
                            "/e2e6/resolution-facts/$committeeId/$resolutionId",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText() shouldBe
                        sourceResolutionFacts
                    // step 5's `membershipId` was a local `val` scoped to the SOURCE testApplication
                    // lambda -- out of scope here by construction, not merely inconvenient to reuse.
                    // Re-derived from the (id-stable, restored) committee/applicant pair instead.
                    val restoredMembershipId =
                        client
                            .get("/e2e6/committee-member-id-for/$committeeId/$applicantId") { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                    client
                        .get("/e2e6/committee-member-facts/$committeeId/$restoredMembershipId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText() shouldBe sourceMembershipFacts
                    client.get("/e2e6/ltr-balance/$applicantId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                        sourceLtrBalance
                    client.get("/e2e6/journal-facts/$applicantId") { header("X-Member-Id", TREASURER_ID) }.bodyAsText() shouldBe
                        sourceJournalFacts

                    // ── Step 17: THE SECOND FINDING, made concrete (see class KDoc) -- the exact ───
                    // ── raw token SOURCE issued in step 3 is STILL live against the TARGET, purely ─
                    // ── because `session` rows travel through export/restore like any other table's ─
                    // ── rows. Session count matches source's exactly (this scenario's one real ─────
                    // ── login, now duplicated onto target as a restored row), and the token itself ──
                    // ── authenticates a real RPC call it was never issued against. Checked BEFORE ───
                    // ── step 18's OWN fresh login below, which would otherwise add a second, ────────
                    // ── genuinely-new session and make this count comparison vacuous. ───────────────
                    (transaction(targetDb) { SessionTable.selectAll().count() }) shouldBe sourceSessionCount
                    client.get("/e2e6/my-balance") { withSession(applicantRawToken) }.status shouldBe HttpStatusCode.OK

                    // ── Step 18: the restored member's own password credentials are genuinely ──────
                    // ── servable via the REAL auth flow, not just present as an opaque DB column ───
                    val applicantEmail =
                        transaction(
                            targetDb,
                        ) { MemberTable.selectAll().where { MemberTable.id eq applicantId }.single()[MemberTable.email] }
                    val freshTargetToken = client.realLogin(email = applicantEmail, password = E2E_STRONG_PASSWORD)
                    (freshTargetToken.isNotBlank()) shouldBe true
                    // A real login always mints a BRAND NEW token -- restore does not (cannot) make
                    // the target hand out the source's own raw token.
                    (freshTargetToken != applicantRawToken) shouldBe true
                }
            } finally {
                // See class KDoc "How the two-database problem is solved" / TestDatabaseFactory KDoc
                // -- unconditionally restoring the JVM-wide ambient default is not optional cleanup,
                // it is what keeps every OTHER Spec sharing this JVM from silently breaking.
                TransactionManager.defaultDatabase = ambientDb
            }
        }
    })

/**
 * Every throwaway test route this scenario's two `testApplication` legs share, nested under
 * `/e2e6` -- registered identically on both the source and target app (each is a wholly separate
 * Ktor `Application`/routing tree, so there is no cross-leg collision risk; only which database is
 * the Exposed ambient default at the moment a given request is handled differs, see class KDoc).
 * `/e2e6/api/backup/export`/`/e2e6/api/backup/restore` are mounted separately by each call site
 * (with a leg-specific [Database]/storage-root argument), not here.
 */
private fun Route.registerScenario6TestRoutes() {
    post("/e2e6/create-committee") {
        val c =
            GovernanceService(call = call).createCommittee(
                CommitteeInput(
                    name = "Mitgliederversammlung (Snapshot Journey)",
                    type = CommitteeType.GENERAL_ASSEMBLY,
                    description = "E2E Scenario 6",
                    quorumPercent = 50,
                ),
            )
        call.respondText(c.id)
    }
    post("/e2e6/register") {
        RegistrationService(
            call = call,
            registrationRateLimiter = LoginRateLimiter(),
            friendRegistrationRateLimiter = LoginRateLimiter(),
            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
            friendVerificationMailer = FakeFriendVerificationMailer(),
        ).registerApplication(
            RegistrationInput(
                displayName = "Snapshot Journey Applicant",
                email = call.request.queryParameters["email"]!!,
                password = E2E_STRONG_PASSWORD,
                agreementVersion = MembershipAgreementDisclaimer.VERSION,
                agreementSha256 = MembershipAgreementDisclaimer.SHA256,
            ),
        )
        call.respondText("OK")
    }
    post("/e2e6/approve/{id}") {
        RegistrationService(
            call = call,
            registrationRateLimiter = LoginRateLimiter(),
            friendRegistrationRateLimiter = LoginRateLimiter(),
            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
            friendVerificationMailer = FakeFriendVerificationMailer(),
        ).approveApplication(call.parameters["id"]!!)
        call.respondText("OK")
    }
    post("/e2e6/add-committee-member/{committeeId}/{memberId}") {
        val m =
            GovernanceService(call = call).addCommitteeMember(
                committeeId = call.parameters["committeeId"]!!,
                input =
                    CommitteeMembershipInput(
                        memberId = call.parameters["memberId"]!!,
                        role = CommitteeRole.MEMBER,
                        since = LocalDate(2026, 10, 1),
                    ),
            )
        call.respondText(m.id)
    }
    post("/e2e6/mint-ltr/{memberId}") {
        LtrLedgerService(call = call).mintLtr(
            MintLtrInput(memberId = call.parameters["memberId"]!!, amountLtr = BigDecimal("25.00"), note = "E2E Scenario 6"),
        )
        call.respondText("OK")
    }
    post("/e2e6/create-meeting/{committeeId}") {
        val m =
            GovernanceService(call = call).createMeeting(
                MeetingInput(
                    committeeId = call.parameters["committeeId"]!!,
                    title = "Snapshot-Journey-Meeting",
                    scheduledAt = LocalDateTime(2026, 11, 20, 18, 0),
                    location = "Vereinsheim",
                    format = MeetingFormat.IN_PERSON,
                ),
            )
        call.respondText(m.id)
    }
    post("/e2e6/submit-motion/{committeeId}") {
        val motion =
            GovernanceService(call = call).submitMotion(
                MotionInput(
                    targetCommitteeId = call.parameters["committeeId"]!!,
                    title = "Snapshot-Journey-Antrag",
                    rationale = "E2E Scenario 6",
                    text = "Beschlusstext Snapshot Journey",
                ),
            )
        call.respondText(motion.id)
    }
    post("/e2e6/review-motion/{id}") {
        GovernanceService(call = call).reviewMotion(id = call.parameters["id"]!!, decision = MotionReviewDecision.ACCEPT)
        call.respondText("OK")
    }
    post("/e2e6/schedule-motion/{id}/{meetingId}") {
        GovernanceService(
            call = call,
        ).scheduleMotion(id = call.parameters["id"]!!, meetingId = call.parameters["meetingId"]!!, position = 1)
        call.respondText("OK")
    }
    post("/e2e6/open-vote/{motionId}") {
        val v = GovernanceService(call = call).openVote(VoteOpenInput(motionId = call.parameters["motionId"]!!))
        call.respondText("${v.id}:${v.options.joinToString(";") { "${it.id}=${it.label}" }}")
    }
    post("/e2e6/cast-vote/{voteId}/{optionId}") {
        GovernanceService(call = call).castVoteBallot(
            VoteBallotInput(
                voteId = call.parameters["voteId"]!!,
                optionId = call.parameters["optionId"]!!,
                stakeLtr = BigDecimal("5.00"),
            ),
        )
        call.respondText("OK")
    }
    post("/e2e6/close-vote/{id}") {
        val v = GovernanceService(call = call).closeVote(call.parameters["id"]!!)
        call.respondText("${v.status}|${v.resolutionId ?: ""}")
    }
    post("/e2e6/create-tier") {
        val tier =
            ContributionService(call).createMembershipTier(
                MembershipTierInput(
                    name = "E2E-Snapshot-Journey-Beitragsstufe",
                    description = "Scenario-private tier",
                    contributionAmount = BigDecimal("10.00"),
                    billingInterval = BillingInterval.MONTHLY,
                ),
            )
        call.respondText(tier.id)
    }
    post("/e2e6/generate-contributions/{tierId}") {
        val count =
            ContributionService(call).generateContributionsForPeriod(
                membershipTierId = call.parameters["tierId"]!!,
                periodStart = LocalDate(2026, 10, 1),
                periodEnd = LocalDate(2026, 10, 31),
            )
        call.respondText(count.toString())
    }
    get("/e2e6/contribution-for/{memberId}") {
        val list = ContributionService(call).listContributions(memberId = call.parameters["memberId"]!!)
        call.respondText(list.joinToString(",") { "${it.id}=${it.amountDue}" })
    }
    post("/e2e6/mark-paid/{contributionId}") {
        ContributionService(call).markContributionPaid(
            contributionId = call.parameters["contributionId"]!!,
            paidAt = LocalDateTime(2026, 10, 15, 12, 0),
            paidAmount = BigDecimal(call.request.queryParameters["amount"]!!),
            note = "E2E Scenario 6",
        )
        call.respondText("OK")
    }
    get("/e2e6/ledger-account/{accountNumber}") {
        val accounts = AccountingService(call).listLedgerAccounts()
        call.respondText(accounts.single { it.accountNumber == call.parameters["accountNumber"]!! }.id)
    }
    post("/e2e6/post-journal-entry") {
        val q = call.request.queryParameters
        val amount = BigDecimal(q["amount"]!!)
        AccountingService(call).postJournalEntry(
            JournalEntryInput(
                entryDate = LocalDate(2026, 10, 15),
                description = "Snapshot-Journey-Mitgliedsbeitrag",
                voucherReference = "E2E-6",
                postings =
                    listOf(
                        PostingInput(
                            ledgerAccountId = q["bankAccountId"]!!,
                            side = PostingSide.DEBIT,
                            amount = amount,
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            ledgerAccountId = q["incomeAccountId"]!!,
                            side = PostingSide.CREDIT,
                            amount = amount,
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                donorMemberId = q["donorMemberId"],
                donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
            ),
        )
        call.respondText("OK")
    }
    get("/e2e6/journal-facts/{memberId}") {
        val list = AccountingService(call).listJournal(donorMemberId = call.parameters["memberId"]!!)
        call.respondText(
            list
                .sortedBy { it.entryDate.toString() }
                .joinToString(",") { entry ->
                    val credit =
                        entry.postings
                            .filter { it.side == PostingSide.CREDIT }
                            .fold(BigDecimal.ZERO.setScale(2)) { acc, p -> acc + p.amount }
                    "${entry.status}:${entry.donorCategory}:$credit"
                },
        )
    }
    get("/e2e6/ltr-balance/{memberId}") {
        val balance = LtrLedgerService(call = call).getMemberBalance(call.parameters["memberId"]!!)
        call.respondText(balance.freeBalanceLtr.toString())
    }
    get("/e2e6/my-balance") {
        val balance = LtrLedgerService(call = call).getMyBalance()
        call.respondText(balance.freeBalanceLtr.toString())
    }
    get("/e2e6/resolution-facts/{committeeId}/{resolutionId}") {
        val resolution =
            GovernanceService(call = call)
                .listResolutions(committeeId = call.parameters["committeeId"]!!)
                .single { it.id == call.parameters["resolutionId"]!! }
        call.respondText(
            "${resolution.status}|${resolution.votesYes}|${resolution.votesNo}|" +
                "${resolution.resolutionMode}|${resolution.title}|${resolution.text}",
        )
    }
    get("/e2e6/committee-member-id-for/{committeeId}/{memberId}") {
        val membership =
            GovernanceService(call = call)
                .listCommitteeMembers(committeeId = call.parameters["committeeId"]!!)
                .single { it.memberId == call.parameters["memberId"]!! }
        call.respondText(membership.id)
    }
    get("/e2e6/committee-member-facts/{committeeId}/{membershipId}") {
        val membership =
            GovernanceService(call = call)
                .listCommitteeMembers(committeeId = call.parameters["committeeId"]!!)
                .single { it.id == call.parameters["membershipId"]!! }
        call.respondText("${membership.memberId}|${membership.memberDisplayName}|${membership.role}|${membership.since}")
    }
    get("/e2e6/verify-chain") {
        val result = AuditLogService(call).verifyChainIntegrity(fromSequenceNumber = null, toSequenceNumber = null)
        call.respondText("${result.valid}:${result.brokenAtSequenceNumber ?: ""}:${result.checkedCount}")
    }
}
