package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostErasureTable
import network.lapis.cloud.server.db.generated.SocialPostReportTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * ADR-0016 (MDA persistence pipeline) — Soziales Netzwerk domain, Welle V1.1.1.
 *
 * Verifies that `lapis-server/src/main/kuml/32-social-network.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`social_post`), and (b) the hand-written
 * [SocialPostTable] Exposed object. Mirrors [CrowdfundingSchemaDriftTest]'s shape.
 */
class SocialNetworkSchemaDriftTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            // NEU-1 (Review Runde 2, 2026-08-18): force = true -- without it, seedIfEmpty() reads
            // System.getenv("LAPIS_SEED_DEMO_DATA"), which `tasks.test` never sets, and returns
            // immediately. SEED_ADMIN_MEMBER_ID below would then only happen to exist if some other
            // spec seeded it first in the same JVM -- every other Spec in this codebase that relies
            // on the seeded fixtures passes force = true for exactly this reason.
            DevSeedData.seedIfEmpty(force = true)
        }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "32-social-network.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test(
            "model declares exactly the social_post/social_post_boost/social_post_report/social_post_erasure entities plus the Member stub",
        ) {
            model.entities.map { it.name }.toSet() shouldBe
                setOf("member", "social_post", "social_post_boost", "social_post_report", "social_post_erasure")
        }

        test("social_post table shape matches the real migrated schema and SocialPostTable 1:1") {
            val entity = model.entities.single { it.name == "social_post" }
            val real = transaction { introspectSocialPostTable() }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder SocialPostTable.columns.map { it.name }

            real.foreignKeys["parent_id"] shouldBe "social_post"
            real.foreignKeys["root_id"] shouldBe "social_post"
            real.foreignKeys["author_member_id"] shouldBe "member"
            real.foreignKeys["state_changed_by"] shouldBe "member"

            entity.attributeByName("parent_id")?.nullable shouldBe true
            entity.attributeByName("root_id")?.nullable shouldBe false
            entity.attributeByName("author_member_id")?.nullable shouldBe false
            entity.attributeByName("state_changed_by")?.nullable shouldBe true

            entity.attributeByName("visibility")?.type shouldBe
                ErmDataType.Enum(
                    name = "SocialPostVisibility",
                    values = listOf("PUBLIC", "MEMBERS_ONLY", "MEMBERS_AND_EXTERNAL"),
                    externalFqName = "network.lapis.cloud.shared.domain.SocialPostVisibility",
                )
            entity.attributeByName("state")?.type shouldBe
                ErmDataType.Enum(
                    name = "SocialPostState",
                    values = listOf("VISIBLE", "HIDDEN_BY_AUTHOR", "REMOVED_LEGAL"),
                    externalFqName = "network.lapis.cloud.shared.domain.SocialPostState",
                )
            entity.attributeByName("initial_weight_ltr")?.type shouldBe ErmDataType.Decimal(18, 2)
        }

        // Welle V1.1.5 Stolperdraht (Plan Stolperfalle 8): dieser Test ist bewusst UMGEDREHT, nicht
        // gelöscht -- aus "hat die Spalten noch nicht" wird "hat sie". V6__social_moderation_and_erasure.sql
        // legt beide Spalten an.
        test("social_post has content_erased_at/content_erasure_note columns (Welle V1.1.5)") {
            val real = transaction { introspectSocialPostTable() }
            ("content_erased_at" in real.columns.keys) shouldBe true
            ("content_erasure_note" in real.columns.keys) shouldBe true
            real.columns.getValue("content_erased_at").nullable shouldBe true
            real.columns.getValue("content_erasure_note").nullable shouldBe true
        }

        test("social_post_boost table shape matches the real migrated schema and SocialPostBoostTable 1:1") {
            val entity = model.entities.single { it.name == "social_post_boost" }
            val real = transaction { introspectSocialPostBoostTable() }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder SocialPostBoostTable.columns.map { it.name }

            real.foreignKeys["post_id"] shouldBe "social_post"
            real.foreignKeys["member_id"] shouldBe "member"
            entity.attributeByName("post_id")?.nullable shouldBe false
            entity.attributeByName("member_id")?.nullable shouldBe false
            entity.attributeByName("amount_ltr")?.type shouldBe ErmDataType.Decimal(18, 2)
        }

        test("social_post_report table shape matches the real migrated schema and SocialPostReportTable 1:1") {
            val entity = model.entities.single { it.name == "social_post_report" }
            val real = transaction { introspectGenericTable("social_post_report") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder SocialPostReportTable.columns.map { it.name }

            real.foreignKeys["post_id"] shouldBe "social_post"
            real.foreignKeys["reporter_member_id"] shouldBe "member"
            real.foreignKeys["decided_by"] shouldBe "member"
            entity.attributeByName("post_id")?.nullable shouldBe false
            entity.attributeByName("reporter_member_id")?.nullable shouldBe true

            entity.attributeByName("category")?.type shouldBe
                ErmDataType.Enum(
                    name = "SocialPostReportCategory",
                    values = listOf("ILLEGAL_CONTENT", "DEFAMATION", "COPYRIGHT", "PERSONAL_DATA", "HATE_SPEECH", "SPAM", "OTHER"),
                    externalFqName = "network.lapis.cloud.shared.domain.SocialPostReportCategory",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "SocialPostReportStatus",
                    values = listOf("OPEN", "UNDER_REVIEW", "ACTION_TAKEN", "DISMISSED"),
                    externalFqName = "network.lapis.cloud.shared.domain.SocialPostReportStatus",
                )
        }

        test("social_post_erasure table shape matches the real migrated schema and SocialPostErasureTable 1:1") {
            val entity = model.entities.single { it.name == "social_post_erasure" }
            val real = transaction { introspectGenericTable("social_post_erasure") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder SocialPostErasureTable.columns.map { it.name }

            real.foreignKeys["post_id"] shouldBe "social_post"
            real.foreignKeys["subject_member_id"] shouldBe "member"
            real.foreignKeys["source_report_id"] shouldBe "social_post_report"
            entity.attributeByName("post_id")?.nullable shouldBe false
            entity.attributeByName("subject_member_id")?.nullable shouldBe true

            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "SocialPostErasureStatus",
                    values = listOf("REQUESTED", "APPROVED", "REJECTED", "EXECUTED"),
                    externalFqName = "network.lapis.cloud.shared.domain.SocialPostErasureStatus",
                )
        }

        test("chk_social_post_report_category/status reject an invalid literal (CHECK-Sonde, Hausmuster)") {
            val postId =
                transaction {
                    val id = Uuid.random()
                    SocialPostTable.insert {
                        it[SocialPostTable.id] = id
                        it[parentId] = null
                        it[rootId] = id
                        it[depth] = 0
                        it[authorMemberId] = SEED_ADMIN_MEMBER_ID
                        it[content] = "chk_social_post_report probe root"
                        it[visibility] = SocialPostVisibility.PUBLIC
                        it[initialWeightLtr] = BigDecimal("1.00")
                        it[publishedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                        it[state] = SocialPostState.VISIBLE
                        it[stateChangedAt] = null
                        it[stateChangedBy] = null
                        it[stateReason] = null
                    }
                    id
                }
            val outcome =
                runCatching {
                    transaction {
                        exec(
                            "INSERT INTO social_post_report (id, post_id, reported_at, category, description, " +
                                "good_faith_confirmed, status) VALUES ('${Uuid.random()}', '$postId', " +
                                "'2026-01-01 00:00:00', 'NOT_A_REAL_CATEGORY', 'x', true, 'OPEN')",
                        )
                    }
                }
            outcome.isFailure shouldBe true
            val exception = outcome.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_social_post_report_category", ignoreCase = true) shouldBe true
        }

        test("chk_social_post_erasure_status rejects an invalid literal (CHECK-Sonde, Hausmuster)") {
            val postId =
                transaction {
                    val id = Uuid.random()
                    SocialPostTable.insert {
                        it[SocialPostTable.id] = id
                        it[parentId] = null
                        it[rootId] = id
                        it[depth] = 0
                        it[authorMemberId] = SEED_ADMIN_MEMBER_ID
                        it[content] = "chk_social_post_erasure probe root"
                        it[visibility] = SocialPostVisibility.PUBLIC
                        it[initialWeightLtr] = BigDecimal("1.00")
                        it[publishedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                        it[state] = SocialPostState.VISIBLE
                        it[stateChangedAt] = null
                        it[stateChangedBy] = null
                        it[stateReason] = null
                    }
                    id
                }
            // BOGUS_STATUS (12 chars) fits VARCHAR(16) -- an invalid-but-fits literal is required so
            // the CHECK constraint itself is exercised, not merely the column-width limit (a longer
            // literal like the obvious "NOT_A_REAL_STATUS", 18 chars, fails on width FIRST and never
            // reaches the CHECK evaluation at all).
            val outcome =
                runCatching {
                    transaction {
                        exec(
                            "INSERT INTO social_post_erasure (id, post_id, requested_at, reason, status) VALUES " +
                                "('${Uuid.random()}', '$postId', '2026-01-01 00:00:00', 'x', 'BOGUS_STATUS')",
                        )
                    }
                }
            outcome.isFailure shouldBe true
            val exception = outcome.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_social_post_erasure_status", ignoreCase = true) shouldBe true
        }

        test("chk_social_post_boost_min_amount rejects an amount_ltr below 0.01") {
            val outcome =
                runCatching {
                    transaction {
                        val postId = Uuid.random()
                        SocialPostTable.insert {
                            it[id] = postId
                            it[parentId] = null
                            it[rootId] = postId
                            it[depth] = 0
                            it[authorMemberId] = SEED_ADMIN_MEMBER_ID
                            it[content] = "chk_social_post_boost_min_amount probe root -- never expected to persist"
                            it[visibility] = SocialPostVisibility.PUBLIC
                            it[initialWeightLtr] = BigDecimal("1.00")
                            it[publishedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                            it[state] = SocialPostState.VISIBLE
                            it[stateChangedAt] = null
                            it[stateChangedBy] = null
                            it[stateReason] = null
                        }
                        SocialPostBoostTable.insert {
                            it[id] = Uuid.random()
                            it[SocialPostBoostTable.postId] = postId
                            it[memberId] = SEED_ADMIN_MEMBER_ID
                            it[amountLtr] = BigDecimal("0.00")
                            it[boostedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                        }
                    }
                }
            // The failing transaction{} block rolls back automatically -- nothing to clean up (the
            // probe root social_post row is rolled back with it).
            outcome.isFailure shouldBe true
            val exception = outcome.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_social_post_boost_min_amount", ignoreCase = true) shouldBe true
        }

        // G8 (Review Runde 1, 2026-08-18): the two tests above never actually exercised
        // chk_social_post_min_weight/chk_social_post_depth -- they only compared column
        // names/nullability/enum shapes, so a migration that silently dropped either CHECK
        // constraint (or mistyped its bound) would have passed unnoticed. These two tests insert a
        // row that violates each constraint directly (bypassing SocialNetworkService, which already
        // enforces the same bounds in Kotlin before ever reaching the DB) and assert the DATABASE
        // itself rejects it -- the actual belt-and-suspenders guarantee `chk_social_post_min_weight`/
        // `chk_social_post_depth` exist for. No established CHECK-constraint-assertion helper exists
        // elsewhere in this codebase's SchemaDriftTests (MemberStatusMigrationTest is the closest
        // precedent, using a raw JDBC insert + runCatching); this uses the typed Exposed `.insert`
        // DSL instead, consistent with this file's/`PersonalDataContributor`'s "typed Exposed query
        // builders exclusively" house rule.
        //
        // NEU-1 (Review Runde 2, 2026-08-18): merely asserting "some ExposedSQLException flew" is
        // also satisfied by an UNRELATED failure -- e.g. fk_social_post_author, if SEED_ADMIN_MEMBER_ID
        // did not actually exist because of the seedIfEmpty(force = true) bug fixed above. Verified:
        // with a VALID initial_weight_ltr (e.g. "1.00"), the min-weight test still passed before this
        // fix, because the insert failed on the FK instead -- i.e. the test asserted nothing about
        // chk_social_post_min_weight specifically. Both tests below now additionally assert the
        // exception message names the expected constraint, so a future migration that silently drops
        // (or mistypes) either CHECK constraint -- while leaving the FK intact -- fails loudly here
        // instead of staying green by accident.

        test("chk_social_post_min_weight rejects an initial_weight_ltr below 0.01") {
            val outcome =
                runCatching {
                    transaction {
                        val postId = Uuid.random()
                        SocialPostTable.insert {
                            it[id] = postId
                            it[parentId] = null
                            it[rootId] = postId
                            it[depth] = 0
                            it[authorMemberId] = SEED_ADMIN_MEMBER_ID
                            it[content] = "chk_social_post_min_weight probe -- never expected to persist"
                            it[visibility] = SocialPostVisibility.PUBLIC
                            it[initialWeightLtr] = BigDecimal("0.00")
                            it[publishedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                            it[state] = SocialPostState.VISIBLE
                            it[stateChangedAt] = null
                            it[stateChangedBy] = null
                            it[stateReason] = null
                        }
                    }
                }
            // The failing transaction{} block rolls back automatically -- nothing to clean up.
            outcome.isFailure shouldBe true
            val exception = outcome.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            // NEU-1: without this, a FK failure (e.g. a missing/wrong SEED_ADMIN_MEMBER_ID) would
            // make this test pass just as green, without ever exercising chk_social_post_min_weight.
            (exception?.message ?: "").contains("chk_social_post_min_weight", ignoreCase = true) shouldBe true
        }

        test("chk_social_post_depth rejects a depth outside 0..64") {
            val outcome =
                runCatching {
                    transaction {
                        val postId = Uuid.random()
                        SocialPostTable.insert {
                            it[id] = postId
                            it[parentId] = null
                            it[rootId] = postId
                            it[depth] = 65
                            it[authorMemberId] = SEED_ADMIN_MEMBER_ID
                            it[content] = "chk_social_post_depth probe -- never expected to persist"
                            it[visibility] = SocialPostVisibility.PUBLIC
                            it[initialWeightLtr] = BigDecimal("1.00")
                            it[publishedAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                            it[state] = SocialPostState.VISIBLE
                            it[stateChangedAt] = null
                            it[stateChangedBy] = null
                            it[stateReason] = null
                        }
                    }
                }
            // The failing transaction{} block rolls back automatically -- nothing to clean up.
            outcome.isFailure shouldBe true
            val exception = outcome.exceptionOrNull()
            (exception is ExposedSQLException) shouldBe true
            // NEU-1: same rationale as chk_social_post_min_weight above.
            (exception?.message ?: "").contains("chk_social_post_depth", ignoreCase = true) shouldBe true
        }
    })

/** Fixed, guessable seeded ADMIN member id -- see `DevSeedData.kt`, same constant every other direct-FK test fixture in this codebase relies on. */
private val SEED_ADMIN_MEMBER_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/** Result of introspecting the real `social_post` table's shape via `information_schema`. Mirrors [CrowdfundingSchemaDriftTest]'s own private helper. */
private data class IntrospectedSocialPostTable(
    val columns: Map<String, IntrospectedSocialPostColumn>,
    val foreignKeys: Map<String, String>,
)

private data class IntrospectedSocialPostColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectSocialPostTable(): IntrospectedSocialPostTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = 'social_post'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = 'social_post'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedSocialPostColumn(nullable = nullable) }
    return IntrospectedSocialPostTable(columns = columns, foreignKeys = fkByColumn)
}

/** Result of introspecting the real `social_post_boost` table's shape via `information_schema`. Mirrors [IntrospectedSocialPostTable]. */
private data class IntrospectedSocialPostBoostTable(
    val columns: Map<String, IntrospectedSocialPostColumn>,
    val foreignKeys: Map<String, String>,
)

private fun JdbcTransaction.introspectSocialPostBoostTable(): IntrospectedSocialPostBoostTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = 'social_post_boost'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = 'social_post_boost'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedSocialPostColumn(nullable = nullable) }
    return IntrospectedSocialPostBoostTable(columns = columns, foreignKeys = fkByColumn)
}

/**
 * Welle V1.1.5 -- generic version of [introspectSocialPostTable]/[introspectSocialPostBoostTable]
 * for [tableName], used by the new `social_post_report`/`social_post_erasure` tests instead of
 * hand-duplicating the same `information_schema` walk a third/fourth time.
 */
private fun JdbcTransaction.introspectGenericTable(tableName: String): IntrospectedSocialPostTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedSocialPostColumn(nullable = nullable) }
    return IntrospectedSocialPostTable(columns = columns, foreignKeys = fkByColumn)
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors [CrowdfundingSchemaDriftTest]'s). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
