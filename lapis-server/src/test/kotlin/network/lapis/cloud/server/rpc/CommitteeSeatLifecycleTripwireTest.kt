package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.102 -- source-scan tripwire for the automatic end of committee seats:
 *
 *  1. every file that writes `member.status` is CLASSIFIED: it either calls [CommitteeSeatLifecycle.endSeatsForStatusChange] (a pinned number
 *     of times) or carries a reason why it never can hold a committee seat (it only inserts brand-new members). A new status writer fails
 *     here and forces the decision;
 *  2. the hook is called from exactly three places and every call is followed by its phase 2 (`recordAudits()`) in the same function;
 *  3. `committee_membership.until` / `auto_end_reason` are written only by an allowlisted set of files (with a reason each) -- the seat hook
 *     (member status change and, since V1.9.112, the switch to rule-based membership), the manual path, the election re-seating and the
 *     staging seed; `auto_end_reason` is set to a non-null value only by `CommitteeSeatLifecycle`;
 *  4. no raw SQL updates `committee_membership` in `src/main`;
 *  5. (V1.9.103) `started_by_election_id` / `ended_by_election_id` are written only by `ElectionSeatSuccession` (and reset to null by the
 *     manual end in `GovernanceService`), `ElectionSeatSuccession.apply` is called from exactly one place (`tallyElectionLocked`), the
 *     succession never writes an account or member role, and no `ElectionTable.update` ever changes `incumbent_policy`, `target_role` or
 *     `target_committee_id` (immutable after opening).
 */
