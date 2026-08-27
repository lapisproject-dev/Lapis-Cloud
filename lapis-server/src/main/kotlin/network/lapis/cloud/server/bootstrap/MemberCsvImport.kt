package network.lapis.cloud.server.bootstrap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.MEMBER_DISPLAY_NAME_MAX_LENGTH
import network.lapis.cloud.server.rpc.MEMBER_EMAIL_MAX_LENGTH
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlin.io.path.exists
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

// =====================================================================================================
// Overview
// =====================================================================================================

/**
 * One-time, operator-run CLI that bulk-imports the PdV's legacy CRM member export into
 * [MemberTable] (V1.2.11 "Einmaliger CSV-Mitglieder-Import"). Follows the exact same shape as
 * [AdminBootstrap]/[FlywayRepair] -- a plain `object` of pure/DB functions plus a freestanding
 * `main()`, ALL inputs from `System.getenv`, NEVER from Gradle properties (keeps values out of
 * shell history / `ps` output, same reasoning those two classes' own KDocs give). **Deliberately
 * NOT a network-reachable endpoint** -- the same trust boundary as running a one-off `psql`
 * command directly against the production database, not a new attack surface.
 *
 * **Nur PdV.** This tool is scoped to the PdV production instance specifically -- the ELB instance
 * has no comparable legacy membership list and is never imported (see the CHANGELOG entry for this
 * wave). The `member.status` CHECK widening and the new `external_reference` column this wave's
 * migration adds still apply to BOTH instances, because they travel through the shared
 * `V1__baseline.sql`/`V10__member_donor_deceased_and_external_reference.sql` schema.
 *
 * **Trockenlauf by default.** Without `LAPIS_MEMBER_IMPORT_COMMIT=true`, [runImport] parses,
 * filters, writes the PII report, logs the aggregates -- and rolls back before returning, writing
 * NOTHING to the database. This is the load-bearing safeguard of this wave: 581 real personal data
 * records against a production database, a single run, no undo (same "several independent
 * safeguards before anything irreversible happens" culture [network.lapis.cloud.server.dunning
 * .DunningPoller] and [AdminBootstrap.bootstrapFirstAdmin] already establish for their own
 * one-shot/hard-to-undo operations).
 *
 * **Operator run order is NOT negotiable** (see `deploy/production/README.adoc` "Einmaliger
 * Mitglieder-CSV-Import (nur PdV)" for the full operator runbook):
 * 1. `flywayRepair` on pdv2 AND the ELB instance (this wave edits `V1__baseline.sql` in place again).
 * 2. Deploy the new server version (`docker compose up -d --build lapis-server`). [DatabaseConfig
 *    .connect] migrates internally on server start, which is what actually applies `V10`.
 * 3. Trockenlauf (no `LAPIS_MEMBER_IMPORT_COMMIT`) -- verify the aggregates match the expected
 *    counts exactly before proceeding.
 * 4. Echtlauf with `LAPIS_MEMBER_IMPORT_COMMIT=true` and a DIFFERENT report path.
 * 5. Retrieve the report, store it safely, then delete the CSV and both reports from the server --
 *    they are PII holdings with no retention concept of their own.
 *
 * Running this tool BEFORE step 2 (deploying the new server version) would let the CLI apply `V10`
 * and write `DONOR`/`DECEASED` rows while the OLD server process is still running against the same
 * database -- that process's [MemberStatus] enum does not know those literals, and Exposed's
 * `enumerationByName` throws on read. Deploy first.
 *
 * ## Environment variables
 * - `LAPIS_DB_URL` / `LAPIS_DB_USER` / `LAPIS_DB_PASSWORD` -- via [DatabaseConfig.connect], exactly
 *   like [AdminBootstrap].
 * - `LAPIS_MEMBER_IMPORT_CSV_PATH` -- path to the CRM export CSV. Required.
 * - `LAPIS_MEMBER_IMPORT_REPORT_PATH` -- path the PII report is written to. Required. Must NOT
 *   already exist -- the report of a prior run is evidence, never silently overwritten.
 * - `LAPIS_MEMBER_IMPORT_COMMIT` -- `true` to actually write; any other value (including unset) is
 *   a Trockenlauf.
 *
 * ## Privacy
 * The report file (see [writeReport]) is the only place any name/email/person-number ever gets
 * written to disk by this tool, created `0600` where the filesystem supports POSIX permissions.
 * Everything on stdout/the application log is aggregate counts ONLY -- no names, no emails, no
 * person numbers, not even in exception messages (an exception may only ever name a record number
 * and a column name, never a value).
 */
object MemberCsvImport

// =====================================================================================================
// CSV parsing -- pure, DB-free, no dependency on any CSV library (none exists in this project's
// dependency graph; not worth adding one for a single-use operator tool).
// =====================================================================================================

/**
 * A small, RFC-4180-near, character-by-character parser: [delimiter]-separated, doubled quote
 * characters escape a literal quote (`""` -> `"`), a field delimiter or newline INSIDE a quoted
 * field is data, not structure, CRLF and bare LF are equivalent line endings, and a leading UTF-8
 * BOM (U+FEFF) on the very first character of the input is discarded before parsing begins (so it
 * never corrupts the first header name). Must match Python's `csv.reader(delimiter=';')` byte for
 * byte -- the real 408-importable-row count this wave's plan documents was verified against that
 * exact behavior.
 */
