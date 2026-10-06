package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.pow

/**
 * Welle V1.9.59 -- the eight status colours of the member-count chart (`--lapis-chart-*` in `theme.css`) exist in ALL THREE theme blocks
 * (light, system-dark, explicit dark) and each has a contrast of at least 3:1 against that block's own `--lapis-surface`
 * (WCAG 1.4.11, graphical objects). The numbers in the CSS comments are checked here, not trusted.
 */
class ThemeChartTokensTest :
    FunSpec({
        val theme =
            File("../lapis-client/src/jsMain/resources/theme.css")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

        val statuses = listOf("active", "application", "friend", "donor", "guest", "withdrawn", "rejected", "deceased")

        data class Block(
            val surface: String,
            val chart: Map<String, String>,
        )

        /** One block per `--lapis-surface:` declaration, in file order: light, system-dark, explicit dark. */
        fun blocks(): List<Block> {
            val result = mutableListOf<Block>()
            var surface: String? = null
            var chart = linkedMapOf<String, String>()

            fun flush() {
                surface?.let { result += Block(it, chart) }
            }
            theme.readLines().forEach { line ->
                val declaration = Regex("""^\s*(--lapis-[a-z-]+):\s*(#[0-9A-Fa-f]{6})\s*;""").find(line) ?: return@forEach
                val name = declaration.groupValues[1]
                val value = declaration.groupValues[2]
                if (name == "--lapis-surface") {
                    flush()
                    surface = value
                    chart = linkedMapOf()
                } else if (name.startsWith("--lapis-chart-")) {
                    chart[name.removePrefix("--lapis-chart-")] = value
                }
            }
            flush()
            return result
        }

        fun luminance(hex: String): Double {
            fun channel(offset: Int): Double {
                val c = hex.substring(offset, offset + 2).toInt(16) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)
        }

        fun contrast(
            a: String,
            b: String,
        ): Double {
            val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la >= lb) la to lb else lb to la } }
            return (hi + 0.05) / (lo + 0.05)
        }

        test("three theme blocks, each carrying all eight status tokens") {
            val all = blocks()
            all.size shouldBe 3
            all.forEach { it.chart.keys shouldContainExactlyInAnyOrder statuses }
        }

        test("every status colour has at least 3:1 contrast against the surface of its own block") {
            blocks().forEachIndexed { index, block ->
                block.chart.forEach { (status, color) ->
                    val ratio = contrast(color, block.surface)
                    check(
                        ratio >= 3.0,
                    ) { "block #$index: --lapis-chart-$status $color on ${block.surface} is only ${"%.2f".format(ratio)}:1" }
                }
            }
        }

        test("the colours of one block are pairwise different (no two statuses look the same)") {
            blocks().forEach { block ->
                block.chart.values
                    .toSet()
                    .size shouldBe statuses.size
            }
        }

        test("the contrast helper itself: black on white is 21:1, a colour on itself 1:1") {
            (contrast("#000000", "#FFFFFF") > 20.99) shouldBe true
            contrast("#1E56C8", "#1E56C8") shouldBe 1.0
        }
    })
