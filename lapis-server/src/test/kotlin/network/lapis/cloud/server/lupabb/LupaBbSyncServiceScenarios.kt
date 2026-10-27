package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.LupaBbAuthRejectedException
import network.lapis.cloud.shared.rpc.LupaBbCredentialsMissingException
import network.lapis.cloud.shared.rpc.LupaBbEncryptionKeyMissingException
import network.lapis.cloud.shared.rpc.LupaBbPreviewExpiredException
import network.lapis.cloud.shared.rpc.LupaBbSyncBusyException
import network.lapis.cloud.shared.rpc.LupaBbSyncRateLimitedException
import network.lapis.cloud.shared.rpc.LupaBbUnavailableException
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
                        )
                    calls.size shouldBe 7
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
                    val stranger = fx.member(displayName = "Wer Fremdes")
                    fx.storeCredentials(server = fake, actor = admin)
                    val summary = call(member = admin) { c -> fx.service(call = c, runtime = rt).loadPreview() }
                    summary.counts.getValue(LupaBbCategory.AMBIGUOUS) shouldBe 1
                    attempt(
                        member = admin,
                    ) { c ->
                        fx
                            .service(
                                call = c,
                                runtime = rt,
                            ).linkMemberToLupaBb(token = summary.token, personNumber = 9_000_000_064, memberId = stranger.toString())
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()
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

        test("linking to a candidate whose person number became taken meanwhile is a conflict and changes nothing") {
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
                        .shouldBeInstanceOf<ConflictException>()
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

        test("the fixtures leave no state behind") {
            fx.member(label = "left-over")
            fx.cleanUp()
            fx.testMemberIds().shouldBeEmpty()
        }
    })
