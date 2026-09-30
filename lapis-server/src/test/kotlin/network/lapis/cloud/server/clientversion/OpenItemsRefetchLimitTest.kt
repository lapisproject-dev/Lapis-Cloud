package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.17: the client's in-place refetch of the open-items list (`OpenItemsRefetch.kt`) chunks its calls by
 * `MAX_OPEN_ITEMS_LIMIT`, which must equal the server's hard per-call cap (`OpenItemService.MAX_LIST_RESULTS`,
 * `limit.coerceIn(1, MAX_LIST_RESULTS)`). If the server cap drops below the client's number, a refetch would silently
 * come back shorter than asked and look like "rows vanished"; this scan fails the build before that can happen.
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private fun sourceFile(relative: String): File = File("../$relative").let { if (it.exists()) it else File(relative) }

private fun constantValue(
    file: File,
    name: String,
): Int =
    Regex("""\bconst\s+val\s+$name\s*=\s*(\d+)""")
        .find(file.readText())
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: error("const $name not found in ${file.path}")

class OpenItemsRefetchLimitTest :
    FunSpec({
        val server = sourceFile("lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc/OpenItemService.kt")
        val client = sourceFile("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/OpenItemsRefetch.kt")

        test("the client's per-call limit equals the server's hard cap") {
            constantValue(file = client, name = "MAX_OPEN_ITEMS_LIMIT") shouldBe constantValue(file = server, name = "MAX_LIST_RESULTS")
        }

        test("the server really clamps listOpenItems to that cap") {
            server.readText().contains("val effectiveLimit = limit.coerceIn(1, MAX_LIST_RESULTS)") shouldBe true
        }
    })
