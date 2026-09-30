package network.lapis.cloud.server.images

/** Value grammar of one allowed SVG attribute or CSS property (see [SvgCrestAttributes]). */
internal sealed interface SvgValueGrammar {
    data object Number : SvgValueGrammar

    /** A number with an optional `px` unit; `%` is NOT allowed (style context and unit-less geometry). */
    data object Length : SvgValueGrammar

    /** Like [Length], but a `%` suffix is allowed as an ATTRIBUTE value (never inside `style`). */
    data object LengthPercent : SvgValueGrammar

    data object Opacity : SvgValueGrammar

    data object Offset : SvgValueGrammar

    data object Paint : SvgValueGrammar

    /** A colour only -- no `url(...)` (used by `stop-color`). */
    data object Color : SvgValueGrammar

    /** `none` or `url(#id)` -- `clip-path`. */
    data object ClipRef : SvgValueGrammar

    data object PathData : SvgValueGrammar

    data object Points : SvgValueGrammar

    data object Transform : SvgValueGrammar

    data object DashArray : SvgValueGrammar

    data object ViewBox : SvgValueGrammar

    data object PreserveAspectRatio : SvgValueGrammar

    data object Id : SvgValueGrammar

    data class Enum(
        val values: Set<String>,
    ) : SvgValueGrammar
}

/**
 * Welle V1.9.21 -- the single source of truth of the SVG crest sanitizer: limits and allowlists.
 * Code, tests and the architecture document's tables all derive from here. Everything is an
 * ALLOWLIST: an element or attribute that is not listed is rejected, never "repaired".
 */
internal object SvgCrestPolicy {
    const val SVG_NS = "http://www.w3.org/2000/svg"
    const val XLINK_NS = "http://www.w3.org/1999/xlink"
    const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    const val XHTML_NS = "http://www.w3.org/1999/xhtml"
    const val MATHML_NS = "http://www.w3.org/1998/Math/MathML"

    const val MAX_INPUT_BYTES = 262_144
    const val MAX_OUTPUT_BYTES = 131_072
    const val MAX_ELEMENTS = 2_000
    const val MAX_ATTRIBUTES_PER_ELEMENT = 20
    const val MAX_SKIPPED_ATTRIBUTES_PER_ELEMENT = 100
    const val MAX_DEPTH = 24
    const val MAX_PATH_DATA_CHARS = 65_536
    const val MAX_TOTAL_PATH_DATA_CHARS = 65_536
    const val MAX_PLAIN_ATTRIBUTE_CHARS = 1_024
    const val MAX_ID_LENGTH = 64
    const val MAX_ABS_NUMBER = 1e6
    const val MAX_VIEWBOX_EDGE = 1e5
    const val MAX_ASPECT_RATIO = 4.0
    const val MAX_USE_ELEMENTS = 32
    const val MAX_GRADIENT_HREF_CHAIN = 4
    const val MAX_EXPANDED_ELEMENTS = 10_000

    val ALLOWED_ELEMENTS: Set<String> =
        setOf(
            "svg",
            "g",
            "path",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polyline",
            "polygon",
            "defs",
            "linearGradient",
            "radialGradient",
            "stop",
            "clipPath",
            "use",
        )

    /** Whole subtree silently dropped (never serialized). */
    val DROPPED_SVG_ELEMENTS: Set<String> = setOf("title", "desc", "metadata")

    /** Namespaces of editor ballast whose elements and attributes are dropped silently. */
    val DROPPED_FOREIGN_NAMESPACES: Set<String> =
        setOf(
            "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd",
            "http://www.inkscape.org/namespaces/inkscape",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://creativecommons.org/ns#",
            "http://purl.org/dc/elements/1.1/",
            "http://ns.adobe.com/AdobeIllustrator/10.0/",
            "http://ns.adobe.com/Extensibility/1.0/",
            "http://ns.adobe.com/AdobeSVGViewerExtensions/3.0/",
            "http://ns.adobe.com/Flows/1.0/",
        )

    /** Namespaces (and the empty one) an element must never live in; `script` there is classified as SCRIPT. */
    val REJECTED_NAMESPACES: Set<String> = setOf(XHTML_NS, MATHML_NS, XLINK_NS, XML_NS, "")

