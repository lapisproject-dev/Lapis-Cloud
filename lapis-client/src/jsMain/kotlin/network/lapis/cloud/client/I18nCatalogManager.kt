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
        // W6a: an amount token (see [moneyToken]) is formatted for the current language, never looked up. First, so no other branch sees it.
        if (key.startsWith(MONEY_SENTINEL)) return formatMoneyToken(key)
        // A composed key (see [trFormat]): KVision hands the WHOLE marker-stripped string to `gettext` with no
        // arguments, so the arguments travel inside the key.
        if (args.isEmpty() && key.contains(I18N_ARG_SEPARATOR)) return composed(key)
        val translated = lookup(key)
        // Security audit W6b follow-up (major finding 1, round 2): this used to resolve any `String` argument that
        // STARTED WITH [KV_I18N_MARKER] through a nested [gettext] call ("Audit V1.4.30" marker-leak fix below, kept
        // here only in this comment for history). That was a CONTENT-based trust decision on a plain, freely typable
        // ASCII prefix -- exactly the mistake [trFormat]'s KDoc calls out -- and this function (unlike [trFormat]) is
        // reached directly, with genuinely untrusted server-/member-controlled text, from dozens of call sites across
        // the client (`gettext("... %1 ...", <server field>)`). No call site in this codebase actually relies on the
        // old resolving behaviour: `ClientUiGuidelineTripwireTest` already forbids passing a literal `tr(...)` result
        // as a `gettext` argument, and every helper that indirectly returns translated text (e.g. `erasureModeLabel`)
        // does so via `gettext(...)` itself, which resolves immediately and never returns a marker-prefixed string.
        // Every `String` argument is therefore always sanitized -- never resolved by content -- exactly like an
        // untrusted plain-`String` [trFormat] argument. This closes the money-forgery path where an attacker-typed
        // member display name / account name / free-text field carrying [KV_I18N_MARKER] + [MONEY_SENTINEL] + a kind
        // + digits was resolved into a fabricated amount.
        val sanitizedArgs =
            Array<Any?>(args.size) { index ->
                val argument = args[index]
                if (argument is String) sanitizeUntrustedI18nText(argument) else argument
            }
        return substitute(translated, sanitizedArgs)
    }

    /**
     * `template SEP arg1 SEP arg2 ...` -> the translated template with every argument resolved in the CURRENT
     * language first (an argument that is itself a `tr(...)` marker string is looked up; a plain one is taken as it
     * is). This is what lets a label like "Summe %1" follow a language switch on a screen that is already rendered.
     */
    private fun composed(key: String): String {
        val parts = key.split(I18N_ARG_SEPARATOR)
        val resolved =
            Array<Any?>(parts.size - 1) { index ->
                val argument = parts[index + 1]
                if (argument.startsWith(KV_I18N_MARKER)) gettext(argument.removePrefix(KV_I18N_MARKER)) else argument
            }
        return substitute(lookup(parts.first()), resolved)
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

    /**
     * Security audit W6b, round 5 (minor finding, "money-forgery hardening" follow-up): this used to substitute
     * `%1`, `%2`, ... one at a time with `result = result.replace("%${index + 1}", ...)`, forward, over the whole
     * running `result` string -- so a LATER substitution could match text that an EARLIER substitution had just
     * inserted. Concretely: an untrusted argument for `%1` whose value is literally the string `"%4"` gets replaced
     * into `result` first; the loop then reaches `%4` and its `.replace("%4", ...)` call matches that just-inserted
     * text too, not only a genuine `%4` placeholder from the original template -- an attacker-chosen display name
     * could steer a LATER, genuinely trusted argument (e.g. a member's staked/settled LTR amount, see
     * `MotionsScreen.kt`'s ballot row) into the wrong placeholder position, spoofing which value appears under which
     * label. [PLACEHOLDER] below is matched against the ORIGINAL [text] in a single linear pass (`Regex.replace`
     * scans the input once and never re-scans substituted output), so no argument's value -- whatever it contains --
     * can ever be re-interpreted as another placeholder.
     */
    private fun substitute(
        text: String,
        args: Array<out Any?>,
    ): String {
        if (args.isEmpty()) return text
        return PLACEHOLDER.replace(text) { match ->
            // Security audit W6b, round 7 (major finding 3, part 2): the digit run [PLACEHOLDER] captures is
            // unbounded -- it can come from an UNTRANSLATED template/key (`lookup` returns the key itself for an
            // unknown catalog entry, see above), which in turn can be attacker-controlled text (a forged
            // "###KvI18nP###%2147483648###KvI18nP###..." payload resolves, via KVision's own plural `trans`
            // dispatch, into exactly this `gettext(key = "%2147483648", args = [...])` call). A bare `.toInt()`
            // throws `NumberFormatException` on a digit run outside the `Int` range and crashes the render for
            // every viewer of that widget, persistently, until the record is deleted. `toIntOrNull()` makes an
            // out-of-range (or otherwise unparsable) run fall through to the same "not a real placeholder, leave
            // it verbatim" branch as an out-of-bounds index below -- never a thrown exception.
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index in 1..args.size) args[index - 1]?.toString() ?: "" else match.value
        }
    }
}

