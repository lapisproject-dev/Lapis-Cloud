package network.lapis.cloud.server.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pins ThemeDefaults, the client theme.css, the shipped JSON resource, the preset and the JSON schema to each other. */
class ThemeConsistencyTest :
    FunSpec({
        val themeCss = repoFile("lapis-client/src/jsMain/resources/theme.css").readText()

        /** Body of the top-level block that starts with [header]; naive brace matching (theme.css has no braces in strings here). */
        fun block(header: String): String {
            val start = themeCss.indexOf("\n$header {")
            check(start >= 0) { "block not found: $header" }
            val open = themeCss.indexOf('{', start)
            var depth = 0
            var i = open
            while (i < themeCss.length) {
                when (themeCss[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return themeCss.substring(open + 1, i)
                    }
                }
                i++
            }
            error("unterminated block $header")
        }

        fun tokens(body: String): Map<String, String> =
            ThemeTokens.COLOR_KEYS.associateWith { key ->
                Regex("""--lapis-${Regex.escape(key)}:\s*(#[0-9A-Fa-f]{6})""")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.uppercase()
                    ?: error("token $key not found")
            }

        test("no rule painted on nav-hover-bg uses a literal text colour") {
            val offenders =
                Regex("""([^{}]+)\{([^{}]*)}""")
                    .findAll(themeCss)
                    .filter { rule ->
                        val body = rule.groupValues[2]
                        body.contains("background-color: var(--lapis-nav-hover-bg)") &&
                            Regex("""(^|[;\s])color:\s*#""").containsMatchIn(body)
                    }.map { it.groupValues[1].trim() }
                    .toList()
            offenders shouldBe emptyList()
        }

        test("no navbar rule paints a fixed (non-themable) token as text colour") {
            val offenders =
                Regex("""([^{}]+)\{([^{}]*)}""")
                    .findAll(themeCss)
                    .filter { rule ->
                        rule.groupValues[1].contains(".lapis-navbar") &&
                            Regex("""(^|[;\s])color:\s*var\(--lapis-fleck""").containsMatchIn(rule.groupValues[2])
                    }.map { it.groupValues[1].trim() }
                    .toList()
            offenders shouldBe emptyList()
        }

        test("the navbar focus ring uses the themable nav-text token") {
            Regex("""\.lapis-navbar[^{}]*:focus-visible[^{}]*\{[^{}]*outline-color:\s*var\(--lapis-nav-text\)""")
                .containsMatchIn(themeCss) shouldBe true
        }

        test("the nav-text focus ring never applies to navbar dropdown menu contents") {
            val rules = Regex("""([^{}]+)\{([^{}]*)\}""").findAll(themeCss)
            val offenders =
                rules
                    .filter { it.groupValues[2].contains("outline-color: var(--lapis-nav-text)") }
                    .flatMap { it.groupValues[1].split(',').asSequence() }
                    .map { it.trim() }
                    .filter { it.contains("dropdown-menu") || it.endsWith(".lapis-navbar :focus-visible") }
                    .toList()
            offenders shouldBe emptyList()
            Regex("""\.lapis-navbar \.dropdown-menu :focus-visible\s*\{[^{}]*outline-color:\s*var\(--lapis-accent\)""")
                .containsMatchIn(themeCss) shouldBe true
        }

        test("ThemeDefaults equals the three token blocks of theme.css") {
            val light = tokens(block(":root"))
            val dark = tokens(block(":root[data-theme=\"dark\"]"))
            val media = block("@media (prefers-color-scheme: dark)")
            val mediaDark = tokens(media.substring(media.indexOf('{') + 1))
            light shouldBe ThemeDefaults.LIGHT
            dark shouldBe ThemeDefaults.DARK
            mediaDark shouldBe ThemeDefaults.DARK
        }
        test("ThemeDefaults covers exactly the 16 tokens") {
            ThemeDefaults.LIGHT.keys.toList() shouldBe ThemeTokens.COLOR_KEYS
            ThemeDefaults.DARK.keys.toList() shouldBe ThemeTokens.COLOR_KEYS
            ThemeTokens.COLOR_KEYS.size shouldBe 16
        }
        test("the font stacks equal the theme.css font declarations") {
            val root = block(":root")
            (root.contains("--lapis-font-body: ${ThemeTokens.DEFAULT_BODY_STACK};")) shouldBe true
            (root.contains("--lapis-font-display: ${ThemeTokens.DEFAULT_DISPLAY_STACK};")) shouldBe true
        }
        test("the bundled default resource equals the preset and ThemeDefaults") {
            val resource = repoFile("lapis-server/src/main/resources/theme/lapis-default.theme.json").readText()
            resource shouldBe repoFile("deploy/example/themes/lapis-default/theme.json").readText()
            val spec = ThemeParser.parse(resource).spec!!
            spec.light.mapValues { it.value.css } shouldBe ThemeDefaults.LIGHT
            spec.dark.mapValues { it.value.css } shouldBe ThemeDefaults.DARK
            ThemeCssRenderer.render(spec = spec, fonts = emptyMap()) shouldBe ResolvedTheme.DEFAULT.css
        }

        val schema = Json.parseToJsonElement(repoFile("lapis-server/src/main/resources/theme/theme.schema.json").readText()).jsonObject

        fun objects(
            e: JsonElement,
            out: MutableList<JsonObject> = mutableListOf(),
        ): List<JsonObject> {
            if (e is JsonObject) {
                if (e["type"]?.jsonPrimitive?.content == "object") out += e
                e.values.forEach { objects(it, out) }
            } else if (e is JsonArray) {
                e.forEach { objects(it, out) }
            }
            return out
        }

        test("every object in the schema forbids additional properties") {
            val all = objects(schema)
            (all.size >= 6) shouldBe true
            all.forEach { it["additionalProperties"]?.jsonPrimitive?.boolean shouldBe false }
        }
        test("schema keys, enums and limits equal the Kotlin constants") {
            val props = schema.getValue("properties").jsonObject
            val mode =
                props
                    .getValue("colors")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("light")
                    .jsonObject
            mode.getValue("required").jsonArray.map { it.jsonPrimitive.content } shouldBe ThemeTokens.COLOR_KEYS
            mode
                .getValue("properties")
                .jsonObject.keys
                .toList() shouldBe ThemeTokens.COLOR_KEYS
            mode
                .getValue("properties")
                .jsonObject
                .getValue("bg")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive.content shouldBe
                ThemeTokens.HEX_REGEX.pattern
            props
                .getValue("radius")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content } shouldBe
                RadiusPreset.entries.map { it.json }
            props
                .getValue("name")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive.content shouldBe ThemeTokens.NAME_REGEX.pattern
            props
                .getValue("description")
                .jsonObject
                .getValue("maxLength")
                .jsonPrimitive.int shouldBe ThemeTokens.MAX_DESCRIPTION
            props
                .getValue("version")
                .jsonObject
                .getValue("const")
                .jsonPrimitive.int shouldBe ThemeTokens.SCHEMA_VERSION
            val role =
                props
                    .getValue("fonts")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("body")
                    .jsonObject
            val roleProps = role.getValue("properties").jsonObject
            roleProps
                .getValue("family")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive.content shouldBe ThemeTokens.FAMILY_REGEX.pattern
            roleProps
                .getValue("fallback")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content } shouldBe
                FontFallback.entries.map { it.json }
            val files = roleProps.getValue("files").jsonObject
            files.getValue("maxItems").jsonPrimitive.int shouldBe ThemeTokens.MAX_FILES_PER_ROLE
            val fileProps =
                files
                    .getValue("items")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
            fileProps
                .getValue("file")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive.content shouldBe ThemeTokens.FONT_FILE_REGEX.pattern
            fileProps
                .getValue("style")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content } shouldBe
                FontStyle.entries.map { it.json }
        }
        test("the presets validate through the full loader; the party preset without fonts only loses the fonts") {
            listOf("lapis-default", "neutral", "high-contrast").forEach { p ->
                val r = ThemeLoader.load(repoFile("deploy/example/themes/$p").toPath().toAbsolutePath())
                r.source shouldBe ThemeSource.CUSTOM
                r.findings.none { it.severity == Severity.ERROR } shouldBe true
            }
            val dir =
                themeDir(
                    json = repoFile("deploy/example/themes/party-yellow-blue/theme.json").readText(),
                    fonts = emptyMap(),
                    license = null,
                )
            val r = ThemeLoader.load(dir)
            r.source shouldBe ThemeSource.CUSTOM_WITHOUT_FONTS
            r.findings.single().code shouldBe ThemeErrorCode.THEME_FONT_MISSING
            val withFonts =
                themeDir(
                    json = repoFile("deploy/example/themes/party-yellow-blue/theme.json").readText(),
                    fonts =
                        mapOf(
                            "Inter-Regular.woff2" to fakeWoff2(),
                            "Inter-SemiBold.woff2" to fakeWoff2(70),
                            "Inter-Bold.woff2" to fakeWoff2(80),
                        ),
                )
            val ok = ThemeLoader.load(withFonts)
            ok.source shouldBe ThemeSource.CUSTOM
            ok.theme.fonts.size shouldBe 3
        }
        test("JsonPrimitive helper sanity") {
            JsonPrimitive("x").content shouldBe "x"
        }
    })
