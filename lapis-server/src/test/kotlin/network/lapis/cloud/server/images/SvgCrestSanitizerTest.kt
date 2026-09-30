package network.lapis.cloud.server.images

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun path(d: String) = "<path d=\"$d\"/>"

private fun nested(levels: Int) = "<g>".repeat(levels) + "</g>".repeat(levels)

private val hostile: List<Triple<String, String, SvgRejection>> =
    listOf(
        // -- script and interactivity -----------------------------------------------------------
        Triple("script element", svgDoc("<script>alert(1)</script>"), SvgRejection.SCRIPT),
        Triple("prefixed script (namespace aliasing)", svgDoc("<x:script xmlns:x=\"$SVG\">alert(1)</x:script>"), SvgRejection.SCRIPT),
        Triple("xhtml script", svgDoc("<script xmlns=\"http://www.w3.org/1999/xhtml\">alert(1)</script>"), SvgRejection.SCRIPT),
        Triple("onload on svg", svgDocWith(attrs = "onload=\"alert(1)\""), SvgRejection.SCRIPT),
        Triple("OnLoad on path", svgDoc("<path d=\"M0 0\" OnLoad=\"alert(1)\"/>"), SvgRejection.SCRIPT),
        Triple("ONCLICK on g", svgDoc("<g ONCLICK=\"alert(1)\"/>"), SvgRejection.SCRIPT),
        Triple("onbegin on animate", svgDoc("<animate onbegin=\"alert(1)\" attributeName=\"x\"/>"), SvgRejection.SCRIPT),
        Triple(
            "foreignObject with div",
            svgDoc("<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x</div></foreignObject>"),
            SvgRejection.SCRIPT,
        ),
        Triple(
            "animate href",
            svgDoc("<path d=\"M0 0\"><animate attributeName=\"href\" values=\"javascript:alert(1)\"/></path>"),
            SvgRejection.SCRIPT,
        ),
        Triple("set to javascript", svgDoc("<a><set attributeName=\"xlink:href\" to=\"javascript:alert(1)\"/></a>"), SvgRejection.SCRIPT),
        Triple(
            "animateTransform",
            svgDoc("<rect width=\"1\" height=\"1\"><animateTransform attributeName=\"transform\"/></rect>"),
            SvgRejection.SCRIPT,
        ),
        Triple("animateMotion", svgDoc("<rect width=\"1\" height=\"1\"><animateMotion path=\"M0 0\"/></rect>"), SvgRejection.SCRIPT),
        Triple("a element", svgDoc("<a xlink:href=\"https://evil.example\"><rect width=\"1\" height=\"1\"/></a>"), SvgRejection.SCRIPT),
        Triple("iframe", svgDoc("<iframe src=\"https://evil.example\"/>"), SvgRejection.SCRIPT),
        Triple("object", svgDoc("<object data=\"x\"/>"), SvgRejection.SCRIPT),
        Triple("embed", svgDoc("<embed src=\"x\"/>"), SvgRejection.SCRIPT),
        // -- external references ----------------------------------------------------------------
        Triple("image http", svgDoc("<image href=\"http://evil.example/x.png\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("image data uri", svgDoc("<image href=\"data:image/png;base64,AAAA\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("feImage", svgDoc("<feImage href=\"http://evil.example/x\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("use of external file", svgDoc("<use href=\"other.svg#x\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("use xlink:href http", svgDoc("<use xlink:href=\"http://e.example/x.svg#a\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("use of missing id", svgDoc("<use href=\"#missing\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("numeric-entity javascript", svgDoc("<use href=\"&#106;avascript:alert(1)\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("tab inside javascript", svgDoc("<use href=\"java&#x09;script:alert(1)\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("leading space JAVASCRIPT", svgDoc("<use href=\" JAVASCRIPT:alert(1)\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple(
            "fill url http",
            svgDoc("<rect width=\"1\" height=\"1\" fill=\"url(http://e.example/#g)\"/>"),
            SvgRejection.EXTERNAL_REFERENCE,
        ),
        Triple(
            "fill url data",
            svgDoc("<rect width=\"1\" height=\"1\" fill=\"url(data:image/svg+xml;base64,AAAA)\"/>"),
            SvgRejection.EXTERNAL_REFERENCE,
        ),
        Triple(
            "style fill url protocol-relative",
            svgDoc("<rect width=\"1\" height=\"1\" style=\"fill:url(//e.example/x)\"/>"),
            SvgRejection.EXTERNAL_REFERENCE,
        ),
        Triple(
            "url with fallback to external",
            svgDoc("<linearGradient id=\"g\"/><rect width=\"1\" height=\"1\" fill=\"url(#g) url(//e.example)\"/>"),
            SvgRejection.EXTERNAL_REFERENCE,
        ),
        Triple("xml:base", svgDoc("<g xml:base=\"http://e.example/\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("href on rect", svgDoc("<rect width=\"1\" height=\"1\" href=\"#x\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple(
            "fill points at a clipPath",
            svgDoc("<clipPath id=\"c\"><rect width=\"1\" height=\"1\"/></clipPath><rect width=\"1\" height=\"1\" fill=\"url(#c)\"/>"),
            SvgRejection.EXTERNAL_REFERENCE,
        ),
        // -- css --------------------------------------------------------------------------------
        Triple("style @import", svgDoc("<rect width=\"1\" height=\"1\" style=\"@import url(x)\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple(
            "style expression",
            svgDoc("<rect width=\"1\" height=\"1\" style=\"fill:expression(alert(1))\"/>"),
            SvgRejection.UNSUPPORTED_CONTENT,
        ),
        Triple("style css escape", svgDoc("<rect width=\"1\" height=\"1\" style=\"fill:\\75rl(x)\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("style comment", svgDoc("<rect width=\"1\" height=\"1\" style=\"fill:red/*x*/\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple(
            "style unknown property",
            svgDoc("<rect width=\"1\" height=\"1\" style=\"behavior:none\"/>"),
            SvgRejection.UNSUPPORTED_CONTENT,
        ),
        Triple("style behavior url", svgDoc("<rect width=\"1\" height=\"1\" style=\"behavior:url(x)\"/>"), SvgRejection.EXTERNAL_REFERENCE),
        Triple("style element", svgDoc("<style>rect{fill:red}</style>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("style element with cdata", svgDoc("<style><![CDATA[rect{fill:red}]]></style>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("filter", svgDoc("<filter id=\"f\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("mask", svgDoc("<mask id=\"m\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("pattern", svgDoc("<pattern id=\"p\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("marker", svgDoc("<marker id=\"m\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("symbol", svgDoc("<symbol id=\"s\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("switch", svgDoc("<switch/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("cursor", svgDoc("<cursor/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("view", svgDoc("<view id=\"v\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("unknown attribute", svgDoc("<rect width=\"1\" height=\"1\" foo=\"bar\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("unknown xlink attribute", svgDoc("<use href=\"#a\" xlink:actuate=\"onLoad\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        // -- xml attacks ------------------------------------------------------------------------
        Triple(
            "doctype with file entity",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + svgDoc("&x;"),
            SvgRejection.UNSUPPORTED_CONTENT,
        ),
        Triple(
            "billion laughs",
            "<!DOCTYPE svg [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;\">]>" + svgDoc("<title>&c;</title>"),
            SvgRejection.UNSUPPORTED_CONTENT,
        ),
        Triple("external dtd", "<!DOCTYPE svg SYSTEM \"http://evil.example/x.dtd\">" + svgDoc(), SvgRejection.UNSUPPORTED_CONTENT),
        Triple(
            "parameter entity",
            "<!DOCTYPE svg [<!ENTITY % p SYSTEM \"http://e.example/p\">%p;]>" + svgDoc(),
            SvgRejection.UNSUPPORTED_CONTENT,
        ),
        Triple("lower-case doctype", "<!doctype svg>" + svgDoc(), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("doctype with line break", "<!\nDOCTYPE svg>" + svgDoc(), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("xml-stylesheet pi", "<?xml-stylesheet href=\"x.css\"?>" + svgDoc(), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("cdata text in g", svgDoc("<g><![CDATA[alert(1)]]></g>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("cdata in dropped metadata", svgDoc("<metadata><![CDATA[alert(1)]]></metadata>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("pi inside a dropped subtree", svgDoc("<metadata><?php echo 1 ?></metadata>"), SvgRejection.UNSUPPORTED_CONTENT),
        // -- size and complexity ----------------------------------------------------------------
        Triple("25 levels", svgDoc(nested(25)), SvgRejection.TOO_COMPLEX),
        Triple("2001 elements", svgDoc("<g/>".repeat(2000)), SvgRejection.TOO_COMPLEX),
        Triple(
            "2001 elements inside a dropped metadata subtree",
            svgDoc("<metadata>" + "<x:a xmlns:x=\"urn:x\"/>".repeat(2000) + "</metadata>"),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple(
            "depth inside a dropped subtree",
            svgDoc(
                "<metadata>" + "<x:a xmlns:x=\"urn:x\">".repeat(30) + "</x:a>".repeat(30) + "</metadata>",
            ),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple("21 attributes", svgDoc("<g " + (1..21).joinToString(" ") { "a$it=\"1\"" } + "/>"), SvgRejection.TOO_COMPLEX),
        Triple("path data over 64 KB", svgDoc(path("M0 0" + " L1 1".repeat(14_000))), SvgRejection.TOO_COMPLEX),
        Triple(
            "sum of path data over 64 KB",
            svgDoc(path("M0 0" + " L1 1".repeat(7_000)) + path("M0 0" + " L1 1".repeat(7_000)) + path("M0 0" + " L1 1".repeat(1_000))),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple("coordinate 1e9 in d", svgDoc(path("M0 0 L1e9 1")), SvgRejection.TOO_COMPLEX),
        Triple("coordinate 1e9 in x", svgDoc("<rect x=\"1e9\" width=\"1\" height=\"1\"/>"), SvgRejection.TOO_COMPLEX),
        Triple("coordinate 1e308 in transform", svgDoc("<g transform=\"translate(1e308)\"/>"), SvgRejection.TOO_COMPLEX),
        Triple("33 use elements", svgDoc("<path id=\"p\" d=\"M0 0\"/>" + "<use href=\"#p\"/>".repeat(33)), SvgRejection.TOO_COMPLEX),
        Triple("use of own ancestor", svgDoc("<g id=\"a\"><use href=\"#a\"/></g>"), SvgRejection.TOO_COMPLEX),
        Triple(
            "use of a use",
            svgDoc("<path id=\"p\" d=\"M0 0\"/><use id=\"u\" href=\"#p\"/><use href=\"#u\"/>"),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple(
            "use of a group containing a use",
            svgDoc("<path id=\"p\" d=\"M0 0\"/><g id=\"g\"><use href=\"#p\"/></g><use href=\"#g\"/>"),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple(
            "gradient cycle",
            svgDoc("<linearGradient id=\"a\" href=\"#b\"/><linearGradient id=\"b\" href=\"#a\"/>"),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple(
            "gradient chain of five",
            svgDoc((1..5).joinToString("") { "<linearGradient id=\"g$it\"" + (if (it < 5) " href=\"#g${it + 1}\"" else "") + "/>" }),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple("id with 65 characters", svgDoc("<g id=\"${"a".repeat(65)}\"/>"), SvgRejection.TOO_COMPLEX),
        Triple(
            "output over 128 KB",
            svgDoc(
                (1..1500).joinToString("") {
                    "<path id=\"p$it\" d=\"M0 0 L10 10\" fill=\"#ffffff\" stroke=\"#000000\" stroke-width=\"1\" transform=\"translate(1 1)\" opacity=\"0.5\"/>\n"
                },
            ),
            SvgRejection.TOO_COMPLEX,
        ),
        Triple("input over 256 KB", svgDoc("<g/>".repeat(1) + " ".repeat(300_000)), SvgRejection.FILE_TOO_LARGE),
        // -- encoding and structure -------------------------------------------------------------
        Triple("iso-8859-1 declaration", "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + svgDoc(), SvgRejection.UNSUPPORTED_FORMAT),
        Triple("xml 1.1 declaration", "<?xml version=\"1.1\"?>" + svgDoc(), SvgRejection.UNSUPPORTED_FORMAT),
        Triple("nul character", svgDoc("<g/>\u0000"), SvgRejection.UNSUPPORTED_FORMAT),
        Triple("control character", svgDoc("<g/>\u0001"), SvgRejection.UNSUPPORTED_FORMAT),
        Triple("svg without namespace", "<svg viewBox=\"0 0 1 1\"/>", SvgRejection.UNSUPPORTED_FORMAT),
        Triple("svg in the wrong namespace", "<svg xmlns=\"urn:x\" viewBox=\"0 0 1 1\"/>", SvgRejection.UNSUPPORTED_FORMAT),
        Triple(
            "html polyglot root",
            "<html xmlns=\"http://www.w3.org/1999/xhtml\">" + svgDoc() + "</html>",
            SvgRejection.UNSUPPORTED_FORMAT,
        ),
        Triple("xhtml element inside svg", svgDoc("<div xmlns=\"http://www.w3.org/1999/xhtml\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("element with empty namespace", svgDoc("<g xmlns=\"\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("two root elements", svgDoc() + svgDoc(), SvgRejection.UNSUPPORTED_FORMAT),
        Triple("nested svg", svgDoc("<svg viewBox=\"0 0 1 1\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("malformed xml", "<svg xmlns=\"$SVG\" viewBox=\"0 0 1 1\"><g></svg>", SvgRejection.UNDECODABLE),
        Triple("duplicate id", svgDoc("<g id=\"a\"/><g id=\"a\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("text inside a path", svgDoc("<path d=\"M0 0\">hello</path>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple("stop outside a gradient", svgDoc("<stop offset=\"0\"/>"), SvgRejection.UNSUPPORTED_CONTENT),
        Triple(
            "shape inside a gradient",
            svgDoc("<linearGradient id=\"g\"><rect width=\"1\" height=\"1\"/></linearGradient>"),
            SvgRejection.UNSUPPORTED_CONTENT,
        ),
        Triple("group inside a clipPath", svgDoc("<clipPath id=\"c\"><g/></clipPath>"), SvgRejection.UNSUPPORTED_CONTENT),
        // -- dimensions -------------------------------------------------------------------------
        Triple("missing viewBox", "<svg xmlns=\"$SVG\" width=\"10\" height=\"10\"/>", SvgRejection.NO_DIMENSIONS),
        Triple("viewBox with zero width", svgDocWith(viewBox = "0 0 0 10"), SvgRejection.DIMENSIONS_TOO_LARGE),
        Triple("viewBox aspect ratio 1:10", svgDocWith(viewBox = "0 0 10 100"), SvgRejection.DIMENSIONS_TOO_LARGE),
        Triple("viewBox edge over 1e5", svgDocWith(viewBox = "0 0 200000 200000"), SvgRejection.DIMENSIONS_TOO_LARGE),
        // -- text -------------------------------------------------------------------------------
        Triple("text", svgDoc("<text x=\"0\" y=\"10\">Hi</text>"), SvgRejection.TEXT_NOT_SUPPORTED),
        Triple("tspan", svgDoc("<tspan>Hi</tspan>"), SvgRejection.TEXT_NOT_SUPPORTED),
        Triple("textPath", svgDoc("<textPath href=\"#p\">Hi</textPath>"), SvgRejection.TEXT_NOT_SUPPORTED),
    )

class SvgCrestSanitizerTest :
    FunSpec({
        context("hostile and unsupported input is rejected with a precise code") {
            for ((name, input, expected) in hostile) {
                test(name) { reasonOf(input.toByteArray()) shouldBe expected }
            }
        }

        test("at least 45 hostile cases are covered") { (hostile.size >= 45) shouldBe true }

        test("UTF-16 input (with BOM) is refused as an unsupported format") {
            val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + svgDoc().toByteArray(Charsets.UTF_16LE)
            SvgCrestSanitizer.isCandidate(utf16) shouldBe false
            reasonOf(utf16) shouldBe SvgRejection.UNSUPPORTED_FORMAT
            val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + svgDoc().toByteArray(Charsets.UTF_16BE)
            reasonOf(be) shouldBe SvgRejection.UNSUPPORTED_FORMAT
        }

        test("BOM-less UTF-16 (a NUL after every character) is refused") {
            reasonOf(svgDoc().toByteArray(Charsets.UTF_16LE)) shouldBe SvgRejection.UNSUPPORTED_FORMAT
        }

        test("invalid UTF-8 is refused") {
            reasonOf(byteArrayOf('<'.code.toByte(), 0xC3.toByte(), 0x28)) shouldBe SvgRejection.UNSUPPORTED_FORMAT
        }

        test("isCandidate: BOM and whitespace are skipped, anything else is not a candidate") {
            SvgCrestSanitizer.isCandidate(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), ' '.code.toByte(), '<'.code.toByte()),
            ) shouldBe
                true
            SvgCrestSanitizer.isCandidate("  \n<svg/>".toByteArray()) shouldBe true
            SvgCrestSanitizer.isCandidate("GIF89a".toByteArray()) shouldBe false
            SvgCrestSanitizer.isCandidate(ByteArray(0)) shouldBe false
        }

        test("sanitize never throws for arbitrary bytes") {
            val random = java.util.Random(42)
            repeat(300) {
                val bytes = ByteArray(random.nextInt(200)).also { random.nextBytes(it) }
                SvgCrestSanitizer.sanitize(bytes) // must not throw
            }
        }

        test("the StAX factory is the JDK implementation and is configured without DTD/entity support") {
            val factory = SvgCrestReader.newFactoryForTest()
            factory.javaClass.name shouldContain "com.sun.xml.internal.stream"
            factory.getProperty("javax.xml.stream.supportDTD") shouldBe false
            factory.getProperty("javax.xml.stream.isSupportingExternalEntities") shouldBe false
        }

        context("valid crests are accepted, normalized, idempotent and deterministic") {
            for (name in listOf("path-crest", "gradient-crest", "clip-crest", "use-crest", "bom-crest", "inkscape-crest")) {
                test(name) {
                    val input = fixture("$name.svg")
                    val first = accepted(input)
                    val output = first.bytes.toString(Charsets.UTF_8)
                    // Ballast is gone, the output is our own serialization.
                    for (gone in listOf("sodipodi", "inkscape", "style=", "<!--", "<?xml", "metadata", "<title", "xml:space", "class=")) {
                        output shouldNotContain gone
                    }
                    output shouldContain "xmlns=\"$SVG\""
                    val scale = maxOf(1.0, 256.0 / maxOf(first.viewBox.width, first.viewBox.height))
                    output shouldContain "width=\"${SvgCrestAttributes.formatNumber(first.viewBox.width * scale)}\""
                    output shouldContain "height=\"${SvgCrestAttributes.formatNumber(first.viewBox.height * scale)}\""
                    // Idempotent and deterministic.
                    accepted(first.bytes).bytes.toList() shouldBe first.bytes.toList()
                    accepted(input).bytes.toList() shouldBe first.bytes.toList()
                    assertOutputInvariant(first.bytes)
                }
            }
        }

        test("an Inkscape export keeps its drawing: style becomes attributes, mm sizes are replaced by the viewBox") {
            val output = accepted(fixture("inkscape-crest.svg")).bytes.toString(Charsets.UTF_8)
            output shouldContain "viewBox=\"0 0 210 210\""
            output shouldContain "width=\"256\""
            output shouldNotContain "mm"
            output shouldContain "fill=\"#cc0000\""
            output shouldContain "stroke=\"#000000\""
            output shouldContain "stroke-width=\"2\""
            output shouldContain "d=\"m 10,10 h 100 v 100 z\""
        }

        test("an Inkscape namedview with many attributes is dropped without TOO_COMPLEX") {
            val attrs = (1..30).joinToString(" ") { "inkscape:snap-$it=\"true\"" }
            val doc =
                svgDoc(
                    "<sodipodi:namedview xmlns:sodipodi=\"http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd\" " +
                        "xmlns:inkscape=\"http://www.inkscape.org/namespaces/inkscape\" id=\"base\" $attrs/>" +
                        "<path d=\"M0 0h10v10z\" fill=\"#c00\"/>",
                )
            val output = accepted(doc.toByteArray()).bytes.toString(Charsets.UTF_8)
            output shouldNotContain "namedview"
            output shouldContain "<path"
        }

        test("a kept element with more than 20 attributes is still rejected as too complex") {
            val attrs = (1..21).joinToString(" ") { "data$it=\"1\"" }
            reasonOf(svgDoc("<path d=\"M0 0\" $attrs/>").toByteArray()) shouldBe SvgRejection.TOO_COMPLEX
        }

        test("Illustrator enable-background and Inkscape 0.92 rendering properties are dropped silently") {
            val doc =
                svgDocWith(
                    attrs = "style=\"enable-background:new 0 0 100 100;\"",
                    body =
                        "<path d=\"M0 0h10v10z\" style=\"fill:#cc0000;paint-order:normal;color:#000;isolation:auto;" +
                            "mix-blend-mode:normal;vector-effect:none;marker:none;solid-color:#000;solid-opacity:1;" +
                            "color-interpolation:sRGB;color-interpolation-filters:linearRGB;color-rendering:auto;" +
                            "shape-rendering:auto;image-rendering:auto;text-rendering:auto;overflow:visible;" +
                            "enable-background:accumulate\"/>",
                )
            val output = accepted(doc.toByteArray()).bytes.toString(Charsets.UTF_8)
            for (gone in listOf("enable-background", "paint-order", "overflow", "isolation", "marker")) output shouldNotContain gone
            output shouldContain "fill=\"#cc0000\""
        }

        test("color is kept so currentColor keeps its colour, as attribute and in style") {
            val attr = accepted(svgDoc("<g color=\"#cc0000\"><path d=\"M0 0h10v10z\" fill=\"currentColor\"/></g>").toByteArray())
            val out1 = attr.bytes.toString(Charsets.UTF_8)
            out1 shouldContain "color=\"#cc0000\""
            out1 shouldContain "fill=\"currentcolor\""
            val style = accepted(svgDoc("<path d=\"M0 0h10v10z\" style=\"color:#20358c;fill:currentColor\"/>").toByteArray())
            style.bytes.toString(Charsets.UTF_8) shouldContain "color=\"#20358c\""
            reasonOf(svgDoc("<path d=\"M0 0h10v10z\" color=\"url(#x)\"/>").toByteArray()) shouldBe SvgRejection.UNSUPPORTED_CONTENT
        }

        test("mix-blend-mode is dropped only as normal; other values are rejected") {
            accepted(svgDoc("<path d=\"M0 0h10v10z\" mix-blend-mode=\"normal\"/>").toByteArray())
                .bytes
                .toString(Charsets.UTF_8) shouldNotContain "mix-blend-mode"
            reasonOf(svgDoc("<path d=\"M0 0h10v10z\" style=\"mix-blend-mode:multiply\"/>").toByteArray()) shouldBe
                SvgRejection.UNSUPPORTED_CONTENT
            reasonOf(svgDoc("<path d=\"M0 0h10v10z\" mix-blend-mode=\"multiply\"/>").toByteArray()) shouldBe
                SvgRejection.UNSUPPORTED_CONTENT
        }

        test("tiny viewBox gets a readable intrinsic size, idempotently") {
            val first = accepted(svgDocWith(viewBox = "0 0 16 16", body = "<path d=\"M0 0h16v16z\"/>").toByteArray())
            val output = first.bytes.toString(Charsets.UTF_8)
            output shouldContain "viewBox=\"0 0 16 16\""
            output shouldContain "width=\"256\""
            output shouldContain "height=\"256\""
            accepted(first.bytes).bytes.toList() shouldBe first.bytes.toList()
        }

        test("exact canonical output of a small crest") {
            accepted(fixture("clip-crest.svg")).bytes.toString(Charsets.UTF_8) shouldBe
                "<svg xmlns=\"$SVG\" viewBox=\"0 0 100 100\" width=\"256\" height=\"256\">\n" +
                "<defs><clipPath id=\"c\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></clipPath></defs>" +
                "<rect width=\"100\" height=\"100\" fill=\"#20358c\" clip-path=\"url(#c)\"/></svg>"
        }

        test("an href is always written as xlink:href with the xlink namespace declared") {
            val output = accepted(fixture("use-crest.svg")).bytes.toString(Charsets.UTF_8)
            output shouldContain "xmlns:xlink=\"$XLINK\""
            output shouldContain "xlink:href=\"#star\""
            output shouldNotContain " href="
        }

        test("paint-order and vector-effect are dropped only when neutral; other values are rejected") {
            accepted(svgDoc("<path d=\"M0 0h10v10z\" style=\"paint-order:fill stroke markers;vector-effect:none\"/>").toByteArray())
            reasonOf(svgDoc("<path d=\"M0 0h10v10z\" stroke=\"#000\" style=\"paint-order:stroke fill markers\"/>").toByteArray()) shouldBe
                SvgRejection.UNSUPPORTED_CONTENT
            reasonOf(svgDoc("<path d=\"M0 0h10v10z\" vector-effect=\"non-scaling-stroke\"/>").toByteArray()) shouldBe
                SvgRejection.UNSUPPORTED_CONTENT
        }

        test("style wins over a presentation attribute of the same property") {
            val output =
                accepted(svgDoc("<rect width=\"1\" height=\"1\" fill=\"#111111\" style=\"fill:#222222;stroke:#333333\"/>").toByteArray())
            output.bytes.toString(Charsets.UTF_8) shouldContain "fill=\"#222222\""
            output.bytes.toString(Charsets.UTF_8) shouldContain "stroke=\"#333333\""
        }

        test("percent lengths are allowed as attributes but not inside style; other units are refused") {
            accepted(svgDoc("<rect width=\"100%\" height=\"50%\"/>").toByteArray())
            reasonOf(svgDoc("<rect width=\"1\" height=\"1\" style=\"stroke-width:5%\"/>").toByteArray()) shouldBe
                SvgRejection.UNSUPPORTED_CONTENT
            reasonOf(svgDoc("<rect width=\"1mm\" height=\"1\"/>").toByteArray()) shouldBe SvgRejection.UNSUPPORTED_CONTENT
        }
    })
