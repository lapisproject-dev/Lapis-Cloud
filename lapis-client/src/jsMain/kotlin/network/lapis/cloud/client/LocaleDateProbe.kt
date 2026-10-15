package network.lapis.cloud.client

/**
 * The ONE place in the client that asks the browser how it writes a date (V1.9.132). Only the ORDER of day/month/year and the SEPARATOR come from
 * the browser; digits are always rendered by [formatLocalDate] itself. This file is the deliberate, documented exception to the "no
 * Intl date API" rule of `DateTime.kt` (tripwire T1 whitelists exactly this file).
 *
 * The probe formats the fixed date 22 November 2033 (day 22, month 11, year 2033: all three components are distinguishable) in UTC and reads the
 * `formatToParts` result. Anything unexpected (an exception, an order other than DMY/MDY/YMD, a separator other than `.`, `/`, `-`) answers
 * `null`, and the caller falls back to a fixed table. The answer per tag is cached (it never changes within a page load).
 */
private val probeCache = HashMap<String, DateFieldOrder?>()

private val probeFunction: dynamic =
    js(
        "(function (tag) { return (new Intl.DateTimeFormat(tag, " +
            "{ year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'UTC' })).formatToParts(Date.UTC(2033, 10, 22)); })",
    )

internal fun jsDateFieldOrderProbe(tag: String): DateFieldOrder? {
    if (probeCache.containsKey(tag)) return probeCache[tag]
    val result = runCatching { probe(tag) }.getOrNull()
    probeCache[tag] = result
    return result
}

private fun probe(tag: String): DateFieldOrder? {
    val parts: dynamic = probeFunction(tag)
    val count = parts.length as Int
    val types = mutableListOf<String>()
    var separator: Char? = null
    for (i in 0 until count) {
        val type = parts[i].type as String
        val value = parts[i].value as String
        when (type) {
            "day", "month", "year" -> types += type
            "literal" -> {
                if (separator == null) {
                    // Some ICU builds wrap the literal in left-to-right/right-to-left marks or add a trailing space (". ").
                    val cleaned = value.replace("‎", "").replace("‏", "").trim()
                    if (cleaned.length == 1 && (cleaned[0] == '.' || cleaned[0] == '/' || cleaned[0] == '-')) separator = cleaned[0]
                }
            }
        }
    }
    val order =
        when (types) {
            listOf("day", "month", "year") -> DateOrder.DMY
            listOf("month", "day", "year") -> DateOrder.MDY
            listOf("year", "month", "day") -> DateOrder.YMD
            else -> return null
        }
    return DateFieldOrder(order, separator ?: return null)
}
