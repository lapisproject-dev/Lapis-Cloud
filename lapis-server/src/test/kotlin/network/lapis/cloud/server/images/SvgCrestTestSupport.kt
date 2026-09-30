package network.lapis.cloud.server.images

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal const val SVG = "http://www.w3.org/2000/svg"
internal const val XLINK = "http://www.w3.org/1999/xlink"

/** A document with the standard namespace and a viewBox; [body] goes inside, [attrs] onto the root. */
internal fun svgDoc(body: String = ""): String = svgDocWith(body = body)

/** Like [svgDoc], with the root attributes and the viewBox under the caller's control. */
internal fun svgDocWith(
    body: String = "",
    attrs: String = "",
    viewBox: String = "0 0 100 100",
): String = "<svg xmlns=\"$SVG\" xmlns:xlink=\"$XLINK\" viewBox=\"$viewBox\" $attrs>$body</svg>"

internal fun fixture(name: String): ByteArray =
    SvgCrestSanitizerTest::class.java
        .getResourceAsStream("/svg-crest/$name")!!
        .readBytes()

internal fun accepted(bytes: ByteArray): SvgSanitizeResult.Accepted {
    val result = SvgCrestSanitizer.sanitize(bytes)
    (result is SvgSanitizeResult.Accepted) shouldBe true
    return result as SvgSanitizeResult.Accepted
}

internal fun reasonOf(bytes: ByteArray): SvgRejection? = (SvgCrestSanitizer.sanitize(bytes) as? SvgSanitizeResult.Rejected)?.reason

private val allowedOutputAttributes: Set<String> =
    SvgCrestPolicy.ATTRIBUTE_ORDER.toSet() + setOf("xmlns", "xmlns:xlink", "xlink:href")

/**
 * The output invariant: re-parsed with a hardened DOM, every element is in the allowlist and in the SVG
 * namespace, every attribute is one the writer can emit, and no value carries a script scheme or a
 * `url(` that does not point at a fragment.
 */
internal fun assertOutputInvariant(output: ByteArray) {
    val text = output.toString(Charsets.UTF_8)
    text.contains("<!DOCTYPE") shouldBe false
    text.contains("<?") shouldBe false
    text.contains("<!--") shouldBe false
    text.contains("<![CDATA[") shouldBe false
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setXIncludeAware(false)
            isExpandEntityReferences = false
        }
    val root = factory.newDocumentBuilder().parse(output.inputStream()).documentElement

    fun walk(element: Element) {
        element.namespaceURI shouldBe SVG
        (element.localName in SvgCrestPolicy.ALLOWED_ELEMENTS) shouldBe true
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            (attribute.nodeName in allowedOutputAttributes) shouldBe true
            val value = attribute.nodeValue.lowercase()
            for (bad in listOf("javascript", "data:", "vbscript", "@import", "expression(", "\\")) {
                value.contains(bad) shouldBe false
            }
            var index = value.indexOf("url(")
            while (index >= 0) {
                value.getOrNull(index + "url(".length) shouldBe '#'
                index = value.indexOf("url(", index + 1)
            }
            if (attribute.nodeName == "xlink:href") value.startsWith("#") shouldBe true
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                walk(child)
            } else {
                (child.nodeType == org.w3c.dom.Node.TEXT_NODE && child.nodeValue.isBlank()) shouldBe true
            }
        }
    }
    walk(root)
    root.getAttribute("viewBox") shouldNotBe ""
}
