package network.lapis.cloud.server.carpool

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.shared.domain.PartnerCarpoolHintDto
import java.io.File

private val repoRoot: File =
    SourceScan
        .mainRoot()
        .absoluteFile.parentFile.parentFile.parentFile.parentFile

/**
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 -- structural guarantees of the foundation that a later edit must not lose without a failing build:
 *  (a) only [CarpoolFederationStore] writes the consent and the instance switch,
 *  (b) the public partner feed and the embed feeds never mention a carpool,
 *  (c) the consent and the switch are not part of the generic organisation settings wire types,
 *  (d) the ONE projection is called only by the consent UI of the client (and by the server in the opt-in check), never from a feed,
 *  (e) nothing in src/main builds an HTTP client for the carpool exchange (wave 1 transfers nothing; since V1.9.314 only the pull client does).
 * V1.9.312 (wave 2, the origin side of the teaser feed) adds:
 *  (f) the feed package makes no outgoing call and fetches no key (the key comes only from the pin),
 *  (g) the feed path is neither public nor embed and no public/embed file knows the feed,
 *  (h) the feed verifies against the server's OWN host and the constant path, never the request's Host header or path,
 *  (i) the refusal answer exists only in `respondUnavailable`, and the audit entry type of the feed is used only by the feed route.
 */
