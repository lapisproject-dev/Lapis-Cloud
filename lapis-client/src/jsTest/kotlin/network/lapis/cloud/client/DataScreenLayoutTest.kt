package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.21 -- pins the `aria-label` marker strip in `tableActionTooltip`/`segmentedControl`
 * (`###KvI18nS###` must never reach a raw DOM attribute) and the segmented control's pressed state.
 * In-memory widget tree only, no DOM mounting -- same posture as [SidebarStructureTest].
 */
class DataScreenLayoutTest {
    private val kvI18nMarker = "###KvI18nS###"

    @Test
    fun resolvedAttributeText_stripsTheMarker_andIsANoOpOtherwise() {
        assertEquals("Details anzeigen", resolvedAttributeText("${kvI18nMarker}Details anzeigen"))
        assertEquals("Details anzeigen", resolvedAttributeText("Details anzeigen"))
    }

    @Test
    fun resolvedAttributeText_translatesNotJustStrips() {
        // Audit V1.4.25 M2: `removePrefix(marker)` left the untranslated German key in the attribute.
        withTranslations(mapOf("Details anzeigen" to "Show details")) {
            assertEquals("Show details", resolvedAttributeText(tr("Details anzeigen")))
            assertEquals("Show details", resolvedAttributeText("${kvI18nMarker}Details anzeigen"))
            // an already resolved string stays as it is
            assertEquals("Details anzeigen", resolvedAttributeText("Details anzeigen"))
        }
    }

    @Test
    fun tableActionButton_withATrTooltip_translatesTheAriaLabel() {
        withTranslations(mapOf("Details anzeigen" to "Show details")) {
            val button = SimplePanel().tableActionButton("fas fa-eye", tr("Details anzeigen"))
            assertEquals("Show details", button.getAttribute("aria-label"))
            button.tableActionTooltip(tr("Gesperrt"))
            assertEquals("Gesperrt", button.getAttribute("aria-label")) // not in the catalog: falls back to the key
        }
    }

    @Test
    fun segmentedControl_ariaLabelOfTheGroupIsTranslated() {
        withTranslations(mapOf("Richtung" to "Direction")) {
            val group = SimplePanel().segmentedControl(options = listOf(1 to "A"), selected = 1, ariaLabel = tr("Richtung")) {}
            assertEquals("Direction", group.getAttribute("aria-label"))
        }
    }

    @Test
    fun tableActionButton_withATrTooltip_doesNotLeakTheMarkerIntoAriaLabel() {
        val button = SimplePanel().tableActionButton("fas fa-eye", tr("Details anzeigen"))
        assertEquals("Details anzeigen", button.getAttribute("aria-label"))
    }

    @Test
    fun tableActionTooltip_overwriteKeepsAriaLabelInSync() {
        val button = SimplePanel().tableActionButton("fas fa-eye", tr("Details anzeigen"))
        button.tableActionTooltip(tr("Gesperrt"))
        assertEquals("Gesperrt", button.getAttribute("aria-label"))
    }

    @Test
    fun segmentedControl_marksExactlyTheSelectedOptionAsPressed() {
        val group =
            SimplePanel().segmentedControl(
                options = listOf(1 to "Alle", 2 to "Kreditoren", 3 to "Debitoren"),
                selected = 2,
                ariaLabel = tr("Richtung"),
            ) {}
        val buttons = group.getChildren().filterIsInstance<Button>()
        assertEquals(listOf("false", "true", "false"), buttons.map { it.getAttribute("aria-pressed") })
        assertEquals("Richtung", group.getAttribute("aria-label"))
        assertEquals("group", group.getAttribute("role"))
    }
}
