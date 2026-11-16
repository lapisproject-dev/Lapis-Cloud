package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V154 -- source-scan tripwire: the four `member.founding_member*` columns have ONE writer, `FoundingMemberStore` (plus the DSGVO erasure, which
 * resets them in the same update that clears the personal columns). The founding-member marker is NOT part of the member finder, the REST API
 * v1, the MCP tools or the honors: those sources must not mention it (it is shown only where the member's consent covers it).
 */
class FoundingMemberWriterTripwireTest :
    FunSpec({
        val columnWrite = Regex("""\bit\[\s*(MemberTable\.)?foundingMember(Since|ConsentGrantedAt|ConsentVersion)?\s*]\s*=""")
        val allowedWrites =
            mapOf(
                "network/lapis/cloud/server/member/FoundingMemberStore.kt" to "the one writer",
                "network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt" to "Art. 17 anonymisation: reset in the same update",
            )

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        test("only the allowlisted files assign the founding-member columns (exact)") {
            val found =
                SourceScan
                    .mainFiles()
                    .filter { columnWrite.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { relative(it) }
                    .toSet()
            found shouldBe allowedWrites.keys
        }

        test("no raw SQL on the columns outside the migration and the generated table") {
            SourceScan
                .mainFiles()
                .filter {
                    it.readText().contains(
                        "founding_member",
                    ) &&
                        !relative(it).startsWith("network/lapis/cloud/server/db/generated/")
                }.map { relative(it) }
                .shouldBeEmpty()
        }

        test("finder, REST API v1, MCP and honors carry no founding-member field") {
            val guarded =
                SourceScan.mainFiles().filter { f ->
                    val r = relative(f)
                    r.startsWith("network/lapis/cloud/server/memberfinder/") ||
                        r.startsWith("network/lapis/cloud/server/mcp/") ||
                        r.endsWith("PublicApiDtos.kt") ||
                        r.contains("MemberHonor")
                }
            (guarded.size > 4) shouldBe true
            // (GROUP_FOUNDING is an unrelated finder purpose)
            guarded
                .filter {
                    it
                        .readText()
                        .replace(
                            "GROUP_FOUNDING",
                            "",
                        ).contains("founding", ignoreCase = true)
                }.map { relative(it) }
                .shouldBeEmpty()
        }
    })
