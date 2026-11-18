package network.lapis.cloud.server.carpool

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
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
 *  (e) nothing in src/main builds an HTTP client for the carpool exchange (wave 1 transfers nothing).
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
                            it.name != "OrganizationSettingsTable.kt"
                    }.filter { writer.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
            offenders.shouldBeEmpty()
            // The store itself does write them (the scan is not vacuous).
            writer.containsMatchIn(SourceScan.blank(main.single { it.name == "CarpoolFederationStore.kt" }.readText())) shouldBe true
        }

        test("(a) CarpoolPostingTable inserts and updates elsewhere never set the consent (the default FALSE applies)") {
            val offenders =
                main
                    .filter { it.name != "CarpoolFederationStore.kt" }
                    .filter { Regex("""\bfederationOptIn\b""").containsMatchIn(SourceScan.blank(it.readText())) }
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

        test("(e) nothing in src/main builds an HTTP client for the carpool exchange") {
            val offenders =
                main
                    .filter { file ->
                        val code = SourceScan.blank(file.readText())
                        Regex("""Carpool""").containsMatchIn(code) && Regex("""\bHttpClient\b|federationHttpClient""").containsMatchIn(code)
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }

        val feedFiles = main.filter { "/server/carpool/feed/" in it.path.replace('\\', '/') }

        test("(f) the feed package has no outgoing call and fetches no key") {
            (feedFiles.size >= 5) shouldBe true
            val forbidden =
                Regex("""\bHttpClient\b|federationHttpClient|fetchActorDocument|PartnerFeedFetcher|resolveSenderKeyInfo|checkWebhookUrl""")
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
            main.filter { bodyLiteral in it.readText() }.map { it.name } shouldBe listOf("CarpoolTeaserFeedRoutes.kt")
            Regex(bodyLiteral).findAll(routes).count() shouldBe 1
            main
                .filter { it.name !in setOf("AuditLog.kt", "CarpoolTeaserFeedRoutes.kt") }
                .filter { Regex("""AuditEntityType\.CARPOOL_FEDERATION_FEED""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test(
            "the feed item carries exactly originHost, hops, maxHops and teaser, the envelope exactly schemaVersion, kind, complete and items",
        ) {
            fun names(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
                (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
            names(
                network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem
                    .serializer()
                    .descriptor,
            ) shouldBe
                listOf("originHost", "hops", "maxHops", "teaser")
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
                .map { it.name } shouldBe listOf("id", "teaser_id", "partner_id", "created_at", "expires_at")
        }

        test("(j) only CarpoolFederationStore and the table file reference CarpoolTeaserTombstoneTable") {
            main
                .filter { it.name !in setOf("CarpoolFederationStore.kt", "CarpoolTeaserTombstoneTable.kt") }
                .filter { Regex("""\bCarpoolTeaserTombstoneTable\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
            // ...and nobody else writes the moment of the consent.
            main
                .filter { it.name !in setOf("CarpoolFederationStore.kt", "CarpoolPostingTable.kt") }
                .filter { Regex("""\bfederationOptInAt\s*\]\s*=[^=]""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("(l) no log line in the carpool packages names a hint id") {
            main
                .filter { "/server/carpool/" in it.path.replace('\\', '/') }
                .flatMap { f -> f.readLines().filter { Regex("""logger\.""").containsMatchIn(it) }.map { f.name to it } }
                .filter { (_, line) -> Regex("""teaserId|ids\.of|\.of\(""").containsMatchIn(line) }
                .shouldBeEmpty()
        }

        test("(m) CarpoolWithdrawalKey.configure is called only in Application.kt") {
            main
                .filter { Regex("""CarpoolWithdrawalKey\s*\.\s*configure\s*\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name } shouldBe listOf("Application.kt")
        }
    })
