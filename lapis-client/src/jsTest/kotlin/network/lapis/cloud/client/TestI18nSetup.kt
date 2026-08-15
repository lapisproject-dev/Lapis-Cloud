package network.lapis.cloud.client

import io.kvision.i18n.I18n
import kotlin.js.EagerInitialization

/**
 * Sprachumschalter-Feature 2026-08-14: `App.kt`'s `main()` is the only place `I18n.manager` gets
 * set away from its `SimpleI18nManager` default -- and `main()` never runs under `jsTest`
 * (Karma+ChromeHeadless loads the compiled test bundle directly, no `main` entry point).
 * `SimpleI18nManager.gettext(key, *args)` (see KVision source) returns `key` completely
 * unmodified, ignoring `args` -- so any test asserting on a `gettext(...)`-wrapped function's
 * return value would see the raw, unsubstituted `%1`/`%2` placeholders instead of the interpolated
 * text. Found live via a full `jsTest` run after the i18n string-wrapping sweep: 72 failing tests,
 * every one of them asserting on a label/caption helper function that had been wrapped in
 * `tr()`/`gettext()`. `I18nCatalogManager` (not KVision's own `kvision-i18n` module's
 * `DefaultI18nManager` -- see that class's KDoc for why) does the `%N` substitution in pure
 * Kotlin, so an empty-catalog instance still substitutes correctly, exactly like production's
 * pre-translation-catalog state (`App.kt`'s own `main()`).
 *
 * `@EagerInitialization` is load-bearing, not decorative: a plain top-level property initializer
 * is dead-code-eliminated from the test bundle when nothing references the property (confirmed
 * live -- without this annotation, the initializer silently never ran at all, verified via a
 * `console.log` that never printed). With it, Kotlin/JS runs the initializer unconditionally at
 * module load, before any individual `@Test` function executes, regardless of which file it's
 * declared in or which test class runs first.
 */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
@Suppress("unused")
private val testI18nSetup =
    run {
        I18n.manager = I18nCatalogManager(emptyMap())
        I18n.language = "de"
    }
