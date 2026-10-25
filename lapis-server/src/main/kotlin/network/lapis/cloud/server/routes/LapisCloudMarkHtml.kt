package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.HTMLTag
import kotlinx.html.Tag
import kotlinx.html.visit
import network.lapis.cloud.shared.branding.LapisCloudMark

/**
 * V1.9.214 -- the Lapis Cloud brand mark as inline SVG for the server-rendered public chrome (dark variant only: the
 * chrome bar is fixed `#14181E`). Geometry and colours come from [LapisCloudMark], so the client mark and this one cannot
 * drift. Built with the kotlinx.html tag API (attributes are escaped; no raw-HTML escape hatch, which the public pages
 * must not use). Colours are presentation attributes (`fill`/`stroke`), never `style=`, which keeps the page inside
 * `style-src 'self'`. Purely decorative (`aria-hidden`); the link's accessible name is the wordmark next to it. Static,
 * no user input.
 */
internal object LapisCloudMarkHtml {
    private const val SIZE_PX = "22"

    fun FlowContent.lapisCloudMark() {
        val palette = LapisCloudMark.DarkPalette
        node(
            name = "svg",
            attrs =
                mapOf(
                    "width" to SIZE_PX,
                    "height" to SIZE_PX,
                    "viewBox" to LapisCloudMark.VIEW_BOX,
                    "aria-hidden" to "true",
                    "focusable" to "false",
                ),
        ) {
            node(
                name = "path",
                attrs =
                    mapOf(
                        "d" to LapisCloudMark.CLOUD_D,
                        "fill" to palette.CLOUD_FILL,
                        "stroke" to palette.CLOUD_STROKE,
                        "stroke-width" to LapisCloudMark.STROKE_CLOUD,
                        "stroke-linejoin" to "round",
                    ),
            )
            node(
                name = "g",
                attrs =
                    mapOf(
                        "stroke" to palette.EDGE,
                        "stroke-width" to LapisCloudMark.STROKE_EDGE,
                        "stroke-linejoin" to "round",
                    ),
            ) {
                facet(d = LapisCloudMark.F1_D, fill = palette.F1)
                facet(d = LapisCloudMark.F2_D, fill = palette.F2)
                facet(d = LapisCloudMark.F3_D, fill = palette.F3)
                facet(d = LapisCloudMark.F4_D, fill = palette.F4)
                facet(d = LapisCloudMark.F5_D, fill = palette.F5)
                facet(d = LapisCloudMark.F6_D, fill = palette.F6)
            }
            node(name = "path", attrs = mapOf("d" to LapisCloudMark.SPARK_D, "fill" to palette.SPARK))
        }
    }

    private fun Tag.facet(
        d: String,
        fill: String,
    ) {
        node(name = "path", attrs = mapOf("d" to d, "fill" to fill))
    }

    private fun Tag.node(
        name: String,
        attrs: Map<String, String>,
        children: HTMLTag.() -> Unit = {},
    ) {
        HTMLTag(
            tagName = name,
            consumer = consumer,
            initialAttributes = attrs,
            namespace = null,
            inlineTag = true,
            emptyTag = false,
        ).visit(children)
    }
}
