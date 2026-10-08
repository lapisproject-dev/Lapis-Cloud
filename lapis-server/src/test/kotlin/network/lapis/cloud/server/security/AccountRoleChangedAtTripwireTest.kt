package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.57 -- source-scan tripwire: **every write of `account.role` stamps `account.role_changed_at`** in the same statement.
 * The approver tenure rule of the admin peer protection ("an ADMIN may approve only after 7 days in the role") reads that column;
 * a writer that forgets it would hand a freshly made administrator -- a strawman -- instant approval rights.
 *
 * Only the two seeds are exempt: they leave the column `NULL` on purpose (NULL = pre-existing account = tenured, which is what a
 * demo/staging administrator should be). The scanner is pinned: the files it finds must equal the writers + the exemption exactly.
 */
class AccountRoleChangedAtTripwireTest :
    FunSpec({
        val exempt =
            mapOf(
                "network/lapis/cloud/server/db/DevSeedData.kt" to "dev seed: NULL means tenured",
                "network/lapis/cloud/server/db/StagingSeedData.kt" to "staging seed: NULL means tenured",
            )
        val expectedWriters =
            setOf(
                "network/lapis/cloud/server/member/MemberRoleStatusMutations.kt",
                "network/lapis/cloud/server/rpc/MemberService.kt",
                "network/lapis/cloud/server/rpc/RegistrationService.kt",
                "network/lapis/cloud/server/federation/OidcGuestMemberStore.kt",
                "network/lapis/cloud/server/keycloak/KeycloakMemberProvisioner.kt",
                "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt",
            )

        val accountWrite = Regex("""(?<![A-Za-z0-9_])AccountTable\.(update|insert|upsert|replace|batchInsert|batchUpsert)\b""")
        val roleAssignment = Regex("""\[\s*(AccountTable\.)?role\s*]\s*=""")
        val stamp = Regex("""\[\s*(AccountTable\.)?roleChangedAt\s*]\s*=""")

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

        fun lambdaBody(
            code: String,
            from: Int,
        ): String? {
            var k = from
            while (k < code.length && code[k].isWhitespace()) k++
            if (k < code.length && code[k] == '(') {
                var depth = 0
                var j = k
                while (j < code.length) {
                    if (code[j] == '(') {
                        depth++
                    } else if (code[j] == ')') {
                        depth--
                        if (depth == 0) break
                    }
                    j++
                }
                k = j + 1
                while (k < code.length && code[k].isWhitespace()) k++
            }
            if (k >= code.length || code[k] != '{') return null
            return code.substring(k, matching(code, k) + 1)
        }

        data class Write(
            val file: String,
            val line: Int,
            val stamped: Boolean,
        )

        fun scan(): List<Write> {
            val root = SourceScan.mainRoot()
            val out = mutableListOf<Write>()
            SourceScan.mainFiles().forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val code = SourceScan.blank(file.readText())
                accountWrite.findAll(code).forEach { m ->
                    val body = lambdaBody(code, m.range.last + 1) ?: return@forEach
                    if (roleAssignment.containsMatchIn(body)) {
                        out += Write(relative, SourceScan.lineOf(text = code, index = m.range.first), stamp.containsMatchIn(body))
                    }
                }
            }
            return out
        }

        test("every write of account.role stamps role_changed_at, except the two seeds") {
            val unstamped = scan().filter { !it.stamped && it.file !in exempt.keys }
            unstamped.map { "${it.file}:${it.line}" }.shouldBeEmpty()
        }

        test("the scanner is pinned: the files with a role write are exactly the writers plus the exempt seeds") {
            scan().map { it.file }.toSet() shouldBe expectedWriters + exempt.keys
        }

        test("the exempt seeds really leave the column NULL (a stamp there would be a silent policy change)") {
            scan().filter { it.file in exempt.keys }.all { !it.stamped } shouldBe true
        }

        test("the scanner sees a planted violation (self-test)") {
            val planted =
                """
                fun bad() {
                    AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.ADMIN }
                    AccountTable.insert { it[role] = AccountRole.MEMBER; it[roleChangedAt] = now }
                }
                """.trimIndent()
            val code = SourceScan.blank(planted)
            val results =
                accountWrite
                    .findAll(code)
                    .mapNotNull { m ->
                        val body = lambdaBody(code, m.range.last + 1) ?: return@mapNotNull null
                        if (roleAssignment.containsMatchIn(body)) stamp.containsMatchIn(body) else null
                    }.toList()
            results shouldBe listOf(false, true)
        }
    })
