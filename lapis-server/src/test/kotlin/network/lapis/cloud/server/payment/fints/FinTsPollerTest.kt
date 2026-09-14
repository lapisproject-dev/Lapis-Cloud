package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
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
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". Regression coverage for [FinTsPoller] --
 * mirrors [network.lapis.cloud.server.payment.dunning.DunningPollerTest]'s own house style: every
 * test constructs its own [FinTsPoller] with an injected fixed `clock` and calls [FinTsPoller.tick]
 * directly, zero timing dependency. [FakeFinTsStatementFetcher] stands in for hbci4j -- no real
 * bank, no network, ever.
 */
class FinTsPollerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdAccountIds = mutableListOf<Uuid>()
        val secretBox = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))
        val now = LocalDateTime(2026, 9, 13, 12, 0)

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                if (createdAccountIds.isNotEmpty()) {
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
                    it[displayName] = "FinTsPoller-Testmitglied"
                    it[email] = "fints-poller-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
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
            iban: String = "DE${(10_000_000 + createdAccountIds.size).toString().padStart(20, '0')}",
        ): Uuid {
            val id = Uuid.random()
            val nowTs = kotlinx.datetime.LocalDateTime(2026, 9, 1, 0, 0)
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
                }
            }
            createdAccountIds += id
            return id
        }

        fun statusOf(accountId: Uuid): FinTsStatus =
            transaction { BankAccountTable.selectAll().where { BankAccountTable.id eq accountId }.single()[BankAccountTable.fintsStatus] }

        fun errorCodeOf(accountId: Uuid): String? =
            transaction {
                BankAccountTable.selectAll().where { BankAccountTable.id eq accountId }.single()[BankAccountTable.fintsLastErrorCode]
            }

        fun buildPoller(
            fetcher: FinTsStatementFetcher,
            mailer: FakeFinTsReauthNotificationMailer,
        ): FinTsPoller =
            FinTsPoller(
                config = FinTsConfig.load { null },
                fetcher = fetcher,
                importService = BankStatementImportService(secretBox = secretBox),
                secretBox = secretBox,
                reauthMailer = mailer,
                clock = { now },
            )

        test("secretBox == null -> tick() is a complete no-op, no throw") {
            val fetcher = FakeFinTsStatementFetcher()
            val poller =
                FinTsPoller(
                    config = FinTsConfig.load { null },
                    fetcher = fetcher,
                    importService = BankStatementImportService(secretBox = null),
                    secretBox = null,
                    reauthMailer = FakeFinTsReauthNotificationMailer(),
                    clock = { now },
                )
            runBlocking { poller.tick() }
            fetcher.calls.size shouldBe 0
        }

        test("NOT_CONFIGURED and REAUTH_REQUIRED accounts are never fetched") {
            val admin = createAdmin()
            // REAUTH_REQUIRED -- credentials stay filled (the alles-oder-nichts CHECK requires
            // this for any non-NOT_CONFIGURED status), only the status flips.
            val reauthId = createActiveAccount(admin, iban = "DE00000000000000000010")
            transaction {
                BankAccountTable.update({ BankAccountTable.id eq reauthId }) { it[fintsStatus] = FinTsStatus.REAUTH_REQUIRED }
            }
            // NOT_CONFIGURED -- a genuinely never-activated account (no credentials at all), since
            // the alles-oder-nichts CHECK forbids NOT_CONFIGURED with credentials still populated.
            val notConfiguredId = Uuid.random()
            transaction {
                val nowTs = kotlinx.datetime.LocalDateTime(2026, 9, 1, 0, 0)
                BankAccountTable.insert {
                    it[BankAccountTable.id] = notConfiguredId
                    it[label] = "Testkonto"
                    it[iban] = "DE00000000000000000011"
                    it[bic] = null
                    it[bankName] = null
                    it[isDefault] = false
                    it[defaultMarker] = null
                    it[createdBy] = admin
                    it[createdAt] = nowTs
                    it[updatedAt] = nowTs
                }
            }
            createdAccountIds += notConfiguredId

            val fetcher = FakeFinTsStatementFetcher()
            val poller = buildPoller(fetcher, FakeFinTsReauthNotificationMailer())
            runBlocking { poller.tick() }
            fetcher.calls.size shouldBe 0
        }

        test("Failed(BANK_UNAVAILABLE) and Failed(TIMEOUT) stay ACTIVE, only the error code is recorded, no mail") {
            val admin = createAdmin()
            val accountId = createActiveAccount(admin)
            val mailer = FakeFinTsReauthNotificationMailer()
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Failed(FinTsErrorCode.BANK_UNAVAILABLE)))
            val poller = buildPoller(fetcher, mailer)

            runBlocking { poller.tick() }

            statusOf(accountId) shouldBe FinTsStatus.ACTIVE
            errorCodeOf(accountId) shouldBe "BANK_UNAVAILABLE"
            mailer.calls.size shouldBe 0
        }

        test("Failed(AUTH_FAILED) -> REAUTH_REQUIRED, exactly one mail") {
            val admin = createAdmin()
            val accountId = createActiveAccount(admin)
            val mailer = FakeFinTsReauthNotificationMailer()
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Failed(FinTsErrorCode.AUTH_FAILED)))
            val poller = buildPoller(fetcher, mailer)

            runBlocking { poller.tick() }

            statusOf(accountId) shouldBe FinTsStatus.REAUTH_REQUIRED
            errorCodeOf(accountId) shouldBe "AUTH_FAILED"
            mailer.calls.size shouldBe 1
        }

        test("TanRequired -> REAUTH_REQUIRED, exactly one mail") {
            val admin = createAdmin()
            val accountId = createActiveAccount(admin)
            val mailer = FakeFinTsReauthNotificationMailer()
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.TanRequired))
            val poller = buildPoller(fetcher, mailer)

            runBlocking { poller.tick() }

            statusOf(accountId) shouldBe FinTsStatus.REAUTH_REQUIRED
            mailer.calls.size shouldBe 1
        }

        test(
            "once REAUTH_REQUIRED, an account is no longer a poll candidate at all -- a later tick neither re-fetches it nor sends a second mail",
        ) {
            val admin = createAdmin()
            val accountId = createActiveAccount(admin)
            val mailer = FakeFinTsReauthNotificationMailer()
            // Only ONE result queued -- if the second tick fetched this account again (a bug: the
            // candidate query stopped filtering on fints_status = ACTIVE), FakeFinTsStatementFetcher's
            // own "no result queued" default (Failed(BANK_UNAVAILABLE)) would silently mask it as a
            // transient error instead of failing loudly -- the explicit `fetcher.calls.size shouldBe 1`
            // assertion below is what actually catches that regression.
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Failed(FinTsErrorCode.AUTH_FAILED)))
            val poller = buildPoller(fetcher, mailer)

            runBlocking {
                poller.tick()
                // Account is now REAUTH_REQUIRED -- the candidate query (`fints_status = 'ACTIVE'`)
                // must no longer select it, so a second tick is a complete no-op for this account.
                poller.tick()
            }

            statusOf(accountId) shouldBe FinTsStatus.REAUTH_REQUIRED
            fetcher.calls.size shouldBe 1
            mailer.calls.size shouldBe 1
        }

        test(
            "a genuine SECOND ACTIVE -> REAUTH_REQUIRED transition (account reactivated in between) sends its OWN mail, not suppressed by the first",
        ) {
            // Distinguishes "mail only on transition" (this test: two REAL transitions, two mails)
            // from "mail only once because the candidate query stopped selecting the row" (the test
            // above): here the account is put back to ACTIVE between ticks -- exactly what a real
            // ADMIN reactivation via beginFinTsSetup does -- so the second failure is a genuinely NEW
            // incident and must notify BOARD/ADMIN again, not be silently swallowed as "already told them".
            val admin = createAdmin()
            val accountId = createActiveAccount(admin)
            val mailer = FakeFinTsReauthNotificationMailer()
            val fetcher =
                FakeFinTsStatementFetcher(
                    listOf(FinTsFetchResult.Failed(FinTsErrorCode.AUTH_FAILED), FinTsFetchResult.Failed(FinTsErrorCode.AUTH_FAILED)),
                )
            val poller = buildPoller(fetcher, mailer)

            runBlocking {
                poller.tick()
                transaction { BankAccountTable.update({ BankAccountTable.id eq accountId }) { it[fintsStatus] = FinTsStatus.ACTIVE } }
                poller.tick()
            }

            statusOf(accountId) shouldBe FinTsStatus.REAUTH_REQUIRED
            mailer.calls.size shouldBe 2
        }

        test("Failed(STATEMENT_FORMAT_UNSUPPORTED) stays ACTIVE, only the error code changes, no mail") {
            val admin = createAdmin()
            val accountId = createActiveAccount(admin)
            val mailer = FakeFinTsReauthNotificationMailer()
            val fetcher = FakeFinTsStatementFetcher(listOf(FinTsFetchResult.Failed(FinTsErrorCode.STATEMENT_FORMAT_UNSUPPORTED)))
            val poller = buildPoller(fetcher, mailer)

            runBlocking { poller.tick() }

            statusOf(accountId) shouldBe FinTsStatus.ACTIVE
            errorCodeOf(accountId) shouldBe "STATEMENT_FORMAT_UNSUPPORTED"
            mailer.calls.size shouldBe 0
        }

        test("one account throwing from the fetcher does not stop the tick from processing the next account") {
            val admin = createAdmin()
            val accountId1 = createActiveAccount(admin, iban = "DE00000000000000000001")
            val accountId2 = createActiveAccount(admin, iban = "DE00000000000000000002")
            val mailer = FakeFinTsReauthNotificationMailer()
            val throwingFetcher =
                object : FinTsStatementFetcher {
                    var calls = 0

                    override fun fetch(
                        credentials: FinTsCredentials,
                        from: kotlinx.datetime.LocalDate,
                        to: kotlinx.datetime.LocalDate,
                    ): FinTsFetchResult {
                        calls++
                        if (calls == 1) throw IllegalStateException("boom")
                        return FinTsFetchResult.Failed(FinTsErrorCode.BANK_UNAVAILABLE)
                    }
                }
            val poller = buildPoller(throwingFetcher, mailer)

            runBlocking { poller.tick() }

            // Both accounts must still be ACTIVE -- neither NPE'd nor got skipped because the OTHER threw.
            statusOf(accountId1) shouldBe FinTsStatus.ACTIVE
            statusOf(accountId2) shouldBe FinTsStatus.ACTIVE
            throwingFetcher.calls shouldBe 2
        }
    })
