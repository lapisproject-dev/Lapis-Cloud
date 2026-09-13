package network.lapis.cloud.server.payment.bankstatement

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.rpc.ContributionPaymentEvents
import network.lapis.cloud.server.rpc.ContributionPostingBridge
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportResultDto
import network.lapis.cloud.shared.domain.BankStatementImportSnapshot
import network.lapis.cloud.shared.domain.BankStatementImportWarningCode
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** ANSI SQL `unique_violation` SQLSTATE -- both PostgreSQL and H2 (this codebase's test dialect) report this for a UNIQUE-index conflict. Same idiom `EventTicketIssuer`/`EventRegistrationSubmission` already establish. */
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

/**
 * Security finding fix (Review MAJOR, residual gap): guards every `postOneLine` correction UPDATE
 * (see the `when (outcome)` block at the end of that function) so it only applies when the line is
 * STILL `SUGGESTED` -- the correction transactions open only after the main attempt transaction has
 * rolled back, which releases its `forUpdate()` lock; without this guard, a concurrent
 * `BankStatementStore.assignLineToContribution`/`assignLineToDonation` call that wins that gap and
 * completes a real booking (status -> `POSTED`) would otherwise be silently clobbered back to
 * `AMBIGUOUS`/`UNMATCHED` by the correction, making the line reassignable again
 * (`requireAssignableLine` only refuses exactly `POSTED`) and reopening the same double-booking class
 * this whole guard mechanism exists to close.
 */
internal val bankStatementLineIsStillSuggested: Op<Boolean>
    get() = BankStatementLineTable.status eq BankStatementLineStatus.SUGGESTED

/** One line eligible for the Phase 2 auto-booking pass -- see [BankStatementImportService.import] KDoc "Phase 2". */
private data class AutoPostCandidate(
    val lineId: Uuid,
    val lineOrdinal: Int,
    val contributionId: Uuid,
    val memberId: Uuid,
    val amount: BigDecimal,
    val currency: String,
    val endToEndReference: String?,
    val fingerprint: String,
    val matchExplanation: String?,
)

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)". Orchestrates format detection -> parsing ->
 * per-line matching/persistence (Phase 1) -> per-line automatic booking (Phase 2). See
 * `network.lapis.cloud.server.routes.BankStatementRoutes` for the HTTP boundary (upload size cap,
 * role gate, error-status mapping).
 *
 * **Transaction shape, zwingend**:
 * - **Phase 1 (ONE transaction)**: account/duplicate checks, `bank_statement_import` insert, every
 *   line's `insertIgnore` (the `fingerprint` unique index is the dedup detector -- `insertedCount
 *   == 0` means "already imported before", counted but not re-matched), [BankStatementMatcher.match]
 *   for every newly-inserted line, its status/explanation persisted BEFORE any booking happens --
 *   an R1 auto-post candidate (`outcome.autoPost == true`) is persisted as `SUGGESTED` here, NEVER
 *   as the matcher's raw `POSTED`, precisely because Phase 2 has not run yet (see the Phase 1 loop's
 *   own comment for the crash-window this avoids).
 * - **Phase 2 (ONE transaction PER auto-postable line, via `postOneLine`)**: [ContributionPostingBridge
 *   .postContributionPayment] must be the LAST row-locking operation of ITS OWN transaction (see
 *   that object's KDoc) -- 200 bookings inside a single transaction would deadlock against the
 *   `ledger_account` locking order that bridge relies on, so each auto-post line gets its own
 *   transaction, structurally mirroring `network.lapis.cloud.server.payment.psp
 *   .PspWebhookIngestion`'s own "one payment_transaction insert + one guarded contribution update +
 *   one bridge call, all in one transaction" shape. Every documented business reason that
 *   transaction cannot reach `POSTED` (see `PostOutcome`) unwinds it via a thrown `PostOneLineHalt`
 *   rather than a normal `return@transaction false` -- so the `payment_transaction` insert and the
 *   `contribution.status = PAID` update either both commit together with `POSTED`, or neither
 *   commits at all. The line's own status correction for a non-`Posted` outcome then happens in a
 *   SEPARATE, freshly opened transaction (see `postOneLine`), never inside the one that may already
 *   be aborted (PostgreSQL `SQLSTATE 25P02` after a failed statement).
 *
 * **Privacy**: the raw bank-statement line text is NEVER read by this class at all (only the
 * already-decoded/parsed [ParsedLine]s are) -- see [BankStatementParseException] KDoc for the one
 * place a raw excerpt DOES surface (the HTTP 422 body, never here, never a log line). No
 * counterparty name/IBAN/purpose ever reaches [logger] -- every log line below names only ids,
 * counts, and enum values.
 */
internal class BankStatementImportService(
    private val secretBox: SecretBox?,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    fun import(
        bytes: ByteArray,
        fileName: String,
        uploadedBy: Uuid,
        uploaderRole: AccountRole,
        // Welle V1.4.14 "Mehrere Bankkonten". `null` is always valid -- see the account-ownership
        // resolution below for the exact fallback rules (explicit id > matched statement IBAN >
        // default account, once at least one `bank_account` row exists; EXACT pre-wave behaviour,
        // untouched, while none exists yet).
        bankAccountId: Uuid? = null,
    ): BankStatementImportResultDto {
        val fileDigest = sha256Hex(bytes)
        val decoded = BankStatementText.decode(bytes)
        val detection = BankStatementFormatDetector.detect(decoded)

        val (format, dialect, parsed) =
            when (detection) {
                is FormatDetection.Unrecognized ->
                    throw BankStatementRejectedException(
                        httpStatus = 422,
                        message = "Format nicht erkannt.",
                        code = BankStatementRejectionCode.FORMAT_UNRECOGNIZED,
                        observedHeaderFields = detection.observedHeaderFields,
                    )
                is FormatDetection.CsvDetected -> {
                    val parsedStatement = runCatching { BankCsvParser.parse(detection) }.getOrElse { throwAsRejection(it) }
                    Triple(BankStatementFormat.CSV, detection.dialect, parsedStatement)
                }
                is FormatDetection.Mt940Detected -> {
                    val parsedStatement = runCatching { Mt940Parser.parse(detection.text) }.getOrElse { throwAsRejection(it) }
                    Triple(BankStatementFormat.MT940, BankCsvDialect.GENERIC, parsedStatement)
                }
            }

        if (parsed.lines.size > MAX_STATEMENT_LINES) {
            throw BankStatementRejectedException(
                httpStatus = 422,
                message = "Der Auszug hat ${parsed.lines.size} Zeilen, das Limit liegt bei $MAX_STATEMENT_LINES.",
                code = BankStatementRejectionCode.TOO_MANY_LINES,
            )
        }

        // Security finding fix (Review MAJOR): reject BEFORE the Phase 1 transaction even opens --
        // see ParsedLine.containsControlCharacter KDoc for why an uncaught control character reaching
        // the insert chain aborts the whole transaction with an opaque 500 instead of this
        // diagnosable, per-line 422. No rawLineExcerpt is included (this class never reads raw line
        // text, see class KDoc "Privacy") -- only the 1-based line number.
        parsed.lines.forEachIndexed { index, line ->
            if (line.containsControlCharacter()) {
                throw BankStatementRejectedException(
                    httpStatus = 422,
                    message = "Zeile ${index + 1} enthaelt ein ungueltiges Steuerzeichen.",
                    code = BankStatementRejectionCode.CONTROL_CHARACTER,
                    lineNumber = index + 1,
                )
            }
        }

        val warnings = mutableListOf<String>()
        // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): parallel machine-readable list -- see
        // BankStatementImportWarningCode KDoc. `warnings` (German prose) is kept as the
        // server-internal/audit counterpart, never rendered by the client anymore.
        val warningCodes = mutableListOf<BankStatementImportWarningCode>()
        val normalizedAccountIban = parsed.accountIban?.let { IbanValidator.normalize(it) }
        val accountIbanIsValidIban = normalizedAccountIban != null && IbanValidator.isValid(normalizedAccountIban)

        val phase1: ImportPhase1Result =
            transaction {
                if (BankStatementImportTable.selectAll().where { BankStatementImportTable.fileDigest eq fileDigest }.count() > 0) {
                    throw BankStatementRejectedException(
                        httpStatus = 409,
                        message = "Diese Datei wurde bereits importiert (identischer Datei-Digest).",
                        code = BankStatementRejectionCode.ALREADY_IMPORTED,
                    )
                }

                // Welle V1.4.14 "Mehrere Bankkonten" -- account-ownership resolution. While
                // `bank_account` has zero rows, this is BYTE-FOR-BYTE the pre-wave check (against
                // `organization_settings.bank_iban` alone) -- every existing test/behaviour stays
                // unchanged. Once at least one `bank_account` row exists, that table becomes the
                // sole source of truth for ownership (it mirrors `organization_settings.bank_iban`/
                // `bank_bic` for its own default row anyway, see `BankAccountStore` KDoc), and an
                // explicitly passed [bankAccountId] takes precedence over IBAN matching.
                val bankAccountRowCount = BankAccountTable.selectAll().count()
                var resolvedBankAccountId: Uuid? = null
                var resolvedBankAccountLabel: String? = null

                if (bankAccountRowCount > 0) {
                    if (bankAccountId != null) {
                        val explicit =
                            BankAccountTable.selectAll().where { BankAccountTable.id eq bankAccountId }.singleOrNull()
                                // Review fix (MINOR, Review Round 3): own code, split out of
                                // FOREIGN_ACCOUNT -- the explicit id simply does not reference any
                                // configured account (deleted concurrently, or a stale client), which
                                // is a DIFFERENT situation from a statement genuinely belonging to a
                                // different, EXISTING account (that case keeps FOREIGN_ACCOUNT below).
                                ?: throw BankStatementRejectedException(
                                    httpStatus = 422,
                                    message = "Das angegebene Bankkonto existiert nicht.",
                                    code = BankStatementRejectionCode.UNKNOWN_BANK_ACCOUNT,
                                )
                        val explicitIban = IbanValidator.normalize(explicit[BankAccountTable.iban])
                        if (accountIbanIsValidIban && normalizedAccountIban != explicitIban) {
                            throw BankStatementRejectedException(
                                httpStatus = 422,
                                message = "Der Auszug gehoert zu einem anderen Konto.",
                                code = BankStatementRejectionCode.FOREIGN_ACCOUNT,
                            )
                        }
                        resolvedBankAccountId = bankAccountId
                        resolvedBankAccountLabel = explicit[BankAccountTable.label]
                    } else if (accountIbanIsValidIban && normalizedAccountIban != null) {
                        val matched =
                            BankAccountTable.selectAll().where { BankAccountTable.iban eq normalizedAccountIban }.singleOrNull()
                                // Review fix (MINOR, Review Round 3): own code, split out of
                                // FOREIGN_ACCOUNT -- the statement's own IBAN does not match ANY
                                // configured account (there is no single "the other account" it
                                // could be pointed at, unlike the explicit-id-with-conflicting-IBAN
                                // branch above, which keeps FOREIGN_ACCOUNT).
                                ?: throw BankStatementRejectedException(
                                    httpStatus = 422,
                                    message = "Der Auszug gehoert zu keinem der hinterlegten Bankkonten.",
                                    code = BankStatementRejectionCode.UNKNOWN_BANK_ACCOUNT,
                                )
                        resolvedBankAccountId = matched[BankAccountTable.id]
                        resolvedBankAccountLabel = matched[BankAccountTable.label]
                    } else {
                        // No explicit account and no (valid) statement-level IBAN to match against
                        // -- attribute to the default account rather than reject outright (many CSV
                        // dialects carry no account identifier at all, same reasoning the pre-wave
                        // NO_BANK_ACCOUNT_CONFIGURED/LEGACY_ACCOUNT_IBAN_FORMAT warnings already
                        // apply below this account has at least the same information as before).
                        val default = BankAccountTable.selectAll().where { BankAccountTable.isDefault eq true }.singleOrNull()
                        resolvedBankAccountId = default?.get(BankAccountTable.id)
                        resolvedBankAccountLabel = default?.get(BankAccountTable.label)
                        // Review fix (MAJOR, findings #2 + #4): every silent default-account
                        // attribution while at least one bank_account row exists gets its OWN
                        // warning code -- previously this fired NOTHING at all when the statement
                        // simply carried no account identifier (the common CSV case, BankCsvParser
                        // never sets one), and reused LEGACY_ACCOUNT_IBAN_FORMAT when it carried an
                        // unparseable one, whose client-rendered label ("Kontoprüfung übersprungen")
                        // no longer matched what actually happened here (an attribution DID happen).
                        // LEGACY_ACCOUNT_IBAN_FORMAT now stays reserved for the zero-bank_account
                        // legacy branch below, where "Kontoprüfung übersprungen" is accurate (no
                        // bank_account row exists to attribute to).
                        if (resolvedBankAccountId != null) {
                            warnings +=
                                "Der Auszug enthaelt keine (gueltige) Kontokennung -- dem Standardkonto " +
                                "\"$resolvedBankAccountLabel\" zugeordnet."
                            warningCodes += BankStatementImportWarningCode.ATTRIBUTED_TO_DEFAULT_ACCOUNT
                        }
                    }
                } else {
                    val orgSettingsRow = OrganizationSettingsTable.selectAll().singleOrNull()
                    val orgBankIban = orgSettingsRow?.get(OrganizationSettingsTable.bankIban)?.let { IbanValidator.normalize(it) }
                    if (orgBankIban != null && accountIbanIsValidIban && normalizedAccountIban != orgBankIban) {
                        throw BankStatementRejectedException(
                            httpStatus = 422,
                            message = "Der Auszug gehoert zu einem anderen Konto.",
                            code = BankStatementRejectionCode.FOREIGN_ACCOUNT,
                        )
                    }
                    if (orgBankIban == null) {
                        warnings += "Kein Bankkonto in den Organisationseinstellungen hinterlegt -- Kontopruefung uebersprungen."
                        warningCodes += BankStatementImportWarningCode.NO_BANK_ACCOUNT_CONFIGURED
                    } else if (parsed.accountIban != null && !accountIbanIsValidIban) {
                        warnings += "Kontokennung des Auszugs ist keine gueltige IBAN (Altformat?) -- Kontopruefung uebersprungen."
                        warningCodes += BankStatementImportWarningCode.LEGACY_ACCOUNT_IBAN_FORMAT
                    }
                }
                if (secretBox == null) {
                    warnings += "IBAN-Abgleich nicht verfuegbar -- LAPIS_SECRET_ENCRYPTION_KEY ist nicht konfiguriert."
                    warningCodes += BankStatementImportWarningCode.IBAN_MATCHING_UNAVAILABLE
                }

                val importId = Uuid.random()
                val now = clock()

                // Review fix (MINOR): the count()-check above is now backed by a real
                // uq_bank_statement_import_file_digest UNIQUE index (see V20 migration), so a genuine
                // concurrent-upload race (two Kassenwarte, or a double-clicked upload button) that
                // slips past that advisory pre-check hits this constraint instead -- converted to the
                // SAME 409 the pre-check already promises, rather than an uncaught ExposedSQLException
                // (which would otherwise surface as an undiagnosable 500).
                val inserted =
                    runCatching {
                        BankStatementImportTable.insert {
                            it[id] = importId
                            it[BankStatementImportTable.format] = format
                            it[BankStatementImportTable.dialect] = dialect.name
                            it[BankStatementImportTable.fileName] = fileName.take(255)
                            it[fileSizeBytes] = bytes.size.toLong()
                            it[BankStatementImportTable.fileDigest] = fileDigest
                            // Review fix (MINOR): capped to the column's own width (VARCHAR(34), the
                            // longest formally possible IBAN) -- previously inserted RAW. A `:25:` tag
                            // whose value exceeds 34 chars (SWIFT allows up to 35x for this field, and
                            // tokenizeTags folds continuation lines into the same tag value) already
                            // fails IbanValidator.isValid on length alone, so accountIbanIsValidIban is
                            // already false and only the "Altformat?" warning fires above -- nothing
                            // there prevented the uncapped value from still reaching this INSERT and
                            // failing with SQLSTATE 22001 ("value too long"), which the broad
                            // ExposedSQLException catch below then misdiagnosed as a duplicate-file 409.
                            it[accountIban] = parsed.accountIban?.take(34)
                            it[statementFrom] = parsed.statementFrom
                            it[statementTo] = parsed.statementTo
                            it[openingBalance] = parsed.openingBalance
                            it[closingBalance] = parsed.closingBalance
                            it[lineCount] = parsed.lines.size
                            it[duplicateCount] = 0 // updated below once known
                            it[autoPostedCount] = 0 // updated after Phase 2
                            it[BankStatementImportTable.uploadedBy] = uploadedBy
                            it[uploadedAt] = now
                            it[BankStatementImportTable.bankAccountId] = resolvedBankAccountId
                        }
                    }
                if (inserted.isFailure) {
                    val cause = inserted.exceptionOrNull()
                    // Review fix (MINOR): narrowed to the genuine uq_bank_statement_import_file_digest
                    // race this branch's own KDoc above describes (SQLSTATE 23505) -- same reasoning as
                    // postOneLine's own ExposedSQLException narrowing (see UNIQUE_VIOLATION_SQL_STATE
                    // KDoc). Blanket-catching every ExposedSQLException here mislabeled ANY other insert
                    // failure (e.g. a "value too long" on account_iban, VARCHAR(34)) as "already
                    // imported" -- a permanently wrong, unactionable 409 for a file that had never been
                    // imported before and, since nothing was actually written, is not idempotently
                    // retriable under that same wrong diagnosis either.
                    if (cause is ExposedSQLException && cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                        throw BankStatementRejectedException(
                            httpStatus = 409,
                            message = "Diese Datei wurde bereits importiert (identischer Datei-Digest).",
                            code = BankStatementRejectionCode.ALREADY_IMPORTED,
                        )
                    }
                    throw cause ?: IllegalStateException("bank_statement_import insert failed with no exception")
                }

                // Review fix (CRITICAL, finding #1; narrowed further -- MEDIUM finding, Review
                // Round 3): see BankStatementFingerprint KDoc "bankAccountDiscriminator" for the
                // full rationale. Unconditional now (no longer gated on `bankAccountRowCount > 1`)
                // -- that gate made the discriminator depend on a MUTABLE count that flips both ways
                // (a 2nd account added, or a 2nd account later deleted again), silently changing
                // every future fingerprint for an account whose OWN identity never changed and
                // opening a duplicate-booking window each time it flipped. Backward-compatibility
                // with every already-stored (discriminator-less, or differently-discriminated)
                // fingerprint is instead handled explicitly per line below via
                // `alreadyImportedUnderLegacyFingerprint` -- scoped to the resolved account's OWN
                // discriminator-bearing history, see that check's own comment for why a plain
                // per-account scope is both reachable and correct.
                val bankAccountDiscriminator: String? = resolvedBankAccountId?.toString()
                // Stable `val` copy of the mutable `resolvedBankAccountId` for the closure below --
                // its value never changes again once phase 1 reaches this point, but a captured
                // `var` cannot be smart-cast to non-null across the lambda boundary.
                val discriminatorAccountId: Uuid? = resolvedBankAccountId

                val occurrenceCounters = mutableMapOf<String, Int>()
                var duplicateCount = 0
                val statusCounts = mutableMapOf<BankStatementLineStatus, Int>()
                val autoPostCandidates = mutableListOf<AutoPostCandidate>()

                parsed.lines.forEachIndexed { index, line ->
                    // Review fix (MAJOR): must use the SAME normalization BankStatementFingerprint.of
                    // itself hashes -- see BankStatementFingerprint KDoc "groupingKey is exposed" for
                    // why building this key from the raw fields (trim/case/amount-scale differences
                    // included) let a genuine second payment silently vanish as a false duplicate.
                    val occurrenceKeyBase =
                        BankStatementFingerprint.groupingKey(
                            accountIban = normalizedAccountIban,
                            bookingDate = line.bookingDate,
                            valueDate = line.valueDate,
                            amount = line.amount,
                            currency = line.currency,
                            counterpartyName = line.counterpartyName,
                            counterpartyIban = line.counterpartyIban,
                            purpose = line.purpose,
                            endToEndReference = line.endToEndReference,
                            bankAccountDiscriminator = bankAccountDiscriminator,
                        )
                    val occurrenceIndex = occurrenceCounters.getOrDefault(occurrenceKeyBase, 0)
                    occurrenceCounters[occurrenceKeyBase] = occurrenceIndex + 1

                    val fingerprint =
                        BankStatementFingerprint.of(
                            accountIban = normalizedAccountIban,
                            bookingDate = line.bookingDate,
                            valueDate = line.valueDate,
                            amount = line.amount,
                            currency = line.currency,
                            counterpartyName = line.counterpartyName,
                            counterpartyIban = line.counterpartyIban,
                            purpose = line.purpose,
                            endToEndReference = line.endToEndReference,
                            occurrenceIndex = occurrenceIndex,
                            bankAccountDiscriminator = bankAccountDiscriminator,
                        )

                    // Review fix (MEDIUM, Review Round 3; corrected -- Round 4, MAJOR
                    // "Doppelbuchung realer Zahlungen im Upgrade-Pfad"): the discriminator above is
                    // now unconditional, so a fingerprint stored BEFORE this account ever carried a
                    // discriminator (no bank_account row yet, exactly one, or a since-deleted SECOND
                    // account that made this one discriminator-bearing for a while) no longer matches
                    // it -- `insertIgnore` below would then insert a genuine-looking but actually
                    // ALREADY-imported line a second time. Closes that gap by also checking the
                    // LEGACY (no-discriminator) fingerprint for the SAME line.
                    //
                    // Round 3 scoped this check to `bankAccountId eq discriminatorAccountId`, which
                    // WAS unreachable by construction at the time: `bank_statement_import.bank_account_id`
                    // is written exactly once (below, from this same `resolvedBankAccountId`), and the
                    // discriminator is derived from that identical value -- so `bank_account_id`
                    // non-null implied the stored fingerprint for that import already carried a
                    // discriminator, while every legacy (discriminator-less) row -- imports made
                    // before any `bank_account` row existed, see V32 migration comment -- still had
                    // `bank_account_id IS NULL` and could therefore never satisfy that filter.
                    //
                    // Fixed at the ROOT instead of re-conditioning this filter a second time (Review
                    // Round 5, residuum of finding #1 "Doppelbuchung realer Zahlungen UND stiller
                    // Verlust echter Buchungen"): an intermediate (Round 4) version of this fix
                    // additionally matched `bank_account_id IS NULL` rows here whenever
                    // [discriminatorAccountId] happened to be the organization's CURRENT default --
                    // but `isDefault` is mutable (`BankAccountStore.setDefault`, and the
                    // default-promotion in `BankAccountStore.delete`), while a legacy row's true owner
                    // is not, so moving the default elsewhere silently mismatched every legacy row in
                    // BOTH directions (a genuine re-import of the legacy account's own history stopped
                    // deduplicating once it was no longer default; a coincidentally-identical NEW
                    // booking on whichever account was default now got wrongly swallowed as a
                    // duplicate of someone else's old line).
                    //
                    // `BankAccountStore.adoptLegacyBankStatementImports` (called from both `create`'s
                    // `makeDefault` branch and `backfillLegacyDefaultAccountIfNeeded`) closes this
                    // properly: the INSTANT this organization's first-ever `bank_account` row is
                    // created, every `bank_statement_import` row still carrying `bank_account_id IS
                    // NULL` at that moment is rewritten to that account's own id -- see its KDoc for
                    // why that is both safe (this codebase is single-tenant; no OTHER account could
                    // ever be the true owner) and permanent (no row can ever become NULL again
                    // afterwards, and the reassignment never changes once made, so a LATER
                    // `setDefault`/deletion elsewhere cannot un-adopt it). `bank_account_id IS NULL`
                    // therefore no longer occurs once any `bank_account` row exists, and the plain
                    // Round-3 filter below is simultaneously reachable (every legacy row now carries
                    // its real owner's id, non-NULL) and correct (that id is the row's permanent,
                    // immutable owner, never a coincidentally-current default).
                    val alreadyImportedUnderLegacyFingerprint =
                        discriminatorAccountId != null &&
                            run {
                                val legacyFingerprint =
                                    BankStatementFingerprint.of(
                                        accountIban = normalizedAccountIban,
                                        bookingDate = line.bookingDate,
                                        valueDate = line.valueDate,
                                        amount = line.amount,
                                        currency = line.currency,
                                        counterpartyName = line.counterpartyName,
                                        counterpartyIban = line.counterpartyIban,
                                        purpose = line.purpose,
                                        endToEndReference = line.endToEndReference,
                                        occurrenceIndex = occurrenceIndex,
                                        bankAccountDiscriminator = null,
                                    )
                                (BankStatementLineTable innerJoin BankStatementImportTable)
                                    .selectAll()
                                    .where {
                                        (BankStatementLineTable.fingerprint eq legacyFingerprint) and
                                            (BankStatementImportTable.bankAccountId eq discriminatorAccountId)
                                    }.limit(1)
                                    .count() > 0
                            }
                    if (alreadyImportedUnderLegacyFingerprint) {
                        duplicateCount++
                        return@forEachIndexed
                    }

                    val lineId = Uuid.random()
                    // Review fix (MINOR): capped to the longest formally possible IBAN (34 chars, the
                    // same bound account_iban and IbanValidator.isValid already apply) BEFORE sealing --
                    // previously the normalized-but-uncapped value was sealed directly. Both parsers can
                    // hand back an oversized counterpartyIban (MT940 folds `?NN`-less continuation lines
                    // into the same subfield via tokenizeTags, and normalize() only strips whitespace/
                    // uppercases, it does not truncate), and SecretBox.seal's base64url ciphertext grows
                    // past the counterparty_iban_ciphertext VARCHAR(1024) column at plaintext lengths as
                    // low as 738 bytes -- an uncaught SQLSTATE 22001 mid-loop that rolls back the whole
                    // Phase-1 transaction with no diagnosis. Deriving ibanLast4 from the SAME capped
                    // variable keeps last4 and ciphertext from ever disagreeing.
                    val normalizedCounterpartyIban = line.counterpartyIban?.let { IbanValidator.normalize(it).take(34) }
                    val sealedIban =
                        if (secretBox != null && normalizedCounterpartyIban != null) {
                            secretBox.seal(plaintext = normalizedCounterpartyIban, aad = lineId.toString())
                        } else {
                            null
                        }
                    val ibanLast4 = if (sealedIban != null) normalizedCounterpartyIban!!.takeLast(4) else null

                    val insertedCount =
                        BankStatementLineTable
                            .insertIgnore {
                                it[id] = lineId
                                it[BankStatementLineTable.importId] = importId
                                it[BankStatementLineTable.fingerprint] = fingerprint
                                it[lineOrdinal] = index
                                it[bookingDate] = line.bookingDate
                                it[valueDate] = line.valueDate
                                it[amount] = line.amount
                                it[currency] = line.currency
                                it[counterpartyName] = line.counterpartyName?.take(140)
                                it[counterpartyIbanLast4] = ibanLast4
                                it[counterpartyIbanCiphertext] = sealedIban
                                it[purpose] = line.purpose?.take(MAX_PURPOSE_LENGTH)
                                it[endToEndReference] = line.endToEndReference?.take(140)
                                it[bookingText] = line.bookingText?.take(64)
                                it[status] = BankStatementLineStatus.UNMATCHED
                                it[matchExplanation] = null
                                it[matchedContributionId] = null
                                it[paymentTransactionId] = null
                                it[resolvedBy] = null
                                it[resolvedAt] = null
                                it[resolutionNote] = null
                            }.insertedCount

                    if (insertedCount == 0) {
                        duplicateCount++
                        return@forEachIndexed
                    }

                    val outcome =
                        BankStatementMatcher.match(
                            line =
                                NormalizedLine(
                                    amount = line.amount,
                                    currency = line.currency,
                                    purpose = line.purpose,
                                    counterpartyName = line.counterpartyName,
                                    counterpartyIbanRaw = line.counterpartyIban,
                                ),
                            secretBox = secretBox,
                        )
                    // Review fix (MEDIUM): an R1 auto-post candidate (outcome.status == POSTED) must
                    // NOT be persisted as POSTED yet -- Phase 2 (its own, later transaction per
                    // candidate, see class KDoc "Phase 2") is what actually books it. Persisting the
                    // matcher's raw POSTED status here would leave a line stuck at POSTED forever if
                    // the process crashes/restarts between Phase 1's commit and Phase 2 running (no
                    // payment_transaction, no journal_entry, contribution still OPEN) --
                    // requireAssignableLine's "a POSTED line can never be reassigned" rule would then
                    // block any manual correction permanently. SUGGESTED is the correct interim
                    // status ("queued for auto-posting", not "done"); Phase 2 below either promotes
                    // it to POSTED or demotes it to UNMATCHED/AMBIGUOUS once the real outcome is known
                    // -- see the `finalStatusCounts` adjustment in [import] that keeps the returned
                    // summary counts in sync with whichever actually happens.
                    val persistedStatus = if (outcome.autoPost) BankStatementLineStatus.SUGGESTED else outcome.status
                    statusCounts[persistedStatus] = (statusCounts[persistedStatus] ?: 0) + 1

                    BankStatementLineTable.update({ BankStatementLineTable.id eq lineId }) {
                        it[status] = persistedStatus
                        it[matchExplanation] = outcome.explanation?.take(500)
                        it[matchedContributionId] = if (outcome.autoPost) outcome.contributionId else null
                    }

                    if (outcome.autoPost && outcome.contributionId != null) {
                        val memberId =
                            ContributionTable
                                .selectAll()
                                .where { ContributionTable.id eq outcome.contributionId }
                                .single()[ContributionTable.memberId]
                        autoPostCandidates +=
                            AutoPostCandidate(
                                lineId = lineId,
                                lineOrdinal = index,
                                contributionId = outcome.contributionId,
                                memberId = memberId,
                                amount = line.amount,
                                currency = line.currency,
                                endToEndReference = line.endToEndReference,
                                fingerprint = fingerprint,
                                matchExplanation = outcome.explanation,
                            )
                    }
                }

                BankStatementImportTable.update({ BankStatementImportTable.id eq importId }) {
                    it[BankStatementImportTable.duplicateCount] = duplicateCount
                }

                // Phase 1 finished -- hand the immutable plan over to Phase 2 (own transactions each).
                ImportPhase1Result(
                    importId = importId,
                    format = format,
                    dialect = dialect,
                    accountIbanMasked = maskIban(parsed.accountIban),
                    statementFrom = parsed.statementFrom,
                    statementTo = parsed.statementTo,
                    balanceChecked = format == BankStatementFormat.MT940,
                    duplicateCount = duplicateCount,
                    statusCounts = statusCounts,
                    autoPostCandidates = autoPostCandidates,
                    bankAccountId = resolvedBankAccountId,
                    bankAccountLabel = resolvedBankAccountLabel,
                )
            }

        // Review fix (MEDIUM): phase1.statusCounts alone would under-report -- every autoPost
        // candidate was bucketed as SUGGESTED (see the Phase 1 loop above), but Phase 2 below may
        // move some of them to POSTED (success), UNMATCHED (account mapping incomplete), or
        // AMBIGUOUS (contribution no longer outstanding / booking failed) instead. finalStatusCounts
        // starts as a copy of the Phase-1 snapshot and is corrected in lock-step with each
        // [postOneLine] outcome so the returned summary always matches what actually ended up in the
        // database -- never "0 unmatched" while a line silently needs manual attention.
        var autoPostedCount = 0
        val finalStatusCounts = phase1.statusCounts.toMutableMap()

        fun moveCount(
            from: BankStatementLineStatus,
            to: BankStatementLineStatus,
        ) {
            finalStatusCounts[from] = (finalStatusCounts[from] ?: 0) - 1
            finalStatusCounts[to] = (finalStatusCounts[to] ?: 0) + 1
        }
        phase1.autoPostCandidates.forEach { candidate ->
            when (postOneLine(candidate = candidate, importId = phase1.importId, uploadedBy = uploadedBy, uploaderRole = uploaderRole)) {
                PostOutcome.Posted -> {
                    autoPostedCount++
                    finalStatusCounts[BankStatementLineStatus.SUGGESTED] = (finalStatusCounts[BankStatementLineStatus.SUGGESTED] ?: 0) - 1
                }
                // The line's persisted status is unchanged (stays SUGGESTED, see postOneLine KDoc) --
                // already correctly bucketed by the Phase 1 snapshot above, nothing to move.
                PostOutcome.AlreadyBooked -> Unit
                // The concurrent winner (manual assign/ignore) already moved this line out of
                // SUGGESTED and is authoritative -- see PostOutcome.LineNoLongerPending KDoc. The
                // returned summary's SUGGESTED bucket stays off-by-one for this line (same accepted
                // tradeoff as AlreadyBooked above); this is an extremely narrow timing window and the
                // summary is informational only, never persisted.
                PostOutcome.LineNoLongerPending -> Unit
                PostOutcome.ContributionAlreadySettled -> moveCount(BankStatementLineStatus.SUGGESTED, BankStatementLineStatus.AMBIGUOUS)
                PostOutcome.BookingIncomplete -> moveCount(BankStatementLineStatus.SUGGESTED, BankStatementLineStatus.UNMATCHED)
                PostOutcome.BookingConflict -> moveCount(BankStatementLineStatus.SUGGESTED, BankStatementLineStatus.AMBIGUOUS)
            }
        }

        transaction {
            BankStatementImportTable.update({ BankStatementImportTable.id eq phase1.importId }) {
                it[BankStatementImportTable.autoPostedCount] = autoPostedCount
            }
            AuditLogRecorder.record(
                actorMemberId = uploadedBy,
                actorRole = uploaderRole,
                entityType = AuditEntityType.BANK_STATEMENT_IMPORT,
                entityId = phase1.importId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        BankStatementImportSnapshot.serializer(),
                        BankStatementImportSnapshot(
                            format = phase1.format,
                            dialect = phase1.dialect.name,
                            fileName = fileName.take(255),
                            fileSizeBytes = bytes.size.toLong(),
                            lineCount = parsed.lines.size,
                            duplicateCount = phase1.duplicateCount,
                            autoPostedCount = autoPostedCount,
                        ),
                    ),
            )
        }

        val ambiguous = finalStatusCounts[BankStatementLineStatus.AMBIGUOUS] ?: 0
        val unmatched = finalStatusCounts[BankStatementLineStatus.UNMATCHED] ?: 0
        val ignored = finalStatusCounts[BankStatementLineStatus.IGNORED] ?: 0
        val suggested = finalStatusCounts[BankStatementLineStatus.SUGGESTED] ?: 0

        return BankStatementImportResultDto(
            importId = phase1.importId.toString(),
            format = phase1.format,
            dialect = phase1.dialect,
            accountIbanMasked = phase1.accountIbanMasked,
            statementFrom = phase1.statementFrom,
            statementTo = phase1.statementTo,
            balanceChecked = phase1.balanceChecked,
            lineCount = parsed.lines.size,
            duplicateCount = phase1.duplicateCount,
            autoPostedCount = autoPostedCount,
            ambiguousCount = ambiguous,
            unmatchedCount = unmatched,
            ignoredCount = ignored,
            suggestedCount = suggested,
            warnings = warnings,
            warningCodes = warningCodes,
            bankAccountId = phase1.bankAccountId?.toString(),
            bankAccountLabel = phase1.bankAccountLabel,
        )
    }

    /** Result of Phase 1, handed to Phase 2 -- module-private carrier, not part of the public API. */
    private data class ImportPhase1Result(
        val importId: Uuid,
        val format: BankStatementFormat,
        val dialect: BankCsvDialect,
        val accountIbanMasked: String?,
        val statementFrom: kotlinx.datetime.LocalDate?,
        val statementTo: kotlinx.datetime.LocalDate?,
        val balanceChecked: Boolean,
        val duplicateCount: Int,
        val statusCounts: Map<BankStatementLineStatus, Int>,
        val autoPostCandidates: List<AutoPostCandidate>,
        val bankAccountId: Uuid?,
        val bankAccountLabel: String?,
    )

    /**
     * Terminal outcome of one line's Phase 2 attempt ([postOneLine]) -- [Posted] is the only case
     * that actually reached a booked journal entry; every other case is a documented business
     * reason Phase 2 could not complete, each with its own line status/note (see [postOneLine]).
     */
    private sealed interface PostOutcome {
        /** A `payment_transaction` with this exact `(MANUAL, fingerprint)` pair already exists -- this candidate was already booked by an earlier run of this same import (see [postOneLine] KDoc "AlreadyBooked"). Line status is left unchanged (still `SUGGESTED`, see Phase 1). */
        data object AlreadyBooked : PostOutcome

        /**
         * Security finding fix (Review MAJOR): the line's row lock (see [postOneLine]'s `forUpdate()`
         * read) showed a status other than `SUGGESTED` -- a concurrent
         * [BankStatementStore.assignLineToContribution]/[BankStatementStore.assignLineToDonation]/
         * [BankStatementStore.ignoreLine] call won the race and already resolved this line via a
         * DIFFERENT `payment_transaction.provider_event_id` (`"manual-assign:$lineId"`/
         * `"manual-donation:$lineId"` vs. this candidate's own `fingerprint`), so
         * `uq_payment_transaction_provider_event` alone would never have caught the double-booking --
         * two separate journal entries for one real bank transaction. No further write happens for
         * this outcome (see the outer `when` below) -- the concurrent winner's status/resolutionNote
         * is authoritative and must not be overwritten.
         */
        data object LineNoLongerPending : PostOutcome

        /** The contribution was concurrently settled (or entered a SEPA debit run) between Phase 1's match and this line's own Phase-2 transaction. Line -> `AMBIGUOUS`. */
        data object ContributionAlreadySettled : PostOutcome

        /** [ContributionPostingBridge.postContributionPayment] degraded (incomplete/inactive ledger-account mapping, see its own KDoc) instead of booking. Line -> `UNMATCHED`. */
        data object BookingIncomplete : PostOutcome

        /** [ContributionPostingBridge.postContributionPayment] threw [ConflictException] (unbalanced postings / GoBD cash-register guard). Line -> `AMBIGUOUS`. */
        data object BookingConflict : PostOutcome

        /** The journal entry was booked and the contribution is `PAID`. Line -> `POSTED`. */
        data object Posted : PostOutcome
    }

    /**
     * Thrown from inside [postOneLine]'s `transaction {}` to unwind it via Exposed's own normal
     * exception-triggered rollback -- Review fix (MAJOR): the `payment_transaction` insert and the
     * `contribution.status = PAID` update, both already executed earlier in the SAME transaction,
     * must be undone together whenever the line cannot reach `POSTED` for one of [PostOutcome]'s
     * documented business reasons. The previous code instead patched the line back to
     * `UNMATCHED`/`AMBIGUOUS` and `return@transaction false`'d -- a NORMAL return, which COMMITS
     * everything written so far: `contribution.status = PAID` with no `journal_entry`, and no way to
     * ever fix it (`BankStatementStore.assignLineToContribution`/`requireAssignableLine` both refuse
     * to touch a contribution that is already `SETTLED`/`PAID`). Throwing instead of patch-and-return
     * makes that state structurally unreachable: [outcome] is applied to the line in a FRESH
     * transaction, opened only AFTER this one has actually rolled back.
     */
    private class PostOneLineHalt(
        val outcome: PostOutcome,
    ) : Exception()

    /**
     * Phase 2 for exactly one line -- own transaction (see class KDoc). Returns the outcome the
     * caller ([import]) uses both to count this line towards `autoPostedCount` and to correct
     * `finalStatusCounts` (see there) -- never throws.
     */
    private fun postOneLine(
        candidate: AutoPostCandidate,
        importId: Uuid,
        uploadedBy: Uuid,
        uploaderRole: AccountRole,
    ): PostOutcome {
        val outcome: PostOutcome =
            try {
                transaction {
                    // Security finding fix (Review MAJOR): row-locks the line for the rest of this
                    // transaction and re-checks its status -- closes the race against
                    // BankStatementStore.assignLineToContribution/assignLineToDonation/ignoreLine,
                    // which take the SAME lock via requireAssignableLine's own `forUpdate()`. Without
                    // this, a Treasurer manually assigning this SUGGESTED line at the same moment
                    // Phase 2 processes it could both succeed (different provider_event_id
                    // fingerprints per path, so uq_payment_transaction_provider_event never fires
                    // across paths) -- one real bank transaction booked twice. See
                    // PostOutcome.LineNoLongerPending KDoc.
                    val currentLineStatus =
                        BankStatementLineTable
                            .selectAll()
                            .where { BankStatementLineTable.id eq candidate.lineId }
                            .forUpdate()
                            .singleOrNull()
                            ?.get(BankStatementLineTable.status)
                    if (currentLineStatus != BankStatementLineStatus.SUGGESTED) {
                        throw PostOneLineHalt(PostOutcome.LineNoLongerPending)
                    }

                    val paymentTransactionId = Uuid.random()
                    val now = clock()
                    val inserted =
                        runCatching {
                            PaymentTransactionTable.insert {
                                it[id] = paymentTransactionId
                                it[provider] = PaymentProvider.MANUAL
                                it[providerEventId] = candidate.fingerprint
                                it[providerPaymentId] = candidate.endToEndReference ?: candidate.fingerprint
                                it[status] = PaymentTransactionStatus.CAPTURED
                                it[amount] = candidate.amount
                                it[currency] = candidate.currency
                                it[feeAmount] = null
                                it[intent] = PaymentIntent.CONTRIBUTION
                                it[contributionId] = candidate.contributionId
                                it[memberId] = candidate.memberId
                                it[payerReference] = null
                                it[receivedAt] = now
                                it[reconciledAt] = now
                                it[reconciledBy] = uploadedBy
                                it[reconciliationNote] = candidate.matchExplanation?.take(2000)
                                it[rawPayloadDigest] = candidate.fingerprint
                                it[checkoutSessionId] = null
                                it[donorCategory] = null
                            }
                        }
                    if (inserted.isFailure) {
                        val cause = inserted.exceptionOrNull()
                        // Review fix (MINOR): only a genuine violation of
                        // uq_payment_transaction_provider_event (SQLSTATE 23505, "unique_violation")
                        // -- the actual `(MANUAL, fingerprint)` idempotency anchor this class KDoc
                        // "AlreadyBooked" describes -- may be diagnosed as AlreadyBooked. Every OTHER
                        // ExposedSQLException (e.g. a "value too long" on provider_payment_id, which
                        // carries no length cap of its own) used to be blanket-classified the same
                        // way, silently mislabeling a genuine data/schema bug as "already booked":
                        // the line kept its SUGGESTED status with a resolutionNote claiming the exact
                        // opposite of what happened (nothing was ever booked). Rethrown instead, same
                        // "on PostgreSQL a failed INSERT aborts the whole transaction (SQLSTATE
                        // 25P02)" reasoning as before -- no further statement may run on this
                        // transaction either way, so throwing immediately is correct for both cases.
                        if (cause is ExposedSQLException && cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                            throw PostOneLineHalt(PostOutcome.AlreadyBooked)
                        }
                        throw cause ?: IllegalStateException("payment_transaction insert failed with no exception")
                    }

                    val updated =
                        ContributionTable.update({
                            (ContributionTable.id eq candidate.contributionId) and
                                (
                                    ContributionTable.status notInList
                                        (ContributionStatusSets.SETTLED + ContributionStatusSets.DEBIT_IN_FLIGHT).toList()
                                )
                        }) {
                            it[status] = ContributionStatus.PAID
                            it[paidAt] = now
                            it[paidAmount] = candidate.amount
                            it[paymentMethod] = ContributionPaymentMethod.MANUAL
                        }
                    if (updated == 0) {
                        throw PostOneLineHalt(PostOutcome.ContributionAlreadySettled)
                    }

                    val journalEntryId =
                        ContributionPostingBridge.postContributionPayment(
                            contributionId = candidate.contributionId,
                            paidAmount = candidate.amount,
                            paidAt = now,
                            source = ContributionPaymentMethod.MANUAL,
                            providerFee = null,
                            actorMemberId = uploadedBy,
                            actorRole = uploaderRole,
                            voucherReference = "KA-${importId.toString().take(8)}-${candidate.lineOrdinal}",
                        )
                    if (journalEntryId == null) {
                        // Review fix (MAJOR) -- see [PostOneLineHalt] KDoc: throwing here (instead of
                        // patching the line back to UNMATCHED and `return@transaction false`ing) rolls
                        // back the payment_transaction insert AND the contribution.status = PAID
                        // update above together with this decision, so the contribution never ends up
                        // permanently PAID with no journal_entry and no way to correct it.
                        throw PostOneLineHalt(PostOutcome.BookingIncomplete)
                    }

                    BankStatementLineTable.update({ BankStatementLineTable.id eq candidate.lineId }) {
                        it[status] = BankStatementLineStatus.POSTED
                        it[BankStatementLineTable.paymentTransactionId] = paymentTransactionId
                    }
                    ContributionPaymentEvents.publishPaid(
                        contributionId = candidate.contributionId,
                        paidAt = now,
                        amount = candidate.amount,
                        transactionId = paymentTransactionId.toString(),
                    )
                    PostOutcome.Posted
                }
            } catch (e: PostOneLineHalt) {
                e.outcome
            } catch (e: ConflictException) {
                // See class KDoc "Phase 2" -- a single line's imbalance/guard failure must not stop
                // the rest of the file. Only the line id is logged, never any content. The `transaction
                // {}` block above already rolled back everything it had written (Exposed's normal
                // exception-triggered rollback, no PostOneLineHalt needed for this path).
                logger.warn {
                    "BankStatementImportService: booking failed for line ${candidate.lineId} (import $importId): ${e::class.simpleName}"
                }
                PostOutcome.BookingConflict
            }

        // Every non-Posted outcome's line-status correction runs in its OWN fresh transaction,
        // opened only now that the attempt transaction above has fully committed (Posted) or rolled
        // back (everything else) -- never inside the same transaction that might already be aborted
        // (PostgreSQL 25P02, see PostOneLineHalt/AlreadyBooked KDoc above).
        when (outcome) {
            PostOutcome.Posted -> Unit
            PostOutcome.AlreadyBooked ->
                transaction {
                    BankStatementLineTable.update({
                        (BankStatementLineTable.id eq candidate.lineId) and (bankStatementLineIsStillSuggested)
                    }) {
                        it[resolutionNote] = "Bereits gebucht"
                    }
                }
            // No write here, deliberately -- see PostOutcome.LineNoLongerPending KDoc: the concurrent
            // path that already resolved this line owns its status/resolutionNote, overwriting either
            // here would clobber e.g. a manual "ignore" reason with a misleading value.
            PostOutcome.LineNoLongerPending -> Unit
            PostOutcome.ContributionAlreadySettled ->
                transaction {
                    // Security finding fix (Review MAJOR, residual gap): this correction transaction
                    // opens AFTER the main attempt transaction above has already rolled back (see
                    // PostOneLineHalt KDoc), which releases the `forUpdate()` lock that transaction
                    // held -- a concurrent BankStatementStore.assignLineToContribution/
                    // assignLineToDonation call can slip in during that gap, see the line still
                    // SUGGESTED, and complete a full manual booking (its own payment_transaction,
                    // status -> POSTED) before this correction runs. The `bankStatementLineIsStillSuggested`
                    // guard below makes this UPDATE a no-op (0 rows) in that case instead of blindly
                    // overwriting the just-committed POSTED status back to AMBIGUOUS/UNMATCHED -- which
                    // would have made the line reassignable again (requireAssignableLine only refuses
                    // exactly POSTED) and let a THIRD path double-book the same bank transaction.
                    BankStatementLineTable.update({
                        (BankStatementLineTable.id eq candidate.lineId) and (bankStatementLineIsStillSuggested)
                    }) {
                        it[status] = BankStatementLineStatus.AMBIGUOUS
                        it[resolutionNote] = "Beitrag war bereits ausgeglichen oder ist aktuell in einem laufenden SEPA-Lastschrifteinzug"
                    }
                }
            PostOutcome.BookingIncomplete ->
                transaction {
                    // See PostOutcome.ContributionAlreadySettled above for why this guard is needed.
                    BankStatementLineTable.update({
                        (BankStatementLineTable.id eq candidate.lineId) and (bankStatementLineIsStillSuggested)
                    }) {
                        it[status] = BankStatementLineStatus.UNMATCHED
                        it[matchedContributionId] = null
                        it[resolutionNote] = "Kontozuordnung unvollstaendig -- Buchung nicht moeglich"
                    }
                }
            PostOutcome.BookingConflict ->
                transaction {
                    // See PostOutcome.ContributionAlreadySettled above for why this guard is needed.
                    BankStatementLineTable.update({
                        (BankStatementLineTable.id eq candidate.lineId) and (bankStatementLineIsStillSuggested)
                    }) {
                        it[status] = BankStatementLineStatus.AMBIGUOUS
                        it[resolutionNote] = "Buchung fehlgeschlagen -- bitte manuell pruefen"
                    }
                }
        }
        return outcome
    }

    private fun throwAsRejection(e: Throwable): Nothing =
        when (e) {
            is BankStatementParseException ->
                throw BankStatementRejectedException(
                    httpStatus = 422,
                    message = e.message ?: "Zeile nicht lesbar",
                    code = e.code,
                    lineNumber = e.lineNumber,
                    rawLineExcerpt = e.rawLineExcerpt,
                )
            else -> throw e
        }

    companion object {
        const val MAX_UPLOAD_BYTES: Long = 5L * 1024 * 1024
        const val MAX_STATEMENT_LINES: Int = 2_000
        private const val MAX_PURPOSE_LENGTH = 2000

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
        }

        /** `"DE...4711"` -- country code + ellipsis + last 4 characters, never the full IBAN. `null` for `null` input. */
        fun maskIban(iban: String?): String? {
            val normalized = iban?.let { IbanValidator.normalize(it) } ?: return null
            if (normalized.length <= 6) return normalized
            return normalized.take(2) + "..." + normalized.takeLast(4)
        }
    }
}

/**
 * Thrown by [BankStatementImportService.import] for every "reject the whole import" condition --
 * [network.lapis.cloud.server.routes.BankStatementRoutes] maps [httpStatus]/[code]/[lineNumber]/
 * [rawLineExcerpt]/[observedHeaderFields] onto the HTTP response body
 * ([network.lapis.cloud.shared.domain.BankStatementImportRejectionDto]). See
 * [BankStatementParseException] KDoc "Privacy" for why [rawLineExcerpt] only ever appears here,
 * never in a log line, never persisted.
 *
 * Welle V1.4.5.1.1 -- [code] deliberately has NO default: all seven throw sites in this file must
 * name their [BankStatementRejectionCode] explicitly, so the compiler (not a code reviewer) catches
 * a forgotten one.
 */
internal class BankStatementRejectedException(
    val httpStatus: Int,
    message: String,
    val code: BankStatementRejectionCode,
    val lineNumber: Int? = null,
    val rawLineExcerpt: String? = null,
    val observedHeaderFields: List<String>? = null,
) : Exception(message)
