package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * Welle V1.9.144 -- `V107__member_finder.sql` on the upgrade path V106 -> V107, on H2 AND on PostgreSQL: the two settings columns and their defaults,
 * the new agreement-text kind FINDER in both CHECK constraints (and nothing else), and every CHECK of the four tables as a refusal. V106 stays
 * untouched; a re-run is a no-op.
 */
abstract class MemberFinderMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun columnDefault(
            table: String,
            column: String,
        ): String? =
            h.connection { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT column_default FROM information_schema.columns WHERE table_name = '$table' AND column_name = '$column'",
                        ).use { rs -> if (rs.next()) rs.getString(1) else "<missing>" }
                }
            }

        val author = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()

        fun version(
            kind: String,
            label: String,
        ): String =
            "INSERT INTO agreement_text_version (id, kind, sequence_no, version_label, valid_from, body, sha256, char_count, " +
                "author_member_id, published_at) VALUES ('${UUID.randomUUID()}', '$kind', 1, '$label', DATE '2026-10-16', 'hallo', " +
                "'${"a".repeat(64)}', 5, '$author', TIMESTAMP '2026-10-16 10:00:00')"

        fun draft(kind: String): String =
            "INSERT INTO agreement_text_draft (kind, id, version_label, valid_from, body, sha256, char_count, updated_by, updated_at) " +
                "VALUES ('$kind', '${UUID.randomUUID()}', 'x.v1', DATE '2026-10-16', 'hallo', '${"b".repeat(64)}', 5, '$author', " +
                "TIMESTAMP '2026-10-16 10:00:00')"

        fun consent(
            type: String,
            version: String? = "'v1'",
            sha: String? = "'${"c".repeat(64)}'",
            interest: String? = "'BOTH'",
            adult: String = "TRUE",
            reason: String? = null,
        ): String =
            "INSERT INTO member_finder_consent_event (id, member_id, event_type, occurred_at, consent_version, consent_sha256, interest, " +
                "adult_confirmed, revoke_reason) VALUES ('${UUID.randomUUID()}', '$author', '$type', TIMESTAMP '2026-10-16 10:00:00', " +
                "${version ?: "NULL"}, ${sha ?: "NULL"}, ${interest ?: "NULL"}, $adult, ${reason ?: "NULL"})"

        fun request(
            to: String?,
            status: String = "OPEN",
            decided: Boolean = false,
            purpose: String = "MEETUP",
            expires: String = "TIMESTAMP '2026-11-16 10:00:00'",
            from: String = author,
        ): String =
            "INSERT INTO member_finder_request (id, from_member_id, to_member_id, purpose, status, created_at, expires_at, decided_at) " +
                "VALUES " +
                "('${UUID.randomUUID()}', '$from', ${to?.let {
                    "'$it'"
                } ?: "NULL"}, '$purpose', '$status', TIMESTAMP '2026-10-16 10:00:00', " +
                "$expires, ${if (decided) "TIMESTAMP '2026-10-17 10:00:00'" else "NULL"})"

        beforeSpec {
            h = newHarness()
            h.flyway("106").migrate()
            h.flyway(null).migrate()
            for (m in listOf(author, other)) {
                exec(
                    "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                        "('$m', 'Finder', 'finder-mig-$m@example.org', 'ACTIVE', DATE '2026-01-01')",
                )
            }
        }
        afterSpec { h.close() }

        test("the settings columns default to 'off' and no notice version") {
            columnDefault("organization_settings", "member_finder_enabled")!!.uppercase() shouldContain "FALSE"
            columnDefault(
                "organization_settings",
                "member_finder_notice_version",
            ).let { it == null || it.uppercase() == "NULL" } shouldBe true
            h.connection { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT member_finder_enabled, member_finder_notice_version FROM organization_settings").use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean(1) shouldBe false
                        rs.getString(2) shouldBe null
                    }
                }
            }
        }

        test("both agreement tables accept FINDER and still refuse an unknown kind; the older kinds are still there") {
            rejection(version("FINDER", "finder.v1")) shouldBe null
            rejection(draft("FINDER")) shouldBe null
            (rejection(version("FINDER2", "x.v1"))!!).shouldContain("chk_agreement_text_version_kind")
            (rejection(draft("FINDER2"))!!).shouldContain("chk_agreement_text_draft_kind")
            for (kind in listOf("MEMBERSHIP", "FRIEND", "REC_ROOM", "REC_PULPIT")) {
                rejection(version(kind, "old-$kind.v1")) shouldBe null
                rejection(draft(kind)) shouldBe null
            }
            exec("DELETE FROM agreement_text_version")
            exec("DELETE FROM agreement_text_draft")
        }

        test("a consent event: a complete GRANT and a REVOKE with a reason pass; every incomplete or contradictory shape is refused") {
            rejection(consent("GRANT")) shouldBe null
            rejection(consent("REVOKE", version = null, sha = null, interest = null, adult = "FALSE", reason = "'INSTANCE_OFF'")) shouldBe
                null
            (rejection(consent("GRANT", adult = "FALSE"))!!).shouldContain("chk_member_finder_consent_event_shape")
            (rejection(consent("GRANT", version = null))!!).shouldContain("chk_member_finder_consent_event_shape")
            (rejection(consent("GRANT", sha = "'short'"))!!).shouldContain("chk_member_finder_consent_event_shape")
            (rejection(consent("GRANT", interest = null))!!).shouldContain("chk_member_finder_consent_event_shape")
            (rejection(consent("GRANT", reason = "'MEMBER'"))!!).shouldContain("chk_member_finder_consent_event_shape")
            (rejection(consent("REVOKE", version = null, sha = null, interest = null, adult = "FALSE"))!!)
                .shouldContain("chk_member_finder_consent_event_shape")
            (rejection(consent("REVOKE", interest = null, reason = "'MEMBER'", adult = "FALSE"))).let { it shouldBe null }
            // an unknown type also breaks the shape; which of the two constraints a database names first differs (H2 versus PostgreSQL)
            (rejection(consent("OTHER"))!!).shouldContain("chk_member_finder_consent_event_")
            (rejection(consent("GRANT", interest = "'NOBODY'"))!!).shouldContain("chk_member_finder_consent_event_interest")
            (rejection(consent("REVOKE", version = null, sha = null, interest = null, adult = "FALSE", reason = "'BECAUSE'"))!!)
                .shouldContain("chk_member_finder_consent_event_reason")
            (
                rejection(
                    consent("GRANT").replace(author, UUID.randomUUID().toString()),
                )!!
            ).shouldContain("fk_member_finder_consent_event_member")
        }

        test(
            "a request: self-request, OPEN with a decision, a decision without OPEN, ACCEPTED without an addressee, a bad status/purpose and a bad expiry are refused",
        ) {
            rejection(request(to = other)) shouldBe null
            rejection(request(to = null)) shouldBe null
            rejection(request(to = other, status = "DECLINED", decided = true)) shouldBe null
            rejection(request(to = null, status = "EXPIRED", decided = true)) shouldBe null
            (rejection(request(to = author))!!).shouldContain("chk_member_finder_request_not_self")
            (rejection(request(to = other, decided = true))!!).shouldContain("chk_member_finder_request_decided")
            (rejection(request(to = other, status = "DECLINED", decided = false))!!).shouldContain("chk_member_finder_request_decided")
            (
                rejection(
                    request(to = null, status = "ACCEPTED", decided = true),
                )!!
            ).shouldContain("chk_member_finder_request_accepted_target")
            (rejection(request(to = other, status = "MAYBE", decided = true))!!).shouldContain("chk_member_finder_request_status")
            (rejection(request(to = other, purpose = "BOTH"))!!).shouldContain("chk_member_finder_request_purpose")
            (
                rejection(
                    request(to = other, expires = "TIMESTAMP '2026-10-16 10:00:00'"),
                )!!
            ).shouldContain("chk_member_finder_request_expiry")
            (rejection(request(to = UUID.randomUUID().toString()))!!).shouldContain("fk_member_finder_request_to")
            (rejection(request(to = other, from = UUID.randomUUID().toString()))!!).shouldContain("fk_member_finder_request_from")
        }

        test("a block: a pair only once, never oneself, only real members") {
            fun block(
                blocker: String,
                blocked: String,
            ) = "INSERT INTO member_finder_block (id, blocker_member_id, blocked_member_id, created_at) VALUES " +
                "('${UUID.randomUUID()}', '$blocker', '$blocked', TIMESTAMP '2026-10-16 10:00:00')"
            rejection(block(author, other)) shouldBe null
            (rejection(block(author, other))!!).shouldContain("uq_member_finder_block_pair")
            rejection(block(other, author)) shouldBe null
            (rejection(block(author, author))!!).shouldContain("chk_member_finder_block_not_self")
            (rejection(block(author, UUID.randomUUID().toString()))!!).shouldContain("fk_member_finder_block_blocked")
        }

        test("the band table accepts the three bands only and has no member column") {
            fun band(
                key: String,
                band: String,
            ) =
                "INSERT INTO member_finder_region_band (region_key, band, computed_at) VALUES ('$key', '$band', TIMESTAMP '2026-10-16 10:00:00')"
            rejection(band("DE-38", "B5_9")) shouldBe null
            rejection(band("DE-10", "B10_19")) shouldBe null
            rejection(band("DE-20", "B20_PLUS")) shouldBe null
            (rejection(band("DE-30", "B4"))!!).shouldContain("chk_member_finder_region_band_band")
            h.connection { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT column_name FROM information_schema.columns WHERE table_name = 'member_finder_region_band'",
                        ).use { rs ->
                            val names = buildSet { while (rs.next()) add(rs.getString(1)) }
                            names shouldBe setOf("region_key", "band", "computed_at")
                        }
                }
            }
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class MemberFinderMigrationTest : MemberFinderMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberFinderMigrationPostgresTest : MemberFinderMigrationScenarios({ PostgresMigrationHarness() })
