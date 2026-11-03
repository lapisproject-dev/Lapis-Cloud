package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.branding.LapisCloudMark
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.9.265 golden snapshots, captured against the UNCHANGED V1.9.264 code BEFORE the logo-variant work: instances that
 * configure only the legacy `LAPIS_BRAND_LOGO_PATH` (or nothing) must render byte-for-byte what they rendered before.
 */
class BrandGoldenSnapshotTest {
    @AfterTest
    fun cleanup() {
        document.getElementById("lapis-brand")?.remove()
    }

    private fun setBrand(json: String) {
        document.getElementById("lapis-brand")?.remove()
        val script = document.createElement("script") as HTMLScriptElement
        script.type = "application/json"
        script.id = "lapis-brand"
        script.textContent = json
        document.head?.appendChild(script)
    }

    @Test
    fun legacyLogo_lockupInnerHtml_isUnchanged() {
        setBrand("""{"title":"X","logoUrl":"/api/branding/logo","keycloakMode":false,"emergencyAdminLoginEnabled":false}""")
        withMountedRoot("brand-golden-legacy") { root, element ->
            root.brandLockup()
            val logo = element().querySelector("span.lapis-brand-logo") as HTMLElement
            // KVision wraps the rich content in a display:contents span; captured from V1.9.264.
            assertEquals("""<span style="display: contents;"><img src="/api/branding/logo" alt="X"></span>""", logo.innerHTML)
            assertEquals("lapis-brand-logo", logo.className.trim())
            assertEquals(0, element().querySelectorAll(".lapis-brand-mark").length)
        }
    }

    @Test
    fun noLogo_lockupInnerHtml_isTheDefaultMark() {
        setBrand("""{"title":"X","logoUrl":null,"keycloakMode":false,"emergencyAdminLoginEnabled":false}""")
        withMountedRoot("brand-golden-default") { root, element ->
            root.brandLockup()
            val mark = element().querySelector("span.lapis-brand-mark") as HTMLElement
            val reference = document.createElement("div") as HTMLElement
            reference.innerHTML = GOLDEN_MARK_SVG
            // KVision collapses the template's line breaks; whitespace is normalised on both sides.
            assertEquals("""<span style="display: contents;">${reference.innerHTML}</span>""".squash(), mark.innerHTML.squash())
            assertEquals(0, element().querySelectorAll(".lapis-brand-logo").length)
        }
    }

    @Test
    fun defaultMarkConstant_isByteIdenticalToTheV264Template() {
        assertEquals(GOLDEN_MARK_SVG, LAPIS_CLOUD_MARK_SVG)
    }

    private fun String.squash(): String = replace(Regex("\\s+"), " ")

    private companion object {
        /** Verbatim copy of the V1.9.264 `LAPIS_CLOUD_MARK_SVG` template (with the shared geometry constants interpolated). */
        const val GOLDEN_MARK_SVG = """<svg class="lapis-brand-mark-svg lapis-brand-mark--light" width="32" height="32" viewBox="${LapisCloudMark.VIEW_BOX}" aria-hidden="true" focusable="false">
    <path class="bm-cloud" d="${LapisCloudMark.CLOUD_D}" stroke-width="${LapisCloudMark.STROKE_CLOUD}" stroke-linejoin="round" />
    <g class="bm-edge" stroke-width="${LapisCloudMark.STROKE_EDGE}" stroke-linejoin="round">
        <path class="bm-f1" d="${LapisCloudMark.F1_D}" />
        <path class="bm-f2" d="${LapisCloudMark.F2_D}" />
        <path class="bm-f3" d="${LapisCloudMark.F3_D}" />
        <path class="bm-f4" d="${LapisCloudMark.F4_D}" />
        <path class="bm-f5" d="${LapisCloudMark.F5_D}" />
        <path class="bm-f6" d="${LapisCloudMark.F6_D}" />
    </g>
    <path class="bm-spark" d="${LapisCloudMark.SPARK_D}" />
</svg>
<svg class="lapis-brand-mark-svg lapis-brand-mark--dark" width="32" height="32" viewBox="${LapisCloudMark.VIEW_BOX}" aria-hidden="true" focusable="false">
    <path class="bm-cloud" d="${LapisCloudMark.CLOUD_D}" stroke-width="${LapisCloudMark.STROKE_CLOUD}" stroke-linejoin="round" />
    <g class="bm-edge" stroke-width="${LapisCloudMark.STROKE_EDGE}" stroke-linejoin="round">
        <path class="bm-f1" d="${LapisCloudMark.F1_D}" />
        <path class="bm-f2" d="${LapisCloudMark.F2_D}" />
        <path class="bm-f3" d="${LapisCloudMark.F3_D}" />
        <path class="bm-f4" d="${LapisCloudMark.F4_D}" />
        <path class="bm-f5" d="${LapisCloudMark.F5_D}" />
        <path class="bm-f6" d="${LapisCloudMark.F6_D}" />
    </g>
    <path class="bm-spark" d="${LapisCloudMark.SPARK_D}" />
</svg>"""
    }
}
