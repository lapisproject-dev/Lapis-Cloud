package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Thrown for a malformed/out-of-range tool argument -- mapped by `McpToolDispatcher` to `-32602 invalid params`. */
internal class McpInvalidToolArgumentsException(
    override val message: String,
) : Exception(message)

/**
 * Reads an optional integer argument named [key], clamped into [range]; `null`/absent uses
 * [default]. Out-of-type throws [McpInvalidToolArgumentsException] -- deliberately NOT the
 * `kotlinx.serialization.json.jsonPrimitive` extension, which throws a bare
 * `IllegalArgumentException` for a JSON object/array value; that exception type is invisible to
 * `McpToolDispatcher`'s dedicated `McpInvalidToolArgumentsException` catch and falls through to its
 * generic `catch (e: Exception)` branch instead, reported to the agent as an opaque "Internal
 * error" (and audited as `ERROR`) rather than the actual "-32602 invalid params" this KDoc promises.
 */
internal fun JsonObject.optionalIntArg(
    key: String,
    default: Int,
    range: IntRange,
): Int {
    val element = this[key] ?: return default
    val value =
        (element as? JsonPrimitive)?.intOrNull ?: throw McpInvalidToolArgumentsException("'$key' must be an integer")
    return value.coerceIn(range)
}

/** Reads a required string argument named [key], trimmed, with a length bound. Same non-primitive-safety reasoning as [optionalIntArg]. */
internal fun JsonObject.requiredStringArg(
    key: String,
    minLength: Int,
    maxLength: Int,
): String {
    val raw = (this[key] as? JsonPrimitive)?.contentOrNullSafe() ?: throw McpInvalidToolArgumentsException("'$key' is required")
    val trimmed = raw.trim()
    if (trimmed.length < minLength || trimmed.length > maxLength) {
        throw McpInvalidToolArgumentsException("'$key' must be between $minLength and $maxLength characters")
    }
    return trimmed
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this.isString) content else null

/**
 * Welle V1.8.2 -- reads a required string argument named [key] and parses it against
 * [valueOf] (an enum's own generated `valueOf`, e.g. `SocialPostVisibility::valueOf`). An
 * unknown/mistyped literal throws [McpInvalidToolArgumentsException] (-32602), same non-oracle
 * posture as every other argument parser in this file -- never a raw `IllegalArgumentException`.
 */
internal fun <T> JsonObject.requiredEnumArg(
    key: String,
    valueOf: (String) -> T,
): T {
    val raw = (this[key] as? JsonPrimitive)?.contentOrNullSafe() ?: throw McpInvalidToolArgumentsException("'$key' is required")
    return runCatching { valueOf(raw) }.getOrElse {
        // MINOR-4 (Welle V1.8.2b): this message lands verbatim in a JSON-RPC response -- strip
        // control characters (a caller-controlled value otherwise ends up unescaped in a JSON
        // string) and cap it, rather than echoing an arbitrarily long/hostile raw value back.
        val safeRaw = raw.filterNot { c -> c.isISOControl() }.take(64)
        throw McpInvalidToolArgumentsException("'$key' has an unknown value: $safeRaw")
    }
}
