package network.lapis.cloud.server.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.backup.TestDatabaseFactory
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val STRONG_PASSWORD = "a-perfectly-strong-bootstrap-password"

/** Exercises [AdminBootstrap] end to end against a real (H2) DB -- no member ever created via DevSeedData needed. */
class AdminBootstrapTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { cleanUpAdminBootstrapTestData(createdMemberIds) }

        fun createTestMember(
            email: String,
            passwordHash: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Bootstrap Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.ADMIN
                    it[AccountTable.passwordHash] = passwordHash
                }
            }
            createdMemberIds += id
            return id
        }

        fun storedPasswordHashOf(memberId: Uuid): String? =
            transaction {
                AccountTable.selectAll().where { AccountTable.memberId eq memberId }.single()[AccountTable.passwordHash]
            }

        test("setInitialAdminPassword: happy path sets the hash for an existing, password-less account") {
            val email = "bootstrap-happy@example.org"
            val memberId = createTestMember(email)

            val result = AdminBootstrap.setInitialAdminPassword(email, STRONG_PASSWORD)

            result shouldBe AdminBootstrap.BootstrapResult.Success(email = email, displayName = "Bootstrap Testmitglied")
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe true
        }

        test("setInitialAdminPassword: email lookup is case-insensitive, mirroring the login endpoint") {
            val email = "bootstrap-case@example.org"
            val memberId = createTestMember(email)

            val result = AdminBootstrap.setInitialAdminPassword("Bootstrap-Case@Example.ORG", STRONG_PASSWORD)

            result shouldBe AdminBootstrap.BootstrapResult.Success(email = email, displayName = "Bootstrap Testmitglied")
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe true
        }

        test("setInitialAdminPassword: unknown email is reported, never throws / never touches the DB") {
            val result = AdminBootstrap.setInitialAdminPassword("no-such-bootstrap-account@example.org", STRONG_PASSWORD)
            result shouldBe AdminBootstrap.BootstrapResult.AccountNotFound("no-such-bootstrap-account@example.org")
        }

        test("setInitialAdminPassword: refuses to overwrite an account that already has a password, without force") {
            val email = "bootstrap-already-set@example.org"
            val memberId = createTestMember(email, passwordHash = PasswordHasher.hash("pre-existing-password"))

            val result = AdminBootstrap.setInitialAdminPassword(email, STRONG_PASSWORD)

            result shouldBe AdminBootstrap.BootstrapResult.AlreadyHasPassword(email)
            PasswordHasher.verify("pre-existing-password", storedPasswordHashOf(memberId)) shouldBe true
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe false
        }

        test("setInitialAdminPassword: force=true deliberately overwrites an already-set password") {
            val email = "bootstrap-force@example.org"
            val memberId = createTestMember(email, passwordHash = PasswordHasher.hash("pre-existing-password"))

            val result = AdminBootstrap.setInitialAdminPassword(email, STRONG_PASSWORD, force = true)

            result shouldBe AdminBootstrap.BootstrapResult.Success(email = email, displayName = "Bootstrap Testmitglied")
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe true
        }

        test("setInitialAdminPassword: a weak password is rejected, account left untouched") {
            val email = "bootstrap-weak@example.org"
            val memberId = createTestMember(email)

            val result = AdminBootstrap.setInitialAdminPassword(email, "short")

            (result is AdminBootstrap.BootstrapResult.WeakPassword) shouldBe true
            storedPasswordHashOf(memberId) shouldBe null
        }

        // ── bootstrapFirstAdmin -- each test gets its OWN isolated H2 database (TestDatabaseFactory,
        // same pattern OrganizationBackupRoundTripTest already uses) rather than the ambient shared
        // test database every other test above uses. The "table is completely empty" precondition
        // this function checks would otherwise be nondeterministic -- it would depend on exactly
        // which other Spec classes happened to run first in this Gradle test JVM and whether they
        // fully cleaned up their own fixture rows, which is exactly the kind of hidden cross-Spec
        // coupling a test suite must not rely on. ──────────────────────────────────────────────────

        fun memberCount(db: Database): Long = transaction(db) { MemberTable.selectAll().count() }

        test("bootstrapFirstAdmin: happy path creates the first member+account row and grants ADMIN on a genuinely empty database") {
            val db = TestDatabaseFactory.freshMigratedH2Database("bootstrap-first-admin-happy-${Uuid.random()}")

            val result = AdminBootstrap.bootstrapFirstAdmin("Erika Musterfrau", "first-admin@example.org", STRONG_PASSWORD, db)

            result shouldBe
                AdminBootstrap.BootstrapFirstAdminResult.Success(email = "first-admin@example.org", displayName = "Erika Musterfrau")
            transaction(db) {
                val row = (MemberTable innerJoin AccountTable).selectAll().single()
                row[MemberTable.email] shouldBe "first-admin@example.org"
                row[MemberTable.status] shouldBe MemberStatus.AKTIV
                row[AccountTable.role] shouldBe AccountRole.ADMIN
                PasswordHasher.verify(STRONG_PASSWORD, row[AccountTable.passwordHash]) shouldBe true
            }
        }

        test("bootstrapFirstAdmin: email is normalized to lowercase, display name is trimmed") {
            val db = TestDatabaseFactory.freshMigratedH2Database("bootstrap-first-admin-normalize-${Uuid.random()}")

            val result = AdminBootstrap.bootstrapFirstAdmin("  Erika Musterfrau  ", "First-Admin@Example.ORG", STRONG_PASSWORD, db)

            result shouldBe
                AdminBootstrap.BootstrapFirstAdminResult.Success(email = "first-admin@example.org", displayName = "Erika Musterfrau")
        }

        test("bootstrapFirstAdmin: refuses when the member table already has at least one row, creates nothing") {
            val db = TestDatabaseFactory.freshMigratedH2Database("bootstrap-first-admin-not-empty-${Uuid.random()}")
            transaction(db) {
                MemberTable.insert {
                    it[id] = Uuid.random()
                    it[displayName] = "Bereits vorhandenes Mitglied"
                    it[email] = "already-here@example.org"
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }

            val result = AdminBootstrap.bootstrapFirstAdmin("Erika Musterfrau", "first-admin@example.org", STRONG_PASSWORD, db)

            result shouldBe AdminBootstrap.BootstrapFirstAdminResult.NotEmpty
            memberCount(db) shouldBe 1L
        }

        test("bootstrapFirstAdmin: a weak password is rejected before touching the database") {
            val db = TestDatabaseFactory.freshMigratedH2Database("bootstrap-first-admin-weak-${Uuid.random()}")

            val result = AdminBootstrap.bootstrapFirstAdmin("Erika Musterfrau", "first-admin@example.org", "short", db)

            (result is AdminBootstrap.BootstrapFirstAdminResult.WeakPassword) shouldBe true
            memberCount(db) shouldBe 0L
        }

        test("bootstrapFirstAdmin: a blank display name is rejected before touching the database") {
            val db = TestDatabaseFactory.freshMigratedH2Database("bootstrap-first-admin-blank-name-${Uuid.random()}")

            val result = AdminBootstrap.bootstrapFirstAdmin("   ", "first-admin@example.org", STRONG_PASSWORD, db)

            result shouldBe AdminBootstrap.BootstrapFirstAdminResult.InvalidInput("displayName must not be blank")
            memberCount(db) shouldBe 0L
        }

        test("bootstrapFirstAdmin: two concurrent invocations against the same empty database -- exactly one wins, never two ADMIN rows") {
            val db = TestDatabaseFactory.freshMigratedH2Database("bootstrap-first-admin-race-${Uuid.random()}")

            val results = runConcurrentBootstrapAttempts(db)

            results.count { it is AdminBootstrap.BootstrapFirstAdminResult.Success } shouldBe 1
            results.count { it is AdminBootstrap.BootstrapFirstAdminResult.NotEmpty } shouldBe 1
            memberCount(db) shouldBe 1L
        }
    })

