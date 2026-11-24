package network.lapis.cloud.server.auction.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.shared.domain.PartnerAuctionHintDto
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
            // c4-b: the same for the tombstones that tell the partners about a restore
            main
                .filter { it.name != "AuctionFederationStore.kt" }
                .filter { Regex("""withdrawAfterRestore""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name } shouldBe listOf("OrganizationRestoreService.kt")
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
            val writer =
                Regex(
                    """\b(auctionSharing|auctionFeedServedAt|auctionFeedServedCount|auctionFeedLastItems|auctionPull\w*)\s*\]\s*=[^=]""",
                )
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

        test("(g) no server file builds an AuctionTeaser: the projection in lapis-shared is the only producer (c3: one exception)") {
            // AuctionReceivedStore rebuilds a RECEIVED, origin-signed hint from its stored columns, so that it is handed on exactly as signed. Every
            // other file only ever gets a teaser from the projection or from a decoded feed. (The rebuild is checked against the signature in a test.)
            main
                .filter { it.name != "AuctionReceivedStore.kt" }
                .filter { Regex("""(?<![A-Za-z.])AuctionTeaser\s*\(""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
            Regex(
                """(?<![A-Za-z.])AuctionTeaser\s*\(""",
            ).findAll(main.single { it.name == "AuctionReceivedStore.kt" }.code()).count() shouldBe
                1
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
            // c3: exactly ONE file of the federation package opens an outgoing connection: the pull client (pinned below).
            main
                .filter { it.inFederationPackage() }
                .filter { it.name != "AuctionTeaserFeedClient.kt" }
                .filter { Regex("""HttpClient|checkWebhookUrl|java\.net\.http|URL\(|openConnection""").containsMatchIn(it.code()) }
                .map { it.name }
                .shouldBeEmpty()
            val client = main.single { it.name == "AuctionTeaserFeedClient.kt" }
            client.path.replace('\\', '/').contains("/server/auction/federation/pull/") shouldBe true
            val clientCode = client.code()
            Regex("""\bclient\s*\.\s*get\s*\(""").findAll(clientCode).count() shouldBe 1
            client.readText().contains("client.get(\"https://\$host\$AUCTION_TEASER_FEED_PATH\")") shouldBe true
            Regex("""\b(post|put|patch|delete|submitForm)\s*\(""").containsMatchIn(clientCode) shouldBe false
            clientCode.contains("allowInsecureHttp = false") shouldBe true
            clientCode.contains("allowInsecureHttp = true") shouldBe false
            Regex("""followRedirects\s*=\s*true""").containsMatchIn(clientCode) shouldBe false
            Regex("""http://""").containsMatchIn(client.readText()) shouldBe false
            // outside the federation package nothing builds a client for the auction path
            main
                .filter { !it.inFederationPackage() }
                .filter {
                    Regex("""AUCTION_TEASER_FEED_PATH""").containsMatchIn(it.code()) &&
                        Regex("""HttpClient|federationHttpClient""").containsMatchIn(it.code())
                }.map { it.name }
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
                        setOf(
                            "AuctionService.kt",
                            "PartnerServerService.kt",
                            "AuctionWithdrawals.kt",
                            "AuctionTeaserFeedBuilder.kt",
                            // c3: the pull tick runs the same bounded consent sweep as the feed build.
                            "AuctionPullPoller.kt",
                            // c4: the GDPR erasure of a seller withdraws his hints first.
                            "AuctionPersonalData.kt",
                        )
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
            // c3: the one poller of the exchange is built and started only here (and defined in its own file).
            main
                .filter { Regex("""Auction\w*Poller""").containsMatchIn(it.code()) }
                .filter { !it.name.startsWith("AuctionPaymentDetails") && it.name != "Application.kt" && it.name != "AuctionPullPoller.kt" }
                .map { it.name }
                .shouldBeEmpty()
            main
                .filter { Regex("""(?<!class )\bAuctionPullPoller\s*\(""").containsMatchIn(it.code()) }
                .map { it.name }
                .sorted() shouldBe listOf("Application.kt")
            Regex("""(?<!class )\bAuctionPullPoller\s*\(""").findAll(app).count() shouldBe 1
            app.contains("auctionPullPoller.start()") shouldBe true
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

        // ---------------------------------------------------------------------------------------------------------------------------------
        // V1.9.322 step c3: the receiving side
        // ---------------------------------------------------------------------------------------------------------------------------------

        test("(c3-a) AuctionReceivedStore is called only by the RPC services, the pull poller, the feed builder and the partner stores") {
            main
                .filter { it.name != "AuctionReceivedStore.kt" }
                .filter { Regex("""AuctionReceivedStore\s*\.""").containsMatchIn(it.code()) }
                .map { it.name }
                .sorted() shouldBe
                listOf(
                    "AuctionPullPoller.kt",
                    "AuctionService.kt",
                    "AuctionTeaserFeedBuilder.kt",
                    "PartnerDiscoveryStore.kt",
                    "PartnerServerService.kt",
                    "PartnerServerStore.kt",
                )
            // AuctionService reaches it by exactly two RPC methods
            val service = main.single { it.name == "AuctionService.kt" }.code()
            Regex("""AuctionReceivedStore\s*\.\s*(\w+)""").findAll(service).map { it.groupValues[1] }.toSet() shouldBe
                setOf("listForMembers", "sourceOf", "hide")
            bodyOf(service, "listPartnerAuctionHints").contains("AuctionReceivedStore.listForMembers") shouldBe true
            bodyOf(service, "hidePartnerAuctionHint").contains("AuctionReceivedStore.hide") shouldBe true
        }

        test(
            "(c3-b) the member list is gated by an ACTIVE membership BEFORE the page check, the hide by ADMIN, and the audit is the last write",
        ) {
            val service = main.single { it.name == "AuctionService.kt" }.code()
            val list = bodyOf(service, "listPartnerAuctionHints")
            val gate = list.indexOf("requireActiveMembership")
            val page = list.indexOf("MAX_PAGE")
            (gate in 0 until page) shouldBe true
            val hide = bodyOf(service, "hidePartnerAuctionHint")
            hide.contains("requireRole(AccountRole.ADMIN)") shouldBe true
            hide.contains("requireActiveMembership") shouldBe true
            // lock order: the partner row of the source before the received row; the audit entry after the hide
            (hide.indexOf("lockPartner") in 0 until hide.indexOf("AuctionReceivedStore.hide")) shouldBe true
            (hide.indexOf("AuctionReceivedStore.hide") < hide.indexOf("AuditLogRecorder.record")) shouldBe true
            // the audit never names a title or an amount
            hide.contains("title") shouldBe false
            hide.contains("priceMinor") shouldBe false
        }

        test("(c3-c) the pull client and the poller are built with their production defaults only") {
            main
                .flatMap { f ->
                    Regex(
                        """(?<!class )(AuctionTeaserFeedClient|AuctionPullPoller)\(\s*[^)\s]""",
                    ).findAll(f.code()).map { "${f.name}: ${it.value}" }
                }.shouldBeEmpty()
        }

        test("(c3-d) the reader decodes with its OWN strict Json and pins the key sets") {
            val reader = main.single { it.name == "AuctionTeaserFeedReader.kt" }.code()
            reader.contains("ignoreUnknownKeys = false") shouldBe true
            reader.contains("ignoreUnknownKeys = true") shouldBe false
            reader.contains("AUCTION_TEASER_FEED_JSON") shouldBe false
        }

        test("(c3-e) the received hint DTO has exactly the ten pinned fields") {
            PartnerAuctionHintDto.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } } shouldBe
                listOf(
                    "originHost",
                    "teaserId",
                    "title",
                    "currency",
                    "priceKind",
                    "priceMinor",
                    "priceAsOf",
                    "buyNowMinor",
                    "offersPossible",
                    "endsAt",
                )
        }

        test("(c3-f) a HIDDEN block is never deleted by the withdrawal paths: every delete of a block names the kind") {
            val code = main.single { it.name == "AuctionReceivedStore.kt" }.code()
            val del = bodyOf(code, "clearWithdrawnBlock")
            del.contains("KIND_WITHDRAWN") shouldBe true
        }

        test("(c4-a) the GDPR erasure of a member withdraws his shared hints BEFORE any row of the member is changed") {
            val code = main.single { it.name == "AuctionPersonalData.kt" }.code()
            val body = bodyOf(code, "eraseMember")
            val first = body.indexOf("AuctionWithdrawals.onSellerErased")
            (first >= 0) shouldBe true
            listOf("deleteWhere", ".update(", "AuctionTable.update").forEach { call ->
                val at = body.indexOf(call)
                if (at >= 0) (first < at) shouldBe true
            }
        }

        test("(c4-c) the member-exit sweep filters the seller in the database (join), not after a limited scan") {
            val body = bodyOf(main.single { it.name == "AuctionFederationStore.kt" }.code(), "optedInNoLongerShareable")
            body.contains("MemberTable") shouldBe true
            body.contains("JoinType.INNER") shouldBe true
            // the old shape: a limited scan of open auctions followed by an in-memory filter on active sellers
            body.contains("activeSellers(") shouldBe false
        }

        test("(c4-d) only AuctionFederationStore touches the snapshot table (insert, update, delete)") {
            main
                .filter { it.name != "AuctionFederationStore.kt" && it.name != "AuctionTeaserSnapshotTable.kt" }
                .filter { Regex("""AuctionTeaserSnapshotTable\b""").containsMatchIn(it.code()) }
                .map { it.name }
                .filter { it != "OrganizationSchemaCatalog.kt" }
                .shouldBeEmpty()
        }

        test("(c4-e) no audit snapshot of the federation code carries an amount") {
            val sinks = Regex("""(PartnerAuditSnapshot|AuctionFederationFeedAuditSnapshot|AuctionFiatAuditSnapshot)\s*\(([^)]*)\)""")
            val amount = Regex("""price|Minor|buyNow|amount""", RegexOption.IGNORE_CASE)
            val files =
                main.filter {
                    it.inFederationPackage() ||
                        it.name in setOf("AuctionService.kt", "PartnerServerService.kt", "AuctionTeaserFeedRoutes.kt")
                }
            // Only the federation package and the two partner/feed audit types are scanned for amounts: a fiat audit in AuctionService
            // legitimately carries the currency and the status, never an amount either.
            files
                .flatMap { f -> sinks.findAll(f.code()).map { f.name to it.groupValues[2] }.toList() }
                .filter { (_, args) -> amount.containsMatchIn(args) }
                .shouldBeEmpty()
        }

        test("(c4-f) everything is off by default: V169 pins DEFAULT FALSE, hop limit 1, range 1..2, and the code caps at 2") {
            val sql =
                SourceScan
                    .mainRoot()
                    .parentFile
                    .resolve("resources/db/migration")
                    .listFiles()!!
                    .single {
                        it.name.startsWith("V169__")
                    }.readText()
            Regex(
                """auction_federation_enabled\s+BOOLEAN\s+NOT NULL\s+DEFAULT\s+FALSE""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(sql) shouldBe
                true
            Regex("""federation_opt_in\s+BOOLEAN\s+NOT NULL\s+DEFAULT\s+FALSE""", RegexOption.IGNORE_CASE).containsMatchIn(sql) shouldBe
                true
            Regex("""auction_sharing\s+BOOLEAN\s+NOT NULL\s+DEFAULT\s+FALSE""", RegexOption.IGNORE_CASE).containsMatchIn(sql) shouldBe true
            Regex("""DEFAULT\s+1\b""").containsMatchIn(sql) shouldBe true
            Regex("""BETWEEN\s+1\s+AND\s+2""", RegexOption.IGNORE_CASE).containsMatchIn(sql) shouldBe true
            network.lapis.cloud.shared.domain.AuctionFederationLimits.MAX_HOPS shouldBe 2
        }

        test("(c4-g) no federation route is mounted under /api/public or /api/embed, and the application mounts the feed route") {
            val app = main.single { it.name == "Application.kt" }.code()
            app.contains("registerAuctionTeaserFeedRoutes") shouldBe true
            main
                .filter { Regex("""auction-feed""").containsMatchIn(it.readText()) }
                .filter { Regex("""/api/(public|embed)[^"]*auction-feed""").containsMatchIn(it.readText()) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(c4-h) a received hint in LTR is refused twice: by the validator and again when the members are served") {
            main.single { it.name == "AuctionTeaserValidator.kt" }.code().contains("AuctionCurrencies.LTR") shouldBe true
            main.single { it.name == "AuctionReceivedStore.kt" }.code().contains("AuctionCurrencies.LTR") shouldBe true
        }
    })