internal object DelimitedCsvParser {
    fun parse(
        text: String,
        delimiter: Char = ';',
    ): List<List<String>> {
        val input = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var sawAnyFieldOnCurrentLine = false

        fun endField() {
            row.add(field.toString())
            field.clear()
            sawAnyFieldOnCurrentLine = true
        }

        fun endRow() {
            // A genuinely blank line -- nothing consumed on it at all (no delimiter, no quote, no
            // character) -- must become an EMPTY row (`[]`), not a one-element row holding a single
            // empty string (`[""]`). Python's `csv.reader` makes the same distinction: `''` (blank
            // line) -> `[]`, but `';'` (one delimiter, still "empty" content-wise) -> `['', '']`.
            // Getting this wrong turns a stray blank line in the CRM export into a fabricated data
            // row whose every field is `""`, which `mapSourceStatus("")` then reports as
            // UNKNOWN_STATUS -- a misleading diagnostic that sends the operator to the status-mapping
            // table instead of to the actual blank line.
            if (row.isEmpty() && field.isEmpty() && !sawAnyFieldOnCurrentLine) {
                rows.add(row)
                row = mutableListOf()
                return
            }
            endField()
            rows.add(row)
            row = mutableListOf()
            sawAnyFieldOnCurrentLine = false
        }

        while (i < input.length) {
            val c = input[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                }
                field.append(c)
                i++
                continue
            }
            when (c) {
                '"' -> {
                    inQuotes = true
                    i++
                }

                delimiter -> {
                    endField()
                    i++
                }

                '\r' -> {
                    endRow()
                    i++
                    if (i < input.length && input[i] == '\n') i++
                }

                '\n' -> {
                    endRow()
                    i++
                }

                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        // Final field/row, if the input didn't end on a line break.
        if (field.isNotEmpty() || sawAnyFieldOnCurrentLine || row.isNotEmpty()) {
            endRow()
        }
        return rows
    }
}

// Deliberately includes both the ASCII-transliterated and the umlaut spelling for the two required
// column NAMES that have one -- the same umlaut/ASCII tolerance [SOURCE_STATUS_MAPPING] already
// applies to status VALUES. The task's own literal column names are ASCII-transliterated
// ("Strasse", "Staatsangehoerigkeit"), but a real UTF-8 CRM export is at least as likely to spell a
// column header with the umlaut ("Straße", "Staatsangehörigkeit") as it is a status literal.
private val COLUMN_NAME_ALTERNATES: Map<String, List<String>> =
    mapOf(
        "Strasse" to listOf("Strasse", "Straße"),
        "Staatsangehoerigkeit" to listOf("Staatsangehoerigkeit", "Staatsangehörigkeit"),
    )

/** Header-name -> column-index lookup, built once from the parsed CSV's first row -- every field access below is by name, never by position. */
internal class CsvHeader(
    headerRow: List<String>,
) {
    private val indexByName: Map<String, Int> = headerRow.mapIndexed { index, name -> name.trim() to index }.toMap()

    fun require(name: String): Int = indexByName[name] ?: error("Required CSV column '$name' not found in header")

    /**
     * Like [require], but for a [name] listed in [COLUMN_NAME_ALTERNATES] also accepts any of its
     * umlaut/ASCII-transliterated alternate spellings -- whichever one is actually present in this
     * header wins. The error message still names the canonical [name], since that is what
     * [REQUIRED_COLUMNS] documents.
     */
    fun requireAny(name: String): Int {
        val candidates = COLUMN_NAME_ALTERNATES[name] ?: listOf(name)
        candidates.forEach { candidate -> indexByName[candidate]?.let { return it } }
        error("Required CSV column '$name' not found in header")
    }
}

/** [row] indexed by [header], defaulting a short row's missing trailing fields to `""` (a source row with trailing empty cells the CSV writer omitted rather than one that's genuinely malformed). Accepts umlaut/ASCII alternate header spellings via [CsvHeader.requireAny]. */
internal fun List<String>.field(
    header: CsvHeader,
    name: String,
): String {
    val index = header.requireAny(name)
    return getOrElse(index) { "" }
}

// =====================================================================================================
// Domain model -- pure data, no DB, no IO, no clock.
// =====================================================================================================

/** One CSV data row, reduced to the fields this import actually uses. Raw, untrimmed values. */
internal data class MemberCsvRow(
    val recordNumber: Int,
    val personNumber: String,
    val firstName: String,
    val lastName: String,
    val nameAffix: String,
    val company: String,
    val street: String,
    val houseNumber: String,
    val postalCode: String,
    val city: String,
    val country: String,
    val dateOfBirth: String,
    val nationality: String,
    val sourceStatus: String,
    val joinedAt: String,
    val email: String,
)

/**
 * Security finding fix (feature/v1.2.11-member-csv-import, MINOR, latent trigger b): `true` if any
 * field holds a raw C0 (`0x00`-`0x1F`) or DEL (`0x7F`) control character, EXCLUDING TAB (`0x09`),
 * LF (`0x0A`) and CR (`0x0D`) -- those three are already given structural meaning by
 * [DelimitedCsvParser] before a field ever reaches this row, so they are not "raw" in the sense this
 * check cares about. The concrete trigger this guards against is a stray NUL byte (`0x00`), which
 * PostgreSQL's `text`/`varchar` columns reject outright (SQLSTATE 22021) if it ever reaches
 * [runImport]'s `MemberTable.insert`.
 */
internal fun MemberCsvRow.containsControlCharacter(): Boolean =
    listOf(
        personNumber,
        firstName,
        lastName,
        nameAffix,
        company,
        street,
        houseNumber,
        postalCode,
        city,
        country,
        dateOfBirth,
        nationality,
        sourceStatus,
        joinedAt,
        email,
    ).any { field -> field.any { c -> (c.code in 0x00..0x1F || c.code == 0x7F) && c != '\t' && c != '\n' && c != '\r' } }

internal enum class MemberImportSkipReason {
    /**
     * Security finding fix (feature/v1.2.11-member-csv-import, MINOR, latent trigger b): Rule 0,
     * checked before everything else -- a raw C0/DEL control character in any field (most plausibly
     * a stray NUL byte from the source CRM export) is rejected here as a clean skip instead of
     * reaching [runImport]'s `MemberTable.insert`, where PostgreSQL's `text`/`varchar` columns
     * reject a NUL byte outright (SQLSTATE 22021) -- which, before [MemberImportWriteException]
     * existed, meant a PII-bearing `ExposedSQLException` uncaught all the way to [main]. TAB/CR/LF
     * are excluded from this check -- [DelimitedCsvParser] already gives those structural meaning
     * (TAB is ordinary field content since the delimiter is `;`, CR/LF are row terminators handled
     * before a field value is ever assembled).
     */
    CONTROL_CHARACTER_IN_FIELD,

    /** Rule 1: the source status is Ablehnung/Ausgeschlossen/Storniert. */
    STATUS_NOT_IMPORTABLE,

    /** Defensive: the source status literal is not in [SOURCE_STATUS_MAPPING] or [SOURCE_STATUS_EXCLUDED] at all. */
    UNKNOWN_STATUS,

