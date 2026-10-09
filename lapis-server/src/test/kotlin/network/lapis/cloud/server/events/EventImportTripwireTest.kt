package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.82 -- structural guarantees of the event import that no behavioural test can pin down by itself. Source-scanning
 * tripwires in the style of `MemberRowLockTripwireTest`: they fail the build when the code drifts away from the design.
 */
class EventImportTripwireTest :
    FunSpec({
        val root = SourceScan.mainRoot()

        fun code(file: File) = SourceScan.blank(file.readText())

        val importedTrue = Regex("""imported\s*=\s*true""")

        test("imported = true is written by EventImporter and nowhere else (no other writer of the flag)") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "EventImporter.kt" }
                    .filter { file -> importedTrue.containsMatchIn(code(file)) }
                    .map { it.name }
            offenders shouldBe emptyList()
            importedTrue.containsMatchIn(code(File(root, "network/lapis/cloud/server/events/EventImporter.kt"))) shouldBe true
        }

        test("EventStore.updateEvent never touches the imported column and its new parameters have no default") {
            val store = code(File(root, "network/lapis/cloud/server/events/EventStore.kt"))
            val update = store.substring(store.indexOf("fun updateEvent("), store.indexOf("fun setStatus("))
            update.contains("EventTable.imported") shouldBe false
            Regex("""summary:\s*String\?\s*=""").containsMatchIn(update) shouldBe false
            Regex("""coverImageAlt:\s*String\?\s*=""").containsMatchIn(update) shouldBe false
            Regex("""onlineUrlPublic:\s*Boolean\s*=""").containsMatchIn(update) shouldBe false
        }

        test("EventPolicy.validate does not know the import (the past-date rule stays as it was)") {
            val policy = File(root, "network/lapis/cloud/server/events/EventPolicy.kt").readText()
            code(File(root, "network/lapis/cloud/server/events/EventPolicy.kt")).contains("imported") shouldBe false
            policy.contains("Beginn darf nicht in der Vergangenheit liegen") shouldBe true
        }

        test("every method of EventImportService starts with the role check") {
            val service = code(File(root, "network/lapis/cloud/server/rpc/EventImportService.kt"))
            val methods =
                Regex("""override suspend fun (\w+)\([^)]*\)[^{]*\{(.*?)\n    }""", RegexOption.DOT_MATCHES_ALL).findAll(service).toList()
            methods.map { it.groupValues[1] } shouldBe listOf("previewEventImport", "commitEventImport")
            for (method in methods) {
                val statements =
                    method.groupValues[2]
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                statements[0] shouldBe "val current = resolveCurrentMember(call)"
                statements[1] shouldBe "current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)"
            }
        }

        test("the import files contain no network client, no mail and no webhook (no SSRF surface, no external effect in a transaction)") {
            val files =
                listOf(
                    "network/lapis/cloud/server/events/EventImporter.kt",
                    "network/lapis/cloud/server/events/EventImportPolicy.kt",
                    "network/lapis/cloud/server/events/EventText.kt",
                    "network/lapis/cloud/server/rpc/EventImportService.kt",
                )
            val forbidden =
                listOf(
                    "HttpClient",
                    "io.ktor.client",
                    "java.net.http",
                    "URL(",
                    ".toURL",
                    "openConnection",
                    "MailDispatcher",
                    "MailOutbox",
                    "Webhook",
                    "runBlocking",
                )
            val hits = mutableListOf<String>()
            for (name in files) {
                val text = code(File(root, name))
                forbidden.filter { text.contains(it) }.forEach { hits += "$name: $it" }
            }
            hits shouldBe emptyList()
        }

        test("the import logs and prints nothing (no payload text in log lines)") {
            for (name in listOf("EventImporter.kt", "EventImportPolicy.kt", "EventText.kt")) {
                val text = code(File(root, "network/lapis/cloud/server/events/$name"))
                text.contains("logger") shouldBe false
                text.contains("println") shouldBe false
            }
        }
    })