/** Separates the template from its arguments inside a composed `tr` string (see [trFormat]); never rendered. */
internal const val I18N_ARG_SEPARATOR = "\u0001"

/** `%1`, `%2`, ... placeholder syntax matched by [I18nCatalogManager.substitute], in a single linear pass over the ORIGINAL template/translation text. */
private val PLACEHOLDER = Regex("""%(\d+)""")

/**
 * A `tr(...)` string with arguments that STAYS live-translatable: `trFormat(tr("Summe %1"), trusted(tr("Einnahmen")))`.
 * KVision's own `tr(key)` takes no arguments, and `gettext("Summe %1", tr("Einnahmen"))` resolves once, at the
 * moment of the call -- the nested marker is never resolved (the label showed `Summe ###KvI18nS###Einnahmen`) and the
 * label would not follow a language switch. Here the result keeps the [template]'s marker prefix, so the widget
 * that shows it re-resolves it on every render, and [I18nCatalogManager.gettext] resolves the arguments per language.
 *
 * [template] must be a `tr(...)` string (so the extraction tooling sees the msgid). Each argument is either a [TrArg]
 * (wrap with [trusted] -- only for the direct result of `tr(...)`/[moneyToken]/[ltrToken]/[plainAmountToken]/[countToken],
 * never for anything derived from server- or user-controlled text) or a plain `String`, which is always treated as
 * untrusted free text and unconditionally sanitized by [sanitizeUntrustedI18nText] -- no exception, regardless of what
 * the string's content looks like.
 *
 * Security audit W6b: an untrusted plain-text argument (e.g. a vote option label or a member display name from
 * `GovernanceService`) that itself contains [I18N_ARG_SEPARATOR] splits into an extra part once
 * [I18nCatalogManager.composed] later does `key.split(I18N_ARG_SEPARATOR)`, shifting every following argument by one
 * position; if that injected part also starts with [KV_I18N_MARKER] it is resolved through [I18nCatalogManager.gettext]
 * again, and a trailing [MONEY_SENTINEL] there lets attacker-controlled text render as a fabricated, freely chosen
 * amount -- exactly the forgery [moneyToken]'s KDoc says cannot happen.
 *
 * Security audit W6b follow-up (major finding 1, not closed by a first attempt): trust must never be decided by
 * *content* -- e.g. "the argument already starts with [KV_I18N_MARKER]" -- because the marker is plain, freely
 * typable ASCII text (`###KvI18nS###`), not a control character; nothing stops an attacker from typing it as the
 * FIRST characters of a label, with no [I18N_ARG_SEPARATOR] injection needed at all, and reaching the exact same
 * forged-amount outcome. [TrArg] makes trust a TYPE instead: only code that calls [trusted] on the direct result of
 * this app's own `tr()`/[moneyToken]/[ltrToken]/... helpers can produce one; a plain `String` -- no matter what it
 * starts with -- is always sanitized.
 */
