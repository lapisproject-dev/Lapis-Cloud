package network.lapis.cloud.server.theme

/**
 * Pure structural parser of `theme.json` (no file access): text in, [ThemeSpec] plus findings out. A structure or colour error
 * discards the whole theme ([ThemeAction.DEFAULT]); an error below `fonts` only drops the fonts ([ThemeAction.FONTS_DROPPED]).
 * Messages never repeat file contents beyond bounded key names.
 */
object ThemeParser {
    data class Outcome(
        /** `null` when the theme as a whole is rejected. */
        val spec: ThemeSpec?,
        val findings: List<ThemeFinding>,
    )

    private val TOP_KEYS = setOf("\$schema", "version", "name", "description", "colors", "radius", "fonts")
    private val FONT_ROLE_KEYS = setOf("family", "fallback", "files")
    private val FONT_FILE_KEYS = setOf("file", "weight", "style", "format")

    fun parse(jsonText: String): Outcome {
        val findings = mutableListOf<ThemeFinding>()
        val root =
            when (val result = StrictJson.parse(text = jsonText)) {
                is StrictJson.Result.Err -> {
                    findings += finding(code = result.code, path = result.path, message = result.message)
                    return Outcome(spec = null, findings = findings)
                }
                is StrictJson.Result.Ok -> result.value
            }
        if (root !is JNode.Obj) {
            findings += finding(code = ThemeErrorCode.THEME_TYPE, path = "$", message = "the root value must be an object")
            return Outcome(spec = null, findings = findings)
        }

        root.members.keys.filter { it !in TOP_KEYS }.forEach {
            findings += finding(code = ThemeErrorCode.THEME_UNKNOWN_KEY, path = "$.${safe(it)}", message = "unknown key")
        }
        root.members["\$schema"]?.let {
            if (it !is JNode.Str) {
                findings +=
                    finding(code = ThemeErrorCode.THEME_TYPE, path = "$.\$schema", message = "must be a string")
            }
        }
        checkVersion(root = root, out = findings)
        val name = checkName(root = root, out = findings)
        checkDescription(root = root, out = findings)
        val light = HashMap<String, HexColor>()
        val dark = HashMap<String, HexColor>()
        checkColors(root = root, light = light, dark = dark, out = findings)
        val radius = checkRadius(root = root, out = findings)

        val structuralErrors = findings.any { it.action == ThemeAction.DEFAULT }
        val fontFindings = mutableListOf<ThemeFinding>()
        var body: FontRoleSpec? = null
        var display: FontRoleSpec? = null
        root.members["fonts"]?.let { fonts ->
            val parsed = parseFonts(node = fonts, out = fontFindings)
            if (fontFindings.none { it.action == ThemeAction.FONTS_DROPPED }) {
                body = parsed.first
                display = parsed.second
            }
        }
        findings += fontFindings
        if (structuralErrors || name == null) return Outcome(spec = null, findings = findings)
        return Outcome(
            spec = ThemeSpec(name = name, light = light, dark = dark, radius = radius, body = body, display = display),
            findings = findings,
        )
    }

    private fun checkVersion(
        root: JNode.Obj,
        out: MutableList<ThemeFinding>,
    ) {
        val v = root.members["version"]
        when {
            v == null -> out += finding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$.version", message = "missing key")
            v !is JNode.Num || v.raw != ThemeTokens.SCHEMA_VERSION.toString() ->
                out +=
                    finding(
                        code = ThemeErrorCode.THEME_BAD_VERSION,
                        path = "$.version",
                        message = "version must be the integer ${ThemeTokens.SCHEMA_VERSION}",
                    )
        }
    }

    private fun checkName(
        root: JNode.Obj,
        out: MutableList<ThemeFinding>,
    ): String? {
        val n = root.members["name"]
        if (n == null) {
            out += finding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$.name", message = "missing key")
            return null
        }
        if (n !is JNode.Str || !ThemeTokens.NAME_REGEX.matches(n.value)) {
            out +=
                finding(
                    code = ThemeErrorCode.THEME_BAD_NAME,
                    path = "$.name",
                    message = "name must match ${ThemeTokens.NAME_REGEX.pattern}",
                )
            return null
        }
        return n.value
    }

