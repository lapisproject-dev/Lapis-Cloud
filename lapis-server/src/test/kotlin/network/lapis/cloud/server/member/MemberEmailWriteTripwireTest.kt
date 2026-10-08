package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- source-scan tripwire: **no code but [EmailChangeStore.applyLocked] may
 * overwrite `member.email` of an EXISTING member.** The address is the login and password-reset identity; any new
 * writer is a new account-takeover path and must either route through the change lifecycle or be consciously added to
 * the allowlist below WITH a reason.
 *
 * Scans every file under `src/main/kotlin` for writes through `MemberTable` (`update`, `upsert`, `replace`,
 * `batchUpsert`, `batchReplace`, `batchInsert`, `insertIgnore`, `insert`) whose lambda assigns the `email` column, and
 * for raw SQL strings of the shape `UPDATE member SET ... email`. The scanner is itself pinned: the files it finds must
 * equal the allowlist exactly, so "no findings" can never just mean "the scanner sees nothing".
 */
class MemberEmailWriteTripwireTest :
    FunSpec({
        // path (relative to src/main/kotlin) -> reason
        val updateAllowlist =
            mapOf(
                "network/lapis/cloud/server/member/EmailChangeStore.kt" to "applyLocked, the ONE writer of an existing member's address",
                "network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt" to
                    "Art. 17 anonymization replaces the address by a placeholder",
            )
        // inserts create NEW members, they cannot take over an existing account
        val insertAllowlist =
            mapOf(
                "network/lapis/cloud/server/rpc/RegistrationService.kt" to
                    "application / FRIEND self-registration / direct creation of a NEW member",
                "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt" to "CSV import creates NEW members",
                "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt" to "first-admin bootstrap creates a NEW member",
                "network/lapis/cloud/server/federation/OidcGuestMemberStore.kt" to "OIDC guest creates a NEW synthetic-address member",
                "network/lapis/cloud/server/keycloak/KeycloakMemberProvisioner.kt" to
                    "Keycloak just-in-time provisioning creates a NEW member (never touches an existing one: an address match wins)",
                "network/lapis/cloud/server/db/DevSeedData.kt" to "dev seed, NEW members",
                "network/lapis/cloud/server/db/StagingSeedData.kt" to "staging seed, NEW members",
            )

        val writeCall =
            Regex("""(?<![A-Za-z0-9_])MemberTable\.(update|upsert|replace|batchUpsert|batchReplace|batchInsert|insertIgnore|insert)\b""")
        val emailAssignment = Regex("""\[\s*(MemberTable\.)?email\s*]\s*=""")
        val rawSql = Regex("""(?is)update\s+"?member"?\s+set\b[^;]*?\bemail\b""")

        fun blankComments(src: String): String =
            src
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { " ".repeat(it.value.length).replace(' ', ' ') }
                .replace(Regex("""//[^\n]*""")) { " ".repeat(it.value.length) }

        fun matching(
            s: String,
            open: Int,
            o: Char,
            c: Char,
        ): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                if (s[j] == o) {
                    depth++
                } else if (s[j] == c) {
                    depth--
                    if (depth == 0) return j
                }
                j++
            }
            return s.length - 1
        }

        /** The lambda body of a `MemberTable.<call>(...) { ... }` / `MemberTable.<call> { ... }`, or null when there is none. */
        fun lambdaBody(
            code: String,
            from: Int,
        ): String? {
            var k = from
            while (k < code.length && code[k].isWhitespace()) k++
            if (k < code.length && code[k] == '(') {
                k = matching(code, k, '(', ')') + 1
                while (k < code.length && code[k].isWhitespace()) k++
            }
            if (k >= code.length || code[k] != '{') return null
            return code.substring(k, matching(code, k, '{', '}') + 1)
        }

        data class Finding(
            val relativePath: String,
            val call: String,
        )

        fun scan(): Pair<List<Finding>, List<String>> {
            val root = SourceScan.mainRoot()
            val findings = mutableListOf<Finding>()
            val sqlFiles = mutableListOf<String>()
            SourceScan.mainFiles().forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val raw = blankComments(file.readText())
                val code = SourceScan.blank(file.readText())
                writeCall.findAll(code).forEach { m ->
                    val body = lambdaBody(code, m.range.last + 1)
                    check(body != null) { "MemberTable.${m.groupValues[1]} without a lambda in $relative -- the scanner cannot judge it" }
                    if (emailAssignment.containsMatchIn(body)) findings += Finding(relative, m.groupValues[1])
                }
                if (rawSql.containsMatchIn(raw)) sqlFiles += relative
            }
            return findings to sqlFiles
        }

        test("only the allowlisted files assign MemberTable.email, and the allowlist is exact (no stale entries)") {
            val (findings, _) = scan()
            val updaters = findings.filter { it.call != "insert" }.map { it.relativePath }.toSet()
            val inserters = findings.filter { it.call == "insert" }.map { it.relativePath }.toSet()
            updaters shouldBe updateAllowlist.keys
            inserters shouldBe insertAllowlist.keys
        }

        test("the address of an existing member is overwritten in exactly one place besides the GDPR anonymization") {
            val (findings, _) = scan()
            val updates = findings.filter { it.call != "insert" }
            updates.map { it.relativePath }.sorted() shouldBe updateAllowlist.keys.sorted()
            updates.count { it.relativePath.endsWith("EmailChangeStore.kt") } shouldBe 1
        }

        test("no raw SQL string updates member.email") {
            scan().second.shouldBeEmpty()
        }

        test("the scanner itself sees a planted violation (self-test)") {
            val planted =
                """
                fun bad(id: Uuid) {
                    MemberTable.update({ MemberTable.id eq id }) { it[email] = "x@example.org" }
                    MemberTable.upsert { it[MemberTable.email] = "y@example.org" }
                }
                """.trimIndent()
            val code = SourceScan.blank(planted)
            val hits =
                writeCall
                    .findAll(
                        code,
                    ).mapNotNull { lambdaBody(code, it.range.last + 1) }
                    .count { emailAssignment.containsMatchIn(it) }
            hits shouldBe 2
            rawSql.containsMatchIn("""exec("UPDATE member SET email = 'a' WHERE id = 1")""") shouldBe true
            rawSql.containsMatchIn("""exec("UPDATE member SET display_name = 'a' WHERE id = 1")""") shouldBe false
        }
    })
