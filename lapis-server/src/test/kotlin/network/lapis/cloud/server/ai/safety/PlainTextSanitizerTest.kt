package network.lapis.cloud.server.ai.safety

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class PlainTextSanitizerTest :
    FunSpec({
        fun clean(
            text: String,
            max: Int = 1_000,
        ) = PlainTextSanitizer.sanitize(text = text, maxChars = max)

        test("HTML tags are removed, so no markup structure survives") {
            val result = clean("Hallo <script>alert(1)</script> Welt <b>fett</b> <img src=x onerror=alert(1)>")
            result shouldNotContain "<script"
            result shouldNotContain "</script"
            result shouldNotContain "<b>"
            result shouldNotContain "<img"
        }

        test("control characters are removed but line breaks are kept") {
            clean("a\u0000b\u0007c\nd") shouldBe "abc\nd"
        }

        test("bidi override and zero-width characters are removed") {
            clean("a‮b​c﻿d") shouldBe "abcd"
        }

        test("line breaks are normalized and blank runs collapsed") {
            clean("a\r\nb\r\rc\n\n\n\n\nd") shouldBe "a\nb\n\nc\n\nd"
        }

        test("the length is capped") {
            val result = clean("x".repeat(500), max = 100)
            (result.length <= 101) shouldBe true
            result.endsWith("…") shouldBe true
        }

        test("a lone less-than in ordinary text survives") {
            clean("Beitrag < 10 Euro") shouldBe "Beitrag < 10 Euro"
        }
    })
