package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.121 -- `V96__member_country_and_nationality_codes.sql`: purely additive (existing rows byte-identical across V95 -> V96),
 * idempotent (the script can run twice), and the shape CHECKs, the composite key and the FK without CASCADE bite. The same
 * scenarios run on H2 and on PostgreSQL.
 */
abstract class MemberCountryMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rows(sql: String): List<List<String>> =
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getString(it) ?: "null" }) }
                    }
                }
            }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        val member = UUID.randomUUID()
        lateinit var before: String

        fun v96Script(): String =
            checkNotNull(
                Thread.currentThread().contextClassLoader.getResourceAsStream("db/migration/V96__member_country_and_nationality_codes.sql"),
            ).use { String(it.readBytes()) }

        beforeSpec {
            h = newHarness()
            h.flyway("95").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at, country, nationality) VALUES " +
                    "('$member', 'M', 'mc-$member@example.org', 'ACTIVE', DATE '2024-01-01', 'Polen', 'polnisch')",
            )
            before = rows("SELECT id, display_name, email, status, country, nationality FROM member ORDER BY id").toString()
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("V96 is additive: existing rows are unchanged, the new column is NULL, the new table is empty") {
            rows("SELECT id, display_name, email, status, country, nationality FROM member ORDER BY id").toString() shouldBe before
            rows("SELECT country_code FROM member WHERE id = '$member'") shouldBe listOf(listOf("null"))
            rows("SELECT COUNT(*) FROM member_nationality") shouldBe listOf(listOf("0"))
        }

        test("V96 is idempotent: running the script again changes nothing and does not fail") {
            val script = v96Script()
            val statements =
                script
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 8) shouldBe true
            statements.forEach { exec(it) }
            rows("SELECT COUNT(*) FROM member_nationality") shouldBe listOf(listOf("0"))
        }

        test("country_code shape CHECK: two upper-case characters or NULL") {
            rejection("UPDATE member SET country_code = 'PL' WHERE id = '$member'") shouldBe null
            (rejection("UPDATE member SET country_code = 'pl' WHERE id = '$member'") != null) shouldBe true
            (rejection("UPDATE member SET country_code = 'P' WHERE id = '$member'") != null) shouldBe true
            (rejection("UPDATE member SET country_code = 'POL' WHERE id = '$member'") != null) shouldBe true
            rejection("UPDATE member SET country_code = NULL WHERE id = '$member'") shouldBe null
        }

        test("member_nationality: composite key, shape CHECK, FK without CASCADE") {
            fun row(
                m: UUID = member,
                code: String,
            ) = "INSERT INTO member_nationality (member_id, country_code) VALUES ('$m', '$code')"
            rejection(row(code = "DE")) shouldBe null
            rejection(row(code = "DE"))!!.lowercase() shouldContain "pk_member_nationality"
            rejection(row(code = "PL")) shouldBe null
            rejection(row(code = "XS")) shouldBe null
            (rejection(row(code = "de")) != null) shouldBe true
            (rejection(row(code = "DEU")) != null) shouldBe true
            (rejection(row(m = UUID.randomUUID(), code = "DE")) != null) shouldBe true
            // no CASCADE: a member with nationality rows cannot be deleted
            (rejection("DELETE FROM member WHERE id = '$member'") != null) shouldBe true
            rows("SELECT COUNT(*) FROM member WHERE id = '$member'") shouldBe listOf(listOf("1"))
        }
    })

class MemberCountryMigrationTest : MemberCountryMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberCountryMigrationPostgresTest : MemberCountryMigrationScenarios({ PostgresMigrationHarness() })
