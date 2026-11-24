package network.lapis.cloud.server.auction.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable

/**
 * V1.9.322 (step c1) -- source tripwires of the auction hint exchange. They grow with the later steps (feed, pull, RPC); today they pin the writer
 * monopolies of the foundation and the rule that this package never touches the LTR ledger (the Euro path moves no LTR, see
 * `AuctionFiatLedgerTripwireTest`).
 */
class AuctionFederationTripwireTest :
    FunSpec({
        val main = SourceScan.mainFiles()

        test("(a) only AuctionFederationStore writes auction.federation_opt_in(_at) and organization_settings.auction_federation_*") {
            val writer =
                Regex(
                    """\b(federationOptIn|federationOptInAt|auctionFederationEnabled|auctionFederationHopLimit)\s*\]\s*=[^=]""",
                )
            main
                .filter { it.name != "AuctionFederationStore.kt" }
                .filter { writer.containsMatchIn(SourceScan.blank(it.readText())) }
                // The carpool store writes ITS OWN `federationOptIn` column (table carpool_posting), pinned by CarpoolFederationTripwireTest.
                .filter { it.name != "CarpoolFederationStore.kt" }
                .map { it.name }
                .shouldBeEmpty()
            // The store itself does write them (the scan is not vacuous).
            writer.containsMatchIn(SourceScan.blank(main.single { it.name == "AuctionFederationStore.kt" }.readText())) shouldBe true
        }

        test("(b) the four exchange tables are referenced only by their generated file and (later) their stores") {
            val tables =
                setOf(
                    "AuctionTeaserSnapshotTable",
                    "AuctionTeaserTombstoneTable",
                    "AuctionReceivedHintTable",
                    "AuctionReceivedTombstoneTable",
                )
            val allowed =
                tables.map { "$it.kt" }.toSet() +
                    setOf("AuctionFederationStore.kt", "AuctionReceivedStore.kt", "OrganizationSchemaCatalog.kt")
            main
                .filter { it.name !in allowed }
                .filter { f -> tables.any { Regex("""\b$it\b""").containsMatchIn(SourceScan.blank(f.readText())) } }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(c) the federation package never mentions the LTR ledger") {
            main
                .filter { "/server/auction/federation/" in it.path.replace('\\', '/') }
                .filter { Regex("""LtrLedger|ltr_ledger""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(c2) OrganizationRestoreService is the only caller of AuctionFederationStore.resetAfterRestore") {
            val callers =
                main
                    .filter { it.name != "AuctionFederationStore.kt" }
                    .filter {
                        Regex("""AuctionFederationStore\s*\.\s*resetAfterRestore""").containsMatchIn(SourceScan.blank(it.readText()))
                    }.map { it.name }
            callers shouldBe listOf("OrganizationRestoreService.kt")
        }

        test("(d) no log line in the federation package names a title, a price or a hint id") {
            main
                .filter { "/server/auction/federation/" in it.path.replace('\\', '/') }
                .flatMap { f -> f.readLines().filter { it.contains("logger.") }.map { f.name to it } }
                .filter { (_, line) ->
                    Regex("""title|priceMinor|buyNow|teaserId|teaser_id""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                }.shouldBeEmpty()
        }

        test("(e) the table columns of the four tables are pinned (a new column is a reviewed decision)") {
            AuctionTeaserSnapshotTable.columns.map { it.name } shouldBe
                listOf("auction_id", "teaser_id", "revision", "price_kind", "price_minor", "price_as_of", "content_sha256", "updated_at")
            AuctionTeaserTombstoneTable.columns.map { it.name } shouldBe
                listOf("id", "teaser_id", "partner_id", "created_at", "expires_at", "relay_origin_host", "relay_reporter_partner_id")
            AuctionReceivedTombstoneTable.columns.map { it.name } shouldBe
                listOf("id", "teaser_id", "origin_host", "kind", "reported_by_partner_id", "created_at", "expires_at")
            AuctionReceivedHintTable.columns.map { it.name } shouldBe
                listOf(
                    "id",
                    "teaser_id",
                    "origin_host",
                    "hops",
                    "max_hops",
                    "via_hosts",
                    "source_partner_id",
                    "revision",
                    "title",
                    "currency",
                    "price_kind",
                    "price_minor",
                    "buy_now_minor",
                    "offers_possible",
                    "ends_at",
                    "price_as_of",
                    "origin_signature",
                    "received_at",
                    "last_seen_at",
                    "expires_at",
                )
        }
    })
