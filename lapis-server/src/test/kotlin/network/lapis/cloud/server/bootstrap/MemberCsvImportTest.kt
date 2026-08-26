package network.lapis.cloud.server.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberStatus

/**
 * Pure-logic coverage for [MemberCsvImport]'s parser/mapping/planning functions -- no database, no
 * filesystem, entirely synthetic data (`Max Testperson`/`test@example.org`,
 * `Erika Musterfrau`/`muster@example.org`, never anything resembling the real PdV export). See
 * [MemberCsvImportDbTest] for the DB-integration coverage (idempotency, actual writes, the CHECK
 * constraint) and [network.lapis.cloud.server.db.MemberStatusDonorDeceasedMigrationTest] for the
 * migration itself.
 */
class MemberCsvImportTest :
    FunSpec({
        // ── DelimitedCsvParser ──────────────────────────────────────────────────────────────

        context("DelimitedCsvParser") {
            test("splits on the delimiter and strips a leading UTF-8 BOM") {
                val rows = DelimitedCsvParser.parse(text = "a;b;c\n1;2;3")
                rows shouldBe listOf(listOf("a", "b", "c"), listOf("1", "2", "3"))
            }

            test("a quoted field containing the delimiter stays one field") {
                val rows = DelimitedCsvParser.parse(text = "a;\"b;c\";d")
                rows shouldBe listOf(listOf("a", "b;c", "d"))
            }

            test("a doubled quote inside a quoted field escapes to one literal quote") {
                val rows = DelimitedCsvParser.parse(text = "a;\"say \"\"hi\"\"\";c")
                rows shouldBe listOf(listOf("a", "say \"hi\"", "c"))
            }

            test("an embedded newline inside a quoted field stays part of the field") {
                val rows = DelimitedCsvParser.parse(text = "a;\"line1\nline2\";c")
                rows shouldBe listOf(listOf("a", "line1\nline2", "c"))
            }

            test("CRLF and bare LF line endings produce the identical result") {
                val crlf = DelimitedCsvParser.parse(text = "a;b\r\nc;d")
                val lf = DelimitedCsvParser.parse(text = "a;b\nc;d")
                crlf shouldBe lf
                crlf shouldBe listOf(listOf("a", "b"), listOf("c", "d"))
            }

            test("a missing required header column throws, naming the column") {
                val exception =
                    runCatching { parseMemberCsv("Personennummer;Vorname\n1;Max") }.exceptionOrNull()
                (exception != null) shouldBe true
                (exception!!.message?.contains("Nachname") == true) shouldBe true
            }

            test("a genuinely blank line produces an empty row ([]), matching Python's csv.reader byte for byte") {
                // Verified against Python: list(csv.reader(io.StringIO('a;b\r\n1;2\r\n\r\n'), delimiter=';'))
                // -> [['a', 'b'], ['1', '2'], []]
                val rows = DelimitedCsvParser.parse(text = "a;b\r\n1;2\r\n\r\n")
                rows shouldBe listOf(listOf("a", "b"), listOf("1", "2"), emptyList())
            }

            test("a line containing only a single delimiter is NOT blank -- it is two empty fields, same as Python") {
                val rows = DelimitedCsvParser.parse(text = "a;b\r\n;\r\n")
                rows shouldBe listOf(listOf("a", "b"), listOf("", ""))
            }

            test("a blank line in the middle of the file does not corrupt the rows around it") {
                val rows = DelimitedCsvParser.parse(text = "a;b\n\n1;2")
                rows shouldBe listOf(listOf("a", "b"), emptyList(), listOf("1", "2"))
            }
        }

        context("parseMemberCsv") {
            val header =
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
                ).joinToString(";")

            test("a full header plus one data row maps every column to the correct MemberCsvRow field, not a shifted/swapped one") {
                val dataRow =
                    listOf(
                        "P-1",
                        "Max",
                        "Mustermann",
                        "von",
                        "",
                        "Teststrasse",
                        "1",
                        "38100",
                        "Braunschweig",
                        "DE",
                        "17.05.1980",
                        "DE",
                        "Mitglied",
                        "01.01.2020",
                        "max@example.org",
                    ).joinToString(";")

                val rows = parseMemberCsv("$header\n$dataRow")

                rows.size shouldBe 1
                val row = rows.single()
                row.recordNumber shouldBe 1
                row.personNumber shouldBe "P-1"
                row.firstName shouldBe "Max"
                row.lastName shouldBe "Mustermann"
                row.nameAffix shouldBe "von"
                row.company shouldBe ""
                row.street shouldBe "Teststrasse"
                row.houseNumber shouldBe "1"
                row.postalCode shouldBe "38100"
                row.city shouldBe "Braunschweig"
                row.country shouldBe "DE"
                row.dateOfBirth shouldBe "17.05.1980"
                row.nationality shouldBe "DE"
                row.sourceStatus shouldBe "Mitglied"
                row.joinedAt shouldBe "01.01.2020"
                row.email shouldBe "max@example.org"
            }

            test("a genuinely blank line in the data section is skipped outright, never surfaces as an UNKNOWN_STATUS row") {
                val dataRow =
                    listOf(
                        "P-1",
                        "Max",
                        "Mustermann",
                        "",
                        "",
                        "Teststrasse",
                        "1",
                        "38100",
                        "Braunschweig",
                        "DE",
                        "",
                        "DE",
                        "Mitglied",
                        "01.01.2020",
                        "max@example.org",
                    ).joinToString(";")

                val rows = parseMemberCsv("$header\n$dataRow\n\n")

                rows.size shouldBe 1
                rows.none { mapSourceStatus(it.sourceStatus) is SourceStatusMapping.Unknown } shouldBe true
            }

            test("the header accepts the umlaut spelling of Strasse/Staatsangehoerigkeit, not only the ASCII-transliterated one") {
                val umlautHeader = header.replace("Strasse", "Straße").replace("Staatsangehoerigkeit", "Staatsangehörigkeit")
                val dataRow =
                    listOf(
                        "P-1",
                        "Max",
                        "Mustermann",
                        "",
                        "",
                        "Musterstraße",
                        "1",
                        "38100",
                        "Braunschweig",
                        "DE",
                        "",
                        "DE",
                        "Mitglied",
                        "01.01.2020",
                        "max@example.org",
                    ).joinToString(";")

                val rows = parseMemberCsv("$umlautHeader\n$dataRow")

                rows.single().street shouldBe "Musterstraße"
                rows.single().nationality shouldBe "DE"
            }
        }

        // ── parseGermanDate ─────────────────────────────────────────────────────────────────

        context("parseGermanDate") {
            test("parses a valid dd.MM.yyyy date") {
                parseGermanDate("31.12.1999") shouldBe LocalDate(1999, 12, 31)
            }

            test("blank input (empty or whitespace-only) is null, not an error") {
                parseGermanDate("") shouldBe null
                parseGermanDate("   ") shouldBe null
            }

            test("ISO format is rejected") {
                runCatching { parseGermanDate("1999-12-31") }.isFailure shouldBe true
            }

            test("a calendar-impossible date is rejected") {
                runCatching { parseGermanDate("32.01.2020") }.isFailure shouldBe true
            }
        }

        // ── mapSourceStatus ─────────────────────────────────────────────────────────────────

        context("mapSourceStatus") {
            test("Mitglied and Neumitglied map to ACTIVE") {
                mapSourceStatus("Mitglied") shouldBe SourceStatusMapping.Importable(MemberStatus.ACTIVE)
                mapSourceStatus("Neumitglied") shouldBe SourceStatusMapping.Importable(MemberStatus.ACTIVE)
            }

            test("both the umlaut and ASCII-transliterated spelling of Gekündigt map to WITHDRAWN") {
                mapSourceStatus("Gekündigt") shouldBe SourceStatusMapping.Importable(MemberStatus.WITHDRAWN)
                mapSourceStatus("Gekuendigt") shouldBe SourceStatusMapping.Importable(MemberStatus.WITHDRAWN)
            }

            test("Spender, Förderer and Foerderer all map to DONOR") {
                mapSourceStatus("Spender") shouldBe SourceStatusMapping.Importable(MemberStatus.DONOR)
                mapSourceStatus("Förderer") shouldBe SourceStatusMapping.Importable(MemberStatus.DONOR)
                mapSourceStatus("Foerderer") shouldBe SourceStatusMapping.Importable(MemberStatus.DONOR)
            }

            test("verstorben (any casing) maps to DECEASED") {
                mapSourceStatus("verstorben") shouldBe SourceStatusMapping.Importable(MemberStatus.DECEASED)
                mapSourceStatus("Verstorben") shouldBe SourceStatusMapping.Importable(MemberStatus.DECEASED)
            }

            test("Ablehnung, Ausgeschlossen and Storniert are Excluded") {
                mapSourceStatus("Ablehnung") shouldBe SourceStatusMapping.Excluded
                mapSourceStatus("Ausgeschlossen") shouldBe SourceStatusMapping.Excluded
                mapSourceStatus("Storniert") shouldBe SourceStatusMapping.Excluded
            }

            test("an unrecognized literal is Unknown, not silently dropped") {
                mapSourceStatus("Ehrenmitglied") shouldBe SourceStatusMapping.Unknown
            }
        }

        // ── buildImportPlan: the four skip reasons, individually ───────────────────────────

        context("buildImportPlan skip reasons") {
            test("STATUS_NOT_IMPORTABLE: a Storniert row is skipped, nothing prepared") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, sourceStatus = "Storniert")))
                plan.prepared.size shouldBe 0
                plan.skipped.size shouldBe 1
                plan.skipped.single().reason shouldBe MemberImportSkipReason.STATUS_NOT_IMPORTABLE
            }

            test("MISSING_EMAIL: a row with no EMail 1 is skipped") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, email = "")))
                plan.skipped.single().reason shouldBe MemberImportSkipReason.MISSING_EMAIL
            }

            test("MISSING_JOINED_AT: a row with no Eintrittsdatum is skipped") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, joinedAt = "")))
                plan.skipped.single().reason shouldBe MemberImportSkipReason.MISSING_JOINED_AT
            }

            test("DUPLICATE_EMAIL_IN_FILE: the FIRST occurrence (file order) wins, case-insensitively") {
                val first = row(recordNumber = 1, email = "A@Example.ORG")
                val second = row(recordNumber = 2, email = "a@example.org")

                val plan = buildImportPlan(listOf(first, second))

                plan.prepared.size shouldBe 1
                plan.prepared.single().recordNumber shouldBe 1
                plan.skipped.single().recordNumber shouldBe 2
                plan.skipped.single().reason shouldBe MemberImportSkipReason.DUPLICATE_EMAIL_IN_FILE
            }

            test("DUPLICATE_EMAIL_IN_FILE: reversing the row order flips which one wins -- proves file order, not set/alphabetical order") {
                val first = row(recordNumber = 2, email = "a@example.org")
                val second = row(recordNumber = 1, email = "A@Example.ORG")

                val plan = buildImportPlan(listOf(first, second))

                plan.prepared.single().recordNumber shouldBe 2
                plan.skipped.single().recordNumber shouldBe 1
            }

            // Security finding fix (feature/v1.2.11-member-csv-import, MINOR, latent trigger b):
            // CONTROL_CHARACTER_IN_FIELD -- a stray NUL byte anywhere in the row is caught here,
            // before it can ever reach `runImport`'s `MemberTable.insert` and crash with a
            // PII-bearing ExposedSQLException.
            test("CONTROL_CHARACTER_IN_FIELD: a NUL byte in any field is skipped, nothing prepared") {
                val nulByte = "\u0000"
                val plan = buildImportPlan(listOf(row(recordNumber = 1, company = "Musterfirma" + nulByte + "GmbH")))
                plan.prepared.size shouldBe 0
                plan.skipped.single().reason shouldBe MemberImportSkipReason.CONTROL_CHARACTER_IN_FIELD
            }

            test(
                "CONTROL_CHARACTER_IN_FIELD: Rule 0 runs before Rule 1 -- a Storniert row with a NUL byte is " +
                    "CONTROL_CHARACTER_IN_FIELD, not STATUS_NOT_IMPORTABLE",
            ) {
                val nulByte = "\u0000"
                val plan =
                    buildImportPlan(listOf(row(recordNumber = 1, sourceStatus = "Storniert", lastName = "Muster" + nulByte + "mann")))
                plan.skipped.single().reason shouldBe MemberImportSkipReason.CONTROL_CHARACTER_IN_FIELD
            }

            test("CONTROL_CHARACTER_IN_FIELD: TAB/CR/LF inside a field do NOT trigger the check -- only raw C0/DEL control characters do") {
                val plan =
                    buildImportPlan(
                        listOf(row(recordNumber = 1, company = "Musterfirma\tGmbH\r\n", firstName = "", lastName = "", nameAffix = "")),
                    )
                plan.prepared.size shouldBe 1
                plan.skipped.size shouldBe 0
            }
        }

        // ── buildImportPlan: rule ORDER (the actual business logic) ────────────────────────

        context("buildImportPlan rule order") {
            test(
                "rule 1 (status) is checked before rule 3 (email) -- a Storniert row with no email is skipped for STATUS_NOT_IMPORTABLE, not MISSING_EMAIL",
            ) {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, sourceStatus = "Storniert", email = "")))
                plan.skipped.size shouldBe 1
                plan.skipped.single().reason shouldBe MemberImportSkipReason.STATUS_NOT_IMPORTABLE
            }

            test(
                "rule 4 (dedup) is populated before rule 5 (joined-at) runs -- BOTH rows of a same-email pair are skipped even when the first fails on joined-at",
            ) {
                val first = row(recordNumber = 1, email = "dup@example.org", joinedAt = "")
                val second = row(recordNumber = 2, email = "dup@example.org")

                val plan = buildImportPlan(listOf(first, second))

                plan.prepared.size shouldBe 0
                plan.skipped.size shouldBe 2
                plan.skipped.single { it.recordNumber == 1 }.reason shouldBe MemberImportSkipReason.MISSING_JOINED_AT
                plan.skipped.single { it.recordNumber == 2 }.reason shouldBe MemberImportSkipReason.DUPLICATE_EMAIL_IN_FILE
            }
        }

        // ── field mapping ───────────────────────────────────────────────────────────────────

        context("field mapping") {
            test("display_name combines Vorname/Namenszusatz/Nachname with single spaces, no doubling when Namenszusatz is blank") {
                deriveDisplayName(row(firstName = "Max", nameAffix = "von", lastName = "Mustermann")) shouldBe "Max von Mustermann"
                deriveDisplayName(row(firstName = "Max", nameAffix = "", lastName = "Mustermann")) shouldBe "Max Mustermann"
            }

            test("display_name falls back to Firma when all three name parts are blank") {
                deriveDisplayName(row(firstName = "", nameAffix = "", lastName = "", company = "Musterfirma GmbH")) shouldBe
                    "Musterfirma GmbH"
            }

            test("DISPLAY_NAME_BLANK: name parts AND company all blank is skipped") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, firstName = "", nameAffix = "", lastName = "", company = "")))
                plan.skipped.single().reason shouldBe MemberImportSkipReason.DISPLAY_NAME_BLANK
            }

            test("street combines Strasse/Hausnummer with a single space; both blank yields null") {
                combineStreet(row(street = "Teststrasse", houseNumber = "1")) shouldBe "Teststrasse 1"
                combineStreet(row(street = "", houseNumber = "")) shouldBe ""
            }

            test("email is lowercased in the prepared row") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, email = "Mixed.Case@Example.ORG")))
                plan.prepared.single().email shouldBe "mixed.case@example.org"
            }

            test("blank optional fields (Land/Geburtstag/Staatsangehoerigkeit) map to null") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, country = "", dateOfBirth = "", nationality = "")))
                val prepared = plan.prepared.single()
                prepared.country shouldBe null
                prepared.dateOfBirth shouldBe null
                prepared.nationality shouldBe null
            }

            test("FIELD_TOO_LONG: an over-length display name is skipped, detail names the column") {
                val plan = buildImportPlan(listOf(row(recordNumber = 1, firstName = "X".repeat(201), nameAffix = "", lastName = "")))
                val skip = plan.skipped.single()
                skip.reason shouldBe MemberImportSkipReason.FIELD_TOO_LONG
                skip.detail shouldBe "display_name"
            }
        }

        // ── aggregation ─────────────────────────────────────────────────────────────────────

        test("a mixed fixture produces exact per-status and per-reason counts") {
            val rows =
                listOf(
                    row(recordNumber = 1, sourceStatus = "Mitglied", email = "active1@example.org"),
                    row(recordNumber = 2, sourceStatus = "Neumitglied", email = "active2@example.org"),
                    row(recordNumber = 3, sourceStatus = "Gekündigt", email = "withdrawn1@example.org"),
                    row(recordNumber = 4, sourceStatus = "Gekuendigt", email = "withdrawn2@example.org"),
                    row(recordNumber = 5, sourceStatus = "Spender", email = "donor1@example.org"),
                    row(recordNumber = 6, sourceStatus = "Förderer", email = "donor2@example.org"),
                    row(recordNumber = 7, sourceStatus = "verstorben", email = "deceased1@example.org"),
                    row(recordNumber = 8, sourceStatus = "Verstorben", email = "deceased2@example.org"),
                    row(recordNumber = 9, sourceStatus = "Storniert", email = "excluded@example.org"),
                    row(recordNumber = 10, sourceStatus = "Ehrenmitglied", email = "unknown@example.org"),
                    row(recordNumber = 11, sourceStatus = "Mitglied", email = ""),
                    row(recordNumber = 12, sourceStatus = "Mitglied", email = "no-joined-at@example.org", joinedAt = ""),
                )

            val plan = buildImportPlan(rows)

            plan.totalRecords shouldBe 12
            plan.prepared.size shouldBe 8
            plan.prepared.count { it.status == MemberStatus.ACTIVE } shouldBe 2
            plan.prepared.count { it.status == MemberStatus.WITHDRAWN } shouldBe 2
            plan.prepared.count { it.status == MemberStatus.DONOR } shouldBe 2
            plan.prepared.count { it.status == MemberStatus.DECEASED } shouldBe 2
            plan.skipped.size shouldBe 4
            plan.skipped.count { it.reason == MemberImportSkipReason.STATUS_NOT_IMPORTABLE } shouldBe 1
            plan.skipped.count { it.reason == MemberImportSkipReason.UNKNOWN_STATUS } shouldBe 1
            plan.skipped.count { it.reason == MemberImportSkipReason.MISSING_EMAIL } shouldBe 1
            plan.skipped.count { it.reason == MemberImportSkipReason.MISSING_JOINED_AT } shouldBe 1
        }
    })

