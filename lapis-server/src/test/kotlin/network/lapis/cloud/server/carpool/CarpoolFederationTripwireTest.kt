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
            "(d) projectCarpoolTeaser is called by the client only in CarpoolFederationUi.kt, and by the server only in CarpoolService.kt",
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
            serverUsers shouldBe setOf("CarpoolService.kt")
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
    })
