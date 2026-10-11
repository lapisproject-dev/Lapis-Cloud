package network.lapis.cloud.server.articles

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.relaxSessionTimeouts
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.CoverImageLimits
import network.lapis.cloud.server.events.CoverProcessingResult
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleImportImageRowDto
import network.lapis.cloud.shared.domain.ArticleImportImageStatus
import network.lapis.cloud.shared.domain.ArticleImportImagesPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportImagesResultDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.106 -- matching and processing of the images of an article import ZIP. The ZIP itself is validated by [ArticleImportZipReader]
 * (in memory, never the file system); this object maps `slug.jpg|jpeg|png` onto articles and runs every accepted image through the SAME cover
 * pipeline as a manual upload (`EventCoverImageProcessor`: header-dimension and bomb guards, EXIF orientation, fresh metadata-free re-encode),
 * with the limits of an IMPORTED article.
 *
 * **Preview** writes nothing: it does the full, honest processing (so "OK (1200 x 800)" is what would really be stored) and reports one row per
 * requested slug. **Commit** only ever touches articles of ONE import run ([ArticleTable.importBatchId], `imported = TRUE`), never overwrites an
 * existing cover, and writes exactly ONE audit entry.
 *
 * **Order of side effects** (same rule as the cover routes): files are written BEFORE the transaction, the database changes happen in it, and
 * the decision which files to keep or delete is taken AFTER it from its result -- never inside the block, because Exposed may run the block again.
 * An exception in the transaction deletes every file written by this call.
 *
 * Nothing here logs a file name, a title or a slug (only counts).
 */
internal object ArticleImportImages {
    const val MAX_SLUGS = 100
    private const val SNIFF_BYTES = 8
    private const val MAX_UNMATCHED_LISTED = 100
    private const val STATEMENT_TIMEOUT_MS = 30_000L
    private const val IDLE_TX_TIMEOUT_MS = 30_000L
    private const val LOCK_TIMEOUT_MS = 5_000L

    private sealed interface Processed {
        class Ok(
            val bytes: ByteArray,
            val format: CoverImageFormat,
            val width: Int,
            val height: Int,
        ) : Processed

        class TooSmall(
            val width: Int,
            val height: Int,
        ) : Processed

        data object Invalid : Processed
    }

    private suspend fun processImage(
        bytes: ByteArray,
        limits: CoverImageLimits,
    ): Processed {
        val format = EventCoverImageProcessor.sniff(bytes.copyOfRange(0, minOf(SNIFF_BYTES, bytes.size))) ?: return Processed.Invalid
        return when (val result = EventCoverImageProcessor.process(bytes = bytes, format = format, limits = limits)) {
            is CoverProcessingResult.Ok ->
                Processed.Ok(bytes = result.bytes, format = result.format, width = result.width, height = result.height)
            is CoverProcessingResult.DimensionsTooSmall -> Processed.TooSmall(width = result.width, height = result.height)
            CoverProcessingResult.DimensionsTooLarge, CoverProcessingResult.Undecodable -> Processed.Invalid
        }
    }

    private fun readZip(zip: ByteArray): ZipReadResult.Ok =
        when (val read = ArticleImportZipReader.read(zip)) {
            is ZipReadResult.Ok -> read
            is ZipReadResult.Rejected -> throw ZipRejectedException(read.reason)
        }

