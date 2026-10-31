package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ForeignTextEllipsizeTest :
    FunSpec({
        test("short text is unchanged, long text is cut with an ellipsis") {
            ForeignText.ellipsize(text = "Kurz", maxChars = 60) shouldBe "Kurz"
            ForeignText.ellipsize(text = "a".repeat(60), maxChars = 60) shouldBe "a".repeat(60)
            ForeignText.ellipsize(text = "a".repeat(61), maxChars = 60) shouldBe "a".repeat(60) + "…"
        }

        test("a surrogate pair is never cut in half") {
            val text = "😀".repeat(70)
            val cut = ForeignText.ellipsize(text = text, maxChars = 60)
            cut.codePointCount(0, cut.length) shouldBe 61
            cut.endsWith("…") shouldBe true
            cut.dropLast(1).all { it.isSurrogate() } shouldBe true
        }
    })