    /** Rule 3: `EMail 1` is blank after trimming. */
    MISSING_EMAIL,

    /** Rule 4: this normalized email already appeared earlier in the same file. */
    DUPLICATE_EMAIL_IN_FILE,

    /** Rule 5: `Eintrittsdatum` is blank. */
    MISSING_JOINED_AT,

    /** Defensive: `Eintrittsdatum` is set but not a valid `dd.MM.yyyy` date. */
    UNPARSEABLE_JOINED_AT,

    /** Defensive: `Geburtstag` is set but not a valid `dd.MM.yyyy` date. */
    UNPARSEABLE_DATE_OF_BIRTH,

    /** Defensive: first/last/affix name AND company are all blank -- nothing to derive a display name from. */
    DISPLAY_NAME_BLANK,

    /** Defensive: a mapped field exceeds its target column's width. [SkippedMember.detail] names the column. */
    FIELD_TOO_LONG,

    /** Idempotency: a member with this `external_reference` already exists in the database. */
    ALREADY_PRESENT_EXTERNAL_REF,

    /** Idempotency / collision with an existing member: this email already exists in the database. */
    ALREADY_PRESENT_EMAIL,
}

/** An import-ready row -- every field already in its DB-bound, validated shape. */
internal data class PreparedMember(
    val recordNumber: Int,
    val externalReference: String?,
    val displayName: String,
    val email: String,
    val status: MemberStatus,
    /** The raw, un-mapped CSV `Status` literal this row's [status] was derived from -- kept alongside the mapped [MemberStatus] so a DB-skip report row (see [runImport]) can show the same "Status (CSV)" value a pre-DB skip row shows, instead of the mapped enum name. */
    val sourceStatus: String,
    val joinedAt: LocalDate,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val dateOfBirth: LocalDate?,
    val nationality: String?,
)

internal data class SkippedMember(
    val recordNumber: Int,
    val externalReference: String,
    val displayName: String,
    val sourceStatus: String,
    val reason: MemberImportSkipReason,
    val detail: String = "",
)

internal data class MemberCsvPlan(
    val totalRecords: Int,
    val prepared: List<PreparedMember>,
    val skipped: List<SkippedMember>,
)

/** Result of [mapSourceStatus] -- a sealed outcome instead of a nullable [MemberStatus], so "excluded on purpose" and "not recognized at all" (a defensive, stop-the-run signal) never collapse into the same `null`. */
internal sealed interface SourceStatusMapping {
    data class Importable(
        val status: MemberStatus,
    ) : SourceStatusMapping

    data object Excluded : SourceStatusMapping

    data object Unknown : SourceStatusMapping
}

// Deliberately includes BOTH the umlaut and the ASCII-transliterated spelling of every literal
// that has one -- the task's own literal names are ASCII-transliterated ("Gekuendigt",
// "Foerderer"), but the real UTF-8 export is expected to contain the umlaut form ("Gekündigt",
// "Förderer"). Comparison is always via trim().lowercase(), so case differences never matter.
private val SOURCE_STATUS_MAPPING: Map<String, MemberStatus> =
    mapOf(
        "mitglied" to MemberStatus.ACTIVE,
        "neumitglied" to MemberStatus.ACTIVE,
        "gekündigt" to MemberStatus.WITHDRAWN,
        "gekuendigt" to MemberStatus.WITHDRAWN,
        "spender" to MemberStatus.DONOR,
        "förderer" to MemberStatus.DONOR,
        "foerderer" to MemberStatus.DONOR,
        "verstorben" to MemberStatus.DECEASED,
    )
private val SOURCE_STATUS_EXCLUDED: Set<String> = setOf("ablehnung", "ausgeschlossen", "storniert")

/** Maps a raw source-CRM status literal (any casing/whitespace) to its target [MemberStatus], or reports it as [SourceStatusMapping.Excluded]/[SourceStatusMapping.Unknown]. Pure, no side effects. */
internal fun mapSourceStatus(raw: String): SourceStatusMapping {
    val normalized = raw.trim().lowercase()
    SOURCE_STATUS_MAPPING[normalized]?.let { return SourceStatusMapping.Importable(it) }
    if (normalized in SOURCE_STATUS_EXCLUDED) return SourceStatusMapping.Excluded
    return SourceStatusMapping.Unknown
}

private val GERMAN_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

/**
 * Parses a `dd.MM.yyyy` date. A blank (after trimming) input is a legitimate "not provided" and
 * returns `null`; anything non-blank that fails to parse (wrong format, an impossible calendar
 * date like 32.01.2020) THROWS -- callers turn that into the appropriate [MemberImportSkipReason],
 * never a silent `null`, so a malformed date is never confused with a genuinely absent one.
 */
internal fun parseGermanDate(raw: String): LocalDate? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val javaDate = java.time.LocalDate.parse(trimmed, GERMAN_DATE_FORMAT)
        javaDate.toKotlinLocalDate()
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("not a valid dd.MM.yyyy date: '$trimmed'", e)
    }
}

// Target column widths -- see V1__baseline.sql / 00-foundation.kuml.kts for the authoritative
// column definitions this table mirrors. A violation is a skip (FIELD_TOO_LONG), never a silent
// truncation. "display_name"/"email" reuse MemberService's own MEMBER_DISPLAY_NAME_MAX_LENGTH/
// MEMBER_EMAIL_MAX_LENGTH constants (Review Runde 3 dedup) instead of a second, independently literal
// 200/320 -- see those constants' own KDoc.
private val FIELD_MAX_LENGTHS: Map<String, Int> =
    mapOf(
        "display_name" to MEMBER_DISPLAY_NAME_MAX_LENGTH,
        "email" to MEMBER_EMAIL_MAX_LENGTH,
        "street" to 200,
        "postal_code" to 20,
        "city" to 200,
        "country" to 100,
        "nationality" to 100,
        "external_reference" to 50,
    )