/**
 * A single synthetic, importable-by-default row -- every test overrides only the field(s) it
 * actually cares about. Deliberately entirely synthetic values ("Max"/"Testperson"/
 * "test@example.org"), never anything resembling the real PdV export.
 */
private fun row(
    recordNumber: Int = 1,
    personNumber: String = "P-$recordNumber",
    firstName: String = "Max",
    lastName: String = "Testperson",
    nameAffix: String = "",
    company: String = "",
    street: String = "Teststrasse",
    houseNumber: String = "1",
    postalCode: String = "38100",
    city: String = "Braunschweig",
    country: String = "DE",
    dateOfBirth: String = "",
    nationality: String = "",
    sourceStatus: String = "Mitglied",
    joinedAt: String = "01.01.2020",
    email: String = "test$recordNumber@example.org",
): MemberCsvRow =
    MemberCsvRow(
        recordNumber = recordNumber,
        personNumber = personNumber,
        firstName = firstName,
        lastName = lastName,
        nameAffix = nameAffix,
        company = company,
        street = street,
        houseNumber = houseNumber,
        postalCode = postalCode,
        city = city,
        country = country,
        dateOfBirth = dateOfBirth,
        nationality = nationality,
        sourceStatus = sourceStatus,
        joinedAt = joinedAt,
        email = email,
    )
