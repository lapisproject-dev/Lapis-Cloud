package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — Registration domain (V0.7.2 Beitritts-/
 * Registrierungs-Workflow, plus V0.11.0 FRIEND self-registration's own
 * `friend_terms_acknowledgment`/`friend_email_verification_token` tables).
 *
 * Verifies that `lapis-server/src/main/kuml/23-registration.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`membership_agreement_acknowledgment`/
 * `password_reset_token`/`friend_terms_acknowledgment`/`friend_email_verification_token`), and (b)
 * the hand-written [MembershipAgreementAcknowledgmentTable]/[PasswordResetTokenTable]/
 * [FriendTermsAcknowledgmentTable]/[FriendEmailVerificationTokenTable] Exposed objects. Mirrors
 * [SessionSchemaDriftTest]'s shape — see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class RegistrationSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "23-registration.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the four new entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "membership_agreement_acknowledgment",
                    "friend_terms_acknowledgment",
                    "friend_email_verification_token",
                    "password_reset_token",
                )
        }

        test(
            "membership_agreement_acknowledgment table shape matches the real migrated schema and MembershipAgreementAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "membership_agreement_acknowledgment" }
            val real = transaction { introspectRegistrationTable("membership_agreement_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                MembershipAgreementAcknowledgmentTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false
        }

        test(
            "friend_terms_acknowledgment table shape matches the real migrated schema and FriendTermsAcknowledgmentTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "friend_terms_acknowledgment" }
            val real = transaction { introspectRegistrationTable("friend_terms_acknowledgment") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                FriendTermsAcknowledgmentTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false
        }

        test(
            "friend_email_verification_token table shape matches the real migrated schema and FriendEmailVerificationTokenTable 1:1",
        ) {
            val entity = model.entities.single { it.name == "friend_email_verification_token" }
            val real = transaction { introspectRegistrationTable("friend_email_verification_token") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder
                FriendEmailVerificationTokenTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            entity.attributeByName("consumed_at")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("token_hash"))
        }

        test("password_reset_token table shape matches the real migrated schema and PasswordResetTokenTable 1:1") {
            val entity = model.entities.single { it.name == "password_reset_token" }
            val real = transaction { introspectRegistrationTable("password_reset_token") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                withClue(clue = "column '${attr.name}'") {
                    real.columns.getValue(attr.name!!).nullable shouldBe attr.nullable
                }
            }
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder PasswordResetTokenTable.columns.map { it.name }

            real.primaryKeyColumns shouldBe setOf("id")
            entity.attributeByName("id")?.primaryKey shouldBe true

            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            entity.attributeByName("member_id")?.nullable shouldBe false

            entity.attributeByName("consumed_at")?.nullable shouldBe true

            real.uniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("token_hash"))
        }
    })

/** Result of introspecting one real table's shape via `information_schema`. Mirrors [SessionSchemaDriftTest]'s own private helper. */
private data class IntrospectedRegistrationTable(
    val columns: Map<String, IntrospectedRegistrationColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
    val uniqueConstraints: List<Set<String>>,
)

private data class IntrospectedRegistrationColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectRegistrationTable(tableName: String): IntrospectedRegistrationTable {
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

    val pkColumns = mutableSetOf<String>()
    exec(
        """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            pkColumns += rs.getString("column_name")
        }
    }

    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name AS name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedRegistrationColumn(nullable = nullable) }
    return IntrospectedRegistrationTable(
        columns = columns,
        foreignKeys = fkByColumn,
        primaryKeyColumns = pkColumns,
        uniqueConstraints = uniqueColumnsByConstraint.values.map { it.toSet() },
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors SchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
