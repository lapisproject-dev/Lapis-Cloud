@file:Suppress("ktlint:standard:max-line-length")

package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.agreementtexts.AgreementMarkdown

/**
 * V1.9.337 characterization of the Markdown pipeline, recorded on commonmark 0.22.0 BEFORE the bump to 0.33.0. Every expectation below is the
 * exact output of the 0.22.0 pipeline; after the bump each deviation has to be justified one by one (legitimate spec fix, never a security
 * aspect: escaping, `href`, images) before an expectation may change.
 *
 * Per input: [ArticleMarkdown.render] (article defaults), [AgreementMarkdown.render] (soft breaks as `<br />`), [AgreementMarkdown.toPlainText]
 * and [AgreementMarkdown.hasBlockNodes].
 *
 * Deviations accepted after the bump to 0.33.0 (all legitimate fixes, none touches escaping, `href`, images or [AgreementMarkdown.hasBlockNodes];
 * the HTML of every case is byte-identical except the surrogate one):
 * - `toPlainText` of nested lists / block quotes (0.27.1, "TextContentRenderer: fix nested lists on the same line"): nested list markers on one
 *   line stay on that line (`- - - a`), indented sub-lists keep their indentation, nested quotes get line breaks.
 * - numeric character references to unpaired surrogates (`&#xD800;`) become U+FFFD instead of a lone surrogate (0.33.0, #474).
 */
