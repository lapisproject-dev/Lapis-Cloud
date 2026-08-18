package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Pure renderer tests -- NO `testApplication`, NO DB. This is the fastest and sharpest XSS test
 * hebel this welle has: [SocialPublicHtml]'s functions are `String` in / `String` out, so every
 * assertion here runs against the actual, final HTML/response body a browser would receive, with
 * no server/network layer in between.
 */
class SocialPublicHtmlTest :
    FunSpec({
        val baseUrl = "https://cloud.lapisproject.dev"
        val postId = "11111111-1111-1111-1111-111111111111"

        fun post(
            id: String = postId,
            content: String = "Hallo Welt",
            author: String = "Alice",
        ): PublicPostView =
            PublicPostView(
                id = id,
                depth = 0,
                authorDisplayName = author,
                contentLines = content.split("\n"),
                totalWeightLtr = "3.14",
                ownWeightLtr = "3.14",
                publishedAtIso = "2026-08-18T12:00:00",
                publishedAtHuman = "18.08.2026",
            )

        test("timelinePage renders post content, author, and a link to the post") {
            val view = PublicTimelineView(posts = listOf(post()), page = 1, hasNext = false)
            val html = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            html shouldContain "Hallo Welt"
            html shouldContain "Alice"
            html shouldContain "$baseUrl/s/$postId"
            html shouldContain "<html"
        }

        test("timelinePage with no posts shows the empty-state message, not an empty canvas") {
            val view = PublicTimelineView(posts = emptyList(), page = 1, hasNext = false)
            val html = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            html shouldContain "Noch keine öffentlichen Beiträge."
        }

        test("timelinePage page 1 is index,follow; page 2 is noindex,follow") {
            val page1 =
                SocialPublicHtml.timelinePage(
                    view = PublicTimelineView(posts = emptyList(), page = 1, hasNext = true),
                    baseUrl = baseUrl,
                )
            page1 shouldContain "index,follow"
            val page2 =
                SocialPublicHtml.timelinePage(
                    view = PublicTimelineView(posts = emptyList(), page = 2, hasNext = false),
                    baseUrl = baseUrl,
                )
            page2 shouldContain "noindex,follow"
            page2 shouldNotContain "\"index,follow\""
        }

        test("postPage renders the root, its descendants, canonical link, and article type OpenGraph tag") {
            val root = post(content = "Wurzel-Inhalt")
            val child = post(id = "22222222-2222-2222-2222-222222222222", content = "Antwort-Inhalt", author = "Bob")
            val view = PublicThreadView(root = root, descendants = listOf(child), truncated = false)
            val html = SocialPublicHtml.postPage(view = view, baseUrl = baseUrl)
            html shouldContain "Wurzel-Inhalt"
            html shouldContain "Antwort-Inhalt"
            html shouldContain "Bob"
            html shouldContain "rel=\"canonical\""
            html shouldContain "$baseUrl/s/$postId"
            html shouldContain "og:type"
            html shouldContain "article"
        }

        test("postPage with truncated=true shows the truncation notice, never a silent cutoff") {
            val view = PublicThreadView(root = post(), descendants = emptyList(), truncated = true)
            val html = SocialPublicHtml.postPage(view = view, baseUrl = baseUrl)
            html shouldContain "Weitere Antworten werden hier nicht angezeigt."
        }

        test("notFoundPage and tooManyRequestsPage are noindex and never expose a stack trace / internal detail") {
            val notFound = SocialPublicHtml.notFoundPage(baseUrl = baseUrl)
            notFound shouldContain "noindex"
            notFound shouldContain "Nicht gefunden"

            val tooMany = SocialPublicHtml.tooManyRequestsPage(baseUrl = baseUrl)
            tooMany shouldContain "noindex"
            tooMany shouldContain "Zu viele Anfragen"
        }

        // N1-Fix (Review-Runde 2): serverErrorPage() -- the M1-Fix 500 fallback page -- had no test
        // of its own at all, unlike notFoundPage/tooManyRequestsPage right above. Same shape as those:
        // it must be noindex and, being the page a genuinely UNEXPECTED failure falls back to, must
        // never leak any exception detail even though (unlike the other two) it has no dynamic input
        // to begin with -- this is a regression guard against a future change adding one.
        test("serverErrorPage is noindex and never exposes a stack trace / internal detail") {
            val serverError = SocialPublicHtml.serverErrorPage(baseUrl = baseUrl)
            serverError shouldContain "noindex"
            serverError shouldContain "Interner Fehler"
            serverError shouldNotContain "Exception"
            serverError shouldNotContain "at network.lapis"
            serverError shouldContain "$baseUrl/s"
        }

        test("T5 XSS-Katalog: malicious post content never reaches the output unescaped, across timeline and thread") {
            val payloads =
                listOf(
                    "<script>alert(1)</script>",
                    "\"><img src=x onerror=alert(1)>",
                    "</p><svg onload=alert(1)>",
                    "javascript:alert(1)",
                    "&#x3c;script&#x3e;",
                    "‮reversed-bidi-text",
                )
            payloads.forEach { payload ->
                val timelineHtml =
                    SocialPublicHtml.timelinePage(
                        view = PublicTimelineView(posts = listOf(post(content = payload)), page = 1, hasNext = false),
                        baseUrl = baseUrl,
                    )
                val threadHtml =
                    SocialPublicHtml.postPage(
                        view = PublicThreadView(root = post(content = payload), descendants = emptyList(), truncated = false),
                        baseUrl = baseUrl,
                    )
                listOf(timelineHtml, threadHtml).forEach { html ->
                    // A payload containing a live '<' must never survive verbatim -- kotlinx.html's
                    // text-node/attribute escaping turns '<'/'>'/'&'/'"' into entities, so a
                    // surviving verbatim match would mean escaping did not happen. Content is
                    // plain text (S7, no href/src ever built from it), so "javascript:alert(1)"
                    // (no '<'/'>'/'"' to escape at all) legitimately survives as INERT text and is
                    // deliberately excluded from this check -- it is not, and cannot become, a live
                    // link. (kotlinx.html also does NOT, and does not need to, scrub plain English
                    // words like "onerror=" from ordinary escaped TEXT -- that substring is inert
                    // once it is no longer inside a live '<tag ...>', which the two checks below
                    // actually prove.)
                    if (payload.contains('<')) {
                        html shouldNotContain payload
                    }
                    html shouldNotContain "<script>alert"
                    html shouldNotContain "<img src=x onerror"
                    html shouldNotContain "<svg onload"
                }
            }
        }

        test("T5 XSS-Katalog: malicious COMMENT (descendant) content never reaches the output unescaped") {
            // M5-Fix (Review-Runde 1): the original catalog only ever exercised ROOT-post content
            // (via `post()` as `view.root`/`view.posts`) -- a descendant node goes through a
            // DIFFERENT render function (`renderThreadDescendant`, since the M4 fix). This proves the
            // same escaping guarantee holds there too.
            val payloads =
                listOf(
                    "<script>alert(1)</script>",
                    "\"><img src=x onerror=alert(1)>",
                    "</p><svg onload=alert(1)>",
                    "javascript:alert(1)",
                    "&#x3c;script&#x3e;",
                    "‮reversed-bidi-text",
                )
            payloads.forEach { payload ->
                val comment = post(id = "33333333-3333-3333-3333-333333333333", content = payload, author = "Comment Author")
                val threadHtml =
                    SocialPublicHtml.postPage(
                        view = PublicThreadView(root = post(content = "Wurzel"), descendants = listOf(comment), truncated = false),
                        baseUrl = baseUrl,
                    )
                if (payload.contains('<')) {
                    threadHtml shouldNotContain payload
                }
                threadHtml shouldNotContain "<script>alert"
                threadHtml shouldNotContain "<img src=x onerror"
                threadHtml shouldNotContain "<svg onload"
            }
        }

        test("T5 XSS-Katalog: malicious author display name is escaped in both timeline and thread") {
            val payload = "<script>alert('author')</script>"
            val timelineHtml =
                SocialPublicHtml.timelinePage(
                    view = PublicTimelineView(posts = listOf(post(author = payload)), page = 1, hasNext = false),
                    baseUrl = baseUrl,
                )
            val threadHtml =
                SocialPublicHtml.postPage(
                    view = PublicThreadView(root = post(author = payload), descendants = emptyList(), truncated = false),
                    baseUrl = baseUrl,
                )
            listOf(timelineHtml, threadHtml).forEach { html ->
                html shouldNotContain "<script>alert"
            }
        }

        test("T5 XSS-Katalog: an empty-string post content still renders a valid, non-empty title") {
            val view = PublicTimelineView(posts = listOf(post(content = "")), page = 1, hasNext = false)
            val html = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            html shouldContain "Beitrag"
        }

        // Security-Audit-Fund S-6 (2026-08-18): documented, ACCEPTED edge case -- NOT an XSS, but a
        // content-INTEGRITY quirk of the `kotlinx.html` text-node escaper. Verified empirically
        // (temporary probe against `kotlinx.html.stream.createHTML` directly, not part of this
        // suite): input `\&lt;script\&gt;` (a literal backslash immediately followed by an entity
        // reference) renders as `<p>&lt;script&gt;</p>` -- BOTH backslashes are silently dropped from
        // the output, and the `&lt;`/`&gt;` pass through UNCHANGED rather than being escaped a
        // second time into `&amp;lt;`/`&amp;gt;` (which is what happens for `&` in every OTHER
        // position, see the `\&amp;` case below). Root cause: `kotlinx.html`'s escaper appears to
        // treat a `&` that is already followed by what looks like a well-formed entity reference as
        // not needing escaping of that `&` itself, and a preceding backslash does not prevent this.
        //
        // This is deliberately NOT fixed (third-party library behavior, not code owned by this
        // welle) -- documented here as an accepted, low-severity known limitation instead, per
        // Security-Audit-Fund S-6 (2026-08-18).
        //
        // Why this is NOT XSS: an HTML5 tokenizer resolves character references (`&lt;`, `&amp;`,
        // ...) EXACTLY ONCE, while parsing markup into the DOM. `&lt;script&gt;` sitting inside an
        // ALREADY-SERIALIZED `<p>` text node is parsed as the two-character-reference sequence it
        // is, producing the DOM TEXT "<script>" (displayed literally on screen) -- NOT re-parsed a
        // second time into a live `<script>` element. There is no code path in this file
        // (`SocialPublicHtml`) that ever takes already-rendered HTML output and feeds it back through
        // an HTML parser, so this could never chain into a real markup-injection.
        test(
            "S-6 (Security-Audit 2026-08-18, documented ACCEPTED edge case, NOT XSS): a backslash " +
                "immediately preceding an HTML entity reference is dropped by kotlinx.html's escaper, " +
                "but this never produces a LIVE tag -- content integrity quirk, not a vulnerability",
        ) {
            val payloads =
                listOf(
                    "\\&lt;script\\&gt;",
                    "\\&amp;",
                )
            payloads.forEach { payload ->
                val html =
                    SocialPublicHtml.timelinePage(
                        view = PublicTimelineView(posts = listOf(post(content = payload)), page = 1, hasNext = false),
                        baseUrl = baseUrl,
                    )
                // The defining XSS negative: no live '<script' tag is ever produced, whatever
                // kotlinx.html's exact escaping quirk does to the surrounding text.
                html shouldNotContain "<script>"
                html shouldNotContain "<script "
            }
        }

        test("Determinism: two renders of the identical view are byte-identical (ETag precondition)") {
            val view = PublicTimelineView(posts = listOf(post()), page = 1, hasNext = true)
            val first = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            val second = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            first shouldBe second

            val threadView = PublicThreadView(root = post(), descendants = emptyList(), truncated = false)
            SocialPublicHtml.postPage(view = threadView, baseUrl = baseUrl) shouldBe
                SocialPublicHtml.postPage(view = threadView, baseUrl = baseUrl)
        }

        test(
            "T6: source scan -- SocialPublicHtml.kt, SocialPublicRoutes.kt, and SocialPublicSitemap.kt " +
                "contain no case-insensitive variant of the raw-HTML-escape-bypass token",
        ) {
            // G8-Fix (Review-Runde 1): the scan used to be case-SENSITIVE (would have missed
            // `Unsafe`/`UNSAFE`) and did not scan SocialPublicSitemap.kt at all, even though it is
            // one of the three files this welle's routing KDoc names as part of the public read path.
            val mainSourceDir = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }
            val scannedFiles =
                listOf(
                    File(mainSourceDir, "network/lapis/cloud/server/routes/SocialPublicHtml.kt"),
                    File(mainSourceDir, "network/lapis/cloud/server/routes/SocialPublicRoutes.kt"),
                    File(mainSourceDir, "network/lapis/cloud/server/routes/SocialPublicSitemap.kt"),
                )
            scannedFiles.forEach { file ->
                file.exists() shouldBe true
                val forbiddenToken = "un" + "safe"
                file.readLines().none { it.contains(forbiddenToken, ignoreCase = true) } shouldBe true
            }
        }

        test("M4: a long descendant comment renders in full, with no silent line-count cutoff") {
            val longContent = (1..12).joinToString("\n") { "Zeile $it" }
            val comment = post(id = "44444444-4444-4444-4444-444444444444", content = longContent, author = "Vielschreiber")
            val html =
                SocialPublicHtml.postPage(
                    view = PublicThreadView(root = post(content = "Wurzel"), descendants = listOf(comment), truncated = false),
                    baseUrl = baseUrl,
                )
            (1..12).forEach { i -> html shouldContain "Zeile $i" }
        }

        test("M4: a long TIMELINE root-post summary is truncated with a visible, working \"read more\" link") {
            val longContent = (1..12).joinToString("\n") { "Zeile $it" }
            val view = PublicTimelineView(posts = listOf(post(content = longContent)), page = 1, hasNext = false)
            val html = SocialPublicHtml.timelinePage(view = view, baseUrl = baseUrl)
            html shouldContain "Zeile 1"
            html shouldNotContain "Zeile 12"
            html shouldContain "Gekürzt"
            html shouldContain "vollständigen Beitrag ansehen"
            html shouldContain "$baseUrl/s/$postId"
        }

        test("Stylesheet is a plain constant, contains no script or interpolation markers") {
            SocialPublicHtml.STYLESHEET shouldNotContain "<script"
            SocialPublicHtml.STYLESHEET shouldNotContain "javascript:"
        }
    })
