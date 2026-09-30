package network.lapis.cloud.server.images

/**
 * Welle V1.9.21 -- cross-reference validation of the sanitized tree: unique IDs, typed targets for
 * every `url(#id)` / `href`, bounded `use` amplification and gradient `href` chains. Targets are looked
 * up among KEPT elements only, so a reference to a removed element is a rejection, never a dangling use.
 */
internal object SvgCrestReferences {
    private val URL_ID = Regex("^url\\(#([A-Za-z_][A-Za-z0-9_.-]*)\\)$")
    private val USE_TARGETS: Set<String> = SvgCrestPolicy.SHAPE_ELEMENTS + "g"

    fun validate(root: SvgNode) {
        val byId = linkedMapOf<String, SvgNode>()
        val parents = java.util.IdentityHashMap<SvgNode, SvgNode?>()
        var total = 0
        val uses = mutableListOf<SvgNode>()

        fun walk(
            node: SvgNode,
            parent: SvgNode?,
        ) {
            total++
            parents[node] = parent
            node.attributes["id"]?.let { if (byId.put(it, node) != null) svgReject(SvgRejection.UNSUPPORTED_CONTENT) }
            if (node.name == "use") uses += node
            node.children.forEach { walk(it, node) }
        }
        walk(root, null)

        for (node in parents.keys) {
            checkPaint(node = node, attribute = "fill", byId = byId, allowedTargets = SvgCrestPolicy.GRADIENT_ELEMENTS)
            checkPaint(node = node, attribute = "stroke", byId = byId, allowedTargets = SvgCrestPolicy.GRADIENT_ELEMENTS)
            checkPaint(node = node, attribute = "clip-path", byId = byId, allowedTargets = setOf("clipPath"))
            if (node.name in SvgCrestPolicy.GRADIENT_ELEMENTS) checkGradientChain(start = node, byId = byId)
        }

        if (uses.size > SvgCrestPolicy.MAX_USE_ELEMENTS) svgReject(SvgRejection.TOO_COMPLEX)
        var expanded = total
        for (use in uses) {
            val target = byId[use.attributes["href"]?.removePrefix("#")] ?: svgReject(SvgRejection.EXTERNAL_REFERENCE)
            if (target.name == "use") svgReject(SvgRejection.TOO_COMPLEX)
            if (target.name !in USE_TARGETS) svgReject(SvgRejection.EXTERNAL_REFERENCE)
            if (containsUse(target)) svgReject(SvgRejection.TOO_COMPLEX)
            var ancestor = parents[use]
            while (ancestor != null) {
                if (ancestor === target) svgReject(SvgRejection.TOO_COMPLEX)
                ancestor = parents[ancestor]
            }
            expanded += subtreeSize(target)
            if (expanded > SvgCrestPolicy.MAX_EXPANDED_ELEMENTS) svgReject(SvgRejection.TOO_COMPLEX)
        }
    }

    private fun checkPaint(
        node: SvgNode,
        attribute: String,
        byId: Map<String, SvgNode>,
        allowedTargets: Set<String>,
    ) {
        val value = node.attributes[attribute] ?: return
        val id = URL_ID.matchEntire(value)?.groupValues?.get(1) ?: return
        val target = byId[id] ?: svgReject(SvgRejection.EXTERNAL_REFERENCE)
        if (target.name !in allowedTargets) svgReject(SvgRejection.EXTERNAL_REFERENCE)
    }

    private fun checkGradientChain(
        start: SvgNode,
        byId: Map<String, SvgNode>,
    ) {
        val visited = java.util.IdentityHashMap<SvgNode, Boolean>()
        var current: SvgNode? = start
        while (current != null) {
            if (visited.put(current, true) != null) svgReject(SvgRejection.TOO_COMPLEX)
            if (visited.size > SvgCrestPolicy.MAX_GRADIENT_HREF_CHAIN) svgReject(SvgRejection.TOO_COMPLEX)
            val href = current.attributes["href"] ?: return
            val target = byId[href.removePrefix("#")] ?: svgReject(SvgRejection.EXTERNAL_REFERENCE)
            if (target.name !in SvgCrestPolicy.GRADIENT_ELEMENTS) svgReject(SvgRejection.EXTERNAL_REFERENCE)
            current = target
        }
    }

    private fun containsUse(node: SvgNode): Boolean = node.children.any { it.name == "use" || containsUse(it) }

    private fun subtreeSize(node: SvgNode): Int = 1 + node.children.sumOf { subtreeSize(it) }
}
