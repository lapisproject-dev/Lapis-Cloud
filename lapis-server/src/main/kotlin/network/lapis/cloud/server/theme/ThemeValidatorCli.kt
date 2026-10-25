package network.lapis.cloud.server.theme

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Validates a theme directory exactly as the server would load it and prints one JSON document to stdout. Exit codes: 0 valid,
 * 1 at least one error (including font and licence errors), 2 usage error or an unreadable directory. Shares [ThemeLoader]; it
 * logs nothing, so stdout is always parseable.
 */
object ThemeValidatorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args = args, out = System.out)
        System.out.flush()
        exitProcess(code)
    }

    internal fun run(
        args: Array<String>,
        out: Appendable,
    ): Int {
        if (args.size != 1) {
            out.append(usage("expected exactly one argument: the theme directory")).append('\n')
            return 2
        }
        val dir =
            try {
                Path.of(args[0]).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                out.append(usage("not a valid path")).append('\n')
                return 2
            }
        val result = ThemeLoader.load(dir)
        val errors = result.findings.filter { it.severity == Severity.ERROR }
        val warnings = result.findings.filter { it.severity == Severity.WARNING }
        val json =
            buildJsonObject {
                put("valid", errors.isEmpty())
                put("hash", if (errors.isEmpty()) JsonPrimitive(result.theme.hash12) else JsonNull)
                put("source", result.source.log)
                put("errors", JsonArray(errors.map { toJson(it) }))
                put("warnings", JsonArray(warnings.map { toJson(it) }))
            }
        out.append(json.toString()).append('\n')
        val unreadable = errors.any { it.code == ThemeErrorCode.THEME_DIR_MISSING || it.code == ThemeErrorCode.THEME_IO }
        return when {
            unreadable -> 2
            errors.isNotEmpty() -> 1
            else -> 0
        }
    }

    private fun toJson(f: ThemeFinding): JsonObject =
        buildJsonObject {
            put("code", f.code.name)
            put("path", f.path)
            put("message", f.message)
            put("action", f.action.log)
        }

    private fun usage(message: String): String =
        buildJsonObject {
            put("valid", false)
            put("hash", JsonNull)
            put("source", "fallback")
            put(
                "errors",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("code", "THEME_IO")
                            put("path", "args")
                            put("message", message)
                            put("action", "none")
                        },
                    ),
                ),
            )
            put("warnings", JsonArray(emptyList()))
        }.toString()
}
