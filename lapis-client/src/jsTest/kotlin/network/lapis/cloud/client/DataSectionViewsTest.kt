package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.P
import io.kvision.panel.SimplePanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Audit V1.4.25 M3 (guideline 2.12): the loading text lives in a `role="status"` region that is mounted ONCE
 * and stays in the document; a live region that is created together with its text is not announced.
 * In-memory widget tree only (no DOM mounting), like [DataScreenLayoutTest].
 */
class DataSectionViewsTest {
    private fun views() = SimplePanel().dataSectionViews()

    private fun <T> DataSectionViews.showState(
        state: DataViewState<T>,
        render: (SimplePanel, T) -> Unit = { _, _ -> },
    ) = show(state = state, emptyText = "leer", noMatchText = { "nichts zu $it" }, onRetry = {}, render = render)

    @Test
    fun statusRegion_isMountedFirst_asAPoliteLiveRegion_beforeAnyLoad() {
        val views = views()
        assertSame(views.status, views.host.getChildren().first())
        assertEquals("status", views.status.getAttribute("role"))
        assertEquals("polite", views.status.getAttribute("aria-live"))
    }

    @Test
    fun loading_setsOnlyTheText_andTheRegionElementSurvivesEveryStateChange() {
        val views = views()
        val region: Div = views.status
        views.showState(DataViewState.Loading)
        assertTrue(region.content.orEmpty().isNotBlank(), "the loading text is set on the mounted region")
        assertTrue(views.body.getChildren().isEmpty(), "nothing but the status text while loading")

        views.showState(DataViewState.Content(listOf(1)), render = { body, _ -> body.add(P("row")) })
        assertSame(region, views.host.getChildren().first(), "the very same region element is still mounted")
        assertEquals("status", region.getAttribute("role"))
        assertEquals("", region.content.orEmpty(), "the text is gone, the element is not")
        assertEquals(1, views.body.getChildren().size)

        views.showState(DataViewState.Loading)
        assertSame(region, views.host.getChildren().first())
        assertTrue(region.content.orEmpty().isNotBlank())
        assertTrue(views.body.getChildren().isEmpty(), "the previous content is cleared while loading")
    }

    @Test
    fun errorEmptyAndNoMatch_clearTheStatusText_andShowExactlyOneBodyElement() {
        val views = views()
        views.showState(DataViewState.Loading)
        views.showState(DataViewState.Error)
        assertEquals("", views.status.content.orEmpty())
        val alert = views.body.getChildren().single() as Div
        assertEquals("alert", alert.getAttribute("role"), "the error state stays a role=alert box")

        views.showState(DataViewState.Empty)
        assertEquals("leer", (views.body.getChildren().single() as P).content)
        views.showState(DataViewState.NoMatch("abc"))
        assertEquals("nichts zu abc", (views.body.getChildren().single() as P).content)
        assertNotNull(views.status.getAttribute("role"))
    }
}
