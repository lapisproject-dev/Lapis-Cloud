package network.lapis.cloud.server.images

import io.kotest.core.spec.style.FunSpec
import java.util.Random

/**
 * Payload x mutation matrix and a seeded fuzz run: the sanitizer either rejects, or -- when it accepts --
 * the output satisfies [assertOutputInvariant] (no script scheme, no external url, only allowlisted names).
 */
private val basePayloads: List<String> =
    listOf(
        "<script>alert(1)</script>",
        "<rect width=\"1\" height=\"1\" onclick=\"alert(1)\"/>",
        "<use href=\"javascript:alert(1)\"/>",
        "<use xlink:href=\"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=#x\"/>",
        "<image href=\"http://evil.example/x.png\"/>",
        "<rect width=\"1\" height=\"1\" fill=\"url(http://evil.example/#x)\"/>",
        "<rect width=\"1\" height=\"1\" style=\"fill:url(//evil.example/x);behavior:url(x)\"/>",
        "<foreignObject><body xmlns=\"http://www.w3.org/1999/xhtml\" onload=\"alert(1)\"/></foreignObject>",
        "<a href=\"javascript:alert(1)\"><rect width=\"1\" height=\"1\"/></a>",
        "<set attributeName=\"href\" to=\"javascript:alert(1)\"/>",
        "<style>@import url(http://evil.example/x.css);</style>",
    )

private fun mutations(payload: String): List<String> {
    val out = mutableListOf(payload, payload.uppercase(), payload.lowercase())
    // numeric entity encoding of a single character, decimal and hex, with leading zeros
    for (index in payload.indices.filter { payload[it].isLetter() }.take(6)) {
        val c = payload[index].code
        out += payload.replaceRange(index, index + 1, "&#$c;")
        out += payload.replaceRange(index, index + 1, "&#x${c.toString(16)};")
        out += payload.replaceRange(index, index + 1, "&#000$c;")
    }
    out += payload.replace("javascript", "java&#x09;script").replace("script", "scr&#x0A;ipt")
    out += payload.replace("href", "xlink:href")
    out += payload.replace("http://", "HtTp://").replace("data:", "DATA:")
    out += payload.replace("url(", "url( ").replace("url(", "url('")
    // prefix aliasing of the SVG and XLink namespaces
    out += payload.replace("<script", "<q:script xmlns:q=\"$SVG\"").replace("</script>", "</q:script>")
    out += payload.replace("xlink:href", "q:href").replace("<use", "<use xmlns:q=\"$XLINK\"")
    // fullwidth / homoglyph variants
    out += payload.replace("script", "ｓcript").replace("http", "ｈttp").replace("url", "ｕrl")
    out += payload.replace("s", "ѕ")
    return out
}

class SvgCrestSanitizerMutationTest :
    FunSpec({
        test("payload x mutation matrix: rejected, or accepted with a clean output") {
            var total = 0
            for (payload in basePayloads) {
                for (mutated in mutations(payload)) {
                    total++
                    val result = SvgCrestSanitizer.sanitize(svgDoc(mutated).toByteArray())
                    if (result is SvgSanitizeResult.Accepted) assertOutputInvariant(result.bytes)
                }
            }
            check(total > 300) { "matrix unexpectedly small: $total" }
        }

        test("every base payload is rejected in its unmutated form") {
            for (payload in basePayloads) {
                val result = SvgCrestSanitizer.sanitize(svgDoc(payload).toByteArray())
                check(result is SvgSanitizeResult.Rejected) { "accepted: $payload" }
            }
        }

        test("seeded fuzz: 5000 byte-level mutations of valid crests never throw and never yield an unsafe output") {
            val seeds =
                listOf("path-crest", "gradient-crest", "clip-crest", "use-crest", "inkscape-crest").map { fixture("$it.svg") }
            val random = Random(20260930)
            repeat(5_000) {
                val base = seeds[random.nextInt(seeds.size)].copyOf()
                val mutated =
                    when (random.nextInt(4)) {
                        0 -> base.also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                        1 -> base.copyOfRange(0, random.nextInt(base.size + 1))
                        2 -> {
                            val at = random.nextInt(base.size)
                            base.copyOfRange(0, at) + ByteArray(random.nextInt(8)) { random.nextInt(256).toByte() } +
                                base.copyOfRange(at, base.size)
                        }
                        else -> {
                            val at = random.nextInt(base.size)
                            base.copyOfRange(0, at) + base.copyOfRange(minOf(base.size, at + random.nextInt(12)), base.size)
                        }
                    }
                val result = SvgCrestSanitizer.sanitize(mutated)
                if (result is SvgSanitizeResult.Accepted) assertOutputInvariant(result.bytes)
            }
        }
    })
