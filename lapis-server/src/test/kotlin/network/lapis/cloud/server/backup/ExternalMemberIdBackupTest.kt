package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V156 -- the two `member.external_member_id*` columns travel with the member row in the whole-organization backup (the schema checksum is computed
 * from the live schema, so a bundle made before V156 is refused by the same mechanism `OrganizationBackupCorruptBundleTest` pins for a tampered
 * checksum). Round trip: id and source survive export and restore; a member without an id stays without one.
 */
class ExternalMemberIdBackupTest :
    FunSpec({
        test("the external member id and its source round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("ext-id-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("ext-id-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("ext-id-source").toFile()
            val targetRoot = Files.createTempDirectory("ext-id-target").toFile()
            val adminId = Uuid.random()
            val plainId = Uuid.random()

            transaction(sourceDb) {
                listOf(adminId, plainId).forEach { memberId ->
                    MemberTable.insert {
                        it[id] = memberId
                        it[displayName] = "Backup $memberId"
                        it[email] = "ext-id-backup-$memberId@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                        if (memberId == adminId) {
                            it[externalMemberId] = "WB-4711"
                            it[externalMemberIdSource] = "Wabeo"
                        }
                    }
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it.legacyAccountRole(role = AccountRole.ADMIN)
                }
            }

            val bundle = File.createTempFile("ext-id-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val restored = MemberTable.selectAll().where { MemberTable.id eq adminId }.single()
                    restored[MemberTable.externalMemberId] shouldBe "WB-4711"
                    restored[MemberTable.externalMemberIdSource] shouldBe "Wabeo"
                    val plain = MemberTable.selectAll().where { MemberTable.id eq plainId }.single()
                    plain[MemberTable.externalMemberId] shouldBe null
                    plain[MemberTable.externalMemberIdSource] shouldBe null
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
