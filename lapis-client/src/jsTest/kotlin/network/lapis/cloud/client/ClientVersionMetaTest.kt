package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLMetaElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val META_NAME = "lapis-client-build"

private fun removeMeta() {
    while (true) {
        val existing = document.querySelector("meta[name=\"$META_NAME\"]") ?: break
        existing.remove()
    }
}

private fun setMeta(content: String) {
    removeMeta()
    val meta = document.createElement("meta") as HTMLMetaElement
    meta.name = META_NAME
    meta.content = content
    document.head?.appendChild(meta)
}

/** V1.4.20 -- [readOwnBuildId] against a real DOM (Karma+ChromeHeadless), same shape as [BrandingTest]. */
class ClientVersionMetaTest {
    @AfterTest
    fun cleanup() {
        removeMeta()
    }

    @Test
    fun missingMeta_isNull() {
        removeMeta()
        assertNull(readOwnBuildId())
    }

    @Test
    fun devSentinel_isNull() {
        setMeta("dev")
        assertNull(readOwnBuildId())
    }

    @Test
    fun blankContent_isNull() {
        setMeta("")
        assertNull(readOwnBuildId())
    }

    @Test
    fun wellFormedContent_isReturned() {
        setMeta("0123456789abcdef")
        assertEquals("0123456789abcdef", readOwnBuildId())
    }

    @Test
    fun implausibleContent_isNull() {
        setMeta("<script>alert(1)</script>")
        assertNull(readOwnBuildId())
    }
}
