package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.2.5 White-Label-Branding -- the "Nicht-Entfernbarkeits-Gate" for `lapisAttribution()`
 * (`LapisAttribution.kt`): the platform identity it links to is fixed, never operator-overridable
 * -- see [Branding] KDoc "PLATFORM_NAME/PLATFORM_URL never come from BrandPayload".
 *
 * **Full KVision-DOM-rendering coverage is deliberately out of scope here**, same precedent
 * `GuestBadgeTest` already establishes for this module (see that class' own KDoc): no KVision
 * render/mount test harness exists in this module, and building one just for this one component
 * would be disproportionate scope for this wave.
 *
 * **Cross-catalog `.po` translation coverage (plan §7.5) was verified manually, not by an
 * automated jsTest, and why**: `TestI18nSetup.kt` deliberately loads `I18n.manager` with an
 * EMPTY catalog map for every jsTest run (see that file's own KDoc) -- there is no existing
 * mechanism in this module's test setup to load the real, translated `messages-<lang>.json`
 * catalogs at test time, and `.po` source files are not reachable from a browser-hosted
 * Karma+ChromeHeadless test at all (no filesystem access). Verified instead by direct inspection
 * of all seven `lapis-client/src/jsMain/resources/modules/i18n/messages-<lang>.po` files
 * (`en`/`fr`/`es`/`it`/`nl`/`pl`/`ru` -- German is the untranslated source language, see `App.kt`'s
 * own `SUPPORTED_LANGUAGES` KDoc): each carries a `msgid "Betrieben mit Lapis Cloud"` entry whose
 * `msgstr` contains the literal substring `"Lapis Cloud"` verbatim (`"Powered by Lapis Cloud"`,
 * `"Propulsé par Lapis Cloud"`, `"Desarrollado con Lapis Cloud"`, `"Realizzato con Lapis Cloud"`,
 * `"Mogelijk gemaakt door Lapis Cloud"`, `"Obsługiwane przez Lapis Cloud"`, `"Работает на базе
 * Lapis Cloud"`) -- i.e. no translator has localized the PRODUCT NAME itself out of an
 * unremovable credit, exactly the regression this whole gate exists to catch.
 *
 * Manual QA substitute for the DOM/visual side (mirrors `GuestBadgeTest`'s own convention): open a
 * deployment with `LAPIS_BRAND_TITLE`/`LAPIS_BRAND_LOGO_PATH` both set, confirm the "Betrieben mit
 * Lapis Cloud" line appears exactly ONCE per screen -- below the app shell, which already wraps
 * every screen including the login screen. (`LoginScreen.kt` originally added a SECOND,
 * screen-local call site on top of that for "extra visibility" -- found live 2026-08-25 to render
 * as an obvious duplicate directly below the login card instead, since the login screen is short
 * enough that both copies land within view without scrolling; removed.) Check every language via
 * the switcher, and that the link opens `https://cloud.lapisproject.dev` in a new tab
 * (`target="_blank"`, `rel="noopener noreferrer"`).
 */
class LapisAttributionTest {
    @Test
    fun platformUrl_isTheFixedLapisCloudDomain_neverOperatorOverridable() {
        assertEquals("https://cloud.lapisproject.dev", Branding.PLATFORM_URL)
    }

    @Test
    fun platformName_isTheFixedLapisCloudName_neverOperatorOverridable() {
        assertEquals("Lapis Cloud", Branding.PLATFORM_NAME)
    }
}
