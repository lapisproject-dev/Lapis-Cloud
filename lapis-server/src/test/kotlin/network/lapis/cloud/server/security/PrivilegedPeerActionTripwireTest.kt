package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- source-scan tripwire: **every code path that can take over, lock out, expose or erase
 * ANOTHER account goes through the peer protection** ([PeerGuard] / [PeerPolicy]), or sits on an allowlist WITH a reason.
 *
 * Sinks (scanned in `src/main/kotlin`, comments and string contents blanked, brace matching, no parser):
 * 1. `AccountTable.update/insert` assigning `passwordHash` or `role`;
 * 2. `PasswordResetTokenStore.createToken(` (a reset link is an account takeover for whoever reads the mailbox);
 * 3. `MemberTable.update` assigning `street`, `postalCode`, `city`, `country`, `dateOfBirth`, `nationality` or `status`;
 * 4. `KeycloakAccountLinkTable.insert` (an identity-provider identity that may log in as the member);
 * 5. `.erase(subject` on the personal-data registry (GDPR erasure hard-deletes the account);
 * 6. reads of `MemberTable.street|postalCode|city|country|dateOfBirth|nationality` in `rpc/` and `routes/`;
 * 7. raw SQL `UPDATE account SET ... password_hash|role`.
 *
 * Rule: a sink is either inside a FUNCTION whose body mentions `PeerGuard.`, `peerGuarded(` or `PeerPolicy.decide`, or its file is
 * on the allowlist with a reason. The two mutation objects the guarded callers delegate to ([network.lapis.cloud.server.member
 * .MemberRoleStatusMutations], [network.lapis.cloud.server.member.TemporaryPasswordMutation]) are allowlisted -- and so that this
 * is not a loophole, every CALL of their entry points must itself sit in a guarded function (or the operator console).
 *
 * The scanner is pinned: the set of files with findings must equal {guarded} U {allowlist} exactly, so "no findings" can never
 * just mean "the scanner sees nothing", and a stale allowlist entry fails the build.
 */
