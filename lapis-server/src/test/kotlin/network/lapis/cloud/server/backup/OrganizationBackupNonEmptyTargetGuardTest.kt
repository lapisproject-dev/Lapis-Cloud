package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Dedicated coverage for [OrganizationRestoreService]'s pre-flight "is the target actually fresh"
 * guard -- the mechanism guarding against an accidental cross-org merge (see that class's KDoc
 * "Deliberately NOT attempted" section). [OrganizationBackupRoundTripTest] already exercises the
 * happy paths (fresh target succeeds; re-running into a populated target needs
 * `allowNonEmptyTarget=true`); this class isolates the guard itself.
 */
class OrganizationBackupNonEmptyTargetGuardTest :
    FunSpec({
        test(
            "restoring into a target that already holds a real (non-seed) row is rejected without allowNonEmptyTarget, target completely unchanged",
        ) {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("guard-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("guard-target-${Uuid.random()}")
            val sourceStorageRoot = Files.createTempDirectory("guard-source-storage").toFile()
            val targetStorageRoot = Files.createTempDirectory("guard-target-storage").toFile()

            val sourceAdminId = Uuid.random()
            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = sourceAdminId
                    it[displayName] = "Guard Source Admin"
                    it[email] = "guard-source-admin@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = sourceAdminId
                    it[role] = AccountRole.ADMIN
                }
            }

            // The target already has a genuinely different member -- simulating an attempted
            // cross-org merge (target is NOT a fresh, empty-beyond-seed instance).
            val targetExistingMemberId = Uuid.random()
            transaction(targetDb) {
                MemberTable.insert {
                    it[id] = targetExistingMemberId
                    it[displayName] = "Pre-Existing Target Member"
                    it[email] = "pre-existing-target-member@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 6, 1)
                    it[membershipTierId] = null
                }
            }

            val bundleFile = File.createTempFile("guard-bundle-", ".zip")
            try {
                bundleFile.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceStorageRoot)
                        .streamExport(actor = CurrentMember(memberId = sourceAdminId, role = AccountRole.ADMIN), sink = out)
                }

                try {
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorageRoot)
                        .restore(
                            actor = CurrentMember(memberId = sourceAdminId, role = AccountRole.ADMIN),
                            bundleFile = bundleFile,
                            allowNonEmptyTarget = false,
                        )
                    throw AssertionError("Expected NonEmptyTargetException")
                } catch (e: NonEmptyTargetException) {
                    (e.message?.contains("member") == true) shouldBe true
                }

                // Target completely unchanged -- still exactly the one pre-existing member, no
                // source rows merged in, since the guard runs before any table is touched.
                val targetMemberIds = transaction(targetDb) { MemberTable.selectAll().map { it[MemberTable.id] } }
                targetMemberIds shouldBe listOf(targetExistingMemberId)

                // The explicit escape hatch: allowNonEmptyTarget=true proceeds (accepting the
                // consequences -- this is a deliberate operator decision, not a default).
                val result =
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorageRoot)
                        .restore(
                            actor = CurrentMember(memberId = sourceAdminId, role = AccountRole.ADMIN),
                            bundleFile = bundleFile,
                            allowNonEmptyTarget = true,
                        )
                (result.tablesRestored.isNotEmpty()) shouldBe true

                val targetMemberIdsAfter = transaction(targetDb) { MemberTable.selectAll().map { it[MemberTable.id] }.toSet() }
                (targetExistingMemberId in targetMemberIdsAfter) shouldBe true
                (sourceAdminId in targetMemberIdsAfter) shouldBe true
            } finally {
                bundleFile.delete()
            }
        }

        test("a target holding only the Flyway-seeded singleton rows is treated as empty (no guard trip)") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("guard-emptyok-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("guard-emptyok-target-${Uuid.random()}")
            val sourceStorageRoot = Files.createTempDirectory("guard-emptyok-source-storage").toFile()
            val targetStorageRoot = Files.createTempDirectory("guard-emptyok-target-storage").toFile()

            val adminId = Uuid.random()
            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Guard EmptyOk Admin"
                    it[email] = "guard-emptyok-admin@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[role] = AccountRole.ADMIN
                }
            }

            val bundleFile = File.createTempFile("guard-emptyok-bundle-", ".zip")
            try {
                bundleFile.outputStream().use { out ->
                    OrganizationExportService(
                        database = sourceDb,
                        documentStorageRoot = sourceStorageRoot,
                    ).streamExport(actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN), sink = out)
                }
                // targetDb is freshly migrated -- only the two Flyway-seeded singleton rows exist,
                // no explicit allowNonEmptyTarget needed.
                val result =
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorageRoot)
                        .restore(
                            actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN),
                            bundleFile = bundleFile,
                            allowNonEmptyTarget = false,
                        )
                (result.tablesRestored.isNotEmpty()) shouldBe true
            } finally {
                bundleFile.delete()
            }
        }
    })