class CommitteeSeatLifecycleTripwireTest :
    FunSpec({
        // status-writing file -> (expected calls of the hook, reason)
        val statusWriters =
            mapOf(
                "network/lapis/cloud/server/member/MemberRoleStatusMutations.kt" to
                    (1 to "the central status path (admin UI, four-eyes execution, operator console)"),
                "network/lapis/cloud/server/rpc/RegistrationService.kt" to
                    (2 to "leaveMembership and rejectApplication end seats; the other writers there create or promote members"),
                "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt" to (0 to "inserts new members only: a new member has no seats"),
                "network/lapis/cloud/server/bootstrap/AdminBootstrap.kt" to (0 to "creates the first admin: no seats"),
                "network/lapis/cloud/server/federation/OidcGuestMemberStore.kt" to (0 to "creates a guest: no seats"),
                "network/lapis/cloud/server/keycloak/KeycloakMemberProvisioner.kt" to (0 to "just-in-time creation: no seats"),
                "network/lapis/cloud/server/db/DevSeedData.kt" to (0 to "dev seed: inserts"),
                "network/lapis/cloud/server/db/StagingSeedData.kt" to (0 to "staging seed: inserts"),
            )
        // files that may write committee_membership.until / auto_end_reason, with the reason
        val seatWriters =
            mapOf(
                "network/lapis/cloud/server/rpc/GovernanceService.kt" to
                    "addCommitteeMember (insert) and the manual end (resets auto_end_reason)",
                "network/lapis/cloud/server/rpc/CommitteeSeatLifecycle.kt" to
                    "the automatic end (member status change) and, since V1.9.112, the end by the switch to rule-based membership (RULE_MODE)",
                "network/lapis/cloud/server/rpc/ElectionService.kt" to
                    "legacy path (elections without an incumbent policy): closes the previous seat when a winner is re-elected",
                "network/lapis/cloud/server/rpc/ElectionSeatSuccession.kt" to
                    "V1.9.103: the tally-time succession of an election with an incumbent policy",
                "network/lapis/cloud/server/db/StagingSeedGovernance.kt" to "staging seed insert",
            )
        val hookCall = Regex("""CommitteeSeatLifecycle\s*\.\s*endSeatsForStatusChange\s*\(""")
        val statusWrite =
            Regex("""(?<![A-Za-z0-9_])MemberTable\.(update|upsert|replace|batchUpsert|batchReplace|batchInsert|insertIgnore|insert)\b""")
        val statusAssignment = Regex("""\[\s*(MemberTable\.)?status\s*]\s*=""")
        val seatWrite =
            Regex("""(?<![A-Za-z0-9_])CommitteeMembershipTable\.(update|insert|upsert|replace|batchInsert|batchUpsert|insertIgnore)\b""")
        val untilAssignment = Regex("""\[\s*(CommitteeMembershipTable\.)?until\s*]\s*=""")
        val reasonAssignment = Regex("""\[\s*(CommitteeMembershipTable\.)?autoEndReason\s*]\s*=\s*([^\n]+)""")
        val funKeyword = Regex("""(?<![A-Za-z0-9_])fun\s""")
        val rawSql = Regex("""(?is)update\s+"?committee_membership"?\s+set\b""")

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        fun sources(): Map<String, String> = SourceScan.mainFiles().associate { relative(it) to SourceScan.blank(it.readText()) }

        fun windowAfter(
            code: String,
            at: Int,
        ): String = code.substring(at, minOf(code.length, at + 2500))

        test("every file that writes member.status is classified, and the hook is called exactly as often as pinned") {
            val src = sources()
            val writers =
                src
                    .filter { (_, code) ->
                        statusWrite.findAll(code).any { statusAssignment.containsMatchIn(windowAfter(code, it.range.last)) }
                    }.keys
            (writers - statusWriters.keys).shouldBeEmpty()
            (statusWriters.keys - writers).shouldBeEmpty()
            statusWriters.forEach { (path, expected) ->
                hookCall.findAll(src.getValue(path)).count() shouldBe expected.first
            }
        }

        test("the hook is called from exactly three places and nowhere else (the definition itself excepted)") {
            val total =
                sources()
                    .filterKeys { it != "network/lapis/cloud/server/rpc/CommitteeSeatLifecycle.kt" }
                    .values
                    .sumOf { hookCall.findAll(it).count() }
            total shouldBe 3
        }

        test("every hook call is followed by recordAudits() before the next function starts (phase 2 exists)") {
            sources()
                .filterKeys { it != "network/lapis/cloud/server/rpc/CommitteeSeatLifecycle.kt" }
                .forEach { (path, code) ->
                    hookCall.findAll(code).forEach { call ->
                        val end = call.range.last
                        val nextFun = funKeyword.find(code, end)?.range?.first ?: code.length
                        withClueOf(path = path) { code.substring(end, nextFun).contains("recordAudits()") shouldBe true }
                    }
                }
        }

        test("committee_membership.until / auto_end_reason are written only by the allowlisted files") {
            val offenders = mutableListOf<String>()
            sources().forEach { (path, code) ->
                val writes =
                    seatWrite.findAll(code).filter { m ->
                        val body = windowAfter(code, m.range.last)
                        untilAssignment.containsMatchIn(body) || reasonAssignment.containsMatchIn(body)
                    }
                if (writes.any() && path !in seatWriters) offenders += path
            }
            offenders.shouldBeEmpty()
        }

        test("a non-null auto_end_reason is assigned only by the hook; the manual path assigns null") {
            sources().forEach { (path, code) ->
                seatWrite.findAll(code).forEach { m ->
                    val body = windowAfter(code, m.range.last).substringBefore("\n    }\n")
                    reasonAssignment.findAll(body).forEach { assignment ->
                        val value = assignment.groupValues[2].trim()
                        if (path == "network/lapis/cloud/server/rpc/CommitteeSeatLifecycle.kt") {
                            (value != "null") shouldBe true
                        } else if (path in seatWriters) {
                            // only the StagingSeed or GovernanceService may assign, and then only null / a seeded explicit value is forbidden
                            withClueOf(path = path) { value shouldBe "null" }
                        }
                    }
                }
            }
        }

        test("V1.9.103: the election provenance columns are written only by the succession (and nulled by the manual end)") {
            val provenance = Regex("""\[\s*(CommitteeMembershipTable\.)?(startedByElectionId|endedByElectionId)\s*]\s*=\s*([^\n]+)""")
            val allowedWriters =
                setOf(
                    "network/lapis/cloud/server/rpc/ElectionSeatSuccession.kt",
                    "network/lapis/cloud/server/rpc/GovernanceService.kt",
                    // V1.9.112: endSeatsForRuleMode resets it to null (a seat has one end cause only)
                    "network/lapis/cloud/server/rpc/CommitteeSeatLifecycle.kt",
                )
            sources().forEach { (path, code) ->
                seatWrite.findAll(code).forEach { m ->
                    val body = windowAfter(code, m.range.last).substringBefore("\n    }\n")
                    provenance.findAll(body).forEach { assignment ->
                        withClueOf(path = path) { (path in allowedWriters) shouldBe true }
                        if (path.endsWith("GovernanceService.kt") || path.endsWith("CommitteeSeatLifecycle.kt")) {
                            withClueOf(path = path) { assignment.groupValues[3].trim() shouldBe "null" }
                        }
                    }
                }
            }
            // the legacy loop and the staging seed never set them (they would be provenance claims without a succession log)
            listOf("rpc/ElectionService.kt", "db/StagingSeedGovernance.kt").forEach {
                provenance.containsMatchIn(sources().getValue("network/lapis/cloud/server/$it")) shouldBe false
            }
        }

        test("V1.9.103: ElectionSeatSuccession.apply has one caller (tallyElectionLocked); the succession never touches roles") {
            val call = Regex("""ElectionSeatSuccession\s*\.\s*apply\s*\(""")
            val callers =
                sources()
                    .filterKeys { it != "network/lapis/cloud/server/rpc/ElectionSeatSuccession.kt" }
                    .filter { (_, code) -> call.containsMatchIn(code) }
            callers.keys.toList() shouldBe listOf("network/lapis/cloud/server/rpc/ElectionService.kt")
            call.findAll(callers.values.single()).count() shouldBe 1
            val tally = callers.values.single().let { it.substring(it.indexOf("internal fun tallyElectionLocked(")) }
            call.containsMatchIn(tally) shouldBe true
            val succession = sources().getValue("network/lapis/cloud/server/rpc/ElectionSeatSuccession.kt")
            Regex("""(?<![A-Za-z0-9_])(AccountTable|MemberTable)\s*\.\s*(update|insert|upsert|replace|delete\w*|batch\w+)\b""")
                .containsMatchIn(succession) shouldBe false
            Regex("""AccountTable""").containsMatchIn(succession) shouldBe false
        }

        test("V1.9.103: an election's incumbent_policy, target_role and target_committee_id are immutable after opening") {
            val immutableAssignment =
                Regex("""\[\s*(ElectionTable\.)?(incumbentPolicy|targetRole|targetCommitteeId)\s*]\s*=""")
            val electionUpdate = Regex("""(?<![A-Za-z0-9_])ElectionTable\.(update|upsert|replace|batchUpsert|batchReplace)\b""")
            sources().forEach { (path, code) ->
                electionUpdate.findAll(code).forEach { m ->
                    val body = windowAfter(code, m.range.last).substringBefore("\n    }\n").substringBefore("\n        }\n")
                    withClueOf(path = path) { immutableAssignment.containsMatchIn(body) shouldBe false }
                }
            }
        }

        test("no raw SQL updates committee_membership in src/main") {
            sources().filterValues { rawSql.containsMatchIn(it) }.keys.shouldBeEmpty()
        }

        test("FoundationPersonalData never writes member.status (it only exports it)") {
            val code = sources().getValue("network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt")
            statusWrite.findAll(code).none { statusAssignment.containsMatchIn(windowAfter(code, it.range.last)) } shouldBe true
        }
    })

private inline fun withClueOf(
    path: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$path: ${e.message}", e)
    }
}