class PrivilegedPeerActionTripwireTest :
    FunSpec({
        // Files whose every finding is inside a guarded function.
        val guardedFiles =
            setOf(
                "network/lapis/cloud/server/rpc/MemberService.kt",
                "network/lapis/cloud/server/rpc/DsgvoService.kt",
                "network/lapis/cloud/server/rpc/KeycloakLinkService.kt",
            )

        // Keys are "path" (the whole file) or "path#function" (that function only), relative to src/main/kotlin -> why an unguarded sink
        // there is fine.
        val allowlist =
            mapOf(
                "network/lapis/cloud/server/member/MemberRoleStatusMutations.kt" to
                    "the ONE write of a role / status; every caller takes the peer decision first (checked by the call-site test below)",
                "network/lapis/cloud/server/member/TemporaryPasswordMutation.kt" to
                    "the ONE write of a temporary password; every caller takes the peer decision first (call-site test below)",
                "network/lapis/cloud/server/rpc/AuthService.kt#changePassword" to
                    "the signed-in member changes their OWN password",
                "network/lapis/cloud/server/routes/AuthRoutes.kt" to "password reset: the bearer of the one-time token set a new password",
                "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt" to
                    "the operator console: shell access to the deployment, no network path",
                "network/lapis/cloud/server/db/DevSeedData.kt" to "dev seed, NEW accounts in an empty database",
                "network/lapis/cloud/server/db/StagingSeedData.kt" to "staging seed, NEW accounts in an empty database",
                "network/lapis/cloud/server/rpc/RegistrationService.kt" to
                    "creates NEW members/accounts and decides applications; createMemberDirect(ADMIN) tells every other administrator",
                "network/lapis/cloud/server/federation/OidcGuestMemberStore.kt" to "OIDC guest: creates a NEW synthetic guest member",
                "network/lapis/cloud/server/keycloak/KeycloakMemberProvisioner.kt" to
                    "Keycloak just-in-time provisioning: creates a NEW MEMBER account (literal role) and its link; touches no existing account",
                "network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt" to
                    "Art. 17 anonymization, reachable only through DsgvoService.executeErasure (guarded)",
                "network/lapis/cloud/server/rpc/CrmService.kt#eraseContact" to
                    "erasure of a CRM contact: a non-member data subject without an account or a role",
                "network/lapis/cloud/server/keycloak/KeycloakAccountLinker.kt" to
                    "automatic link at the identity provider's own login when the VERIFIED address matches; the manual link is guarded",
            )

        // Reads of address / beneficial-owner columns that are fine, with the reason ("path" or "path#function").
        val readAllowlist =
            mapOf(
                "network/lapis/cloud/server/rpc/MemberService.kt#requirePlausibleDeathDate" to
                    "date of birth only feeds the plausibility check",
                "network/lapis/cloud/server/rpc/MemberService.kt#toMemberDto" to
                    "projection used for the caller's OWN view and for writers who already passed the peer decision",
                "network/lapis/cloud/server/rpc/BoardMemberMapService.kt" to
                    "the board map shows postal-code centroids of board members: a published feature, no address leaves",
                "network/lapis/cloud/server/rpc/MemberFamilyService.kt" to
                    "age computation of the family membership rules, no value leaves",
                "network/lapis/cloud/server/rpc/BoardMembershipService.kt#getTransparenzregisterReport" to
                    "only checks WHETHER a value is present (data-gap list), no value leaves",
                "network/lapis/cloud/server/rpc/MemberAnniversaryService.kt#getUpcomingAnniversaries" to
                    "anniversary list for BOARD/ADMIN -- documented limitation (CHANGELOG, Known limitations)",
                "network/lapis/cloud/server/routes/MailmergeRoutes.kt#toMailmergeMemberDto" to
                    "the postal address of recipients is the purpose of a mail merge -- documented limitation (CHANGELOG, Known limitations)",
            )

        val guardMarker = Regex("""PeerGuard\.|peerGuarded\s*\(|PeerPolicy\.decide""")
        val memberWriteColumns = "street|postalCode|city|country|dateOfBirth|nationality|status"
        val accountWrite = Regex("""(?<![A-Za-z0-9_])AccountTable\.(update|insert|upsert|replace|batchInsert|batchUpsert)\b""")
        val accountAssignment = Regex("""\[\s*(AccountTable\.)?(passwordHash|role)\s*]\s*=""")
        val memberWrite = Regex("""(?<![A-Za-z0-9_])MemberTable\.(update|upsert|replace|batchUpsert)\b""")
        val memberAssignment = Regex("""\[\s*(MemberTable\.)?($memberWriteColumns)\s*]\s*=""")
        val resetToken = Regex("""PasswordResetTokenStore\.createToken\s*\(""")
        val keycloakInsert = Regex("""KeycloakAccountLinkTable\.(insert|upsert|batchInsert)\b""")
        val erase = Regex("""\.erase\s*\(\s*subject\b""")
        val addressRead = Regex("""(?<![A-Za-z0-9_])MemberTable\.(street|postalCode|city|country|dateOfBirth|nationality)\b""")
        val rawAccountSql = Regex("""(?is)update\s+"?account"?\s+set\b[^;]*?\b(password_hash|role)\b""")
        val mutationCall =
            Regex(
                """(?<![A-Za-z0-9_])(MemberRoleStatusMutations\.(applyRoleChangeLocked|applyStatusChangeLocked)|TemporaryPasswordMutation\.applyLocked)\b""",
            )

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

        data class FunSpan(
            val name: String,
            val range: IntRange,
        )

        /** Every function declaration: block bodies by brace matching, expression bodies up to the next function. */
        fun functionSpans(code: String): List<FunSpan> {
            val decl = Regex("""(?<![A-Za-z0-9_])fun\s+(<[^>]*>\s*)?([A-Za-z0-9_.<>?, ]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
            val starts = decl.findAll(code).map { it.range.first }.toList()
            return decl
                .findAll(code)
                .map { m ->
                    val paramsOpen = m.range.last
                    val paramsClose = matching(code, paramsOpen, '(', ')')
                    var k = paramsClose + 1
                    // skip return type / throws / where up to '{' or '='
                    while (k < code.length && code[k] != '{' && code[k] != '=') k++
                    val end =
                        if (k < code.length && code[k] == '{') {
                            matching(code, k, '{', '}')
                        } else {
                            starts.firstOrNull { it > m.range.first }?.minus(1) ?: (code.length - 1)
                        }
                    FunSpan(name = m.groupValues[3], range = m.range.first..end)
                }.toList()
        }

        data class Finding(
            val file: String,
            val function: String,
            val kind: String,
            val line: Int,
            val guarded: Boolean,
        )

        fun innermost(
            spans: List<FunSpan>,
            index: Int,
        ): FunSpan? = spans.filter { index in it.range }.maxByOrNull { it.range.first }

        fun innermostGuarded(
            code: String,
            spans: List<FunSpan>,
            index: Int,
        ): Boolean {
            val span = innermost(spans, index) ?: return false
            return guardMarker.containsMatchIn(code.substring(span.range.first, span.range.last + 1))
        }

        /** The allowlist key that covers [f], or null. */
        fun allowedBy(
            allowed: Map<String, String>,
            f: Finding,
        ): String? = listOf("${f.file}#${f.function}", f.file).firstOrNull { it in allowed.keys }

        fun scanWrites(): List<Finding> {
            val root = SourceScan.mainRoot()
            val out = mutableListOf<Finding>()
            SourceScan.mainFiles().forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (relative.contains("/db/generated/")) return@forEach
                val src = file.readText()
                val code = SourceScan.blank(src)
                val spans = functionSpans(code)

                fun add(
                    kind: String,
                    index: Int,
                ) {
                    out +=
                        Finding(
                            file = relative,
                            function = innermost(spans, index)?.name ?: "<top level>",
                            kind = kind,
                            line = SourceScan.lineOf(text = code, index = index),
                            guarded = innermostGuarded(code, spans, index),
                        )
                }
                accountWrite.findAll(code).forEach { m ->
                    val body = lambdaBody(code, m.range.last + 1) ?: return@forEach
                    if (accountAssignment.containsMatchIn(body)) add("AccountTable.${m.groupValues[1]}", m.range.first)
                }
                memberWrite.findAll(code).forEach { m ->
                    val body = lambdaBody(code, m.range.last + 1) ?: return@forEach
                    if (memberAssignment.containsMatchIn(body)) add("MemberTable.${m.groupValues[1]}", m.range.first)
                }
                resetToken.findAll(code).forEach { add("PasswordResetTokenStore.createToken", it.range.first) }
                keycloakInsert.findAll(code).forEach { add("KeycloakAccountLinkTable.${it.groupValues[1]}", it.range.first) }
                erase.findAll(code).forEach { add("erase(subject", it.range.first) }
                val raw =
                    src
                        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { " ".repeat(it.value.length) }
                        .replace(Regex("""(?m)^\s*//.*$""")) { " ".repeat(it.value.length) }
                rawAccountSql.findAll(raw).forEach { m ->
                    out +=
                        Finding(
                            relative,
                            "<raw sql>",
                            "raw SQL on account",
                            SourceScan.lineOf(text = raw, index = m.range.first),
                            guarded = false,
                        )
                }
            }
            return out
        }

        fun scanReads(): List<Finding> {
            val root = SourceScan.mainRoot()
            val out = mutableListOf<Finding>()
            SourceScan.mainFiles().forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (!relative.startsWith("network/lapis/cloud/server/rpc/") &&
                    !relative.startsWith("network/lapis/cloud/server/routes/")
                ) {
                    return@forEach
                }
                val code = SourceScan.blank(file.readText())
                val spans = functionSpans(code)
                addressRead.findAll(code).forEach { m ->
                    out +=
                        Finding(
                            file = relative,
                            function = innermost(spans, m.range.first)?.name ?: "<top level>",
                            kind = "read MemberTable.${m.groupValues[1]}",
                            line = SourceScan.lineOf(text = code, index = m.range.first),
                            guarded = innermostGuarded(code, spans, m.range.first),
                        )
                }
            }
            return out
        }

        test(
            "every write sink is guarded, or covered by an allowlist entry with a reason -- and the allowlist is exact (no stale entries)",
        ) {
            val findings = scanWrites()
            val unguarded = findings.filter { !it.guarded }
            val unexpected = unguarded.filter { allowedBy(allowlist, it) == null }
            unexpected.map { "${it.file}#${it.function}:${it.line} ${it.kind}" }.joinToString("\n") shouldBe ""
            // pins: no stale allowlist entry, and the files whose sinks are ALL guarded are exactly the expected ones
            val used = unguarded.mapNotNull { allowedBy(allowlist, it) }.toSet()
            (allowlist.keys - used).shouldBeEmpty()
            val guardedOnlyFiles = findings.map { it.file }.toSet() - unguarded.map { it.file }.toSet()
            guardedOnlyFiles shouldBe guardedFiles
        }

        test("every call of the mutation entry points sits in a guarded function (or the operator console)") {
            val root = SourceScan.mainRoot()
            val offenders = mutableListOf<String>()
            var calls = 0
            SourceScan.mainFiles().forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (relative.endsWith("MemberRoleStatusMutations.kt") || relative.endsWith("TemporaryPasswordMutation.kt")) return@forEach
                val code = SourceScan.blank(file.readText())
                val spans = functionSpans(code)
                mutationCall.findAll(code).forEach { m ->
                    calls++
                    val consoleOk = relative.endsWith("bootstrap/AdminBootstrap.kt")
                    if (!consoleOk && !innermostGuarded(code, spans, m.range.first)) {
                        offenders += "$relative:${SourceScan.lineOf(text = code, index = m.range.first)} ${m.value}"
                    }
                }
            }
            offenders.shouldBeEmpty()
            // updateMemberRole, updateMemberStatus, setTemporaryPasswordForMember (via the mutation objects), approve (x2) + execute,
            // the console (set-role, set-status): the scanner must really see call sites
            (calls >= 6) shouldBe true
        }

        test("reads of address and beneficial-owner columns in rpc/ and routes/ are guarded or allowlisted with a reason (exact)") {
            val findings = scanReads()
            val unguarded = findings.filter { !it.guarded }
            unguarded
                .filter { allowedBy(readAllowlist, it) == null }
                .map { "${it.file}#${it.function}:${it.line} ${it.kind}" }
                .joinToString("\n") shouldBe ""
            (readAllowlist.keys - unguarded.mapNotNull { allowedBy(readAllowlist, it) }.toSet()).shouldBeEmpty()
            // the protected read of the member administration IS guarded
            findings.any {
                it.file.endsWith(
                    "rpc/MemberService.kt",
                ) &&
                    it.function == "getMemberAddressForAdministration" &&
                    it.guarded
            } shouldBe
                true
        }

        test("no network-reachable code path writes the credential columns through raw SQL") {
            scanWrites().filter { it.kind == "raw SQL on account" }.shouldBeEmpty()
        }

        test("the scanner itself sees planted violations (self-test)") {
            val planted =
                """
                class Bad {
                    fun takeOver(id: Uuid) {
                        transaction {
                            AccountTable.update({ AccountTable.memberId eq id }) { it[passwordHash] = "x" }
                            MemberTable.update({ MemberTable.id eq id }) { it[MemberTable.status] = MemberStatus.WITHDRAWN }
                            PasswordResetTokenStore.createToken(id)
                        }
                    }

                    fun fine(id: Uuid) {
                        PeerGuard.require(actor, id, PeerAction.RESET_MAIL, false)
                        PasswordResetTokenStore.createToken(id)
                    }
                }
                """.trimIndent()
            val code = SourceScan.blank(planted)
            val spans = functionSpans(code)
            val hits = mutableListOf<Boolean>()
            accountWrite.findAll(code).forEach { m ->
                if (accountAssignment.containsMatchIn(lambdaBody(code, m.range.last + 1)!!)) {
                    hits +=
                        innermostGuarded(code, spans, m.range.first)
                }
            }
            memberWrite.findAll(code).forEach { m ->
                if (memberAssignment.containsMatchIn(lambdaBody(code, m.range.last + 1)!!)) {
                    hits +=
                        innermostGuarded(code, spans, m.range.first)
                }
            }
            resetToken.findAll(code).forEach { hits += innermostGuarded(code, spans, it.range.first) }
            hits shouldBe listOf(false, false, false, true)
            rawAccountSql.containsMatchIn("""exec("UPDATE account SET role = 'ADMIN' WHERE id = 1")""") shouldBe true
            rawAccountSql.containsMatchIn("""exec("UPDATE account SET oidc_subject = 'a'")""") shouldBe false
            mutationCall.containsMatchIn("MemberRoleStatusMutations.applyRoleChangeLocked(") shouldBe true
        }
    })
