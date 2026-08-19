package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.rpc.SocialVisibility
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.1.3 Soziales Netzwerk "Öffentlicher SEO-Lesepfad" -- data loading and XML rendering for
 * `/sitemap.xml` (and, once the URL count exceeds [MAX_URLS_PER_FILE], the `/sitemap-{n}.xml`
 * shards it indexes). Kept separate from [SocialPublicHtml] (this is XML, not HTML) and from
 * `SocialPublicRoutes.kt` (this file owns the query + XML-shape logic, the route file owns
 * request handling/caching/headers).
 *
 * **Not streamed.** [loadEntriesForShard] runs its query inside the CALLER's own short transaction,
 * returns a plain in-memory list, and the caller closes that transaction BEFORE handing the list to
 * [renderUrlset]/[renderSitemapIndex] -- a streaming writer inside an open transaction would hold a
 * pool connection for as long as a possibly slow client takes to read the response body (same
 * Security-Audit-Fund S-A1 mechanism `SocialReadPipeline`'s own KDoc documents for the RPC read
 * path). At up to [MAX_URLS_PER_FILE] `(Uuid, LocalDateTime)` pairs per shard (a few MB at most),
 * building the whole XML string in memory is cheap.
 *
 * **Review-Runde-1 finding M2 (2026-08-18): per-shard SQL pagination, not a Kotlin `.drop().take()`
 * over an in-memory list.** The previous shape always loaded up to [MAX_TOTAL_URLS] (450 000) root
 * ids EVERY call -- including every single `/sitemap-{n}.xml` shard request, which only ever needed
 * [MAX_URLS_PER_FILE] of them -- and only then sliced the requested shard out of that in-memory
 * list with `.drop(startIndex).take(MAX_URLS_PER_FILE)`. Two problems followed directly from that:
 * (a) `rootId inList publicRootIds` bound every one of those (up to 450 000) ids as a SQL bind
 * parameter -- above PostgreSQL's 65 535-parameter ceiling that throws every time, turning a public,
 * unauthenticated, cacheable route into a permanently-broken one the moment the public-root count
 * crosses that line; (b) `.limit(MAX_TOTAL_URLS)` carried NO `orderBy`, so on overflow the database
 * was free to hand back an ARBITRARY subset of rows on each call -- Kotlin only sorted AFTER that
 * arbitrary subset was already chosen, so the class KDoc's claim of a "deterministic and stable
 * shard assignment" was false the moment more than [MAX_TOTAL_URLS] roots existed.
 *
 * [loadEntriesForShard] fixes both: it takes the shard number and does `orderBy(id).limit(...)
 * .offset(...)` directly in SQL, so a single call NEVER materializes more than [MAX_URLS_PER_FILE]
 * root ids regardless of how many public roots exist overall, and the ordering/selection is fully
 * decided by the database, deterministically, across repeated calls. The subsequent `rootId
 * inList <shard's ids>` query for `lastmod` is chunked at [ROOT_ID_QUERY_CHUNK_SIZE] -- comfortably
 * under the PostgreSQL bind-parameter ceiling even though a single shard (<= [MAX_URLS_PER_FILE] =
 * 45 000) already fits under 65 535 on its own, so a future increase of [MAX_URLS_PER_FILE] cannot
 * silently reintroduce the failure this finding describes.
 */
internal object SocialPublicSitemap {
    /** A single sitemap `<url>` entry -- a public, visible ROOT post's id and its thread's last-modified timestamp. */
    data class SitemapEntry(
        val id: Uuid,
        val lastmod: LocalDateTime,
    )

    /** Sitemap protocol ceiling most implementations self-impose (50 000 nominal limit) -- kept comfortably under it. */
    const val MAX_URLS_PER_FILE: Int = 45_000

    /** Hard shard ceiling -- see [loadEntriesForShard] KDoc "Overflow" for what happens beyond this. */
    const val MAX_SHARDS: Int = 10

    /** [MAX_URLS_PER_FILE] × [MAX_SHARDS] -- the absolute ceiling this sitemap can ever expose. */
    const val MAX_TOTAL_URLS: Int = MAX_URLS_PER_FILE * MAX_SHARDS