    val SCRIPT_ELEMENTS: Set<String> =
        setOf(
            "script",
            "animate",
            "animateTransform",
            "animateMotion",
            "animateColor",
            "set",
            "foreignObject",
            "iframe",
            "object",
            "embed",
            "a",
            "handler",
            "listener",
            "discard",
        )

    val EXTERNAL_ELEMENTS: Set<String> = setOf("image", "feImage")

    val TEXT_ELEMENTS: Set<String> =
        setOf(
            "text",
            "tspan",
            "textPath",
            "tref",
            "altGlyph",
            "font",
            "font-face",
            "font-face-src",
            "font-face-uri",
            "font-face-format",
            "font-face-name",
            "glyph",
            "missing-glyph",
        )

    val SHAPE_ELEMENTS: Set<String> = setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")
    val GRADIENT_ELEMENTS: Set<String> = setOf("linearGradient", "radialGradient")
    private val CONTAINER_CHILDREN: Set<String> = SHAPE_ELEMENTS + GRADIENT_ELEMENTS + setOf("g", "defs", "clipPath", "use")

    /** Allowed children per parent element. A parent not listed here has no children. */
    val ALLOWED_CHILDREN: Map<String, Set<String>> =
        mapOf(
            "svg" to CONTAINER_CHILDREN,
            "g" to CONTAINER_CHILDREN,
            "defs" to CONTAINER_CHILDREN,
            "linearGradient" to setOf("stop"),
            "radialGradient" to setOf("stop"),
            "clipPath" to SHAPE_ELEMENTS,
        )

    /** Prefix of editor-only `style` properties that are dropped silently. */
    val IGNORABLE_STYLE_PROPERTY_PREFIXES: Set<String> = setOf("-inkscape-")

    /**
     * Rendering-only properties written by common editors (Illustrator, Inkscape 0.92). They carry no risk
     * and no reference; they are dropped silently and never serialized (allowlist output stays intact).
     */
    val IGNORABLE_STYLE_PROPERTIES: Set<String> =
        setOf(
            "enable-background",
            "isolation",
            "marker",
            "solid-color",
            "solid-opacity",
            "color-interpolation",
            "color-interpolation-filters",
            "color-rendering",
            "shape-rendering",
            "image-rendering",
            "text-rendering",
            "overflow",
        )

    /** Dropped silently only with one of these neutral values; any other value changes the rendering and is rejected. */
    val IGNORABLE_WHEN_NEUTRAL: Map<String, Set<String>> =
        mapOf(
            "mix-blend-mode" to setOf("normal"),
            "paint-order" to setOf("normal", "fill", "fill stroke", "fill stroke markers"),
            "vector-effect" to setOf("none"),
        )

    val PRESENTATION_PROPERTIES: Map<String, SvgValueGrammar> =
        mapOf(
            "fill" to SvgValueGrammar.Paint,
            "stroke" to SvgValueGrammar.Paint,
            "stroke-width" to SvgValueGrammar.Length,
            "stroke-opacity" to SvgValueGrammar.Opacity,
            "fill-opacity" to SvgValueGrammar.Opacity,
            "opacity" to SvgValueGrammar.Opacity,
            "fill-rule" to SvgValueGrammar.Enum(setOf("nonzero", "evenodd")),
            "clip-rule" to SvgValueGrammar.Enum(setOf("nonzero", "evenodd")),
            "stroke-linecap" to SvgValueGrammar.Enum(setOf("butt", "round", "square")),
            "stroke-linejoin" to SvgValueGrammar.Enum(setOf("miter", "round", "bevel")),
            "stroke-miterlimit" to SvgValueGrammar.Number,
            "stroke-dasharray" to SvgValueGrammar.DashArray,
            "stroke-dashoffset" to SvgValueGrammar.Length,
            "stop-color" to SvgValueGrammar.Color,
            // Resolves `currentColor`, so it must keep its value rather than be dropped.
            "color" to SvgValueGrammar.Color,
            "stop-opacity" to SvgValueGrammar.Opacity,
            "display" to SvgValueGrammar.Enum(setOf("none", "inline")),
            "visibility" to SvgValueGrammar.Enum(setOf("visible", "hidden", "collapse")),
            "clip-path" to SvgValueGrammar.ClipRef,
        )

