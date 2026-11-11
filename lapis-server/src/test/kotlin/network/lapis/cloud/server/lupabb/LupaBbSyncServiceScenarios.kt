package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.generated.LupaBbSyncSuppressionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.LupaBbAuthRejectedException
import network.lapis.cloud.shared.rpc.LupaBbCredentialsMissingException
import network.lapis.cloud.shared.rpc.LupaBbEncryptionKeyMissingException
import network.lapis.cloud.shared.rpc.LupaBbMemberAlreadyLinkedException
import network.lapis.cloud.shared.rpc.LupaBbPreviewExpiredException
import network.lapis.cloud.shared.rpc.LupaBbSyncBusyException
import network.lapis.cloud.shared.rpc.LupaBbSyncRateLimitedException
import network.lapis.cloud.shared.rpc.LupaBbUnavailableException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.226 -- the RPC service end to end against the fake LupaBB, on H2 AND PostgreSQL (claims, row locks, savepoints, the real migration chain). */
abstract class LupaBbSyncServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = LupaBbFixtures()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { LupaBbSuppression.configure(TEST_BOX) }
        afterTest { fx.cleanUp() }
        afterSpec { db.deactivate() }

        fun unique() = "Testfeld${Uuid.random().toString().take(8)}"

        fun server(vararg members: kotlinx.serialization.json.JsonObject): FakeLupaBbServer =
            FakeLupaBbServer().also { it.members = members.toList() }

        fun input(s: FakeLupaBbServer) = LupaBbCredentialsInput(username = s.username, password = s.password, apiKey = s.apiKey)

        test("every method needs the ADMIN role: MEMBER, TREASURER and BOARD get Forbidden and LupaBB is never asked") {
            server().use { fake ->
                fx.storeCredentials(server = fake, actor = fx.admin())
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val calls: List<Pair<String, suspend (io.ktor.server.application.ApplicationCall) -> Any?>> =
                        listOf(
                            "getStatus" to { c -> fx.service(call = c, runtime = rt).getStatus() },
                            "verify" to { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input(fake)) },
                            "remove" to { c -> fx.service(call = c, runtime = rt).removeCredentials() },
                            "load" to { c -> fx.service(call = c, runtime = rt).loadPreview() },
                            "page" to
                                { c ->
                                    fx
                                        .service(
                                            call = c,
                                            runtime = rt,
                                        ).getPreviewPage(token = "t", filter = LupaBbPreviewFilter.ALL, offset = 0, limit = 10)
                                },
                            "apply" to { c -> fx.service(call = c, runtime = rt).applyPreview("t") },
                            "link" to
                                { c ->
                                    fx
                                        .service(
                                            call = c,
                                            runtime = rt,
                                        ).linkMemberToLupaBb(token = "t", personNumber = 1, memberId = Uuid.random().toString())
                                },
                            "applyProtected" to { c ->
                                fx.service(call = c, runtime = rt).applyProtectedRow(token = "t", personNumber = 1)
                            },
                            "search" to
                                { c -> fx.service(call = c, runtime = rt).searchLinkTargets(token = "t", personNumber = 1, query = "abc") },
                            "skip" to { c -> fx.service(call = c, runtime = rt).skipRow(token = "t", personNumber = 1) },
                        )
                    calls.size shouldBe 10
                    for (role in listOf(AccountRole.MEMBER, AccountRole.TREASURER, AccountRole.BOARD)) {
                        val member = fx.member(role = role)
                        for ((name, op) in calls) {
                            (name to attempt(member = member, op = op).exceptionOrNull()).second.shouldBeInstanceOf<ForbiddenException>()
                        }
                    }
                }
                fake.requestCount.get() shouldBe 0
            }
        }

        test("checking credentials stores them sealed; the header is tried first, the query parameter when the header is refused") {
            server().use { fake ->
                fake.acceptHeader = false
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val status = call(member = admin) { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input(fake)) }
                    status.credentialsSet shouldBe true
                    status.setByName shouldBe "Admin admin"
                    val row = transaction { LupaBbSyncConfigTable.selectAll().single() }
                    row[LupaBbSyncConfigTable.apiKeyTransport] shouldBe "QUERY"
                    for (secret in listOf(fake.username, fake.password, fake.apiKey)) {
                        row[LupaBbSyncConfigTable.credentialsCiphertext] shouldNotContain
                            secret
                    }
                    transaction { LupaBbCredentialStore.load(TEST_BOX) }!!.credentials.password shouldBe fake.password
                    // the AAD binds the ciphertext to the config row
                    runCatching {
                        TEST_BOX.open(
                            sealed = row[LupaBbSyncConfigTable.credentialsCiphertext],
                            aad = "other-row",
                        )
                    }.isFailure shouldBe
                        true
                }
            }
        }

        test("wrong credentials: AuthRejected and nothing stored; no key on the server: EncryptionKeyMissing; blank input: BadRequest") {
            server().use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).verifyAndSaveCredentials(
                                LupaBbCredentialsInput(username = fake.username, password = "falsch", apiKey = fake.apiKey),
                            )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<LupaBbAuthRejectedException>()
                    transaction { LupaBbSyncConfigTable.selectAll().count() } shouldBe 0
                    attempt(
                        member = admin,
                    ) { c -> fx.service(call = c, runtime = fx.runtime(server = fake, box = null)).verifyAndSaveCredentials(input(fake)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<LupaBbEncryptionKeyMissingException>()
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).verifyAndSaveCredentials(LupaBbCredentialsInput(username = " ", password = "x", apiKey = "y"))
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).verifyAndSaveCredentials(LupaBbCredentialsInput(username = "u\u0000", password = "x", apiKey = "y"))
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()
                }
            }
        }

        test("checking credentials is rate limited: five attempts per ten minutes and admin") {
            server().use { fake ->
                val rt = fx.runtime(server = fake, limiter = FederationInboxRateLimiter(maxRequests = 5, window = 10.minutes))
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    repeat(5) {
                        attempt(
                            member = admin,
                        ) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).verifyAndSaveCredentials(
                                    LupaBbCredentialsInput(username = fake.username, password = "falsch", apiKey = fake.apiKey),
                                )
                        }.exceptionOrNull()
                            .shouldBeInstanceOf<LupaBbAuthRejectedException>()
                    }
                    attempt(member = admin) { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input(fake)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<RateLimitedException>()
                }
            }
        }

        test("preview then apply end to end: counts, paging, filter, single use, owner bound, expired token") {
            val family = unique()
            server(
                FakeLupaBbServer.member(personNumber = 9_000_000_060, familyName = family),
                FakeLupaBbServer.member(personNumber = 9_000_000_061, familyName = unique(), status = 6),
                FakeLupaBbServer.member(personNumber = 9_000_000_062, familyName = unique(), status = 10),
            ).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val other = fx.admin("other")
                    fx.member(displayName = "Erika $family", personNumber = 9_000_000_061)
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input(fake)) }
                    fx.allowNextFetch()
                    val summary = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    summary.newCount shouldBe 1
                    summary.counts.getValue(LupaBbCategory.SKIPPED) shouldBe 1
                    val page =
                        call(
                            member = admin,
                        ) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.NEW, offset = 0, limit = 50)
                        }
                    page.total shouldBe 1
                    page.rows.single().personNumber shouldBe 9_000_000_060
                    call(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.ALL, offset = 0, limit = 1)
                    }.rows.size shouldBe
                        1
                    // another admin cannot use it, an unknown token neither
                    attempt(
                        member = other,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.ALL, offset = 0, limit = 10)
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<LupaBbPreviewExpiredException>()
                    attempt(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview("unbekannt") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<LupaBbPreviewExpiredException>()
                    val result = call(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview(summary.token) }
                    result.created shouldBe 1
                    (fx.byPersonNumber(9_000_000_060) != null) shouldBe true
                    attempt(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview(summary.token) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<LupaBbPreviewExpiredException>()
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).getStatus() }.lastRun!!.created shouldBe 1
                }
            }
        }

        test("LupaBB is asked at most once a minute; a running claim is Busy; a stale claim is free; a foreign release does not free it") {
            server(FakeLupaBbServer.member(personNumber = 9_000_000_063, familyName = unique())).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    fx.storeCredentials(server = fake, actor = admin)
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).loadPreview()
                    }.exceptionOrNull().shouldBeInstanceOf<LupaBbSyncRateLimitedException>()
                    fx.allowNextFetch()
                    val claim = LupaBbRunGuard.claim(kind = LupaBbRunKind.APPLY, now = ServerClock.now())
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).loadPreview()
                    }.exceptionOrNull().shouldBeInstanceOf<LupaBbSyncBusyException>()
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).removeCredentials()
                    }.exceptionOrNull().shouldBeInstanceOf<LupaBbSyncBusyException>()
                    LupaBbRunGuard.release(Uuid.random()) // fencing: not the holder
                    LupaBbRunGuard.isRunning(ServerClock.now()) shouldBe true
                    // a claim older than ten minutes counts as free
                    val old =
                        ServerClock
                            .now()
                            .toJavaLocalDateTime()
                            .minusMinutes(11)
                            .toKotlinLocalDateTime()
                    transaction { LupaBbSyncConfigTable.update({ LupaBbSyncConfigTable.id eq 1 }) { it[runningSince] = old } }
                    LupaBbRunGuard.isRunning(ServerClock.now()) shouldBe false
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    // the old holder cannot release the new claim either, and releasing its own is harmless
                    LupaBbRunGuard.release(claim)
                }
            }
        }

        test("parallel claims: exactly one wins") {
            server().use { fake ->
                val admin = fx.admin()
                fx.storeCredentials(server = fake, actor = admin)
                val outcomes =
                    withContext(Dispatchers.IO) {
                        (1..6)
                            .map {
                                async {
                                    runCatching {
                                        LupaBbRunGuard.claim(
                                            kind = LupaBbRunKind.APPLY,
                                            now = ServerClock.now(),
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                outcomes.count { it.isSuccess } shouldBe 1
                outcomes.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<LupaBbSyncBusyException>() }
            }
        }

        test("without stored credentials a preview is CredentialsMissing; an unreachable LupaBB is Unavailable and the claim is released") {
            server().use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).loadPreview()
                    }.exceptionOrNull().shouldBeInstanceOf<LupaBbCredentialsMissingException>()
                    fx.storeCredentials(server = fake, actor = admin)
                    fake.mode = FakeMode.SERVER_ERROR
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).loadPreview()
                    }.exceptionOrNull().shouldBeInstanceOf<LupaBbUnavailableException>()
                    LupaBbRunGuard.isRunning(ServerClock.now()) shouldBe false
                }
            }
        }

        test("an ambiguous record is linked by hand to one of its candidates and then waits for the next sync") {
            val family = unique()
            val record =
                FakeLupaBbServer.member(
                    personNumber = 9_000_000_064,
                    familyName = family,
                    email = "${TEST_EMAIL_PREFIX}link@example.org",
                )
            server(record).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val candidate = fx.member(displayName = "Erika $family", email = "${TEST_EMAIL_PREFIX}anders@example.org")
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    summary.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 1
                    val page =
                        call(
                            member = admin,
                        ) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).linkMemberToLupaBb(token = summary.token, personNumber = 9_000_000_064, memberId = candidate.toString())
                        }
                    page.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 0
                    fx.memberRow(candidate)[MemberTable.lupabbPersonNumber] shouldBe 9_000_000_064
                    fx.memberRow(candidate)[MemberTable.displayName] shouldBe "Erika $family"
                    val skipped =
                        call(
                            member = admin,
                        ) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.SKIPPED, offset = 0, limit = 10)
                        }
                    skipped.rows
                        .single()
                        .reasons
                        .single()
                        .code shouldBe LupaBbRowReasonCode.LINKED_WAITING
                    // linking a second member to the same person number is refused
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).linkMemberToLupaBb(token = summary.token, personNumber = 9_000_000_064, memberId = candidate.toString())
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()
                }
            }
        }

        test("linking to a member whose person number became taken meanwhile is refused (already linked) and changes nothing") {
            val family = unique()
            val record =
                FakeLupaBbServer.member(
                    personNumber = 9_000_000_084,
                    familyName = family,
                    email = "${TEST_EMAIL_PREFIX}conflict@example.org",
                )
            server(record).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val candidate = fx.member(displayName = "Erika $family", email = "${TEST_EMAIL_PREFIX}conflict-other@example.org")
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    summary.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 1
                    fx.member(personNumber = 9_000_000_084, displayName = "Wer anders")
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).linkMemberToLupaBb(token = summary.token, personNumber = 9_000_000_084, memberId = candidate.toString())
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<LupaBbMemberAlreadyLinkedException>()
                    fx.memberRow(candidate)[MemberTable.lupabbPersonNumber] shouldBe null
                }
            }
        }

        test("credentials can be removed again") {
            server().use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input(fake)) }
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).removeCredentials() }.credentialsSet shouldBe false
                    transaction { LupaBbSyncConfigTable.selectAll().count() } shouldBe 0
                }
            }
        }

        test(
            "a unique violation under the savepoint ends in a fixed code and the run goes on (PostgreSQL aborts a transaction otherwise)",
        ) {
            val admin = fx.admin()
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_080, family = unique(), email = "${TEST_EMAIL_PREFIX}taken-pg@example.org"),
                            wireMember(personNumber = 9_000_000_081, family = unique()),
                            wireMember(personNumber = 9_000_000_082, family = unique()),
                        ),
                    actor = admin,
                )
            fx.member(email = "${TEST_EMAIL_PREFIX}taken-pg@example.org", displayName = "Wer anders")
            fx.member(personNumber = 9_000_000_081, displayName = "Wer anders 2")
            val result =
                LupaBbApplier(
                    chapterEnforced = false,
                ).apply(entry = entryOf(plan = plan, admin = admin), actor = fx.current(id = admin))
            result.created shouldBe 1
            result.failures.associate { it.personNumber to it.code } shouldBe
                mapOf(
                    9_000_000_080L to LupaBbRowReasonCode.EMAIL_TAKEN,
                    9_000_000_081L to LupaBbRowReasonCode.PERSON_NUMBER_TAKEN,
                )
            (fx.byPersonNumber(9_000_000_082) != null) shouldBe true
        }

        test("an apply that waits for the row lock of a concurrent manual edit finds the member changed and writes nothing") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_083)
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_083, family = family)), actor = admin)
            val locked = java.util.concurrent.CountDownLatch(1)
            val proceed = java.util.concurrent.CountDownLatch(1)
            val result =
                withContext(Dispatchers.IO) {
                    val editor =
                        async {
                            transaction {
                                MemberTable
                                    .selectAll()
                                    .where { MemberTable.id eq id }
                                    .forUpdate()
                                    .toList()
                                locked.countDown()
                                proceed.await()
                                MemberTable.update({ MemberTable.id eq id }) { it[street] = "Handbearbeitet" }
                            }
                        }
                    locked.await()
                    val applying =
                        async {
                            LupaBbApplier(
                                chapterEnforced = false,
                            ).apply(entry = entryOf(plan = plan, admin = admin), actor = fx.current(id = admin))
                        }
                    kotlinx.coroutines.delay(400)
                    proceed.countDown()
                    editor.await()
                    applying.await()
                }
            result.failures.single().code shouldBe LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW
            fx.memberRow(id)[MemberTable.street] shouldBe "Handbearbeitet"
        }

        test("a legacy FRIEND record reported as Foerderer turns DONOR on apply; a FRIEND with BOARD stays manual") {
            val family = unique()
            server(
                FakeLupaBbServer.member(personNumber = 9_000_000_070, familyName = family, status = 3),
                FakeLupaBbServer.member(personNumber = 9_000_000_071, familyName = family, status = 3),
            ).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val friend = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_070)
                    val board =
                        fx.member(
                            role = AccountRole.BOARD,
                            status = MemberStatus.FRIEND,
                            displayName = "Erika $family",
                            personNumber = 9_000_000_071,
                        )
                    call(member = admin) { c -> fx.service(call = c, runtime = rt).verifyAndSaveCredentials(input(fake)) }
                    fx.allowNextFetch()
                    val summary = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    // both rows change (the management member only in the safe profile fields, never in the status)
                    summary.counts.getValue(LupaBbCategory.CHANGED) shouldBe 2
                    val result = call(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview(summary.token) }
                    result.updated shouldBe 2
                    result.errors shouldBe 0
                    fx.memberRow(friend)[MemberTable.status] shouldBe MemberStatus.DONOR
                    fx.memberRow(board)[MemberTable.status] shouldBe MemberStatus.FRIEND
                }
            }
        }

        // ---- "Konflikte direkt loesen": take over, search + link, skip ----

        /** Opens a preview whose record [number] is AMBIGUOUS (its e-mail belongs to a member that is linked to another person already). */
        suspend fun LupaBbApp.ambiguousPreview(
            fake: FakeLupaBbServer,
            rt: LupaBbSyncRuntime,
            admin: Uuid,
        ) = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }

        fun ambiguousRecord(
            number: Long,
            email: String,
        ) = FakeLupaBbServer.member(personNumber = number, familyName = unique(), email = email)

        test("searching link targets: at least 3 characters, literal wildcards, no anonymised member, linked ones flagged, at most 10") {
            val email = "${TEST_EMAIL_PREFIX}owner@example.org"
            server(
                ambiguousRecord(9_000_000_600, email),
                FakeLupaBbServer.member(personNumber = 9_000_000_609, familyName = unique()),
            ).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val tag = unique()
                    fx.member(email = email, personNumber = 9_000_000_601, displayName = "Inhaber $tag")
                    repeat(12) { fx.member(displayName = "Sucher $tag $it") }
                    fx.member(displayName = "Sucher $tag anonym", anonymized = true)
                    val linked = fx.member(displayName = "Verknuepft $tag", personNumber = 9_000_000_602)
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = ambiguousPreview(fake, rt, admin)

                    suspend fun search(
                        q: String,
                        number: Long = 9_000_000_600,
                    ) = attempt(member = admin) { c ->
                        fx.service(call = c, runtime = rt).searchLinkTargets(token = summary.token, personNumber = number, query = q)
                    }
                    search("Su").getOrThrow() shouldBe emptyList()
                    search("  ab ").getOrThrow() shouldBe emptyList()
                    search("Sucher $tag").getOrThrow().size shouldBe 10
                    search("Sucher $tag anonym").getOrThrow() shouldBe emptyList()
                    search("$tag%").getOrThrow() shouldBe emptyList()
                    search("Verknuepft $tag").getOrThrow().single().let {
                        it.memberId shouldBe linked.toString()
                        it.linkedElsewhere shouldBe true
                    }
                    search("x".repeat(101)).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                    search("abc\u0007def").exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                    // a row that is not ambiguous cannot be searched for
                    search("Sucher $tag", number = 9_000_000_609).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                    search("Sucher $tag", number = 123).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                }
            }
        }

        test("an ambiguous record is linked to any found member: only the person number is written, never the e-mail") {
            val email = "${TEST_EMAIL_PREFIX}owner2@example.org"
            server(ambiguousRecord(9_000_000_620, email)).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val tag = unique()
                    fx.member(email = email, personNumber = 9_000_000_621, displayName = "Inhaber $tag")
                    val wanted = fx.member(displayName = "Gesucht $tag", email = "${TEST_EMAIL_PREFIX}gesucht@example.org")
                    val linkedElsewhere = fx.member(displayName = "Belegt $tag", personNumber = 9_000_000_622)
                    val gone = fx.member(displayName = "Anonym $tag", anonymized = true)
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = ambiguousPreview(fake, rt, admin)

                    suspend fun link(id: String) =
                        attempt(member = admin) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).linkMemberToLupaBb(token = summary.token, personNumber = 9_000_000_620, memberId = id)
                        }
                    link(linkedElsewhere.toString()).exceptionOrNull().shouldBeInstanceOf<LupaBbMemberAlreadyLinkedException>()
                    link(gone.toString()).exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    link(Uuid.random().toString()).exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    link("not-a-uuid").exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    val page = link(wanted.toString()).getOrThrow()
                    page.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 0
                    fx.memberRow(wanted)[MemberTable.lupabbPersonNumber] shouldBe 9_000_000_620
                    fx.memberRow(wanted)[MemberTable.email] shouldBe "${TEST_EMAIL_PREFIX}gesucht@example.org"
                    val audit =
                        transaction {
                            AuditLogEntryTable
                                .selectAll()
                                .where {
                                    AuditLogEntryTable.entityId eq wanted
                                }.map { it[AuditLogEntryTable.afterSnapshot] }
                        }
                    audit.single()!!.contains("lupabbLinked") shouldBe true
                }
            }
        }

        test("skipping an ambiguous row only marks it in this preview: no suppression entry, a new comparison shows it again") {
            val email = "${TEST_EMAIL_PREFIX}owner3@example.org"
            server(
                ambiguousRecord(9_000_000_630, email),
                FakeLupaBbServer.member(personNumber = 9_000_000_639, familyName = unique()),
            ).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    fx.member(email = email, personNumber = 9_000_000_631)
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = ambiguousPreview(fake, rt, admin)
                    val suppressedBefore = transaction { LupaBbSyncSuppressionTable.selectAll().count() }
                    val page =
                        call(
                            member = admin,
                        ) { c -> fx.service(call = c, runtime = rt).skipRow(token = summary.token, personNumber = 9_000_000_630) }
                    page.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 0
                    val skipped =
                        call(member = admin) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.SKIPPED, offset = 0, limit = 10)
                        }
                    skipped.rows
                        .single { it.personNumber == 9_000_000_630 }
                        .reasons
                        .single()
                        .code shouldBe
                        LupaBbRowReasonCode.SKIPPED_BY_ADMIN
                    transaction { LupaBbSyncSuppressionTable.selectAll().count() } shouldBe suppressedBefore
                    val audit =
                        transaction {
                            AuditLogEntryTable
                                .selectAll()
                                .where { AuditLogEntryTable.entityType eq AuditEntityType.LUPABB_SYNC_CONFIG }
                                .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
                        }
                    audit.any { it.contains("PREVIEW_ROW_SKIPPED") } shouldBe true
                    audit.none { it.contains("9000000630") } shouldBe true
                    // not ambiguous any more: a second skip is refused
                    attempt(
                        member = admin,
                    ) { c -> fx.service(call = c, runtime = rt).skipRow(token = summary.token, personNumber = 9_000_000_630) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()
                    fx.allowNextFetch()
                    val again = ambiguousPreview(fake, rt, admin)
                    again.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 1
                }
            }
        }

        test("taking over a held-back row: tamper cases are refused, success marks the row and the later apply does not write it again") {
            val family = unique()
            server(
                FakeLupaBbServer.member(personNumber = 9_000_000_640, familyName = family),
                FakeLupaBbServer.member(personNumber = 9_000_000_641, familyName = unique()),
            ).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val other = fx.admin("other")
                    val board =
                        fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_640, street = "Alt")
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = ambiguousPreview(fake, rt, admin)

                    suspend fun take(
                        number: Long,
                        who: Uuid = admin,
                        token: String = summary.token,
                    ) = attempt(
                        member = who,
                    ) { c -> fx.service(call = c, runtime = rt).applyProtectedRow(token = token, personNumber = number) }
                    take(123).exceptionOrNull().shouldBeInstanceOf<BadRequestException>() // not in the preview
                    take(9_000_000_641).exceptionOrNull().shouldBeInstanceOf<BadRequestException>() // a NEW row, nothing held back
                    take(9_000_000_640, who = other).exceptionOrNull().shouldBeInstanceOf<LupaBbPreviewExpiredException>()
                    take(9_000_000_640, token = "unknown").exceptionOrNull().shouldBeInstanceOf<LupaBbPreviewExpiredException>()
                    fx.memberRow(board)[MemberTable.street] shouldBe "Alt"
                    val result = take(9_000_000_640).getOrThrow()
                    result.applied shouldBe true
                    result.failure shouldBe null
                    fx.memberRow(board)[MemberTable.street] shouldNotBe "Alt"
                    val row =
                        call(member = admin) { c ->
                            fx
                                .service(
                                    call = c,
                                    runtime = rt,
                                ).getPreviewPage(token = summary.token, filter = LupaBbPreviewFilter.ALL, offset = 0, limit = 50)
                        }.rows.single { it.personNumber == 9_000_000_640 }
                    row.category shouldBe LupaBbCategory.UNCHANGED
                    row.reasons.single().code shouldBe LupaBbRowReasonCode.APPLIED_BY_ADMIN
                    // the bulk apply neither fails with CHANGED_SINCE_PREVIEW nor writes the row again
                    val applied = call(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview(summary.token) }
                    applied.failures.none { it.personNumber == 9_000_000_640 } shouldBe true
                }
            }
        }

        test("the own record cannot be taken over; a running sync makes it Busy") {
            val family = unique()
            server(FakeLupaBbServer.member(personNumber = 9_000_000_650, familyName = family)).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val self =
                        fx.member(role = AccountRole.ADMIN, displayName = "Erika $family", personNumber = 9_000_000_650, street = "Alt")
                    fx.storeCredentials(server = fake, actor = self)
                    val summary = ambiguousPreview(fake, rt, self)
                    val result =
                        call(
                            member = self,
                        ) { c -> fx.service(call = c, runtime = rt).applyProtectedRow(token = summary.token, personNumber = 9_000_000_650) }
                    result.applied shouldBe false
                    result.failure shouldBe LupaBbRowReasonCode.OWN_RECORD
                    fx.memberRow(self)[MemberTable.street] shouldBe "Alt"
                    val claim = LupaBbRunGuard.claim(kind = LupaBbRunKind.APPLY, now = ServerClock.now())
                    try {
                        attempt(
                            member = self,
                        ) { c -> fx.service(call = c, runtime = rt).applyProtectedRow(token = summary.token, personNumber = 9_000_000_650) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<LupaBbSyncBusyException>()
                    } finally {
                        LupaBbRunGuard.release(claim)
                    }
                }
            }
        }

        test("a FRIEND whose record says withdrawn turns WITHDRAWN on apply (end of a friendship), a FRIEND found by e-mail does not") {
            val family = unique()
            server(
                FakeLupaBbServer.member(personNumber = 9_000_000_660, familyName = family, status = 6),
            ).use { fake ->
                val rt = fx.runtime(server = fake)
                lupaBbTestApplication(fixtures = fx) {
                    val admin = fx.admin()
                    val friend = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_660)
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = ambiguousPreview(fake, rt, admin)
                    val result = call(member = admin) { c -> fx.service(call = c, runtime = rt).applyPreview(summary.token) }
                    result.errors shouldBe 0
                    fx.memberRow(friend)[MemberTable.status] shouldBe MemberStatus.WITHDRAWN
                }
            }
        }

        test("the fixtures leave no state behind") {
            fx.member(label = "left-over")
            fx.cleanUp()
            fx.testMemberIds().shouldBeEmpty()
        }
    })
