package network.lapis.cloud.server.db

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * `V169__auction_federation.sql` (H2): every switch defaults to OFF, the hop limit to 1, and the CHECK constraints hold. The statements that must
 * fail run in a transaction that is never committed. The PostgreSQL variant of the SQL is the same file.
 */
class AuctionFederationMigrationTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        fun columnInfo(
            table: String,
            column: String,
            field: String,
        ): String =
            transaction {
                var out = ""
                exec(
                    "SELECT $field FROM information_schema.columns WHERE table_name = '$table' AND column_name = '$column'",
                ) { rs ->
                    rs.next()
                    out = rs.getString(1)
                }
                out
            }

        test("organization_settings defaults: exchange off, hop limit 1") {
            columnInfo("organization_settings", "auction_federation_hop_limit", "column_default") shouldBe "1"
            columnInfo("organization_settings", "auction_federation_enabled", "column_default").uppercase() shouldBe "FALSE"
        }

        test("auction.federation_opt_in is NOT NULL, the timestamp is nullable") {
            columnInfo("auction", "federation_opt_in", "is_nullable") shouldBe "NO"
            columnInfo("auction", "federation_opt_in_at", "is_nullable") shouldBe "YES"
        }

        test("a hop limit of 3 is rejected by the CHECK (the exchange allows at most two hops)") {
            shouldThrowAny {
                transaction { exec("UPDATE organization_settings SET auction_federation_hop_limit = 3") }
            }
        }

        test("a hint whose buy-now price is not above its price is rejected") {
            val partner = UUID.randomUUID()
            val partnerSql =
                "INSERT INTO partner_server (id, host, created_at, next_fetch_at) VALUES ('$partner', 'a.example', " +
                    "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')"
            val hintSql =
                "INSERT INTO auction_received_hint (id, teaser_id, origin_host, hops, max_hops, source_partner_id, revision, title, " +
                    "currency, price_kind, price_minor, buy_now_minor, offers_possible, ends_at, price_as_of, origin_signature, " +
                    "received_at, last_seen_at, expires_at) VALUES ('${UUID.randomUUID()}', '${"a".repeat(64)}', 'b.example', 1, 1, " +
                    "'$partner', 1, 't', 'EUR', 'START', 1000, 1000, FALSE, TIMESTAMP '2030-01-02 00:00:00', " +
                    "TIMESTAMP '2030-01-01 00:00:00', 'sig', TIMESTAMP '2030-01-01 00:00:00', TIMESTAMP '2030-01-01 00:00:00', " +
                    "TIMESTAMP '2030-01-02 00:00:00')"
            shouldThrowAny {
                transaction {
                    exec(partnerSql)
                    exec(hintSql)
                }
            }
        }

        test("the audit entity type CHECK accepts AUCTION_FEDERATION_FEED") {
            val count =
                transaction {
                    var n = 0
                    exec(
                        "SELECT COUNT(*) FROM information_schema.check_constraints " +
                            "WHERE UPPER(constraint_name) = 'CHK_AUDIT_LOG_ENTRY_ENTITY_TYPE' " +
                            "AND UPPER(check_clause) LIKE '%AUCTION_FEDERATION_FEED%'",
                    ) { rs ->
                        rs.next()
                        n = rs.getInt(1)
                    }
                    n
                }
            count shouldBe 1
        }
    })
