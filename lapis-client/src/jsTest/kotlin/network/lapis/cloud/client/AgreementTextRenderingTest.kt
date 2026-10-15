package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
