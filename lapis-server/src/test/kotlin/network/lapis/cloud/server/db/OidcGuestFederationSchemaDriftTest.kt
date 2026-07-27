package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.OidcHomeServerRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.OidcRpLoginAttemptTable
import network.lapis.cloud.server.db.generated.OidcSigningKeyTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) -- V0.8.2 OIDC-Gastzugang-Federation domain.
 *
 * Verifies that `lapis-server/src/main/kuml/25-oidc-guest-federation.kuml.kts` is a faithful model
 * of both (a) the real, Flyway-migrated H2 schema and (b) the hand-written `Oidc*Table` Exposed
 * objects. Mirrors [FederationSchemaDriftTest]'s shape.
 *
 * The domain-specific structural point this test pins above all others:
 * `oidc_guest_login_event.member_id` has NO FK in the real schema -- the one deliberate deviation
 * from "every FK-bearing column gets a real constraint" this wave makes (see
 * `25-oidc-guest-federation.kuml.kts` file header) -- a future accidental `.references(...)`
 * addition to [OidcGuestLoginEventTable] would break this test loudly.
 */
class OidcGuestFederationSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "25-oidc-guest-federation.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the nine OIDC guest-federation entities plus the Member stub") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "oidc_signing_key",
                    "oidc_client_registration",
                    "oidc_client_redirect_uri",
                    "oidc_authorization_code",
                    "oidc_issued_token",
                    "oidc_home_server_registration",
                    "oidc_rp_login_attempt",
                    "oidc_guest_profile",
                    "oidc_guest_login_event",
                )
        }

        test("oidc_signing_key table shape matches the real migrated schema and OidcSigningKeyTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "oidc_signing_key" }
            val real = transaction { introspectOidcTable("oidc_signing_key") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcSigningKeyTable.columns.map { it.name }
            real.primaryKeyColumns shouldBe setOf("id")
            real.foreignKeys.isEmpty() shouldBe true
        }

        test("oidc_client_registration table shape matches the real migrated schema and OidcClientRegistrationTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "oidc_client_registration" }
            val real = transaction { introspectOidcTable("oidc_client_registration") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcClientRegistrationTable.columns.map { it.name }
            real.foreignKeys.isEmpty() shouldBe true
            entity.attributeByName("backchannel_logout_uri")?.nullable shouldBe true
        }

        test("oidc_client_redirect_uri table shape matches the real migrated schema and OidcClientRedirectUriTable 1:1") {
            val entity = model.entities.single { it.name == "oidc_client_redirect_uri" }
            val real = transaction { introspectOidcTable("oidc_client_redirect_uri") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcClientRedirectUriTable.columns.map { it.name }
            real.foreignKeys["client_registration_id"] shouldBe "oidc_client_registration"
            model.entityNameOf(entity.attributeByName("client_registration_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "oidc_client_registration"
        }

        test("oidc_authorization_code table shape matches the real migrated schema and OidcAuthorizationCodeTable 1:1") {
            val entity = model.entities.single { it.name == "oidc_authorization_code" }
            val real = transaction { introspectOidcTable("oidc_authorization_code") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcAuthorizationCodeTable.columns.map { it.name }
            real.foreignKeys["client_registration_id"] shouldBe "oidc_client_registration"
            real.foreignKeys["member_id"] shouldBe "member"
            entity.attributeByName("nonce")?.nullable shouldBe true
            entity.attributeByName("consumed_at")?.nullable shouldBe true
        }

        test("oidc_issued_token table shape matches the real migrated schema and OidcIssuedTokenTable 1:1") {
            val entity = model.entities.single { it.name == "oidc_issued_token" }
            val real = transaction { introspectOidcTable("oidc_issued_token") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcIssuedTokenTable.columns.map { it.name }
            real.foreignKeys["client_registration_id"] shouldBe "oidc_client_registration"
            real.foreignKeys["member_id"] shouldBe "member"
            entity.attributeByName("revoked_at")?.nullable shouldBe true
        }

        test("oidc_home_server_registration table shape matches the real migrated schema and OidcHomeServerRegistrationTable 1:1, no FK") {
            val entity = model.entities.single { it.name == "oidc_home_server_registration" }
            val real = transaction { introspectOidcTable("oidc_home_server_registration") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcHomeServerRegistrationTable.columns.map { it.name }
            real.foreignKeys.isEmpty() shouldBe true
        }

        test("oidc_rp_login_attempt table shape matches the real migrated schema and OidcRpLoginAttemptTable 1:1, NO member FK") {
            val entity = model.entities.single { it.name == "oidc_rp_login_attempt" }
            val real = transaction { introspectOidcTable("oidc_rp_login_attempt") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcRpLoginAttemptTable.columns.map { it.name }
            real.foreignKeys["home_server_registration_id"] shouldBe "oidc_home_server_registration"
            real.foreignKeys["member_id"] shouldBe null
            entity.attributeByName("consumed_at")?.nullable shouldBe true
        }

        test("oidc_guest_profile table shape matches the real migrated schema and OidcGuestProfileTable 1:1") {
            val entity = model.entities.single { it.name == "oidc_guest_profile" }
            val real = transaction { introspectOidcTable("oidc_guest_profile") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcGuestProfileTable.columns.map { it.name }
            real.foreignKeys["member_id"] shouldBe "member"
            entity.attributeByName("picture_url")?.nullable shouldBe true
            entity.attributeByName("membership_status")?.nullable shouldBe true
        }

        test(
            "oidc_guest_login_event table shape matches the real migrated schema and OidcGuestLoginEventTable 1:1 -- member_id has NO FK (deliberate)",
        ) {
            val entity = model.entities.single { it.name == "oidc_guest_login_event" }
            val real = transaction { introspectOidcTable("oidc_guest_login_event") }
            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.map { it.name } shouldContainExactlyInAnyOrder OidcGuestLoginEventTable.columns.map { it.name }

            // The pinned regression guard: member_id must NEVER gain a real FK constraint, in
            // either the real schema, the kUML model, or (structurally, by the hand-written
            // Table's own `uuid("member_id").nullable()` with no `.references(...)`) the Exposed
            // object.
            real.foreignKeys["member_id"] shouldBe null
            entity.attributeByName("member_id")?.foreignKey shouldBe null
            entity.attributeByName("member_id")?.nullable shouldBe true

            entity.attributeByName("event_type")?.type shouldBe
                ErmDataType.Enum(
                    name = "OidcLoginEventType",
                    values =
                        listOf(
                            "RP_LOGIN_SUCCESS",
                            "RP_LOGIN_FAILED",
                            "ISSUER_TOKEN_ISSUED",
                            "ISSUER_TOKEN_ISSUE_FAILED",
                            "BACKCHANNEL_LOGOUT_RECEIVED",
                            "BACKCHANNEL_LOGOUT_SENT",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.OidcLoginEventType",
                )
        }

        // account.oidc_issuer itself is modelled in 00-foundation.kuml.kts, not this file -- already
        // covered by SchemaDriftTest's "account table shape matches the real migrated schema" test
        // (exact column-name-set equality against the real, Flyway-migrated schema), which already
        // includes oidc_issuer since both the model and the real schema gained it together.
        test("account.oidc_issuer + oidc_subject composite unique index exists on the real migrated schema") {
            val hasCompositeUniqueIndex =
                transaction {
                    var found = false
                    exec(
                        """
                        SELECT 1
                        FROM information_schema.indexes
                        WHERE table_name = 'account'
                          AND index_name = 'uq_account_oidc_issuer_subject'
                          AND index_type_name = 'UNIQUE INDEX'
                        """.trimIndent(),
                    ) { rs -> found = rs.next() }
                    found
                }
            hasCompositeUniqueIndex shouldBe true
        }
    })

private data class IntrospectedOidcTable(
    val columns: Map<String, IntrospectedOidcColumn>,
    val foreignKeys: Map<String, String>,
    val primaryKeyColumns: Set<String>,
)

private data class IntrospectedOidcColumn(
    val nullable: Boolean,
)

private fun JdbcTransaction.introspectOidcTable(tableName: String): IntrospectedOidcTable {
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

    val columns = nullableByColumn.mapValues { (_, nullable) -> IntrospectedOidcColumn(nullable = nullable) }
    return IntrospectedOidcTable(columns = columns, foreignKeys = fkByColumn, primaryKeyColumns = pkColumns)
}
