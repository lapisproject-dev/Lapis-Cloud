package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Welle V1.9.13 review-fix regression guard. `SELECT ... FOR UPDATE` on the nullable side of an
 * OUTER join is rejected by real PostgreSQL (`ERROR: FOR UPDATE cannot be applied to the nullable
 * side of an outer join`) but SILENTLY ACCEPTED by H2's `MODE=PostgreSQL` dialect -- the dialect
 * this whole test suite runs against. `RegionalChapterService.grantOfficer` originally locked
 * `(MemberTable leftJoin AccountTable).forUpdate()`: every test passed (H2 does not reject it), but
 * an ADMIN calling `grantOfficer` on a real Postgres instance (PdV/ELB/Staging) got an unhandled
 * `ExposedSQLException` -- see that method's own KDoc for the fix (lock `MemberTable` alone, check
 * account existence as a separate unlocked query).
 *
 * Structural source-text scan, not a behavioral test -- by definition, an H2-backed integration
 * test CANNOT observe this class of bug (that is exactly how it slipped through originally), so the
 * guard has to live at the text level. Same "guard exists at the text level because the test DB
 * cannot catch it" posture [PaymentsRegressionScanTest] already establishes for its own scans.
 *
 * Deliberately a small, fixed backward-lookback window (not a real SQL/Kotlin parser) -- this
 * catches the shape that actually caused the incident (`(A leftJoin B).forUpdate()` a few lines
 * later in the SAME fluent chain) without trying to be a general-purpose static analyzer. A false
 * positive (an unrelated OUTER join a few lines above an UNRELATED `forUpdate()`) would just mean
 * an extra look at the offending line, which is an acceptable cost for a regression guard that
 * closes a real, already-happened production-breaking bug class.
 */
class OuterJoinForUpdateScanTest :
    FunSpec({
        test("no lapis-server main-source Exposed query chain combines an OUTER join with forUpdate() within the same statement") {
            val serverMainDir = resolveOuterJoinScanModuleDir("lapis-server/src/main/kotlin")
            val outerJoinPattern = Regex("""\b(leftJoin|rightJoin|fullJoin)\b|JoinType\.(LEFT|RIGHT|FULL)""")
            val forUpdatePattern = Regex("""\.forUpdate\(""")
            // How far back (in source lines) a `.forUpdate()` call is checked for a preceding OUTER
            // join in the SAME fluent chain -- generous enough for every real query shape in this
            // codebase (Table -> .selectAll()/.join(...)/.where{}/.forUpdate(), at most a handful of
            // lines), short enough to stay scoped to the current statement rather than bleeding into
            // unrelated, earlier code in the same function.
            val lookbackLines = 10

            val offenders =
                serverMainDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        val lines = file.readLines()
                        lines.indices.mapNotNull { index ->
                            if (!forUpdatePattern.containsMatchIn(lines[index])) return@mapNotNull null
                            var start = index
                            var blanksSeen = 0
                            while (start > 0 && blanksSeen == 0) {
                                start--
                                if (lines[start].isBlank()) blanksSeen++
                                if (index - start >= lookbackLines) break
                            }
                            val windowStart = if (blanksSeen > 0) start + 1 else start
                            // Comment-only lines are excluded from the scanned statement text --
                            // otherwise a `//`-comment merely EXPLAINING the outer-join/forUpdate()
                            // pitfall (as this codebase's own fix commentary does, right next to the
                            // fixed code) would self-trigger the very guard it documents.
                            val statement =
                                lines
                                    .subList(windowStart, index + 1)
                                    .filterNot { it.trim().startsWith("//") }
                                    .joinToString("\n")
                            if (outerJoinPattern.containsMatchIn(statement)) {
                                "${file.path}:${index + 1}: forUpdate() combined with an OUTER join in the same statement -- " +
                                    "lock the non-nullable side's table alone and check the other side's existence separately " +
                                    "(see RegionalChapterService.grantOfficer KDoc)"
                            } else {
                                null
                            }
                        }
                    }.toList()
            offenders.shouldBeEmpty()
        }
    })

/**
 * Resolves [relativePath] whether the test process's working directory is the repo root or
 * `lapis-server` itself -- same idiom as [PaymentsRegressionScanTest]'s own `resolveModuleDir`
 * (Kotlin top-level `private` is file-scoped, so that one is invisible here; given a distinct name
 * only for readability when both files are open side by side).
 */
private fun resolveOuterJoinScanModuleDir(relativePath: String): File {
    val fromRepoRoot = File(relativePath)
    if (fromRepoRoot.exists()) return fromRepoRoot
    val fromModuleDir = File("../$relativePath")
    if (fromModuleDir.exists()) return fromModuleDir
    error("could not resolve '$relativePath' from working directory ${File(".").absolutePath}")
}
