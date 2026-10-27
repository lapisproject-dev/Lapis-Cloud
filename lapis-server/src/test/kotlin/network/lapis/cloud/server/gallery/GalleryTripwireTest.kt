package network.lapis.cloud.server.gallery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.domain.SocialPostDto
import java.io.File

/**
 * V1.9.222 -- structural guards of the gallery, in the house style of `TenderAccessTripwireTest`: they read the production source and
 * fail
 * the
 * moment a rule that no behavioural test can see is broken.
 */
class GalleryTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun usersOf(token: String): Set<String> = files.filter { SourceScan.blank(it.readText()).contains(token) }.map { it.name }.toSet()

        test("AttachedImageTable is touched only by the store, the DSGVO contributor (coveredTables) and its own generated file") {
            usersOf("AttachedImageTable") shouldBe setOf("AttachedImageStore.kt", "AttachedImagePersonalData.kt", "AttachedImageTable.kt")
        }

        test("the contributor does not query the table itself: it goes through the store") {
            val code = SourceScan.blank(files.single { it.name == "AttachedImagePersonalData.kt" }.readText())
            listOf(".selectAll(", ".deleteWhere", ".insert", ".update(").forEach { (code.contains("AttachedImageTable$it")) shouldBe false }
        }

        test("every code path that deletes an auction or a crowdfunding project also calls the gallery's orphan hook in the same file") {
            val deleters =
                listOf("AuctionTable", "CrowdfundingProjectTable")
                    .flatMap { table ->
                        files.filter { f ->
                            Regex("""$table\.(deleteWhere|deleteAll|delete)\b""").containsMatchIn(SourceScan.blank(f.readText()))
                        }
                    }.filterNot { it.name == "AttachedImageStore.kt" }
            // today the set is EMPTY (no path deletes an auction or a project); it must stay empty or call the hook
            deleters.filterNot { SourceScan.blank(it.readText()).contains("deleteAllForOwnerInTx") }.map { it.name }.shouldBeEmpty()
        }

        test("the gallery writes no caption, file name, storage key or path into a log line") {
            val sources =
                files.filter {
                    it.parentFile.name == "gallery" ||
                        it.name == "AttachedImageRoutes.kt" ||
                        it.name == "AttachedImagePersonalData.kt"
                }
            (sources.size >= 8) shouldBe true
            val offenders =
                sources.flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> line.contains("logger.") }
                        .filter { (_, line) ->
                            listOf("caption", "altText", "storageKey", "path", "File", "name", "bytes.").any {
                                line.contains(it) &&
                                    !line.contains("bytes=")
                            }
                        }.map { (index, _) -> "${file.name}:${index + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test(
            "the gallery RPC service is the fourth to last registerService of Application.kt (the dashboard, the party report and the LupaBB sync follow it)",
        ) {
            val code = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/Application.kt").readText())
            val registrations =
                Regex("""registerService\(""")
                    .findAll(code)
                    .map { it.range.first }
                    .toList()
            registrations[registrations.size - 4] shouldBe code.indexOf("registerService(IAttachedImageService::class)")
            registrations[registrations.size - 3] shouldBe code.indexOf("registerService(IDashboardService::class)")
            registrations[registrations.size - 2] shouldBe code.indexOf("registerService(IPartyReportService::class)")
            registrations.last() shouldBe code.indexOf("registerService(ILupaBbSyncService::class)")
        }

        // ── V1.9.223: pictures in social posts ──────────────────────────────────────────────
        test("every file that writes the post-erasure tombstone also calls the orphan hook, except the member-level DSGVO contributor") {
            val tombstoneWriters =
                files.filter { SourceScan.blank(it.readText()).contains("SocialContentTombstone.ON_POST_REQUEST") }
            tombstoneWriters.map { it.name } shouldBe listOf("SocialNetworkService.kt")
            // SocialNetworkPersonalData.kt tombstones with ON_MEMBER_ERASURE only; the pictures of an erased member go through
            // AttachedImagePersonalData (they belong to their uploader, and only the author can upload to a post)
            tombstoneWriters.filterNot { SourceScan.blank(it.readText()).contains("deleteAllForOwnerInTx") }.map { it.name }.shouldBeEmpty()
        }

        test("the public pages, mails, MCP and the transparency routes never touch gallery pictures") {
            val sensitive =
                files.filter {
                    it.name.startsWith("SocialPublic") ||
                        it.name == "SocialReadPipeline.kt" ||
                        it.name == "PublicTransparencyRoutes.kt" ||
                        it.parentFile.name in setOf("mail", "mcp", "tools")
                }
            (sensitive.size >= 8) shouldBe true
            val offenders =
                sensitive.filter { file ->
                    val text = file.readText()
                    listOf("AttachedImage", "GalleryUrls", "/api/images").any { text.contains(it) }
                }
            offenders.map { it.name }.shouldBeEmpty()
        }

        test("SocialPostDto has no image field: the public path renders the same DTO and must never carry pictures") {
            val descriptor = SocialPostDto.serializer().descriptor
            (0 until descriptor.elementsCount)
                .map { descriptor.getElementName(it) }
                .filter { it.contains("image", ignoreCase = true) || it.contains("picture", ignoreCase = true) }
                .shouldBeEmpty()
        }

        test("a social-level block or mute extends GalleryOwnerPolicy in the same change") {
            val token = Regex("Social(Block|Mute)|social_(block|mute)")
            val mentioning = files.filter { token.containsMatchIn(it.readText()) }.map { it.name }
            val policy = files.single { it.name == "GalleryOwnerPolicy.kt" }.readText()
            // empty today (only MCP and member-finder blocks exist); once a block exists the policy must name it
            if (mentioning.any { it != "GalleryOwnerPolicy.kt" }) token.containsMatchIn(policy) shouldBe true
        }
    })