    private fun checkDescription(
        root: JNode.Obj,
        out: MutableList<ThemeFinding>,
    ) {
        val d = root.members["description"] ?: return
        if (d !is JNode.Str || d.value.length > ThemeTokens.MAX_DESCRIPTION || d.value.any { it < ' ' }) {
            out +=
                finding(
                    code = ThemeErrorCode.THEME_BAD_DESCRIPTION,
                    path = "$.description",
                    message = "description must be a string of at most ${ThemeTokens.MAX_DESCRIPTION} printable characters",
                )
        }
    }

    private fun checkColors(
        root: JNode.Obj,
        light: MutableMap<String, HexColor>,
        dark: MutableMap<String, HexColor>,
        out: MutableList<ThemeFinding>,
    ) {
        val colors = root.members["colors"]
        if (colors == null) {
            out += finding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$.colors", message = "missing key")
            return
        }
        if (colors !is JNode.Obj) {
            out += finding(code = ThemeErrorCode.THEME_TYPE, path = "$.colors", message = "must be an object")
            return
        }
        colors.members.keys.filter { it != "light" && it != "dark" }.forEach {
            out += finding(code = ThemeErrorCode.THEME_UNKNOWN_KEY, path = "$.colors.${safe(it)}", message = "unknown key")
        }
        listOf("light" to light, "dark" to dark).forEach { (mode, target) ->
            val node = colors.members[mode]
            val path = "$.colors.$mode"
            when {
                node == null -> out += finding(code = ThemeErrorCode.THEME_MISSING_KEY, path = path, message = "missing key")
                node !is JNode.Obj -> out += finding(code = ThemeErrorCode.THEME_TYPE, path = path, message = "must be an object")
                else -> {
                    node.members.keys.filter { it !in ThemeTokens.COLOR_KEYS }.forEach {
                        out +=
                            finding(code = ThemeErrorCode.THEME_UNKNOWN_KEY, path = "$path.${safe(it)}", message = "unknown colour token")
                    }
                    ThemeTokens.COLOR_KEYS.forEach { key ->
                        val value = node.members[key]
                        when {
                            value == null ->
                                out +=
                                    finding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$path.$key", message = "missing colour token")
                            value !is JNode.Str ->
                                out +=
                                    finding(code = ThemeErrorCode.THEME_TYPE, path = "$path.$key", message = "must be a string")
                            else -> {
                                val hex = HexColor.parse(value.value)
                                if (hex == null) {
                                    out +=
                                        finding(
                                            code = ThemeErrorCode.THEME_BAD_HEX,
                                            path = "$path.$key",
                                            message = "must be #RGB, #RRGGBB or #RRGGBBAA",
                                        )
                                } else {
                                    target[key] = hex
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkRadius(
        root: JNode.Obj,
        out: MutableList<ThemeFinding>,
    ): RadiusPreset {
        val r = root.members["radius"] ?: return RadiusPreset.DEFAULT
        if (r !is JNode.Str) {
            out += finding(code = ThemeErrorCode.THEME_TYPE, path = "$.radius", message = "must be a string")
            return RadiusPreset.DEFAULT
        }
        val preset = RadiusPreset.fromJson(r.value)
        if (preset ==
            null
        ) {
            out +=
                finding(
                    code = ThemeErrorCode.THEME_BAD_ENUM,
                    path = "$.radius",
                    message = "must be one of ${RadiusPreset.entries.joinToString { it.json }}",
                )
        }
        return preset ?: RadiusPreset.DEFAULT
    }

    private fun parseFonts(
        node: JNode,
        out: MutableList<ThemeFinding>,
    ): Pair<FontRoleSpec?, FontRoleSpec?> {
        if (node !is JNode.Obj) {
            out += fontFinding(code = ThemeErrorCode.THEME_TYPE, path = "$.fonts", message = "must be an object")
            return null to null
        }
        node.members.keys.filter { it != "body" && it != "display" }.forEach {
            out += fontFinding(code = ThemeErrorCode.THEME_UNKNOWN_KEY, path = "$.fonts.${safe(it)}", message = "unknown key")
        }
        val body = node.members["body"]?.let { parseRole(node = it, path = "$.fonts.body", out = out) }
        val display = node.members["display"]?.let { parseRole(node = it, path = "$.fonts.display", out = out) }
        if (out.any { it.action == ThemeAction.FONTS_DROPPED }) return null to null

        val roles = listOfNotNull(body, display)
        val distinct = roles.flatMap { r -> r.files.map { it.file } }.toSet()
        if (distinct.size > ThemeTokens.MAX_FONT_FILES) {
            out +=
                fontFinding(
                    code = ThemeErrorCode.THEME_FONT_COUNT,
                    path = "$.fonts",
                    message = "more than ${ThemeTokens.MAX_FONT_FILES} distinct font files",
                )
        }
        // The same (family, weight, style) must always resolve to one file.
        val seen = HashMap<Triple<String, Int, FontStyle>, String>()
        roles.forEach { r ->
            r.files.forEach { f ->
                val key = Triple(r.family, f.weight, f.style)
                val previous = seen.putIfAbsent(key, f.file)
                if (previous != null && previous != f.file) {
                    out +=
                        fontFinding(
                            code = ThemeErrorCode.THEME_FONT_CONFLICT,
                            path = "$.fonts",
                            message = "one family/weight/style maps to two different files",
                        )
                }
            }
        }
        if (out.any { it.action == ThemeAction.FONTS_DROPPED }) return null to null
        return body to display
    }

    private fun parseRole(
        node: JNode,
        path: String,
        out: MutableList<ThemeFinding>,
    ): FontRoleSpec? {
        if (node !is JNode.Obj) {
            out += fontFinding(code = ThemeErrorCode.THEME_TYPE, path = path, message = "must be an object")
            return null
        }
        val before = out.size
        node.members.keys.filter { it !in FONT_ROLE_KEYS }.forEach {
            out += fontFinding(code = ThemeErrorCode.THEME_UNKNOWN_KEY, path = "$path.${safe(it)}", message = "unknown key")
        }
        val familyNode = node.members["family"]
        var family = ""
        when {
            familyNode == null ->
                out +=
                    fontFinding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$path.family", message = "missing key")
            familyNode !is JNode.Str || !ThemeTokens.FAMILY_REGEX.matches(familyNode.value) ->
                out +=
                    fontFinding(
                        code = ThemeErrorCode.THEME_FONT_FAMILY,
                        path = "$path.family",
                        message = "family must match ${ThemeTokens.FAMILY_REGEX.pattern}",
                    )
            else -> family = familyNode.value
        }
        var fallback = FontFallback.SANS_SERIF
        node.members["fallback"]?.let { f ->
            val parsed = (f as? JNode.Str)?.let { FontFallback.fromJson(it.value) }
            if (parsed == null) {
                out +=
                    fontFinding(
                        code = ThemeErrorCode.THEME_BAD_ENUM,
                        path = "$path.fallback",
                        message = "must be one of ${FontFallback.entries.joinToString { it.json }}",
                    )
            } else {
                fallback = parsed
            }
        }
        val files = mutableListOf<FontFileSpec>()
        when (val filesNode = node.members["files"]) {
            null -> out += fontFinding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$path.files", message = "missing key")
            !is JNode.Arr -> out += fontFinding(code = ThemeErrorCode.THEME_TYPE, path = "$path.files", message = "must be an array")
            else -> {
                if (filesNode.items.isEmpty() || filesNode.items.size > ThemeTokens.MAX_FILES_PER_ROLE) {
                    out +=
                        fontFinding(
                            code = ThemeErrorCode.THEME_FONT_COUNT,
                            path = "$path.files",
                            message = "needs 1 to ${ThemeTokens.MAX_FILES_PER_ROLE} files",
                        )
                } else {
                    filesNode.items.forEachIndexed { idx, item ->
                        parseFile(node = item, path = "$path.files[$idx]", out = out)?.let {
                            files +=
                                it
                        }
                    }
                }
            }
        }
        if (out.size > before) return null
        return FontRoleSpec(family = family, fallback = fallback, files = files)
    }

    private fun parseFile(
        node: JNode,
        path: String,
        out: MutableList<ThemeFinding>,
    ): FontFileSpec? {
        if (node !is JNode.Obj) {
            out += fontFinding(code = ThemeErrorCode.THEME_TYPE, path = path, message = "must be an object")
            return null
        }
        val before = out.size
        node.members.keys.filter { it !in FONT_FILE_KEYS }.forEach {
            out += fontFinding(code = ThemeErrorCode.THEME_UNKNOWN_KEY, path = "$path.${safe(it)}", message = "unknown key")
        }
        var file = ""
        val fileNode = node.members["file"]
        when {
            fileNode == null -> out += fontFinding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$path.file", message = "missing key")
            fileNode !is JNode.Str || !isPlainFontFileName(fileNode.value) ->
                out +=
                    fontFinding(
                        code = ThemeErrorCode.THEME_FONT_PATH,
                        path = "$path.file",
                        message = "must be a plain file name matching ${ThemeTokens.FONT_FILE_REGEX.pattern}",
                    )
            else -> file = fileNode.value
        }
        var weight = 400
        val weightNode = node.members["weight"]
        if (weightNode == null) {
            out += fontFinding(code = ThemeErrorCode.THEME_MISSING_KEY, path = "$path.weight", message = "missing key")
        } else {
            val w = (weightNode as? JNode.Num)?.raw?.toIntOrNull()
            if (w == null || w !in 100..900 || w % 100 != 0) {
                out += fontFinding(code = ThemeErrorCode.THEME_BAD_ENUM, path = "$path.weight", message = "must be 100, 200, ... or 900")
            } else {
                weight = w
            }
        }
        var style = FontStyle.NORMAL
        node.members["style"]?.let { s ->
            val parsed = (s as? JNode.Str)?.let { FontStyle.fromJson(it.value) }
            if (parsed ==
                null
            ) {
                out += fontFinding(code = ThemeErrorCode.THEME_BAD_ENUM, path = "$path.style", message = "must be normal or italic")
            } else {
                style = parsed
            }
        }
        node.members["format"]?.let { f ->
            if (f !is JNode.Str ||
                f.value != "woff2"
            ) {
                out +=
                    fontFinding(code = ThemeErrorCode.THEME_BAD_ENUM, path = "$path.format", message = "must be woff2")
            }
        }
        if (out.size > before) return null
        return FontFileSpec(file = file, weight = weight, style = style)
    }

    /** Regex first, then the belt-and-braces structural check: exactly one path element, no `..`. */
    internal fun isPlainFontFileName(name: String): Boolean {
        if (!ThemeTokens.FONT_FILE_REGEX.matches(name)) return false
        if (name.contains("..")) return false
        return try {
            java.nio.file.Path
                .of(name)
                .nameCount == 1
        } catch (_: java.nio.file.InvalidPathException) {
            false
        }
    }

    private fun safe(key: String): String = key.take(64).map { if (it < ' ' || it == '\u007F') '?' else it }.joinToString("")

    private fun finding(
        code: ThemeErrorCode,
        path: String,
        message: String,
    ) = ThemeFinding(code = code, path = path, message = message, severity = Severity.ERROR, action = ThemeAction.DEFAULT)

    private fun fontFinding(
        code: ThemeErrorCode,
        path: String,
        message: String,
    ) = ThemeFinding(code = code, path = path, message = message, severity = Severity.ERROR, action = ThemeAction.FONTS_DROPPED)
}
