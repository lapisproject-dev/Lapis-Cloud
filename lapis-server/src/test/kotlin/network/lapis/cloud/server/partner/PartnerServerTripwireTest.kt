package network.lapis.cloud.server.partner

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.IPartnerServerService

/**
 * V1.9.244 -- structural guarantees of the partner feature that a later edit must not lose without a failing build:
 *  - every RPC method starts with the ADMIN gate (the list steers outgoing requests),
 *  - [PartnerFeedFetcher] is the only place that opens a connection and it is only ever constructed with its production defaults,
 *  - the stored partner list of a partner is read back ONLY by the two stores and the parser (V1.9.245: the recursion is the one consumer),
 *  - only [PartnerServerStore] and [PartnerDiscoveryStore] write the partner tables,
 *  - the recursion runner is referenced only by the poller, and the feed builder / embed feed never touch the cache.
 */
class PartnerServerTripwireTest :
    FunSpec({
        val methods =
            setOf(
                "list",
                "probeAndSave",
                "setActive",
                "remove",
                "listBlocklist",
                "addBlock",
                "removeBlock",
                "setPartnerListHidden",
                "getPartnerRecursionSettings",
                "setPartnerRecursionSettings",
                "listDiscoveredPartnerServers",
            )
        val partnerFiles = SourceScan.mainFiles().filter { "/server/partner/" in it.path }

        test("the RPC surface is exactly the eleven known methods") {
            IPartnerServerService::class.java.declaredMethods
                .filter { !it.isSynthetic && '$' !in it.name }
                .map { it.name }
                .toSet() shouldBe methods
        }

        test("every RPC method of the service starts with resolveCurrentMember(call) and the ADMIN gate") {
            val code = SourceScan.blank(SourceScan.mainFiles().single { it.name == "PartnerServerService.kt" }.readText())
            val headers = Regex("""override suspend fun (\w+)\([^)]*\)\s*(?::\s*[\w<>?, ]+)?\s*\{""").findAll(code).toList()
            headers.map { it.groupValues[1] }.toSet() shouldBe methods
            val offenders =
                headers.filterNot { m ->
                    val lines =
                        code
                            .substring(m.range.last + 1)
                            .trimStart()
                            .lineSequence()
                            .map { it.trim() }
                            .toList()
                    (lines[0] == "resolveCurrentMember(call).requireRole(AccountRole.ADMIN)") ||
                        (lines[0] == "val current = resolveCurrentMember(call)" && lines[1] == "current.requireRole(AccountRole.ADMIN)")
                }
            offenders.map { it.groupValues[1] }.shouldBeEmpty()
        }

        test("PartnerFeedFetcher is constructed with its production defaults only, everywhere in src/main") {
            val offenders =
                SourceScan.mainFiles().flatMap { file ->
                    val code = SourceScan.blank(file.readText())
                    Regex("""(?<!class )PartnerFeedFetcher\(\s*[^)\s]""").findAll(code).map { "${file.name}: ${it.value}" }.toList()
                }
            offenders.shouldBeEmpty()
        }

        test(
            "no file of the partner package except the fetcher builds an HTTP client, and nothing there opens a transaction around a fetch",
        ) {
            val clientBuilders = Regex("""\bHttpClient\s*\(|federationHttpClient\s*\(|\bHttpClient\s*\{""")
            partnerFiles
                .filter { it.name != "PartnerFeedFetcher.kt" }
                .filter { clientBuilders.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .shouldBeEmpty()
            val fetcher = SourceScan.blank(partnerFiles.single { it.name == "PartnerFeedFetcher.kt" }.readText())
            Regex("""\btransaction\s*\{""").containsMatchIn(fetcher) shouldBe false
            Regex("""PartnerServerStore""").containsMatchIn(fetcher) shouldBe false
        }

        test("the stored partner list of a partner is read back only by the two stores and the parser") {
            partnerFiles
                .filter { it.name !in setOf("PartnerServerStore.kt", "PartnerDiscoveryStore.kt", "PartnerFeedParser.kt") }
                .filter { SourceScan.blank(it.readText()).contains("remotePartnersJson") }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("only the store writes the partner tables and the two partner columns of the settings") {
            val writers =
                SourceScan.mainFiles().filter { file ->
                    val code = SourceScan.blank(file.readText())
                    Regex(
                        """\b(PartnerServerTable|PartnerEventTable|PartnerBlocklistTable|PartnerDiscoveredServerTable)\s*\.\s*(insert|update|upsert|batchInsert|deleteWhere|deleteAll)\b""",
                    ).containsMatchIn(code) ||
                        Regex("""\bpartnerListHidden\b|\bpartnerServerId\b|\bpartnerRecursion\w*""").containsMatchIn(code) &&
                        "OrganizationSettingsTable" in code
                }
            writers
                .map { it.name }
                .filter { it !in setOf("PartnerServerStore.kt", "PartnerDiscoveryStore.kt", "OrganizationSettingsTable.kt") }
                .shouldBeEmpty()
        }

        test("PartnerRecursionRunner is referenced from src/main only by the poller, never from a request path") {
            SourceScan
                .mainFiles()
                .filter { SourceScan.blank(it.readText()).contains("PartnerRecursionRunner") }
                .map { it.name }
                .toSet() shouldBe setOf("PartnerFeedPoller.kt", "PartnerRecursionRunner.kt")
        }

        test("the feed builder and the embed feed never touch the partner cache") {
            listOf("PartnerFeedBuilder.kt", "EmbedEventsFeedRoutes.kt").forEach { name ->
                val code = SourceScan.blank(SourceScan.mainFiles().single { it.name == name }.readText())
                withClue(name) {
                    Regex(
                        """PartnerEventTable|PartnerDiscoveredServerTable|PartnerEventDisplay|PartnerDiscoveryStore""",
                    ).containsMatchIn(code) shouldBe
                        false
                }
            }
        }

        test("only PublicPartnerEventHtml carries the external-link rel, and the two pages that list partner events never set target=") {
            val routes = SourceScan.mainFiles().filter { "/server/routes/" in it.path }
            routes
                .filter {
                    SourceScan.blank(it.readText()).contains("noopener noreferrer external") ||
                        it.readText().contains("noopener noreferrer external")
                }.map { it.name } shouldBe listOf("PublicPartnerEventHtml.kt")
            routes
                .filter {
                    it.name in setOf("PublicOverviewHtml.kt", "PublicPartnerEventHtml.kt") &&
                        Regex("""\["target"\]|\btarget\s*=\s*"_""").containsMatchIn(it.readText())
                }.map { it.name }
                .shouldBeEmpty()
        }

        test("the feed never carries what the public pages do not show") {
            val builder = SourceScan.blank(partnerFiles.single { it.name == "PartnerFeedBuilder.kt" }.readText())
            listOf(
                "EventTable.description",
                "EventTable.capacity",
                "EventTable.fee",
                "EventTable.createdBy",
                "EventRegistrationTable",
            ).forEach {
                (it in builder) shouldBe false
            }
        }
    })
