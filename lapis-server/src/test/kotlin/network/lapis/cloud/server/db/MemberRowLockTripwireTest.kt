package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.60 -- tripwire for the member row lock rule (`docs/architecture/row-locks.adoc`):
 *
 * 1. No `forUpdate()` on a `MemberTable` query anywhere in production code, except the three pinned key-column sites
 *    (they may write `member_number`), each carrying the marker comment `row-lock: FOR UPDATE (key change`.
 *    Everything else uses `forMemberUpdate()` (`FOR NO KEY UPDATE` on PostgreSQL).
 * 2. `ForNoKeyUpdate` is spelled in exactly one place: `db/RowLocks.kt`.
 * 3. No hand-written SQL locks `member` rows.
 * 4. Every writer of a key column of `member` (`id`, `email`, `member_number`) is pinned: such an UPDATE silently raises
 *    `FOR NO KEY UPDATE` to `FOR UPDATE`, so a new writer must be reviewed against the lock rule.
 *
 * Comments and string contents are blanked first ([SourceScan.blank]); the detectors are pure functions with their own
 * positive and negative self-tests below.
 */
class MemberRowLockTripwireTest :
    FunSpec({
        val keyChangeSites =
            mapOf(
                "member/MemberNumberAllocator.kt" to 1,
                "member/MemberCardStore.kt" to 1,
                "member/MemberCardIssuance.kt" to 1,
            )
        val keyColumnWriters =
            setOf(
                "member/EmailChangeStore.kt",
                "member/MemberNumberAllocator.kt",
                "dsgvo/FoundationPersonalData.kt",
            )
        val marker = "row-lock: FOR UPDATE (key change"
        val serverRoot = "network/lapis/cloud/server/"

        fun relative(f: File): String = f.invariantSeparatorsPath.substringAfter(serverRoot)

        // ---- test 1: forUpdate() on MemberTable ----
        test("no forUpdate() on a MemberTable query outside the pinned key-column sites") {
            val files = SourceScan.mainFiles()
            files.size shouldBeGreaterThan 300 // the scan must not silently run empty
            val perFile = mutableMapOf<String, MutableList<Int>>()
            files.forEach { f ->
                val raw = f.readText()
                val hits = MemberRowLockScan.memberForUpdateOffsets(SourceScan.blank(raw))
                if (hits.isNotEmpty()) perFile[relative(f)] = hits.map { SourceScan.lineOf(text = raw, index = it) }.toMutableList()
            }
            val unexpected =
                perFile
                    .filter { (file, lines) -> keyChangeSites[file] != lines.size }
                    .map { (file, lines) -> "$file: forUpdate() on MemberTable at line(s) $lines -- use forMemberUpdate()" }
            unexpected.shouldBeEmpty()
            val stale =
                keyChangeSites.keys
                    .filter {
                        it !in perFile
                    }.map { "stale allowlist entry (no forUpdate() on MemberTable any more): $it" }
            stale.shouldBeEmpty()
            // Each pinned site must say WHY, next to the call.
            keyChangeSites.keys.forEach { file ->
                val f = files.single { relative(it) == file }
                val raw = f.readText()
                val line = perFile.getValue(file).single()
                val window = raw.lines().subList((line - 8).coerceAtLeast(0), line).joinToString("\n")
                (marker in window) shouldBe true
            }
        }

        // ---- test 2: one spelling of FOR NO KEY UPDATE ----
        test("ForNoKeyUpdate is spelled only in db/RowLocks.kt") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { Regex("""\bForNoKeyUpdate\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { relative(it) }
            offenders shouldContainExactly listOf("db/RowLocks.kt")
        }

        // ---- test 3: no hand-written row-locking SQL on member ----
        test("no hand-written SQL locks member rows") {
            val pattern = Regex("""(?is)\bfrom\s+"?member"?\b[^;]{0,300}?\bfor\s+(update|no\s+key|share|key\s+share)\b""")
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { f ->
                        val code =
                            f
                                .readLines()
                                .filterNot { l -> l.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } }
                                .joinToString("\n")
                        pattern.containsMatchIn(code)
                    }.map { relative(it) }
            offenders.shouldBeEmpty()
        }

        // ---- test 4: writers of a key column of member ----
        test("only the pinned files write a key column (id, email, member_number) of member") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { MemberRowLockScan.writesMemberKeyColumn(SourceScan.blank(it.readText())) }
                    .map { relative(it) }
                    .toSet()
            (offenders - keyColumnWriters).shouldBeEmpty()
            (keyColumnWriters - offenders).shouldBeEmpty() // a stale pin is a finding, too
        }

        // ---- self-tests of the detectors ----
        fun hits(src: String) = MemberRowLockScan.memberForUpdateOffsets(SourceScan.blank(src)).size

        test("self-test (positive): the detector flags every shape of forUpdate() on a member query") {
            hits("val r = MemberTable.selectAll().where { MemberTable.id eq x }.forUpdate().singleOrNull()") shouldBe 1
            hits(
                """
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq x }
                    .forUpdate()
                    .singleOrNull()
                """.trimIndent(),
            ) shouldBe 1
            hits("MemberTable.selectAll().where { MemberTable.id inList ids }.orderBy(MemberTable.id).forUpdate().toList()") shouldBe 1
            hits(
                """
                fun f(forUpdate: Boolean) {
                    val query = MemberTable.selectAll().where { MemberTable.id eq id }
                    val row = (if (forUpdate) query.forUpdate() else query).singleOrNull()
                }
                """.trimIndent(),
            ) shouldBe 1
            hits("MemberTable.selectAll().where { MemberTable.id eq x }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate).single()") shouldBe
                1
            hits("(MemberTable innerJoin AccountTable).selectAll().where { MemberTable.id eq x }.forUpdate().toList()") shouldBe 1
            hits("(AccountTable innerJoin MemberTable).selectAll().forUpdate().toList()") shouldBe 1
            MemberRowLockScan.writesMemberKeyColumn(
                SourceScan.blank("MemberTable.update({ MemberTable.id eq x }) { it[email] = y }"),
            ) shouldBe
                true
            MemberRowLockScan.writesMemberKeyColumn(
                SourceScan.blank("MemberTable.update({ MemberTable.id eq x }) { it[MemberTable.memberNumber] = y }"),
            ) shouldBe true
        }

        test("self-test (negative): comments, strings, other tables and the helper are not flagged") {
            hits("// MemberTable.selectAll().forUpdate()") shouldBe 0
            hits("/** MemberTable.selectAll().where { x }.forUpdate() */ val a = 1") shouldBe 0
            hits("val s = \"MemberTable.selectAll().forUpdate()\"") shouldBe 0
            hits("MemberEmailChangeTable.selectAll().where { x }.forUpdate().singleOrNull()") shouldBe 0
            hits("ElectionBoardMemberTable.selectAll().forUpdate().toList()") shouldBe 0
            hits("TrustAnchorPoolMemberTable.selectAll().where { x }.forUpdate().toList()") shouldBe 0
            hits("MemberTable.selectAll().where { MemberTable.id eq x }.forMemberUpdate().singleOrNull()") shouldBe 0
            hits(
                """
                val m = MemberTable.selectAll().where { MemberTable.id eq x }.singleOrNull()
                val p = PoliticianProfileTable.selectAll().where { y }.forUpdate().singleOrNull()
                """.trimIndent(),
            ) shouldBe 0
            hits(
                """
                fun a() { MemberTable.selectAll().toList() }

                fun b() { LedgerAccountTable.selectAll().forUpdate().toList() }
                """.trimIndent(),
            ) shouldBe 0
            MemberRowLockScan.writesMemberKeyColumn(
                SourceScan.blank("MemberTable.update({ MemberTable.id eq x }) { it[status] = y }"),
            ) shouldBe
                false
            MemberRowLockScan.writesMemberKeyColumn(SourceScan.blank("// MemberTable.update({ x }) { it[email] = y }")) shouldBe false
            MemberRowLockScan.writesMemberKeyColumn(SourceScan.blank("MemberEmailChangeTable.update({ x }) { it[email] = y }")) shouldBe
                false
        }
    })

/** Pure source detectors of [MemberRowLockTripwireTest] (input: [SourceScan.blank]ed code). */
internal object MemberRowLockScan {
    private val member = """(?<![A-Za-z0-9_])MemberTable(?![A-Za-z0-9_])"""
    private val forUpdateCall = Regex("""\.\s*forUpdate\s*\(""")
    private val memberStart = Regex(member)
    private val joinOnMember = Regex("""$member\s+(inner|left|right|cross)Join\b|\b(inner|left|right|cross)Join\s+$member""")

    /** Offsets of every `.forUpdate(` whose receiver query is built from `MemberTable`. */
    fun memberForUpdateOffsets(code: String): List<Int> =
        forUpdateCall
            .findAll(code)
            .filter { m -> receiverIsMemberQuery(code = code, callAt = m.range.first) }
            .map { it.range.first }
            .toList()

    private fun receiverIsMemberQuery(
        code: String,
        callAt: Int,
    ): Boolean {
        // Receiver is a plain identifier (`query.forUpdate()`): resolve its `val` in the same function.
        val ident = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""").find(code.substring((callAt - 80).coerceAtLeast(0), callAt))
        if (ident != null) {
            val name = ident.groupValues[1]
            val before = code.substring((callAt - 1500).coerceAtLeast(0), callAt)
            val decl = Regex("""\bva[lr]\s+$name\s*(:[^=\n]+)?=\s*""").findAll(before).lastOrNull() ?: return false
            return chainFrom(before.substring(decl.range.last + 1)).let { startsWithMember(it) }
        }
        // Receiver is a call chain: walk back to the start of the statement.
        val windowStart = (callAt - 900).coerceAtLeast(0)
        val statement = code.substring(statementStart(code = code, windowStart = windowStart, callAt = callAt), callAt)
        if (joinOnMember.containsMatchIn(statement) && Regex("""\bselect(All)?\b""").containsMatchIn(statement)) return true
        return startsWithMember(statement)
    }

    /** The chain text of a `val x = <expr>` initializer: up to the first blank line or the next statement keyword. */
    private fun chainFrom(rest: String): String = rest.split(Regex("""\n\s*\n|\n\s*(val|var|fun|if|for|return)\b""")).first()

    private val memberHead =
        Regex("""^\s*(?:(?:va[lr]\s+\w+\s*(?::[^=]+)?=|\w+\s*=|return)\s*)?\(?\s*$member""")

    private fun startsWithMember(statement: String): Boolean = memberHead.containsMatchIn(statement) && balanced(statement)

    /**
     * Index in [code] where the statement that ends at [callAt] begins (not before [windowStart]): walk backwards, crossing balanced `(...)`
     * and `{...}`; stop at an unbalanced opener, a `;`, or a line break after which the next line does not continue a call
     * chain (does not start with `.` / `?.`).
     */
    private fun statementStart(
        code: String,
        windowStart: Int,
        callAt: Int,
    ): Int {
        var braces = 0
        var parens = 0
        var i = callAt - 1
        while (i >= windowStart) {
            when (code[i]) {
                '}' -> braces++
                '{' -> if (braces > 0) braces-- else return i + 1
                ')' -> parens++
                '(' -> if (parens > 0) parens-- else return i + 1
                ';' -> if (braces == 0 && parens == 0) return i + 1
                '\n' ->
                    if (braces == 0 && parens == 0) {
                        val next = code.substring(i + 1).trimStart(' ', '\t')
                        if (!next.startsWith(".") && !next.startsWith("?.")) return i + 1
                    }
            }
            i--
        }
        return windowStart
    }

    private fun balanced(s: String): Boolean =
        s.count { it == '(' } - s.count { it == ')' } in 0..1 && s.count { it == '{' } == s.count { it == '}' }

    private val updateCall = Regex("""$member\s*\.\s*update\s*\(""")
    private val keyAssign = Regex("""\bit\s*\[\s*(MemberTable\s*\.\s*)?(email|memberNumber|id)\s*]""")

    /** `true` when [code] contains a `MemberTable.update(...) { ... }` block that assigns `id`, `email` or `memberNumber`. */
    fun writesMemberKeyColumn(code: String): Boolean =
        updateCall.findAll(code).any { m ->
            var i = m.range.last + 1
            var depth = 1
            while (i < code.length && depth > 0) {
                if (code[i] == '(') {
                    depth++
                } else if (code[i] == ')') {
                    depth--
                }
                i++
            }
            val open = code.indexOf('{', i)
            if (open == -1 || code.substring(i, open).isNotBlank()) return@any false
            var j = open
            var braces = 0
            while (j < code.length) {
                if (code[j] == '{') {
                    braces++
                } else if (code[j] == '}') {
                    braces--
                }
                j++
                if (braces == 0) break
            }
            keyAssign.containsMatchIn(code.substring(open, j))
        }
}
