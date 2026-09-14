package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
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
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- the plan's "Kernstück": proves that the
 * poller's digest-precheck (see [FinTsPoller] KDoc "Digest-Vorprüfung") and a manual file-upload
 * through the REAL, unmodified [BankStatementImportService.import] produce IDENTICAL
 * `bank_statement_line.fingerprint` values for identical bytes, and that an identical (already-
 * seen) fetch window advances the poller's own bookkeeping WITHOUT creating a second
 * `bank_statement_import` row or throwing `ALREADY_IMPORTED`.
 */
class FinTsIngestReuseTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdAccountIds = mutableListOf<Uuid>()
        val secretBox = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                val importIds =
                    BankStatementImportTable.selectAll().where { BankStatementImportTable.bankAccountId inList createdAccountIds }.map {
                        it[BankStatementImportTable.id]
                    }
                if (importIds.isNotEmpty()) {
                    BankStatementLineTable.deleteWhere { BankStatementLineTable.importId inList importIds }
                    BankStatementImportTable.deleteWhere { BankStatementImportTable.id inList importIds }
                }
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
                    it[displayName] = "FinTsIngestReuse-Testmitglied"
                    it[email] = "fints-ingest-reuse-${Uuid.random()}@example.org"
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
            admin: Uuid,
            iban: String,
        ): Uuid {
            val id = Uuid.random()
            val nowTs =
                network.lapis.cloud.server.db.DbClock
                    .nowLocalDateTime()
            transaction {
                BankAccountTable.insert {
                    it[BankAccountTable.id] = id
                    it[label] = "Testkonto"
                    it[BankAccountTable.iban] = iban
                    it[bic] = null
                    it[bankName] = null
                    it[isDefault] = false
                    it[defaultMarker] = null
                    it[createdBy] = admin
                    it[createdAt] = nowTs
                    it[updatedAt] = nowTs
                    it[fintsBlz] = "12345678"
                    it[fintsUrl] = "https://example.com/hbci"
                    it[fintsUserIdCiphertext] = secretBox.seal(plaintext = "user", aad = id.toString())
                    it[fintsPinCiphertext] = secretBox.seal(plaintext = "pin", aad = id.toString())
                    it[fintsStatus] = FinTsStatus.ACTIVE
                    it[fintsActivatedBy] = admin
                    it[fintsActivatedAt] = nowTs
                    it[fintsPinSetAt] = nowTs
                }
            }
            createdAccountIds += id
            return id
        }

        /**
         * Minimal well-formed MT940 -- one booking, EXACT shape [BankStatementImportServiceTest]'s
         * own fixtures already use and verified to be accepted by the real parser (no `:28C:` line,
         * no trailing `-` sentinel, `\r\n`-joined, no trailing newline).
         */
        fun sampleMt940(iban: String): ByteArray =
            listOf(
                ":20:STARTUMS",
                ":25:$iban",
                ":60F:C260901EUR0,00",
                ":61:2609010901C50,00NTRFNONREF",
                ":86:?20Testueberweisung",
                ":62F:C260901EUR50,00",
            ).joinToString("\r\n").toByteArray(Charsets.UTF_8)

        test(
            "the SAME booking line re-imported for the SAME account via a DIFFERENT (overlapping-window) file dedupes via the LINE fingerprint, not the file digest",
        ) {
            // `BankStatementFingerprint`'s own KDoc: the fingerprint deliberately does NOT include
            // the file/import identity (only account + line content + an occurrence index for
            // WITHIN-file repeats) -- so the SAME booking on the SAME account, re-imported from a
            // DIFFERENT file (a poller's sliding fetch window naturally overlaps its own last run),
            // must dedupe at the LINE level (`insertIgnore` on `uq_bank_statement_line_fingerprint`)
            // even though the file-level digest precheck (`FinTsPoller` KDoc "Digest-Vorprüfung",
            // covered by the second test below) never even fires here because the files differ.
            val admin = createAdmin()
            val accountId = createActiveAccount(admin, "DE89370400440532013000")
            val importService = BankStatementImportService(secretBox = secretBox)

            fun statementWith(reference: String) =
                listOf(
                    ":20:$reference",
                    ":25:DE89370400440532013000",
                    ":60F:C260901EUR0,00",
                    ":61:2609010901C50,00NTRFNONREF",
                    ":86:?20Testueberweisung",
                    ":62F:C260901EUR50,00",
                ).joinToString("\r\n").toByteArray(Charsets.UTF_8)

            // "Manual upload" path -- the exact same import() call BankStatementRoutes itself uses.
            val manualResult =
                importService.import(
                    bytes = statementWith("MANUAL01"),
                    fileName = "manual-upload.sta",
                    uploadedBy = admin,
                    uploaderRole = AccountRole.ADMIN,
                    bankAccountId = accountId,
                )
            manualResult.lineCount shouldBe 1
            manualResult.duplicateCount shouldBe 0

            // "Poller" path -- SAME account, SAME booking line, different `:20:` reference (so the
            // FILE digest differs -- this exercises LINE-level dedup, not the file-level precheck).
            val pollerResult =
                importService.import(
                    bytes = statementWith("POLLER01"),
                    fileName = "fints-3000-2026-08-01-2026-09-01.sta",
                    uploadedBy = admin,
                    uploaderRole = AccountRole.ADMIN,
                    bankAccountId = accountId,
                )
            pollerResult.lineCount shouldBe 1
            pollerResult.duplicateCount shouldBe 1

            // Exactly ONE bank_statement_line row exists for this fingerprint in total -- the
            // second import's identical line was recognized as a duplicate, never double-booked.
            val totalRowsForThisAccount =
                transaction {
                    BankStatementLineTable
                        .selectAll()
                        .where {
                            BankStatementLineTable.importId inList
                                listOf(Uuid.parse(manualResult.importId), Uuid.parse(pollerResult.importId))
                        }.count()
                }
            totalRowsForThisAccount shouldBe 1L
        }

        test("an identical (already-seen) fetch window advances bookkeeping WITHOUT a second bank_statement_import row or a 409") {
            val admin = createAdmin()
            val accountId = createActiveAccount(admin, "DE44500105175407324931")
            val bytes = sampleMt940("DE44500105175407324931")
            val importService = BankStatementImportService(secretBox = secretBox)
            val mailer = FakeFinTsReauthNotificationMailer()
            val fetcher =
                FakeFinTsStatementFetcher(
                    listOf(
                        FinTsFetchResult.Mt940(bytes = bytes, statementCount = 1),
                        FinTsFetchResult.Mt940(bytes = bytes, statementCount = 1),
                    ),
                )
            val poller =
                FinTsPoller(
                    config = FinTsConfig.load { null },
                    fetcher = fetcher,
                    importService = importService,
                    secretBox = secretBox,
                    reauthMailer = mailer,
                )

            kotlinx.coroutines.runBlocking {
                poller.tick()
                val countAfterFirst =
                    transaction {
                        BankStatementImportTable
                            .selectAll()
                            .where {
                                BankStatementImportTable.bankAccountId eq
                                    accountId
                            }.count()
                    }
                countAfterFirst shouldBe 1L

                // Second tick, SAME bytes (a re-run over an overlapping window) -- digest precheck
                // must short-circuit before import() ever throws ALREADY_IMPORTED.
                poller.tick()
                val countAfterSecond =
                    transaction {
                        BankStatementImportTable
                            .selectAll()
                            .where {
                                BankStatementImportTable.bankAccountId eq
                                    accountId
                            }.count()
                    }
                countAfterSecond shouldBe 1L
            }
        }
    })
