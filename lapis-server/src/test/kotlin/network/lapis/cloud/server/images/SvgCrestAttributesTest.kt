package network.lapis.cloud.server.images

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis

private fun attrs() = SvgCrestAttributes()

private fun SvgCrestAttributes.ok(
    grammar: SvgValueGrammar,
    value: String,
    fromStyle: Boolean = false,
) = validate(grammar = grammar, value = value, fromStyle = fromStyle)

private fun SvgCrestAttributes.rejects(
    grammar: SvgValueGrammar,
    value: String,
    fromStyle: Boolean = false,
): SvgRejection? =
    try {
        validate(grammar = grammar, value = value, fromStyle = fromStyle)
        null
    } catch (e: SvgRejectedException) {
        e.reason
    }

class SvgCrestAttributesTest :
    FunSpec({
        test("paint: keywords, hex, rgb and fragment urls are normalized; everything else is refused") {
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "  NONE ") shouldBe "none"
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "Red") shouldBe "red"
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "#AbC") shouldBe "#AbC"
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "#11223344") shouldBe "#11223344"
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "rgb(255, 10%, 0)") shouldBe "rgb(255, 10%, 0)"
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "url( '#grad' )") shouldBe "url(#grad)"
            attrs().ok(grammar = SvgValueGrammar.Paint, value = "url(\"#grad\")") shouldBe "url(#grad)"
            for (bad in listOf("#12", "#ggg", "rgb(1,2)", "url(#a) red", "notacolor", "red;", "var(--x)")) {
                attrs().rejects(grammar = SvgValueGrammar.Paint, value = bad) shouldBe SvgRejection.UNSUPPORTED_CONTENT
            }
            attrs().rejects(grammar = SvgValueGrammar.Color, value = "url(#a)") shouldBe SvgRejection.UNSUPPORTED_CONTENT
        }

        test("numbers: bounded, finite, exponents limited") {
            attrs().ok(grammar = SvgValueGrammar.Number, value = "-3.5e2") shouldBe "-3.5e2"
            attrs().rejects(grammar = SvgValueGrammar.Number, value = "1e7") shouldBe SvgRejection.TOO_COMPLEX
            attrs().rejects(grammar = SvgValueGrammar.Number, value = "1e99999") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.Number, value = "NaN") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.Number, value = "Infinity") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.Number, value = "1e-400") shouldBe "1e-400"
        }

        test("lengths, opacity and offset") {
            attrs().ok(grammar = SvgValueGrammar.Length, value = "12px") shouldBe "12"
            attrs().ok(grammar = SvgValueGrammar.LengthPercent, value = "50%") shouldBe "50%"
            attrs().rejects(grammar = SvgValueGrammar.LengthPercent, value = "50%", fromStyle = true) shouldBe
                SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.Length, value = "2em") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.Opacity, value = "0.5") shouldBe "0.5"
            attrs().ok(grammar = SvgValueGrammar.Opacity, value = "50%") shouldBe "50%"
            attrs().rejects(grammar = SvgValueGrammar.Opacity, value = "1.5") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.Opacity, value = "-0.1") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.Offset, value = "100%") shouldBe "100%"
        }

        test("transform is re-serialized in a canonical form") {
            attrs().ok(grammar = SvgValueGrammar.Transform, value = "translate(10,20)rotate( 45 , 1 2 )") shouldBe
                "translate(10 20) rotate(45 1 2)"
            attrs().rejects(grammar = SvgValueGrammar.Transform, value = "translate(1 2) evil(3)") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.Transform, value = "matrix(1 2 3)") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.Transform, value = "scale(1e9)") shouldBe SvgRejection.TOO_COMPLEX
        }

        test("path data: character set, magnitude and whitespace normalization") {
            attrs().ok(grammar = SvgValueGrammar.PathData, value = "M0,0 \n L 10 10\tz") shouldBe "M0,0 L 10 10 z"
            attrs().rejects(grammar = SvgValueGrammar.PathData, value = "M0 0 X 1 1") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().rejects(grammar = SvgValueGrammar.PathData, value = "M0 0 L 1e7 1") shouldBe SvgRejection.TOO_COMPLEX
            attrs().ok(grammar = SvgValueGrammar.Points, value = "1,2 3,4") shouldBe "1,2 3,4"
            attrs().rejects(grammar = SvgValueGrammar.Points, value = "1,2 M3,4") shouldBe SvgRejection.UNSUPPORTED_CONTENT
        }

        test("enums, dash arrays, ids, preserveAspectRatio, viewBox") {
            attrs().ok(grammar = SvgValueGrammar.Enum(setOf("a", "b")), value = "a") shouldBe "a"
            attrs().rejects(grammar = SvgValueGrammar.Enum(setOf("a", "b")), value = "A") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.DashArray, value = "4, 2 1") shouldBe "4 2 1"
            attrs().rejects(grammar = SvgValueGrammar.DashArray, value = "-1") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.Id, value = "a-b_c.1") shouldBe "a-b_c.1"
            attrs().rejects(grammar = SvgValueGrammar.Id, value = "1abc") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.PreserveAspectRatio, value = "xMidYMid meet") shouldBe "xMidYMid meet"
            attrs().rejects(grammar = SvgValueGrammar.PreserveAspectRatio, value = "evil") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            attrs().ok(grammar = SvgValueGrammar.ViewBox, value = "0,0, 100.50 100") shouldBe "0 0 100.5 100"
        }

        test("screening classifies dangerous schemes before any grammar") {
            attrs().rejects(grammar = SvgValueGrammar.Paint, value = "JaVa\tScRiPt:alert(1)") shouldBe SvgRejection.EXTERNAL_REFERENCE
            attrs().rejects(grammar = SvgValueGrammar.Paint, value = "url(//e.example/x)") shouldBe SvgRejection.EXTERNAL_REFERENCE
            attrs().rejects(grammar = SvgValueGrammar.Paint, value = "red\\00") shouldBe SvgRejection.UNSUPPORTED_CONTENT
        }

        test("style: split, converted, -inkscape-* dropped, unknown rejected; first ':' only") {
            attrs().convertStyle("fill:#ff0000; stroke : none;;-inkscape-stroke:none") shouldBe
                mapOf("fill" to "#ff0000", "stroke" to "none")
            attrs().rejects(grammar = SvgValueGrammar.Paint, value = "url(#a:b)") shouldBe SvgRejection.UNSUPPORTED_CONTENT
            val duplicate = runCatching { attrs().convertStyle("fill:red;fill:blue") }.exceptionOrNull()
            (duplicate as SvgRejectedException).reason shouldBe SvgRejection.UNSUPPORTED_CONTENT
        }

        test("ReDoS guard: 64 KB of valid and of almost valid data is processed quickly") {
            val valid = "M0 0" + " L1.5 2.5".repeat(7_000)
            val almost = "M0 0" + " L1.5 2.5".repeat(7_000) + " X"
            val millis =
                measureTimeMillis {
                    attrs().ok(grammar = SvgValueGrammar.PathData, value = valid)
                    attrs().rejects(grammar = SvgValueGrammar.PathData, value = almost)
                    attrs().rejects(grammar = SvgValueGrammar.Transform, value = "translate(" + "1 ".repeat(400) + " x")
                    attrs().rejects(grammar = SvgValueGrammar.Paint, value = "rgb(" + "1,".repeat(400))
                }
            (millis < 500) shouldBe true
        }
    })
