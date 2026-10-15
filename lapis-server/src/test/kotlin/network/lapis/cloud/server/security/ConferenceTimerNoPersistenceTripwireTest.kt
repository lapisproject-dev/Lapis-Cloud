package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan
import java.io.File

/**
 * V1.9.133 -- the conference timer is volatile by design: no database row, no audit entry, no migration. The only database access of the
 * timer files is the read-only room lookup (`selectAll`) of the authorization and of the reconciler.
 */
class ConferenceTimerNoPersistenceTripwireTest :
    FunSpec({
        val files =
            listOf(
                "rpc/ConferenceTimerService.kt",
                "conference/ConferenceTimerReconciler.kt",
                "conference/ConferenceTimerState.kt",
            )

        test("the timer files write no row and record no audit entry") {
            files.forEach { name ->
                val code = SourceScan.blank(EncounterSourceScan.mainFile(name).readText())
                listOf(".insert", ".update(", "deleteWhere", ".upsert", ".batchInsert", "AuditLogRecorder", "AuditLog", "Audit").forEach {
                    (code.contains(it)) shouldBe false
                }
            }
        }

        test("every transaction of the timer files is a read: authorize and the reconciler's room lookup") {
            files.forEach { name ->
                val code = SourceScan.blank(EncounterSourceScan.mainFile(name).readText())
                SourceScan.transactionBlocks(code).forEach { block ->
                    val text = code.substring(block)
                    (text.contains("selectAll") || text.trim() == "authorize(roomId = roomId, current = current)") shouldBe true
                }
            }
            // the service has exactly two transactions (moderatorRoom and timerState), both only `authorize`, which only reads
            EncounterSourceScan
                .functions(
                    EncounterSourceScan.mainFile("rpc/ConferenceTimerService.kt"),
                ).first { it.name == "authorize" }
                .body
                .let {
                    (it.contains("selectAll")) shouldBe true
                }
            SourceScan
                .blank(EncounterSourceScan.mainFile("rpc/ConferenceTimerService.kt").readText())
                .let { Regex("""transaction\s*\{""").findAll(it).count() } shouldBe 2
        }

        test("there is no migration for the timer") {
            val migrations = File(SourceScan.mainRoot().parentFile, "resources/db/migration")
            (migrations.exists()) shouldBe true
            migrations
                .listFiles()!!
                .map { it.name.lowercase() }
                .filter { it.contains("timer") }
                .shouldBeEmpty()
        }
    })
