package network.lapis.cloud.server.payment.bankstatement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.BankAccountSnapshot
import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val IBAN_1 = "DE89370400440532013000"
private const val IBAN_2 = "DE02120300000000202051"
private const val IBAN_3 = "DE12500105170648489890"

class BankAccountStoreTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdBankAccountIds = mutableListOf<Uuid>()
        val createdImportIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        fun createAdmin(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "BankAccount-Test Admin"
                    it[email] = "bankaccount-admin-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        // Review fix (MAJOR, Review Round 3, security finding): a TREASURER-role member, for the
        // requireAdminForMirrorChange negative tests below -- BankAccountStore.create/update/
        // delete/setDefault all accept an `actorRole`, and the store itself (not just the RPC
        // layer) must refuse TREASURER for the mutations that touch the organization_settings
        // mirror.
        fun createTreasurer(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "BankAccount-Test Treasurer"
                    it[email] = "bankaccount-treasurer-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.TREASURER
                }
            }
            createdMemberIds += id
            return id
        }

        // Review fix (MINOR, finding #6, test coverage): no test in this file previously asserted
        // ANYTHING about the audit trail this class's own KDoc promises -- AuditLogEntryTable was
        // only ever touched in cleanup. This helper makes that trail assertable.
        data class AuditEntrySummary(
            val action: AuditAction,
            val before: String?,
            val after: String?,
        )

        fun auditEntriesFor(entityId: Uuid): List<AuditEntrySummary> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { (AuditLogEntryTable.entityType eq AuditEntityType.BANK_ACCOUNT) and (AuditLogEntryTable.entityId eq entityId) }
                    .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                    .map {
                        AuditEntrySummary(
                            action = it[AuditLogEntryTable.action],
                            before = it[AuditLogEntryTable.beforeSnapshot],
                            after = it[AuditLogEntryTable.afterSnapshot],
                        )
                    }
            }

        afterTest {
            transaction {
                if (createdImportIds.isNotEmpty()) {
                    BankStatementImportTable.deleteWhere { BankStatementImportTable.id inList createdImportIds }
                }
                if (createdBankAccountIds.isNotEmpty()) {
                    BankAccountTable.deleteWhere { BankAccountTable.id inList createdBankAccountIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = null
                    it[bankBic] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                    createdMemberIds.forEach {
                        AccountTable.deleteWhere { AccountTable.memberId eq it }
                        MemberTable.deleteWhere { MemberTable.id eq it }
                    }
                }
            }
            createdMemberIds.clear()
            createdBankAccountIds.clear()
            createdImportIds.clear()
        }

        test("the very first account created becomes the default and is mirrored into organization_settings") {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(dto.id)

            dto.isDefault shouldBe true
            dto.iban shouldBe IBAN_1
            transaction {
                val settings =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()
                settings[OrganizationSettingsTable.bankIban] shouldBe IBAN_1
            }
        }

        test("a second account is not automatically the default") {
            val admin = createAdmin()
            val first =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val second =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Zweites", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(first.id)
            createdBankAccountIds += Uuid.parse(second.id)

            first.isDefault shouldBe true
            second.isDefault shouldBe false
        }

        test("create rejects a formally invalid IBAN") {
            val admin = createAdmin()
            shouldThrow<ConflictException> {
                BankAccountStore.create(
                    input = BankAccountInput(label = "Ungueltig", iban = "DE00INVALID"),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            }
        }

        test("create rejects a duplicate IBAN") {
            val admin = createAdmin()
            val first =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(first.id)
            shouldThrow<ConflictException> {
                BankAccountStore.create(
                    input = BankAccountInput(label = "Duplikat", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            }
        }

        test("updating the default account re-mirrors its (possibly changed) iban/bic into organization_settings") {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(dto.id)

            BankAccountStore.update(
                bankAccountId = Uuid.parse(dto.id),
                input = BankAccountInput(label = "Hauptkonto (umbenannt)", iban = IBAN_2, bic = "DEUTDEFF"),
                actorMemberId = admin,
                actorRole = AccountRole.ADMIN,
            )

            transaction {
                val settings =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()
                settings[OrganizationSettingsTable.bankIban] shouldBe IBAN_2
                settings[OrganizationSettingsTable.bankBic] shouldBe "DEUTDEFF"
            }
        }

        test("update on a not-found account throws NotFoundException") {
            shouldThrow<NotFoundException> {
                BankAccountStore.update(
                    bankAccountId = Uuid.random(),
                    input = BankAccountInput(label = "x", iban = IBAN_1),
                    actorMemberId = createAdmin(),
                    actorRole = AccountRole.ADMIN,
                )
            }
        }

        test("delete is refused while a bank_statement_import references the account") {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val bankAccountId = Uuid.parse(dto.id)
            createdBankAccountIds += bankAccountId
            val importId = Uuid.random()
            transaction {
                BankStatementImportTable.insert {
                    it[id] = importId
                    it[format] = BankStatementFormat.CSV
                    it[dialect] = BankCsvDialect.GENERIC.name
                    it[fileName] = "test.csv"
                    it[fileSizeBytes] = 10
                    it[fileDigest] = "digest-${Uuid.random()}"
                    it[accountIban] = null
                    it[statementFrom] = null
                    it[statementTo] = null
                    it[openingBalance] = null
                    it[closingBalance] = null
                    it[lineCount] = 0
                    it[duplicateCount] = 0
                    it[autoPostedCount] = 0
                    it[uploadedBy] = admin
                    it[uploadedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[BankStatementImportTable.bankAccountId] = bankAccountId
                }
            }
            createdImportIds += importId

            shouldThrow<ConflictException> {
                BankAccountStore.delete(bankAccountId = bankAccountId, actorMemberId = admin, actorRole = AccountRole.ADMIN)
            }
        }

        test("deleting the default account promotes the next-oldest remaining account and re-mirrors it") {
            val admin = createAdmin()
            val first =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val second =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Zweites", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(second.id)

            BankAccountStore.delete(bankAccountId = Uuid.parse(first.id), actorMemberId = admin, actorRole = AccountRole.ADMIN)

            val remaining = BankAccountStore.listDtos()
            remaining.size shouldBe 1
            remaining.single().isDefault shouldBe true
            remaining.single().iban shouldBe IBAN_2
            transaction {
                val settings =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()
                settings[OrganizationSettingsTable.bankIban] shouldBe IBAN_2
            }
        }

        test("deleting the last remaining account clears the organization_settings mirror") {
            val admin = createAdmin()
            val only =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Einziges", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )

            BankAccountStore.delete(bankAccountId = Uuid.parse(only.id), actorMemberId = admin, actorRole = AccountRole.ADMIN)

            BankAccountStore.listDtos() shouldBe emptyList()
            transaction {
                val settings =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()
                settings[OrganizationSettingsTable.bankIban] shouldBe null
                settings[OrganizationSettingsTable.bankBic] shouldBe null
            }
        }

        test("setDefaultBankAccount unsets the previous default, sets the new one, and re-mirrors") {
            val admin = createAdmin()
            val first =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val second =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Zweites", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(first.id)
            createdBankAccountIds += Uuid.parse(second.id)

            val result =
                BankAccountStore.setDefault(
                    bankAccountId = Uuid.parse(second.id),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )

            result.single { it.id == second.id }.isDefault shouldBe true
            result.single { it.id == first.id }.isDefault shouldBe false
            transaction {
                val settings =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()
                settings[OrganizationSettingsTable.bankIban] shouldBe IBAN_2
            }
            // Exactly one row still carries the unique 'X' default_marker -- the two-step
            // unset-then-set sequence in BankAccountStore.setDefault must never leave both (or
            // neither) rows marked, which would violate uq_bank_account_default and roll back.
            transaction {
                BankAccountTable.selectAll().where { BankAccountTable.defaultMarker eq "X" }.count() shouldBe 1L
            }
        }

        test("backfillLegacyDefaultAccountIfNeeded creates exactly one default account from an existing organization_settings.bank_iban") {
            val admin = createAdmin()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = IBAN_3
                }
            }

            BankAccountStore.backfillLegacyDefaultAccountIfNeeded()

            val accounts = BankAccountStore.listDtos()
            accounts.size shouldBe 1
            accounts.single().isDefault shouldBe true
            accounts.single().iban shouldBe IBAN_3
            accounts.single().label shouldBe "Hauptkonto"
            createdBankAccountIds += Uuid.parse(accounts.single().id)

            // Idempotent: a second call is a no-op once a row exists.
            BankAccountStore.backfillLegacyDefaultAccountIfNeeded()
            BankAccountStore.listDtos().size shouldBe 1
        }

        test("backfillLegacyDefaultAccountIfNeeded is a no-op when organization_settings.bank_iban is null") {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = null
                }
            }
            BankAccountStore.backfillLegacyDefaultAccountIfNeeded()
            BankAccountStore.listDtos() shouldBe emptyList()
        }

        // ── Review fix (MAJOR, Review Round 5, residuum of finding #1): adoption of pre-existing
        // `bank_account_id IS NULL` `bank_statement_import` rows the instant the organization's
        // first `bank_account` row is created -- from EITHER of the two paths that can create it. ──

        fun insertLegacyNullAccountImport(
            uploader: Uuid,
            fileName: String,
        ): Uuid {
            val importId = Uuid.random()
            transaction {
                BankStatementImportTable.insert {
                    it[id] = importId
                    it[format] = BankStatementFormat.CSV
                    it[dialect] = BankCsvDialect.GENERIC.name
                    it[BankStatementImportTable.fileName] = fileName
                    it[fileSizeBytes] = 10
                    it[fileDigest] = "legacy-digest-${Uuid.random()}"
                    it[accountIban] = null
                    it[statementFrom] = null
                    it[statementTo] = null
                    it[openingBalance] = null
                    it[closingBalance] = null
                    it[lineCount] = 0
                    it[duplicateCount] = 0
                    it[autoPostedCount] = 0
                    it[uploadedBy] = uploader
                    it[uploadedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[bankAccountId] = null
                }
            }
            createdImportIds += importId
            return importId
        }

        test("backfillLegacyDefaultAccountIfNeeded adopts pre-existing NULL-bank_account_id imports into the new Hauptkonto") {
            val admin = createAdmin()
            val legacyImportId = insertLegacyNullAccountImport(admin, "legacy-backfill.csv")
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[bankIban] = IBAN_3
                }
            }

            BankAccountStore.backfillLegacyDefaultAccountIfNeeded()

            val hauptkonto = BankAccountStore.listDtos().single()
            createdBankAccountIds += Uuid.parse(hauptkonto.id)
            transaction {
                BankStatementImportTable
                    .selectAll()
                    .where { BankStatementImportTable.id eq legacyImportId }
                    .single()[BankStatementImportTable.bankAccountId] shouldBe Uuid.parse(hauptkonto.id)
            }
        }

        test("create's very first (default) account adopts pre-existing NULL-bank_account_id imports too") {
            // Covers the OTHER path to a first account, distinct from the automatic startup
            // backfill above: an installation upgraded without `organization_settings.bank_iban`
            // ever being configured (so `backfillLegacyDefaultAccountIfNeeded` no-ops, see its own
            // `rawIban == null` branch) can still have accumulated `bank_account_id IS NULL`
            // imports, and its ADMIN creates the first account by hand.
            val admin = createAdmin()
            val legacyImportId = insertLegacyNullAccountImport(admin, "legacy-manual-first-account.csv")

            val hauptkonto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(hauptkonto.id)

            transaction {
                BankStatementImportTable
                    .selectAll()
                    .where { BankStatementImportTable.id eq legacyImportId }
                    .single()[BankStatementImportTable.bankAccountId] shouldBe Uuid.parse(hauptkonto.id)
            }
        }

        test(
            "delete is refused for an account that only owns a bank_statement_import through adoption " +
                "(Hinweis Szenario c, Review Round 5) -- the reference guard sees the adopted, no longer NULL, row",
        ) {
            // Before the Round 5 fix, a `bank_account_id IS NULL` legacy row was invisible to
            // `delete`'s own `BankStatementImportTable.bankAccountId eq bankAccountId` reference
            // guard -- the account semantically owning that import history could be deleted right
            // out from under it. Adoption removes the NULL special case entirely: once adopted, the
            // row is referenced exactly like any other, non-legacy import.
            val admin = createAdmin()
            val legacyImportId = insertLegacyNullAccountImport(admin, "legacy-blocks-delete.csv")
            val hauptkonto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val hauptkontoId = Uuid.parse(hauptkonto.id)
            createdBankAccountIds += hauptkontoId

            shouldThrow<ConflictException> {
                BankAccountStore.delete(bankAccountId = hauptkontoId, actorMemberId = admin, actorRole = AccountRole.ADMIN)
            }
            // Untouched -- delete() must roll back cleanly, not partially adopt/detach anything.
            transaction {
                BankStatementImportTable
                    .selectAll()
                    .where { BankStatementImportTable.id eq legacyImportId }
                    .single()[BankStatementImportTable.bankAccountId] shouldBe hauptkontoId
            }
            BankAccountStore.listDtos().size shouldBe 1
        }

        // ── Review fix (MINOR, finding #6, test coverage): audit trail + masking regressions ──────

        test("create records exactly one CREATE audit entry whose before/after NEVER carry the full IBAN") {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(dto.id)

            val entries = auditEntriesFor(Uuid.parse(dto.id))
            entries.size shouldBe 1
            entries.single().action shouldBe AuditAction.CREATE
            entries.single().before shouldBe null
            val after = entries.single().after
            after shouldNotBe null
            val snapshot = Json.decodeFromString(BankAccountSnapshot.serializer(), after!!)
            (after.contains(IBAN_1)) shouldBe false
            snapshot.ibanMasked shouldBe BankStatementImportService.maskIban(IBAN_1)
        }

        test("update records exactly one UPDATE audit entry, before/after reflecting the OLD/NEW label, never the full IBAN") {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Alter Name", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(dto.id)

            BankAccountStore.update(
                bankAccountId = Uuid.parse(dto.id),
                input = BankAccountInput(label = "Neuer Name", iban = IBAN_2),
                actorMemberId = admin,
                actorRole = AccountRole.ADMIN,
            )

            val entries = auditEntriesFor(Uuid.parse(dto.id))
            // create() already wrote one CREATE entry -- update() must add exactly one more, not
            // replace or duplicate it.
            entries.size shouldBe 2
            val updateEntry = entries[1]
            updateEntry.action shouldBe AuditAction.UPDATE
            val rawBefore = updateEntry.before
            val rawAfter = updateEntry.after
            rawBefore shouldNotBe null
            rawAfter shouldNotBe null
            val before = Json.decodeFromString(BankAccountSnapshot.serializer(), rawBefore!!)
            val after = Json.decodeFromString(BankAccountSnapshot.serializer(), rawAfter!!)
            before.label shouldBe "Alter Name"
            after.label shouldBe "Neuer Name"
            (rawBefore.contains(IBAN_1)) shouldBe false
            (rawAfter.contains(IBAN_2)) shouldBe false
        }

        test("delete records exactly one VOID audit entry for the deleted account") {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Einziges", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val bankAccountId = Uuid.parse(dto.id)

            BankAccountStore.delete(bankAccountId = bankAccountId, actorMemberId = admin, actorRole = AccountRole.ADMIN)

            val entries = auditEntriesFor(bankAccountId)
            entries.size shouldBe 2 // CREATE, then VOID
            entries[1].action shouldBe AuditAction.VOID
            entries[1].after shouldBe null
        }

        test(
            "setDefault on an account that is ALREADY the default is a no-op and records NO additional audit entry " +
                "(idempotence)",
        ) {
            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Hauptkonto", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val bankAccountId = Uuid.parse(dto.id)
            createdBankAccountIds += bankAccountId
            val entriesAfterCreate = auditEntriesFor(bankAccountId)

            BankAccountStore.setDefault(bankAccountId = bankAccountId, actorMemberId = admin, actorRole = AccountRole.ADMIN)

            auditEntriesFor(bankAccountId).size shouldBe entriesAfterCreate.size
        }

        test(
            "deleting the default account records an UPDATE audit entry for the PROMOTED account too, and bumps its updatedAt",
        ) {
            // Regression test for a review finding (MEDIUM): the promotion used to leave no audit
            // trace at all -- only the deleted account's own VOID entry was recorded -- even though
            // the promotion silently changes the organization's SEPA-Creditor identity and every
            // future invoice/dunning letterhead. updatedAt on the promoted row was also never
            // bumped, unlike the equivalent transition in setDefault().
            val admin = createAdmin()
            val first =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val second =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Zweites", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val secondId = Uuid.parse(second.id)
            createdBankAccountIds += secondId
            val secondUpdatedAtBeforeDelete = second.updatedAt

            BankAccountStore.delete(bankAccountId = Uuid.parse(first.id), actorMemberId = admin, actorRole = AccountRole.ADMIN)

            val secondEntries = auditEntriesFor(secondId)
            // create() already wrote one CREATE entry for "second" -- the promotion must add
            // exactly one more UPDATE entry, distinct from "first"'s own VOID entry.
            secondEntries.size shouldBe 2
            secondEntries[1].action shouldBe AuditAction.UPDATE
            val before = Json.decodeFromString(BankAccountSnapshot.serializer(), secondEntries[1].before!!)
            val after = Json.decodeFromString(BankAccountSnapshot.serializer(), secondEntries[1].after!!)
            before.isDefault shouldBe false
            after.isDefault shouldBe true

            val promoted = BankAccountStore.listDtos().single { it.id == second.id }
            promoted.isDefault shouldBe true
            (promoted.updatedAt > secondUpdatedAtBeforeDelete) shouldBe true
        }

        // Review fix (MAJOR, Review Round 3, security finding): requireAdminForMirrorChange
        // coverage at the STORE layer directly (defense in depth on top of BankAccountServiceTest's
        // own RPC-layer matrix) -- a TREASURER must never reach the ADMIN-only
        // organization_settings.bank_iban/bank_bic mirror through create/update/delete/setDefault,
        // regardless of which RPC surface (if any, ever) calls this store with that actorRole.
        test("create: a TREASURER cannot create the very first (default) account, but ADMIN can") {
            val treasurer = createTreasurer()

            shouldThrow<ForbiddenException> {
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = treasurer,
                    actorRole = AccountRole.TREASURER,
                )
            }
            transaction { BankAccountTable.selectAll().count() } shouldBe 0L

            val admin = createAdmin()
            val dto =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(dto.id)
            dto.isDefault shouldBe true
        }

        test("create: a TREASURER CAN create a SECOND, non-default account once a default already exists") {
            val admin = createAdmin()
            val treasurer = createTreasurer()
            val first =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Erstes", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(first.id)

            val second =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Zweites", iban = IBAN_2),
                    actorMemberId = treasurer,
                    actorRole = AccountRole.TREASURER,
                )
            createdBankAccountIds += Uuid.parse(second.id)

            second.isDefault shouldBe false
        }

        test("update: a TREASURER cannot edit the DEFAULT account, but CAN edit a NON-default account") {
            val admin = createAdmin()
            val treasurer = createTreasurer()
            val default =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Standard", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val defaultId = Uuid.parse(default.id)
            createdBankAccountIds += defaultId
            val nonDefault =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Nicht-Standard", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val nonDefaultId = Uuid.parse(nonDefault.id)
            createdBankAccountIds += nonDefaultId

            shouldThrow<ForbiddenException> {
                BankAccountStore.update(
                    bankAccountId = defaultId,
                    input = BankAccountInput(label = "Umbenannt", iban = IBAN_1),
                    actorMemberId = treasurer,
                    actorRole = AccountRole.TREASURER,
                )
            }

            val updated =
                BankAccountStore.update(
                    bankAccountId = nonDefaultId,
                    input = BankAccountInput(label = "Umbenannt", iban = IBAN_2),
                    actorMemberId = treasurer,
                    actorRole = AccountRole.TREASURER,
                )
            updated.label shouldBe "Umbenannt"
        }

        test("delete: a TREASURER cannot delete the DEFAULT account, but CAN delete a NON-default account") {
            val admin = createAdmin()
            val treasurer = createTreasurer()
            val default =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Standard", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val defaultId = Uuid.parse(default.id)
            createdBankAccountIds += defaultId
            val nonDefault =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Nicht-Standard", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val nonDefaultId = Uuid.parse(nonDefault.id)

            shouldThrow<ForbiddenException> {
                BankAccountStore.delete(bankAccountId = defaultId, actorMemberId = treasurer, actorRole = AccountRole.TREASURER)
            }

            BankAccountStore.delete(bankAccountId = nonDefaultId, actorMemberId = treasurer, actorRole = AccountRole.TREASURER)
            transaction { BankAccountTable.selectAll().where { BankAccountTable.id eq nonDefaultId }.count() } shouldBe 0L
        }

        test("setDefault: a TREASURER cannot repoint the default, even onto a NON-default account -- ADMIN can") {
            val admin = createAdmin()
            val treasurer = createTreasurer()
            val default =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Standard", iban = IBAN_1),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            createdBankAccountIds += Uuid.parse(default.id)
            val nonDefault =
                BankAccountStore.create(
                    input = BankAccountInput(label = "Nicht-Standard", iban = IBAN_2),
                    actorMemberId = admin,
                    actorRole = AccountRole.ADMIN,
                )
            val nonDefaultId = Uuid.parse(nonDefault.id)
            createdBankAccountIds += nonDefaultId

            shouldThrow<ForbiddenException> {
                BankAccountStore.setDefault(bankAccountId = nonDefaultId, actorMemberId = treasurer, actorRole = AccountRole.TREASURER)
            }
            BankAccountStore.listDtos().single { it.id == nonDefault.id }.isDefault shouldBe false

            BankAccountStore.setDefault(bankAccountId = nonDefaultId, actorMemberId = admin, actorRole = AccountRole.ADMIN)
            BankAccountStore.listDtos().single { it.id == nonDefault.id }.isDefault shouldBe true
        }
    })
