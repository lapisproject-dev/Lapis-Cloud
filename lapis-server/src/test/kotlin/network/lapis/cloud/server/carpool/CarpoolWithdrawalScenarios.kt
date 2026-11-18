package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.carpool.feed.TEST_IDS
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.dsgvo.CarpoolPersonalData
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.rpc.carpoolTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable as Tomb

/**
 * V1.9.313 "Mitfahrzentrale-Foederation" W3 -- the withdrawal on the origin side, on H2 AND PostgreSQL with the same assertions: every trigger writes
 * exactly the right row, the non-triggers write none, a second withdrawal changes nothing, the life is capped, the consent filter, a missing key,
 * the erasure and the sweep. (The admin triggers B4/B5 are in `CarpoolWithdrawalAdminTest`, the wire in `CarpoolTeaserFeedWithdrawalScenarios`.)
 */
abstract class CarpoolWithdrawalScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            CarpoolWithdrawalKey.resetForTest()
            transaction {
                exec("UPDATE carpool_posting SET federation_opt_in = FALSE")
                PartnerServerTable.deleteAll()
                Tomb.deleteAll()
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }
        }
        afterSpec {
            try {
                CarpoolWithdrawalKey.resetForTest()
                transaction {
                    PartnerServerTable.deleteAll()
                    Tomb.deleteAll()
                    CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2)
                }
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun shareable(
            author: Uuid,
            daysAhead: Int = 10,
        ) = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover", daysAhead = daysAhead)

        fun optIn(
            id: Uuid,
            at: LocalDateTime = DbClock.nowLocalDateTime(),
        ) = transaction { CarpoolFederationStore.setOptIn(postingId = id, value = true, now = at) }

        fun storedOptIn(id: Uuid) = transaction { CarpoolFederationStore.optIn(id) }

        fun optInAt(id: Uuid): LocalDateTime? =
            transaction {
                CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq id }.single()[CarpoolPostingTable.federationOptInAt]
            }

        /** A partner that fetched the feed AFTER every consent given so far in this test (so the consent filter lets a tombstone through). */
        fun servedPartner() = newFeedPartner().also { recordServed(partnerId = it.id, at = DbClock.nowLocalDateTime().plusSeconds(60)) }

        fun tombstoneIds() = allTombstones().map { it[Tomb.teaserId] }

        test("the expiry is min(created + 14 days, departure + 2 days at 00:00 UTC), pinned") {
            val created = LocalDateTime(2026, 11, 18, 10, 30, 0)
            CarpoolTeaserTombstonePolicy.expiresAt(createdAt = created, departure = LocalDate(2026, 12, 31)) shouldBe
                LocalDateTime(2026, 12, 2, 10, 30, 0)
            CarpoolTeaserTombstonePolicy.expiresAt(createdAt = created, departure = LocalDate(2026, 11, 20)) shouldBe
                LocalDateTime(2026, 11, 22, 0, 0, 0)
            CarpoolTeaserTombstonePolicy.expiresAt(createdAt = created, departure = LocalDate(2026, 11, 1)) shouldBe
                LocalDateTime(2026, 11, 3, 0, 0, 0)
            CarpoolTeaserTombstonePolicy.TTL_DAYS shouldBe 14
            CarpoolTeaserTombstonePolicy.GRACE_DAYS_AFTER_DEPARTURE shouldBe 2
        }

        test("B1: withdrawing the consent writes ONE global tombstone (id, no partner, day, capped life) and clears the consent") {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-b1")
                    val id = shareable(owner)
                    servedPartner()
                    call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = true) }
                    (optInAt(id) != null) shouldBe true
                    call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false) }
                    storedOptIn(id) shouldBe false
                    optInAt(id) shouldBe null
                    val rows = allTombstones()
                    rows shouldHaveSize 1
                    val row = rows.single()
                    row[Tomb.teaserId] shouldBe TEST_IDS.of(id)
                    row[Tomb.partnerId] shouldBe null
                    row[Tomb.expiresAt] shouldBe
                        CarpoolTeaserTombstonePolicy.expiresAt(
                            createdAt = row[Tomb.createdAt],
                            departure = data.today().plus(DatePeriod(days = 10)),
                        )
                    row[Tomb.id] shouldBe CarpoolFederationStore.tombstoneId(teaserId = TEST_IDS.of(id), partnerId = null)
                }
            }
        }

        test(
            "the consent filter: without a fetch AFTER the consent there is nothing to withdraw (no row), with one there is; the consent ends either way",
        ) {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-q1")
                    val never = shareable(owner)
                    val partner = newFeedPartner()
                    val consentAt = DbClock.nowLocalDateTime().plusSeconds(30)
                    recordServed(partnerId = partner.id, at = consentAt.minus1s())
                    optIn(never, at = consentAt)
                    call(member = owner) { setCarpoolPostingFederationOptIn(postingId = never.toString(), optIn = false) }
                    storedOptIn(never) shouldBe false
                    allTombstones() shouldHaveSize 0
                    val served = shareable(owner)
                    optIn(served, at = consentAt)
                    recordServed(partnerId = partner.id, at = consentAt.plusSeconds(1))
                    call(member = owner) { setCarpoolPostingFederationOptIn(postingId = served.toString(), optIn = false) }
                    tombstoneIds() shouldBe listOf(TEST_IDS.of(served))
                }
            }
        }

        test("a second withdrawal changes nothing: one row, created_at and expires_at stay (the life is never extended)") {
            withWithdrawalKey {
                val owner = data.member(label = "wd-idem")
                val id = shareable(owner)
                servedPartner()
                optIn(id)
                val target = CarpoolWithdrawalTarget(postingId = id, departureDate = data.today().plus(DatePeriod(days = 10)))
                val t1 = DbClock.nowLocalDateTime()
                transaction {
                    CarpoolFederationStore.withdraw(
                        targets = listOf(target),
                        partnerId = null,
                        ids = TEST_IDS,
                        now = t1,
                        clearOptIn = false,
                        requireServed = false,
                    )
                } shouldBe 1
                val first = allTombstones().single()
                transaction {
                    CarpoolFederationStore.withdraw(
                        targets = listOf(target),
                        partnerId = null,
                        ids = TEST_IDS,
                        now = t1.plusSeconds(3600),
                        clearOptIn = false,
                        requireServed = false,
                    )
                } shouldBe 0
                val second = allTombstones().single()
                second[Tomb.createdAt] shouldBe first[Tomb.createdAt]
                second[Tomb.expiresAt] shouldBe first[Tomb.expiresAt]
            }
        }

        test("an EXPIRED tombstone that the sweep has not removed yet does not swallow a new withdrawal of the same hint") {
            withWithdrawalKey {
                val owner = data.member(label = "wd-expired")
                val id = shareable(owner)
                optIn(id)
                val target = CarpoolWithdrawalTarget(postingId = id, departureDate = data.today().plus(DatePeriod(days = 10)))
                val now = DbClock.nowLocalDateTime()
                val old = LocalDateTime(now.date.minus(DatePeriod(days = 15)), now.time)

                fun withdrawAt(at: LocalDateTime) =
                    transaction {
                        CarpoolFederationStore.withdraw(
                            targets = listOf(target),
                            partnerId = null,
                            ids = TEST_IDS,
                            now = at,
                            clearOptIn = false,
                            requireServed = false,
                        )
                    }
                withdrawAt(old) shouldBe 1
                (allTombstones().single()[Tomb.expiresAt] <= now) shouldBe true
                withdrawAt(now) shouldBe 1
                val live = allTombstones().single()
                live[Tomb.createdAt] shouldBe now
                (live[Tomb.expiresAt] > now) shouldBe true
                transaction {
                    CarpoolFederationStore
                        .liveWithdrawals(
                            partnerId = Uuid.random(),
                            now = now,
                            scanLimit = 10,
                        ).rows
                } shouldHaveSize
                    1
            }
        }

        test("giving the consent again while the exchange is OFF keeps the global tombstone of the instance switch-off") {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-reconsent-off")
                    val id = shareable(owner)
                    servedPartner()
                    optIn(id)
                    call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false) }
                    tombstoneIds() shouldBe listOf(TEST_IDS.of(id))
                    transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                    runCatching { call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = true) } }
                    storedOptIn(id) shouldBe false
                    tombstoneIds() shouldBe listOf(TEST_IDS.of(id))
                }
            }
        }

        test("two parallel withdrawals of the same hint end with one row and no exception") {
            withWithdrawalKey {
                val owner = data.member(label = "wd-par")
                val id = shareable(owner)
                optIn(id)
                val target = CarpoolWithdrawalTarget(postingId = id, departureDate = data.today().plus(DatePeriod(days = 10)))
                runBlocking {
                    withTimeout(30.seconds) {
                        (1..2)
                            .map {
                                async(kotlinx.coroutines.Dispatchers.IO) {
                                    transaction {
                                        CarpoolFederationStore.withdraw(
                                            targets = listOf(target),
                                            partnerId = null,
                                            ids = TEST_IDS,
                                            now = DbClock.nowLocalDateTime(),
                                            clearOptIn = false,
                                            requireServed = false,
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                }
                allTombstones() shouldHaveSize 1
            }
        }

        test("giving the consent again forgets the global tombstone of that hint (and only that one)") {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-reconsent")
                    val a = shareable(owner)
                    val b = shareable(owner)
                    servedPartner()
                    listOf(a, b).forEach { optIn(it) }
                    listOf(
                        a,
                        b,
                    ).forEach { call(member = owner) { setCarpoolPostingFederationOptIn(postingId = it.toString(), optIn = false) } }
                    allTombstones() shouldHaveSize 2
                    call(member = owner) { setCarpoolPostingFederationOptIn(postingId = a.toString(), optIn = true) }
                    tombstoneIds() shouldBe listOf(TEST_IDS.of(b))
                }
            }
        }

        test("B2: deleting a shared trip writes the tombstone before the row disappears; deleting an unshared trip writes none") {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-b2")
                    val shared = shareable(owner)
                    val plain = shareable(owner)
                    optIn(shared)
                    servedPartner()
                    call(member = owner) { deletePosting(id = plain.toString()) }
                    allTombstones() shouldHaveSize 0
                    call(member = owner) { deletePosting(id = shared.toString()) }
                    tombstoneIds() shouldBe listOf(TEST_IDS.of(shared))
                    transaction { CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq shared }.count() } shouldBe 0L
                }
            }
        }

        test("B3: an edit that makes the trip unshareable withdraws with the OLD date; one that keeps it shareable writes nothing") {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-b3")
                    val oldDate = data.today().plus(DatePeriod(days = 12))
                    val id = shareable(owner, daysAhead = 12)
                    optIn(id)
                    servedPartner()

                    fun input(
                        type: CarpoolPostingType = CarpoolPostingType.OFFER,
                        to: String = "30159 Hannover",
                        date: LocalDate = oldDate,
                    ) = CarpoolPostingInput(
                        type = type,
                        fromPlace = "38100 Braunschweig",
                        toPlace = to,
                        departureDate = date,
                        seatsOffered = if (type == CarpoolPostingType.OFFER) 2 else null,
                    )
                    // stays shareable (new text, new place with a postal code): same hint id, no tombstone
                    call(member = owner) { updatePosting(id = id.toString(), input = input(to = "30161 Hannover")) }
                    storedOptIn(id) shouldBe true
                    allTombstones() shouldHaveSize 0
                    // loses the postal code: the consent ends and the OLD date bounds the life
                    call(
                        member = owner,
                    ) { updatePosting(id = id.toString(), input = input(to = "Hannover", date = oldDate.plus(DatePeriod(days = 3)))) }
                    storedOptIn(id) shouldBe false
                    val row = allTombstones().single()
                    row[Tomb.teaserId] shouldBe TEST_IDS.of(id)
                    row[Tomb.expiresAt] shouldBe
                        CarpoolTeaserTombstonePolicy.expiresAt(createdAt = row[Tomb.createdAt], departure = oldDate)
                }
            }
        }

        test("B3: an edit to a request withdraws; an edit that pushes the date beyond the 90 day window withdraws but keeps the consent") {
            withWithdrawalKey {
                carpoolTestApplication {
                    val owner = data.member(label = "wd-b3b")
                    val near = shareable(owner, daysAhead = 20)
                    val far = shareable(owner, daysAhead = 20)
                    listOf(near, far).forEach { optIn(it) }
                    servedPartner()
                    call(member = owner) {
                        updatePosting(
                            id = near.toString(),
                            input =
                                CarpoolPostingInput(
                                    type = CarpoolPostingType.REQUEST,
                                    fromPlace = "38100 Braunschweig",
                                    toPlace = "30159 Hannover",
                                    departureDate = data.today().plus(DatePeriod(days = 20)),
                                ),
                        )
                    }
                    storedOptIn(near) shouldBe false
                    tombstoneIds() shouldBe listOf(TEST_IDS.of(near))
                    call(member = owner) {
                        updatePosting(
                            id = far.toString(),
                            input =
                                CarpoolPostingInput(
                                    type = CarpoolPostingType.OFFER,
                                    fromPlace = "38100 Braunschweig",
                                    toPlace = "30159 Hannover",
                                    departureDate = data.today().plus(DatePeriod(days = 120)),
                                    seatsOffered = 2,
                                ),
                        )
                    }
                    storedOptIn(far) shouldBe true
                    tombstoneIds().toSet() shouldBe setOf(TEST_IDS.of(near), TEST_IDS.of(far))
                }
            }
        }

        test(
            "no tombstone when the departure grace period is over, none without a consent-time fetch, none when the trip was never shared",
        ) {
            withWithdrawalKey {
                val owner = data.member(label = "wd-none")
                val past = shareable(owner, daysAhead = -5)
                optIn(past)
                servedPartner()
                transaction {
                    CarpoolFederationStore.withdraw(
                        targets =
                            listOf(
                                CarpoolWithdrawalTarget(postingId = past, departureDate = data.today().plus(DatePeriod(days = -5))),
                            ),
                        partnerId = null,
                        ids = TEST_IDS,
                        now = DbClock.nowLocalDateTime(),
                        clearOptIn = true,
                        requireServed = false,
                    )
                } shouldBe 0
                allTombstones() shouldHaveSize 0
                storedOptIn(past) shouldBe false
            }
        }

        test("without a key the consent is still cleared, no row is written and the miss is counted") {
            val owner = data.member(label = "wd-nokey")
            val id = shareable(owner)
            optIn(id)
            servedPartner()
            transaction {
                CarpoolFederationStore.withdraw(
                    targets = listOf(CarpoolWithdrawalTarget(postingId = id, departureDate = data.today().plus(DatePeriod(days = 10)))),
                    partnerId = null,
                    ids = null,
                    now = DbClock.nowLocalDateTime(),
                    clearOptIn = true,
                    requireServed = true,
                )
            } shouldBe 0
            storedOptIn(id) shouldBe false
            allTombstones() shouldHaveSize 0
            CarpoolWithdrawalKey.skippedNoKey.get() shouldBe 1L
        }

        test("B6: the erasure of a member writes the tombstones BEFORE the trips are deleted; no member or trip stays referenced") {
            withWithdrawalKey {
                val owner = data.member(label = "wd-b6")
                val a = shareable(owner)
                val b = shareable(owner)
                val unshared = shareable(owner)
                listOf(a, b).forEach { optIn(it) }
                servedPartner()
                transaction { CarpoolPersonalData.eraseMember(memberId = owner, mode = ErasureMode.entries.first()) }
                tombstoneIds().toSet() shouldBe setOf(TEST_IDS.of(a), TEST_IDS.of(b))
                transaction { CarpoolPostingTable.selectAll().where { CarpoolPostingTable.authorMemberId eq owner }.count() } shouldBe 0L
                transaction { CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq unshared }.count() } shouldBe 0L
                allTombstones().forEach { it[Tomb.partnerId] shouldBe null }
            }
        }

        test(
            "B7: a trip of an author who left is withdrawn by the sweep (consent cleared, tombstone, one audit entry without an actor), bounded by the limit",
        ) {
            withWithdrawalKey {
                val gone = data.member(label = "wd-b7-gone")
                val stays = data.member(label = "wd-b7-stays")
                val a = shareable(gone)
                val b = shareable(gone)
                val ok = shareable(stays)
                listOf(a, b, ok).forEach { optIn(it) }
                servedPartner()
                data.setStatus(memberId = gone, status = MemberStatus.WITHDRAWN)
                transaction {
                    CarpoolWithdrawals.sweepInactiveAuthors(
                        now = DbClock.nowLocalDateTime(),
                        ids = TEST_IDS,
                        limit = 1,
                    )
                } shouldBe
                    1
                transaction {
                    CarpoolWithdrawals.sweepInactiveAuthors(
                        now = DbClock.nowLocalDateTime(),
                        ids = TEST_IDS,
                        limit = 50,
                    )
                } shouldBe
                    1
                transaction {
                    CarpoolWithdrawals.sweepInactiveAuthors(
                        now = DbClock.nowLocalDateTime(),
                        ids = TEST_IDS,
                        limit = 50,
                    )
                } shouldBe
                    0
                storedOptIn(a) shouldBe false
                storedOptIn(b) shouldBe false
                storedOptIn(ok) shouldBe true
                tombstoneIds().toSet() shouldBe setOf(TEST_IDS.of(a), TEST_IDS.of(b))
                val audits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.CARPOOL_FEDERATION_OPT_IN) and
                                    (AuditLogEntryTable.entityId eq a)
                            }.toList()
                    }
                audits shouldHaveSize 1
                audits.single()[AuditLogEntryTable.actorMemberId] shouldBe null
            }
        }

        test("the cleanup deletes only expired rows and is idempotent; the poller tick runs retention, cleanup and sweep") {
            withWithdrawalKey {
                val owner = data.member(label = "wd-clean")
                val live = shareable(owner)
                val now = DbClock.nowLocalDateTime()
                transaction {
                    CarpoolFederationStore.withdraw(
                        targets =
                            listOf(
                                CarpoolWithdrawalTarget(postingId = live, departureDate = data.today().plus(DatePeriod(days = 10))),
                            ),
                        partnerId = null,
                        ids = TEST_IDS,
                        now = now,
                        clearOptIn = false,
                        requireServed = false,
                    )
                    insertExpired(teaserId = "e".repeat(64), now = now)
                }
                allTombstones() shouldHaveSize 2
                transaction { CarpoolFederationStore.deleteExpiredTombstones(now) } shouldBe 1
                transaction { CarpoolFederationStore.deleteExpiredTombstones(now) } shouldBe 0
                allTombstones() shouldHaveSize 1
                transaction { insertExpired(teaserId = "f".repeat(64), now = now) }
                val gone = data.member(label = "wd-clean-gone")
                val sweepId = shareable(gone)
                optIn(sweepId)
                servedPartner()
                data.setStatus(memberId = gone, status = MemberStatus.WITHDRAWN)
                CarpoolRetentionPoller().tick()
                tombstoneIds().toSet() shouldBe setOf(TEST_IDS.of(live), TEST_IDS.of(sweepId))
                storedOptIn(sweepId) shouldBe false
            }
        }

        test("B1 and the sweep running in parallel do not deadlock (same lock order: trips first, tombstones after)") {
            withWithdrawalKey {
                val gone = data.member(label = "wd-dl-gone")
                val owner = data.member(label = "wd-dl-owner")
                val sweepIds = (1..5).map { shareable(gone) }
                val ownerIds = (1..5).map { shareable(owner) }
                (sweepIds + ownerIds).forEach { optIn(it) }
                servedPartner()
                data.setStatus(memberId = gone, status = MemberStatus.WITHDRAWN)
                runBlocking {
                    withTimeout(60.seconds) {
                        listOf(
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                transaction {
                                    CarpoolWithdrawals.sweepInactiveAuthors(
                                        now = DbClock.nowLocalDateTime(),
                                        ids = TEST_IDS,
                                        limit = 50,
                                    )
                                }
                            },
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                transaction {
                                    val targets = CarpoolFederationStore.lockOptedInTargetsOfAuthor(owner)
                                    CarpoolFederationStore.withdraw(
                                        targets = targets,
                                        partnerId = null,
                                        ids = TEST_IDS,
                                        now = DbClock.nowLocalDateTime(),
                                        clearOptIn = true,
                                        requireServed = true,
                                    )
                                }
                            },
                        ).awaitAll()
                    }
                }
                allTombstones() shouldHaveSize 10
            }
        }
    })

private fun LocalDateTime.plusSeconds(s: Int): LocalDateTime = (toInstant(TimeZone.UTC) + s.seconds).toLocalDateTime(TimeZone.UTC)

private fun LocalDateTime.minus1s(): LocalDateTime = plusSeconds(-1)

private fun insertExpired(
    teaserId: String,
    now: LocalDateTime,
) {
    Tomb.insert {
        it[Tomb.id] = CarpoolFederationStore.tombstoneId(teaserId = teaserId, partnerId = null)
        it[Tomb.teaserId] = teaserId
        it[Tomb.createdAt] = now.plusSeconds(-3600)
        it[Tomb.expiresAt] = now.plusSeconds(-1)
    }
}
