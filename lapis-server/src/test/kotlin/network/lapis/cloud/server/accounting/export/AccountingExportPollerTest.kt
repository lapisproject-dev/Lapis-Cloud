package network.lapis.cloud.server.accounting.export

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountingExportDirection
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunStatus
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

private fun LocalDateTime.plusDuration(duration: kotlin.time.Duration): LocalDateTime =
    toInstant(TimeZone.UTC).plus(duration).toLocalDateTime(TimeZone.UTC)

/** A controllable [AccountingExportProviderAdapter] -- no HTTP anywhere, [callCount] proves whether
 * [pushVoucher] was actually invoked ([AccountingExportPoller]'s own already-exported recheck must
 * skip the call entirely, not merely ignore its outcome). Welle V1.4.5.4 "sevDesk-Live-Anbindung":
 * [provider] is now a constructor parameter (default [AccountingExportProvider.LEXOFFICE], every
 * existing call site unaffected) so a test can register TWO independent fakes under
 * `adaptersByProvider` and prove each provider's items reach only its own adapter -- see
 * "two providers, two adapters" test below. */
private class FakeAdapter(
    override val provider: AccountingExportProvider = AccountingExportProvider.LEXOFFICE,
) : AccountingExportProviderAdapter {
    var callCount = 0
    val outcomes = ArrayDeque<VoucherPushOutcome>()

    override suspend fun testConnection(token: String): ConnectionTestOutcome = ConnectionTestOutcome.Success(companyName = "Test e.V.")

    override suspend fun listCategories(token: String): CategoryListOutcome = CategoryListOutcome.Success(categories = emptyList())

    override suspend fun pushVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome {
        callCount++
        return outcomes.removeFirstOrNull() ?: VoucherPushOutcome.Rejected(errorCode = "NO_OUTCOME_QUEUED", message = "test misconfigured")
    }
}

/**
 * Exercises [AccountingExportPoller]'s claim/send/retry/reap machinery end to end against a real
 * (H2) DB, entirely through [FakeAdapter] -- no HTTP anywhere in this file, same house rule
 * [network.lapis.cloud.server.payment.psp.StripeCheckoutClientTest] establishes for outbound
 * provider clients (the HTTP client itself, [network.lapis.cloud.server.accounting.export.lexoffice
 * .LexofficeApiClient], has its own dedicated [network.lapis.cloud.server.accounting.export.lexoffice
 * .LexofficeApiClientTest] against a [io.ktor.client.engine.mock.MockEngine]).
 */
class AccountingExportPollerTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val createdMemberIds = mutableListOf<Uuid>()
        val createdEntryIds = mutableListOf<Uuid>()
        val createdRunIds = mutableListOf<Uuid>()

        afterEach {
            transaction {
                exec(
                    "DELETE FROM accounting_export_item WHERE run_id IN (${createdRunIds.joinToString(
                        ",",
                    ) { "'$it'" }.ifEmpty { "'00000000-0000-0000-0000-000000000000'" }})",
                )
                if (createdRunIds.isNotEmpty()) {
                    exec(
                        "DELETE FROM accounting_export_run WHERE id IN (${createdRunIds.joinToString(",") { "'$it'" }})",
                    )
                }
                exec("DELETE FROM accounting_export_connection")
                if (createdEntryIds.isNotEmpty()) JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdEntryIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            createdRunIds.clear()
            createdEntryIds.clear()
            createdMemberIds.clear()
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Poller Testmitglied"
                    it[email] = "poller-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        fun newJournalEntry(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = LocalDate(2026, 1, 15)
                    it[description] = "Poller Testbuchung"
                    it[voucherReference] = null
                    it[JournalEntryTable.createdBy] = createdBy
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(2026, 1, 15, 9, 0)
                    it[createdAt] = LocalDateTime(2026, 1, 15, 8, 0)
                }
            }
            createdEntryIds += id
            return id
        }

        fun plannedVoucher(
            journalEntryId: Uuid,
            alreadyExported: Boolean = false,
        ) = PlannedVoucher(
            journalEntryId = journalEntryId,
            entryDate = LocalDate(2026, 1, 15),
            voucherNumber = AccountingExportPlanner.voucherNumber(entryDate = LocalDate(2026, 1, 15), journalEntryId = journalEntryId),
            direction = AccountingExportDirection.INCOME,
            grossAmount = BigDecimal("42.00"),
            ledgerAccountId = Uuid.random(),
            externalCategoryId = "cat-1",
            externalCategoryName = "Einnahmen",
            description = "Poller Testbuchung",
            voucherReference = null,
            alreadyExported = alreadyExported,
        )

        fun newRunWithOneItem(
            actor: Uuid,
            alreadyExported: Boolean = false,
            provider: AccountingExportProvider = AccountingExportProvider.LEXOFFICE,
        ): Pair<Uuid, Uuid> {
            val entryId = newJournalEntry(actor)
            val now = dbClockNow()
            val runId =
                AccountingExportStore.createRun(
                    provider = provider,
                    from = LocalDate(2026, 1, 1),
                    to = LocalDate(2026, 1, 31),
                    startedBy = actor,
                    now = now,
                    vouchers = listOf(plannedVoucher(journalEntryId = entryId, alreadyExported = alreadyExported)),
                )
            createdRunIds += runId
            val itemId =
                transaction {
                    AccountingExportItemTable
                        .selectAll()
                        .where {
                            AccountingExportItemTable.runId eq runId
                        }.single()[AccountingExportItemTable.id]
                }
            return runId to itemId
        }

        fun connectAdapter(): Pair<FakeAdapter, AccountingExportPoller> {
            val box = SecretBox(randomKey())
            AccountingExportStore.upsertToken(
                provider = AccountingExportProvider.LEXOFFICE,
                token = "test-token-1234567890abcdef",
                secretBox = box,
                now = dbClockNow(),
            )
            val adapter = FakeAdapter()
            val poller =
                AccountingExportPoller(
                    config = AccountingExportConfig(enabled = true, pollIntervalSeconds = 2, secretEncryptionKey = box.let { null }),
                    secretBox = box,
                    adaptersByProvider = mapOf(AccountingExportProvider.LEXOFFICE to adapter),
                )
            return adapter to poller
        }

        /** Welle V1.4.5.4 "sevDesk-Live-Anbindung": TWO independent fake adapters, one per provider,
         * both registered on the SAME poller -- see "two providers, two adapters" test below. */
        fun connectTwoAdapters(): Triple<FakeAdapter, FakeAdapter, AccountingExportPoller> {
            val box = SecretBox(randomKey())
            AccountingExportStore.upsertToken(
                provider = AccountingExportProvider.LEXOFFICE,
                token = "test-token-lexoffice-1234567890",
                secretBox = box,
                now = dbClockNow(),
            )
            AccountingExportStore.upsertToken(
                provider = AccountingExportProvider.SEVDESK,
                token = "test-token-sevdesk-1234567890ab",
                secretBox = box,
                now = dbClockNow(),
            )
            val lexAdapter = FakeAdapter(provider = AccountingExportProvider.LEXOFFICE)
            val sevAdapter = FakeAdapter(provider = AccountingExportProvider.SEVDESK)
            val poller =
                AccountingExportPoller(
                    config = AccountingExportConfig(enabled = true, pollIntervalSeconds = 2, secretEncryptionKey = null),
                    secretBox = box,
                    adaptersByProvider =
                        mapOf(
                            AccountingExportProvider.LEXOFFICE to lexAdapter,
                            AccountingExportProvider.SEVDESK to sevAdapter,
                        ),
                )
            return Triple(lexAdapter, sevAdapter, poller)
        }

        fun itemStatus(itemId: Uuid): AccountingExportItemStatus =
            transaction {
                AccountingExportItemTable
                    .selectAll()
                    .where {
                        AccountingExportItemTable.id eq itemId
                    }.single()[AccountingExportItemTable.status]
            }

        test("a claim on an already-SENDING item is a no-op -- only the winner of the atomic UPDATE proceeds") {
            val actor = newMember()
            val (runId, itemId) = newRunWithOneItem(actor)
            val first = AccountingExportStore.claim(id = itemId, now = dbClockNow())
            val second = AccountingExportStore.claim(id = itemId, now = dbClockNow())
            first shouldBe (first ?: error("first claim should have won"))
            second shouldBe null
            runId shouldBe runId
        }

        test(
            "createRun itself marks an already-exported voucher SKIPPED_ALREADY_EXPORTED -- it is never even PENDING, " +
                "so the poller's send phase never touches it (see the NEXT test for the poller's OWN recheck)",
        ) {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor, alreadyExported = true)
            itemStatus(itemId) shouldBe AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
            val (adapter, poller) = connectAdapter()
            poller.tick()
            itemStatus(itemId) shouldBe AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
            adapter.callCount shouldBe 0
        }

        test(
            "a PENDING item whose journal entry became exported by ANOTHER run in the meantime is skipped by the " +
                "poller's OWN pre-send recheck, WITHOUT any HTTP call (Fund 2026-09-07: the previous test above never " +
                "actually exercised this recheck -- its item was already terminal at creation, never PENDING)",
        ) {
            val actor = newMember()
            val (firstRunId, firstItemId) = newRunWithOneItem(actor)
            val entryId =
                transaction {
                    AccountingExportItemTable
                        .selectAll()
                        .where { AccountingExportItemTable.id eq firstItemId }
                        .single()[AccountingExportItemTable.journalEntryId]
                }
            // The FIRST run's item genuinely succeeds -- this is what makes journalEntryId
            // "already exported" from AccountingExportStore.isAlreadyExported's point of view.
            AccountingExportStore.markSucceeded(
                id = firstItemId,
                provider = AccountingExportProvider.LEXOFFICE,
                journalEntryId = entryId,
                externalVoucherId = "ext-first-run",
                now = dbClockNow(),
            )
            // markSucceeded alone does NOT finalize the run (that is the poller's/startExport's own
            // job, see recomputeRunCounts) -- without this the first run stays RUNNING with
            // active_key='LEXOFFICE' and the SECOND createRun below would collide with
            // uq_accounting_export_run_active.
            AccountingExportStore.recomputeRunCounts(runId = firstRunId, now = dbClockNow())

            // A SECOND run plans the SAME journal entry as PENDING -- as if it had been planned
            // before the first run's send actually landed (createRun's own alreadyExported=false
            // here is deliberately "wrong", simulating exactly that race).
            val secondRunId =
                AccountingExportStore.createRun(
                    provider = AccountingExportProvider.LEXOFFICE,
                    from = LocalDate(2026, 1, 1),
                    to = LocalDate(2026, 1, 31),
                    startedBy = actor,
                    now = dbClockNow(),
                    vouchers = listOf(plannedVoucher(journalEntryId = entryId, alreadyExported = false)),
                )
            createdRunIds += secondRunId
            val secondItemId =
                transaction {
                    AccountingExportItemTable
                        .selectAll()
                        .where { AccountingExportItemTable.runId eq secondRunId }
                        .single()[AccountingExportItemTable.id]
                }
            itemStatus(secondItemId) shouldBe AccountingExportItemStatus.PENDING

            val (adapter, poller) = connectAdapter()
            poller.tick()

            itemStatus(secondItemId) shouldBe AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
            adapter.callCount shouldBe 0
        }

        test("Succeeded marks the item SUCCEEDED and writes the exported_key shadow column") {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor)
            val (adapter, poller) = connectAdapter()
            adapter.outcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "ext-123")
            poller.tick()
            itemStatus(itemId) shouldBe AccountingExportItemStatus.SUCCEEDED
            val row = transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemId }.single() }
            row[AccountingExportItemTable.exportedKey]?.contains("LEXOFFICE") shouldBe true
            row[AccountingExportItemTable.externalVoucherId] shouldBe "ext-123"
        }

        test("Rejected marks the item FAILED immediately, no retry") {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor)
            val (adapter, poller) = connectAdapter()
            adapter.outcomes += VoucherPushOutcome.Rejected(errorCode = "INVALID", message = "kaputt")
            poller.tick()
            itemStatus(itemId) shouldBe AccountingExportItemStatus.FAILED
            adapter.callCount shouldBe 1
        }

        test("Indeterminate marks the item UNKNOWN immediately, no retry") {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor)
            val (adapter, poller) = connectAdapter()
            adapter.outcomes += VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            poller.tick()
            itemStatus(itemId) shouldBe AccountingExportItemStatus.UNKNOWN
            adapter.callCount shouldBe 1
        }

        test("three consecutive Retryable outcomes exhaust the attempt budget and end in FAILED, never a fourth attempt") {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor)
            val (adapter, poller) = connectAdapter()
            repeat(3) { adapter.outcomes += VoucherPushOutcome.Retryable(errorCode = "RATE_LIMITED", message = "429", retryAfter = null) }
            // Backoff after attempt 1/2 schedules nextAttemptAt in the future -- force it due
            // immediately after each tick so the test does not depend on real wall-clock delay.
            repeat(3) {
                poller.tick()
                transaction {
                    AccountingExportItemTable.update({ AccountingExportItemTable.id eq itemId }) { it[nextAttemptAt] = null }
                }
            }
            itemStatus(itemId) shouldBe AccountingExportItemStatus.FAILED
            adapter.callCount shouldBe 3
        }

        test(
            "a stale SENDING item is reaped to UNKNOWN, never back to PENDING, AND the owning run is finalized in the " +
                "SAME tick (Fund 2026-09-07: reapStaleClaims used to return only a count, never the run id, so Phase B " +
                "never recomputed a run whose only remaining item got reaped -- it stayed RUNNING/active_key set forever)",
        ) {
            val actor = newMember()
            val (runId, itemId) = newRunWithOneItem(actor)
            val (_, poller) = connectAdapter()
            val now = dbClockNow()
            transaction {
                AccountingExportItemTable.update({ AccountingExportItemTable.id eq itemId }) {
                    it[status] = AccountingExportItemStatus.SENDING
                    it[claimedAt] = now.plusDuration(-(10.minutes))
                }
            }
            poller.tick()
            itemStatus(itemId) shouldBe AccountingExportItemStatus.UNKNOWN

            val run = requireNotNull(AccountingExportStore.getRun(runId))
            run.status shouldBe AccountingExportRunStatus.COMPLETED_WITH_ERRORS
            run.unknown shouldBe 1
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false
        }

        test(
            "reapStaleClaims never clobbers a status change that lands between its own SELECT and UPDATE -- a " +
                "concurrent abortRun mid-window leaves the item UNKNOWN/ABORTED_WHILE_SENDING, NOT overwritten to " +
                "UNKNOWN/STALE_CLAIM_REAPED (Fund 2026-09-07 review Runde 2, Befund 2: reapStaleClaims' UPDATE used " +
                "to be keyed only by id (\"id inList stale.map { it.first }\"), without re-checking status = " +
                "SENDING -- an atomic single predicate before this fix, split into a read-then-write-by-id when " +
                "reapStaleClaims moved from returning a count to returning run ids; Fund 2026-09-07 review Runde 3, " +
                "Befund 1: abortRun itself used to route a SENDING item to FAILED/ABORTED_BY_USER, which " +
                "retryFailed would then blindly resend -- a SENDING item may already have reached lexoffice, so it " +
                "now lands on UNKNOWN/ABORTED_WHILE_SENDING instead, same as a reaped stale claim, and this test's " +
                "expected status/errorCode were updated to match). This test forces the exact interleaving with a " +
                "real FOR UPDATE row lock (same idiom EventStore.lockEventForUpdate/EventCapacityTest already " +
                "establish in this codebase) instead of relying on incidental thread timing.",
        ) {
            val actor = newMember()
            val (runId, itemId) = newRunWithOneItem(actor)
            val now = dbClockNow()
            transaction {
                AccountingExportItemTable.update({ AccountingExportItemTable.id eq itemId }) {
                    it[status] = AccountingExportItemStatus.SENDING
                    it[claimedAt] = now.plusDuration(-(10.minutes))
                }
            }

            val lockAcquired = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)
            val lockHolderDone = CountDownLatch(1)

            // Holds a FOR UPDATE row lock on the item across both latches -- reapStaleClaims' own
            // SELECT (no FOR UPDATE, so unaffected) still finds the item stale/SENDING on the
            // reaper thread below, but its UPDATE blocks on this lock until releaseLock fires.
            val lockHolder =
                Thread {
                    transaction {
                        AccountingExportItemTable
                            .selectAll()
                            .where { AccountingExportItemTable.id eq itemId }
                            .forUpdate()
                            .single()
                        lockAcquired.countDown()
                        check(releaseLock.await(20, TimeUnit.SECONDS)) { "release signal never arrived" }
                        // The concurrent user action racing into reapStaleClaims' SELECT-to-UPDATE
                        // window -- committed (releasing the lock) strictly BEFORE reapStaleClaims'
                        // own UPDATE can proceed, since that UPDATE is queued behind this same lock.
                        AccountingExportStore.abortRun(runId = runId, now = now)
                    }
                    lockHolderDone.countDown()
                }
            lockHolder.start()
            check(lockAcquired.await(20, TimeUnit.SECONDS)) { "lock was never acquired" }

            val reaper = Thread { AccountingExportStore.reapStaleClaims(staleCutoff = now.plusDuration(-(5.minutes)), now = now) }
            reaper.start()
            // Give the reaper thread time to run its own SELECT and reach its own blocking UPDATE
            // before releasing the lock -- otherwise there is nothing left to race against.
            Thread.sleep(300)
            releaseLock.countDown()
            check(lockHolderDone.await(20, TimeUnit.SECONDS)) { "lockHolder did not finish in time" }
            reaper.join(20_000)
            reaper.isAlive shouldBe false

            val row = transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemId }.single() }
            row[AccountingExportItemTable.status] shouldBe AccountingExportItemStatus.UNKNOWN
            row[AccountingExportItemTable.errorCode] shouldBe "ABORTED_WHILE_SENDING"
        }

        test("after every item in a run reaches a terminal state, the run itself finalizes to COMPLETED and active_key clears") {
            val actor = newMember()
            val (runId, _) = newRunWithOneItem(actor)
            val (adapter, poller) = connectAdapter()
            adapter.outcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "ext-999")
            poller.tick()
            val run = requireNotNull(AccountingExportStore.getRun(runId))
            run.status shouldBe AccountingExportRunStatus.COMPLETED
            run.succeeded shouldBe 1
        }

        test(
            "a run whose planned vouchers are ALL already exported finalizes to COMPLETED right at creation, " +
                "instead of staying RUNNING/active_key-locked forever (Fund 2026-09-07: createRun itself always " +
                "starts a run RUNNING, and a run with zero PENDING items is never touched by the poller's send " +
                "phase -- the caller, AccountingExportService.startExport, must recompute right after createRun; " +
                "this test exercises exactly that one-line fix via the same Store call it makes)",
        ) {
            val actor = newMember()
            val (runId, itemId) = newRunWithOneItem(actor, alreadyExported = true)
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe true
            requireNotNull(AccountingExportStore.getRun(runId)).status shouldBe AccountingExportRunStatus.RUNNING

            AccountingExportStore.recomputeRunCounts(runId = runId, now = dbClockNow())

            val run = requireNotNull(AccountingExportStore.getRun(runId))
            run.status shouldBe AccountingExportRunStatus.COMPLETED
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false
            itemStatus(itemId) shouldBe AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
        }

        test(
            "AccountingExportPoller.tick finalizes a run that started RUNNING with zero PENDING items purely via " +
                "the activeRuns() safety net -- NEITHER the reap phase NOR the send phase ever touches this run's " +
                "single item (it is SKIPPED_ALREADY_EXPORTED from creation, never SENDING/PENDING), so before Fund " +
                "2026-09-07 review Runde 2 (Befund 4) wired activeRuns() into Phase B, a real tick() (as opposed to " +
                "the test above's direct recomputeRunCounts call) left this run RUNNING/active_key-locked forever",
        ) {
            val actor = newMember()
            val (runId, itemId) = newRunWithOneItem(actor, alreadyExported = true)
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe true
            requireNotNull(AccountingExportStore.getRun(runId)).status shouldBe AccountingExportRunStatus.RUNNING

            val (_, poller) = connectAdapter()
            poller.tick()

            val run = requireNotNull(AccountingExportStore.getRun(runId))
            run.status shouldBe AccountingExportRunStatus.COMPLETED
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false
            itemStatus(itemId) shouldBe AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
        }

        test(
            "retryFailed on an ABORTED run reopens it to RUNNING (hasActiveRun becomes true again) instead of " +
                "leaving the run permanently ABORTED while its reset items actually get resent behind it " +
                "(Fund 2026-09-07: retryFailed's reopen branch previously only covered COMPLETED/" +
                "COMPLETED_WITH_ERRORS, never ABORTED)",
        ) {
            val actor = newMember()
            val (runId, itemId) = newRunWithOneItem(actor)
            AccountingExportStore.abortRun(runId = runId, now = dbClockNow())
            requireNotNull(AccountingExportStore.getRun(runId)).status shouldBe AccountingExportRunStatus.ABORTED
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false
            itemStatus(itemId) shouldBe AccountingExportItemStatus.FAILED

            val reopened = requireNotNull(AccountingExportStore.retryFailed(runId = runId, now = dbClockNow()))
            reopened.status shouldBe AccountingExportRunStatus.RUNNING
            // hasActiveRun() being true again correctly blocks a concurrent second start for the
            // same provider -- the exact gap the reopen fixes.
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe true
            itemStatus(itemId) shouldBe AccountingExportItemStatus.PENDING

            val (adapter, poller) = connectAdapter()
            adapter.outcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "ext-after-retry")
            poller.tick()
            itemStatus(itemId) shouldBe AccountingExportItemStatus.SUCCEEDED
            val finalRun = requireNotNull(AccountingExportStore.getRun(runId))
            finalRun.status shouldBe AccountingExportRunStatus.COMPLETED
            AccountingExportStore.hasActiveRun(AccountingExportProvider.LEXOFFICE) shouldBe false
        }

        test(
            "a Retryable outcome with a provider-supplied retryAfter honors that delay instead of the fixed " +
                "2s/4s/8s backoff table (Fund 2026-09-07: outcome.retryAfter was previously read by " +
                "LexofficeApiClient but never consumed by the poller at all)",
        ) {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor)
            val (adapter, poller) = connectAdapter()
            adapter.outcomes += VoucherPushOutcome.Retryable(errorCode = "RATE_LIMITED", message = "429", retryAfter = 90.seconds)
            val beforeTick = dbClockNow()
            poller.tick()
            val nextAttemptAt =
                transaction {
                    AccountingExportItemTable
                        .selectAll()
                        .where { AccountingExportItemTable.id eq itemId }
                        .single()[AccountingExportItemTable.nextAttemptAt]
                }
            val scheduledDelay =
                requireNotNull(nextAttemptAt).toInstant(TimeZone.UTC) - beforeTick.toInstant(TimeZone.UTC)
            // Comfortably beyond the 8s ceiling the fixed table would ever produce on a first
            // attempt (2s) -- proves the 90s Retry-After was actually used, not silently discarded.
            (scheduledDelay >= 60.seconds) shouldBe true
        }

        test("markSucceeded truncates an over-long external_voucher_id instead of throwing on the length-limited column") {
            val actor = newMember()
            val (_, itemId) = newRunWithOneItem(actor)
            val entryId =
                transaction {
                    val row = AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemId }.single()
                    row[AccountingExportItemTable.journalEntryId]
                }
            val overLong = "V".repeat(100) // external_voucher_id is VARCHAR(64)
            AccountingExportStore.markSucceeded(
                id = itemId,
                provider = AccountingExportProvider.LEXOFFICE,
                journalEntryId = entryId,
                externalVoucherId = overLong,
                now = dbClockNow(),
            )
            val stored =
                transaction {
                    val row = AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq itemId }.single()
                    row[AccountingExportItemTable.externalVoucherId]
                }
            stored?.length shouldBe 64
        }

        // ── Welle V1.4.5.4 "sevDesk-Live-Anbindung" ─────────────────────────────────────────

        test(
            "two providers, two adapters: a SEVDESK item reaches ONLY the SEVDESK adapter, a LEXOFFICE item " +
                "reaches ONLY the LEXOFFICE adapter -- adaptersByProvider dispatches by item.provider, never by insertion order",
        ) {
            val actor = newMember()
            val (_, lexItemId) = newRunWithOneItem(actor, provider = AccountingExportProvider.LEXOFFICE)
            val (_, sevItemId) = newRunWithOneItem(actor, provider = AccountingExportProvider.SEVDESK)
            val (lexAdapter, sevAdapter, poller) = connectTwoAdapters()
            lexAdapter.outcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "lex-ext-1")
            sevAdapter.outcomes += VoucherPushOutcome.Succeeded(externalVoucherId = "sev-ext-1")

            poller.tick()

            itemStatus(lexItemId) shouldBe AccountingExportItemStatus.SUCCEEDED
            itemStatus(sevItemId) shouldBe AccountingExportItemStatus.SUCCEEDED
            lexAdapter.callCount shouldBe 1
            sevAdapter.callCount shouldBe 1

            val lexRow = transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq lexItemId }.single() }
            val sevRow = transaction { AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq sevItemId }.single() }
            lexRow[AccountingExportItemTable.exportedKey]?.startsWith("LEXOFFICE:") shouldBe true
            sevRow[AccountingExportItemTable.exportedKey]?.startsWith("SEVDESK:") shouldBe true
        }
    })

// TimeZone.UTC explicitly -- must match AccountingExportPoller's own default `clock` exactly (see
// its constructor), or a stale-claim test computing "10 minutes before now" in a DIFFERENT zone
// than the poller's own `now` would be off by that zone's offset, not 10 minutes.
private fun dbClockNow(): LocalDateTime =
    network.lapis.cloud.server.db.DbClock
        .nowLocalDateTime(TimeZone.UTC)
