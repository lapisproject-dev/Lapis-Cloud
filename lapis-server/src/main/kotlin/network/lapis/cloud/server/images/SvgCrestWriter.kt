package network.lapis.cloud.server.images

/**
 * Welle V1.9.21 -- deterministic serialization of the sanitized tree. The original document is never
 * written; attributes come out in the fixed [SvgCrestPolicy.ATTRIBUTE_ORDER]. The output is a subset of
 * the accepted input grammar and every normalization is a fixed point, so sanitizing the output again
 * yields the identical bytes.
 */
internal object SvgCrestWriter {
    private const val MIN_INTRINSIC_EDGE = 256.0

    fun write(
        root: SvgNode,
        viewBox: SvgViewBox,
    ): ByteArray {
        val builder = StringBuilder()
        val hasHref = hasHref(root)
        builder.append("<svg xmlns=\"").append(SvgCrestPolicy.SVG_NS).append('"')
        if (hasHref) builder.append(" xmlns:xlink=\"").append(SvgCrestPolicy.XLINK_NS).append('"')
        val rootAttributes = root.attributes.toMutableMap()
        rootAttributes["viewBox"] = SvgCrestAttributes.viewBoxText(viewBox)
        // Intrinsic size: long edge at least MIN_INTRINSIC_EDGE, aspect ratio kept.
        val longEdge = maxOf(viewBox.width, viewBox.height)
        val scale = if (longEdge < MIN_INTRINSIC_EDGE) MIN_INTRINSIC_EDGE / longEdge else 1.0
        rootAttributes["width"] = SvgCrestAttributes.formatNumber(viewBox.width * scale)
        rootAttributes["height"] = SvgCrestAttributes.formatNumber(viewBox.height * scale)
        appendAttributes(builder = builder, attributes = rootAttributes)
        if (root.children.isEmpty()) {
            builder.append("/>")
        } else {
            builder.append(">\n")
            root.children.forEach { writeNode(builder = builder, node = it) }
            builder.append("</svg>")
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private fun writeNode(
        builder: StringBuilder,
        node: SvgNode,
    ) {
        builder.append('<').append(node.name)
        appendAttributes(builder = builder, attributes = node.attributes)
        if (node.children.isEmpty()) {
            builder.append("/>")
        } else {
            builder.append('>')
            node.children.forEach { writeNode(builder = builder, node = it) }
            builder.append("</").append(node.name).append('>')
        }
    }

    private fun appendAttributes(
        builder: StringBuilder,
        attributes: Map<String, String>,
    ) {
        val ordered =
            attributes.keys.sortedWith(
                compareBy<String> {
                    SvgCrestPolicy.ATTRIBUTE_ORDER
                        .indexOf(it)
                        .let { index -> if (index < 0) Int.MAX_VALUE else index }
                }.thenBy { it },
            )
        for (name in ordered) {
            builder
                .append(' ')
                .append(if (name == "href") "xlink:href" else name)
                .append("=\"")
                .append(escape(attributes.getValue(name)))
                .append('"')
        }
    }

    private fun hasHref(node: SvgNode): Boolean = "href" in node.attributes || node.children.any { hasHref(it) }

    private fun escape(value: String): String =
        buildString(value.length) {
            for (c in value) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
}