    val GEOMETRY_ATTRIBUTES: Map<String, SvgValueGrammar> =
        mapOf(
            "x" to SvgValueGrammar.LengthPercent,
            "y" to SvgValueGrammar.LengthPercent,
            "width" to SvgValueGrammar.LengthPercent,
            "height" to SvgValueGrammar.LengthPercent,
            "cx" to SvgValueGrammar.LengthPercent,
            "cy" to SvgValueGrammar.LengthPercent,
            "r" to SvgValueGrammar.LengthPercent,
            "rx" to SvgValueGrammar.LengthPercent,
            "ry" to SvgValueGrammar.LengthPercent,
            "x1" to SvgValueGrammar.LengthPercent,
            "y1" to SvgValueGrammar.LengthPercent,
            "x2" to SvgValueGrammar.LengthPercent,
            "y2" to SvgValueGrammar.LengthPercent,
            "d" to SvgValueGrammar.PathData,
            "points" to SvgValueGrammar.Points,
            "transform" to SvgValueGrammar.Transform,
            "id" to SvgValueGrammar.Id,
        )

    val GRADIENT_ATTRIBUTES: Map<String, SvgValueGrammar> =
        mapOf(
            "fx" to SvgValueGrammar.LengthPercent,
            "fy" to SvgValueGrammar.LengthPercent,
            "fr" to SvgValueGrammar.LengthPercent,
            "offset" to SvgValueGrammar.Offset,
            "gradientTransform" to SvgValueGrammar.Transform,
            "gradientUnits" to SvgValueGrammar.Enum(setOf("userSpaceOnUse", "objectBoundingBox")),
            "clipPathUnits" to SvgValueGrammar.Enum(setOf("userSpaceOnUse", "objectBoundingBox")),
            "spreadMethod" to SvgValueGrammar.Enum(setOf("pad", "reflect", "repeat")),
        )

    /** Root-only attributes (`version` is accepted but never written). */
    val ROOT_ATTRIBUTES: Set<String> = setOf("viewBox", "preserveAspectRatio", "width", "height", "version")

    /** Elements whose `href` is allowed. */
    val HREF_ELEMENTS: Set<String> = setOf("use", "linearGradient", "radialGradient")

    /** Fixed serialization order of attributes (deterministic output). */
    val ATTRIBUTE_ORDER: List<String> =
        listOf(
            "id",
            "viewBox",
            "preserveAspectRatio",
            "width",
            "height",
            "x",
            "y",
            "cx",
            "cy",
            "r",
            "rx",
            "ry",
            "x1",
            "y1",
            "x2",
            "y2",
            "fx",
            "fy",
            "fr",
            "d",
            "points",
            "transform",
            "gradientTransform",
            "gradientUnits",
            "clipPathUnits",
            "spreadMethod",
            "offset",
            "href",
            "fill",
            "fill-opacity",
            "fill-rule",
            "stroke",
            "stroke-width",
            "stroke-opacity",
            "stroke-linecap",
            "stroke-linejoin",
            "stroke-miterlimit",
            "stroke-dasharray",
            "stroke-dashoffset",
            "stop-color",
            "stop-opacity",
            "opacity",
            "clip-path",
            "clip-rule",
            "display",
            "visibility",
        )

    /** The CSS colour keywords accepted by the paint grammar (lower case). */
    val COLOR_KEYWORDS: Set<String> =
        (
            "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown " +
                "burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan " +
                "darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid " +
                "darkred darksalmon darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet " +
                "deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro " +
                "ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki " +
                "lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan lightgoldenrodyellow " +
                "lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray " +
                "lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine " +
                "mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise " +
                "mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab " +
                "orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru " +
                "pink plum powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown " +
                "seagreen seashell sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan " +
                "teal thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen"
        ).split(' ').toSet() + setOf("none", "currentcolor", "transparent")
}
