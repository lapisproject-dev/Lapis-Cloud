package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.testdb.PostgresConfigured
import java.util.UUID

/**
 * V1.9.342 -- `V177__content_translation.sql` on the upgrade path V176 -> V177, on H2 AND on PostgreSQL: an event from before the migration has NO
 * consent; the consent shape CHECK; the translation table's key and CHECKs; `content_language` defaults to `de`; a re-run is a no-op.
 */
abstract class ContentTranslationMigrationScenarios(
    private val newHarness: () -> MigrationHarness,
) : FunSpec({
        lateinit var h: MigrationHarness

        fun exec(sql: String) = h.connection { c -> c.createStatement().use { it.execute(sql) } }

        fun rejection(sql: String): String? =
            runCatching { exec(sql) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { t -> t.cause }.joinToString(" | ") { t -> t.message.orEmpty() } }

        fun scalar(sql: String): String? =
            h.connection { c ->
                c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            }

        val member = UUID.randomUUID().toString()
        val oldEvent = "00000000-0000-0000-0000-00000000bb01"
        val rowId = "00000000-0000-0000-0000-0000000000f2"

        fun event(
            id: String,
            consent: String,
            by: String?,
            at: String?,
        ): String =
            "INSERT INTO event (id, slug, title, description, location_text, starts_at, ends_at, fee_amount, fee_currency, status, " +
                "visibility, created_at, created_by, translation_consent, translation_consent_by, translation_consent_at) VALUES " +
                "('$id', 'ct-mig-$id', 'T', 'd', 'Saal', TIMESTAMP '2030-06-15 18:00:00', TIMESTAMP '2030-06-15 20:00:00', 0, 'EUR', " +
                "'DRAFT', 'PUBLIC', TIMESTAMP '2026-10-26 10:00:00', '$member', $consent, ${by?.let { "'$it'" } ?: "NULL"}, " +
                "${at?.let { "TIMESTAMP '$it'" } ?: "NULL"})"

        fun translation(
            id: String,
            field: String = "TITLE",
            sha: String = "a".repeat(64),
            source: String = "de",
            target: String = "en",
            type: String = "EVENT",
        ): String =
            "INSERT INTO content_translation (content_type, content_id, field, source_sha256, source_lang, target_lang, translated_text, " +
                "model_profile, created_at) VALUES ('$type', '$id', '$field', '$sha', '$source', '$target', 'x', 'p:m', TIMESTAMP '2026-10-26 10:00:00')"

        beforeSpec {
            h = newHarness()
            h.flyway("176").migrate()
            exec(
                "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                    "('$member', 'Trans', 'ct-mig-$member@example.org', 'ACTIVE', DATE '2026-01-01')",
            )
            exec(
                "INSERT INTO event (id, slug, title, description, location_text, starts_at, ends_at, fee_amount, fee_currency, status, " +
                    "visibility, created_at, created_by) VALUES ('$oldEvent', 'ct-old', 'T', 'd', 'Saal', " +
                    "TIMESTAMP '2030-06-15 18:00:00', TIMESTAMP '2030-06-15 20:00:00', 0, 'EUR', 'PUBLISHED', 'PUBLIC', TIMESTAMP '2026-10-26 10:00:00', '$member')",
            )
            h.flyway(null).migrate()
        }
        afterSpec { h.close() }

        test("an event from before the migration has no consent and no decision trail") {
            scalar("SELECT translation_consent FROM event WHERE id = '$oldEvent'")!!.lowercase().let {
                (
                    it == "false" ||
                        it == "f" ||
                        it == "0"
                )
            } shouldBe
                true
            scalar("SELECT translation_consent_by FROM event WHERE id = '$oldEvent'") shouldBe null
            scalar("SELECT translation_consent_at FROM event WHERE id = '$oldEvent'") shouldBe null
        }

        test("the consent shape CHECK: a consent needs who and when; no consent may carry a trail (the last decision)") {
            val by = member
            rejection(event(UUID.randomUUID().toString(), "TRUE", by, "2026-11-01 10:00:00")) shouldBe null
            rejection(event(UUID.randomUUID().toString(), "FALSE", by, "2026-11-01 10:00:00")) shouldBe null
            rejection(event(UUID.randomUUID().toString(), "FALSE", null, null)) shouldBe null
            (
                rejection(
                    event(UUID.randomUUID().toString(), "TRUE", null, null),
                )!!
            ).lowercase().shouldContain("chk_event_translation_consent")
            (rejection(event(UUID.randomUUID().toString(), "TRUE", by, null))!!).lowercase().shouldContain("chk_event_translation_consent")
            (rejection(event(UUID.randomUUID().toString(), "TRUE", null, "2026-11-01 10:00:00"))!!)
                .lowercase()
                .shouldContain("chk_event_translation_consent")
        }

        test("the deciding member must exist") {
            (rejection(event(UUID.randomUUID().toString(), "TRUE", UUID.randomUUID().toString(), "2026-11-01 10:00:00"))!!)
                .lowercase()
                .shouldContain("fk_event_translation_consent_by")
        }

        test("content_translation: the key is composite, duplicates are refused, a changed hash or language is a new row") {
            val id = UUID.randomUUID().toString()
            rejection(translation(id)) shouldBe null
            (rejection(translation(id)) != null) shouldBe true
            rejection(translation(id, sha = "b".repeat(64))) shouldBe null
            rejection(translation(id, target = "fr")) shouldBe null
            rejection(translation(id, field = "SUMMARY")) shouldBe null
            scalar("SELECT COUNT(*) FROM content_translation WHERE content_id = '$id'") shouldBe "4"
        }

        test("content_translation: the CHECKs on type, field and language pair") {
            val id = UUID.randomUUID().toString()
            (rejection(translation(id, type = "ARTICLE"))!!).lowercase().shouldContain("chk_content_translation_type")
            (rejection(translation(id, field = "LOCATION"))!!).lowercase().shouldContain("chk_content_translation_field")
            (rejection(translation(id, target = "ja"))!!).lowercase().shouldContain("chk_content_translation_lang")
            (rejection(translation(id, source = "xx"))!!).lowercase().shouldContain("chk_content_translation_lang")
            (rejection(translation(id, source = "de", target = "de"))!!).lowercase().shouldContain("chk_content_translation_lang")
        }

        test("content_translation has no foreign key: a row for an unknown content id is accepted (polymorphic column)") {
            rejection(translation(UUID.randomUUID().toString())) shouldBe null
        }

        test("organization_settings.content_language defaults to de, is NOT NULL and takes another code") {
            scalar("SELECT content_language FROM organization_settings WHERE id = '$rowId'") shouldBe "de"
            exec("UPDATE organization_settings SET content_language = 'pl' WHERE id = '$rowId'")
            scalar("SELECT content_language FROM organization_settings WHERE id = '$rowId'") shouldBe "pl"
            (rejection("UPDATE organization_settings SET content_language = NULL WHERE id = '$rowId'") != null) shouldBe true
            exec("UPDATE organization_settings SET content_language = 'de' WHERE id = '$rowId'")
        }

        test("a re-run is a no-op") {
            h.flyway(null).migrate().migrationsExecuted shouldBe 0
        }
    })

class ContentTranslationMigrationTest : ContentTranslationMigrationScenarios({ H2MigrationHarness() })

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ContentTranslationMigrationPostgresTest : ContentTranslationMigrationScenarios({ PostgresMigrationHarness() })
