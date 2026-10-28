package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.240 -- the finalised Protokoll is append-only by construction, not by a database trigger (the repository has no such pattern): exactly ONE
 * main file writes the version tables, every update of `protocol_version` is one of the three conditional forms, and nothing that renders or reads
 * the Protokoll can see a ballot or a voter.
 */
class ProtocolVersionStoreTripwireTest :
    FunSpec({
        val versionTables =
            listOf("ProtocolVersionTable", "ProtocolVersionConfirmationTable", "ProtocolVersionTaskTable", "ProtocolTaskStatusTable")
        val writes = Regex("""\.(update|insert|deleteWhere|upsert|batchInsert|replace)\b""")
        val generated = "/db/generated/"

        fun mainFiles() = SourceScan.mainFiles().filterNot { it.path.contains(generated) }

        test("only ProtocolVersionStore (and the DSGVO contributor for the task states) writes the version tables") {
            val offenders =
                mainFiles()
                    .filter { f -> f.name != "ProtocolVersionStore.kt" }
                    .filter { f ->
                        val code = SourceScan.blank(f.readText())
                        versionTables.any { table ->
                            Regex("""\b$table\s*\.(update|insert|deleteWhere|upsert|batchInsert|replace)\b""").containsMatchIn(code) ||
                                Regex("""\b$table\s*\.\s*update\(""").containsMatchIn(code)
                        }
                    }.map { it.name }
            offenders shouldBe listOf("GovernancePersonalData.kt")
            val dsgvo = SourceScan.blank(mainFiles().single { it.name == "GovernancePersonalData.kt" }.readText())
            // the contributor deletes ONLY the task states, nothing else
            versionTables.filter { it != "ProtocolTaskStatusTable" }.forEach { table ->
                writes.findAll(dsgvo).none { m ->
                    dsgvo.substring((m.range.first - table.length - 1).coerceAtLeast(0), m.range.first).endsWith(table)
                } shouldBe
                    true
            }
        }

        test("every update of ProtocolVersionTable in the store is conditional on the status or on a missing PDF; there is no delete") {
            val code =
                SourceScan.blank(
                    java.io.File(SourceScan.mainRoot(), "network/lapis/cloud/server/governance/session/ProtocolVersionStore.kt").readText(),
                )
            Regex("""ProtocolVersionTable\.update""").findAll(code).count() shouldBe 3
            Regex("""ProtocolVersionTable\.update\(\{[^}]*status eq""").findAll(code).count() shouldBe 3
            Regex("""ProtocolVersionTable\.(deleteWhere|upsert|replace)""").containsMatchIn(code) shouldBe false
            Regex("""ProtocolVersionConfirmationTable\.(update|deleteWhere|upsert|replace)""").containsMatchIn(code) shouldBe false
            Regex("""ProtocolVersionTaskTable\.(update|deleteWhere|upsert|replace)""").containsMatchIn(code) shouldBe false
        }

        test("the Protokoll code never touches a ballot, a voter or an election tally") {
            val names =
                listOf(
                    "ProtocolSnapshot.kt",
                    "ProtocolVersionStore.kt",
                    "MeetingProtocolOperations.kt",
                    "ProtocolApprovalItems.kt",
                    "ProtocolPdfArchiver.kt",
                    "MeetingProtocolPdfGenerator.kt",
                    "MeetingProtocolRoutes.kt",
                )
            val offenders =
                mainFiles()
                    .filter { it.name in names }
                    .filter {
                        Regex(
                            """BallotTable|BallotSelection|EligibleVoter|ElectionParticipation|VoteBallot|ElectionTallyApproval""",
                        ).containsMatchIn(SourceScan.blank(it.readText()))
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }

        test("no log line of the Protokoll code carries a text, a name or a hash") {
            val offenders =
                mainFiles()
                    .filter { it.name in setOf("ProtocolPdfArchiver.kt", "MeetingProtocolOperations.kt", "MeetingProtocolRoutes.kt") }
                    .flatMap { f ->
                        f
                            .readLines()
                            .withIndex()
                            .filter { (_, line) ->
                                line.contains("logger.") &&
                                    Regex("""\$\{?(sha|hash|body|text|name|snapshot|note)""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                            }.map { "${f.name}:${it.index + 1}" }
                    }
            offenders.shouldBeEmpty()
        }
    })
