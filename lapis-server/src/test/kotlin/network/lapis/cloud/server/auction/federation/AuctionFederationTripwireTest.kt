package network.lapis.cloud.server.auction.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import java.io.File

/**
 * V1.9.322 (steps c1, c2) -- source tripwires of the auction hint exchange. They grow with the later steps (feed, pull, RPC); today they pin the writer
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

        // ---------------------------------------------------------------------------------------------------------------------------------
        // V1.9.322 step c2
        // ---------------------------------------------------------------------------------------------------------------------------------

        fun File.code() = SourceScan.blank(readText())

        fun File.inFederationPackage() = "/server/auction/federation/" in path.replace('\\', '/')

        fun bodyOf(
            code: String,
            name: String,
        ): String {
            val start = Regex("""\bfun\s+$name\s*\(""").find(code)?.range?.first ?: error("function $name not found")
            var i = code.indexOf('{', start)
            val open = i
            var depth = 0
            while (i < code.length) {
                when (code[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return code.substring(open, i + 1)
                    }
                }
                i++
            }
            error("unbalanced braces in $name")
        }

        test("(b3) only PartnerServerStore writes partner_server.auction_*") {
            val writer = Regex("""\b(auctionSharing|auctionFeedServedAt|auctionFeedServedCount|auctionFeedLastItems)\s*\]\s*=[^=]""")
            main
                .filter { it.name != "PartnerServerStore.kt" && it.name != "PartnerServerTable.kt" }
                .filter { writer.containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
            writer.containsMatchIn(main.single { it.name == "PartnerServerStore.kt" }.code()) shouldBe true
        }

        test("(f) each purpose literal exists exactly once and the origin statement is built only in AuctionOriginSignatures") {
            val ids = main.filter { "lapis-auction-teaser-id/v1" in it.readText() }
            ids.map { it.name } shouldBe listOf("AuctionTeaserIds.kt")
            Regex("lapis-auction-teaser-id/v1").findAll(ids.single().readText()).count() shouldBe 1
            val origin = main.filter { "lapis-auction-teaser-origin/v1" in it.readText() }
            origin.map { it.name } shouldBe listOf("AuctionOriginSignatures.kt")
            Regex("lapis-auction-teaser-origin/v1").findAll(origin.single().readText()).count() shouldBe 1
            main
                .filter { it.name != "AuctionOriginSignatures.kt" }
                .filter { Regex("""AuctionOriginSignatures\s*\.\s*statementBytes""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(g) no server file builds an AuctionTeaser: the projection in lapis-shared is the only producer") {
            main
                .filter { Regex("""(?<![A-Za-z.])AuctionTeaser\s*\(""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(h) the feed path is private, differs from the carpool path, and the federation package makes no outgoing call") {
            val dtos = main.single { it.name == "AuctionTeaserFeedDtos.kt" }.readText()
            Regex("""AUCTION_TEASER_FEED_PATH\s*=\s*"([^"]+)"""").find(dtos)!!.groupValues[1] shouldBe "/api/federation/auction-feed/v1"
            "/api/federation/auction-feed/v1".startsWith("/api/public") shouldBe false
            main
                .filter { Regex("""Public|Embed""").containsMatchIn(it.name) }
                .filter { "auction-feed" in it.readText() || "AUCTION_TEASER_FEED_PATH" in it.readText() }
                .map { it.name }
                .shouldBeEmpty()
            ("/api/federation/auction-feed/v1" == "/api/federation/carpool-feed/v1") shouldBe false
            main
                .filter { it.inFederationPackage() }
                .filter { Regex("""HttpClient|checkWebhookUrl|java\.net\.http|URL\(|openConnection""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
            // the route and the gate never read the host or the path of the request
            listOf("AuctionTeaserFeedRoutes.kt", "AuctionTeaserFeedGate.kt").forEach { name ->
                val code = main.single { it.name == name }.code()
                Regex("""request\s*\.\s*(host|path|uri|local|port)\b|HttpHeaders\.Host|requestURI""").containsMatchIn(code) shouldBe false
            }
        }

        test("(i) the feed audit type is used only by the feed route") {
            main
                .filter { it.name !in setOf("AuditLog.kt", "AuctionTeaserFeedRoutes.kt") }
                .filter { Regex("""AuditEntityType\.AUCTION_FEDERATION_FEED""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(j) the withdrawal hooks sit exactly where the auction leaves the shareable state") {
            val service = main.single { it.name == "AuctionService.kt" }.code()

            fun hooks(name: String) = Regex("""AuctionWithdrawals\s*\.""").findAll(bodyOf(service, name)).count()
            hooks("buyNowFiat") shouldBe 1
            hooks("settleFiatAuctionLocked") shouldBe 2
            hooks("acceptOffer") shouldBe 1
            hooks("disableAuction") shouldBe 1
            hooks("setAuctionFiatEnabled") shouldBe 1
            hooks("takeBackFederationConsent") shouldBe 1
            Regex("""AuctionWithdrawals\s*\.\s*onSwitchedToLtr""").findAll(bodyOf(service, "updateAuctionTerms")).count() shouldBe 1
            // nowhere else in the file
            Regex("""AuctionWithdrawals\s*\.""").findAll(service).count() shouldBe 8
            val partners = main.single { it.name == "PartnerServerService.kt" }.code()
            Regex("""AuctionWithdrawals\s*\.\s*onInstanceOff""").findAll(bodyOf(partners, "setAuctionFederationSettings")).count() shouldBe
                1
            Regex("""AuctionWithdrawals\s*\.\s*onPartnerOff""").findAll(bodyOf(partners, "setPartnerAuctionSharing")).count() shouldBe 1
            Regex("""AuctionWithdrawals\s*\.""").findAll(partners).count() shouldBe 2
            // every other caller is the feed builder's lazy sweep
            main
                .filter {
                    it.name !in
                        setOf("AuctionService.kt", "PartnerServerService.kt", "AuctionWithdrawals.kt", "AuctionTeaserFeedBuilder.kt")
                }.filter { Regex("""AuctionWithdrawals\s*\.""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(k) one predicate decides which partner may pull the feed, and it holds back a changed signing key") {
            main.single { it.name == "AuctionFeedRecipients.kt" }.code().contains("signingKeyChangedAt == null") shouldBe true
            main.single { it.name == "AuctionTeaserFeedGate.kt" }.code().contains("mayPullAuctionFeed") shouldBe true
            bodyOf(
                main.single { it.name == "AuctionService.kt" }.code(),
                "getAuctionFederationReach",
            ).contains("mayPullAuctionFeed") shouldBe
                true
        }

        test("(l) the key is configured only by Application, which wires the route and the module limiter and starts no poller") {
            main
                .filter { it.name !in setOf("Application.kt", "AuctionTeaserIds.kt") }
                .filter { Regex("""AuctionFederationKey\s*\.\s*configure""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
            val app = main.single { it.name == "Application.kt" }.code()
            app.contains("AuctionFederationKey.configure(auctionTeaserIds)") shouldBe true
            app.contains("registerAuctionTeaserFeedRoutes(") shouldBe true
            app.contains("hintResolveLimiter = auctionHintResolveRateLimiter") shouldBe true
            main
                .filter { Regex("""Auction\w*Poller""").containsMatchIn(it.code()) }
                .filter { !it.name.startsWith("AuctionPaymentDetails") && it.name != "Application.kt" }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(m) no bidding, buying or offer method looks at the hint id") {
            val service = main.single { it.name == "AuctionService.kt" }.code()
            listOf(
                "placeBid",
                "placeFiatBid",
                "buyNow",
                "makeOffer",
                "counterOffer",
                "acceptOffer",
                "declineOffer",
                "withdrawOffer",
                "settleAuction",
                "confirmPaymentReceived",
            ).forEach { name ->
                val body = if (Regex("""\bfun\s+$name\s*\(""").containsMatchIn(service)) bodyOf(service, name) else ""
                Regex("""snapshotByTeaserId|teaserId|AuctionTeaser""").containsMatchIn(body) shouldBe false
            }
            // the id is resolved by exactly one RPC method
            main
                .filter { Regex("""snapshotByTeaserId""").containsMatchIn(it.code()) }
                .map { it.name }
                .sorted() shouldBe listOf("AuctionFederationStore.kt", "AuctionService.kt")
            Regex("""snapshotByTeaserId""").findAll(service).count() shouldBe 1
        }

        test("(n) the offers badge has one switch and one producer") {
            main.filter { "OFFERS_BADGE_SHARED" in it.readText() }.map { it.name }.shouldBeEmpty()
            val shared =
                File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/AuctionFederation.kt").let {
                    if (it.exists()) {
                        it
                    } else {
                        File(
                            "lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/AuctionFederation.kt",
                        )
                    }
                }
            val code = shared.code()
            Regex("""OFFERS_BADGE_SHARED""").findAll(code).count() shouldBe 2
            Regex("""fun offersPossible\([^)]*\)\s*:\s*Boolean\s*=\s*OFFERS_BADGE_SHARED""").containsMatchIn(code) shouldBe true
        }

        test("(u2) the number of concurrent auction feed builds is defined once, in AuctionTeaserFeedLimits") {
            main
                .filter { it.name != "AuctionTeaserFeedLimits.kt" && it.name != "CarpoolTeaserFeedLimits.kt" }
                .filter { Regex("""\bMAX_CONCURRENT_BUILDS\b""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
            Regex("""const val MAX_CONCURRENT_BUILDS\s*=\s*4\b""").containsMatchIn(
                main.single { it.name == "AuctionTeaserFeedLimits.kt" }.readText(),
            ) shouldBe true
        }

        test("(d2) no log line in the throttle or the withdrawal code derives or names an id") {
            main
                .filter {
                    it.name in
                        setOf(
                            "AuctionTeaserSnapshots.kt",
                            "AuctionWithdrawals.kt",
                            "AuctionTeaserFeedBuilder.kt",
                            "AuctionTeaserFeedRoutes.kt",
                        )
                }.flatMap { f -> f.readLines().filter { it.contains("logger.") }.map { f.name to it } }
                .filter { (_, line) -> Regex("""\.of\(|ids|auctionId|\$\{it\}""").containsMatchIn(line) }
                .shouldBeEmpty()
        }
    })
