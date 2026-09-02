package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.1a "Öffentliche Website-Integration" -- covers only the pure, DOM-free
 * [buildEmbedSnippet] function factored out of `EmbedIntegrationScreen.kt`: same scope posture as
 * [BackupHttpTest] (no DOM/rendering test harness exists in this module).
 */
class EmbedIntegrationScreenTest {
    @Test
    fun buildEmbedSnippet_containsThePublicBaseUrl() {
        val snippet = buildEmbedSnippet("https://cloud.example.org")
        assertTrue(snippet.contains("https://cloud.example.org/embed/v1/lapis-widgets.js"))
    }

    @Test
    fun buildEmbedSnippet_containsBothNoJsFallbackAnchors() {
        val snippet = buildEmbedSnippet("https://cloud.example.org")
        assertTrue(snippet.contains("data-lapis-widget=\"login\""))
        assertTrue(snippet.contains("<a href=\"https://cloud.example.org/#/login\">"))
        assertTrue(snippet.contains("data-lapis-widget=\"join\""))
        assertTrue(snippet.contains("<a href=\"https://cloud.example.org/#/register\">"))
    }

    @Test
    fun buildEmbedSnippet_isDeterministic() {
        val first = buildEmbedSnippet("https://cloud.example.org")
        val second = buildEmbedSnippet("https://cloud.example.org")
        assertEquals(first, second)
    }

    @Test
    fun buildEmbedSnippet_trimsATrailingSlashOnThePublicBaseUrl() {
        val withSlash = buildEmbedSnippet("https://cloud.example.org/")
        val withoutSlash = buildEmbedSnippet("https://cloud.example.org")
        assertEquals(withoutSlash, withSlash)
    }
}
