package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.298 -- source-scan tripwire: `member.voting_right`, `member.voting_right_source` and `member.voting_right_changed_at` have ONE
 * writer, `MemberVotingRights`. The only other places that may assign the columns are the DSGVO erasure (resets to the defaults in the same
 * `update` that clears the personal columns) and the LupaBB applier's INSERT of a new member (the values come from
 * `MemberVotingRights.insertValues`). The three mutating entry points may only be called by `MemberService` (administrator, ADMIN-only, never
 * the own row) and `LupaBbApplier` (the sync). The set of files with findings must equal the allowlist EXACTLY.
 */
class MemberVotingRightWriterTripwireTest :
    FunSpec({
        val columnWrite = Regex("""\bit\[\s*(MemberTable\.)?votingRight(Source|ChangedAt)?\s*]\s*=""")
        val allowedWrites =
            mapOf(
                "network/lapis/cloud/server/member/MemberVotingRights.kt" to "the one writer",
                "network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt" to
                    "Art. 17 anonymisation: resets to the defaults in the same update",
                "network/lapis/cloud/server/lupabb/LupaBbApplier.kt" to
                    "INSERT of a new member; the values come from MemberVotingRights.insertValues",
            )
        val mutatingCall = Regex("""\bMemberVotingRights\.(setManualLocked|releaseToSyncLocked|applyFromSyncLocked)\s*\(""")
        val allowedCallers =
            mapOf(
                "network/lapis/cloud/server/rpc/MemberService.kt" to "ADMIN-only RPCs: never the own row, member row lock, audit",
                "network/lapis/cloud/server/lupabb/LupaBbApplier.kt" to "the sync: member row lock, fingerprint, never over a MANUAL value",
            )

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        fun findings(regex: Regex): Map<String, Int> =
            SourceScan
                .mainFiles()
                .associate { relative(it) to regex.findAll(SourceScan.blank(it.readText())).count() }
                .filterValues { it > 0 }

        test("only the allowlisted files assign the voting-right columns (exact)") {
            findings(columnWrite).keys shouldBe allowedWrites.keys
        }

        test("only MemberService and LupaBbApplier call the mutating entry points of MemberVotingRights (exact)") {
            findings(mutatingCall).keys shouldBe allowedCallers.keys
        }

        test("the other places read the columns but never write them: no raw SQL on the columns outside the migration") {
            val rawSql = Regex("""voting_right""")
            // strings are blanked by SourceScan.blank, so scan the raw text of the main sources for SQL fragments
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter {
                        rawSql.containsMatchIn(
                            it.readText(),
                        ) &&
                            !relative(it).startsWith("network/lapis/cloud/server/db/generated/")
                    }.map { relative(it) }
                    .filterNot { it.endsWith("MemberVotingRights.kt") || it.endsWith("FoundationPersonalData.kt") }
            // generated tables and the DSGVO/Votes may mention the column name in comments only; a raw UPDATE/INSERT would be a finding
            offenders.filter {
                Regex("""\b(UPDATE|INSERT INTO)\b[^;]*voting_right""").containsMatchIn(File(SourceScan.mainRoot(), it).readText())
            } shouldBe
                emptyList()
        }
    })
