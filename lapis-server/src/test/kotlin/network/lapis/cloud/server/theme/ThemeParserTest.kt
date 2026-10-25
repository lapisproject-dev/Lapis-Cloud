package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class ThemeParserTest :
    FunSpec({
        fun codes(json: String) = ThemeParser.parse(json).findings.map { it.code }

        test("the four presets parse without findings that reject them") {
            listOf("lapis-default", "neutral", "high-contrast", "party-yellow-blue").forEach { preset ->
                val outcome = ThemeParser.parse(repoFile("deploy/example/themes/$preset/theme.json").readText())
                outcome.spec shouldNotBe null
                outcome.findings shouldBe emptyList()
            }
        }
        test("unknown keys at top level, in colors.light and in a font file") {
            codes(themeJson { it["extra"] = JsonPrimitive(1) }) shouldContain ThemeErrorCode.THEME_UNKNOWN_KEY
            codes(themeJsonWithColor(mode = "light", key = "accent-x", value = "#112233")) shouldContain ThemeErrorCode.THEME_UNKNOWN_KEY
            val fontJson =
                themeJson {
                    val body = fontsJson(Triple("A.woff2", 400, "normal")).getValue("body").jsonObject
                    val file =
                        (body.getValue("files") as JsonArray)[0].jsonObject.toMutableMap().also { m ->
                            m["bogus"] = JsonPrimitive(1)
                        }
                    val role = body.toMutableMap().also { m -> m["files"] = JsonArray(listOf(JsonObject(file))) }
                    it["fonts"] = buildJsonObject { put("body", JsonObject(role)) }
                }
            val outcome = ThemeParser.parse(fontJson)
            outcome.findings.single().code shouldBe ThemeErrorCode.THEME_UNKNOWN_KEY
            outcome.findings.single().path shouldBe "$.fonts.body.files[0].bogus"
            outcome.findings.single().action shouldBe ThemeAction.FONTS_DROPPED
            outcome.spec shouldNotBe null
        }
        test("a missing colour token") {
            val outcome = ThemeParser.parse(themeJsonWithColor(mode = "dark", key = "accent", value = null))
            outcome.spec shouldBe null
            outcome.findings.single().code shouldBe ThemeErrorCode.THEME_MISSING_KEY
            outcome.findings.single().path shouldBe "$.colors.dark.accent"
        }
        test("bad versions: 2, \"1\" and 1.0") {
            listOf("2", "\"1\"", "1.0").forEach { v ->
                val json = themeJson { }.replace("\"version\":1", "\"version\":$v")
                codes(json) shouldContain ThemeErrorCode.THEME_BAD_VERSION
            }
        }
        test("bad name and bad description") {
            codes(themeJson { it["name"] = JsonPrimitive("a/b") }) shouldContain ThemeErrorCode.THEME_BAD_NAME
            codes(themeJson { it["name"] = JsonPrimitive("x".repeat(65)) }) shouldContain ThemeErrorCode.THEME_BAD_NAME
            codes(themeJson { it["description"] = JsonPrimitive("x".repeat(201)) }) shouldContain ThemeErrorCode.THEME_BAD_DESCRIPTION
            codes(themeJson { it["description"] = JsonPrimitive("a\nb") }) shouldContain ThemeErrorCode.THEME_BAD_DESCRIPTION
        }
        test("bad hex values, including CSS injection attempts") {
            listOf("#12", "red", "#GGGGGG", "rgb(0,0,0)", "#123;}", "#123456 ", "#12345").forEach { bad ->
                ThemeParser.parse(themeJsonWithColor(mode = "light", key = "bg", value = bad)).findings.map { it.code } shouldContain
                    ThemeErrorCode.THEME_BAD_HEX
            }
        }
        test("wrong types and a bad radius") {
            codes(themeJson { it["colors"] = JsonPrimitive("x") }) shouldContain ThemeErrorCode.THEME_TYPE
            codes(themeJson { it["radius"] = JsonPrimitive("huge") }) shouldContain ThemeErrorCode.THEME_BAD_ENUM
            codes(themeJson { it["radius"] = JsonPrimitive(3) }) shouldContain ThemeErrorCode.THEME_TYPE
        }
        test("hex normalisation") {
            HexColor.parse("#abc")!!.css shouldBe "#AABBCC"
            HexColor.parse("#aabbcc80")!!.css shouldBe "#AABBCC80"
        }
        test("font structure errors drop the fonts but keep the colours") {
            val badFamily = themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal"), family = "In'ter;}") }
            val outcome = ThemeParser.parse(badFamily)
            outcome.spec shouldNotBe null
            outcome.spec!!.body shouldBe null
            outcome.findings.map { it.code } shouldBe listOf(ThemeErrorCode.THEME_FONT_FAMILY)
            val badWeight = ThemeParser.parse(themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 450, "normal")) })
            badWeight.spec!!.body shouldBe null
            ThemeParser.parse(themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 400, "oblique")) }).spec!!.body shouldBe null
        }
        test("font file names that are not plain names are rejected") {
            listOf("../x.woff2", "/abs.woff2", "a/b.woff2", "a\\b.woff2", "x.ttf", "..woff2", "a..b.woff2").forEach { name ->
                val outcome = ThemeParser.parse(themeJson { it["fonts"] = fontsJson(Triple(name, 400, "normal")) })
                outcome.findings.map { it.code } shouldContain ThemeErrorCode.THEME_FONT_PATH
                outcome.spec!!.body shouldBe null
            }
        }
        test("font count limits and conflicts") {
            val five = (1..5).map { Triple("F$it.woff2", 400, "normal") }.toTypedArray()
            ThemeParser.parse(themeJson { it["fonts"] = fontsJson(*five) }).findings.map { it.code } shouldContain
                ThemeErrorCode.THEME_FONT_COUNT
            val conflict =
                ThemeParser.parse(
                    themeJson {
                        it["fonts"] =
                            fontsJson(Triple("A.woff2", 400, "normal"), Triple("B.woff2", 400, "normal"))
                    },
                )
            conflict.findings.map { it.code } shouldContain ThemeErrorCode.THEME_FONT_CONFLICT
            conflict.spec!!.body shouldBe null
        }
        test("the same file in both roles is allowed and counted once") {
            val json =
                themeJson {
                    val body = fontsJson(Triple("A.woff2", 400, "normal")).getValue("body")
                    it["fonts"] =
                        buildJsonObject {
                            put("body", body)
                            put("display", body)
                        }
                }
            val outcome = ThemeParser.parse(json)
            outcome.findings shouldHaveSize 0
            outcome.spec!!.display shouldNotBe null
        }
        test("malformed JSON rejects the theme") {
            ThemeParser.parse("{").spec shouldBe null
            ThemeParser
                .parse("[]")
                .findings
                .single()
                .code shouldBe ThemeErrorCode.THEME_TYPE
        }
    })