class CarpoolFederationTripwireTest :
    FunSpec({
        val main = SourceScan.mainFiles()

        test("(a) only CarpoolFederationStore writes federationOptIn and carpoolFederation*") {
            val writer =
                Regex(
                    """\b(federationOptIn|carpoolFederationEnabled|carpoolFederationHopLimit)\s*\]\s*=[^=]|\bit\s*\[\s*(federationOptIn|carpoolFederationEnabled|carpoolFederationHopLimit)\s*\]\s*=[^=]""",
                )
            val offenders =
                main
                    .filter {
                        it.name != "CarpoolFederationStore.kt" &&
                            it.name != "CarpoolPostingTable.kt" &&
                            it.name != "OrganizationSettingsTable.kt" &&
                            // V1.9.322: the auction exchange has its OWN `federationOptIn` column (table `auction`); its writer monopoly is
                            // pinned by AuctionFederationTripwireTest, not here.
                            it.name != "AuctionFederationStore.kt"
                    }.filter { writer.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
            offenders.shouldBeEmpty()
            // The store itself does write them (the scan is not vacuous).
            writer.containsMatchIn(SourceScan.blank(main.single { it.name == "CarpoolFederationStore.kt" }.readText())) shouldBe true
        }

        test("(a) CarpoolPostingTable inserts and updates elsewhere never set the consent (the default FALSE applies)") {
            val offenders =
                main
                    .filter {
                        it.name != "CarpoolFederationStore.kt" &&
                            it.name != "AuctionFederationStore.kt" &&
                            it.name != "AuctionTable.kt" &&
                            // V1.9.322 (step c2): the auction exchange READS its own `auction.federation_opt_in` (DTO, feed builder, withdrawal hooks);
                            // the writer monopoly of that column is pinned by AuctionFederationTripwireTest (a).
                            it.name != "AuctionService.kt" &&
                            it.name != "AuctionTeaserFeedBuilder.kt" &&
                            it.name != "AuctionWithdrawals.kt" &&
                            // V1.9.322 (c4): the GDPR export reads the consent of the seller.
                            it.name != "AuctionPersonalData.kt"
                    }.filter { Regex("""\bfederationOptIn\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            // Reads are fine (DTO building, export); the table object itself, the service (DTO read) and the export may mention the column.
            offenders.filter { it !in setOf("CarpoolPostingTable.kt", "CarpoolService.kt", "CarpoolPersonalData.kt") }.shouldBeEmpty()
        }

        test("(b) the partner feed and the embed feeds never mention a carpool") {
            val names =
                setOf(
                    "PartnerFeedBuilder.kt",
                    "PartnerFeedDtos.kt",
                    "PartnerFeedRoutes.kt",
                    "EmbedEventsFeedRoutes.kt",
                    "EmbedPartnerEventsFeed.kt",
                    "EmbedArticlesFeedRoutes.kt",
                    "EmbedProfilesFeedRoutes.kt",
                )
            names.forEach { name ->
                val code = SourceScan.blank(main.single { it.name == name }.readText())
                withClue(name) { Regex("""Carpool""", RegexOption.IGNORE_CASE).containsMatchIn(code) shouldBe false }
            }
        }

        test("(c) the organisation settings wire types do not carry the switch, the hop limit or the consent") {
            val shared = File(repoRoot, "lapis-shared/src/commonMain/kotlin")
            val files =
                shared
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            it.extension == "kt" &&
                            it.readText().contains("class OrganizationSettings")
                    }.toList()
            (files.isNotEmpty()) shouldBe true
            files.forEach { file ->
                val code = file.readText()
                withClue(file.name) { Regex("""carpoolFederation|federationOptIn""").containsMatchIn(code) shouldBe false }
            }
        }

        test(
            "(d) projectCarpoolTeaser is called by the client only in CarpoolFederationUi.kt, and by the server only in CarpoolService.kt and CarpoolTeaserFeedBuilder.kt",
        ) {
            val call = Regex("""\bprojectCarpoolTeaser\s*\(""")
            val client = File(repoRoot, "lapis-client/src/jsMain/kotlin")
            val clientUsers =
                client
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && call.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            clientUsers shouldBe setOf("CarpoolFederationUi.kt")
            val serverUsers = main.filter { call.containsMatchIn(SourceScan.blank(it.readText())) }.map { it.name }.toSet()
            serverUsers shouldBe setOf("CarpoolService.kt", "CarpoolTeaserFeedBuilder.kt")
        }

        test(
            "(e) V1.9.314: only carpool/pull/CarpoolTeaserFeedClient.kt builds an HTTP client for the carpool exchange, with one fixed GET",
        ) {
            val offenders =
                main
                    .filter { it.name != "CarpoolTeaserFeedClient.kt" }
                    // V1.9.322 c3: pinned separately by AuctionFederationTripwireTest (h) (its signing key type is named after the carpool exchange).
                    .filter { it.name != "AuctionTeaserFeedClient.kt" }
                    .filter { file ->
                        val code = SourceScan.blank(file.readText())
                        Regex("""Carpool""").containsMatchIn(code) && Regex("""\bHttpClient\b|federationHttpClient""").containsMatchIn(code)
                    }.map { it.name }
            offenders.shouldBeEmpty()
            val client = main.single { it.name == "CarpoolTeaserFeedClient.kt" }
            client.path.replace('\\', '/').contains("/server/carpool/pull/") shouldBe true
            val code = SourceScan.blank(client.readText())
            Regex("""\bclient\s*\.\s*get\s*\(""").findAll(code).count() shouldBe 1
            client.readText().contains("client.get(\"https://\$host\$CARPOOL_TEASER_FEED_PATH\")") shouldBe true
            Regex("""\b(post|put|patch|delete|submitForm)\s*\(""").containsMatchIn(code) shouldBe false
            code.contains("allowInsecureHttp = false") shouldBe true
            code.contains("allowInsecureHttp = true") shouldBe false
            Regex("""followRedirects\s*=\s*true""").containsMatchIn(code) shouldBe false
            Regex("""http://""").containsMatchIn(client.readText()) shouldBe false
        }

        test("(e2) the carpool pull client is constructed with its production defaults only, everywhere in src/main") {
            val offenders =
                main.flatMap { file ->
                    val code = SourceScan.blank(file.readText())
                    Regex("""(?<!class )CarpoolTeaserFeedClient\(\s*[^)\s]""").findAll(code).map { "${file.name}: ${it.value}" }.toList()
                }
            offenders.shouldBeEmpty()
        }

        test("(q) the pull poller is constructed and started only in Application.kt") {
            main
                .filter { it.name != "CarpoolPullPoller.kt" }
                .filter { Regex("""\bCarpoolPullPoller\s*\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name } shouldBe listOf("Application.kt")
        }

        test("(n2) CarpoolService reaches the received hints only through CarpoolReceivedStore.listForMembers") {
            val source = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/CarpoolService.kt").readText())
            Regex("""CarpoolReceivedStore\.(\w+)""").findAll(source).map { it.groupValues[1] }.toSet() shouldBe setOf("listForMembers")
        }

        test("(n3) the member-facing hint DTO has exactly the four pinned fields") {
            PartnerCarpoolHintDto.serializer().descriptor.let { d ->
                (0 until d.elementsCount).map { d.getElementName(it) }
            } shouldBe listOf("departureDate", "fromPostcodeArea", "toPostcodeArea", "originHost")
        }

        test("(r) the feed gate and the reach information both use the one predicate mayPullCarpoolFeed") {
            val root = File(SourceScan.mainRoot(), "network/lapis/cloud/server")
            listOf("carpool/feed/CarpoolTeaserFeedGate.kt", "rpc/CarpoolService.kt").forEach {
                withClue(it) { SourceScan.blank(File(root, it).readText()) shouldContain "mayPullCarpoolFeed(" }
            }
        }

        test(
            "(n) only CarpoolReceivedStore and the table files reference the received tables, and the columns are exactly the pinned set",
        ) {
            main
                .filter { it.name !in setOf("CarpoolReceivedStore.kt", "CarpoolReceivedHintTable.kt", "CarpoolReceivedTombstoneTable.kt") }
                .filter { Regex("""\bCarpoolReceived(Hint|Tombstone)Table\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
            CarpoolReceivedHintTable.columns.map { it.name } shouldBe
                listOf(
                    "id",
                    "teaser_id",
                    "origin_host",
                    "hops",
                    "max_hops",
                    "via_hosts",
                    "source_partner_id",
                    "departure_date",
                    "from_postcode_area",
                    "to_postcode_area",
                    "origin_signature",
                    "received_at",
                    "last_seen_at",
                    "expires_at",
                )
            CarpoolReceivedTombstoneTable.columns.map { it.name } shouldBe
                listOf("id", "teaser_id", "origin_host", "reported_by_partner_id", "created_at", "expires_at")
            val columns = (CarpoolReceivedHintTable.columns + CarpoolReceivedTombstoneTable.columns).map { it.name }
            columns.filter { Regex("""name|mail|phone|member|posting|place|note""").containsMatchIn(it) }.shouldBeEmpty()
        }

        test(
            "(o) the signed statement exists in exactly one place: the purpose literal is written once, only CarpoolOriginSignatures builds it",
        ) {
            val purpose = "lapis-carpool-teaser-origin/v1"
            main.filter { purpose in it.readText() }.map { it.name } shouldBe listOf("CarpoolOriginSignatures.kt")
            Regex(
                Regex.escape("\"" + purpose + "\""),
            ).findAll(main.single { it.name == "CarpoolOriginSignatures.kt" }.readText()).count() shouldBe
                1
            main
                .filter { Regex("""\bstatementBytes\s*\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                // V1.9.322: the auction statement has its own builder
                .toSet() shouldBe setOf("CarpoolOriginSignatures.kt", "AuctionOriginSignatures.kt")
        }

        test("(a) the pull poller reads the instance switch only through CarpoolFederationStore.settings()") {
            val poller = SourceScan.blank(main.single { it.name == "CarpoolPullPoller.kt" }.readText())
            poller.contains("OrganizationSettingsTable") shouldBe false
            poller.contains("CarpoolFederationStore.settings()") shouldBe true
        }

        val feedFiles = main.filter { "/server/carpool/feed/" in it.path.replace('\\', '/') }

        test("(f) the feed package has no outgoing call and fetches no key") {
            (feedFiles.size >= 5) shouldBe true
            val forbidden =
                Regex(
                    """\bHttpClient\b|federationHttpClient|fetchActorDocument|PartnerFeedFetcher|resolveSenderKeyInfo|checkWebhookUrl|carpool\.pull|CarpoolTeaserFeedClient""",
                )
            feedFiles.filter { forbidden.containsMatchIn(SourceScan.blank(it.readText())) }.map { it.name }.shouldBeEmpty()
        }

        test(
            "(g) the feed path is not under /api/public or /api/embed, differs from the partner feed path, and no public or embed file knows the feed",
        ) {
            val dtos = feedFiles.single { it.name == "CarpoolTeaserFeedDtos.kt" }.readText()
            val path = Regex("""CARPOOL_TEASER_FEED_PATH\s*=\s*"([^"]+)"""").find(dtos)!!.groupValues[1]
            path.startsWith("/api/public") shouldBe false
            path.startsWith("/api/embed") shouldBe false
            path shouldBe "/api/federation/carpool-feed/v1"
            (path != "/api/public/partner-feed/v1") shouldBe true
            main
                .filter { it.name.startsWith("Embed") || it.name.startsWith("Public") || it.name == "PartnerFeedRoutes.kt" }
                .filter { Regex("""CarpoolTeaserFeed|CARPOOL_TEASER_FEED""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(h) the feed route and gate never read the request's host or path") {
            listOf("CarpoolTeaserFeedRoutes.kt", "CarpoolTeaserFeedGate.kt").forEach { name ->
                val code = SourceScan.blank(feedFiles.single { it.name == name }.readText())
                withClue(name) {
                    Regex(
                        """request\.host\(|request\.path\(|request\.uri|HttpHeaders\.Host\b|origin\.serverHost""",
                    ).containsMatchIn(code) shouldBe
                        false
                }
            }
        }

        test("(i) the refusal body is written only in respondUnavailable; the feed audit type is used only by the feed route") {
            val routes = feedFiles.single { it.name == "CarpoolTeaserFeedRoutes.kt" }.readText()
            val bodyLiteral = "\"error\":\"unavailable\""
            // V1.9.322: the auction feed route has its OWN refusal writer with the very same bytes (pinned by AuctionFederationTripwireTest and
            // by AuctionTeaserFeedScenarios, which compare both answers); no other file may write the literal.
            main.filter { bodyLiteral in it.readText() }.map { it.name }.sorted() shouldBe
                listOf("AuctionTeaserFeedRoutes.kt", "CarpoolTeaserFeedRoutes.kt")
            Regex(bodyLiteral).findAll(routes).count() shouldBe 1
            Regex(bodyLiteral).findAll(main.single { it.name == "AuctionTeaserFeedRoutes.kt" }.readText()).count() shouldBe 1
            main
                .filter { it.name !in setOf("AuditLog.kt", "CarpoolTeaserFeedRoutes.kt") }
                .filter { Regex("""AuditEntityType\.CARPOOL_FEDERATION_FEED""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test(
            "the feed item carries exactly originHost, hops, maxHops, teaser, via and originSignature, the envelope exactly schemaVersion, kind, complete and items",
        ) {
            fun names(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
                (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
            names(
                network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem
                    .serializer()
                    .descriptor,
            ) shouldBe
                listOf("originHost", "hops", "maxHops", "teaser", "via", "originSignature")
            names(
                network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedV1
                    .serializer()
                    .descriptor,
            ) shouldBe
                listOf("schemaVersion", "kind", "complete", "items", "withdrawn")
            // V1.9.313 (k): a withdrawal is exactly the pseudonymous id and the day; the table exactly its five columns -- no member, trip or text.
            names(
                network.lapis.cloud.server.carpool.feed.CarpoolTeaserWithdrawal
                    .serializer()
                    .descriptor,
            ) shouldBe listOf("teaserId", "withdrawnAt")
            network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable.columns
                .map { it.name } shouldBe
                listOf("id", "teaser_id", "partner_id", "relay_origin_host", "relay_reporter_partner_id", "created_at", "expires_at")
        }

        test("(j) only CarpoolFederationStore and the table file reference CarpoolTeaserTombstoneTable") {
            main
                .filter { it.name !in setOf("CarpoolFederationStore.kt", "CarpoolTeaserTombstoneTable.kt") }
                .filter { Regex("""\bCarpoolTeaserTombstoneTable\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
            // ...and nobody else writes the moment of the consent.
            main
                .filter { it.name !in setOf("CarpoolFederationStore.kt", "CarpoolPostingTable.kt", "AuctionFederationStore.kt") }
                .filter { Regex("""\bfederationOptInAt\s*\]\s*=[^=]""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(l) no log line in the carpool packages names a hint id") {
            main
                .filter { "/server/carpool/" in it.path.replace('\\', '/') }
                .flatMap { f -> f.readLines().filter { Regex("""logger\.""").containsMatchIn(it) }.map { f.name to it } }
                .filter { (_, line) ->
                    Regex("""teaserId|ids\.of|\.of\(|signature|keyId|etag""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                }.shouldBeEmpty()
        }

        test("(m) CarpoolWithdrawalKey.configure is called only in Application.kt") {
            main
                .filter { Regex("""CarpoolWithdrawalKey\s*\.\s*configure\s*\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name } shouldBe listOf("Application.kt")
        }

        test("(m2) OrganizationRestoreService is the only caller of CarpoolFederationStore.withdrawAfterRestore") {
            main
                .filter { it.name != "CarpoolFederationStore.kt" }
                .filter { Regex("""CarpoolFederationStore\s*\.\s*withdrawAfterRestore""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name } shouldBe listOf("OrganizationRestoreService.kt")
        }

        test("(s) the pull tick deletes expired rows BEFORE it starts any pull") {
            val body = SourceScan.blank(main.single { it.name == "CarpoolPullPoller.kt" }.readText())
            val tick = body.substring(body.indexOf("suspend fun tick()"))
            val sweep = tick.indexOf("deleteExpired(")
            val tombstones = tick.indexOf("deleteExpiredTombstones(")
            val firstPull = listOf(tick.indexOf("pullOneSafely("), tick.indexOf("client.fetch(")).filter { it >= 0 }.min()
            (sweep in 0 until firstPull) shouldBe true
            (tombstones in 0 until firstPull) shouldBe true
            // and before the early return of a switched-off instance
            (sweep < tick.indexOf("!state.enabled")) shouldBe true
        }

        test("(t) apply never deletes the rows of another source: reject, never displace") {
            val src = SourceScan.blank(main.single { it.name == "CarpoolReceivedStore.kt" }.readText())
            val apply = src.substring(src.indexOf("fun apply("), src.indexOf("private class ReceivedTombstone"))
            Regex("""CarpoolReceivedHintTable\s*\.\s*deleteWhere[^\n]*sourcePartnerId\s*neq""").containsMatchIn(apply) shouldBe false
            // the one deletion of hints in apply is bound to the id of a row of THIS source or to the rows the withdrawal removes
            Regex("""sourcePartnerId\s*neq""").containsMatchIn(apply) shouldBe false
            apply.contains("CAPACITY") shouldBe true
        }

        test("(u) the number of concurrent feed builds is defined once, in CarpoolTeaserFeedLimits") {
            main
                // V1.9.322: the auction feed has its own limits object with its own constant (pinned by AuctionFederationTripwireTest).
                .filter { it.name != "CarpoolTeaserFeedLimits.kt" && it.name != "AuctionTeaserFeedLimits.kt" }
                .filter { Regex("""\bMAX_CONCURRENT_BUILDS\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
            Regex("""const val MAX_CONCURRENT_BUILDS\s*=\s*4\b""").containsMatchIn(
                main.single { it.name == "CarpoolTeaserFeedLimits.kt" }.readText(),
            ) shouldBe true
        }

        test("(v) the counters hold numbers only: no host, id or text is ever stored in CarpoolPullCounters") {
            val src = SourceScan.blank(main.single { it.name == "CarpoolReceivedStore.kt" }.readText())
            val counters = src.substring(src.indexOf("object CarpoolPullCounters"), src.indexOf("internal object CarpoolReceivedStore"))
            Regex("""\bString\b|MutableMap<String|HashMap<String|ConcurrentHashMap""").containsMatchIn(counters) shouldBe false
        }
    })
