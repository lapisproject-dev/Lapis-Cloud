package network.lapis.cloud.server.payment.bankstatement

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.BankAccountFinTsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.payment.sepa.BicValidator
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.BankAccountInput
import network.lapis.cloud.shared.domain.BankAccountSnapshot
import network.lapis.cloud.shared.domain.FinTsStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** ANSI SQL `unique_violation` SQLSTATE -- same idiom `BankStatementImportService`/`EventTicketIssuer`/`EventRegistrationSubmission` already establish (own private copy per file, this repo's convention). */
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

/**
 * Review fix (MINOR, finding #5): narrows an [ExposedSQLException]'s underlying unique-violation to
 * a NAMED constraint, by substring-matching the (lowercase-folded, `DATABASE_TO_LOWER=TRUE` on the
 * H2 test dialect, same casing on real Postgres) constraint name Postgres/H2 both embed in the
 * underlying [java.sql.SQLException.getMessage]. Without this, `create`/`update` blanket-labeled
 * EVERY unique violation (including a concurrent `create()` race on `uq_bank_account_default`, or --
 * worse, before the SQLSTATE-23505 narrowing this fix also adds -- even an unrelated
 * `fk_bank_account_created_by` FK violation, SQLSTATE 23503) as "IBAN already exists".
 */
private fun ExposedSQLException.violatesConstraint(constraintName: String): Boolean {
    val message = cause?.message ?: message
    return message?.contains(constraintName, ignoreCase = true) == true
}

/**
 * Welle V1.4.14 "Mehrere Bankkonten". Reine Exposed-Datenzugriffsschicht fuer `bank_account` --
 * oeffnet, wie jede `*Store` in diesem Codebase, ihre eigenen `transaction {}` (siehe
 * `BankStatementStore` KDoc fuer die Begruendung dieses Musters).
 *
 * **Der Default-Spiegel**: genau EIN Konto trägt zu jedem Zeitpunkt `isDefault = true`
 * (`chk_bank_account_default_marker` + `uq_bank_account_default` erzwingen das strukturell). Seine
 * IBAN/BIC werden nach `organization_settings.bank_iban`/`bank_bic` gespiegelt -- die einzige
 * Schreibstelle dieser beiden Spalten, sobald mindestens eine `bank_account`-Zeile existiert (siehe
 * `network.lapis.cloud.server.rpc.OrganizationSettingsService.updateOrganizationSettings`, das
 * seinerseits diese beiden Felder ignoriert, sobald diese Bedingung erfuellt ist). Jede SEPA-
 * Creditor-Generierung (pain.008) und jeder Beitragsrechnung-/Mahnungs-Briefkopf liest weiterhin
 * ausschliesslich `organization_settings`, nicht `bank_account` direkt -- diese Store-Klasse ist
 * die einzige Stelle, die diese beiden Wahrheiten synchron haelt.
 *
 * **Autorisierung des Spiegels** (Review-Fix, MAJOR-Sicherheitsbefund): weil dieser Spiegel die
 * einzige Schreibstelle von `organization_settings.bank_iban`/`bank_bic` ist, sobald er greift,
 * und `OrganizationSettingsService.updateOrganizationSettings` denselben direkten Schreibzugriff
 * ADMIN-only verlangt, verlangt [requireAdminForMirrorChange] dieselbe Rolle fuer jede Mutation, die
 * den Spiegel tatsaechlich beruehrt (`create()`s allererstes Konto, `update()`/`delete()` des
 * aktuell-Default-Kontos, `setDefault()` immer) -- unabhaengig vom breiteren TREASURER/ADMIN-Gate
 * an der RPC-Oberflaeche (`network.lapis.cloud.server.rpc.BankAccountService`). Gewoehnliche
 * Mehrkonten-Verwaltung, die den Spiegel NICHT beruehrt, bleibt TREASURER-erreichbar.
 */
internal object BankAccountStore {
    fun listDtos(): List<BankAccountDto> =
        transaction {
            BankAccountTable
                .selectAll()
                .orderBy(BankAccountTable.isDefault to SortOrder.DESC, BankAccountTable.label to SortOrder.ASC)
                .map { it.toDto() }
        }

    fun create(
        input: BankAccountInput,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): BankAccountDto =
        transaction {
            val normalizedIban = requireValidIbanOrConflict(input.iban)
            val normalizedBic = requireValidBicOrConflict(input.bic)
            val makeDefault = BankAccountTable.selectAll().count() == 0L
            // Review fix (MAJOR, security finding): the very first account becomes the default and
            // is mirrored immediately below -- gate BEFORE the insert, not after, so a rejected
            // TREASURER call creates nothing.
            if (makeDefault) requireAdminForMirrorChange(actorRole)
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()

            val inserted =
                runCatching {
                    BankAccountTable.insert {
                        it[BankAccountTable.id] = id
                        it[label] =
                            input.label
                                .trim()
                                .ifBlank { "Bankkonto" }
                                .take(120)
                        it[iban] = normalizedIban
                        it[bic] = normalizedBic
                        it[bankName] = input.bankName?.trim()?.take(140)
                        it[isDefault] = makeDefault
                        it[defaultMarker] = if (makeDefault) "X" else null
                        it[createdBy] = actorMemberId
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            if (inserted.isFailure) {
                val cause = inserted.exceptionOrNull()
                // Review fix (MINOR, finding #5): narrowed to SQLSTATE 23505 (unique_violation) --
                // the previous blanket `is ExposedSQLException` catch also mislabeled a
                // `fk_bank_account_created_by` FK violation (SQLSTATE 23503, e.g. a stale/deleted
                // actor member id) as "IBAN already exists". Within 23505, the constraint name
                // distinguishes a genuine duplicate IBAN from a concurrent `create()` race on
                // `uq_bank_account_default` (two calls both see `count() == 0L` on an empty table
                // and both try to insert the FIRST, default-marked row) -- the latter is not an IBAN
                // problem at all and telling the caller so would be actively misleading.
                if (cause is ExposedSQLException && cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    throw if (cause.violatesConstraint("uq_bank_account_default")) {
                        ConflictException(
                            "Gleichzeitig wurde bereits ein anderes Bankkonto als erstes/Standardkonto angelegt -- bitte erneut versuchen.",
                        )
                    } else {
                        ConflictException("Ein Bankkonto mit dieser IBAN existiert bereits.")
                    }
                }
                throw cause ?: IllegalStateException("bank_account insert failed with no exception")
            }
            if (makeDefault) {
                mirrorDefaultToOrganizationSettings(iban = normalizedIban, bic = normalizedBic)
                // Review fix (MAJOR, Review Round 5 -- residuum of finding #1): this IS the moment
                // this organization's bank-account identity first becomes known -- see
                // `adoptLegacyBankStatementImports` KDoc for the full rationale. Must run here too,
                // not only in `backfillLegacyDefaultAccountIfNeeded`: an installation that upgraded
                // without ever having `organization_settings.bank_iban` configured (that function
                // then no-ops, see its own `rawIban == null` branch) can still have accumulated
                // `bank_account_id IS NULL` imports from before this wave, and its ADMIN creating
                // their first account by hand through the ordinary UI/RPC path -- this branch -- is
                // exactly as much "the identity becoming known" as the automatic startup backfill is.
                adoptLegacyBankStatementImports(id)
            }

            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.BANK_ACCOUNT,
                entityId = id,
                action = AuditAction.CREATE,
                before = null,
                after = Json.encodeToString(BankAccountSnapshot.serializer(), snapshotOf(id)),
            )
            requireDto(id)
        }

    fun update(
        bankAccountId: Uuid,
        input: BankAccountInput,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): BankAccountDto =
        transaction {
            val row = requireRowForUpdate(bankAccountId)
            // Review fix (MAJOR, security finding): gate BEFORE validating/mutating anything --
            // re-mirrors below iff this account is currently the default.
            if (row[BankAccountTable.isDefault]) requireAdminForMirrorChange(actorRole)
            val before = row.toSnapshot()
            val normalizedIban = requireValidIbanOrConflict(input.iban)
            val normalizedBic = requireValidBicOrConflict(input.bic)
            val now = DbClock.nowLocalDateTime()

            val updated =
                runCatching {
                    BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                        it[label] =
                            input.label
                                .trim()
                                .ifBlank { "Bankkonto" }
                                .take(120)
                        it[iban] = normalizedIban
                        it[bic] = normalizedBic
                        it[bankName] = input.bankName?.trim()?.take(140)
                        it[updatedAt] = now
                    }
                }
            if (updated.isFailure) {
                val cause = updated.exceptionOrNull()
                // Review fix (MINOR, finding #5): narrowed to SQLSTATE 23505, same reasoning as
                // create() above -- this UPDATE only ever touches label/iban/bic/updatedAt, so
                // uq_bank_account_iban is the only realistic unique constraint it could violate,
                // but any OTHER failure (there is currently none with its own FK on this statement,
                // but narrowing defensively matches create()'s own idiom) must propagate as-is
                // rather than being mislabeled "IBAN already exists".
                if (cause is ExposedSQLException && cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    throw ConflictException("Ein Bankkonto mit dieser IBAN existiert bereits.")
                }
                throw cause ?: IllegalStateException("bank_account update failed with no exception")
            }
            if (row[BankAccountTable.isDefault]) mirrorDefaultToOrganizationSettings(iban = normalizedIban, bic = normalizedBic)

            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.BANK_ACCOUNT,
                entityId = bankAccountId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(BankAccountSnapshot.serializer(), before),
                after = Json.encodeToString(BankAccountSnapshot.serializer(), snapshotOf(bankAccountId)),
            )
            requireDto(bankAccountId)
        }

    /**
     * Refused (409) while any `bank_statement_import` still references this account -- deleting a
     * historically-attributed account out from under an existing import would silently orphan the
     * FK-less display join (`BankStatementStore.listImports`) instead of failing loudly. If the
     * deleted account was the default, the next-oldest remaining account (if any) is promoted;
     * otherwise `organization_settings.bank_iban`/`bank_bic` are cleared (exactly the pre-wave
     * "unconfigured" state).
     *
     * Review fix (CRITICAL, empirically reproduced): also refused (409) while any
     * `bank_account_fints_acknowledgment` row references this account. That table's own FK
     * (`fk_ba_fints_ack_bank_account_id`, V33__bank_account_fints.sql) has NO `ON DELETE CASCADE` --
     * `BankAccountFinTsPersonalData`'s KDoc documents *why*: the row is an accountability record
     * ("which ADMIN acknowledged which disclaimer version for which account", Art. 5(2) DSGVO) that
     * is retained unconditionally, even across a member-erasure request. Silently cascading it away
     * on account deletion would defeat that retention guarantee, so -- exactly like an existing
     * `bank_statement_import` reference -- an existing acknowledgment blocks the deletion instead of
     * letting the raw `ExposedSQLException` from the FK violation escape as an uncaught 500.
     */
    fun delete(
        bankAccountId: Uuid,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ) {
        transaction {
            val row = requireRowForUpdate(bankAccountId)
            // Review fix (MAJOR, security finding): gate BEFORE the referenced/delete checks below
            // -- deleting the CURRENT default either promotes the next-oldest account or clears the
            // mirror entirely, both of which repoint/blank the organization-wide SEPA-creditor IBAN.
            if (row[BankAccountTable.isDefault]) requireAdminForMirrorChange(actorRole)
            val before = row.toSnapshot()
            val referenced =
                BankStatementImportTable.selectAll().where { BankStatementImportTable.bankAccountId eq bankAccountId }.count() > 0
            if (referenced) {
                throw ConflictException(
                    "Dieses Bankkonto wird von mindestens einem Kontoauszugs-Import referenziert und kann nicht geloescht werden.",
                )
            }
            val hasFinTsAcknowledgment =
                BankAccountFinTsAcknowledgmentTable
                    .selectAll()
                    .where { BankAccountFinTsAcknowledgmentTable.bankAccountId eq bankAccountId }
                    .count() > 0
            if (hasFinTsAcknowledgment) {
                throw ConflictException(
                    "Fuer dieses Bankkonto liegt eine FinTS/HBCI-Rechtshinweis-Bestaetigung vor und kann " +
                        "aus Nachweispflicht (Art. 5(2) DSGVO) nicht geloescht werden.",
                )
            }
            BankAccountTable.deleteWhere { BankAccountTable.id eq bankAccountId }

            if (row[BankAccountTable.isDefault]) {
                val next =
                    BankAccountTable
                        .selectAll()
                        // Review fix (MEDIUM, finding #3 "Nebenbefund"): `id` tie-breaker -- two
                        // accounts with an identical createdAt (same millisecond, or a future path
                        // that ever backdates/imports createdAt) would otherwise make `firstOrNull()`
                        // pick a row in whatever order the DB happens to return ties, non-
                        // deterministically. `id` is a UUID, arbitrary but STABLE across repeated
                        // runs of this exact query, which is all determinism requires here.
                        .orderBy(BankAccountTable.createdAt to SortOrder.ASC, BankAccountTable.id to SortOrder.ASC)
                        .limit(1)
                        .firstOrNull()
                if (next != null) {
                    val nextId = next[BankAccountTable.id]
                    val nextBefore = next.toSnapshot()
                    BankAccountTable.update({ BankAccountTable.id eq nextId }) {
                        it[isDefault] = true
                        it[defaultMarker] = "X"
                        // Review fix (MEDIUM, finding #3): setDefault() already bumps updatedAt on
                        // this exact transition (see there) -- this promotion is the SAME kind of
                        // change and must not silently leave the row's own updatedAt stale.
                        it[updatedAt] = DbClock.nowLocalDateTime()
                    }
                    mirrorDefaultToOrganizationSettings(iban = next[BankAccountTable.iban], bic = next[BankAccountTable.bic])
                    // Review fix (MEDIUM, finding #3): the promoted account changing the
                    // organization's SEPA-Creditor identity and every future invoice/dunning
                    // letterhead is exactly the kind of change §25 PartG/GoBD traceability requires
                    // a "who and when" audit entry for -- setDefault() already records this same
                    // transition (isDefault false -> true) for its own call path; deleting the
                    // previous default must not silently skip it just because the trigger was a
                    // deletion elsewhere rather than an explicit setDefault call.
                    AuditLogRecorder.record(
                        actorMemberId = actorMemberId,
                        actorRole = actorRole,
                        entityType = AuditEntityType.BANK_ACCOUNT,
                        entityId = nextId,
                        action = AuditAction.UPDATE,
                        before = Json.encodeToString(BankAccountSnapshot.serializer(), nextBefore),
                        after = Json.encodeToString(BankAccountSnapshot.serializer(), snapshotOf(nextId)),
                    )
                } else {
                    clearOrganizationSettingsMirror()
                }
            }

            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.BANK_ACCOUNT,
                entityId = bankAccountId,
                action = AuditAction.VOID,
                before = Json.encodeToString(BankAccountSnapshot.serializer(), before),
                after = null,
            )
        }
    }

    fun setDefault(
        bankAccountId: Uuid,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): List<BankAccountDto> =
        transaction {
            val row = requireRowForUpdate(bankAccountId)
            val before = row.toSnapshot()
            if (!row[BankAccountTable.isDefault]) {
                // Review fix (MAJOR, security finding): this ENTIRE branch exists to repoint the
                // organization-wide default -- gate it unconditionally, before the row-lock below.
                requireAdminForMirrorChange(actorRole)
                // Review fix (MINOR, finding #5): row-lock the CURRENT default row too --
                // requireRowForUpdate above only locked the TARGET row, so two concurrent
                // setDefault() calls onto two DIFFERENT accounts could both see the SAME current
                // default, both unset it, and both set their OWN target -- two rows racing for the
                // single 'X' `uq_bank_account_default` slot. Locking the current default row here
                // serializes that race at the DB level (the second call blocks until the first
                // commits, then re-reads the now-current default).
                BankAccountTable
                    .selectAll()
                    .where { BankAccountTable.isDefault eq true }
                    .forUpdate()
                    .toList()
                // Unset the CURRENT default first -- uq_bank_account_default allows only one 'X'
                // at a time, so the old row's marker must clear before the new one is set.
                val updated =
                    runCatching {
                        BankAccountTable.update({ BankAccountTable.isDefault eq true }) {
                            it[isDefault] = false
                            it[defaultMarker] = null
                        }
                        BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                            it[isDefault] = true
                            it[defaultMarker] = "X"
                            it[updatedAt] = DbClock.nowLocalDateTime()
                        }
                    }
                if (updated.isFailure) {
                    // Review fix (MINOR, finding #5): defensive fallback for whatever residual
                    // uq_bank_account_default violation still slips past the row lock above (e.g. a
                    // weaker isolation level on some future deployment) -- converts it to the SAME
                    // Conflict create()/update() already promise, instead of an uncaught
                    // ExposedSQLException surfacing as an undiagnosable 500.
                    val cause = updated.exceptionOrNull()
                    if (cause is ExposedSQLException && cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                        throw ConflictException(
                            "Es wurde gleichzeitig bereits ein anderes Standardkonto gesetzt -- bitte erneut versuchen.",
                        )
                    }
                    throw cause ?: IllegalStateException("bank_account setDefault update failed with no exception")
                }
                mirrorDefaultToOrganizationSettings(iban = row[BankAccountTable.iban], bic = row[BankAccountTable.bic])
                AuditLogRecorder.record(
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                    entityType = AuditEntityType.BANK_ACCOUNT,
                    entityId = bankAccountId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(BankAccountSnapshot.serializer(), before),
                    after = Json.encodeToString(BankAccountSnapshot.serializer(), snapshotOf(bankAccountId)),
                )
            }
            listDtos()
        }

    /**
     * Idempotent one-time migration, called once at application startup (see
     * `network.lapis.cloud.server.Application.module`): if `bank_account` is still empty AND
     * `organization_settings.bank_iban` already carries a (formally valid) value, creates exactly
     * one row ("Hauptkonto", `isDefault = true`) so a fresh V1.4.14 deployment never presents an
     * empty accounts screen to an organization that had already configured its bank details before
     * this wave existed. No-op (and safe to call again on every restart) once at least one
     * `bank_account` row exists, or when no ADMIN account exists yet to attribute `created_by` to
     * (a brand-new, not-yet-seeded deployment).
     *
     * Also adopts every pre-existing `bank_statement_import` row with `bank_account_id IS NULL`
     * into this new "Hauptkonto" -- see [adoptLegacyBankStatementImports] KDoc.
     */
    fun backfillLegacyDefaultAccountIfNeeded() {
        transaction {
            if (BankAccountTable.selectAll().count() > 0) return@transaction
            val settingsRow =
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
                    ?: return@transaction
            val rawIban = settingsRow[OrganizationSettingsTable.bankIban]
            if (rawIban == null) {
                // Review fix (MINOR, finding #6): debug, not warn -- "no bank_iban configured yet"
                // is the expected, common state for a brand-new deployment, not a problem.
                logger.debug { "BankAccountStore: legacy backfill skipped -- no organization_settings.bank_iban configured" }
                return@transaction
            }
            val normalizedIban =
                runCatching { IbanValidator.requireValid(rawIban) }.getOrNull() ?: run {
                    // Review fix (MINOR, finding #6): this one IS worth a warning -- there WAS a
                    // signal to backfill (a configured bank_iban) but it didn't parse, so the
                    // organization silently keeps seeing an empty accounts screen instead.
                    logger.warn {
                        "BankAccountStore: legacy backfill skipped -- organization_settings.bank_iban did not parse as a valid IBAN"
                    }
                    return@transaction
                }
            val adminMemberId =
                AccountTable
                    .selectAll()
                    .where { AccountTable.role eq AccountRole.ADMIN }
                    .orderBy(AccountTable.id to SortOrder.ASC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(AccountTable.memberId) ?: run {
                    logger.warn { "BankAccountStore: legacy backfill skipped -- no ADMIN account exists yet to attribute created_by to" }
                    return@transaction
                }
            val now = DbClock.nowLocalDateTime()
            val id = Uuid.random()
            // Review fix (MINOR, finding #6): logged on failure instead of swallowed silently --
            // the class had no logger at all despite CLAUDE.md's kotlin-logging mandate and its own
            // sibling class (BankStatementImportService) already using one. Without this, a failed
            // insert (e.g. a constraint violation or a schema drift) left NO trace anywhere: no
            // account, no log line, no metric, repeating silently on every restart.
            runCatching {
                BankAccountTable.insert {
                    it[BankAccountTable.id] = id
                    it[label] = "Hauptkonto"
                    it[iban] = normalizedIban
                    it[bic] = settingsRow[OrganizationSettingsTable.bankBic]
                    it[bankName] = null
                    it[isDefault] = true
                    it[defaultMarker] = "X"
                    it[createdBy] = adminMemberId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }.onSuccess {
                adoptLegacyBankStatementImports(id)
            }.onFailure { e ->
                logger.error(e) { "BankAccountStore: legacy backfill insert failed -- no default account was created" }
            }
        }
    }

    /**
     * Review fix (MAJOR, Review Round 5 -- residuum of finding #1 "Doppelbuchung realer Zahlungen
     * UND stiller Verlust echter Buchungen"): re-attributes every `bank_statement_import` row still
     * carrying `bank_account_id IS NULL` to [newlyCreatedAccountId], the account whose creation just
     * made this call. Called from exactly the two places where a `bank_account` row transitions
     * from "does not exist" to "exists for the first time" -- [create]'s `makeDefault` branch and
     * [backfillLegacyDefaultAccountIfNeeded] -- because `BankStatementImportService.import` can only
     * ever leave `bank_account_id` NULL on a freshly-inserted row while `bank_account` is still
     * completely empty (see its own `bankAccountRowCount > 0` branch: once ANY account exists,
     * every import resolves to a concrete account, explicit, IBAN-matched, or the always-present
     * default one). The instant the first account is created, EVERY row that is still NULL at that
     * moment is therefore, unambiguously and permanently, an import from before any `bank_account`
     * row ever existed for this (single-tenant) organization -- there is no other account it could
     * belong to, and no OTHER row can ever become NULL again afterwards.
     *
     * Previously this identity was reconstructed ad hoc, per dedup lookup, in
     * `BankStatementImportService.import` by checking whether the resolved account happened to be
     * the organization's CURRENT default -- but `isDefault` is mutable (`setDefault`, and the
     * default-promotion in [delete]), while a `bank_account_id IS NULL` row's true owner is not, so
     * moving the default elsewhere silently mismatched every legacy row in both directions (a
     * genuine re-import of the legacy account's own history stopped deduplicating; a coincidentally
     * identical NEW booking on whichever account is default now got wrongly swallowed as a
     * duplicate of someone else's old line). Writing the real, permanent owner into
     * `bank_account_id` HERE, once, the moment that owner becomes knowable, removes the NULL/legacy
     * special case from every later lookup entirely: `BankStatementImportService`'s own
     * `alreadyImportedUnderLegacyFingerprint` fallback can then scope its match by the resolved
     * account's own id alone, exactly like every other (non-legacy) fingerprint lookup already does.
     *
     * Idempotent by construction (`WHERE bank_account_id IS NULL`) -- a second, redundant call (e.g.
     * [backfillLegacyDefaultAccountIfNeeded] running again on a later restart, which its own
     * `BankAccountTable.selectAll().count() > 0` guard above already makes a no-op before ever
     * reaching this insert) simply finds nothing left to adopt.
     */
    private fun adoptLegacyBankStatementImports(newlyCreatedAccountId: Uuid) {
        val adopted =
            BankStatementImportTable.update({ BankStatementImportTable.bankAccountId.isNull() }) {
                it[BankStatementImportTable.bankAccountId] = newlyCreatedAccountId
            }
        if (adopted > 0) {
            logger.info {
                "BankAccountStore: adopted $adopted pre-existing bank_statement_import row(s) with " +
                    "bank_account_id IS NULL into newly created account $newlyCreatedAccountId"
            }
        }
    }

    private fun requireRowForUpdate(bankAccountId: Uuid): ResultRow =
        BankAccountTable
            .selectAll()
            .where { BankAccountTable.id eq bankAccountId }
            .forUpdate()
            .singleOrNull() ?: throw NotFoundException("BankAccount $bankAccountId not found")

    private fun requireDto(bankAccountId: Uuid): BankAccountDto =
        BankAccountTable
            .selectAll()
            .where { BankAccountTable.id eq bankAccountId }
            .single()
            .toDto()

    private fun snapshotOf(bankAccountId: Uuid): BankAccountSnapshot =
        BankAccountTable
            .selectAll()
            .where { BankAccountTable.id eq bankAccountId }
            .single()
            .toSnapshot()

    /**
     * Review fix (MAJOR, security finding): every one of the four call sites below, when the
     * mutation actually TOUCHES the [organization_settings] mirror (a fresh organization's very
     * first account -- `create()`'s `makeDefault` branch --, editing the CURRENTLY-default
     * account, [setDefault]'s entire reason to exist, or deleting the CURRENTLY-default account),
     * repoints the organization-wide SEPA-creditor IBAN/BIC -- the exact pair of fields
     * `network.lapis.cloud.server.rpc.OrganizationSettingsService.updateOrganizationSettings`
     * requires ADMIN (not TREASURER) to edit directly. `BANK_ACCOUNT_WRITE_ROLES` at the RPC layer
     * (`network.lapis.cloud.server.rpc.BankAccountService`) still admits TREASURER/ADMIN for the
     * coarse "may call this method at all" gate -- ordinary multi-account bookkeeping (a
     * second/third NON-default account, editing a NON-default account's label/IBAN/BIC, deleting a
     * NON-default account) stays TREASURER-reachable, unaffected by this narrower check. Without
     * it, a TREASURER could reach the ADMIN-only write through this side door despite being
     * deliberately excluded from the direct one -- see the review finding for the full exploit
     * (repoint the SEPA creditor account the org's own Beitragsrechnungen/Mahnungen print, and every
     * future `createDebitBatch` freezes into its pain.008).
     */
    private fun requireAdminForMirrorChange(actorRole: AccountRole) {
        if (actorRole != AccountRole.ADMIN) {
            throw ForbiddenException(
                "Nur ADMIN darf das Standardkonto anlegen, aendern, setzen oder loeschen -- " +
                    "das aendert die organisationsweite SEPA-Empfaenger-IBAN/BIC.",
            )
        }
    }

    private fun mirrorDefaultToOrganizationSettings(
        iban: String,
        bic: String?,
    ) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[bankIban] = iban
            it[bankBic] = bic
        }
    }

    private fun clearOrganizationSettingsMirror() {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[bankIban] = null
            it[bankBic] = null
        }
    }

    private fun requireValidIbanOrConflict(raw: String): String =
        try {
            IbanValidator.requireValid(raw)
        } catch (e: IllegalArgumentException) {
            throw ConflictException("Die IBAN ist ungueltig: ${e.message}")
        }

    private fun requireValidBicOrConflict(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim().uppercase()
        if (!BicValidator.isValid(trimmed)) {
            throw ConflictException("Die BIC hat kein gueltiges Format.")
        }
        return trimmed
    }

    /**
     * Welle V1.4.14 Wave 2 -- `finTsAvailable` reflects whether [network.lapis.cloud.server.crypto.SecretBox]
     * is even configured on this instance (`LAPIS_SECRET_ENCRYPTION_KEY`), NOT whether this
     * particular account happens to be active -- callers ([network.lapis.cloud.server.rpc.BankAccountService])
     * pass the module-scoped `bankStatementSecretBox != null` flag through. `finTsUserIdMask` is a
     * CONSTANT masked string, never a partial reveal (Design-Team decision, see `BankAccountDto`
     * KDoc) -- the userid is frequently the account number itself, and the IBAN already sits on the
     * same screen.
     */
    fun ResultRow.toDto(finTsAvailable: Boolean): BankAccountDto =
        BankAccountDto(
            id = this[BankAccountTable.id].toString(),
            label = this[BankAccountTable.label],
            iban = this[BankAccountTable.iban],
            ibanMasked = BankStatementImportService.maskIban(this[BankAccountTable.iban]) ?: this[BankAccountTable.iban],
            bic = this[BankAccountTable.bic],
            bankName = this[BankAccountTable.bankName],
            isDefault = this[BankAccountTable.isDefault],
            createdAt = this[BankAccountTable.createdAt],
            updatedAt = this[BankAccountTable.updatedAt],
            finTsStatus = this[BankAccountTable.fintsStatus],
            finTsBlz = this[BankAccountTable.fintsBlz],
            finTsUrl = this[BankAccountTable.fintsUrl],
            finTsUserIdMask = if (this[BankAccountTable.fintsUserIdCiphertext] != null) FINTS_USER_ID_MASK else null,
            finTsPinSetAt = this[BankAccountTable.fintsPinSetAt],
            finTsLastSuccessAt = this[BankAccountTable.fintsLastSuccessAt],
            finTsLastErrorCode = this[BankAccountTable.fintsLastErrorCode],
            finTsAvailable = finTsAvailable,
            finTsGapFrom = this[BankAccountTable.fintsGapFrom],
            finTsGapTo = this[BankAccountTable.fintsGapTo],
            finTsGapDetectedAt = this[BankAccountTable.fintsGapDetectedAt],
        )

    private fun ResultRow.toDto(): BankAccountDto = toDto(finTsAvailable = true)

    private fun ResultRow.toSnapshot(): BankAccountSnapshot =
        BankAccountSnapshot(
            label = this[BankAccountTable.label],
            ibanMasked = BankStatementImportService.maskIban(this[BankAccountTable.iban]) ?: this[BankAccountTable.iban],
            bic = this[BankAccountTable.bic],
            bankName = this[BankAccountTable.bankName],
            isDefault = this[BankAccountTable.isDefault],
            finTsStatus = this[BankAccountTable.fintsStatus],
        )

    // ============================================================================================
    // Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf".
    // ============================================================================================

    /**
     * Writes the four sealed credential columns, flips status -> ACTIVE, sets
     * `fints_activated_by`/`fints_activated_at`/`fints_pin_set_at`, and records ONE audit entry.
     * Caller ([network.lapis.cloud.server.rpc.BankAccountService.beginFinTsSetup]) has already: (a)
     * checked the ADMIN role, (b) matched the disclaimer, (c) run [network.lapis.cloud.server.webhook.checkWebhookUrl],
     * (d) run the actual hbci4j setup dialog to [network.lapis.cloud.server.payment.fints.FinTsSetupOutcome.Verified],
     * and (e) sealed [userIdCiphertext]/[pinCiphertext] via [network.lapis.cloud.server.crypto.SecretBox]
     * -- this function is pure persistence, no further validation.
     */
    fun activateFinTs(
        bankAccountId: Uuid,
        blz: String,
        url: String,
        userIdCiphertext: String,
        pinCiphertext: String,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        finTsAvailable: Boolean,
    ): BankAccountDto =
        transaction {
            val row = requireRowForUpdate(bankAccountId)
            val before = row.toSnapshot()
            val now = DbClock.nowLocalDateTime()
            BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                it[fintsBlz] = blz
                it[fintsUrl] = url
                it[fintsUserIdCiphertext] = userIdCiphertext
                it[fintsPinCiphertext] = pinCiphertext
                it[fintsStatus] = FinTsStatus.ACTIVE
                it[fintsActivatedBy] = actorMemberId
                it[fintsActivatedAt] = now
                it[fintsPinSetAt] = now
                // Review fix (MEDIUM): a (re-)activation must start with a FRESH watermark, not the
                // one an EARLIER activation of this same account left behind. Without this, an
                // account activated in January, disabled in February, and reactivated in September
                // keeps January's fints_last_fetch_to -- FinTsPoller.tick's `?: ...` watermark
                // fallback only fires when the column is NULL, so the very first live poll after
                // reactivation would request a ~7-month window, well past what banks typically allow
                // for HKKAZ (~90 days) and past this app's own FinTsConfig.fetchWindowDays cap.
                // fints_last_error_code is reset for the same reason: a stale code from the PREVIOUS
                // activation attempt must not survive into a freshly (successfully) activated account
                // -- BankAccountDto.finTsLastErrorCode would otherwise misreport a working account.
                it[fintsLastFetchTo] = null
                it[fintsLastSuccessAt] = null
                it[fintsLastErrorCode] = null
            }
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.BANK_ACCOUNT,
                entityId = bankAccountId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(BankAccountSnapshot.serializer(), before),
                after =
                    Json.encodeToString(
                        BankAccountSnapshot.serializer(),
                        before.copy(finTsStatus = FinTsStatus.ACTIVE),
                    ),
            )
            requireRowForRead(bankAccountId).toDto(finTsAvailable = finTsAvailable)
        }

    /** Clears all four credential columns + activation metadata, resets status -> NOT_CONFIGURED. There is no fourth "disabled with credentials around" state (structurally enforced by `chk_bank_account_fints_credentials_complete`). */
    fun disableFinTs(
        bankAccountId: Uuid,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        finTsAvailable: Boolean,
    ): BankAccountDto =
        transaction {
            val row = requireRowForUpdate(bankAccountId)
            val before = row.toSnapshot()
            BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                it[fintsBlz] = null
                it[fintsUrl] = null
                it[fintsUserIdCiphertext] = null
                it[fintsPinCiphertext] = null
                it[fintsStatus] = FinTsStatus.NOT_CONFIGURED
                it[fintsActivatedBy] = null
                it[fintsActivatedAt] = null
                it[fintsPinSetAt] = null
            }
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.BANK_ACCOUNT,
                entityId = bankAccountId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(BankAccountSnapshot.serializer(), before),
                after =
                    Json.encodeToString(
                        BankAccountSnapshot.serializer(),
                        before.copy(finTsStatus = FinTsStatus.NOT_CONFIGURED),
                    ),
            )
            requireRowForRead(bankAccountId).toDto(finTsAvailable = finTsAvailable)
        }

    /** Read-only row lookup for FinTS operations that do not need a `forUpdate()` row lock (the write path above already took one). */
    private fun requireRowForRead(bankAccountId: Uuid): ResultRow =
        BankAccountTable.selectAll().where { BankAccountTable.id eq bankAccountId }.single()

    fun dtoOrNull(
        bankAccountId: Uuid,
        finTsAvailable: Boolean,
    ): BankAccountDto? =
        transaction {
            BankAccountTable.selectAll().where { BankAccountTable.id eq bankAccountId }.singleOrNull()?.toDto(
                finTsAvailable = finTsAvailable,
            )
        }

    private const val FINTS_USER_ID_MASK = "********"
}
