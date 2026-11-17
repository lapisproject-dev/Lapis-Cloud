package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.307 -- source-scan tripwires of the external member id.
 *  1. The two `member.external_member_id*` columns have ONE writer, `ExternalMemberIdStore` (plus the DSGVO erasure, which resets them in the same
 *     update that clears the personal columns).
 *  2. Ballot anonymity: nothing that builds, stores or exports a ballot, a vote, an election, a consensus rating or a participation list may read the
 *     id. The voting policy only adds a WHERE predicate; the id is never projected there.
 *  3. The id is NOT part of the finder, the REST API v1, MCP, the member card, the public profiles or the embed feed.
 */
class ExternalMemberIdWriterTripwireTest :
    FunSpec({
        val columnWrite = Regex("""\bit\[\s*(MemberTable\.)?externalMemberId(Source)?\s*]\s*=""")
        val allowedWrites =
            mapOf(
                "network/lapis/cloud/server/member/ExternalMemberIdStore.kt" to "the one writer",
                "network/lapis/cloud/server/dsgvo/FoundationPersonalData.kt" to "Art. 17 anonymisation: reset in the same update",
            )

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        fun code(file: File): String = SourceScan.blank(file.readText())

        test("only the allowlisted files assign the external-id columns (exact)") {
            val found =
                SourceScan
                    .mainFiles()
                    .filter { columnWrite.containsMatchIn(code(it)) }
                    .map { relative(it) }
                    .toSet()
            found shouldBe allowedWrites.keys
        }

        test("no raw SQL on the columns outside the migration and the generated table") {
            SourceScan
                .mainFiles()
                .filter {
                    code(it).contains("external_member_id") &&
                        !relative(it).startsWith("network/lapis/cloud/server/db/generated/")
                }.map { relative(it) }
                .shouldBeEmpty()
        }

        test("ballot anonymity: the voting and ballot code never reads the id") {
            val guarded =
                SourceScan.mainFiles().filter { f ->
                    val r = relative(f)
                    val name = f.name
                    r.startsWith("network/lapis/cloud/server/governance/") ||
                        (
                            r.startsWith("network/lapis/cloud/server/rpc/") &&
                                listOf("Election", "Vote", "SystemicConsensus", "Ballot", "Resolution", "GovernanceReads", "Tender").any {
                                    name.startsWith(it)
                                }
                        ) ||
                        r.startsWith("network/lapis/cloud/server/pdf/") &&
                        (name.contains("Ballot") || name.contains("Election") || name.contains("Protocol"))
                }
            (guarded.size > 15) shouldBe true
            guarded.filter { code(it).contains("externalMemberId") }.map { relative(it) }.shouldBeEmpty()
        }

        test("the shared DTOs of votes, elections, consensus, resolutions and meetings carry no external id field") {
            val shared =
                File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain").let {
                    if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain")
                }
            val guarded =
                shared
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            listOf("Vote", "Election", "SystemicConsensus", "Resolution", "Governance", "Meeting", "Ballot").any { p ->
                                it.name.startsWith(p)
                            }
                    }.toList()
            (guarded.size > 3) shouldBe true
            guarded.filter { SourceScan.blank(it.readText()).contains("externalMemberId") }.map { it.name }.shouldBeEmpty()
        }

        test("finder, REST API v1, MCP, member card, public profiles and embed feed carry no external id") {
            val guarded =
                SourceScan.mainFiles().filter { f ->
                    val r = relative(f)
                    r.startsWith("network/lapis/cloud/server/memberfinder/") ||
                        r.startsWith("network/lapis/cloud/server/mcp/") ||
                        r.endsWith("PublicApiDtos.kt") ||
                        r.contains("MemberCard") ||
                        r.contains("PublicProfiles") ||
                        r.contains("EmbedProfilesFeed")
                }
            (guarded.size > 6) shouldBe true
            guarded.filter { code(it).contains("externalMemberId") }.map { relative(it) }.shouldBeEmpty()
        }
    })
