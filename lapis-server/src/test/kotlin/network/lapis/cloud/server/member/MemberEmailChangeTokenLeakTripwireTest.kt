package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.56 -- source-scan tripwire: a raw address-change token never reaches a log line or an audit snapshot.
 * Scans `member/` and `mail/` (where the tokens live) for `logger.<level> { ... }` blocks that interpolate a token
 * variable, and for audit-recorder calls whose arguments mention one. The raw tokens (`confirmRaw`, `revokeRaw`,
 * `rawToken`, `rawRevokeToken`) exist only as local variables between insert and mail hand-off.
 */
class MemberEmailChangeTokenLeakTripwireTest :
    FunSpec({
        val tokenNames = Regex("""\b(confirmRaw|revokeRaw|rawToken|rawRevokeToken|rawSessionToken|ownRawSessionToken)\b""")
        val loggerCall = Regex("""\blogger\s*\.\s*(trace|debug|info|warn|error)\s*(\([^)]*\))?\s*\{""")

        fun matching(
            s: String,
            open: Int,
        ): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                if (s[j] == '{') {
                    depth++
                } else if (s[j] == '}') {
                    depth--
                    if (depth == 0) return j
                }
                j++
            }
            return s.length - 1
        }

        fun scannedFiles(): List<File> {
            val root = SourceScan.mainRoot()
            return SourceScan.mainFiles().filter {
                val rel = it.relativeTo(root).path.replace(File.separatorChar, '/')
                rel.startsWith("network/lapis/cloud/server/member/") || rel.startsWith("network/lapis/cloud/server/mail/")
            }
        }

        /** Like [SourceScan.blank] but keeps string contents (interpolated `$token` lives inside a string). */
        fun withoutComments(src: String): String =
            src
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { " ".repeat(it.value.length) }
                .replace(Regex("""(?m)^\s*//.*$""")) { " ".repeat(it.value.length) }

        test("no log line in member/ or mail/ interpolates a raw token") {
            val offenders = mutableListOf<String>()
            scannedFiles().forEach { file ->
                val text = withoutComments(file.readText())
                loggerCall.findAll(text).forEach { m ->
                    val open = text.indexOf('{', m.range.last - 1)
                    val body = text.substring(open, matching(text, open) + 1)
                    if (tokenNames.containsMatchIn(body)) {
                        offenders +=
                            "${file.name}:${SourceScan.lineOf(text = text, index = m.range.first)}"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("no AuditLogRecorder.record call in member/ mentions a raw token") {
            val offenders = mutableListOf<String>()
            scannedFiles().forEach { file ->
                val text = withoutComments(file.readText())
                Regex("""AuditLogRecorder\.record\s*\(""").findAll(text).forEach { m ->
                    var depth = 0
                    var j = m.range.last
                    while (j < text.length) {
                        if (text[j] == '(') {
                            depth++
                        } else if (text[j] == ')') {
                            depth--
                            if (depth == 0) break
                        }
                        j++
                    }
                    if (tokenNames.containsMatchIn(text.substring(m.range.last, j))) {
                        offenders +=
                            "${file.name}:${SourceScan.lineOf(text = text, index = m.range.first)}"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("the scanner finds files and a planted leak (self-test)") {
            scannedFiles().any { it.name == "EmailChangeService.kt" } shouldBe true
            val planted = """logger.warn { "change failed for token ${'$'}rawToken" }"""
            val open = planted.indexOf('{')
            tokenNames.containsMatchIn(planted.substring(open, matching(planted, open) + 1)) shouldBe true
        }
    })