/**
 * Fires two [AdminBootstrap.bootstrapFirstAdmin] calls against the SAME database from two
 * independent OS threads, synchronized via [CountDownLatch] so both are issued as close to
 * simultaneously as possible -- mirrors `RegistrationServiceTest`'s own
 * `runConcurrentApproveAndReject` helper shape. Proves the `OrganizationSettingsTable.forUpdate()`
 * lock in [AdminBootstrap.bootstrapFirstAdmin] genuinely serializes the two calls: without it, both
 * threads could observe an empty [MemberTable] before either commits and both would succeed.
 */
private fun runConcurrentBootstrapAttempts(
    db: Database,
    timeoutSeconds: Long = 20,
): List<AdminBootstrap.BootstrapFirstAdminResult> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<AdminBootstrap.BootstrapFirstAdminResult>()
    val failures = mutableListOf<Throwable>()

    fun attemptThread(email: String): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                val result = AdminBootstrap.bootstrapFirstAdmin("Erika Musterfrau", email, STRONG_PASSWORD, db)
                synchronized(results) { results += result }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val firstThread = attemptThread("race-a@example.org")
    val secondThread = attemptThread("race-b@example.org")
    firstThread.start()
    secondThread.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent bootstrapFirstAdmin calls did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun cleanUpAdminBootstrapTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}