/**
 * Applies the five filter/mapping rules to every row, IN THIS ORDER -- the order is what produces
 * the documented 581 -> 408 split (210 ACTIVE / 102 WITHDRAWN / 75 DONOR / 21 DECEASED), and must
 * never be reordered without re-deriving that split against a real dry run:
 *
 * 0. (Security finding fix, MINOR, latent trigger b) [MemberCsvRow.containsControlCharacter] skips
 *    [MemberImportSkipReason.CONTROL_CHARACTER_IN_FIELD] -- see that reason's own KDoc. Ahead of
 *    rule 1 rather than folded into the "Only then" defensive batch in step 6 below, because letting
 *    a NUL byte reach [runImport]'s `MemberTable.insert` is a DB-crash risk, not merely a data-shape
 *    nicety like the checks in step 6. Does not affect the documented split above: the real PdV
 *    export this split was derived from contains no such characters.
 * 1. [mapSourceStatus] -- `Excluded` skips [MemberImportSkipReason.STATUS_NOT_IMPORTABLE], `Unknown`
 *    skips [MemberImportSkipReason.UNKNOWN_STATUS].
 * 2. Adopt the mapped [MemberStatus].
 * 3. `EMail 1` blank (after trim) skips [MemberImportSkipReason.MISSING_EMAIL].
 * 4. `email.trim().lowercase()` already seen earlier in THIS FILE (strict input order, a
 *    [LinkedHashSet] -- never `groupBy`/`sortedBy`/`toSet`, which would silently stop being
 *    order-preserving) skips [MemberImportSkipReason.DUPLICATE_EMAIL_IN_FILE]. The FIRST occurrence
 *    in file order always wins and is the one prepared.
 * 5. `Eintrittsdatum` blank skips [MemberImportSkipReason.MISSING_JOINED_AT]; present but
 *    unparseable skips [MemberImportSkipReason.UNPARSEABLE_JOINED_AT].
 * 6. Only then the remaining defensive checks (field lengths, `dateOfBirth`, `displayName`).
 *
 * Note rules 4 and 5 interact: the dedup set is populated BEFORE the joined-at check runs, so a
 * row that would already fail rule 5 (missing joined-at) still occupies its email in the dedup set
 * for any LATER row with the same email -- both get skipped, for two different reasons.
 */
internal fun buildImportPlan(rows: List<MemberCsvRow>): MemberCsvPlan {
    val prepared = mutableListOf<PreparedMember>()
    val skipped = mutableListOf<SkippedMember>()
    val seenEmails = LinkedHashSet<String>()

    for (row in rows) {
        fun skip(
            reason: MemberImportSkipReason,
            detail: String = "",
        ) {
            skipped +=
                SkippedMember(
                    recordNumber = row.recordNumber,
                    externalReference = row.personNumber.trim(),
                    displayName = deriveDisplayName(row),
                    sourceStatus = row.sourceStatus,
                    reason = reason,
                    detail = detail,
                )
        }

        // Rule 0 -- see MemberImportSkipReason.CONTROL_CHARACTER_IN_FIELD KDoc.
        if (row.containsControlCharacter()) {
            skip(reason = MemberImportSkipReason.CONTROL_CHARACTER_IN_FIELD)
            continue
        }

        // Rule 1
        val statusMapping = mapSourceStatus(row.sourceStatus)
        val status =
            when (statusMapping) {
                is SourceStatusMapping.Excluded -> {
                    skip(reason = MemberImportSkipReason.STATUS_NOT_IMPORTABLE)
                    continue
                }

                is SourceStatusMapping.Unknown -> {
                    skip(reason = MemberImportSkipReason.UNKNOWN_STATUS, detail = row.sourceStatus)
                    continue
                }

                is SourceStatusMapping.Importable -> statusMapping.status
            }

        // Rule 3
        val normalizedEmail = row.email.trim().lowercase()
        if (normalizedEmail.isEmpty()) {
            skip(reason = MemberImportSkipReason.MISSING_EMAIL)
            continue
        }

        // Rule 4 -- the dedup set is populated for THIS row regardless of what happens below, so a
        // later duplicate is still caught even if this row itself gets skipped by a later rule.
        if (!seenEmails.add(normalizedEmail)) {
            skip(reason = MemberImportSkipReason.DUPLICATE_EMAIL_IN_FILE)
            continue
        }

        // Rule 5
        val joinedAtRaw = row.joinedAt.trim()
        if (joinedAtRaw.isEmpty()) {
            skip(reason = MemberImportSkipReason.MISSING_JOINED_AT)
            continue
        }
        val joinedAt =
            try {
                parseGermanDate(joinedAtRaw)
            } catch (e: IllegalArgumentException) {
                skip(reason = MemberImportSkipReason.UNPARSEABLE_JOINED_AT, detail = e.message.orEmpty())
                continue
            }
        checkNotNull(joinedAt) { "joinedAtRaw was non-blank but parsed to null" }

        // Defensive: date of birth
        val dateOfBirth =
            try {
                parseGermanDate(row.dateOfBirth)
            } catch (e: IllegalArgumentException) {
                skip(reason = MemberImportSkipReason.UNPARSEABLE_DATE_OF_BIRTH, detail = e.message.orEmpty())
                continue
            }

        // Defensive: display name
        val displayName = deriveDisplayName(row)
        if (displayName.isBlank()) {
            skip(reason = MemberImportSkipReason.DISPLAY_NAME_BLANK)
            continue
        }

        val street = combineStreet(row).ifEmpty { null }
        val postalCode = row.postalCode.trim().ifEmpty { null }
        val city = row.city.trim().ifEmpty { null }
        val country = row.country.trim().ifEmpty { null }
        val nationality = row.nationality.trim().ifEmpty { null }
        val externalReference = row.personNumber.trim().ifEmpty { null }

        val tooLong =
            listOfNotNull(
                "display_name".takeIf { displayName.length > FIELD_MAX_LENGTHS.getValue(it) },
                "email".takeIf { normalizedEmail.length > FIELD_MAX_LENGTHS.getValue(it) },
                "street".takeIf { street != null && street.length > FIELD_MAX_LENGTHS.getValue(it) },
                "postal_code".takeIf { postalCode != null && postalCode.length > FIELD_MAX_LENGTHS.getValue(it) },
                "city".takeIf { city != null && city.length > FIELD_MAX_LENGTHS.getValue(it) },
                "country".takeIf { country != null && country.length > FIELD_MAX_LENGTHS.getValue(it) },
                "nationality".takeIf { nationality != null && nationality.length > FIELD_MAX_LENGTHS.getValue(it) },
                "external_reference".takeIf { externalReference != null && externalReference.length > FIELD_MAX_LENGTHS.getValue(it) },
            ).firstOrNull()
        if (tooLong != null) {
            skip(reason = MemberImportSkipReason.FIELD_TOO_LONG, detail = tooLong)
            continue
        }

        prepared +=
            PreparedMember(
                recordNumber = row.recordNumber,
                externalReference = externalReference,
                displayName = displayName,
                email = normalizedEmail,
                status = status,
                sourceStatus = row.sourceStatus,
                joinedAt = joinedAt,
                street = street,
                postalCode = postalCode,
                city = city,
                country = country,
                dateOfBirth = dateOfBirth,
                nationality = nationality,
            )
    }

    return MemberCsvPlan(totalRecords = rows.size, prepared = prepared, skipped = skipped)
}

