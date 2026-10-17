package network.lapis.cloud.server.assets

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.security.MessageDigest

/**
 * V1.9.152 (Begegnungsraum Stufe 2c) -- guard for the ten drawings of the fixed room templates in `lapis-client/src/jsMain/webAssets/encounter-objects-v1`.
 * Lives in `lapis-server` because `jsTest` has no file system (same reason as [VideoBackgroundAssetsTest]).
 *
 * The contract (see `PROVENANCE.adoc`): vector only with a `viewBox`, no `<text>`, `<script>`, `<foreignObject>`, gradient, raster image or external reference,
 * at most 8 KB, the three block tiles are 160 x 48, every file has a provenance entry and a SHA-256 in `lapis-client/build.gradle.kts` that matches the file.
 */
class EncounterObjectAssetsTest :
    FunSpec({
        val clientDir: File = File("../lapis-client").let { if (it.exists()) it else File("lapis-client") }
        val dir = File(clientDir, "src/jsMain/webAssets/encounter-objects-v1")
        val provenance = File(dir, "PROVENANCE.adoc")
        val gradleFile = File(clientDir, "build.gradle.kts")

        val blockTiles = listOf("block-pew", "block-chair", "block-stool")
        val names = listOf("altar", "podium", "plant", "counter", "whiteboard", "table-round", "table-long") + blockTiles

        fun svg(name: String) = File(dir, "$name.svg")

        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        test("exactly the ten expected drawings exist (no file without an entry and vice versa)") {
            names.forEach { svg(it).exists() shouldBe true }
            dir.listFiles { f -> f.isFile && f.name.endsWith(".svg") }!!.map { it.name.removeSuffix(".svg") } shouldContainExactlyInAnyOrder
                names
        }

        test("every drawing is at most 8 KB") {
            names.forEach { (svg(it).length() <= 8 * 1024) shouldBe true }
        }

        test("every drawing is vector only: a viewBox, and no text, script, foreignObject, gradient, raster image or external reference") {
            val forbidden =
                listOf(
                    "<text",
                    "<script",
                    "<foreignobject",
                    "lineargradient",
                    "radialgradient",
                    "<image",
                    "<use",
                    "href=",
                    "url(",
                    "http://",
                    "https://",
                )
            names.forEach { name ->
                val text = svg(name).readText()
                text shouldContain "viewBox=\""
                val lower = text.lowercase()
                // the one allowed URL is the SVG namespace declaration
                val withoutNamespace = lower.replace("xmlns=\"http://www.w3.org/2000/svg\"", "")
                forbidden.forEach { word -> failIfFound(name = name, word = word, found = withoutNamespace.contains(word)) }
            }
        }

        test("the three block tiles are 160 x 48, exactly one block of three seats") {
            blockTiles.forEach { svg(it).readText() shouldContain "viewBox=\"0 0 160 48\"" }
        }

        test("the chairs and stools are centred on the seats of a block (x 24, 80, 136 and y 24)") {
            val chairs = Regex("<rect x=\"(\\d+)\" y=\"(\\d+)\" width=\"36\" height=\"36\"").findAll(svg("block-chair").readText()).toList()
            chairs.map { it.groupValues[1].toInt() + 18 } shouldBe listOf(24, 80, 136)
            chairs.map { it.groupValues[2].toInt() + 18 }.distinct() shouldBe listOf(24)
            val stools = Regex("<circle cx=\"(\\d+)\" cy=\"(\\d+)\" r=\"17\"").findAll(svg("block-stool").readText()).toList()
            stools.map { it.groupValues[1].toInt() } shouldBe listOf(24, 80, 136)
            stools.map { it.groupValues[2].toInt() }.distinct() shouldBe listOf(24)
        }

        test("the pew covers the whole block (x 2 to 158)") {
            svg("block-pew").readText() shouldContain "<rect x=\"2\" y=\"4\" width=\"156\" height=\"40\""
        }

        test("every drawing has its SHA-256 in the Gradle task and it matches the file") {
            val gradle = gradleFile.readText()
            names.forEach { name ->
                gradle shouldContain "\"encounter-objects-v1/$name.svg\" to \"${sha256(svg(name))}\""
            }
        }

        test("every drawing is named in the provenance note, which also states the licence") {
            val text = provenance.readText()
            names.forEach { text shouldContain "`$it.svg`" }
            text shouldContain "original drawings made for Lapis Cloud"
            text shouldContain "licence of this repository"
        }
    })

private fun failIfFound(
    name: String,
    word: String,
    found: Boolean,
) {
    if (found) throw AssertionError("$name.svg contains the forbidden token \"$word\"")
}
