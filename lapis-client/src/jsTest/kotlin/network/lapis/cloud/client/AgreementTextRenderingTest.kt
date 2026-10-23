package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AgreementTextFormat
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.134 -- the agreement text renderer: the pure block parser (the built-in placeholder's own shape: a title, an intro, bullet items wrapped
 * onto indented lines, a closing paragraph) and the DOM it builds -- element TEXT only, never markup, and KVision's i18n markers never resolve.
 */
class AgreementTextRenderingTest {
    private val placeholder =
        listOf(
            "Beitrittsvertrag und Satzung (Zusammenfassung)",
            "",
            "Mit Ihrer Mitgliedschaft schliessen Sie einen Vertrag mit der",
            "Organisation. Bevor Sie fortfahren, bestaetigen Sie:",
            "",
            "- Die Satzung ist verbindlich.",
            "- Die Aufnahme erfolgt erst nach Pruefung und ausdruecklicher Zustimmung durch",
            "  den Vorstand -- ein Anspruch besteht nicht.",
            "• Ein dritter Punkt mit anderem Zeichen.",
            "",
            "Dieser Hinweis stellt KEINE Rechtsberatung dar.",
        ).joinToString("\n")

    @Test
    fun parse_buildsParagraphsAndBullets_joiningWrappedLinesWithASpace() {
        val blocks = parseAgreementBlocks(placeholder)
        assertEquals(4, blocks.size)
        assertEquals(AgreementBlock.Paragraph("Beitrittsvertrag und Satzung (Zusammenfassung)"), blocks[0])
        assertEquals(
            AgreementBlock.Paragraph(
                "Mit Ihrer Mitgliedschaft schliessen Sie einen Vertrag mit der Organisation. Bevor Sie fortfahren, bestaetigen Sie:",
            ),
            blocks[1],
        )
        assertEquals(
            AgreementBlock.Bullets(
                listOf(
                    "Die Satzung ist verbindlich.",
                    "Die Aufnahme erfolgt erst nach Pruefung und ausdruecklicher Zustimmung durch den Vorstand -- ein Anspruch besteht nicht.",
                    "Ein dritter Punkt mit anderem Zeichen.",
                ),
            ),
            blocks[2],
        )
        assertEquals(AgreementBlock.Paragraph("Dieser Hinweis stellt KEINE Rechtsberatung dar."), blocks[3])
    }

    @Test
    fun parse_aBulletWithoutABlankLineBeforeItStartsAList_andAParagraphAfterItEndsTheList() {
        val blocks = parseAgreementBlocks("Intro\n- eins\n- zwei\nSchluss")
        assertEquals(
            listOf(AgreementBlock.Paragraph("Intro"), AgreementBlock.Bullets(listOf("eins", "zwei")), AgreementBlock.Paragraph("Schluss")),
            blocks,
        )
    }

    @Test
    fun parse_isTotal_emptyAndBlankInputGiveNoBlocks_crlfIsALineBreak() {
        assertEquals(emptyList(), parseAgreementBlocks(""))
        assertEquals(emptyList(), parseAgreementBlocks(" \n\n  \n"))
        assertEquals(
            listOf(AgreementBlock.Paragraph("a b"), AgreementBlock.Paragraph("c")),
            parseAgreementBlocks("a\r\nb\r\n\r\nc"),
        )
    }

    private fun host(
        id: String,
        block: (io.kvision.panel.Root, () -> HTMLElement) -> Unit,
    ) = withMountedRoot(id, block)

    @Test
    fun render_buildsParagraphsAndListItemsAsText() {
        host("agreement-render-structure") { root, element ->
            root.renderAgreementText(placeholder)
            val el = element()
            assertEquals(3, el.querySelectorAll("p").length)
            assertEquals(1, el.querySelectorAll("ul").length)
            assertEquals(3, el.querySelectorAll("ul > li").length)
            assertTrue(el.textContent.orEmpty().contains("den Vorstand -- ein Anspruch besteht nicht."))
        }
    }

    @Test
    fun render_neverBuildsMarkup_aScriptOrImageTagStaysVisibleText() {
        host("agreement-render-xss") { root, element ->
            root.renderAgreementText(
                "<script>window.__agreementXss = 1</script>\n\n- <img src=x onerror=\"window.__agreementXss = 2\">\n- <b>fett</b>",
            )
            val el = element()
            assertNull(el.querySelector("script"))
            assertNull(el.querySelector("img"))
            assertNull(el.querySelector("b"))
            assertTrue(el.textContent.orEmpty().contains("<script>window.__agreementXss = 1</script>"))
            assertTrue(el.textContent.orEmpty().contains("<b>fett</b>"))
            assertTrue(js("typeof window.__agreementXss === 'undefined'").unsafeCast<Boolean>(), "no script ran")
            assertEquals(2, el.querySelectorAll("li").length)
        }
    }

