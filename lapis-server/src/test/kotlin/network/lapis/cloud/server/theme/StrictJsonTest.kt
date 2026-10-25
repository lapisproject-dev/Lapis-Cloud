package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class StrictJsonTest :
    FunSpec({
        fun err(text: String): StrictJson.Result.Err = StrictJson.parse(text = text).shouldBeInstanceOf<StrictJson.Result.Err>()

        test("valid JSON parses") {
            val ok = StrictJson.parse(text = """{"a":[1,2.5,"x",true,null],"b":{"c":"d"}}""").shouldBeInstanceOf<StrictJson.Result.Ok>()
            (ok.value as JNode.Obj).members.keys.toList() shouldBe listOf("a", "b")
        }
        test("a duplicate key is rejected with its path") {
            val e = err("""{"colors":{"light":{"accent":"#111111","accent":"#222222"}}}""")
            e.code shouldBe ThemeErrorCode.THEME_JSON_MALFORMED
            e.path shouldBe "$.colors.light.accent"
            e.message shouldContain "duplicate key 'accent'"
        }
        test("trailing comma, comment, NaN and trailing content are rejected") {
            err("""{"a":1,}""")
            err("""{"a":[1,]}""")
            err("""{"a":1 /* c */}""")
            err(
                """// c
{"a":1}""",
            )
            err("""{"a":NaN}""")
            err("""{"a":1} x""")
            err("""{"a":1}{"b":2}""")
        }
        test("depth, string length and member limits") {
            err("[".repeat(9) + "]".repeat(9))
            StrictJson.parse(text = "[".repeat(8) + "]".repeat(8)).shouldBeInstanceOf<StrictJson.Result.Ok>()
            err("\"" + "a".repeat(1025) + "\"")
            err("{" + (0..64).joinToString(",") { "\"k$it\":1" } + "}")
        }
        test("number literals keep their text so 1 and 1.0 differ") {
            val one = (StrictJson.parse(text = "1") as StrictJson.Result.Ok).value as JNode.Num
            val onePointZero = (StrictJson.parse(text = "1.0") as StrictJson.Result.Ok).value as JNode.Num
            one.raw shouldBe "1"
            onePointZero.raw shouldBe "1.0"
        }
        test("invalid UTF-8 is rejected, a BOM is stripped") {
            StrictJson.decodeUtf8(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)) shouldBe null
            StrictJson.decodeUtf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x7B, 0x7D)) shouldBe "{}"
        }
        test("control characters in strings are rejected") {
            err("\"a\u0001b\"")
        }
    })