    /**
     * Batch size for `rootId inList <ids>` queries -- comfortably under PostgreSQL's 65 535
     * bound-parameter limit. See the class KDoc "Review-Runde-1 finding M2" for why this is applied
     * even though a single shard's id list already fits under that limit on its own.
     */
    private const val ROOT_ID_QUERY_CHUNK_SIZE = 10_000

    /**
     * Total number of public, visible ROOT posts -- a single `SELECT COUNT(*)`, never loads a row of
     * actual data. Used by the caller ONLY to decide `<urlset>` vs. `<sitemapindex>` and the shard
     * count for the index -- never to decide how much to load for any one shard, see
     * [loadEntriesForShard].
     */
    fun countPublicRoots(): Long =
        SocialPostTable
            .selectAll()
            .where { SocialVisibility.publicReadableCondition() and SocialPostTable.parentId.isNull() }
            .count()

    /**
     * Loads the `(id, lastmod)` pairs for ONE 1-based [shard] -- `orderBy(id).limit
     * (MAX_URLS_PER_FILE).offset((shard - 1) * MAX_URLS_PER_FILE)` entirely in SQL, so this function
     * NEVER materializes more than [MAX_URLS_PER_FILE] root ids, however many public roots exist in
     * total. Returns an empty list once [shard] is past the actual data (the caller turns that into
     * a 404, see `SocialPublicRoutes.kt`).
     *
     * Every public, visible ROOT post is ausschließlich
     * [network.lapis.cloud.shared.domain.SocialPostVisibility.PUBLIC] +
     * [network.lapis.cloud.shared.domain.SocialPostState.VISIBLE] (never a comment id -- comments
     * have no URL of their own, see `SocialPublicRoutes.kt` § routing). **Kein Ranking-Horizont** --
     * an old post falls out of the timeline but stays a valid, indexable URL.
     *
     * `lastmod` = `max(published_at, coalesce(state_changed_at, published_at))` over the ENTIRE
     * thread (every row sharing that `root_id`, any visibility/state) -- a new (or newly
     * hidden/removed) comment changes what a crawler would see if it re-fetched the page, so it
     * must bump `lastmod` even though the comment itself has no URL. Boosts deliberately do NOT
     * feed into `lastmod` -- a boost only changes a weight number, not the indexed CONTENT, so
     * treating every boost as a content change would be pure crawl-budget noise.
     *
     * **Overflow**: [shard] beyond [MAX_SHARDS] is the caller's responsibility to reject (404)
     * before calling this function -- see [countPublicRoots] and `SocialPublicRoutes.kt`'s truncation
     * warning, logged loudly (never silently) once the total exceeds [MAX_TOTAL_URLS].
     */
    fun loadEntriesForShard(shard: Int): List<SitemapEntry> {
        require(shard in 1..MAX_SHARDS) { "shard must be in 1..$MAX_SHARDS, was $shard" }
        val offset = (shard - 1).toLong() * MAX_URLS_PER_FILE
        val rootIds =
            SocialPostTable
                .select(SocialPostTable.id)
                .where { SocialVisibility.publicReadableCondition() and SocialPostTable.parentId.isNull() }
                .orderBy(SocialPostTable.id)
                .limit(MAX_URLS_PER_FILE)
                .offset(offset)
                .map { it[SocialPostTable.id] }
        if (rootIds.isEmpty()) return emptyList()

        val maxPublishedAt = SocialPostTable.publishedAt.max()
        val maxStateChangedAt = SocialPostTable.stateChangedAt.max()
        // Welle V1.1.5 (Plan § 5.4): ein Tombstoning (`executeContentErasure`/
        // `SocialNetworkPersonalData.erase`) aendert den Seiteninhalt eines Threads, OHNE
        // `state_changed_at` zu bewegen (die rechtliche Entfernung ist orthogonal dazu, siehe
        // `SocialPostDto.contentErasedAt` KDoc) -- ohne dieses dritte `max()` signalisiert ein
        // Tombstone dem Crawler KEINE Aenderung, und die alte, inhaltstragende Version bleibt bis
        // zum naechsten spontanen Re-Crawl im Index.
        val maxContentErasedAt = SocialPostTable.contentErasedAt.max()
        val lastmodByRootId = mutableMapOf<Uuid, LocalDateTime?>()
        rootIds.chunked(ROOT_ID_QUERY_CHUNK_SIZE).forEach { chunk ->
            SocialPostTable
                .select(SocialPostTable.rootId, maxPublishedAt, maxStateChangedAt, maxContentErasedAt)
                .where { SocialPostTable.rootId inList chunk }
                .groupBy(SocialPostTable.rootId)
                .forEach { row ->
                    val candidates = listOfNotNull(row[maxPublishedAt], row[maxStateChangedAt], row[maxContentErasedAt])
                    lastmodByRootId[row[SocialPostTable.rootId]] = candidates.maxOrNull()
                }
        }
        return rootIds.mapNotNull { id -> lastmodByRootId[id]?.let { SitemapEntry(id = id, lastmod = it) } }
    }