    @Test
    fun render_aForgedKvisionI18nMarkerDoesNotResolveATranslation() {
        host("agreement-render-marker") { root, element ->
            // `Version` is a real catalog key; a forged marker in front of it must NOT be turned into its translation.
            root.renderAgreementText("###KvI18nS###Version")
            val text = element().textContent.orEmpty()
            assertFalse(text.contains("###KvI18n"), "the marker is gone: '$text'")
            assertTrue(text.contains("Version"))
        }
    }

    // ---- V1.9.196: renderAgreementBody / agreementFrame with a MARKDOWN text ----------------------------------------------------------

    private val serverHtml = "<h2>Vertrag</h2>\n<p>Ein <strong>fetter</strong> Absatz.</p>\n<ul>\n<li>eins</li>\n</ul>\n"

    @Test
    fun body_aPlainText_staysElementText_withoutTheHtmlBody_exactlyAsBefore() {
        host("agreement-body-plain") { root, element ->
            root.renderAgreementBody(text = placeholder, format = AgreementTextFormat.PLAIN, renderedHtml = null)
            val el = element()
            assertEquals(3, el.querySelectorAll("p").length)
            assertEquals(3, el.querySelectorAll("ul > li").length)
            assertNull(el.querySelector(".$AGREEMENT_BODY_CLASS"))
        }
        host("agreement-body-plain-ignores-html") { root, element ->
            // a plain text never takes HTML, even if one were handed over
            root.renderAgreementBody(
                text = "Nur Text",
                format = AgreementTextFormat.PLAIN,
                renderedHtml = "<script>window.__agreementXss = 3</script>",
            )
            assertNull(element().querySelector("script"))
            assertNull(element().querySelector(".$AGREEMENT_BODY_CLASS"))
            assertTrue(js("typeof window.__agreementXss === 'undefined'").unsafeCast<Boolean>())
        }
    }

    @Test
    fun body_aMarkdownText_isTheServersHtml_inTheOneBodyElement() {
        host("agreement-body-markdown") { root, element ->
            root.renderAgreementBody(
                text = "# Quelltext **nicht anzeigen**",
                format = AgreementTextFormat.MARKDOWN,
                renderedHtml = serverHtml,
            )
            val el = element()
            val body = assertNotNull(el.querySelector(".$AGREEMENT_BODY_CLASS"))
            assertEquals(1, el.querySelectorAll(".$AGREEMENT_BODY_CLASS").length)
            assertEquals("Vertrag", body.querySelector("h2")?.textContent)
            assertEquals("fetter", body.querySelector("p > strong")?.textContent)
            assertEquals(1, body.querySelectorAll("ul > li").length)
            assertFalse(el.textContent.orEmpty().contains("Quelltext"), "the source is not shown next to the html")
        }
    }

    @Test
    fun body_aMarkdownTextWithoutUsableHtml_fallsBackToTheSourceAsText() {
        host("agreement-body-markdown-no-html") { root, element ->
            root.renderAgreementBody(text = "Quelltext **roh**", format = AgreementTextFormat.MARKDOWN, renderedHtml = null)
            assertNull(element().querySelector(".$AGREEMENT_BODY_CLASS"))
            assertTrue(element().textContent.orEmpty().contains("Quelltext **roh**"), "readable, not wrong")
        }
        host("agreement-body-markdown-bad-html") { root, element ->
            // html that does not start with a tag (e.g. a forged i18n marker) is never used
            root.renderAgreementBody(text = "Quelltext", format = AgreementTextFormat.MARKDOWN, renderedHtml = "###KvI18nS###Version")
            assertNull(element().querySelector(".$AGREEMENT_BODY_CLASS"))
            assertFalse(element().textContent.orEmpty().contains("###KvI18n"))
            assertTrue(element().textContent.orEmpty().contains("Quelltext"))
        }
    }

    @Test
    fun frame_keepsItsRegionAttributes_forMarkdown() {
        host("agreement-frame-markdown") { root, element ->
            root.agreementFrame(text = "x", ariaLabel = "Vertrag", format = AgreementTextFormat.MARKDOWN, renderedHtml = serverHtml)
            val frame = assertNotNull(element().querySelector(".$AGREEMENT_FRAME_CLASS"))
            assertEquals("region", frame.getAttribute("role"))
            assertEquals("0", frame.getAttribute("tabindex"))
            assertEquals("Vertrag", frame.getAttribute("aria-label"))
            assertNotNull(frame.querySelector(".$AGREEMENT_BODY_CLASS h2"))
        }
    }
}
