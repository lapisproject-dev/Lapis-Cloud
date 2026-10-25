package network.lapis.cloud.server.theme

import java.util.Locale
import kotlin.math.pow

/** WCAG 2.x contrast maths and the per-mode pair check of [ThemeTokens.CONTRAST_PAIRS]. */
object ThemeContrast {
    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun relativeLuminance(c: HexColor): Double = 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)

    /** Alpha-composites [fg] over the (treated as opaque) [bg]. */
    fun composite(
        fg: HexColor,
        bg: HexColor,
    ): HexColor {
        val a = fg.alpha
        if (a >= 1.0) return fg

        fun mix(
            f: Int,
            b: Int,
        ) = Math.round(f * a + b * (1 - a)).toInt()
        return HexColor.rgb(r = mix(fg.r, bg.r), g = mix(fg.g, bg.g), b = mix(fg.b, bg.b))
    }

    fun ratio(
        a: HexColor,
        b: HexColor,
    ): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Checks all pairs of one mode ("light" or "dark"); the colours must contain every token. */
    fun check(
        mode: String,
        colors: Map<String, HexColor>,
    ): List<ThemeFinding> {
        val base = if (mode == "dark") HexColor.rgb(r = 0, g = 0, b = 0) else HexColor.rgb(r = 255, g = 255, b = 255)
        val opaque = HashMap<String, HexColor>()

        fun resolved(key: String): HexColor? {
            opaque[key]?.let { return it }
            val raw = colors[key] ?: return null
            val under =
                when (key) {
                    "bg" -> base
                    "surface" -> resolved("bg") ?: base
                    "nav-hover-bg" -> resolved("nav") ?: resolved("surface") ?: base
                    else -> resolved("surface") ?: base
                }
            return composite(fg = raw, bg = under).also { opaque[key] = it }
        }

        val findings = mutableListOf<ThemeFinding>()
        for (pair in ThemeTokens.CONTRAST_PAIRS) {
            val bg = resolved(pair.bg) ?: continue
            val fgRaw = colors[pair.fg] ?: continue
            val fg = composite(fg = fgRaw, bg = bg)
            val ratio = ratio(a = fg, b = bg)
            if (ratio + 1e-9 < pair.min) {
                val text = String.format(Locale.ROOT, "%.2f", ratio)
                val min = String.format(Locale.ROOT, "%.1f", pair.min)
                findings +=
                    if (pair.blocking) {
                        ThemeFinding(
                            code = ThemeErrorCode.THEME_CONTRAST,
                            path = "$.colors.$mode.${pair.fg}",
                            message = "${pair.fg} on ${pair.bg} has contrast $text:1, needs at least $min:1",
                            severity = Severity.ERROR,
                            action = ThemeAction.DEFAULT,
                        )
                    } else {
                        ThemeFinding(
                            code = ThemeErrorCode.THEME_CONTRAST_ADVISORY,
                            path = "$.colors.$mode.${pair.fg}",
                            message = "${pair.fg} on ${pair.bg} has contrast $text:1, recommended $min:1",
                            severity = Severity.WARNING,
                            action = ThemeAction.NONE,
                        )
                    }
            }
        }
        return findings
    }
}
