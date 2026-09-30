package network.lapis.cloud.server.images

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

private val logger = KotlinLogging.logger {}

/** Minimal element tree of the SANITIZED document -- attributes are already final, output-ready values. */
internal class SvgNode(
    val name: String,
    val attributes: MutableMap<String, String>,
    val children: MutableList<SvgNode> = mutableListOf(),
)

/**
 * Welle V1.9.21 -- StAX reader (no DOM) that builds [SvgNode]s of everything allowed and throws
 * [SvgRejectedException] for everything else. Elements are classified by namespace URI and local
 * name, NEVER by prefix. Limits, the DTD/PI/CDATA rules and the depth counter also apply inside
 * silently dropped subtrees, so a dropped container cannot smuggle a bomb.
 */
internal object SvgCrestReader {
    private val factory: XMLInputFactory =
        XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLInputFactory.IS_COALESCING, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            setProperty("http://java.sun.com/xml/stream/properties/report-cdata-event", true)
            // Second line of defence only -- the real guards are the DTD rejection and the counters below.
            for ((name, value) in listOf<Pair<String, Any>>(
                XMLConstants.FEATURE_SECURE_PROCESSING to true,
                "jdk.xml.entityExpansionLimit" to "1",
                "jdk.xml.maxElementDepth" to "64",
                "jdk.xml.elementAttributeLimit" to "64",
            )) {
                runCatching { setProperty(name, value) }
            }
        }

    internal fun newFactoryForTest(): XMLInputFactory = factory

    fun read(text: String): SvgNode {
        val reader = factory.createXMLStreamReader(StringReader(text))
        try {
            return parse(reader)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun parse(reader: XMLStreamReader): SvgNode {
        val attributes = SvgCrestAttributes()
        val stack = ArrayDeque<SvgNode>()
        var root: SvgNode? = null
        var rootClosed = false
        var depth = 0
        var elements = 0
        var skipDepth = -1
        while (true) {
            val event =
                try {
                    reader.next()
                } catch (e: XMLStreamException) {
                    logger.debug {
                        "SVG crest parse error: ${e.javaClass.simpleName} line=${e.location?.lineNumber} column=${e.location?.columnNumber}"
                    }
                    svgReject(if (rootClosed) SvgRejection.UNSUPPORTED_FORMAT else SvgRejection.UNDECODABLE)
                }
            when (event) {
                XMLStreamConstants.START_ELEMENT -> {
                    depth++
                    if (depth > SvgCrestPolicy.MAX_DEPTH) svgReject(SvgRejection.TOO_COMPLEX)
                    elements++
                    if (elements > SvgCrestPolicy.MAX_ELEMENTS) svgReject(SvgRejection.TOO_COMPLEX)
                    val attributeCount = reader.attributeCount
                    val namespace = reader.namespaceURI.orEmpty()
                    val local = reader.localName
                    when {
                        root == null -> {
                            if (attributeCount > SvgCrestPolicy.MAX_ATTRIBUTES_PER_ELEMENT) svgReject(SvgRejection.TOO_COMPLEX)
                            if (namespace != SvgCrestPolicy.SVG_NS || local != "svg") svgReject(SvgRejection.UNSUPPORTED_FORMAT)
                            root =
                                SvgNode(
                                    name = "svg",
                                    attributes = attributes.collect(element = "svg", isRoot = true, raw = rawAttributes(reader)),
                                )
                            stack.addLast(root)
                        }
                        skipDepth != -1 ->
                            if (attributeCount > SvgCrestPolicy.MAX_SKIPPED_ATTRIBUTES_PER_ELEMENT) svgReject(SvgRejection.TOO_COMPLEX)
                        else ->
                            when (classify(namespace = namespace, local = local)) {
                                Kind.SKIP -> {
                                    // Dropped wholesale (e.g. sodipodi:namedview): only a generous cap, nothing is serialized.
                                    if (attributeCount >
                                        SvgCrestPolicy.MAX_SKIPPED_ATTRIBUTES_PER_ELEMENT
                                    ) {
                                        svgReject(SvgRejection.TOO_COMPLEX)
                                    }
                                    skipDepth = depth
                                }
                                Kind.KEEP -> {
                                    if (attributeCount > SvgCrestPolicy.MAX_ATTRIBUTES_PER_ELEMENT) svgReject(SvgRejection.TOO_COMPLEX)
                                    val parent = stack.last()
                                    if (local !in SvgCrestPolicy.ALLOWED_CHILDREN[parent.name].orEmpty()) {
                                        svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                                    }
                                    val node =
                                        SvgNode(
                                            name = local,
                                            attributes = attributes.collect(element = local, isRoot = false, raw = rawAttributes(reader)),
                                        )
                                    parent.children += node
                                    stack.addLast(node)
                                }
                            }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (skipDepth == depth) {
                        skipDepth = -1
                    } else if (skipDepth == -1) {
                        stack.removeLast()
                    }
                    depth--
                    if (depth == 0) rootClosed = true
                }
                XMLStreamConstants.CDATA ->
                    if (!reader.text.isXmlWhitespace()) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                XMLStreamConstants.CHARACTERS ->
                    if (skipDepth == -1 && stack.isNotEmpty() && !reader.text.isXmlWhitespace()) {
                        svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                    }
                XMLStreamConstants.DTD,
                XMLStreamConstants.ENTITY_REFERENCE,
                XMLStreamConstants.ENTITY_DECLARATION,
                XMLStreamConstants.NOTATION_DECLARATION,
                XMLStreamConstants.PROCESSING_INSTRUCTION,
                -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                XMLStreamConstants.END_DOCUMENT -> return root ?: svgReject(SvgRejection.UNSUPPORTED_FORMAT)
                else -> Unit // START_DOCUMENT, COMMENT, SPACE, ...
            }
        }
    }

    private enum class Kind { KEEP, SKIP }

    private fun classify(
        namespace: String,
        local: String,
    ): Kind {
        if (namespace == SvgCrestPolicy.SVG_NS) {
            return when {
                local in SvgCrestPolicy.SCRIPT_ELEMENTS -> svgReject(SvgRejection.SCRIPT)
                local in SvgCrestPolicy.EXTERNAL_ELEMENTS -> svgReject(SvgRejection.EXTERNAL_REFERENCE)
                local in SvgCrestPolicy.TEXT_ELEMENTS -> svgReject(SvgRejection.TEXT_NOT_SUPPORTED)
                local in SvgCrestPolicy.DROPPED_SVG_ELEMENTS -> Kind.SKIP
                local != "svg" && local in SvgCrestPolicy.ALLOWED_ELEMENTS -> Kind.KEEP
                else -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            }
        }
        if (namespace in SvgCrestPolicy.REJECTED_NAMESPACES) {
            if (namespace == SvgCrestPolicy.XHTML_NS && local == "script") svgReject(SvgRejection.SCRIPT)
            svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        }
        return Kind.SKIP // editor metadata of a foreign namespace: dropped, never serialized
    }

    private fun rawAttributes(reader: XMLStreamReader): List<SvgRawAttribute> =
        (0 until reader.attributeCount).map { index ->
            SvgRawAttribute(
                namespace = reader.getAttributeNamespace(index),
                localName = reader.getAttributeLocalName(index),
                value = reader.getAttributeValue(index),
            )
        }

    private fun String.isXmlWhitespace(): Boolean = all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
}
