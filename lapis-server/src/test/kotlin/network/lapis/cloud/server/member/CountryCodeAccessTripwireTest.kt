package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.121 -- source-scan tripwire: `member.country_code` and `member_nationality` are personal data (GwG beneficial-owner
 * data). Only a short, reasoned list of files may touch them, and the WRITING entry points of [MemberCountryStore] may only be
 * called from files that take the peer decision first or are erasure / import / reconciliation code.
 *
 * The set of files with findings must equal the allowlist EXACTLY, so "no findings" cannot mean "the scanner sees nothing" and a
 * stale entry fails the build.
 */
class CountryCodeAccessTripwireTest :
    FunSpec({
        // path (relative to src/main/kotlin) -> why the file may touch the columns / the table directly
        val allowedAccess =
            mapOf(
                "network/lapis/cloud/server/member/MemberCountryStore.kt" to "the one reader/writer",
                "network/lapis/cloud/server/member/CountryLegacyMappingEngine.kt" to
                    "bulk reconciliation: BOARD/ADMIN only, skips ADMIN accounts, compare-and-set under the member row lock",
                "network/lapis/cloud/server/dsgvo/MemberNationalityPersonalData.kt" to
                    "owns member_nationality (Art. 15 export / Art. 17 erasure)",
                "network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt" to
                    "Art. 15 export and Art. 17 anonymisation of the member row",
                "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt" to "operator console: inserts NEW members",
                "network/lapis/cloud/server/lupabb/LupaBbMemberLoader.kt" to
                    "V1.9.226: reads the country code for the LupaBB comparison (ADMIN only, never leaves the server except as a code in the preview)",
                "network/lapis/cloud/server/db/StagingSeedData.kt" to "staging seed, NEW members in an empty database",
                "network/lapis/cloud/server/rpc/MemberService.kt" to "peer-guarded writes; the caller's own / privileged projections",
                "network/lapis/cloud/server/rpc/BoardMemberMapService.kt" to "aggregate counts only, no value leaves",
                "network/lapis/cloud/server/rpc/BoardMembershipService.kt" to "presence flag only (missingCountry), no value leaves",
                "network/lapis/cloud/server/routes/MailmergeRoutes.kt" to "the recipient's postal address is the purpose of a mail merge",
                "network/lapis/cloud/server/memberfinder/MemberFinderEligibility.kt" to
                    "V1.9.144: derives the finder region on every query (only 'is it DE', never stored, never returned) -- the one finder reader",
            )

        val access =
            Regex("""\bMemberNationalityTable\b|(?<![A-Za-z0-9_])MemberTable\.countryCode\b|\bit\[\s*(MemberTable\.)?countryCode\s*]""")
        val writingCall = Regex("""\bMemberCountryStore\.(setCountry|setCountryKeepLegacy|replaceNationalities|clearAll)\s*\(""")
        val writingCallers =
            mapOf(
                "network/lapis/cloud/server/rpc/MemberService.kt" to "peer-guarded RPCs",
                "network/lapis/cloud/server/rpc/RegistrationService.kt" to
                    "registration stores the applicant's own optional input on a brand-new row; rejection erases it",
                "network/lapis/cloud/server/member/CountryLegacyMappingEngine.kt" to "reconciliation",
                "network/lapis/cloud/server/dsgvo/MemberNationalityPersonalData.kt" to "erasure",
                "network/lapis/cloud/server/bootstrap/MemberCsvImport.kt" to "import of NEW members",
                "network/lapis/cloud/server/lupabb/LupaBbApplier.kt" to
                    "V1.9.226 LupaBB sync: new members and record-by-record updates under the member row lock; members with a management role are never changed",
            )

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        test("only the allowlisted files touch member.country_code / member_nationality (exact)") {
            val files =
                SourceScan
                    .mainFiles()
                    .filter { !relative(it).contains("/db/generated/") }
                    .filter { access.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { relative(it) }
                    .toSet()
            files shouldBe allowedAccess.keys
        }

        test("the writing entry points of MemberCountryStore are only called from the reasoned files (exact)") {
            val files =
                SourceScan
                    .mainFiles()
                    .filter { relative(it) != "network/lapis/cloud/server/member/MemberCountryStore.kt" }
                    .filter { writingCall.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { relative(it) }
                    .toSet()
            files shouldBe writingCallers.keys
        }

        test("the scanner sees planted violations (self-test)") {
            access.containsMatchIn("MemberNationalityTable.deleteWhere { }") shouldBe true
            access.containsMatchIn("it[MemberTable.countryCode] = x") shouldBe true
            access.containsMatchIn("it[countryCode] = x") shouldBe true
            access.containsMatchIn("LENGTHS[countryCode]") shouldBe false
            access.containsMatchIn("MemberTable.country") shouldBe false
            writingCall.containsMatchIn("MemberCountryStore.replaceNationalities(id, codes)") shouldBe true
            writingCall.containsMatchIn("MemberCountryStore.loadNationalities(id)") shouldBe false
        }
    })
