package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.FakeFinTsReauthNotificationMailer
import network.lapis.cloud.server.payment.bankstatement.BankStatementImportService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FinTsStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * Review fix (MEDIUM, test coverage) -- [FinTsPoller.processOneAccount]'s watermark-floor KDoc
 * documents a follow-up MEDIUM finding this test pins: clamping a stale watermark up to
 * `now - fetchWindowDays` silently widens the SKIPPED gap between the old watermark and the floor
 * -- a fetch that then SUCCEEDS used to clear `fints_last_error_code`/advance
 * `fints_last_fetch_to` exactly as if nothing had ever been missed. The fix persists
 * [FETCH_WINDOW_GAP_ERROR_CODE] instead of `null` on that one success; this test proves (a) the
 * fetch window is actually clamped to the floor (not the ancient watermark), (b) the marker is
 * set on a gap-covering success, (c) an ordinary (non-gap) success still clears the code exactly
 * as before this fix, and (d) the marker self-heals on the NEXT gap-free tick.
 *
 * House style mirrors [FinTsPollerTest]/[FinTsIngestReuseTest] -- self-contained fixture helpers,
 * fixed injected `clock`, zero timing dependency.
 *
 * Review fix (MEDIUM, test coverage -- Runde 4): the three `fintsGapXxx` columns
 * ([network.lapis.cloud.server.db.generated.BankAccountTable.fintsGapFrom]/`fintsGapTo`/
 * `fintsGapDetectedAt`, added by the Runde-3 fix) previously had NO behavioural test anywhere in
 * this codebase -- only [BankAccountSchemaDriftTest] checked their existence/nullability, never
 * their VALUES. `docs/architecture/bank-account.adoc` claimed this class already "covers both
 * signals", which was false; that claim is corrected alongside this fix. The first test below now
 * also asserts the durable columns after a gap-covering success, and the self-heal test asserts
 * that a SUBSEQUENT gap-free tick leaves them UNCHANGED (durable, never cleared by
 * [FinTsPoller.processOneAccount]'s unconditional-overwrite branch that clears the TRANSIENT
 * `fints_last_error_code` marker) -- the exact property [FinTsPoller.kt]'s
 * `if (fetchWindowGapDetected) { ... }` guard around the three `fintsGapXxx` assignments exists to
 * provide, and the exact regression that guard's removal ("simplifying" it to an unconditional
 * assignment, like the two lines above it) would silently reintroduce.
 */
class FinTsPollerWatermarkGapTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdAccountIds = mutableListOf<Uuid>()
        val secretBox = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))
        val now = LocalDateTime(2026, 9, 13, 12, 0)

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                // Every test in this file feeds the poller a real FinTsFetchResult.Mt940 success,
                // which runs the REAL BankStatementImportService.import() -- so, unlike
                // FinTsPollerTest (which only ever exercises Failed/AUTH_FAILED results), cleanup
                // here must also remove the bank_statement_import/bank_statement_line rows those
                // successful imports create, same as FinTsIngestReuseTest's own afterEach, BEFORE
                // deleting bank_account (no ON DELETE CASCADE on that FK either).
                if (createdAccountIds.isNotEmpty()) {
                    val importIds =
                        BankStatementImportTable.selectAll().where { BankStatementImportTable.bankAccountId inList createdAccountIds }.map {
                            it[BankStatementImportTable.id]
                        }
                    if (importIds.isNotEmpty()) {
                        BankStatementLineTable.deleteWhere { BankStatementLineTable.importId inList importIds }
                        BankStatementImportTable.deleteWhere { BankStatementImportTable.id inList importIds }
                    }
                    BankAccountTable.deleteWhere { BankAccountTable.id inList createdAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                createdMemberIds.forEach {
                    AccountTable.deleteWhere { AccountTable.memberId eq it }
                    MemberTable.deleteWhere { MemberTable.id eq it }
                }
            }
            createdMemberIds.clear()
            createdAccountIds.clear()
        }

        fun createAdmin(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "FinTsPoller-Watermark-Testmitglied"
                    it[email] = "fints-poller-watermark-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        fun createActiveAccount(
            activatedBy: Uuid,
            iban: String,
            lastFetchTo: LocalDate?,
        ): Uuid {
            val id = Uuid.random()
            val nowTs = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                BankAccountTable.insert {
                    it[BankAccountTable.id] = id
                    it[label] = "Testkonto"
                    it[BankAccountTable.iban] = iban
                    it[bic] = null
                    it[bankName] = null
                    it[isDefault] = false
                    it[defaultMarker] = null
                    it[createdBy] = activatedBy
                    it[createdAt] = nowTs
                    it[updatedAt] = nowTs
                    it[fintsBlz] = "12345678"
                    it[fintsUrl] = "https://example.com/hbci"
                    it[fintsUserIdCiphertext] = secretBox.seal(plaintext = "user-$id", aad = id.toString())
                    it[fintsPinCiphertext] = secretBox.seal(plaintext = "pin-$id", aad = id.toString())
                    it[fintsStatus] = FinTsStatus.ACTIVE
                    it[fintsActivatedBy] = activatedBy
                    it[fintsActivatedAt] = nowTs
                    it[fintsPinSetAt] = nowTs
                    it[fintsLastFetchTo] = lastFetchTo
                }
            }
            createdAccountIds += id
            return id
        }

        fun errorCodeOf(accountId: Uuid): String? =
            transaction {
                BankAccountTable.selectAll().where { BankAccountTable.id eq accountId }.single()[BankAccountTable.fintsLastErrorCode]
            }

        fun fetchToOf(accountId: Uuid): LocalDate? =
            transaction {
                BankAccountTable.selectAll().where { BankAccountTable.id eq accountId }.single()[BankAccountTable.fintsLastFetchTo]
            }

        /** The DURABLE gap record -- see the class KDoc "Review fix (MEDIUM, test coverage -- Runde 4)". */
        fun gapRecordOf(accountId: Uuid): Triple<LocalDate?, LocalDate?, LocalDateTime?> =
            transaction {
                val row = BankAccountTable.selectAll().where { BankAccountTable.id eq accountId }.single()
                Triple(
                    row[BankAccountTable.fintsGapFrom],
                    row[BankAccountTable.fintsGapTo],
                    row[BankAccountTable.fintsGapDetectedAt],
                )
            }

        fun buildPoller(fetcher: FinTsStatementFetcher): FinTsPoller =
            FinTsPoller(
                config = FinTsConfig.load { null },
                fetcher = fetcher,
                importService = BankStatementImportService(secretBox = secretBox),
                secretBox = secretBox,
                reauthMailer = FakeFinTsReauthNotificationMailer(),
                clock = { now },
            )

        /** Same minimal well-formed MT940 fixture [FinTsIngestReuseTest] uses, one booking. */
        fun sampleMt940(
            iban: String,
            reference: String,
        ): ByteArray =
            listOf(
                ":20:$reference",
                ":25:$iban",
                ":60F:C260901EUR0,00",
                ":61:2609010901C50,00NTRFNONREF",
                ":86:?20Testueberweisung",
                ":62F:C260901EUR50,00",
            ).joinToString("\r\n").toByteArray(Charsets.UTF_8)

        val fetchWindowDays = FinTsConfig.load { null }.fetchWindowDays
        val oldestAllowedFrom = now.date.minus(fetchWindowDays, DateTimeUnit.DAY)

        // A stale watermark is clamped to oldestAllowedFrom (the skipped gap is never fetched), and
        // a successful fetch OVER that gap marks FETCH_WINDOW_GAP instead of clearing the error code.
        test("a watermark far older than the fetch window is clamped and the successful fetch marks FETCH_WINDOW_GAP") {
            val admin = createAdmin()
            val staleWatermark = LocalDate(2026, 1, 31) // months before oldestAllowedFrom (2026-08-30 for a 14d window)
            val accountId = createActiveAccount(activatedBy = admin, iban = "DE89370400440532013001", lastFetchTo = staleWatermark)
            val bytes = sampleMt940(iban = "DE89370400440532013001", reference = "GAP01")
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Mt940(bytes = bytes, statementCount = 1)))
            val poller = buildPoller(fetcher)

            runBlocking { poller.tick() }

            // Clamped to the floor, NOT the stale watermark itself (staleWatermark != oldestAllowedFrom).
            fetcher.calls.single().from shouldBe oldestAllowedFrom
            errorCodeOf(accountId) shouldBe "FETCH_WINDOW_GAP"
            fetchToOf(accountId) shouldBe now.date
            // Durable record (Review fix, Runde 3) -- the SKIPPED range, not the fetched one.
            val (gapFrom, gapTo, gapDetectedAt) = gapRecordOf(accountId)
            gapFrom shouldBe staleWatermark
            gapTo shouldBe oldestAllowedFrom
            gapDetectedAt shouldBe now
        }

        test("a watermark within the fetch window is NOT clamped and a successful fetch clears the error code as before this fix") {
            val admin = createAdmin()
            val recentWatermark = now.date.minus(3, DateTimeUnit.DAY)
            val accountId = createActiveAccount(activatedBy = admin, iban = "DE89370400440532013002", lastFetchTo = recentWatermark)
            val bytes = sampleMt940(iban = "DE89370400440532013002", reference = "NOGAP01")
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Mt940(bytes = bytes, statementCount = 1)))
            val poller = buildPoller(fetcher)

            runBlocking { poller.tick() }

            fetcher.calls.single().from shouldBe recentWatermark
            errorCodeOf(accountId) shouldBe null
            fetchToOf(accountId) shouldBe now.date
            // No gap this tick -- the durable columns are never written in the first place.
            gapRecordOf(accountId) shouldBe Triple(null, null, null)
        }

        test("a null watermark (never fetched before) is treated like a fresh account, no gap marker") {
            val admin = createAdmin()
            val accountId = createActiveAccount(activatedBy = admin, iban = "DE89370400440532013003", lastFetchTo = null)
            val bytes = sampleMt940(iban = "DE89370400440532013003", reference = "FIRST01")
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Mt940(bytes = bytes, statementCount = 1)))
            val poller = buildPoller(fetcher)

            runBlocking { poller.tick() }

            fetcher.calls.single().from shouldBe oldestAllowedFrom
            errorCodeOf(accountId) shouldBe null
            gapRecordOf(accountId) shouldBe Triple(null, null, null)
        }

        test("the FETCH_WINDOW_GAP marker self-heals on the next gap-free tick") {
            val admin = createAdmin()
            val staleWatermark = LocalDate(2026, 1, 31)
            val accountId = createActiveAccount(activatedBy = admin, iban = "DE89370400440532013004", lastFetchTo = staleWatermark)
            val bytes1 = sampleMt940(iban = "DE89370400440532013004", reference = "GAP02")
            val bytes2 = sampleMt940(iban = "DE89370400440532013004", reference = "GAP03")
            val fetcher =
                FakeFinTsStatementFetcher(
                    listOf(
                        FinTsFetchResult.Mt940(bytes = bytes1, statementCount = 1),
                        FinTsFetchResult.Mt940(bytes = bytes2, statementCount = 1),
                    ),
                )
            val poller = buildPoller(fetcher)

            runBlocking {
                poller.tick()
                errorCodeOf(accountId) shouldBe "FETCH_WINDOW_GAP"
                val (gapFromAfterTick1, gapToAfterTick1, gapDetectedAtAfterTick1) = gapRecordOf(accountId)
                gapFromAfterTick1 shouldBe staleWatermark
                gapToAfterTick1 shouldBe oldestAllowedFrom
                gapDetectedAtAfterTick1 shouldBe now

                // Second tick, same fixed `now` -- watermark is now `now.date` (set by the first
                // tick above), so `from == watermark`, no gap this time. The TRANSIENT marker
                // self-heals (cleared, matching the pre-existing assertion below), but the DURABLE
                // record must survive UNCHANGED -- this is the exact property the Review-fix-Runde-3
                // guard (`if (fetchWindowGapDetected) { ... }` around the three fintsGapXxx
                // assignments in FinTsPoller.kt) exists to provide. Before this test existed, nothing
                // in this codebase would have caught that guard being "simplified" away.
                poller.tick()
                errorCodeOf(accountId) shouldBe null
                gapRecordOf(accountId) shouldBe Triple(gapFromAfterTick1, gapToAfterTick1, gapDetectedAtAfterTick1)
            }
        }
    })
