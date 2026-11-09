package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * V1.9.296 drift guard: every route of the sidebar registry (`NavRegistry.entries`) is named in `docs/architecture/staging-seed-coverage.adoc`,
 * so a new navigation entry cannot be added without saying what a seeded instance shows in it. Reads the client source as text (the server module
 * does not depend on the client).
 */
class StagingSeedCoverageDocTest :
    FunSpec({
        fun first(vararg candidates: String): File = candidates.map { File(it) }.first { it.exists() }

        test("every route constant of NavRegistry appears in the seed coverage document") {
            val registry =
                first(
                    "../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/NavRegistry.kt",
                    "lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/NavRegistry.kt",
                ).readText()
            val doc = first("../docs/architecture/staging-seed-coverage.adoc", "docs/architecture/staging-seed-coverage.adoc").readText()
            val routes = Regex("""entry\(\s*Routes\.(\w+)""").findAll(registry).map { it.groupValues[1] }.toSet()
            routes.size shouldBeGreaterThan 70 // the scan must not silently run empty
            routes.filter { "`$it`" !in doc }.shouldBeEmpty()
        }
    })
