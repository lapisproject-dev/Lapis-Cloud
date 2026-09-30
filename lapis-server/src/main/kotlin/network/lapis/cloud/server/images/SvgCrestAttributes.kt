package network.lapis.cloud.server.images

import network.lapis.cloud.server.images.SvgCrestPolicy.MAX_ABS_NUMBER
import java.math.BigDecimal
import kotlin.math.abs

/** Thrown inside the sanitizer pipeline only; [SvgCrestSanitizer.sanitize] turns it into `Rejected`. */
internal class SvgRejectedException(
    val reason: SvgRejection,
) : RuntimeException(reason.name, null, false, false)

internal fun svgReject(reason: SvgRejection): Nothing = throw SvgRejectedException(reason)

/** One attribute as read from the parser: namespace URI (null/empty = none), local name, value. */
internal class SvgRawAttribute(
    val namespace: String?,
    val localName: String,
    val value: String,
)

/**
 * Welle V1.9.21 -- attribute allowlist and value grammars. Every regex is linear and anchored (no
 * nested quantifiers), and the length ceiling is checked BEFORE any regex runs. One instance per
 * document: it tracks the total path-data budget.
 */
internal class SvgCrestAttributes {
    private var totalPathChars = 0

    /**
     * Returns the final (sanitized, canonical) attributes of one kept element, keyed by output name
     * (`href` for both `href` and `xlink:href`). Rejects on anything outside the allowlist.
     */
    fun collect(
        element: String,
        isRoot: Boolean,
        raw: List<SvgRawAttribute>,
    ): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        var style: Map<String, String> = emptyMap()
        for (attribute in raw) {
            val local = attribute.localName
            if (local.lowercase().startsWith("on")) svgReject(SvgRejection.SCRIPT)
            when (attribute.namespace.orEmpty()) {
                "" ->
                    when (local) {
                        "style" -> style = convertStyle(attribute.value)
                        "class" -> Unit
                        "href" -> putHref(out = out, element = element, value = attribute.value)
                        else -> plainAttribute(out = out, element = element, isRoot = isRoot, name = local, value = attribute.value)
                    }
                SvgCrestPolicy.XLINK_NS ->
                    if (local ==
                        "href"
                    ) {
                        putHref(out = out, element = element, value = attribute.value)
                    } else {
                        svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                    }
                SvgCrestPolicy.XML_NS ->
                    when (local) {
                        "space", "lang" -> Unit
                        "base" -> svgReject(SvgRejection.EXTERNAL_REFERENCE)
                        else -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                    }
                else -> Unit // sodipodi:*, inkscape:*, other foreign namespaces: editor ballast, dropped
            }
        }
        // `style` overrides a presentation attribute of the same property (CSS cascade: attributes have specificity 0).
        for ((name, value) in style) out[name] = value
        return out
    }

    private fun putHref(
        out: MutableMap<String, String>,
        element: String,
        value: String,
    ) {
        if (element !in SvgCrestPolicy.HREF_ELEMENTS) svgReject(SvgRejection.EXTERNAL_REFERENCE)
        screen(value)
        if (value.length > SvgCrestPolicy.MAX_ID_LENGTH + 1 || !HREF_REGEX.matches(value)) svgReject(SvgRejection.EXTERNAL_REFERENCE)
        val previous = out.put("href", value)
        if (previous != null && previous != value) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
    }

    private fun plainAttribute(
        out: MutableMap<String, String>,
        element: String,
        isRoot: Boolean,
        name: String,
        value: String,
    ) {
        if (isRoot && (name == "version" || name == "baseProfile")) return
        if (name in SvgCrestPolicy.IGNORABLE_STYLE_PROPERTIES) return
        SvgCrestPolicy.IGNORABLE_WHEN_NEUTRAL[name]?.let { neutral ->
            if (normalizeKeyword(value) !in neutral) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            return
        }
        if (isRoot && (name == "width" || name == "height")) {
            // Recomputed from the viewBox by the writer, so the original (often "210mm" from editors) is only screened.
            if (value.length > SvgCrestPolicy.MAX_PLAIN_ATTRIBUTE_CHARS) svgReject(SvgRejection.TOO_COMPLEX)
            screen(value)
            return
        }
        val grammar: SvgValueGrammar =
            when {
                isRoot && name == "viewBox" -> SvgValueGrammar.ViewBox
                isRoot && name == "preserveAspectRatio" -> SvgValueGrammar.PreserveAspectRatio
                name == "d" && element != "path" -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                name == "points" && element != "polyline" && element != "polygon" -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                else ->
                    SvgCrestPolicy.GEOMETRY_ATTRIBUTES[name]
                        ?: SvgCrestPolicy.GRADIENT_ATTRIBUTES[name]
                        ?: SvgCrestPolicy.PRESENTATION_PROPERTIES[name]
                        ?: svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            }
        if (out.put(name, validate(grammar = grammar, value = value, fromStyle = false)) !=
            null
        ) {
            svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        }
    }

    /** Converts a `style` attribute into presentation attributes; the `style` attribute itself is never written. */
    fun convertStyle(value: String): Map<String, String> {
        if (value.length > SvgCrestPolicy.MAX_PLAIN_ATTRIBUTE_CHARS) svgReject(SvgRejection.TOO_COMPLEX)
        screen(value)
        val lower = value.lowercase()
        if ('@' in value || "/*" in value || "expression" in lower) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        val result = linkedMapOf<String, String>()
        for (declaration in value.split(';')) {
            if (declaration.isBlank()) continue
            val colon = declaration.indexOf(':')
            if (colon < 0) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            val property = declaration.substring(0, colon).trim().lowercase()
            val propertyValue = declaration.substring(colon + 1).trim()
            val grammar = SvgCrestPolicy.PRESENTATION_PROPERTIES[property]
            when {
                grammar != null -> {
                    if (result.put(property, validate(grammar = grammar, value = propertyValue, fromStyle = true)) != null) {
                        svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                    }
                }
                property in SvgCrestPolicy.IGNORABLE_STYLE_PROPERTIES -> Unit
                property in SvgCrestPolicy.IGNORABLE_WHEN_NEUTRAL -> {
                    if (normalizeKeyword(propertyValue) !in SvgCrestPolicy.IGNORABLE_WHEN_NEUTRAL.getValue(property)) {
                        svgReject(SvgRejection.UNSUPPORTED_CONTENT)
                    }
                }
                SvgCrestPolicy.IGNORABLE_STYLE_PROPERTY_PREFIXES.any { property.startsWith(it) } -> Unit
                else -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            }
        }
        return result
    }

    private fun normalizeKeyword(value: String): String = value.trim().lowercase().replace(WHITESPACE_RUN, " ")

    /** Defense in depth before any grammar: dangerous schemes and external `url(...)` are classified first. */
    private fun screen(value: String) {
        val normalized = value.filter { !it.isWhitespace() && !it.isISOControl() }.lowercase()
        if ("javascript:" in normalized || "vbscript:" in normalized || "data:" in normalized) {
            svgReject(SvgRejection.EXTERNAL_REFERENCE)
        }
        if ("@import" in normalized || "expression(" in normalized || '\\' in normalized) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        var from = normalized.indexOf("url(")
        while (from >= 0) {
            var next = from + "url(".length
            if (next < normalized.length && (normalized[next] == '\'' || normalized[next] == '"')) next++
            if (next >= normalized.length || normalized[next] != '#') svgReject(SvgRejection.EXTERNAL_REFERENCE)
            from = normalized.indexOf("url(", from + 1)
        }
    }

    internal fun validate(
        grammar: SvgValueGrammar,
        value: String,
        fromStyle: Boolean,
    ): String {
        val isPath = grammar == SvgValueGrammar.PathData || grammar == SvgValueGrammar.Points
        val limit = if (isPath) SvgCrestPolicy.MAX_PATH_DATA_CHARS else SvgCrestPolicy.MAX_PLAIN_ATTRIBUTE_CHARS
        if (value.length > limit) svgReject(SvgRejection.TOO_COMPLEX)
        if (!isPath) screen(value)
        return when (grammar) {
            SvgValueGrammar.Number -> number(value.trim()).second
            SvgValueGrammar.Length -> length(value = value, allowPercent = false)
            SvgValueGrammar.LengthPercent -> length(value = value, allowPercent = !fromStyle)
            SvgValueGrammar.Opacity, SvgValueGrammar.Offset -> fraction(value = value, allowPercent = !fromStyle)
            SvgValueGrammar.Paint -> paint(value = value, allowUrl = true)
            SvgValueGrammar.Color -> paint(value = value, allowUrl = false)
            SvgValueGrammar.ClipRef -> clipRef(value)
            SvgValueGrammar.PathData -> pathData(value = value, charset = CHARSET_PATH)
            SvgValueGrammar.Points -> pathData(value = value, charset = CHARSET_POINTS)
            SvgValueGrammar.Transform -> transform(value)
            SvgValueGrammar.DashArray -> dashArray(value)
            SvgValueGrammar.ViewBox -> viewBoxText(parseViewBox(value))
            SvgValueGrammar.PreserveAspectRatio ->
                value.trim().also { if (!PAR_REGEX.matches(it)) svgReject(SvgRejection.UNSUPPORTED_CONTENT) }
            SvgValueGrammar.Id -> id(value)
            is SvgValueGrammar.Enum ->
                value.trim().also { if (it !in grammar.values) svgReject(SvgRejection.UNSUPPORTED_CONTENT) }
        }
    }

    private fun number(text: String): Pair<Double, String> {
        if (!NUMBER_REGEX.matches(text)) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        val number = text.toDouble()
        if (!number.isFinite() || abs(number) > MAX_ABS_NUMBER) svgReject(SvgRejection.TOO_COMPLEX)
        return number to text
    }

    private fun length(
        value: String,
        allowPercent: Boolean,
    ): String {
        var text = value.trim()
        val percent = allowPercent && text.endsWith("%")
        text = if (percent) text.dropLast(1) else text.removeSuffix("px")
        return number(text).second + if (percent) "%" else ""
    }

    private fun fraction(
        value: String,
        allowPercent: Boolean,
    ): String {
        var text = value.trim()
        val percent = allowPercent && text.endsWith("%")
        if (percent) text = text.dropLast(1)
        val (number, normalized) = number(text)
        if (number < 0.0 || number > (if (percent) 100.0 else 1.0)) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        return normalized + if (percent) "%" else ""
    }

    private fun paint(
        value: String,
        allowUrl: Boolean,
    ): String {
        val text = value.trim()
        val lower = text.lowercase()
        return when {
            lower in SvgCrestPolicy.COLOR_KEYWORDS -> lower
            HEX_COLOR_REGEX.matches(text) || RGB_COLOR_REGEX.matches(text) -> text
            allowUrl -> urlReference(text)
            else -> svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        }
    }

    private fun clipRef(value: String): String {
        val text = value.trim()
        return if (text == "none") text else urlReference(text)
    }

    private fun urlReference(text: String): String {
        val match = URL_REGEX.matchEntire(text) ?: svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        return "url(#${match.groupValues[2]})"
    }

    private fun pathData(
        value: String,
        charset: Regex,
    ): String {
        if (!charset.matches(value)) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        for (token in NUMBER_TOKEN_REGEX.findAll(value)) {
            val number = token.value.toDouble()
            if (!number.isFinite() || abs(number) > MAX_ABS_NUMBER) svgReject(SvgRejection.TOO_COMPLEX)
        }
        val normalized = value.trim().replace(WHITESPACE_RUN, " ")
        totalPathChars += normalized.length
        if (totalPathChars > SvgCrestPolicy.MAX_TOTAL_PATH_DATA_CHARS) svgReject(SvgRejection.TOO_COMPLEX)
        return normalized
    }

    private fun transform(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        val parts = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val match = TRANSFORM_FUNCTION_REGEX.find(text, index)
            if (match == null || match.range.first != index) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            val name = match.groupValues[1]
            val arguments =
                match.groupValues[2]
                    .trim()
                    .split(ARGUMENT_SEPARATOR)
                    .filter { it.isNotEmpty() }
            val valid =
                when (name) {
                    "matrix" -> arguments.size == 6
                    "translate", "scale" -> arguments.size in 1..2
                    "rotate" -> arguments.size == 1 || arguments.size == 3
                    else -> arguments.size == 1
                }
            if (!valid) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            parts += "$name(${arguments.joinToString(" ") { number(it).second }})"
            index = match.range.last + 1
            while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++
        }
        return parts.joinToString(" ")
    }

    private fun dashArray(value: String): String {
        val text = value.trim()
        if (text == "none") return text
        val items = text.split(ARGUMENT_SEPARATOR).filter { it.isNotEmpty() }
        if (items.isEmpty()) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        return items.joinToString(" ") {
            val (number, normalized) = number(it.removeSuffix("px"))
            if (number < 0.0) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            normalized
        }
    }

    private fun id(value: String): String {
        if (value.length > SvgCrestPolicy.MAX_ID_LENGTH) svgReject(SvgRejection.TOO_COMPLEX)
        if (!ID_REGEX.matches(value)) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
        return value
    }

    companion object {
        private const val NUM = "[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,4})?"
        private val NUMBER_REGEX = Regex("^$NUM$")
        private val NUMBER_TOKEN_REGEX = Regex("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private val CHARSET_PATH = Regex("^[MmZzLlHhVvCcSsQqTtAa0-9eE+\\-., \\t\\n\\r]*$")
        private val CHARSET_POINTS = Regex("^[0-9eE+\\-., \\t\\n\\r]*$")
        private val WHITESPACE_RUN = Regex("[ \\t\\n\\r]+")
        private val ARGUMENT_SEPARATOR = Regex("[\\s,]+")
        private val ID_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")
        private val HREF_REGEX = Regex("^#[A-Za-z_][A-Za-z0-9_.-]{0,63}$")
        private val HEX_COLOR_REGEX = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
        private const val RGB_PART = "[0-9]{1,3}(?:\\.[0-9]+)?%?"
        private val RGB_COLOR_REGEX =
            Regex("^rgba?\\(\\s*$RGB_PART\\s*,\\s*$RGB_PART\\s*,\\s*$RGB_PART\\s*(?:,\\s*[0-9.]{1,6}%?\\s*)?\\)$")
        private val URL_REGEX = Regex("^url\\(\\s*(['\"]?)#([A-Za-z_][A-Za-z0-9_.-]{0,63})\\1\\s*\\)$")
        private val PAR_REGEX = Regex("^(?:none|x(?:Min|Mid|Max)Y(?:Min|Mid|Max))(?: (?:meet|slice))?$")
        private val TRANSFORM_FUNCTION_REGEX = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^()]*)\\)")

        /** Parses and range-checks a `viewBox` value; every violation of the size rules is DIMENSIONS_TOO_LARGE. */
        fun parseViewBox(value: String): SvgViewBox {
            if (value.length > SvgCrestPolicy.MAX_PLAIN_ATTRIBUTE_CHARS) svgReject(SvgRejection.TOO_COMPLEX)
            val parts = value.trim().split(ARGUMENT_SEPARATOR)
            if (parts.size != 4 || parts.any { !NUMBER_REGEX.matches(it) }) svgReject(SvgRejection.UNSUPPORTED_CONTENT)
            val numbers = parts.map { it.toDouble() }
            if (numbers.any { !it.isFinite() }) svgReject(SvgRejection.DIMENSIONS_TOO_LARGE)
            val (minX, minY, width, height) = numbers
            val tooLarge =
                abs(minX) > MAX_ABS_NUMBER ||
                    abs(minY) > MAX_ABS_NUMBER ||
                    width <= 0.0 ||
                    height <= 0.0 ||
                    width > SvgCrestPolicy.MAX_VIEWBOX_EDGE ||
                    height > SvgCrestPolicy.MAX_VIEWBOX_EDGE ||
                    width / height > SvgCrestPolicy.MAX_ASPECT_RATIO ||
                    height / width > SvgCrestPolicy.MAX_ASPECT_RATIO
            if (tooLarge) svgReject(SvgRejection.DIMENSIONS_TOO_LARGE)
            return SvgViewBox(minX = minX, minY = minY, width = width, height = height)
        }

        fun viewBoxText(viewBox: SvgViewBox): String =
            listOf(viewBox.minX, viewBox.minY, viewBox.width, viewBox.height).joinToString(" ") { formatNumber(it) }

        /** Canonical number text: integers without a fraction, everything else as plain decimal without exponent. */
        fun formatNumber(value: Double): String =
            if (value == Math.rint(value) && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            }
    }
}