    /** A plain `<urlset>` for [entries] -- used directly under `/sitemap.xml` when [entries] fits in one file, or for one shard. */
    fun renderUrlset(
        entries: List<SitemapEntry>,
        baseUrl: String,
    ): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
            entries.forEach { entry ->
                append("  <url>\n")
                append("    <loc>").append(xmlEscape("$baseUrl/s/${entry.id}")).append("</loc>\n")
                append("    <lastmod>").append(xmlEscape(entry.lastmod.toW3cDatetime())).append("</lastmod>\n")
                append("  </url>\n")
            }
            append("</urlset>\n")
        }

    /** A sitemap INDEX pointing at `/sitemap-1.xml` .. `/sitemap-{shardCount}.xml` -- used under `/sitemap.xml` once [MAX_URLS_PER_FILE] is exceeded. */
    fun renderSitemapIndex(
        baseUrl: String,
        shardCount: Int,
    ): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
            for (shard in 1..shardCount) {
                append("  <sitemap>\n")
                append("    <loc>").append(xmlEscape("$baseUrl/sitemap-$shard.xml")).append("</loc>\n")
                append("  </sitemap>\n")
            }
            append("</sitemapindex>\n")
        }
}

/**
 * Minimal XML text/attribute escape -- `<loc>` only ever contains [network.lapis.cloud.server
 * .federation.FederationConfig.publicBaseUrl] plus a [kotlin.uuid.Uuid], `<lastmod>` only a W3C
 * Datetime (see [toW3cDatetime]), so none of the five characters below can realistically occur
 * today. Applied anyway (rather than trusted as "obviously safe") so a later field addition to this
 * sitemap can never reintroduce an XML-injection gap by omission.
 */
internal fun xmlEscape(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

/**
 * G1-Fix (Review-Runde 1): a full W3C Datetime (`2026-08-18T14:32:10.123456Z`), not merely a date
 * (`2026-08-18`). The date-only form (still valid per the sitemap protocol) silently collapsed every
 * `lastmod` change within the same calendar day into an unchanged value -- two comments added to the
 * same root five minutes apart on the same day were indistinguishable to a crawler deciding whether
 * to re-fetch. [network.lapis.cloud.server.db.DbClock] captures with microsecond precision (see its
 * own KDoc), so this loses no information the database doesn't already have.
 *
 * Converted via [TimeZone.currentSystemDefault] -- the SAME zone [DbClock.nowLocalDateTime]'s
 * default parameter uses to CAPTURE `published_at`/`state_changed_at` in the first place (see
 * `SocialNetworkService`'s write paths), so this is the round-trip inverse of how the value was
 * produced, not an independent assumption about server timezone. [kotlinx.datetime.Instant.toString]
 * always renders in UTC with a trailing `Z`, which is both valid W3C Datetime and independent of
 * which zone this reader/writer pair currently resolves to.
 */
internal fun LocalDateTime.toW3cDatetime(): String = toInstant(TimeZone.currentSystemDefault()).toString()
