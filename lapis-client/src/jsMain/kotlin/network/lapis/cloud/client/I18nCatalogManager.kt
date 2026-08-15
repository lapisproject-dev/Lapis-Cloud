package network.lapis.cloud.client

import io.kvision.i18n.I18n
import io.kvision.i18n.I18nManager
import kotlin.js.jsTypeOf

/**
 * Sprachumschalter-Feature 2026-08-14: a minimal, dependency-free replacement for KVision's own
 * `kvision-i18n` module's `DefaultI18nManager`.
 *
 * **Why not `DefaultI18nManager`**: that class wraps the npm `gettext.js` package via
 * `@JsModule("gettext.js") external val gettextJs: dynamic` + `gettextJs()`. Found live, in both
 * `jsBrowserDevelopmentWebpack`'s actual bundle (a real browser load, not just a compile check) and
 * under `jsTest`: this throws `TypeError: ...gettextJs... is not a function` at the very first
 * construction -- the whole app crashes on load. Root cause is an interop mismatch between this
 * Kotlin/Kotlin-JS-IR-compiler version's `@JsModule` binding and `gettext.js` 2.0.3's plain
 * `module.exports = function(...) {...}` CJS export shape (confirmed by inspecting the resolved
 * `node_modules/gettext.js/dist/gettext.cjs.min.js` directly) -- not a bug in this app's own code,
 * and not worth chasing further upstream given how little of `gettext.js`'s feature surface this
 * app actually needs (no plural forms are used anywhere -- `ntr()`/`ngettext()` never appear in
 * this codebase's i18n sweep).
 *
 * This class implements exactly what's needed instead: a flat msgid->msgstr lookup per language,
 * plus `%1`/`%2`/... placeholder substitution matching `gettext.js`'s own convention (so the
 * `gettext("... %1 ...", arg)` call sites written throughout this app's i18n sweep need no
 * changes). Catalogs are the `po2json`-format JSON objects `KVConvertPoTask`/`generatePotFile`
 * already produce from this module's `.po` translation files (see `.gettext.json`,
 * `deploy`-adjacent `src/jsMain/resources/modules/i18n/`) -- only the `[""]` metadata entry is
 * skipped; every other key is a `msgid: msgstr` pair, read via plain dynamic property access
 * (`json[key]`), no JS library involved.
 */
class I18nCatalogManager(
    private val catalogs: Map<String, dynamic>,
) : I18nManager {
    override fun gettext(
        key: String,
        vararg args: Any?,
    ): String {
        val translated = lookup(key)
        return substitute(translated, args)
    }

    /**
     * No plural forms are used anywhere in this app (verified during the i18n sweep -- see class
     * KDoc) -- a simple English-shaped rule (`value == 1` -> singular) is a safe, unused-in-practice
     * fallback rather than a real feature, kept only so this class fully implements [I18nManager].
     */
    override fun ngettext(
        singularKey: String,
        pluralKey: String,
        value: Int,
        vararg args: Any?,
    ): String {
        val key = if (value == 1) singularKey else pluralKey
        return gettext(key, *args)
    }

    private fun lookup(key: String): String {
        val catalog = catalogs[I18n.language] ?: return key
        val entry = catalog[key]
        return if (entry != null && jsTypeOf(entry) == "string") entry.unsafeCast<String>() else key
    }

    private fun substitute(
        text: String,
        args: Array<out Any?>,
    ): String {
        if (args.isEmpty()) return text
        var result = text
        args.forEachIndexed { index, arg ->
            result = result.replace("%${index + 1}", arg?.toString() ?: "")
        }
        return result
    }
}
