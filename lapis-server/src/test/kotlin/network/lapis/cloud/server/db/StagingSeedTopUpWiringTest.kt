package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** Source tripwire: the top-up is called in `main()` after the seed, and never from `module()`. */
class StagingSeedTopUpWiringTest :
    FunSpec({
        test("Application.kt calls the top-up only inside main(), after StagingSeedData.seedIfEmpty()") {
            val source = File("src/main/kotlin/network/lapis/cloud/server/Application.kt").readText()
            val call = "StagingSeedTopUp.topUpIfEligible()"
            source.split(call).size - 1 shouldBe 1
            val mainStart = source.indexOf("fun main(")
            val moduleStart = source.indexOf("fun Application.module()")
            (mainStart >= 0 && moduleStart >= 0) shouldBe true
            val callAt = source.indexOf(call)
            val seedAt = source.indexOf("StagingSeedData.seedIfEmpty()")
            (seedAt in mainStart until callAt) shouldBe true
            // main() is declared before module(): the call must lie inside main()
            (mainStart < callAt && callAt < moduleStart) shouldBe true
        }
    })