class MarkdownCharacterizationTest :
    FunSpec({
        data class Case(
            val name: String,
            val input: String,
            val article: String,
            val agreement: String,
            val plain: String,
            val blocks: Boolean,
        )

        val cases =
            listOf(
                Case(
                    name = "h1",
                    input = "# Titel\n\nText",
                    article = "<h2>Titel</h2>\n<p>Text</p>\n",
                    agreement = "<h2>Titel</h2>\n<p>Text</p>\n",
                    plain = "Titel\nText",
                    blocks = true,
                ),
                Case(
                    name = "h-levels",
                    input = "## Zwei\n### Drei\n#### Vier\n##### F\u00fcnf\n###### Sechs",
                    article = "<h3>Zwei</h3>\n<h4>Drei</h4>\n<h4>Vier</h4>\n<h4>F\u00fcnf</h4>\n<h4>Sechs</h4>\n",
                    agreement = "<h3>Zwei</h3>\n<h4>Drei</h4>\n<h4>Vier</h4>\n<h4>F\u00fcnf</h4>\n<h4>Sechs</h4>\n",
                    plain = "Zwei\nDrei\nVier\nF\u00fcnf\nSechs",
                    blocks = true,
                ),
                Case(
                    name = "setext",
                    input = "Titel\n=====\n\nUnter\n-----\n\nText",
                    article = "<h2>Titel</h2>\n<h3>Unter</h3>\n<p>Text</p>\n",
                    agreement = "<h2>Titel</h2>\n<h3>Unter</h3>\n<p>Text</p>\n",
                    plain = "Titel\nUnter\nText",
                    blocks = true,
                ),
                Case(
                    name = "ul",
                    input = "- a\n- b\n- c",
                    article = "<ul>\n<li>a</li>\n<li>b</li>\n<li>c</li>\n</ul>\n",
                    agreement = "<ul>\n<li>a</li>\n<li>b</li>\n<li>c</li>\n</ul>\n",
                    plain = "- a\n- b\n- c",
                    blocks = true,
                ),
                Case(
                    name = "ol",
                    input = "1. a\n2. b\n3. c",
                    article = "<ol>\n<li>a</li>\n<li>b</li>\n<li>c</li>\n</ol>\n",
                    agreement = "<ol>\n<li>a</li>\n<li>b</li>\n<li>c</li>\n</ol>\n",
                    plain = "1. a\n2. b\n3. c",
                    blocks = true,
                ),
                Case(
                    name = "ol-start",
                    input = "3. a\n4. b",
                    article = "<ol start=\"3\">\n<li>a</li>\n<li>b</li>\n</ol>\n",
                    agreement = "<ol start=\"3\">\n<li>a</li>\n<li>b</li>\n</ol>\n",
                    plain = "3. a\n4. b",
                    blocks = true,
                ),
                Case(
                    name = "nested-list",
                    input = "- a\n  - b\n    - c\n- d",
                    article = "<ul>\n<li>a\n<ul>\n<li>b\n<ul>\n<li>c</li>\n</ul>\n</li>\n</ul>\n</li>\n<li>d</li>\n</ul>\n",
                    agreement = "<ul>\n<li>a\n<ul>\n<li>b\n<ul>\n<li>c</li>\n</ul>\n</li>\n</ul>\n</li>\n<li>d</li>\n</ul>\n",
                    plain = "- a\n  - b\n    - c\n- d",
                    blocks = true,
                ),
                Case(
                    name = "oneline-nested-list",
                    input = "- - - a",
                    article = "<ul>\n<li>\n<ul>\n<li>\n<ul>\n<li>a</li>\n</ul>\n</li>\n</ul>\n</li>\n</ul>\n",
                    agreement = "<ul>\n<li>\n<ul>\n<li>\n<ul>\n<li>a</li>\n</ul>\n</li>\n</ul>\n</li>\n</ul>\n",
                    plain = "- - - a",
                    blocks = true,
                ),
                Case(
                    name = "oneline-nested-ol",
                    input = "1. - 2. x",
                    article = "<ol>\n<li>\n<ul>\n<li>\n<ol start=\"2\">\n<li>x</li>\n</ol>\n</li>\n</ul>\n</li>\n</ol>\n",
                    agreement = "<ol>\n<li>\n<ul>\n<li>\n<ol start=\"2\">\n<li>x</li>\n</ol>\n</li>\n</ul>\n</li>\n</ol>\n",
                    plain = "1. - 2. x",
                    blocks = true,
                ),
                Case(
                    name = "quote",
                    input = "> zitat\n> zweite Zeile\n\nText",
                    article = "<blockquote>\n<p>zitat\nzweite Zeile</p>\n</blockquote>\n<p>Text</p>\n",
                    agreement = "<blockquote>\n<p>zitat<br />\nzweite Zeile</p>\n</blockquote>\n<p>Text</p>\n",
                    plain = "\u00abzitat\nzweite Zeile\u00bb\nText",
                    blocks = true,
                ),
                Case(
                    name = "quote-nested",
                    input = "> a\n>> b\n>>> c",
                    article = "<blockquote>\n<p>a</p>\n<blockquote>\n<p>b</p>\n<blockquote>\n<p>c</p>\n</blockquote>\n</blockquote>\n</blockquote>\n",
                    agreement = "<blockquote>\n<p>a</p>\n<blockquote>\n<p>b</p>\n<blockquote>\n<p>c</p>\n</blockquote>\n</blockquote>\n</blockquote>\n",
                    plain = "\u00aba\n\u00abb\n\u00abc\u00bb\u00bb\u00bb",
                    blocks = true,
                ),
                Case(
                    name = "fence",
                    input = "```kotlin\nval x = 1 < 2\n```",
                    article = "<pre><code class=\"language-kotlin\">val x = 1 &lt; 2\n</code></pre>\n",
                    agreement = "<pre><code class=\"language-kotlin\">val x = 1 &lt; 2\n</code></pre>\n",
                    plain = "val x = 1 < 2",
                    blocks = true,
                ),
                Case(
                    name = "fence-tilde",
                    input = "~~~\nplain\n~~~",
                    article = "<pre><code>plain\n</code></pre>\n",
                    agreement = "<pre><code>plain\n</code></pre>\n",
                    plain = "plain",
                    blocks = true,
                ),
                Case(
                    name = "indented-code",
                    input = "    code <b>\n    mehr",
                    article = "<pre><code>code &lt;b&gt;\nmehr\n</code></pre>\n",
                    agreement = "<pre><code>code &lt;b&gt;\nmehr\n</code></pre>\n",
                    plain = "code <b>\nmehr",
                    blocks = true,
                ),
                Case(
                    name = "inline-code",
                    input = "Ein `code <x>` Span und ``mit ` Tick``",
                    article = "<p>Ein <code>code &lt;x&gt;</code> Span und <code>mit ` Tick</code></p>\n",
                    agreement = "<p>Ein <code>code &lt;x&gt;</code> Span und <code>mit ` Tick</code></p>\n",
                    plain = "Ein \"code <x>\" Span und \"mit ` Tick\"",
                    blocks = false,
                ),
                Case(
                    name = "thematic",
                    input = "a\n\n***\n\nb\n\n---\n\nc",
                    article = "<p>a</p>\n<hr />\n<p>b</p>\n<hr />\n<p>c</p>\n",
                    agreement = "<p>a</p>\n<hr />\n<p>b</p>\n<hr />\n<p>c</p>\n",
                    plain = "a\n***\nb\n***\nc",
                    blocks = true,
                ),
                Case(
                    name = "linkref-only",
                    input = "[foo]: https://example.org \"Titel\"",
                    article = "",
                    agreement = "",
                    plain = "",
                    blocks = true,
                ),
                Case(
                    name = "linkref-with-para",
                    input = "[foo]: https://example.org\n\nSiehe [foo] und [bar].",
                    article = "<p>Siehe <a href=\"https://example.org\" target=\"_blank\" rel=\"nofollow noopener ugc\">foo</a> und [bar].</p>\n",
                    agreement = "<p>Siehe <a href=\"https://example.org\" target=\"_blank\" rel=\"nofollow noopener ugc\">foo</a> und [bar].</p>\n",
                    plain = "Siehe \"foo\" (https://example.org) und [bar].",
                    blocks = true,
                ),
                Case(
                    name = "linkref-unbalanced",
                    input = "[foo]: <https://example.org/a(b>\n\n[foo]",
                    article = "<p><a href=\"https://example.org/a(b\" target=\"_blank\" rel=\"nofollow noopener ugc\">foo</a></p>\n",
                    agreement = "<p><a href=\"https://example.org/a(b\" target=\"_blank\" rel=\"nofollow noopener ugc\">foo</a></p>\n",
                    plain = "\"foo\" (https://example.org/a(b)",
                    blocks = true,
                ),
                Case(
                    name = "autolink",
                    input = "<https://example.org/pfad?x=1&y=2> und <mailto:a@b.de> und <a@b.de>",
                    article = "<p><a href=\"https://example.org/pfad?x=1&amp;y=2\" target=\"_blank\" rel=\"nofollow noopener ugc\">https://example.org/pfad?x=1&amp;y=2</a> und <a href=\"mailto:a@b.de\" rel=\"nofollow noopener ugc\">mailto:a@b.de</a> und <a href=\"mailto:a@b.de\" rel=\"nofollow noopener ugc\">a@b.de</a></p>\n",
                    agreement = "<p><a href=\"https://example.org/pfad?x=1&amp;y=2\" target=\"_blank\" rel=\"nofollow noopener ugc\">https://example.org/pfad?x=1&amp;y=2</a> und <a href=\"mailto:a@b.de\" rel=\"nofollow noopener ugc\">mailto:a@b.de</a> und <a href=\"mailto:a@b.de\" rel=\"nofollow noopener ugc\">a@b.de</a></p>\n",
                    plain = "\"https://example.org/pfad?x=1&y=2\" (https://example.org/pfad?x=1&y=2) und \"mailto:a@b.de\" (mailto:a@b.de) und \"a@b.de\" (mailto:a@b.de)",
                    blocks = false,
                ),
                Case(
                    name = "autolink-js",
                    input = "<javascript:alert(1)>",
                    article = "<p><a>javascript:alert(1)</a></p>\n",
                    agreement = "<p><a>javascript:alert(1)</a></p>\n",
                    plain = "\"javascript:alert(1)\" (javascript:alert(1))",
                    blocks = false,
                ),
                Case(
                    name = "image-code-alt",
                    input = "![mit `code` im Alt](https://example.org/x.png)",
                    article = "<p>mit  im Alt</p>\n",
                    agreement = "<p>mit  im Alt</p>\n",
                    plain = "\"mit \"code\" im Alt\" (https://example.org/x.png)",
                    blocks = false,
                ),
                Case(
                    name = "image-nested-emph",
                    input = "![*fett* **stark** x](https://example.org/x.png)",
                    article = "<p>fett stark x</p>\n",
                    agreement = "<p>fett stark x</p>\n",
                    plain = "\"fett stark x\" (https://example.org/x.png)",
                    blocks = false,
                ),
                Case(
                    name = "entities",
                    input = "&amp; &lt; &gt; &quot; &copy; &#35; &#x41; &nbsp;|",
                    article = "<p>&amp; &lt; &gt; &quot; \u00a9 # A \u00a0|</p>\n",
                    agreement = "<p>&amp; &lt; &gt; &quot; \u00a9 # A \u00a0|</p>\n",
                    plain = "& < > \" \u00a9 # A \u00a0|",
                    blocks = false,
                ),
                Case(
                    name = "entity-surrogate",
                    input = "x &#xD800; y &#55357; z",
                    article = "<p>x \ufffd y \ufffd z</p>\n",
                    agreement = "<p>x \ufffd y \ufffd z</p>\n",
                    plain = "x \ufffd y \ufffd z",
                    blocks = false,
                ),
                Case(
                    name = "entity-unknown",
                    input = "&unknownentity; &#0; &#x110000;",
                    article = "<p>&amp;unknownentity; \ufffd \ufffd</p>\n",
                    agreement = "<p>&amp;unknownentity; \ufffd \ufffd</p>\n",
                    plain = "&unknownentity; \ufffd \ufffd",
                    blocks = false,
                ),
                Case(
                    name = "link-parens",
                    input = "[x](https://example.org/a_(b)) und [y](https://example.org/a\\(b)",
                    article = "<p><a href=\"https://example.org/a_(b)\" target=\"_blank\" rel=\"nofollow noopener ugc\">x</a> und <a href=\"https://example.org/a(b\" target=\"_blank\" rel=\"nofollow noopener ugc\">y</a></p>\n",
                    agreement = "<p><a href=\"https://example.org/a_(b)\" target=\"_blank\" rel=\"nofollow noopener ugc\">x</a> und <a href=\"https://example.org/a(b\" target=\"_blank\" rel=\"nofollow noopener ugc\">y</a></p>\n",
                    plain = "\"x\" (https://example.org/a_(b)) und \"y\" (https://example.org/a(b)",
                    blocks = false,
                ),
                Case(
                    name = "link-title",
                    input = "[x](https://example.org \"Ti\\\"tel\") [y](https://example.org 'a\"b')",
                    article = "<p><a href=\"https://example.org\" title=\"Ti&quot;tel\" target=\"_blank\" rel=\"nofollow noopener ugc\">x</a> <a href=\"https://example.org\" title=\"a&quot;b\" target=\"_blank\" rel=\"nofollow noopener ugc\">y</a></p>\n",
                    agreement = "<p><a href=\"https://example.org\" title=\"Ti&quot;tel\" target=\"_blank\" rel=\"nofollow noopener ugc\">x</a> <a href=\"https://example.org\" title=\"a&quot;b\" target=\"_blank\" rel=\"nofollow noopener ugc\">y</a></p>\n",
                    plain = "\"x\" (Ti\"tel: https://example.org) \"y\" (a\"b: https://example.org)",
                    blocks = false,
                ),
                Case(
                    name = "link-mixed-case",
                    input = "[x](HTTP://example.org) [y](Https://example.org) [z](MAILTO:a@b.de)",
                    article = "<p><a>x</a> <a>y</a> <a>z</a></p>\n",
                    agreement = "<p><a>x</a> <a>y</a> <a>z</a></p>\n",
                    plain = "\"x\" (HTTP://example.org) \"y\" (Https://example.org) \"z\" (MAILTO:a@b.de)",
                    blocks = false,
                ),
                Case(
                    name = "link-schemes",
                    input = "[a](javascript:alert(1)) [b](JaVaScRiPt:x) [c](data:text/html,x) [d](vbscript:x) [e](/rel) [f](#frag) [g](tel:123)",
                    article = "<p><a>a</a> <a>b</a> <a>c</a> <a>d</a> <a>e</a> <a>f</a> <a>g</a></p>\n",
                    agreement = "<p><a>a</a> <a>b</a> <a>c</a> <a>d</a> <a>e</a> <a>f</a> <a>g</a></p>\n",
                    plain = "\"a\" (javascript:alert(1)) \"b\" (JaVaScRiPt:x) \"c\" (data:text/html,x) \"d\" (vbscript:x) \"e\" (/rel) \"f\" (#frag) \"g\" (tel:123)",
                    blocks = false,
                ),
                Case(
                    name = "link-entity-scheme",
                    input = "[a](&#106;avascript:alert(1)) [b](java&#x09;script:x)",
                    article = "<p><a>a</a> <a>b</a></p>\n",
                    agreement = "<p><a>a</a> <a>b</a></p>\n",
                    plain = "\"a\" (javascript:alert(1)) \"b\" (java\tscript:x)",
                    blocks = false,
                ),
                Case(
                    name = "link-attr-injection",
                    input = "[x](https://a\"onmouseover=\"b) [y](<https://a b>)",
                    article = "<p><a href=\"https://a&quot;onmouseover=&quot;b\" target=\"_blank\" rel=\"nofollow noopener ugc\">x</a> <a href=\"https://a b\" target=\"_blank\" rel=\"nofollow noopener ugc\">y</a></p>\n",
                    agreement = "<p><a href=\"https://a&quot;onmouseover=&quot;b\" target=\"_blank\" rel=\"nofollow noopener ugc\">x</a> <a href=\"https://a b\" target=\"_blank\" rel=\"nofollow noopener ugc\">y</a></p>\n",
                    plain = "\"x\" (https://a\"onmouseover=\"b) \"y\" (https://a b)",
                    blocks = false,
                ),
                Case(
                    name = "link-nested",
                    input = "[[x](javascript:y)](https://example.org)",
                    article = "<p>[<a>x</a>](https://example.org)</p>\n",
                    agreement = "<p>[<a>x</a>](https://example.org)</p>\n",
                    plain = "[\"x\" (javascript:y)](https://example.org)",
                    blocks = false,
                ),
                Case(
                    name = "softbreak",
                    input = "zeile eins\nzeile zwei",
                    article = "<p>zeile eins\nzeile zwei</p>\n",
                    agreement = "<p>zeile eins<br />\nzeile zwei</p>\n",
                    plain = "zeile eins\nzeile zwei",
                    blocks = false,
                ),
                Case(
                    name = "hardbreak-spaces",
                    input = "zeile eins  \nzeile zwei",
                    article = "<p>zeile eins<br />\nzeile zwei</p>\n",
                    agreement = "<p>zeile eins<br />\nzeile zwei</p>\n",
                    plain = "zeile eins\nzeile zwei",
                    blocks = false,
                ),
                Case(
                    name = "hardbreak-backslash",
                    input = "zeile eins\\\nzeile zwei",
                    article = "<p>zeile eins<br />\nzeile zwei</p>\n",
                    agreement = "<p>zeile eins<br />\nzeile zwei</p>\n",
                    plain = "zeile eins\nzeile zwei",
                    blocks = false,
                ),
                Case(
                    name = "html-block",
                    input = "<div onclick=\"x\">\nhallo\n</div>\n\nText",
                    article = "<p>&lt;div onclick=&quot;x&quot;&gt;\nhallo\n&lt;/div&gt;</p>\n<p>Text</p>\n",
                    agreement = "<p>&lt;div onclick=&quot;x&quot;&gt;\nhallo\n&lt;/div&gt;</p>\n<p>Text</p>\n",
                    plain = "<div onclick=\"x\">\nhallo\n</div>Text",
                    blocks = true,
                ),
                Case(
                    name = "html-inline",
                    input = "a <b>fett</b> <script>alert(1)</script> <img src=x onerror=alert(1)> b",
                    article = "<p>a &lt;b&gt;fett&lt;/b&gt; &lt;script&gt;alert(1)&lt;/script&gt; &lt;img src=x onerror=alert(1)&gt; b</p>\n",
                    agreement = "<p>a &lt;b&gt;fett&lt;/b&gt; &lt;script&gt;alert(1)&lt;/script&gt; &lt;img src=x onerror=alert(1)&gt; b</p>\n",
                    plain = "a <b>fett</b> <script>alert(1)</script> <img src=x onerror=alert(1)> b",
                    blocks = false,
                ),
                Case(
                    name = "html-comment",
                    input = "<!-- kommentar --> text",
                    article = "<p>&lt;!-- kommentar --&gt; text</p>\n",
                    agreement = "<p>&lt;!-- kommentar --&gt; text</p>\n",
                    plain = "<!-- kommentar --> text",
                    blocks = true,
                ),
                Case(
                    name = "emph",
                    input = "*a* **b** ***c*** _d_ __e__ *a **b** c*",
                    article = "<p><em>a</em> <strong>b</strong> <em><strong>c</strong></em> <em>d</em> <strong>e</strong> <em>a <strong>b</strong> c</em></p>\n",
                    agreement = "<p><em>a</em> <strong>b</strong> <em><strong>c</strong></em> <em>d</em> <strong>e</strong> <em>a <strong>b</strong> c</em></p>\n",
                    plain = "a b c d e a b c",
                    blocks = false,
                ),
                Case(
                    name = "emph-unbalanced",
                    input = "*a **b* c** ***",
                    article = "<p><em>a <em><em>b</em> c</em></em> ***</p>\n",
                    agreement = "<p><em>a <em><em>b</em> c</em></em> ***</p>\n",
                    plain = "a b c ***",
                    blocks = false,
                ),
                Case(
                    name = "mixed",
                    input = "# T\n\n- a **b**\n  > q [l](https://example.org)\n\n```\ncode\n```\n\nEnde",
                    article = "<h2>T</h2>\n<ul>\n<li>a <strong>b</strong>\n<blockquote>\n<p>q <a href=\"https://example.org\" target=\"_blank\" rel=\"nofollow noopener ugc\">l</a></p>\n</blockquote>\n</li>\n</ul>\n<pre><code>code\n</code></pre>\n<p>Ende</p>\n",
                    agreement = "<h2>T</h2>\n<ul>\n<li>a <strong>b</strong>\n<blockquote>\n<p>q <a href=\"https://example.org\" target=\"_blank\" rel=\"nofollow noopener ugc\">l</a></p>\n</blockquote>\n</li>\n</ul>\n<pre><code>code\n</code></pre>\n<p>Ende</p>\n",
                    plain = "T\n- a b\n  \u00abq \"l\" (https://example.org)\u00bb\ncode\nEnde",
                    blocks = true,
                ),
                Case(
                    name = "umlaute",
                    input = "\u00dcberschrift \u00e4\u00f6\u00fc\u00df \u20ac",
                    article = "<p>\u00dcberschrift \u00e4\u00f6\u00fc\u00df \u20ac</p>\n",
                    agreement = "<p>\u00dcberschrift \u00e4\u00f6\u00fc\u00df \u20ac</p>\n",
                    plain = "\u00dcberschrift \u00e4\u00f6\u00fc\u00df \u20ac",
                    blocks = false,
                ),
                Case(
                    name = "empty",
                    input = "",
                    article = "",
                    agreement = "",
                    plain = "",
                    blocks = false,
                ),
                Case(
                    name = "blank",
                    input = "  \n\n  ",
                    article = "",
                    agreement = "",
                    plain = "",
                    blocks = false,
                ),
            )

        test("the case table is large enough to be a meaningful pin") {
            (cases.size >= 30) shouldBe true
        }

        for (case in cases) {
            test("article render: ${case.name}") { ArticleMarkdown.render(markdown = case.input) shouldBe case.article }
            test("agreement render: ${case.name}") { AgreementMarkdown.render(body = case.input) shouldBe case.agreement }
            test("plain text: ${case.name}") { AgreementMarkdown.toPlainText(case.input) shouldBe case.plain }
            test("hasBlockNodes: ${case.name}") { AgreementMarkdown.hasBlockNodes(case.input) shouldBe case.blocks }
        }
    })
