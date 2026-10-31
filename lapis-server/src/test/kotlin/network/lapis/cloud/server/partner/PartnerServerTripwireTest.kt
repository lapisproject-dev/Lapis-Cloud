package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.IPartnerServerService

/**
 * V1.9.244 -- structural guarantees of the partner feature that a later edit must not lose without a failing build:
 *  - every RPC method starts with the ADMIN gate (the list steers outgoing requests),
 *  - [PartnerFeedFetcher] is the only place that opens a connection and it is only ever constructed with its production defaults,
 *  - the partner lists of the partners are stored but never read back to fetch from (no recursion in P1),
 *  - only [PartnerServerStore] writes the three tables.
 */
class PartnerServerTripwireTest :
    FunSpec({
        val methods =
            setOf("list", "probeAndSave", "setActive", "remove", "listBlocklist", "addBlock", "removeBlock", "setPartnerListHidden")
        val partnerFiles = SourceScan.mainFiles().filter { "/server/partner/" in it.path }

        test("the RPC surface is exactly the eight known methods") {
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

        test("the stored partner list of a partner is never read back to fetch from") {
            partnerFiles
                .filter { it.name != "PartnerServerStore.kt" && it.name != "PartnerFeedParser.kt" }
                .filter { SourceScan.blank(it.readText()).contains("remotePartnersJson") }
                .map { it.name }
                .shouldBeEmpty()
        }

        test("only the store writes the partner tables and the two partner columns of the settings") {
            val writers =
                SourceScan.mainFiles().filter { file ->
                    val code = SourceScan.blank(file.readText())
                    Regex(
                        """\b(PartnerServerTable|PartnerEventTable|PartnerBlocklistTable)\s*\.\s*(insert|update|upsert|batchInsert|deleteWhere|deleteAll)\b""",
                    ).containsMatchIn(code) ||
                        Regex("""\bpartnerListHidden\b|\bpartnerServerId\b""").containsMatchIn(code) &&
                        "OrganizationSettingsTable" in code
                }
            writers.map { it.name }.filter { it != "PartnerServerStore.kt" && it != "OrganizationSettingsTable.kt" }.shouldBeEmpty()
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
