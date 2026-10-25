package network.lapis.cloud.server.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Resolves a repo-relative path from either the module directory or the repository root. */
internal fun repoFile(path: String): File = File("../$path").let { if (it.exists()) it else File(path) }

internal val DEFAULT_THEME_JSON: String get() = repoFile("deploy/example/themes/lapis-default/theme.json").readText()

internal fun fakeWoff2(size: Int = 64): ByteArray = ByteArray(size).also { ThemeTokens.WOFF2_MAGIC.copyInto(it) }

/** Builds a theme.json from the default one with [mutate] applied to the root member map. */
internal fun themeJson(mutate: (MutableMap<String, JsonElement>) -> Unit = {}): String {
    val root = Json.parseToJsonElement(DEFAULT_THEME_JSON).jsonObject.toMutableMap()
    mutate(root)
    return JsonObject(root).toString()
}

/** Replaces one colour token of one mode. */
internal fun themeJsonWithColor(
    mode: String,
    key: String,
    value: String?,
): String =
    themeJson { root ->
        val colors = root.getValue("colors").jsonObject.toMutableMap()
        val m = colors.getValue(mode).jsonObject.toMutableMap()
        if (value == null) m.remove(key) else m[key] = JsonPrimitive(value)
        colors[mode] = JsonObject(m)
        root["colors"] = JsonObject(colors)
    }

internal fun fontsJson(
    vararg bodyFiles: Triple<String, Int, String>,
    family: String = "Inter",
): JsonObject =
    buildJsonObject {
        put(
            "body",
            buildJsonObject {
                put("family", JsonPrimitive(family))
                put("fallback", JsonPrimitive("sans-serif"))
                put(
                    "files",
                    JsonArray(
                        bodyFiles.map { (file, weight, style) ->
                            buildJsonObject {
                                put("file", JsonPrimitive(file))
                                put("weight", JsonPrimitive(weight))
                                put("style", JsonPrimitive(style))
                            }
                        },
                    ),
                )
            },
        )
    }

internal fun themeDir(
    json: String,
    fonts: Map<String, ByteArray> = emptyMap(),
    license: String? = "OFL.txt",
): Path {
    val dir = Files.createTempDirectory("lapis-theme-test")
    dir.toFile().deleteOnExit()
    Files.writeString(dir.resolve("theme.json"), json)
    fonts.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
    if (license != null) Files.writeString(dir.resolve(license), "SIL Open Font License 1.1")
    return dir
}

internal fun symlinksSupported(): Boolean =
    try {
        val dir = Files.createTempDirectory("lapis-symlink-probe")
        val target = Files.writeString(dir.resolve("t"), "x")
        Files.createSymbolicLink(dir.resolve("l"), target)
        true
    } catch (_: Exception) {
        false
    }
