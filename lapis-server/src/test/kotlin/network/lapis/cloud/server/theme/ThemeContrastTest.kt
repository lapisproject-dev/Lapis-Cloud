package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class ThemeContrastTest :
    FunSpec({
        fun hex(s: String) = HexColor.parse(s)!!

        fun colors(
            base: Map<String, String>,
            vararg override: Pair<String, String>,
        ): Map<String, HexColor> = (base + override).mapValues { hex(it.value) }

        test("black on white is 21:1") {
            ThemeContrast.ratio(a = hex("#000000"), b = hex("#FFFFFF")) shouldBe (21.0 plusOrMinus 0.001)
        }
        test("#777777 on white is below 4.5") {
            (ThemeContrast.ratio(a = hex("#777777"), b = hex("#FFFFFF")) < 4.5) shouldBe true
            (ThemeContrast.ratio(a = hex("#777777"), b = hex("#FFFFFF")) > 4.4) shouldBe true
        }
        test("alpha is mixed onto the background") {
            val mixed = ThemeContrast.composite(fg = hex("#000000"), bg = hex("#FFFFFF"))
            mixed.css shouldBe "#000000"
            // a half transparent text colour reaches a lower ratio than the opaque one
            val opaque = colors(ThemeDefaults.LIGHT)
            val translucent = colors(ThemeDefaults.LIGHT, "text" to "#14181E40")
            ThemeContrast.check(mode = "light", colors = opaque).none { it.code == ThemeErrorCode.THEME_CONTRAST } shouldBe true
            ThemeContrast.check(mode = "light", colors = translucent).any { it.code == ThemeErrorCode.THEME_CONTRAST } shouldBe true
        }
        test("the default colours pass every blocking pair and only warn about border-strong") {
            listOf("light" to ThemeDefaults.LIGHT, "dark" to ThemeDefaults.DARK).forEach { (mode, map) ->
                val findings = ThemeContrast.check(mode = mode, colors = colors(map))
                findings.none { it.severity == Severity.ERROR } shouldBe true
                findings.single().code shouldBe ThemeErrorCode.THEME_CONTRAST_ADVISORY
                findings.single().action shouldBe ThemeAction.NONE
            }
        }
        test("a failing blocking pair names the path and rejects") {
            val findings = ThemeContrast.check(mode = "dark", colors = colors(ThemeDefaults.DARK, "accent" to "#1B2028"))
            val f = findings.first { it.code == ThemeErrorCode.THEME_CONTRAST }
            f.path shouldBe "$.colors.dark.accent"
            f.action shouldBe ThemeAction.DEFAULT
        }
        test("a dark accent-soft behind body text is rejected") {
            val findings = ThemeContrast.check(mode = "light", colors = colors(ThemeDefaults.LIGHT, "accent-soft" to "#14181E"))
            findings.first { it.code == ThemeErrorCode.THEME_CONTRAST }.path shouldBe "$.colors.light.text"
        }
        test("nav-hover-bg alpha is mixed over nav, not over surface") {
            val light = ThemeDefaults.LIGHT
            val findings =
                ThemeContrast.check(
                    mode = "light",
                    colors =
                        colors(
                            light,
                            "surface" to "#FFFFFF",
                            "nav" to "#000000",
                            "nav-hover-bg" to "#FFFFFF20",
                            "nav-text" to "#FFFFFF",
                        ),
                )
            findings.none { it.code == ThemeErrorCode.THEME_CONTRAST && it.path.endsWith("nav-text") } shouldBe true
        }
    })
