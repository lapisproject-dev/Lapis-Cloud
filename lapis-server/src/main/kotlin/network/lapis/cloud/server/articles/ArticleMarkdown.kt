package network.lapis.cloud.server.articles

import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Server-side Markdown -> sanitized HTML rendering for the article body -- the ONLY place a
 * `article.body` Markdown source is ever turned into HTML, used both by the public
 * `/aktuelles/{slug}` route and by `ArticleService.previewArticle`/`getArticleForReview` (so the
 * board reviews EXACTLY the HTML the public page will later show, never the raw Markdown -- see
 * [network.lapis.cloud.shared.domain.ArticleReviewDto] KDoc).
 *
 * Deliberately narrow scope ("Rams' Umfang" -- Design-Team decision): no GFM tables/strikethrough
 * extension, and three defense-in-depth departures from commonmark-java's defaults:
 * 1. [escapeHtml] true -- any raw HTML/`<script>` in the source is escaped as text, never passed
 *    through.
 * 2. Links: a `javascript:`/`data:`/other non-http(s)/mailto scheme has its `href` stripped
 *    entirely (dead `<a>`, not a removed element -- keeps the link TEXT visible); `http(s)` links
 *    get `target="_blank"` + `rel="nofollow noopener ugc"`; `mailto:` links keep `rel` but no
 *    `target` (no external tab context makes sense for a mail client).
 * 3. Images render as their alt text ONLY, never an `<img>` tag -- commonmark-java has no built-in
 *    "image -> alt text" mode; [ImageAsAltTextRenderer] below recurses into the [Image] node's
 *    children (the alt text is a set of inline child nodes in commonmark's AST, not an attribute)
 *    and emits their text content.
 * 4. Headings are shifted down by one level and capped at h4 ([HeadingShiftRenderer]) -- an
 *    article's own `<h1>` is reserved for the page-level title the surrounding page template
 *    renders, so a `# Foo` in the body becomes `<h2>Foo</h2>`, six-deep nesting caps at `<h4>`.
 */
object ArticleMarkdown {
    private val parser: Parser = Parser.builder().extensions(emptyList()).build()

    fun render(markdown: String): String {
        // Defense-in-depth cap -- ArticlePolicy.BODY_MAX already bounds this at the write path
        // (saveDraft/submitArticle), this is a second, independent belt for any caller that
        // reaches this function with an un-vetted string (e.g. a future direct previewArticle
        // call before validateDraftLengths has run).
        val document = parser.parse(markdown.take(ArticlePolicy.BODY_MAX))
        val renderer =
            HtmlRenderer
                .builder()
                .escapeHtml(true)
                .attributeProviderFactory { LinkAttributeProvider() }
                .nodeRendererFactory { context -> ImageAsAltTextRenderer(context) }
                .nodeRendererFactory { context -> HeadingShiftRenderer(context = context, minLevel = 2, maxLevel = 4) }
                .build()
        return renderer.render(document)
    }
}

/** See [ArticleMarkdown] KDoc point 2. */
private class LinkAttributeProvider : AttributeProvider {
    override fun setAttributes(
        node: Node,
        tagName: String,
        attributes: MutableMap<String, String>,
    ) {
        if (tagName != "a") return
        val href = attributes["href"].orEmpty()
        when {
            href.startsWith("http://") || href.startsWith("https://") -> {
                attributes["target"] = "_blank"
                attributes["rel"] = "nofollow noopener ugc"
            }
            href.startsWith("mailto:") -> {
                attributes["rel"] = "nofollow noopener ugc"
            }
            else -> {
                // javascript:, data:, vbscript:, or any other scheme (including a blank/malformed
                // href) -- strip the href entirely so the anchor renders as dead, inert text
                // rather than an executable/navigable link. The visible link TEXT stays.
                attributes.remove("href")
            }
        }
    }
}

/** See [ArticleMarkdown] KDoc point 3. */
private class ImageAsAltTextRenderer(
    private val context: HtmlNodeRendererContext,
) : NodeRenderer {
    override fun getNodeTypes(): Set<Class<out Node>> = setOf(Image::class.java)

    override fun render(node: Node) {
        val html = context.writer
        html.text(collectAltText(node))
    }

    private fun collectAltText(node: Node): String {
        val builder = StringBuilder()
        appendAltText(node = node, builder = builder)
        return builder.toString()
    }

    private fun appendAltText(
        node: Node,
        builder: StringBuilder,
    ) {
        var child = node.firstChild
        while (child != null) {
            if (child is Text) {
                builder.append(child.literal)
            } else {
                appendAltText(node = child, builder = builder)
            }
            child = child.next
        }
    }
}

/** See [ArticleMarkdown] KDoc point 4. */
private class HeadingShiftRenderer(
    private val context: HtmlNodeRendererContext,
    private val minLevel: Int,
    private val maxLevel: Int,
) : NodeRenderer {
    override fun getNodeTypes(): Set<Class<out Node>> = setOf(Heading::class.java)

    override fun render(node: Node) {
        val heading = node as Heading
        val level = (heading.level + (minLevel - 1)).coerceIn(minLevel, maxLevel)
        val tagName = "h$level"
        val html = context.writer
        html.line()
        html.tag(tagName, context.extendAttributes(node, tagName, emptyMap()))
        var child = node.firstChild
        while (child != null) {
            context.render(child)
            child = child.next
        }
        html.tag("/$tagName")
        html.line()
    }
}
