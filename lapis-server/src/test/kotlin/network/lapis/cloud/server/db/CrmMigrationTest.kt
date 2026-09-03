package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val ADMIN_UUID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- `V17__crm_contacts.sql`'s CHECK constraints
 * actually fire against the real migrated H2 schema. "CHECK-Sonde" pattern, same house idiom
 * [SocialNetworkSchemaDriftTest] establishes: a raw `exec()` INSERT with an invalid-but-column-
 * width-fitting value, expecting an [ExposedSQLException] naming the violated constraint.
 */
class CrmMigrationTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun probeInsert(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        fun baseColumns(
            id: Uuid,
            displayName: String,
            contactType: String = "INTERESSENT",
            lawfulBasis: String = "LEGITIMATE_INTEREST",
            email: String? = null,
            consentSource: String? = null,
            consentGivenAt: String? = null,
            consentWithdrawnAt: String? = null,
        ): String {
            val emailSql = email?.let { "'$it'" } ?: "NULL"
            val consentSourceSql = consentSource?.let { "'$it'" } ?: "NULL"
            val consentGivenSql = consentGivenAt?.let { "TIMESTAMP '$it'" } ?: "NULL"
            val consentWithdrawnSql = consentWithdrawnAt?.let { "TIMESTAMP '$it'" } ?: "NULL"
            return "INSERT INTO crm_contact (id, display_name, email, contact_type, lawful_basis, consent_source, " +
                "consent_given_at, consent_withdrawn_at, created_at, created_by, retention_review_due_at) VALUES (" +
                "'$id', '$displayName', $emailSql, '$contactType', '$lawfulBasis', $consentSourceSql, " +
                "$consentGivenSql, $consentWithdrawnSql, TIMESTAMP '2026-01-01 00:00:00', '$ADMIN_UUID', " +
                "TIMESTAMP '2028-01-01 00:00:00')"
        }

        test("chk_crm_contact_type rejects an invalid literal that still fits VARCHAR(19)") {
            val exception = probeInsert(baseColumns(id = Uuid.random(), displayName = "Probe A", contactType = "BOGUS_TYPE"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_crm_contact_type", ignoreCase = true) shouldBe true
        }

        test("chk_crm_contact_lawful_basis rejects an invalid literal that still fits VARCHAR(19)") {
            val exception = probeInsert(baseColumns(id = Uuid.random(), displayName = "Probe B", lawfulBasis = "BOGUS_BASIS"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_crm_contact_lawful_basis", ignoreCase = true) shouldBe true
        }

        test("chk_crm_contact_consent_fields rejects CONSENT without consent_source/consent_given_at") {
            val exception =
                probeInsert(
                    baseColumns(
                        id = Uuid.random(),
                        displayName = "Probe C",
                        lawfulBasis = "CONSENT",
                        consentSource = null,
                        consentGivenAt = null,
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_crm_contact_consent_fields", ignoreCase = true) shouldBe true
        }

        test("chk_crm_contact_consent_fields allows a complete CONSENT row") {
            val exception =
                probeInsert(
                    baseColumns(
                        id = Uuid.random(),
                        displayName = "Probe D",
                        lawfulBasis = "CONSENT",
                        consentSource = "Infostand",
                        consentGivenAt = "2026-01-01 00:00:00",
                    ),
                )
            exception shouldBe null
        }

        test("chk_crm_contact_withdrawal_requires_consent rejects a withdrawal without a prior consent_given_at") {
            val exception =
                probeInsert(
                    baseColumns(
                        id = Uuid.random(),
                        displayName = "Probe E",
                        consentGivenAt = null,
                        consentWithdrawnAt = "2026-02-01 00:00:00",
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_crm_contact_withdrawal_requires_consent", ignoreCase = true) shouldBe true
        }

        test("uq_crm_contact_email allows two rows with NULL email but rejects a duplicate non-null email") {
            val e1 = probeInsert(baseColumns(id = Uuid.random(), displayName = "Probe F1", email = null))
            val e2 = probeInsert(baseColumns(id = Uuid.random(), displayName = "Probe F2", email = null))
            e1 shouldBe null
            e2 shouldBe null

            val sharedEmail = "dup-probe-${Uuid.random()}@example.org"
            val first = probeInsert(baseColumns(id = Uuid.random(), displayName = "Probe F3", email = sharedEmail))
            first shouldBe null
            val second = probeInsert(baseColumns(id = Uuid.random(), displayName = "Probe F4", email = sharedEmail))
            (second is ExposedSQLException) shouldBe true
        }
    })
