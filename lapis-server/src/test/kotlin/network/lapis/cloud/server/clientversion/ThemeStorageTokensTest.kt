package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.pow

/**
 * Welle V1.9.273 -- the three fill colours of the storage-space bar (`--lapis-storage-{info,hint,warning}` in `theme.css`) exist in ALL THREE
 * theme blocks, resolve to a colour, and each has at least 3:1 contrast against the track (`--lapis-surface-sunken`, WCAG 1.4.11). Headline and
 * sentence (text and emphasis colours on `--lapis-surface`) are held to 4.5:1. The `.lapis-storage*` rules carry no raw hex value.
 */
class ThemeStorageTokensTest :
    FunSpec({
        val theme =
            File("../lapis-client/src/jsMain/resources/theme.css")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

        /** One map per `--lapis-surface:` declaration (light, system-dark, explicit dark) with every `--lapis-*` declaration of that block. */
        fun blocks(): List<Map<String, String>> {
            val result = mutableListOf<MutableMap<String, String>>()
            var current: MutableMap<String, String>? = null
            theme.readLines().forEach { line ->
                val declaration = Regex("""^\s*(--lapis-[a-z-]+):\s*([^;]+?)\s*;""").find(line) ?: return@forEach
                val name = declaration.groupValues[1]
                val value = declaration.groupValues[2]
                if (name == "--lapis-surface") {
                    current = linkedMapOf<String, String>().also { result += it }
                }
                current?.put(name, value)
            }
            return result
        }

        fun resolve(
            name: String,
            block: Map<String, String>,
        ): String {
            var value = block[name] ?: error("$name is missing")
            repeat(5) {
                val ref = Regex("""^var\((--lapis-[a-z-]+)\)$""").find(value) ?: return value
                value = block[ref.groupValues[1]] ?: error("${ref.groupValues[1]} is missing")
            }
            return value
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

        val tokens = listOf("info", "hint", "warning").map { "--lapis-storage-$it" }

        test("three theme blocks, each carrying all three storage tokens") {
            val all = blocks()
            all.size shouldBe 3
            all.forEach { block -> tokens.forEach { check(block.containsKey(it)) { "$it is missing in a block" } } }
        }

        test("every fill colour has at least 3:1 contrast against the track of its own block") {
            blocks().forEachIndexed { index, block ->
                val track = resolve("--lapis-surface-sunken", block)
                tokens.forEach { token ->
                    val ratio = contrast(resolve(token, block), track)
                    check(ratio >= 3.0) { "block #$index: $token ${resolve(token, block)} on $track is only ${"%.2f".format(ratio)}:1" }
                }
            }
        }

        test("the text colours of the bar have at least 4.5:1 against the surface of their block") {
            blocks().forEachIndexed { index, block ->
                val surface = resolve("--lapis-surface", block)
                listOf("--lapis-text", "--lapis-invalid").forEach { token ->
                    val ratio = contrast(resolve(token, block), surface)
                    check(ratio >= 4.5) { "block #$index: $token on $surface is only ${"%.2f".format(ratio)}:1" }
                }
            }
        }

        test("the .lapis-storage rules carry no raw hex colour") {
            val css = theme.readText()
            val rules = Regex("""(?s)(\.lapis-storage[^{}]*)\{([^}]*)}""").findAll(css).toList()
            check(rules.isNotEmpty()) { "no .lapis-storage rule found" }
            rules.forEach { rule ->
                check(!Regex("""#[0-9A-Fa-f]{3,8}\b""").containsMatchIn(rule.groupValues[2])) { "raw hex in ${rule.groupValues[1].trim()}" }
            }
        }

        test("the contrast helper itself: black on white is 21:1") {
            (contrast("#000000", "#FFFFFF") > 20.99) shouldBe true
        }
    })
