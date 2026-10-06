package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.uuid.Uuid

private const val SAFE_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 100"><path d="M60 5 L115 30 L115 70 Q60 110 5 70 L5 30 Z" fill="#1d2968"/></svg>"""
private const val SCRIPT_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>alert(1)</script><rect width="10" height="10"/></svg>"""

private fun pngBytes(): ByteArray {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

private fun jpegBytes(): ByteArray {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
}

/**
 * V1.9.65 -- the ADMIN backup bundle (format 2) now carries the crest, event-cover and article-cover FILES, so a restored instance
 * does not answer 404 for them. A manipulated bundle must not get past the upload hardening: strict entry names (no traversal),
 * only ids a restored row references, size cap, SVG through the crest sanitizer again, raster by signature. A format 1 bundle
 * (no `assets/`) still restores.
 */
class OrganizationBackupAssetsTest :
    FunSpec({
        class Env(
            val sourceDb: Database,
            val targetDb: Database,
            val sourceRoots: BackupAssetRoots,
            val targetRoots: BackupAssetRoots,
            val sourceDocs: File,
            val targetDocs: File,
            val actor: CurrentMember,
            val chapterSvg: Uuid,
            val chapterPng: Uuid,
            val eventCover: Uuid,
            val articleCover: Uuid,
        )

        fun seedAdmin(db: Database): Uuid {
            val adminId = Uuid.random()
            transaction(db) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Assets Admin"
                    it[email] = "assets-admin-$adminId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-assets-test-only"
                    it[role] = AccountRole.ADMIN
                }
            }
            return adminId
        }

        fun newEnv(): Env {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-assets-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-assets-target-${Uuid.random()}")
            val sourceDocs = Files.createTempDirectory("assets-source-docs").toFile()
            val targetDocs = Files.createTempDirectory("assets-target-docs").toFile()
            // Overridden roots OUTSIDE the document storage, like LAPIS_*_STORAGE_ROOT does.
            val sourceRoots =
                BackupAssetRoots(
                    chapterCrests = Files.createTempDirectory("assets-src-crests").toFile(),
                    eventCovers = Files.createTempDirectory("assets-src-events").toFile(),
                    articleCovers = Files.createTempDirectory("assets-src-articles").toFile(),
                )
            val targetRoots =
                BackupAssetRoots(
                    chapterCrests = Files.createTempDirectory("assets-dst-crests").toFile(),
                    eventCovers = Files.createTempDirectory("assets-dst-events").toFile(),
                    articleCovers = Files.createTempDirectory("assets-dst-articles").toFile(),
                )
            val adminId = seedAdmin(sourceDb)
            val chapterSvg = Uuid.random()
            val chapterPng = Uuid.random()
            val eventCover = Uuid.random()
            val articleCover = Uuid.random()
            sourceRoots.chapterCrests.resolve("$chapterSvg.svg").writeText(SAFE_SVG)
            sourceRoots.chapterCrests.resolve("$chapterPng.png").writeBytes(pngBytes())
            sourceRoots.eventCovers.resolve("$eventCover.jpg").writeBytes(jpegBytes())
            sourceRoots.articleCovers.resolve("$articleCover.png").writeBytes(pngBytes())
            transaction(sourceDb) {
                for ((name, crestId, type) in listOf(
                    Triple("Svg-Verband", chapterSvg, "image/svg+xml"),
                    Triple("Png-Verband", chapterPng, "image/png"),
                )) {
                    RegionalChapterTable.insert {
                        it[id] = Uuid.random()
                        it[RegionalChapterTable.name] = name
                        it[nameKey] = name.lowercase()
                        it[createdAt] = LocalDateTime(2027, 1, 1, 8, 0)
                        it[crestImageId] = crestId
                        it[crestPublicToken] = "token-$crestId".take(60)
                        it[crestContentType] = type
                    }
                }
                EventTable.insert {
                    it[id] = Uuid.random()
                    it[slug] = "assets-event-${Uuid.random()}".take(60)
                    it[title] = "Assets-Veranstaltung"
                    it[description] = "test"
                    it[locationText] = "Ort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2027, 5, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2027, 5, 1, 20, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = LocalDateTime(2027, 1, 1, 8, 0)
                    it[createdBy] = adminId
                    it[cancelledAt] = null
                    it[coverImageId] = eventCover
                }
                ArticleTable.insert {
                    it[id] = Uuid.random()
                    it[slug] = "assets-article-${Uuid.random()}".take(60)
                    it[title] = "Assets-Artikel"
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt"
                    it[coverImageId] = articleCover
                    it[authorId] = adminId
                    it[status] = ArticleStatus.DRAFT
                    it[submittedAt] = null
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[rejectionReason] = null
                    it[publishedAt] = null
                    it[createdAt] = LocalDateTime(2027, 1, 1, 8, 0)
                    it[updatedAt] = LocalDateTime(2027, 1, 1, 8, 0)
                }
            }
            return Env(
                sourceDb = sourceDb,
                targetDb = targetDb,
                sourceRoots = sourceRoots,
                targetRoots = targetRoots,
                sourceDocs = sourceDocs,
                targetDocs = targetDocs,
                actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                chapterSvg = chapterSvg,
                chapterPng = chapterPng,
                eventCover = eventCover,
                articleCover = articleCover,
            )
        }

        fun export(env: Env): File {
            val bundle = File.createTempFile("assets-bundle-", ".zip")
            bundle.outputStream().use {
                OrganizationExportService(database = env.sourceDb, documentStorageRoot = env.sourceDocs, assetRoots = env.sourceRoots)
                    .streamExport(actor = env.actor, sink = it)
            }
            return bundle
        }

        fun restore(
            env: Env,
            bundle: File,
        ) = OrganizationRestoreService(database = env.targetDb, documentStorageRoot = env.targetDocs, assetRoots = env.targetRoots)
            .restore(actor = env.actor, bundleFile = bundle)

        /** Rewrites a bundle, passing every entry through [transform] (name, bytes) -> replacement entries; adds [extra] entries. */
        fun rewrite(
            bundle: File,
            extra: List<Pair<String, ByteArray>> = emptyList(),
            transform: (String, ByteArray) -> List<Pair<String, ByteArray>> = { n, b -> listOf(n to b) },
        ): File {
            val out = File.createTempFile("assets-bundle-mod-", ".zip")
            ZipFile(bundle).use { zip ->
                ZipOutputStream(out.outputStream()).use { zos ->
                    zip.entries().asSequence().forEach { e ->
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        transform(e.name, bytes).forEach { (n, b) ->
                            zos.putNextEntry(ZipEntry(n))
                            zos.write(b)
                            zos.closeEntry()
                        }
                    }
                    extra.forEach { (n, b) ->
                        zos.putNextEntry(ZipEntry(n))
                        zos.write(b)
                        zos.closeEntry()
                    }
                }
            }
            return out
        }

        fun entryNames(bundle: File): List<String> =
            ZipFile(bundle).use { z ->
                z
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            }

        test("export carries the crest, event cover and article cover files; restore writes them into the (overridden) roots") {
            val env = newEnv()
            val bundle = export(env)
            val names = entryNames(bundle)
            names shouldContain "assets/chapter-crests/${env.chapterSvg}.svg"
            names shouldContain "assets/chapter-crests/${env.chapterPng}.png"
            names shouldContain "assets/event-covers/${env.eventCover}.jpg"
            names shouldContain "assets/article-covers/${env.articleCover}.png"

            val result = restore(env, bundle)
            result.assetsRestored shouldBe 4
            env.targetRoots.chapterCrests
                .resolve("${env.chapterPng}.png")
                .readBytes()
                .toList() shouldBe
                env.sourceRoots.chapterCrests
                    .resolve("${env.chapterPng}.png")
                    .readBytes()
                    .toList()
            env.targetRoots.eventCovers
                .resolve("${env.eventCover}.jpg")
                .isFile shouldBe true
            env.targetRoots.articleCovers
                .resolve("${env.articleCover}.png")
                .isFile shouldBe true
            env.targetRoots.chapterCrests
                .resolve("${env.chapterSvg}.svg")
                .readText() shouldContain "<svg"
        }

        test("a crest row whose file is missing on the source does not fail the export") {
            val env = newEnv()
            env.sourceRoots.chapterCrests
                .resolve("${env.chapterPng}.png")
                .delete()
            val bundle = export(env)
            entryNames(bundle) shouldNotContain "assets/chapter-crests/${env.chapterPng}.png"
            restore(env, bundle).assetsRestored shouldBe 3
        }

        test("a manipulated SVG with a script element is not restored and cannot slip past the sanitizer") {
            val env = newEnv()
            val bundle = export(env)
            val evil =
                rewrite(bundle) { name, bytes ->
                    if (name ==
                        "assets/chapter-crests/${env.chapterSvg}.svg"
                    ) {
                        listOf(name to SCRIPT_SVG.toByteArray())
                    } else {
                        listOf(name to bytes)
                    }
                }
            val result = restore(env, evil)
            result.assetsRestored shouldBe 3
            env.targetRoots.chapterCrests
                .resolve("${env.chapterSvg}.svg")
                .exists() shouldBe false
            result.warnings.any { it.contains("failed validation") } shouldBe true
        }

        test("a raster entry without the signature of its extension is not restored") {
            val env = newEnv()
            val bundle = export(env)
            val evil =
                rewrite(bundle) { name, bytes ->
                    if (name ==
                        "assets/event-covers/${env.eventCover}.jpg"
                    ) {
                        listOf(name to "<html>not a jpeg</html>".toByteArray())
                    } else {
                        listOf(
                            name to bytes,
                        )
                    }
                }
            restore(env, evil).assetsRestored shouldBe 3
            env.targetRoots.eventCovers
                .resolve("${env.eventCover}.jpg")
                .exists() shouldBe false
        }

        test("path traversal and foreign names are never written anywhere") {
            val env = newEnv()
            val bundle = export(env)
            val outside = Files.createTempDirectory("assets-outside").toFile()
            val traversal = "assets/chapter-crests/../../${outside.name}/${Uuid.random()}.png"
            val evil =
                rewrite(
                    bundle,
                    extra =
                        listOf(
                            traversal to pngBytes(),
                            "assets/chapter-crests/${Uuid.random()}/x.png" to pngBytes(),
                            "assets/chapter-crests/not-a-uuid.png" to pngBytes(),
                            "assets/unknown-folder/${Uuid.random()}.png" to pngBytes(),
                            "assets//etc/passwd" to pngBytes(),
                        ),
                )
            val result = restore(env, evil)
            result.assetsRestored shouldBe 4
            result.warnings.size shouldBe 5
            outside.listFiles().orEmpty().size shouldBe 0
            env.targetRoots.chapterCrests
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted() shouldBe
                listOf("${env.chapterPng}.png", "${env.chapterSvg}.svg").sorted()
        }

        test("an entry for an id that no restored row references is skipped") {
            val env = newEnv()
            val bundle = export(env)
            val orphan = Uuid.random()
            val result = restore(env, rewrite(bundle, extra = listOf("assets/chapter-crests/$orphan.png" to pngBytes())))
            result.assetsRestored shouldBe 4
            env.targetRoots.chapterCrests
                .resolve("$orphan.png")
                .exists() shouldBe false
            result.warnings.any { it.contains("not referenced") } shouldBe true
        }

        test("an oversized entry is skipped") {
            val env = newEnv()
            val bundle = export(env)
            val huge = pngBytes() + ByteArray(MAX_RESTORED_ASSET_BYTES.toInt() + 10)
            val big =
                rewrite(bundle) { name, bytes ->
                    if (name == "assets/article-covers/${env.articleCover}.png") listOf(name to huge) else listOf(name to bytes)
                }
            restore(env, big).assetsRestored shouldBe 3
            env.targetRoots.articleCovers
                .resolve("${env.articleCover}.png")
                .exists() shouldBe false
        }

        test("a format 1 bundle without assets/ and without the asset manifest fields is still restored") {
            val env = newEnv()
            val bundle = export(env)
            val legacy =
                rewrite(bundle) { name, bytes ->
                    when {
                        name.startsWith("assets/") -> emptyList()
                        name == "manifest.json" -> {
                            val json =
                                kotlinx.serialization.json.Json
                                    .parseToJsonElement(bytes.toString(Charsets.UTF_8))
                                    .let { it as kotlinx.serialization.json.JsonObject }
                            val reduced =
                                kotlinx.serialization.json.JsonObject(
                                    json.filterKeys { it != "assetCount" && it != "assetBytesTotal" } +
                                        ("formatVersion" to kotlinx.serialization.json.JsonPrimitive(1)),
                                )
                            listOf(name to reduced.toString().toByteArray())
                        }
                        else -> listOf(name to bytes)
                    }
                }
            val result = restore(env, legacy)
            result.assetsRestored shouldBe 0
            result.tablesRestored.isNotEmpty() shouldBe true
        }

        test("an unsupported format version is still rejected before anything is written") {
            val env = newEnv()
            val bundle = export(env)
            val future =
                rewrite(bundle) { name, bytes ->
                    if (name == "manifest.json") {
                        listOf(name to bytes.toString(Charsets.UTF_8).replace("\"formatVersion\":2", "\"formatVersion\":99").toByteArray())
                    } else {
                        listOf(name to bytes)
                    }
                }
            shouldThrow<IncompatibleBundleException> { restore(env, future) }
            env.targetRoots.chapterCrests
                .listFiles()
                .orEmpty()
                .size shouldBe 0
        }

        test("entry-name grammar: only the three families with their extensions and a canonical lowercase UUID") {
            val id = Uuid.random()
            BackupAssetRules.parse("assets/chapter-crests/$id.svg").shouldNotBeNull()
            BackupAssetRules.parse("assets/event-covers/$id.svg").shouldBeNull()
            BackupAssetRules.parse("assets/article-covers/$id.jpg").shouldNotBeNull()
            BackupAssetRules.parse("assets/chapter-crests/${id.toString().uppercase()}.png").shouldBeNull()
            BackupAssetRules.parse("assets/chapter-crests/$id.png.tmp").shouldBeNull()
            BackupAssetRules.parse("blobs/$id.png").shouldBeNull()
        }
    })