/** `Vorname Namenszusatz Nachname` (e.g. "Max von Mustermann"), skipping blank parts so there is never a doubled space; falls back to `Firma` if all three name parts are blank (a company-only CRM row), or `""` if that too is blank -- callers turn a blank result into [MemberImportSkipReason.DISPLAY_NAME_BLANK]. */
internal fun deriveDisplayName(row: MemberCsvRow): String {
    val fromNameParts =
        listOf(row.firstName, row.nameAffix, row.lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    return fromNameParts.ifBlank { row.company.trim() }
}

/** `Strasse Hausnummer`, trimmed, never a doubled space when one part is blank. */
internal fun combineStreet(row: MemberCsvRow): String =
    listOf(row.street.trim(), row.houseNumber.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")

// =====================================================================================================
// CSV -> MemberCsvRow (still pure -- no DB, no IO beyond what the caller already read).
// =====================================================================================================

private val REQUIRED_COLUMNS =
    listOf(
        "Personennummer",
        "Vorname",
        "Nachname",
        "Namenszusatz",
        "Firma",
        "Strasse",
        "Hausnummer",
        "Postleitzahl",
        "Ort",
        "Land",
        "Geburtstag",
        "Staatsangehoerigkeit",
        "Status",
        "Eintrittsdatum",
        "EMail 1",
    )

/**
 * Parses the raw delimited [text] into [MemberCsvRow]s. Throws immediately, naming the missing
 * column, if any [REQUIRED_COLUMNS] entry is absent from the header -- never a partial import. A
 * data row that [DelimitedCsvParser] reports as genuinely empty (`[]`, a stray blank line in the
 * source file, as opposed to a row of the right column count with every field blank) is skipped
 * outright -- it is not a record, so it must never surface as one, e.g. as a fabricated
 * all-blank-fields row that [mapSourceStatus] would misreport as `UNKNOWN_STATUS`.
 * [recordNumber][MemberCsvRow.recordNumber] still reflects the row's 1-based position among ALL
 * data rows (blank ones included), so it keeps lining up with the source file's line numbers.
 */
internal fun parseMemberCsv(text: String): List<MemberCsvRow> {
    val table = DelimitedCsvParser.parse(text = text)
    require(table.isNotEmpty()) { "CSV file is empty" }
    val header = CsvHeader(table.first())
    REQUIRED_COLUMNS.forEach { header.requireAny(it) }

    return table.drop(1).mapIndexedNotNull { index, row ->
        if (row.isEmpty()) return@mapIndexedNotNull null
        MemberCsvRow(
            recordNumber = index + 1,
            personNumber = row.field(header = header, name = "Personennummer"),
            firstName = row.field(header = header, name = "Vorname"),
            lastName = row.field(header = header, name = "Nachname"),
            nameAffix = row.field(header = header, name = "Namenszusatz"),
            company = row.field(header = header, name = "Firma"),
            street = row.field(header = header, name = "Strasse"),
            houseNumber = row.field(header = header, name = "Hausnummer"),
            postalCode = row.field(header = header, name = "Postleitzahl"),
            city = row.field(header = header, name = "Ort"),
            country = row.field(header = header, name = "Land"),
            dateOfBirth = row.field(header = header, name = "Geburtstag"),
            nationality = row.field(header = header, name = "Staatsangehoerigkeit"),
            sourceStatus = row.field(header = header, name = "Status"),
            joinedAt = row.field(header = header, name = "Eintrittsdatum"),
            email = row.field(header = header, name = "EMail 1"),
        )
    }
}

// =====================================================================================================
// DB write -- idempotent, single transaction, Trockenlauf-safe.
// =====================================================================================================

internal data class ImportOutcome(
    val plan: MemberCsvPlan,
    /** Rows skipped ONLY at DB-write time (idempotency collisions) -- disjoint from [MemberCsvPlan.skipped], which is entirely pre-DB. */
    val dbSkips: List<SkippedMember>,
    val insertedCount: Int,
    val committed: Boolean,
)

/**
 * Security finding fix (feature/v1.2.11-member-csv-import, MINOR): thrown by [runImport] in place
 * of whatever the underlying JDBC/Exposed exception was, so a DB-write failure can never leak PII
 * to stdout/the application log via `ExposedSQLException.toString()` -- verified (exposed-core
 * 1.3.1) to inline the failing statement's BOUND ARGUMENTS (name, email, date of birth, address,
 * external reference) directly into its own message via `causedByQueries()`/`expandArgs`.
 * Deliberately carries ONLY [recordNumber] and sets no `cause` -- the original exception is
 * intentionally dropped, not chained, so it can never resurface via a stack trace either -- and its
 * own [message] never repeats anything beyond that record number, so even an instance that somehow
 * reaches the JVM's default uncaught-exception handler (stderr, mirrored into Gradle's console)
 * exposes nothing beyond "record #N's DB write failed". See the class KDoc "Privacy" for the wider
 * guarantee this upholds. [main] is the sole intended catcher.
 */
internal class MemberImportWriteException(
    val recordNumber: Int,
) : RuntimeException(
        "DB write failed for record #$recordNumber -- see MemberCsvImport KDoc \"Privacy\": no " +
            "further detail is included here to avoid leaking PII via the underlying exception's message",
    )

/**
 * Writes [plan] to the database inside a SINGLE transaction. If [commit] is `false` (Trockenlauf),
 * every DB-side check below still runs -- including both idempotency lookups -- but the
 * transaction is rolled back at the end, so nothing is actually persisted; this is what makes the
 * dry run a genuine rehearsal instead of a purely in-memory estimate.
 *
 * **Concurrency**: locks [OrganizationSettingsTable]'s Flyway-seeded singleton row via
 * `SELECT ... FOR UPDATE` before doing anything else -- the exact idiom [AdminBootstrap
 * .bootstrapFirstAdmin] uses for the same reason (see that function's own "Concurrency" KDoc): it
 * serializes two concurrent invocations of this tool (an operator running the Gradle task twice)
 * against each other, so the second run always sees the first run's writes before making its own
 * idempotency decisions, instead of a classic check-then-act race that could double-import a row.
 *
 * **Idempotency, both checks unconditional** (never one as a fallback for the other): a row whose
 * `external_reference` already exists in the DB is skipped ([MemberImportSkipReason
 * .ALREADY_PRESENT_EXTERNAL_REF]); a row whose (already-lowercased) `email` already exists is
 * skipped ([MemberImportSkipReason.ALREADY_PRESENT_EMAIL]) -- this second check exists because the
 * operator's own admin account is realistically also a row in the CRM export, with the same email
 * but a different (or absent) person number; without it, that row would hit `member.email UNIQUE`
 * and abort the whole run instead of being skipped cleanly.
 */
internal fun runImport(
    plan: MemberCsvPlan,
    commit: Boolean,
    database: Database? = null,
): ImportOutcome =
    transaction(database) {
        // Serializes concurrent runImport calls against each other -- see function KDoc "Concurrency".
        OrganizationSettingsTable.selectAll().forUpdate().single()

        val existingExternalReferences =
            MemberTable
                .select(MemberTable.externalReference)
                .where { MemberTable.externalReference.isNotNull() }
                .mapNotNullTo(mutableSetOf()) { it[MemberTable.externalReference] }
        val existingEmails =
            MemberTable
                .select(MemberTable.email)
                .map { it[MemberTable.email].trim().lowercase() }
                .toMutableSet()

        val dbSkips = mutableListOf<SkippedMember>()
        var insertedCount = 0

        for (member in plan.prepared) {
            val skipReason =
                when {
                    member.externalReference != null && member.externalReference in existingExternalReferences ->
                        MemberImportSkipReason.ALREADY_PRESENT_EXTERNAL_REF

                    member.email in existingEmails -> MemberImportSkipReason.ALREADY_PRESENT_EMAIL
                    else -> null
                }
            if (skipReason != null) {
                dbSkips +=
                    SkippedMember(
                        recordNumber = member.recordNumber,
                        externalReference = member.externalReference.orEmpty(),
                        displayName = member.displayName,
                        sourceStatus = member.sourceStatus,
                        reason = skipReason,
                    )
                continue
            }

            try {
                MemberTable.insert {
                    it[id] = Uuid.random()
                    it[displayName] = member.displayName
                    it[email] = member.email
                    it[status] = member.status
                    it[joinedAt] = member.joinedAt
                    it[membershipTierId] = null
                    it[street] = member.street
                    it[postalCode] = member.postalCode
                    it[city] = member.city
                    it[country] = member.country
                    it[dateOfBirth] = member.dateOfBirth
                    it[nationality] = member.nationality
                    it[externalReference] = member.externalReference
                }
            } catch (e: Exception) {
                // Deliberately no `cause = e` -- see MemberImportWriteException KDoc: the underlying
                // exception (e.g. ExposedSQLException) may render this row's bound PII values into
                // its own message, and chaining it as a cause would let that resurface via a
                // stack trace even though the message text itself is never read here.
                throw MemberImportWriteException(recordNumber = member.recordNumber)
            }
            insertedCount++
            member.externalReference?.let { existingExternalReferences += it }
            existingEmails += member.email
        }

        if (!commit) rollback()

        ImportOutcome(plan = plan, dbSkips = dbSkips, insertedCount = insertedCount, committed = commit)
    }

// =====================================================================================================
// Report (PII) -- the ONE place any name/email/person-number is ever written to disk by this tool.
// =====================================================================================================

/**
 * Writes the full, per-record report (every record, imported AND skipped, not only the skipped
 * ones) to [path] as UTF-8-with-BOM, semicolon-separated (mirrors the source format, opens cleanly
 * in Excel), RFC-4180-quoted where a value contains the delimiter/quote/newline. Columns:
 * `Datensatz;Personennummer;Name;Status (CSV);Ergebnis;Grund;Detail;Lauf`, `Ergebnis` being
 * `IMPORTIERT`/`UEBERSPRUNGEN`, `Lauf` being [committed]'s `ECHTLAUF`/`TROCKENLAUF` on EVERY row --
 * the two reports the runbook produces (Trockenlauf, then Echtlauf, see the `deploy/production
 * /README.adoc` runbook) only differ by filename, so this column is what tells them apart once a
 * report has been copied/renamed/opened out of context, without relying on the filename alone.
 * `IMPORTIERT` is only ever emitted for a Trockenlauf report as "would be imported if this were an
 * Echtlauf" -- the DB write itself was rolled back (see [runImport] `commit = false`), never
 * actually persisted.
 *
 * A DB-write-time idempotency skip ([dbSkips], see [runImport] "Idempotency") is reported ONCE, as
 * `UEBERSPRUNGEN`, never additionally as `IMPORTIERT` -- see the exclusion below.
 *
 * **CSV/Formula-Injection guard** (security finding fix, MAJOR): `Name`/`Personennummer`/
 * `Status (CSV)` are untrusted source-CRM values opened directly in Excel by an operator (per
 * "opens cleanly in Excel" above) -- see [csvField]'s own KDoc for the OWASP CSV-Injection guard
 * every field written by this function passes through.
 *
 * [path] must NOT already exist -- throws [IllegalStateException] rather than overwriting, since
 * the report of a prior run is the sole audit trail of a one-time, non-repeatable operation.
 * Attempts `0600` POSIX permissions (readable/writable only by the owner) via
 * [PosixFilePermissions], falling back to whatever the default create-permissions are on a
 * non-POSIX filesystem (Windows) -- this tool is only ever expected to run on the Linux deployment
 * host, but must not crash outright if run elsewhere for a one-off local rehearsal.
 */
internal fun writeReport(
    path: Path,
    plan: MemberCsvPlan,
    dbSkips: List<SkippedMember>,
    committed: Boolean,
) {
    check(!path.exists()) { "Report already exists at $path -- refusing to overwrite a prior run's report" }

    val laufLabel = if (committed) "ECHTLAUF" else "TROCKENLAUF"

    // dbSkips is drawn FROM plan.prepared (see runImport: it iterates plan.prepared and moves a
    // subset of those very rows into dbSkips when a DB-side idempotency check fires) -- so every
    // dbSkips.recordNumber is BY CONSTRUCTION also a plan.prepared.recordNumber, never disjoint
    // from it. The line below excludes exactly those record numbers from the "IMPORTIERT" block so
    // a DB-skipped row is reported once, as UEBERSPRUNGEN (via the dbSkips loop below), instead of
    // being double-counted as both IMPORTIERT and UEBERSPRUNGEN.
    val dbSkipRecordNumbers = dbSkips.map { it.recordNumber }.toSet()
    val lines = mutableListOf<String>()
    lines += csvLine(listOf("Datensatz", "Personennummer", "Name", "Status (CSV)", "Ergebnis", "Grund", "Detail", "Lauf"))

    plan.prepared.forEach { member ->
        if (member.recordNumber in dbSkipRecordNumbers) return@forEach
        lines +=
            csvLine(
                listOf(
                    member.recordNumber.toString(),
                    member.externalReference.orEmpty(),
                    member.displayName,
                    member.status.name,
                    "IMPORTIERT",
                    "",
                    "",
                    laufLabel,
                ),
            )
    }
    plan.skipped.forEach { skip ->
        lines +=
            csvLine(
                listOf(
                    skip.recordNumber.toString(),
                    skip.externalReference,
                    skip.displayName,
                    skip.sourceStatus,
                    "UEBERSPRUNGEN",
                    skip.reason.name,
                    skip.detail,
                    laufLabel,
                ),
            )
    }
    dbSkips.forEach { skip ->
        lines +=
            csvLine(
                listOf(
                    skip.recordNumber.toString(),
                    skip.externalReference,
                    skip.displayName,
                    skip.sourceStatus,
                    "UEBERSPRUNGEN",
                    skip.reason.name,
                    skip.detail,
                    laufLabel,
                ),
            )
    }

    val content = "﻿" + lines.joinToString("\r\n") + "\r\n"
    val posixAttribute = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    try {
        Files.createFile(path, posixAttribute)
    } catch (e: UnsupportedOperationException) {
        // Non-POSIX filesystem (e.g. a one-off local rehearsal on Windows) -- fall back to the
        // platform default create-permissions rather than crashing outright.
        Files.createFile(path)
    }
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.WRITE)
}

/**
 * Pre-flight check that [path] can actually be created, run BEFORE [runImport] -- an Echtlauf run
 * commits the DB transaction irreversibly, and [writeReport] (the sole audit trail of that
 * irreversible write, see its own KDoc) only runs afterwards; if the report's target directory does
 * not exist, or the process lacks write permission there, [writeReport] would only discover that
 * AFTER the commit, with no way to reproduce the report from a second run (idempotency means a
 * second run sees every row as an idempotency skip, not as freshly importable). Creates and
 * immediately deletes a zero-byte probe file at [path]'s exact location -- same directory, same
 * POSIX-permission code path [writeReport] itself uses -- so the failure mode surfaces here,
 * before anything irreversible has happened, not after.
 */
internal fun validateReportPathWritable(path: Path) {
    check(!path.exists()) { "Report already exists at $path -- refusing to overwrite a prior run's report" }
    val posixAttribute = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    try {
        Files.createFile(path, posixAttribute)
    } catch (e: UnsupportedOperationException) {
        Files.createFile(path)
    }
    Files.delete(path)
}

private fun csvLine(fields: List<String>): String = fields.joinToString(";") { csvField(it) }

/**
 * The set of leading characters Excel/LibreOffice/Google Sheets treat as "this cell is a formula,
 * not text" on CSV import -- the classic OWASP CSV/Formula-Injection trigger set. `'-'`/`'+'` are
 * included alongside the more obvious `'='`/`'@'` because Excel accepts a bare arithmetic
 * expression (`-1+2`, `+cmd|'/c calc'!A1`) as a formula with no leading `=` at all. A leading TAB
 * (`0x09`) or CR (`0x0D`) is included too -- Excel's own CSV importer trims/normalizes leading
 * whitespace-like control characters before this same formula-sniffing check runs, so a value that
 * only *looks* safe because it starts with a tab is not.
 */
private val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t', '\r')

/**
 * Security finding fix (feature/v1.2.11-member-csv-import, MAJOR, OWASP CSV Injection): this report
 * is opened directly in Excel by an operator (see [writeReport] KDoc) and untrusted CRM values
 * (`Name`/`Personennummer`/`Status (CSV)`) flow into it verbatim. A value whose FIRST character is a
 * [FORMULA_TRIGGER_CHARS] member gets a leading apostrophe prefixed -- the standard "force this cell
 * to be text, never a formula" escape every spreadsheet application honors -- and is then ALWAYS
 * quoted (not only when it happens to also contain a delimiter/quote/newline): simply quoting a
 * formula value is NOT sufficient on its own, since Excel re-evaluates a quoted CSV cell's leading
 * `=`/`+`/`-`/`@` as a formula trigger on import regardless of the surrounding quotes.
 */
private fun csvField(value: String): String {
    val needsFormulaGuard = value.isNotEmpty() && value[0] in FORMULA_TRIGGER_CHARS
    val guardedValue = if (needsFormulaGuard) "'$value" else value
    return if (needsFormulaGuard || guardedValue.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + guardedValue.replace("\"", "\"\"") + "\""
    } else {
        guardedValue
    }
}

// =====================================================================================================
// Aggregate log output -- NEVER any PII, only counts.
// =====================================================================================================

private fun logAggregates(outcome: ImportOutcome) {
    val plan = outcome.plan
    logger.info { "Datensätze insgesamt: ${plan.totalRecords}" }
    logger.info { "Importierbar (vor DB-Idempotenz): ${plan.prepared.size}" }
    MemberStatus.entries.forEach { status ->
        val count = plan.prepared.count { it.status == status }
        if (count > 0) logger.info { "  Status $status: $count" }
    }
    logger.info { "Bei DB-Schreiben übersprungen (Idempotenz): ${outcome.dbSkips.size}" }
    if (outcome.committed) {
        logger.info { "Tatsächlich geschrieben: ${outcome.insertedCount}" }
    } else {
        logger.info { "Würde geschrieben (TROCKENLAUF, nichts tatsächlich geschrieben): ${outcome.insertedCount}" }
    }
    logger.info { "Vor-DB übersprungen: ${plan.skipped.size}" }
    (plan.skipped.map { it.reason } + outcome.dbSkips.map { it.reason })
        .groupingBy { it }
        .eachCount()
        .forEach { (reason, count) -> logger.info { "  $reason: $count" } }
    if (plan.skipped.any { it.reason == MemberImportSkipReason.UNKNOWN_STATUS }) {
        logger.error {
            "UNKNOWN_STATUS > 0 -- the status-mapping table does not cover every literal in this " +
                "file. Stop and fix the mapping before proceeding to a commit run."
        }
    }
}

// =====================================================================================================
// main()
// =====================================================================================================

fun main() {
    val csvPathString =
        System.getenv("LAPIS_MEMBER_IMPORT_CSV_PATH") ?: error("LAPIS_MEMBER_IMPORT_CSV_PATH must be set")
    val reportPathString =
        System.getenv("LAPIS_MEMBER_IMPORT_REPORT_PATH") ?: error("LAPIS_MEMBER_IMPORT_REPORT_PATH must be set")
    val commit = System.getenv("LAPIS_MEMBER_IMPORT_COMMIT")?.equals("true", ignoreCase = true) == true

    val csvPath = Path.of(csvPathString)
    if (!Files.isRegularFile(csvPath)) {
        logger.error { "LAPIS_MEMBER_IMPORT_CSV_PATH does not point to an existing regular file: $csvPath" }
        kotlin.system.exitProcess(1)
    }
    val fileSize = Files.size(csvPath)
    val maxFileSizeBytes = 16L * 1024 * 1024
    if (fileSize > maxFileSizeBytes) {
        logger.error { "CSV file is $fileSize bytes, exceeding the $maxFileSizeBytes byte limit -- refusing to process" }
        kotlin.system.exitProcess(1)
    }

    val text = Files.readString(csvPath, StandardCharsets.UTF_8)
    val rows =
        try {
            parseMemberCsv(text)
        } catch (e: IllegalArgumentException) {
            logger.error { "CSV parsing failed: ${e.message}" }
            kotlin.system.exitProcess(1)
        } catch (e: IllegalStateException) {
            logger.error { "CSV parsing failed: ${e.message}" }
            kotlin.system.exitProcess(1)
        }

    val maxRecords = 50_000
    if (rows.size > maxRecords) {
        logger.error { "CSV has ${rows.size} data records, exceeding the $maxRecords record limit -- refusing to process" }
        kotlin.system.exitProcess(1)
    }

    val plan = buildImportPlan(rows)
    if (plan.skipped.any { it.reason == MemberImportSkipReason.UNKNOWN_STATUS }) {
        logAggregates(ImportOutcome(plan = plan, dbSkips = emptyList(), insertedCount = 0, committed = false))
        logger.error { "Aborting: at least one UNKNOWN_STATUS row -- see above." }
        kotlin.system.exitProcess(1)
    }

    val reportPath = Path.of(reportPathString)
    try {
        // Fails fast, BEFORE runImport can commit anything irreversible, if the report's target
        // directory is missing/unwritable -- see validateReportPathWritable's KDoc.
        validateReportPathWritable(reportPath)
    } catch (e: Exception) {
        logger.error { "LAPIS_MEMBER_IMPORT_REPORT_PATH is not writable: ${e.message}" }
        kotlin.system.exitProcess(1)
    }

    DatabaseConfig.connect()
    // Security finding fix (feature/v1.2.11-member-csv-import, MINOR): this call used to be
    // unguarded, so a DB-write failure (e.g. a dropped SSH tunnel mid-import, see the runbook's
    // `jdbc:postgresql://localhost:<tunnelled-port>`, or a control character an upstream CRM export
    // let through) would propagate an ExposedSQLException all the way to the JVM's default
    // uncaught-exception handler -- printing the failing INSERT's bound arguments (name, email,
    // date of birth, address, external reference) to stderr/Gradle's console, in direct violation
    // of this class's own KDoc "Privacy" promise. [runImport] itself now wraps every insert failure
    // in [MemberImportWriteException] (record number only, no cause); the catch-all below is
    // defense in depth for anything else that might interrupt the transaction (e.g. the initial
    // `SELECT ... FOR UPDATE`, a connection drop) -- neither branch ever logs `e.message` or a
    // stack trace, only what is guaranteed PII-free.
    val outcome =
        try {
            runImport(plan = plan, commit = commit)
        } catch (e: MemberImportWriteException) {
            logger.error { "Import aborted: ${e.message}" }
            kotlin.system.exitProcess(1)
        } catch (e: Exception) {
            logger.error {
                "Import aborted: an unexpected ${e::class.simpleName} interrupted the DB write -- no further " +
                    "detail is logged here to avoid leaking PII (see MemberCsvImport KDoc \"Privacy\")."
            }
            kotlin.system.exitProcess(1)
        }
    try {
        writeReport(path = reportPath, plan = plan, dbSkips = outcome.dbSkips, committed = outcome.committed)
        logger.info { "Bericht geschrieben: $reportPath" }
    } finally {
        // Always logged, even if writeReport above throws (e.g. the target filesystem changed
        // state between the pre-flight probe and now) -- the aggregate counts are the only signal
        // an operator gets if the write itself fails after an Echtlauf has already committed.
        logAggregates(outcome)
    }
    if (!commit) {
        logger.warn { "TROCKENLAUF -- nichts geschrieben. Zum Schreiben LAPIS_MEMBER_IMPORT_COMMIT=true setzen." }
    } else {
        logger.info { "Echtlauf abgeschlossen: ${outcome.insertedCount} Mitglieder geschrieben." }
    }
}
