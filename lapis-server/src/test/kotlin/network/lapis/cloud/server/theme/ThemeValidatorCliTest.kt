package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ThemeValidatorCliTest :
    FunSpec({
        fun run(vararg args: String): Pair<Int, String> {
            val out = StringBuilder()
            val code = ThemeValidatorCli.run(args = arrayOf(*args), out = out)
            return code to out.toString()
        }

        test("exit 0 and a parseable report for a valid theme") {
            val (code, text) = run(themeDir(json = DEFAULT_THEME_JSON).toString())
            code shouldBe 0
            val json = Json.parseToJsonElement(text).jsonObject
            json.getValue("valid").jsonPrimitive.boolean shouldBe true
            json
                .getValue("hash")
                .jsonPrimitive.content.length shouldBe 12
            json.getValue("errors").jsonArray.size shouldBe 0
        }
        test("exit 1 with code, path and message for an invalid theme") {
            val (code, text) = run(themeDir(json = themeJsonWithColor(mode = "light", key = "bg", value = "red")).toString())
            code shouldBe 1
            val err =
                Json
                    .parseToJsonElement(text)
                    .jsonObject
                    .getValue("errors")
                    .jsonArray
                    .single()
                    .jsonObject
            err.getValue("code").jsonPrimitive.content shouldBe "THEME_BAD_HEX"
            err.getValue("path").jsonPrimitive.content shouldBe "$.colors.light.bg"
            err
                .getValue("message")
                .jsonPrimitive.content
                .isNotEmpty() shouldBe true
        }
        test("font and licence errors are errors (exit 1)") {
            val json = themeJson { it["fonts"] = fontsJson(Triple("A.woff2", 400, "normal")) }
            run(themeDir(json = json, fonts = mapOf("A.woff2" to fakeWoff2()), license = null).toString()).first shouldBe 1
        }
        test("exit 2 for missing arguments, too many arguments and an unreadable directory") {
            run().first shouldBe 2
            run("a", "b").first shouldBe 2
            run("/nonexistent/lapis-theme-dir").first shouldBe 2
            Json.parseToJsonElement(run().second)
        }
        test("the report is the only output: nothing but one JSON line") {
            val (_, text) = run(themeDir(json = DEFAULT_THEME_JSON).toString())
            text.trim().lines().size shouldBe 1
        }
    })