    /** [slugs]: the slugs of the import preview rows (CREATE and SKIP). Validated against the import slug pattern; at most [MAX_SLUGS]. */
    suspend fun preview(
        zip: ByteArray,
        slugs: List<String>,
    ): ArticleImportImagesPreviewDto {
        val requested = slugs.distinct()
        if (requested.size > MAX_SLUGS) throw BadRequestException("Höchstens $MAX_SLUGS Artikel je Bild-Paket.")
        if (requested.any { it.length > ArticleImportPolicy.SLUG_MAX_LENGTH || !ArticleImportPolicy.SLUG_PATTERN.matches(it) }) {
            throw BadRequestException("Ungültige Artikelkennung.")
        }
        val read = readZip(zip)
        val imageBySlug = read.images.associateBy { it.slug }
        val existing = transaction { ArticleStore.existingSlugs(requested) }

        // Only articles that do not exist yet are created by the import, and only imported articles get these limits -- structural, never a
        // client parameter. (A slug that already exists is not touched at all, see IGNORED_EXISTING_ARTICLE.)
        val limits = CoverImageLimits.IMPORTED_ARTICLE_COVER
        val rows = mutableListOf<ArticleImportImageRowDto>()
        for (slug in requested) {
            val hasFile = slug in imageBySlug || slug in read.duplicateSlugs
            if (slug in existing) {
                if (hasFile) rows += ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.IGNORED_EXISTING_ARTICLE)
                continue
            }
            rows +=
                when {
                    slug in read.duplicateSlugs -> ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.DUPLICATE)
                    slug !in imageBySlug -> ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.MISSING)
                    else ->
                        when (val processed = processImage(bytes = imageBySlug.getValue(slug).bytes, limits = limits)) {
                            is Processed.Ok ->
                                ArticleImportImageRowDto(
                                    slug = slug,
                                    status = ArticleImportImageStatus.OK,
                                    width = processed.width,
                                    height = processed.height,
                                )
                            is Processed.TooSmall ->
                                ArticleImportImageRowDto(
                                    slug = slug,
                                    status = ArticleImportImageStatus.TOO_SMALL,
                                    width = processed.width,
                                    height = processed.height,
                                )
                            Processed.Invalid -> ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.INVALID_FORMAT)
                        }
                }
        }
        val requestedSet = requested.toSet()
        val unmatched =
            read.images.filter { it.slug !in requestedSet }.map { it.fileName } + read.duplicateSlugs.filter { it !in requestedSet }
        return ArticleImportImagesPreviewDto(
            rows = rows,
            unmatchedFiles = unmatched.take(MAX_UNMATCHED_LISTED),
            unmatchedCount = unmatched.size,
            ignoredSystemFiles = read.ignoredSystemFiles,
        )
    }

    suspend fun commit(
        zip: ByteArray,
        batchId: Uuid,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        storage: EventCoverStorage,
        now: LocalDateTime,
    ): ArticleImportImagesResultDto {
        val read = readZip(zip)
        val imageBySlug = read.images.associateBy { it.slug }
        // An unknown or foreign batch id gives an empty list: applied = 0, no error, no audit entry.
        val targets = transaction { ArticleStore.importBatchTargets(batchId) }

        val rows = mutableListOf<ArticleImportImageRowDto>()
        val written = mutableListOf<Pair<ArticleStore.ImportBatchTarget, Uuid>>() // target -> new cover id
        val dimensions = mutableMapOf<Uuid, Pair<Int, Int>>()
        val writtenIds = mutableListOf<Uuid>()
        try {
            for (target in targets) {
                val slug = target.slug
                val hasFile = slug in imageBySlug || slug in read.duplicateSlugs
                if (target.hasCover) {
                    if (hasFile) rows += ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.IGNORED_EXISTING_ARTICLE)
                    continue
                }
                if (slug in read.duplicateSlugs) {
                    rows += ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.DUPLICATE)
                    continue
                }
                val image = imageBySlug[slug]
                if (image == null) {
                    rows += ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.MISSING)
                    continue
                }
                // The limits come from the article's own DB row (here: the batch target read from the table), never from the request.
                when (val processed = processImage(bytes = image.bytes, limits = limitsForTarget(target))) {
                    is Processed.Ok -> {
                        val newId = Uuid.random()
                        storage.write(id = newId, format = processed.format, bytes = processed.bytes)
                        writtenIds += newId
                        written += target to newId
                        dimensions[newId] = processed.width to processed.height
                    }
                    is Processed.TooSmall ->
                        rows +=
                            ArticleImportImageRowDto(
                                slug = slug,
                                status = ArticleImportImageStatus.TOO_SMALL,
                                width = processed.width,
                                height = processed.height,
                            )
                    Processed.Invalid -> rows += ArticleImportImageRowDto(slug = slug, status = ArticleImportImageStatus.INVALID_FORMAT)
                }
            }

            val rejectedBefore =
                rows.count {
                    it.status == ArticleImportImageStatus.TOO_SMALL ||
                        it.status == ArticleImportImageStatus.INVALID_FORMAT
                }
            val outcome =
                transaction {
                    relaxSessionTimeouts(
                        statementTimeoutMs = STATEMENT_TIMEOUT_MS,
                        idleInTransactionTimeoutMs = IDLE_TX_TIMEOUT_MS,
                        lockTimeoutMs = LOCK_TIMEOUT_MS,
                    )
                    val applied = mutableListOf<Pair<ArticleStore.ImportBatchTarget, Uuid>>()
                    val skippedNow = mutableListOf<Pair<ArticleStore.ImportBatchTarget, Uuid>>()
                    // ids ascending: the same lock order as the bulk approval, so the two can never deadlock each other
                    for ((target, newId) in written.sortedBy { it.first.id }) {
                        val locked = ArticleStore.lockForUpdate(target.id)
                        val stillOurs =
                            locked != null &&
                                locked[ArticleTable.imported] &&
                                locked[ArticleTable.importBatchId] == batchId &&
                                locked[ArticleTable.coverImageId] == null
                        if (stillOurs) {
                            ArticleStore.setCoverImageId(id = target.id, coverImageId = newId)
                            applied += target to newId
                        } else {
                            skippedNow += target to newId
                        }
                    }
                    // Audit LAST (chain-state row lock until commit). ONE entry for the whole run, no file names.
                    if (applied.isNotEmpty()) {
                        AuditLogRecorder.record(
                            actorMemberId = actorMemberId,
                            actorRole = actorRole,
                            entityType = AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED,
                            entityId = batchId,
                            action = AuditAction.UPDATE,
                            after =
                                buildJsonObject {
                                    put("batchId", batchId.toString())
                                    put("appliedCount", applied.size)
                                    put("rejectedCount", rejectedBefore)
                                    put("appliedArticleIds", JsonArray(applied.map { JsonPrimitive(it.first.id.toString()) }))
                                }.toString(),
                            occurredAt = now,
                        )
                    }
                    CommitOutcome(applied = applied.toList(), skipped = skippedNow.toList())
                }
            // AFTER the transaction, decided from its result: files of rows that were taken over meanwhile are removed.
            for ((_, newId) in outcome.skipped) storage.delete(newId)
            for ((target, newId) in outcome.applied) {
                val (w, h) = dimensions.getValue(newId)
                rows += ArticleImportImageRowDto(slug = target.slug, status = ArticleImportImageStatus.OK, width = w, height = h)
            }
            for ((target, _) in outcome.skipped) {
                rows +=
                    ArticleImportImageRowDto(slug = target.slug, status = ArticleImportImageStatus.IGNORED_EXISTING_ARTICLE)
            }
            logger.info {
                "Article import images committed: applied=${outcome.applied.size} skipped=${outcome.skipped.size} rows=${rows.size}"
            }
            val rejected =
                rows.count {
                    it.status == ArticleImportImageStatus.TOO_SMALL ||
                        it.status == ArticleImportImageStatus.INVALID_FORMAT
                }
            return ArticleImportImagesResultDto(applied = outcome.applied.size, rejected = rejected, rows = rows.sortedBy { it.slug })
        } catch (e: Exception) {
            // Anything failed: no file written by THIS call may stay behind.
            writtenIds.forEach { runCatching { storage.delete(it) } }
            throw e
        }
    }

    private fun limitsForTarget(target: ArticleStore.ImportBatchTarget): CoverImageLimits =
        ArticleCoverPolicy.limitsForArticle(target.imported)

    private class CommitOutcome(
        val applied: List<Pair<ArticleStore.ImportBatchTarget, Uuid>>,
        val skipped: List<Pair<ArticleStore.ImportBatchTarget, Uuid>>,
    )
}
