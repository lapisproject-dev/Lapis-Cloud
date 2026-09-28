package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" review-fix -- structural source-text scan
 * guarding [memberVisibility]'s own KDoc "Allowlisted call sites only" invariant: this is the ONE
 * new authorization-narrowing boundary the wave adds (see [MemberVisibility] KDoc), so a NEW,
 * unreviewed call site silently appearing somewhere else (an API-key route, the `mcp` package, a
 * future service) would be exactly the kind of drift a behavioral test cannot catch -- the call
 * site would need to exist and be exercised BEFORE a behavioral test could fail on it. Same "guard
 * exists at the text level because a behavioral test cannot catch it structurally" posture
 * [PaymentsRegressionScanTest] already establishes for its own scans.
 *
 * A standalone `RegionalChapterNoWideningTest` covering the SAME concern behaviorally (actually
 * calling the API-key/`mcp` surfaces and asserting no widening) is, per the CHANGELOG's own
 * "Umfang dieser Welle" disclosure, not yet built -- this scan is what stands in for it today.
 */
class RegionalChapterVisibilityAllowlistScanTest :
    FunSpec({
        test("no lapis-server main-source file outside the allowlist calls .memberVisibility()") {
            val serverMainDir = resolveAllowlistScanModuleDir("lapis-server/src/main/kotlin")
            // Relative to serverMainDir -- the ONLY files permitted to call memberVisibility().
            val allowlist =
                setOf(
                    "network/lapis/cloud/server/rpc/MemberService.kt",
                    "network/lapis/cloud/server/rpc/AuthService.kt",
                    // The declaration site itself (the extension function's own body/KDoc mention
                    // the name in prose, not as a call).
                    "network/lapis/cloud/server/security/RegionalChapterVisibility.kt",
                )
            val callPattern = Regex("""\.memberVisibility\(\)""")

            val offenders =
                serverMainDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { file -> file.relativeTo(serverMainDir).path.replace(File.separatorChar, '/') !in allowlist }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (callPattern.containsMatchIn(line)) "${file.path}:${index + 1}: $line" else null
                        }
                    }.toList()
            offenders.shouldBeEmpty()
        }
    })

/**
 * Resolves [relativePath] whether the test process's working directory is the repo root or
 * `lapis-server` itself -- same idiom [PaymentsRegressionScanTest]'s own `resolveModuleDir`
 * establishes (Kotlin top-level `private` is file-scoped, so that one is invisible here).
 */
private fun resolveAllowlistScanModuleDir(relativePath: String): File {
    val fromRepoRoot = File(relativePath)
    if (fromRepoRoot.exists()) return fromRepoRoot
    val fromModuleDir = File("../$relativePath")
    if (fromModuleDir.exists()) return fromModuleDir
    error("could not resolve '$relativePath' from working directory ${File(".").absolutePath}")
}
