package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.139 -- the ride messages are a narrow exception for guests and friends, NOT a widening of the direct messages: every method of
 * `DirectMessageService` keeps `requireActiveMembership(`.
 */
class CarpoolMessageDmBoundaryTripwireTest :
    FunSpec({
        val serverMain = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }
        val source = File(serverMain, "network/lapis/cloud/server/rpc/DirectMessageService.kt").readText()

        test("every override of DirectMessageService is gated by requireActiveMembership") {
            val declarations = Regex("""(?m)^    override suspend fun (\w+)\(""").findAll(source).toList()
            (declarations.size >= 8) shouldBe true
            val wrong = mutableListOf<String>()
            declarations.forEachIndexed { i, match ->
                val end = if (i + 1 < declarations.size) declarations[i + 1].range.first else source.length
                if ("requireActiveMembership(" !in source.substring(match.range.first, end)) wrong += match.groupValues[1]
            }
            wrong.shouldBeEmpty()
        }

        test("the passenger gate is not used by the direct-message code") {
            listOf("requireCarpoolPassengerMembership", "CARPOOL_PASSENGER", "CarpoolMessages").forEach { (it in source) shouldBe false }
        }
    })
