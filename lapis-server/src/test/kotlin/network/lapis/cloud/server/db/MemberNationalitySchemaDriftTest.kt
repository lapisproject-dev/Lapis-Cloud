package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- Welle V1.9.121. Verifies that `68-member-nationality.kuml.kts` (and the new `country_code`
 * attribute of `member` in `00-foundation.kuml.kts`) is a faithful model of the real, Flyway-migrated H2 schema (V96) and of the
 * hand-written [MemberNationalityTable]. Mirrors [MemberStatusHistorySchemaDriftTest].
 */
class MemberNationalitySchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val nationalityModel: ErmModel by lazy {
            KumlModelLoader.loadErmModel(File(KumlModelLoader.kumlSourceDir, "68-member-nationality.kuml.kts"))
        }
        val foundationModel: ErmModel by lazy {
            KumlModelLoader.loadErmModel(
                File(KumlModelLoader.kumlSourceDir, "00-foundation.kuml.kts"),
            )
        }

        test("model declares exactly member_nationality and the member stub") {
            nationalityModel.entities.map { it.name }.toSet() shouldBe setOf("member_nationality", "member")
        }

        test("member_nationality table shape matches the real migrated schema, with its FK to member") {
            val entity = nationalityModel.entities.single { it.name == "member_nationality" }
            val real = transaction { columnsOf("member_nationality") }
            entity.attributes.map { it.name }.toSet() shouldBe real.keys
            entity.attributes.forEach { attr -> real.getValue(attr.name!!) shouldBe attr.nullable }
            transaction { foreignKeysOf("member_nationality") }["member_id"] shouldBe "member"
        }

        test("member_nationality entity columns match the hand-written MemberNationalityTable 1:1") {
            nationalityModel.entities
                .single { it.name == "member_nationality" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MemberNationalityTable.columns.map { it.name }
        }

        test("member.country_code is modelled, nullable, and a column of the real table and of MemberTable") {
            val attr =
                foundationModel.entities
                    .single { it.name == "member" }
                    .attributes
                    .single { it.name == "country_code" }
            attr.nullable shouldBe true
            transaction { columnsOf("member") }["country_code"] shouldBe true
            MemberTable.columns.any { it.name == "country_code" } shouldBe true
        }
    })

private fun JdbcTransaction.columnsOf(tableName: String): Map<String, Boolean> {
    val nullable = mutableMapOf<String, Boolean>()
    exec("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '$tableName'") { rs ->
        while (rs.next()) nullable[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
    }
    return nullable
}

private fun JdbcTransaction.foreignKeysOf(tableName: String): Map<String, String> {
    val fk = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) fk[rs.getString("fk_column")] = rs.getString("ref_table")
    }
    return fk
}
