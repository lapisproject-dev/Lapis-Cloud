package network.lapis.cloud.server.membermap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Structural, source-text-scan regression guard -- same "structural coverage test, not a
 * behavioral one" idiom as `network.lapis.cloud.server.rpc.PaymentsRegressionScanTest`/
 * `CrmRegressionScanTest`/`network.lapis.cloud.server.ai.AiStructureTest`.
 *
 * Guards the exact honesty-gap defect found in review of Welle V1.9.5 "Vorstands-Karte": the real
 * production entry point `fun Application.module()` (zero args -- the one `embeddedServer(Netty,
 * ..., module = Application::module)` in `main()` actually calls) forwarded `aiConfig`/`mcpConfig`
 * to the real `Application.module(...)` body but NOT `memberMapConfig`, so that parameter silently
 * fell back to its `MemberMapConfig.notConfigured()` default in every real deployment -- no matter
 * what `LAPIS_MAP_PMTILES_PATH` an operator set per `deploy/example/README.adoc` §"Member map
 * (optional)" and `.env.example`. The server would still start, log "not configured", and answer
 * every `GET /api/board/member-map/basemap.pmtiles` with 404 -- exactly the same observable
 * behavior as the *intentional* "unset" case, which is why this went unnoticed.
 *
 * [MemberMapConfigTest] only ever exercises `MemberMapConfig.load()`'s own string parsing with an
 * injected fake `env` lambda, never the real `System.getenv`-backed zero-arg entry point.
 * `network.lapis.cloud.server.routes.MemberMapRoutesTest` starts the full `module(...)` with an
 * explicit `memberMapConfig = MemberMapConfig(...)` argument, which also bypasses the exact defect
 * here (a missing keyword argument at the zero-arg call site). Neither would have caught this --
 * hence this dedicated source-scan of the zero-arg entry point itself.
 */
class MemberMapModuleWiringRegressionTest :
    FunSpec({
        val applicationFile = resolveModuleFile("lapis-server/src/main/kotlin/network/lapis/cloud/server/Application.kt")
        val entryPointLine = applicationFile.readLines().singleOrNull { it.trimStart().startsWith("fun Application.module() =") }

        test("the scan actually finds the real zero-arg entry point (guard against a silently empty/duplicated scan)") {
            entryPointLine.shouldNotBeNull()
        }

        test(
            "production zero-arg entry point forwards memberMapConfig = MemberMapConfig.load(), analog to " +
                "aiConfig/mcpConfig -- regression guard for the review finding where this keyword argument was " +
                "missing and the parameter silently fell back to MemberMapConfig.notConfigured()",
        ) {
            checkNotNull(entryPointLine) { "entry point line not found -- see the scan-emptiness guard above" }
                .shouldContain("memberMapConfig = MemberMapConfig.load()")
        }
    })

/** Mirrors `CrmRegressionScanTest`'s own `resolveModuleDir` -- works whether the test process's working directory is the repo root or `lapis-server` itself. */
private fun resolveModuleFile(relativePath: String): File {
    val fromRepoRoot = File(relativePath)
    if (fromRepoRoot.exists()) return fromRepoRoot
    val fromModuleDir = File("../$relativePath")
    if (fromModuleDir.exists()) return fromModuleDir
    error("could not resolve '$relativePath' from working directory ${File(".").absolutePath}")
}