internal fun trFormat(
    template: String,
    vararg args: Any,
): String = template + args.joinToString("") { I18N_ARG_SEPARATOR + trFormatArgPayload(it) }

private fun trFormatArgPayload(arg: Any): String =
    when (arg) {
        is TrArg -> arg.raw
        is String -> sanitizeUntrustedI18nText(arg)
        else -> error("trFormat argument must be a String or a TrArg, was $arg")
    }

/**
 * Marks a value as the direct, unmodified result of this app's own `tr()`/[moneyToken]/[ltrToken]/[plainAmountToken]/
 * [countToken] helpers, safe to pass into [trFormat] without sanitization. See the security note on [trFormat] for why
 * trust here is a type, never a string prefix. Never wrap plain-text, server- or user-controlled strings with this --
 * doing so defeats the type-based guard [trFormat] relies on.
 */
internal fun trusted(value: String): TrArg = TrArg(value)

/** See [trusted]. */
internal class TrArg internal constructor(
    internal val raw: String,
)

/**
 * Unconditionally strips [I18N_ARG_SEPARATOR], [KV_I18N_MARKER] and [MONEY_SENTINEL] from untrusted free text --
 * server- or user-controlled labels, titles, display names -- before it is used either as a [trFormat] argument (see
 * [trFormatArgPayload]) or directly as a widget's content. KVision's `Widget` class resolves ANY content string that
 * starts with [KV_I18N_MARKER] through `I18n.trans`/`gettext` on render (see `io.kvision.core.Widget`), independent
 * of [trFormat]/[I18nCatalogManager.gettext] -- so a vote option label or motion title carrying a forged marker +
 * [MONEY_SENTINEL] payload renders as a fabricated amount even when it never touches [trFormat] (security audit W6b
 * follow-up, major finding 4). Any untrusted text handed to a widget as plain content must be sanitized with this
 * function first.
 *
 * Security audit W6b, round 7 (major finding 3, part 1): this used to strip only [KV_I18N_MARKER] (the SINGULAR
 * `tr()` marker) to a fixed point, leaving [KV_I18N_MARKER_PLURAL] (the `ntr()`/plural marker) untouched. KVision's
 * own `Widget.trans` render path resolves `###KvI18nP###...` exactly as unconditionally as `###KvI18nS###...` --
 * an untrusted field carrying a forged plural marker + a guessable catalog key still reached translated catalog
 * text even after "sanitization", and a malformed plural payload could crash the render entirely (see the guard
 * added to [I18nCatalogManager.substitute]). Both markers are now stripped to a fixed point in the same loop.
 */
internal fun sanitizeUntrustedI18nText(text: String): String {
    // Security audit W6b follow-up (major finding 2): [I18N_ARG_SEPARATOR] and [MONEY_SENTINEL] are single characters
    // -- removing occurrences of a single character can never CREATE a new occurrence of that same character, so a
    // single `replace` pass is safe for them. [KV_I18N_MARKER] and [KV_I18N_MARKER_PLURAL] are MULTI-character
    // ("###KvI18nS###" / "###KvI18nP###"): deleting one occurrence can splice its neighbours together into a NEW
    // occurrence that a single pass never sees, e.g. the input `"###KvI" + KV_I18N_MARKER + "18nS###"` becomes the
    // intact marker `"###KvI18nS###"` after one pass. Replace both to a FIXED POINT instead -- keep removing until
    // neither remains -- so no nested/overlapping construction of either marker can survive. Each iteration
    // strictly removes at least one occurrence, so this always terminates.
    var result = text.replace(I18N_ARG_SEPARATOR, "").replace(MONEY_SENTINEL, "")
    while (result.contains(KV_I18N_MARKER) || result.contains(KV_I18N_MARKER_PLURAL)) {
        result = result.replace(KV_I18N_MARKER, "").replace(KV_I18N_MARKER_PLURAL, "")
    }
    return result
}
