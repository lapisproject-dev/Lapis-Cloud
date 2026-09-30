package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Welle V1.9.15 -- the editor's DOM normaliser and its pure helpers (no editor, no RPC). */
class MailingHtmlNormalizerTest {
    private fun normalized(html: String): String {
        val root: Element = document.createElement("div")
        root.innerHTML = html
        normalizeMailingHtml(root)
        return root.innerHTML
    }

    @Test
    fun boldItalicAndDiv_areRenamedToTheServersTags() {
        assertEquals("<strong>a</strong>", normalized("<b>a</b>"))
        assertEquals("<em>a</em>", normalized("<i>a</i>"))
        assertEquals("<p>a</p>", normalized("<div>a</div>"))
    }

    @Test
    fun aDivWrappingBlocks_isUnwrapped_notTurnedIntoANestedParagraph() {
        assertEquals("<p>a</p><p>b</p>", normalized("<div><p>a</p><p>b</p></div>"))
    }

    @Test
    fun spanAndFont_areUnwrapped_andTheirTextKept() {
        assertEquals("Hallo Welt", normalized("<span style=\"color:red\">Hallo</span> <font color=\"red\">Welt</font>"))
    }

    @Test
    fun everyAttributeExceptAnchorHref_isRemoved() {
        assertEquals("<p>a</p>", normalized("<p style=\"x\" class=\"y\" id=\"z\" onclick=\"alert(1)\">a</p>"))
        assertEquals(
            "<a href=\"https://example.org/\">l</a>",
            normalized("<a href=\"https://example.org/\" target=\"_blank\" onclick=\"x\" style=\"y\">l</a>"),
        )
    }

    @Test
    fun imagesScriptsAndTables_areDroppedOrUnwrapped() {
        assertEquals("<p>a</p>", normalized("<p>a<img src=\"x\"></p>"))
        assertEquals("<p>a</p>", normalized("<p>a</p><script>alert(1)</script>"))
        assertEquals("<p>a</p>", normalized("<p>a</p><style>p{}</style>"))
        assertEquals("cell", normalized("<table><tbody><tr><td>cell</td></tr></tbody></table>"))
        assertFalse(normalized("<iframe src=\"https://evil.example\">x</iframe>t").contains("iframe"))
    }

    @Test
    fun anchorsWithAnUnsafeScheme_areUnwrapped() {
        assertEquals("<p>text</p>", normalized("<p><a href=\"javascript:alert(1)\">text</a></p>"))
        assertEquals("<p>text</p>", normalized("<p><a href=\"data:text/html,x\">text</a></p>"))
        assertEquals("<p>text</p>", normalized("<p><a href=\"ftp://x.example\">text</a></p>"))
        assertEquals("<p>text</p>", normalized("<p><a>text</a></p>"))
    }

    @Test
    fun comments_areRemoved() {
        assertEquals("<p>a</p>", normalized("<p>a</p><!-- hidden -->"))
    }

    @Test
    fun serializeNormalized_doesNotTouchTheLiveTree() {
        val root = document.createElement("div")
        root.innerHTML = "<b>a</b><script>alert(1)</script>"
        val serialized = serializeNormalized(root)
        assertEquals("<strong>a</strong>", serialized)
        assertEquals("<b>a</b><script>alert(1)</script>", root.innerHTML)
    }

    @Test
    fun plainTextToParagraphHtml_escapesAndBuildsParagraphsAndBreaks() {
        assertEquals("<p>a &lt;script&gt;x&lt;/script&gt;</p>", plainTextToParagraphHtml("a <script>x</script>"))
        assertEquals("<p>eins</p><p>zwei<br>drei</p>", plainTextToParagraphHtml("eins\n\nzwei\ndrei"))
        assertEquals("<p>a</p><p>b</p>", plainTextToParagraphHtml("a\r\n\r\nb"))
        assertEquals("", plainTextToParagraphHtml("  \n\n  "))
        assertEquals("a &amp; &quot;b&quot;", plainTextToInlineHtml("a & \"b\""))
    }

    @Test
    fun isValidLinkUrl_matrix() {
        listOf("https://example.org", "http://example.org/a?b=c", "HTTPS://EXAMPLE.ORG", "mailto:a@example.org").forEach {
            assertTrue(isValidLinkUrl(it), it)
        }
        listOf(
            "",
            "https://",
            "http://",
            "mailto:",
            "ftp://example.org",
            "javascript:alert(1)",
            "data:text/html,x",
            "//example.org",
            "example.org",
            "https://exa mple.org",
            "https://example.org/\nx",
            "https://example.org/\u0000",
            "https://example.org/" + "a".repeat(2100),
        ).forEach { assertFalse(isValidLinkUrl(it), it.take(40)) }
    }
}
