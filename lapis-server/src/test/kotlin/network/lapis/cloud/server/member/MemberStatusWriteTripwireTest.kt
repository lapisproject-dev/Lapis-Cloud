package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- source-scan tripwire: **every code path that writes `member.status` must append the
 * change to `member_status_history` in the same transaction** ([MemberStatusHistory.recordLocked]). A status change that bypasses
 * the history makes every later count wrong and cannot be repaired afterwards (the instant is lost).
 *
 * Scans every file under `src/main/kotlin` for writes through `MemberTable` (`update`, `upsert`, `replace`, `batch*`, `insertIgnore`,
 * `insert`) whose lambda assigns the `status` column. Each file's number of such writes is pinned (allowlist WITH a reason), and
 * every write must be followed by a `MemberStatusHistory.recordLocked(` call before the next status write or the next `fun`.
 * Raw SQL that updates `member.status` or inserts into `member` is forbidden in `src/main`; in migrations newer than V72 it is only
 * allowed together with `member_status_history`.
 */
class MemberStatusWriteTripwireTest :
    FunSpec({
        // path (relative to src/main/kotlin) -> expected number of `MemberTable.<write> { it[status] = ... }` blocks, and why
        val allowlist =
            mapOf(
                "network/lapis/cloud/server/member/MemberRoleStatusMutations.kt" to
                    (
                        1 to
                            "applyStatusChangeLocked, the central path of updateMemberStatus, privileged-action execution and operator console"
                    ),
                "network/lapis/cloud/server/rpc/RegistrationService.kt" to
                    (
                        7 to
                            "registerApplication, approveApplication, rejectApplication, createMemberDirect, leaveMembership, " +
                            "registerFriend, applyForMembership"
                    ),
                "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt" to (1 to "CSV import (source IMPORT)"),
                "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt" to (1 to "first-admin bootstrap"),
                "network/lapis/cloud/server/federation/OidcGuestMemberStore.kt" to (1 to "OIDC guest creation"),
                "network/lapis/cloud/server/keycloak/KeycloakMemberProvisioner.kt" to
                    (1 to "Keycloak just-in-time provisioning (source KEYCLOAK_JIT)"),
                "network/lapis/cloud/server/db/DevSeedData.kt" to (1 to "dev seed (source SEED)"),
                "network/lapis/cloud/server/db/StagingSeedData.kt" to (1 to "staging seed (source SEED)"),
            )
        // migrations up to V72 that rewrite member rows by SQL: all predate the history (V3 renamed status labels)
        val knownOlderMigrations = setOf("V3__member_status_english_and_friend.sql")

        val writeCall =
            Regex("""(?<![A-Za-z0-9_])MemberTable\.(update|upsert|replace|batchUpsert|batchReplace|batchInsert|insertIgnore|insert)\b""")
        val statusAssignment = Regex("""\[\s*(MemberTable\.)?status\s*]\s*=""")
        val recordCall = Regex("""MemberStatusHistory\s*\.\s*recordLocked\s*\(""")
        val funKeyword = Regex("""(?<![A-Za-z0-9_])fun\s""")
        val rawSql = Regex("""(?is)update\s+"?member"?\s+set\b[^;]*?\bstatus\b|insert\s+into\s+"?member"?\b""")

        fun blankComments(src: String): String =
            src
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { " ".repeat(it.value.length) }
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

        /** Every status-assigning MemberTable write in [code]: the index right behind the write call and whether a record call follows in time. */
        fun statusWrites(code: String): List<Pair<Int, Boolean>> {
            val ends =
                writeCall
                    .findAll(code)
                    .filter { m ->
                        val body = lambdaBody(code, m.range.last + 1)
                        check(body != null) { "MemberTable.${m.groupValues[1]} without a lambda -- the scanner cannot judge it" }
                        statusAssignment.containsMatchIn(body)
                    }.map { it.range.last + 1 }
                    .toList()
            return ends.mapIndexed { idx, end ->
                val nextWrite = ends.getOrNull(idx + 1) ?: code.length
                val nextFun = funKeyword.find(code, end)?.range?.first ?: code.length
                val limit = minOf(nextWrite, nextFun)
                end to recordCall.containsMatchIn(code.substring(end, limit))
            }
        }

        fun scan(): Map<String, List<Pair<Int, Boolean>>> {
            val root = SourceScan.mainRoot()
            val result = mutableMapOf<String, List<Pair<Int, Boolean>>>()
            SourceScan.mainFiles().forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val writes = statusWrites(SourceScan.blank(file.readText()))
                if (writes.isNotEmpty()) result[relative] = writes
            }
            return result
        }

        test("only the allowlisted files assign MemberTable.status, with exactly the pinned number of writes") {
            val found = scan()
            found.keys shouldBe allowlist.keys
            allowlist.forEach { (path, expected) -> found.getValue(path).size shouldBe expected.first }
        }

        test("every status write is followed by MemberStatusHistory.recordLocked in the same function") {
            val missing =
                scan().flatMap { (path, writes) ->
                    writes.filterNot { it.second }.map { "$path @ write #${writes.indexOf(it) + 1}" }
                }
            missing.shouldBeEmpty()
        }

        test("no raw SQL in src/main writes member.status or inserts a member") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { rawSql.containsMatchIn(blankComments(it.readText())) }
                    .map { it.name }
            offenders.shouldBeEmpty()
        }

        test("migrations newer than V72 touch member.status or insert members only together with member_status_history") {
            val dir = File(SourceScan.mainRoot().parentFile, "resources/db/migration")
            val offenders =
                dir
                    .listFiles { f -> f.extension == "sql" }!!
                    .filter { f ->
                        val version = Regex("""^V(\d+)__""").find(f.name)!!.groupValues[1].toInt()
                        val sql = f.readText().lines().joinToString("\n") { it.substringBefore("--") }
                        rawSql.containsMatchIn(sql) &&
                            if (version <= 72) f.name !in knownOlderMigrations else !sql.contains("member_status_history")
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }

        test("the scanner itself sees planted violations (self-test)") {
            val noRecord =
                SourceScan.blank(
                    """
                    fun bad(id: Uuid) {
                        MemberTable.update({ MemberTable.id eq id }) { it[status] = MemberStatus.ACTIVE }
                    }
                    """.trimIndent(),
                )
            statusWrites(noRecord).map { it.second } shouldBe listOf(false)

            val recordInOtherFunction =
                SourceScan.blank(
                    """
                    fun bad(id: Uuid) {
                        MemberTable.insert { it[MemberTable.status] = MemberStatus.ACTIVE }
                    }
                    fun other(id: Uuid) {
                        MemberStatusHistory.recordLocked(memberId = id)
                    }
                    """.trimIndent(),
                )
            statusWrites(recordInOtherFunction).map { it.second } shouldBe listOf(false)

            val twoWritesOneRecord =
                SourceScan.blank(
                    """
                    fun f(id: Uuid) {
                        MemberTable.update({ MemberTable.id eq id }) { it[status] = a }
                        MemberTable.update({ MemberTable.id eq id }) { it[status] = b }
                        MemberStatusHistory.recordLocked(memberId = id)
                    }
                    """.trimIndent(),
                )
            statusWrites(twoWritesOneRecord).map { it.second } shouldBe listOf(false, true)

            val good =
                SourceScan.blank(
                    """
                    fun f(id: Uuid) {
                        MemberTable.update({ MemberTable.id eq id }) { it[status] = a }
                        MemberStatusHistory.recordLocked(memberId = id)
                    }
                    """.trimIndent(),
                )
            statusWrites(good).map { it.second } shouldBe listOf(true)

            // a write that does not touch the status is not a finding
            statusWrites(SourceScan.blank("""fun f() { MemberTable.update({ MemberTable.id eq x }) { it[email] = y } }""")) shouldBe
                emptyList()

            rawSql.containsMatchIn("""exec("UPDATE member SET status = 'X' WHERE id = 1")""") shouldBe true
            rawSql.containsMatchIn("""exec("INSERT INTO member (id) VALUES (1)")""") shouldBe true
            rawSql.containsMatchIn("""exec("INSERT INTO member_status_history (member_id) VALUES (1)")""") shouldBe false
            rawSql.containsMatchIn("""exec("UPDATE member SET display_name = 'a' WHERE id = 1")""") shouldBe false
        }
    })
