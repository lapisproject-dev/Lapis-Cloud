package network.lapis.cloud.server.bootstrap

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.backup.TestDatabaseFactory
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * H2-integration coverage for [runImport]/[writeReport] -- each test gets its OWN freshly
 * Flyway-migrated database ([TestDatabaseFactory], same pattern [AdminBootstrapTest] uses for
 * `bootstrapFirstAdmin`), so idempotency/emptiness assertions are deterministic instead of
 * depending on what other Spec classes happened to leave behind in the shared ambient database.
 * See [MemberCsvImportTest] for the pure parsing/planning coverage and
 * [network.lapis.cloud.server.db.MemberStatusDonorDeceasedMigrationTest] for the migration itself.
 */
class MemberCsvImportDbTest :
    FunSpec({
        fun memberCount(db: Database): Long = transaction(db) { MemberTable.selectAll().count() }

        test("commit=true writes ACTIVE/WITHDRAWN/DONOR/DECEASED rows -- proves the CHECK widening against the real migrated schema") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-statuses-${Uuid.random()}")
            val plan =
                planOf(
                    preparedMember(recordNumber = 1, externalReference = "P-1", email = "active@example.org", status = MemberStatus.ACTIVE),
                    preparedMember(
                        recordNumber = 2,
                        externalReference = "P-2",
                        email = "withdrawn@example.org",
                        status = MemberStatus.WITHDRAWN,
                    ),
                    preparedMember(recordNumber = 3, externalReference = "P-3", email = "donor@example.org", status = MemberStatus.DONOR),
                    preparedMember(
                        recordNumber = 4,
                        externalReference = "P-4",
                        email = "deceased@example.org",
                        status = MemberStatus.DECEASED,
                    ),
                )

            val outcome = runImport(plan = plan, commit = true, database = db)

            outcome.insertedCount shouldBe 4
            outcome.dbSkips.size shouldBe 0
            memberCount(db) shouldBe 4
            transaction(db) {
                MemberStatus.entries
                    .filter { it in setOf(MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED) }
                    .forEach { status ->
                        MemberTable.selectAll().where { MemberTable.status eq status }.count() shouldBe 1L
                    }
            }
        }

        test("all target fields are read back correctly") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-fields-${Uuid.random()}")
            val plan =
                planOf(
                    preparedMember(
                        recordNumber = 1,
                        externalReference = "P-100",
                        email = "readback@example.org",
                        status = MemberStatus.ACTIVE,
                        street = "Musterstrasse 1",
                        postalCode = "38100",
                        city = "Braunschweig",
                        country = "DE",
                        dateOfBirth = LocalDate(1980, 5, 17),
                        nationality = "DE",
                    ),
                )

            runImport(plan = plan, commit = true, database = db)

            transaction(db) {
                val row = MemberTable.selectAll().where { MemberTable.externalReference eq "P-100" }.single()
                row[MemberTable.externalReference] shouldBe "P-100"
                row[MemberTable.joinedAt] shouldBe LocalDate(2020, 1, 1)
                row[MemberTable.street] shouldBe "Musterstrasse 1"
                row[MemberTable.dateOfBirth] shouldBe LocalDate(1980, 5, 17)
                row[MemberTable.nationality] shouldBe "DE"
            }
        }

        test("membership_tier_id, reviewed_by, friend_since, email_verified_at and anonymized_at are all NULL for an imported row") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-nulls-${Uuid.random()}")
            val plan = planOf(preparedMember(recordNumber = 1, externalReference = "P-200", email = "nulls@example.org"))

            runImport(plan = plan, commit = true, database = db)

            transaction(db) {
                val row = MemberTable.selectAll().where { MemberTable.externalReference eq "P-200" }.single()
                row[MemberTable.membershipTierId] shouldBe null
                row[MemberTable.reviewedBy] shouldBe null
                row[MemberTable.friendSince] shouldBe null
                row[MemberTable.emailVerifiedAt] shouldBe null
                row[MemberTable.anonymizedAt] shouldBe null
            }
        }

        test("no account row is ever created for an imported member") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-no-account-${Uuid.random()}")
            val plan = planOf(preparedMember(recordNumber = 1, externalReference = "P-300", email = "no-account@example.org"))

            runImport(plan = plan, commit = true, database = db)

            transaction(db) {
                val memberId = MemberTable.selectAll().where { MemberTable.externalReference eq "P-300" }.single()[MemberTable.id]
                AccountTable.selectAll().where { AccountTable.memberId eq memberId }.count() shouldBe 0L
            }
        }

        test("idempotency: running the same plan twice inserts nothing the second time, reports ALREADY_PRESENT_EXTERNAL_REF") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-idempotent-${Uuid.random()}")
            val plan =
                planOf(
                    preparedMember(recordNumber = 1, externalReference = "P-1", email = "a@example.org"),
                    preparedMember(recordNumber = 2, externalReference = "P-2", email = "b@example.org"),
                    preparedMember(recordNumber = 3, externalReference = "P-3", email = "c@example.org"),
                    preparedMember(recordNumber = 4, externalReference = "P-4", email = "d@example.org"),
                )

            val firstRun = runImport(plan = plan, commit = true, database = db)
            firstRun.insertedCount shouldBe 4
            memberCount(db) shouldBe 4

            val secondRun = runImport(plan = plan, commit = true, database = db)

            secondRun.insertedCount shouldBe 0
            secondRun.dbSkips.size shouldBe 4
            secondRun.dbSkips.count { it.reason == MemberImportSkipReason.ALREADY_PRESENT_EXTERNAL_REF } shouldBe 4
            memberCount(db) shouldBe 4
        }

        test("an email collision with an existing (externalReference-less) member is skipped, never a UNIQUE violation") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-email-collision-${Uuid.random()}")
            transaction(db) {
                MemberTable.insert {
                    it[id] = Uuid.random()
                    it[displayName] = "Bestandsmitglied"
                    it[email] = "bestand@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2019, 1, 1)
                    it[membershipTierId] = null
                    it[externalReference] = null
                }
            }
            val plan = planOf(preparedMember(recordNumber = 1, externalReference = "P-999", email = "bestand@example.org"))

            val outcome = runImport(plan = plan, commit = true, database = db)

            outcome.insertedCount shouldBe 0
            outcome.dbSkips.single().reason shouldBe MemberImportSkipReason.ALREADY_PRESENT_EMAIL
            memberCount(db) shouldBe 1
        }

        test(
            "Security finding fix (feature/v1.2.11-member-csv-import, MINOR): a DB-write failure during " +
                "runImport throws MemberImportWriteException carrying ONLY the record number -- never the " +
                "underlying exception's own message, which (see that class's KDoc) may inline bound PII",
        ) {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-write-exception-${Uuid.random()}")
            // external_reference is VARCHAR(50); this test constructs the PreparedMember directly
            // (bypassing buildImportPlan's own FIELD_TOO_LONG pre-check, which would normally catch
            // this before runImport ever sees it) specifically to force a genuine DB-level failure
            // and exercise runImport's OWN defense -- H2 in MODE=PostgreSQL (TestDatabaseFactory)
            // enforces VARCHAR length the same way the real Postgres deployment does.
            val plan =
                planOf(
                    preparedMember(recordNumber = 7, externalReference = "X".repeat(51), email = "overflow@example.org"),
                )

            val exception = shouldThrow<MemberImportWriteException> { runImport(plan = plan, commit = true, database = db) }

            exception.recordNumber shouldBe 7
            (exception.message ?: "") shouldContain "record #7"
            // The entire point of this exception type: it must never repeat the underlying
            // JDBC/Exposed exception's own message text, since that text may bind PII values
            // (see MemberImportWriteException KDoc).
            (exception.message ?: "").contains("overflow@example.org") shouldBe false
            (exception.cause == null) shouldBe true
            memberCount(db) shouldBe 0
        }

        test("commit=false (Trockenlauf) writes nothing, but the outcome still reports the rows as importable") {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-dry-run-${Uuid.random()}")
            val plan =
                planOf(
                    preparedMember(recordNumber = 1, externalReference = "P-1", email = "a@example.org"),
                    preparedMember(recordNumber = 2, externalReference = "P-2", email = "b@example.org"),
                    preparedMember(recordNumber = 3, externalReference = "P-3", email = "c@example.org"),
                    preparedMember(recordNumber = 4, externalReference = "P-4", email = "d@example.org"),
                )
            val countBefore = memberCount(db)

            val outcome = runImport(plan = plan, commit = false, database = db)

            outcome.insertedCount shouldBe 4
            outcome.committed shouldBe false
            memberCount(db) shouldBe countBefore
        }

        test(
            "financial safeguard: an imported ACTIVE member is never assigned a membership tier, so contribution generation cannot see it",
        ) {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-financial-safeguard-${Uuid.random()}")
            val tierId =
                transaction(db) {
                    val id = Uuid.random()
                    MembershipTierTable.insert {
                        it[MembershipTierTable.id] = id
                        it[name] = "Standardbeitrag"
                        it[description] = "Test-Tarif"
                        it[contributionAmount] = BigDecimal("10.00")
                        it[billingInterval] = BillingInterval.MONTHLY
                        it[active] = true
                        it[paymentTermDays] = 14
                    }
                    id
                }
            val plan =
                planOf(
                    preparedMember(recordNumber = 1, externalReference = "P-1", email = "active@example.org", status = MemberStatus.ACTIVE),
                )

            runImport(plan = plan, commit = true, database = db)

            transaction(db) {
                val matches =
                    MemberTable
                        .selectAll()
                        .where { (MemberTable.membershipTierId eq tierId) and (MemberTable.status eq MemberStatus.ACTIVE) }
                        .count()
                matches shouldBe 0L
            }
        }

        test("writeReport writes a header plus one line per record, and refuses to overwrite an existing file") {
            val tempDir = Files.createTempDirectory("member-csv-import-report-test")
            val reportPath = tempDir.resolve("report.csv")
            val plan =
                MemberCsvPlan(
                    totalRecords = 2,
                    prepared = listOf(preparedMember(recordNumber = 1, externalReference = "P-1", email = "a@example.org")),
                    skipped =
                        listOf(
                            SkippedMember(
                                recordNumber = 2,
                                externalReference = "P-2",
                                displayName = "Erika Musterfrau",
                                sourceStatus = "Storniert",
                                reason = MemberImportSkipReason.STATUS_NOT_IMPORTABLE,
                            ),
                        ),
                )

            writeReport(path = reportPath, plan = plan, dbSkips = emptyList(), committed = true)

            val lines = Files.readAllLines(reportPath, Charsets.UTF_8)
            lines.size shouldBe 3
            (lines[0].contains("Datensatz")) shouldBe true
            (lines.drop(1).any { it.contains("STATUS_NOT_IMPORTABLE") }) shouldBe true

            val secondAttempt = runCatching { writeReport(path = reportPath, plan = plan, dbSkips = emptyList(), committed = true) }
            secondAttempt.isFailure shouldBe true
        }

        test(
            "Security finding fix (feature/v1.2.11-member-csv-import, MAJOR, OWASP CSV Injection): a " +
                "formula-triggering leading character in Personennummer/Name/Status (CSV) is apostrophe-escaped " +
                "AND quoted in the report, never left to reach Excel as a live formula",
        ) {
            val tempDir = Files.createTempDirectory("member-csv-import-report-formula-test")
            val reportPath = tempDir.resolve("report.csv")
            val plan =
                MemberCsvPlan(
                    totalRecords = 2,
                    prepared =
                        listOf(
                            PreparedMember(
                                recordNumber = 1,
                                externalReference = "=cmd|' /c calc'!A1",
                                displayName = "+HYPERLINK(\"https://angreifer.example\")",
                                email = "formula1@example.org",
                                status = MemberStatus.ACTIVE,
                                sourceStatus = "Mitglied",
                                joinedAt = LocalDate(2020, 1, 1),
                                street = null,
                                postalCode = null,
                                city = null,
                                country = null,
                                dateOfBirth = null,
                                nationality = null,
                            ),
                        ),
                    skipped =
                        listOf(
                            SkippedMember(
                                recordNumber = 2,
                                externalReference = "-2+3",
                                displayName = "@SUM(1,2)",
                                sourceStatus = "=WEBSERVICE(\"https://angreifer.example\")",
                                reason = MemberImportSkipReason.MISSING_EMAIL,
                            ),
                        ),
                )

            writeReport(path = reportPath, plan = plan, dbSkips = emptyList(), committed = true)

            val lines = Files.readAllLines(reportPath, Charsets.UTF_8)
            val recordOneLine = lines.drop(1).single { it.startsWith("1;") }
            val recordTwoLine = lines.drop(1).single { it.startsWith("2;") }

            // Guarded AND quoted -- a bare quote around the value would NOT be enough, since Excel
            // re-evaluates a quoted CSV cell's leading =/+/-/@ as a formula on import regardless.
            recordOneLine.contains("\"'=cmd|' /c calc'!A1\"") shouldBe true
            recordOneLine.contains("\"'+HYPERLINK(") shouldBe true
            recordTwoLine.contains("\"'-2+3\"") shouldBe true
            recordTwoLine.contains("\"'@SUM(1,2)\"") shouldBe true
            recordTwoLine.contains("\"'=WEBSERVICE(") shouldBe true
        }

        test(
            "writeReport with a non-empty dbSkips does NOT throw, and the DB-skipped record is reported once as UEBERSPRUNGEN, never also as IMPORTIERT",
        ) {
            val tempDir = Files.createTempDirectory("member-csv-import-report-dbskips-test")
            val reportPath = tempDir.resolve("report.csv")
            val plan =
                MemberCsvPlan(
                    totalRecords = 2,
                    prepared =
                        listOf(
                            preparedMember(recordNumber = 1, externalReference = "P-1", email = "kept@example.org"),
                            preparedMember(recordNumber = 2, externalReference = "P-2", email = "dbskip@example.org"),
                        ),
                    skipped = emptyList(),
                )
            val dbSkips =
                listOf(
                    SkippedMember(
                        recordNumber = 2,
                        externalReference = "P-2",
                        displayName = "Test Mitglied 2",
                        sourceStatus = "Mitglied",
                        reason = MemberImportSkipReason.ALREADY_PRESENT_EXTERNAL_REF,
                    ),
                )

            // Must not throw -- dbSkips.recordNumber is BY CONSTRUCTION also in plan.prepared (see
            // runImport), this is the documented, expected shape, not an inconsistency.
            writeReport(path = reportPath, plan = plan, dbSkips = dbSkips, committed = true)

            val lines = Files.readAllLines(reportPath, Charsets.UTF_8)
            val recordTwoLines = lines.drop(1).filter { it.startsWith("2;") }
            recordTwoLines.size shouldBe 1
            recordTwoLines.single().contains("UEBERSPRUNGEN") shouldBe true
            recordTwoLines.single().contains("ALREADY_PRESENT_EXTERNAL_REF") shouldBe true
            recordTwoLines.single().contains("IMPORTIERT") shouldBe false

            val recordOneLine = lines.drop(1).single { it.startsWith("1;") }
            recordOneLine.contains("IMPORTIERT") shouldBe true
        }

        test(
            "end-to-end: runImport(commit=true) against a DB with a pre-existing email collision, then writeReport(outcome.dbSkips) -- the colliding record is UEBERSPRUNGEN, not IMPORTIERT, and the run does not crash",
        ) {
            val db = TestDatabaseFactory.freshMigratedH2Database("member-csv-import-e2e-dbskip-report-${Uuid.random()}")
            transaction(db) {
                MemberTable.insert {
                    it[id] = Uuid.random()
                    it[displayName] = "Bestandsmitglied"
                    it[email] = "bestand@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2019, 1, 1)
                    it[membershipTierId] = null
                    it[externalReference] = null
                }
            }
            val plan =
                planOf(
                    preparedMember(recordNumber = 1, externalReference = "P-1", email = "fresh@example.org"),
                    preparedMember(recordNumber = 2, externalReference = "P-999", email = "bestand@example.org"),
                )

            val outcome = runImport(plan = plan, commit = true, database = db)
            outcome.dbSkips.size shouldBe 1
            outcome.dbSkips.single().reason shouldBe MemberImportSkipReason.ALREADY_PRESENT_EMAIL

            val tempDir = Files.createTempDirectory("member-csv-import-e2e-report-test")
            val reportPath = tempDir.resolve("report.csv")

            // Must not throw -- this is exactly the shape the CRITICAL bug hit: dbSkips non-empty
            // after a real committed runImport.
            writeReport(path = reportPath, plan = plan, dbSkips = outcome.dbSkips, committed = outcome.committed)

            val lines = Files.readAllLines(reportPath, Charsets.UTF_8)
            val recordTwoLines = lines.drop(1).filter { it.startsWith("2;") }
            recordTwoLines.size shouldBe 1
            recordTwoLines.single().contains("UEBERSPRUNGEN") shouldBe true
            recordTwoLines.single().contains("ALREADY_PRESENT_EMAIL") shouldBe true
            recordTwoLines.single().contains("IMPORTIERT") shouldBe false
            lines.drop(1).single { it.startsWith("1;") }.contains("IMPORTIERT") shouldBe true
        }
    })

/** A single synthetic, DB-ready [PreparedMember] -- every test overrides only the fields it cares about. */
private fun preparedMember(
    recordNumber: Int,
    externalReference: String?,
    email: String,
    status: MemberStatus = MemberStatus.ACTIVE,
    sourceStatus: String = "Mitglied",
    joinedAt: LocalDate = LocalDate(2020, 1, 1),
    street: String? = null,
    postalCode: String? = null,
    city: String? = null,
    country: String? = null,
    dateOfBirth: LocalDate? = null,
    nationality: String? = null,
): PreparedMember =
    PreparedMember(
        recordNumber = recordNumber,
        externalReference = externalReference,
        displayName = "Test Mitglied $recordNumber",
        email = email,
        status = status,
        sourceStatus = sourceStatus,
        joinedAt = joinedAt,
        street = street,
        postalCode = postalCode,
        city = city,
        country = country,
        dateOfBirth = dateOfBirth,
        nationality = nationality,
    )

private fun planOf(vararg members: PreparedMember): MemberCsvPlan =
    MemberCsvPlan(totalRecords = members.size, prepared = members.toList(), skipped = emptyList())
