package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/** V1.9.226 -- who may name the LupaBB tables: the stores, the guard, the applier, the status read of the service and the data subject contributor. */
class LupaBbStoreTripwireTest :
    FunSpec({
        val allowed =
            mapOf(
                "LupaBbSyncConfigTable" to setOf("LupaBbCredentialStore.kt", "LupaBbRunGuard.kt", "LupaBbPersonalData.kt"),
                "LupaBbSyncRunTable" to setOf("LupaBbApplier.kt", "LupaBbSyncService.kt", "LupaBbPersonalData.kt"),
                "MemberLupaBbProfileTable" to setOf("LupaBbMemberLoader.kt", "LupaBbApplier.kt", "LupaBbPersonalData.kt"),
                "LupaBbSyncSuppressionTable" to setOf("LupaBbSuppression.kt", "LupaBbPersonalData.kt"),
            )
        val columns =
            Regex("""\bMemberTable\s*\.\s*(lupabbPersonNumber|lupabbMissingSince)\b|\[\s*(lupabbPersonNumber|lupabbMissingSince)\s*]""")
        val columnUsers =
            setOf(
                "LupaBbMemberLoader.kt",
                "LupaBbApplier.kt",
                "LupaBbLinker.kt",
                "LupaBbPersonalData.kt",
                "MemberService.kt",
            )

        for ((table, users) in allowed) {
            test("$table is named only by ${users.joinToString()}") {
                SourceScan
                    .mainFiles()
                    .filter { "/db/generated/" !in it.path }
                    .filter { Regex("""\b$table\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet() shouldBe users
            }
        }

        test("the two LupaBB columns of member are touched only by the sync code, the data subject contributor and the admin row mapping") {
            SourceScan
                .mainFiles()
                .filter { "/db/generated/" !in it.path }
                .filter { columns.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet() shouldBe columnUsers
        }
    })
