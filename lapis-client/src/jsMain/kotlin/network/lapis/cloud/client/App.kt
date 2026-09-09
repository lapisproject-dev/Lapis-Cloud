package network.lapis.cloud.client

import io.kvision.Application
import io.kvision.BootstrapCssModule
import io.kvision.BootstrapModule
import io.kvision.CoreModule
import io.kvision.FontAwesomeModule
import io.kvision.core.AlignItems
import io.kvision.dropdown.ddLink
import io.kvision.dropdown.dropDown
import io.kvision.dropdown.separator
import io.kvision.html.ButtonStyle
import io.kvision.html.Link
import io.kvision.html.button
import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.navbar.Nav
import io.kvision.navbar.Navbar
import io.kvision.navbar.NavbarExpand
import io.kvision.navbar.nav
import io.kvision.navbar.navLink
import io.kvision.navbar.navbar
import io.kvision.offcanvas.OffPlacement
import io.kvision.offcanvas.OffResponsiveType
import io.kvision.offcanvas.Offcanvas
import io.kvision.offcanvas.offcanvas
import io.kvision.panel.hPanel
import io.kvision.panel.root
import io.kvision.panel.vPanel
import io.kvision.remote.registerRemoteTypes
import io.kvision.startApplication
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.rpc.IAuthService
import org.w3c.dom.events.Event
import org.w3c.dom.get
import org.w3c.dom.set

@JsModule("./modules/i18n/messages-en.json")
@JsNonModule
external val messagesEn: dynamic

@JsModule("./modules/i18n/messages-fr.json")
@JsNonModule
external val messagesFr: dynamic

@JsModule("./modules/i18n/messages-es.json")
@JsNonModule
external val messagesEs: dynamic

@JsModule("./modules/i18n/messages-it.json")
@JsNonModule
external val messagesIt: dynamic

@JsModule("./modules/i18n/messages-nl.json")
@JsNonModule
external val messagesNl: dynamic

@JsModule("./modules/i18n/messages-pl.json")
@JsNonModule
external val messagesPl: dynamic

@JsModule("./modules/i18n/messages-ru.json")
@JsNonModule
external val messagesRu: dynamic

/** Application-wide coroutine scope tied to the browser's event loop. */
val AppScope: CoroutineScope = CoroutineScope(window.asCoroutineDispatcher())

/**
 * The Lapis family's faceted-stone brand mark, copied verbatim (same `<polygon>`/`<polyline>`
 * point coordinates) from `cloud.lapisproject.dev`'s `Logo.astro` -- see that component's KDoc
 * for the "shared wordmark, per-project suffix" convention this app's plain "Lapis Cloud" label
 * already follows without a `suffix` prop. `stroke="currentColor"` picks up `.lapis-brand-mark`'s
 * `color` from theme.css rather than hardcoding a color here, so a future dark-mode pass only
 * needs to touch the CSS, not this markup.
 *
 * `internal`, not `private` (V1.2.5 White-Label-Branding) -- `LapisAttribution.kt`'s
 * `lapisAttribution()` reuses this exact markup at a smaller size (`.lapis-attribution-mark` CSS
 * override in theme.css), rather than introducing a second, potentially-drifting SVG constant --
 * see that file's own KDoc.
 */
internal const val LAPIS_GEM_MARK_SVG = """<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <polygon points="12,1 21,8 17,23 7,23 3,8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
    <polyline points="12,1 12,23" stroke="currentColor" stroke-width="1" opacity="0.55" />
    <polyline points="3,8 21,8" stroke="currentColor" stroke-width="1" opacity="0.55" />
    <polyline points="7,23 12,8 17,23" stroke="currentColor" stroke-width="1" opacity="0.55" />
</svg>"""

// brandLogoImgHtml/escapeHtmlAttribute moved to BrandLockup.kt (V1.4.7 "Root-Verlinkung") -- the
// navbar brand mark below and BrandLockup.kt's brandLockup() now share the same two functions
// instead of each carrying its own copy.

/**
 * Sprachumschalter-Feature 2026-08-14: supported UI languages, in the order shown in the
 * dropdown. Each language's own name is shown in ITS OWN language (standard language-picker
 * convention -- a German speaker looking for "Polski" doesn't know to look under "Polnisch"),
 * never translated via [tr]. German has no translation catalog (see [initI18n]) -- its entry
 * here exists only so it appears as a selectable item in the switcher.
 */
private val SUPPORTED_LANGUAGES =
    listOf(
        "de" to "Deutsch",
        "en" to "English",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "ru" to "Русский",
    )

private const val LANGUAGE_STORAGE_KEY = "lapis-cloud-language"

/**
 * Reads a previously saved language choice from `localStorage`, falling back to German -- NOT
 * [io.kvision.i18n.I18n]'s own default of the browser's OS/UI locale ([window.navigator.language]),
 * which would silently show e.g. English to a German-OS-locale visitor whose organization's
 * default working language is German (see CLAUDE.md "Sprache: überwiegend Deutsch"). Falling back
 * to a fixed default here, rather than autodetecting, keeps the vault's own written-language
 * convention as the app's actual default too.
 */
private fun initialLanguage(): String = localStorage[LANGUAGE_STORAGE_KEY] ?: "de"

/** Persists the choice and triggers [io.kvision.i18n.I18n]'s own live-retranslate-in-place. */
private fun setLanguage(code: String) {
    localStorage[LANGUAGE_STORAGE_KEY] = code
    I18n.language = code
}

/**
 * Vertical-Sidebar-Umbau (2026-09-08): the currently-loaded hash route, read directly off
 * `window.location.hash` -- NOT off [NavHighlight]'s own tracked `activeRoute`, which is still
 * `null` at the point `App.start()` first needs this (the boot-time session probe resolves, and
 * fires [AppState.onSessionChange], BEFORE `initRouting` ever calls `Routing.kt`'s `show()` the
 * first time). Reading the raw URL fragment sidesteps that ordering entirely: it reflects
 * whatever deep link the browser already has, independent of whether `kvResolve()` has run yet --
 * exactly what `Sidebar.kt`'s `buildSidebar` needs to decide which group (if any) starts forced
 * open on a fresh page load (see that function's own KDoc).
 */
private fun currentHashRoute(): String? =
    window.location.hash
        .removePrefix("#")
        .ifBlank { null }

/**
 * V0.7.3 Basis-Mehrseiten-UI: replaces the V0.1.5 single-dashboard "acting as" member-switcher
 * demo with a real, multi-screen SPA covering the core domains needed for a first deployment. Each
 * screen lives in its own file (`LoginScreen.kt`, `RegistrationScreen.kt`, `DashboardScreen.kt`,
 * `MemberAdministrationScreen.kt`, `ContributionsScreen.kt`, `DocumentsScreen.kt`,
 * `CommunicationScreen.kt`) -- mirrors the flat, one-file-per-concern convention
 * `lapis-server/.../rpc/` already uses for its services. `Routing.kt` wires hash-based navigation
 * between them; `AppState.kt`/`AuthHttp.kt` hold the real session-cookie auth this file's own
 * previous KDoc always pointed towards.
 */
class App : Application() {
    override fun start() {
        root("lapis-client") {
            // Vertical-Sidebar-Umbau (2026-09-08): `expand = ALWAYS` means the navbar itself never
            // collapses into Bootstrap's own hamburger toggler -- there is nothing left in it that
            // WOULD need one (language switcher + account menu are two small dropdowns, not a long
            // link list any more), and a second, redundant hamburger next to the sidebar's own
            // `toggleButton` below would be confusing (which one opens what?).
            val navbar =
                navbar(
                    label = Branding.title,
                    // Nutzer-Entscheidung V1.4.7 ("überall, auch eingeloggt"): der Marken-Klick
                    // führt konsequent auf die öffentliche Startseite "/", auch aus einer
                    // eingeloggten Tiefenansicht heraus. Echte volle Seitennavigation, KEIN
                    // SPA-Hash-Ziel.
                    link = "/",
                    // MUSS gesetzt sein: main() setzt weiter unten global
                    // `Link.useDataNavigoForLinks = true`. Ohne dieses Opt-out emittiert der
                    // Brand-<a> `data-navigo`, navigo fängt den Klick ab, behandelt "/" als
                    // unbekannte Client-Route und feuert seinen notFound-Handler -- der Link zeigt
                    // dann auf Root und TUT NICHTS. Dasselbe Opt-out-Muster wie LoginScreen.kt's
                    // OIDC-Link und BrandLockup.kt.
                    dataNavigo = false,
                    className = "lapis-navbar",
                    expand = NavbarExpand.ALWAYS,
                )
            // UI/UX-Design-Team-Review 2026-08-14 (Forstall): the brand mark reuses the exact
            // gem-glyph polygon geometry from cloud.lapisproject.dev's Logo.astro, not a
            // reinterpretation, so the deployed app and the marketing site read as one product.
            // `labelFirst = false` puts this child (added below) before the brand title text --
            // see `Link.render()`: labelFirst controls whether the label+icon/image render before
            // or after `childrenVNodes()`, and `Link` is itself a `Container`, so `brandLink.span`
            // is a normal child add, not a special API.
            navbar.brandLink.labelFirst = false
            navbar.brandLink.addCssClass("lapis-brand-text")
            // V1.2.5 White-Label-Branding, three-state rule (UI/UX-Design-Team-Review, Jobs' final
            // review): a custom logo always wins; absent that, the Lapis gem mark shows ONLY next
            // to the untouched default title (an operator who set a custom title but no logo gets
            // no mark at all, rather than a mark that misleadingly still reads "Lapis" branding
            // next to their own name). Rendered as a real `<img src="...">` element (Ive's
            // requirement) via the same `rich = true` HTML-string mechanism the gem mark below
            // already uses -- not KVision's `Link.image` property, whose exact mutation semantics
            // on an already-constructed `Link` were not verified against this pinned KVision
            // version (see V1.2.5 plan "Offene Frage 1"); this path is proven to compile and
            // render in this exact codebase already.
            when {
                Branding.logoUrl != null ->
                    navbar.brandLink.span(
                        content = brandLogoImgHtml(src = Branding.logoUrl!!, alt = Branding.title),
                        rich = true,
                        className = "lapis-brand-logo",
                    )
                Branding.title == Branding.DEFAULT_TITLE ->
                    navbar.brandLink.span(content = LAPIS_GEM_MARK_SVG, rich = true, className = "lapis-brand-mark")
                else -> {
                    // Custom title, no custom logo -- no mark, see comment above.
                }
            }
            // Review-Fund 2026-09-08 (Finding 1, KRITISCH): the toggle button used to be built HERE,
            // once, as a local `val`. `Navbar.add()` (verified against pinned kvision-bootstrap
            // 9.6.0 `Navbar.kt`) unconditionally routes every child -- this button included -- into
            // the navbar's own private collapsible `container` panel, and `refreshNavbar`'s own
            // `navbar.removeAll()` (see that function below) clears exactly that panel. Since
            // `refreshShell()` (which calls `refreshNavbar`) runs synchronously a few lines below,
            // still during boot, the button built here was deleted again before any user could ever
            // see or click it -- live-verified: `.lapis-sidebar-toggle`/`fa-bars` had 0 DOM matches.
            // It is now built fresh inside `refreshNavbar` itself instead, on every call, exactly
            // like every other piece of `container` content that function already throws away and
            // reconstructs (language switcher, account dropdown) -- see that function's own KDoc.

            // `hPanel`, not `vPanel` -- `VPanel` bakes `flex-direction: column` into an INLINE
            // style, which would silently defeat `.lapis-shell`'s side-by-side layout regardless
            // of what theme.css says (inline style always wins over a stylesheet without
            // `!important`). `HPanel` bakes in `flex-direction: row` instead, which is what a
            // sidebar-beside-content layout actually needs -- see this file's own KDoc trail for
            // why this was verified against the pinned KVision 9.6.0 source rather than assumed.
            val shell = hPanel(alignItems = AlignItems.FLEXSTART, className = "lapis-shell")
            // Verified against the pinned kvision-bootstrap 9.6.0 `Offcanvas.kt` source (no local
            // sources jar was available to browse in the IDE) -- `Offcanvas` IS-A `SimplePanel`
            // whose overridden `add()` delegates to its own private `body` panel, so `sidebar`
            // itself (not a separate `sidebar.body`) is exactly the right `SimplePanel` to hand to
            // `buildSidebar` below and to every DSL builder (`.link`/`.button`/...) it uses.
            // `responsiveType = RESPONSIVELG` is the `.offcanvas-lg` class theme.css's `!important`
            // rule (see that file's own comment) forces permanently visible from 992px up -- see
            // plan Abschnitt 7 Stolperfalle 1, the single highest-priority pitfall of this wave.
            val sidebar =
                shell.offcanvas(
                    placement = OffPlacement.START,
                    closeButton = true,
                    responsiveType = OffResponsiveType.RESPONSIVELG,
                    scrollableBody = true,
                    backdrop = true,
                    escape = true,
                    className = "lapis-sidebar",
                )
            // Review-Fund 2026-09-08 (Finding 1, KRITISCH): KVision 9.6.0's `Offcanvas.afterInsert()`
            // unconditionally calls `showBootstrap()` the moment this widget is mounted -- it never
            // consults the KVision-level `visible` flag `init { this.hide() }` set to `false` (see
            // the pinned kvision-bootstrap 9.6.0 `Offcanvas.kt` source, `afterInsert` override).
            // `showBootstrap()` is real Bootstrap 5.3 `Offcanvas.prototype.show()`, which (because
            // `backdrop = true` above) unconditionally creates a full-viewport dark `.offcanvas-
            // backdrop` and locks page scroll -- on EVERY app boot, EVERY viewport size including
            // desktop, since Bootstrap's own JS never consults the `.offcanvas-lg` breakpoint CSS
            // relies on (theme.css's `!important` desktop override is a separate, CSS-only concern,
            // see that file's own comment). Left unfixed, the very first paint of the app -- the
            // login form included -- is instantly covered by that backdrop until a user stumbles
            // onto dismissing it (click-outside or Escape).
            //
            // Cancelled here, not compensated for after the fact (e.g. a follow-up
            // `sidebar.hideBootstrap()`), because compensating would still flash the backdrop
            // on-screen for one animation frame before hiding it again. Bootstrap's real `show()`
            // (bootstrap 5.3.3 `js/src/offcanvas.js`) fires a genuine, cancelable, BUBBLING DOM
            // event `show.bs.offcanvas` as its very first act and returns immediately -- without
            // ever creating the backdrop -- if that event's `defaultPrevented` is true. Listening on
            // `document` (rather than the sidebar's own element, which does not exist in the real
            // DOM yet at this point -- `afterInsert` has not run) still catches it because the event
            // bubbles.
            //
            // Review-Fund 2026-09-08 Runde 4 (Finding 1+2, beide KRITISCH, live verifiziert): the
            // PREVIOUS version of this fix removed the listener from INSIDE the handler, after
            // matching one `show.bs.offcanvas` event on `sidebar`'s own element. That is wrong on
            // both sides of the breakpoint:
            //  - Desktop (>=992px): `refreshShell()` (below) runs a SECOND time as soon as the
            //    boot-time session probe resolves -- even when it resolves back to `null` for the
            //    ordinary anonymous visitor, because `AppState.setSession` (see that file's own
            //    fix) used to fire `onSessionChange` unconditionally. That second `refreshShell()`
            //    call used to unmount-then-immediately-remount the sidebar (`sidebar.hide()`
            //    followed by the eager-mount `sidebar.show()`), firing a SECOND `show.bs.offcanvas`
            //    event that the already-self-removed listener could no longer catch -- Bootstrap's
            //    real backdrop then stayed on screen over the login form. Fixed at the source below:
            //    `refreshShell()`'s anonymous branch no longer unmounts the sidebar when it is about
            //    to be eagerly re-mounted anyway, so `sidebar.show()` becomes the no-op KVision's own
            //    `Widget.visible` setter already promises once nothing actually changed.
            //  - Mobile (<992px): the sidebar never auto-mounts at boot, so the self-removing
            //    listener stayed armed indefinitely -- and the very FIRST `show.bs.offcanvas` event
            //    it could ever catch was the user's own first tap on the hamburger button
            //    (`toggleButton.onClick` in `refreshNavbar` below), which it wrongly swallowed:
            //    `preventDefault()` blocked Bootstrap's real `show()`, so the drawer never actually
            //    became visible even though KVision's `visible` flag (and `aria-expanded`) said it
            //    had. Fixed here instead: the listener's lifetime is now scoped EXPLICITLY to the one
            //    synchronous `refreshShell()` call a few lines below, not to "whichever
            //    `show.bs.offcanvas` event happens to fire first" -- it is removed unconditionally
            //    right after that call returns, whether or not it actually intercepted anything
            //    (mobile: it never does, and is now gone before the user's first real tap can reach
            //    it; desktop: it catches exactly that one boot-time auto-show, same as before).
            lateinit var cancelInitialAutoShow: (Event) -> Unit
            cancelInitialAutoShow = { event ->
                if (event.target == sidebar.getElement()) {
                    event.preventDefault()
                }
            }
            document.addEventListener("show.bs.offcanvas", cancelInitialAutoShow)

            val pageContainer = shell.vPanel(className = "lapis-content")

            // Rebuilds both the navbar (language switcher + account menu, toggle button included)
            // and the sidebar (route list) together -- a single entry point so every trigger that
            // needs both in sync (session change, language switch) only has one function to call,
            // never two calls that could drift out of step with each other.
            fun refreshShell() {
                refreshNavbar(navbar, sidebar, onLanguageChange = ::refreshShell)
                val session = AppState.session
                if (session != null) {
                    // Live-Fund 2026-09-09 (pzb.parteidervernunft.de, ADMIN, Nachfolge-Bug von
                    // V1.4.8): dieser `onNavigate`-Callback lief bislang UNBEDINGT auf jeden
                    // Sidebar-Link-Klick -- korrekt fuer Mobile (Drawer nach der Navigation
                    // schliessen), aber auf Desktop hebt `sidebar.hide()` denselben KVision-
                    // Offcanvas-Mechanismus aus, den `shouldMountSidebarEagerly` weiter oben erst
                    // eigens `sidebar.show()`-t: einmal versteckt, bleibt die Sidebar verschwunden,
                    // weil ausser `refreshShell()` (nur bei Session-/Sprachwechsel) nichts sie je
                    // wieder zeigt -- exakt das live gemeldete Symptom "Menu nur auf dem Dashboard
                    // sichtbar, alle anderen Seiten zeigen kein Menu". Live reproduziert via
                    // `jsBrowserDevelopmentRun` + fingierter ADMIN-Session: `.lapis-sidebar`
                    // verschwand nachweislich aus `.lapis-shell`s Kindern nach jedem Sidebar-Link-
                    // Klick auf einer Desktop-Breite. Guard hier spiegelt exakt das Muster, das der
                    // anonyme Zweig unten fuer denselben Zweck schon verwendet.
                    buildSidebar(sidebar, session, currentHashRoute()) {
                        if (!shouldMountSidebarEagerly(window.innerWidth)) sidebar.hide()
                    }
                } else {
                    // Anonymous (both "not yet known, probe still in flight" and "definitely
                    // logged out") -- no sidebar CONTENT at all, see `refreshNavbar`'s own
                    // `session == null` branch KDoc for why this state is reachable well past boot
                    // (LOGIN/REGISTER/REGISTER_FRIEND/PASSWORD_RESET/VERIFY_EMAIL are all unguarded
                    // routes).
                    clearSidebar(sidebar)
                    // Review-Fund 2026-09-08 Runde 4 (Finding 1, KRITISCH): this used to be an
                    // unconditional `sidebar.hide()` here, on every single call of this function --
                    // including the routine "session probe resolved, still anonymous" call every
                    // ordinary visitor's boot sequence makes. On a desktop viewport that turned every
                    // such call into a real unmount (this `hide()`) immediately followed by a real
                    // remount (the eager-mount `sidebar.show()` below), because the sidebar was
                    // already mounted+visible from the PRECEDING call. That second mount fires a
                    // second, genuine `show.bs.offcanvas` event -- which by then no listener is left
                    // to intercept (see the `cancelInitialAutoShow` comment above) -- so Bootstrap's
                    // real backdrop stayed on screen over the login form. Only `hide()` when we are
                    // NOT about to eagerly remount right below: on mobile this is still the ordinary
                    // "start closed/unmounted" default it always was; on desktop it leaves an
                    // already-mounted sidebar exactly as it is, so the `sidebar.show()` a few lines
                    // down becomes the true no-op `Widget.visible`'s own setter already promises
                    // (only calls `refresh()` on an actual change) instead of a real hide+show cycle.
                    if (!shouldMountSidebarEagerly(window.innerWidth)) {
                        sidebar.hide()
                    }
                }
                // Review-Fund 2026-09-08 (Finding 2, KRITISCH): `Offcanvas` starts KVision-invisible
                // (its own `init { hide() }`), and `SimplePanel.childrenVNodes()` (verified against
                // pinned kvision 9.6.0 `SimplePanel.kt`) completely EXCLUDES an invisible child from
                // the rendered DOM -- not merely CSS-hidden, genuinely never inserted. theme.css's
                // own `@media (min-width: 992px)` `!important` override therefore had nothing to act
                // on: live-verified, `document.querySelectorAll('[class*=offcanvas]')` had 0
                // matches, on every viewport, for every session. Mounting it here -- on EVERY
                // `refreshShell()` call, not just once at boot -- is what actually fixes that: a
                // no-op once already mounted (`Widget.visible`'s own setter only calls `refresh()`
                // on an actual change) as long as the anonymous branch above did not just force an
                // unmount, which (see that branch's own comment) it now no longer does on desktop.
                // Below the breakpoint this is always a no-op too (`shouldMountSidebarEagerly`
                // returns `false`) -- the mobile toggle button owns mounting/unmounting entirely via
                // `Offcanvas.toggle()`'s own normal open/close cycle, exactly as the library intends;
                // nothing here fights that path. A `resize` listener that re-evaluates this
                // mid-session (e.g. a desktop window narrowed below, or widened past, 992px without a
                // reload) is deliberately NOT added -- out of scope for this fix, no finding asked
                // for it, and `refreshShell()` already re-asserts this on every session/language
                // change that happens to occur near the breakpoint.
                if (shouldMountSidebarEagerly(window.innerWidth)) {
                    sidebar.show()
                }
            }
            refreshShell()
            // Review-Fund 2026-09-08 Runde 4 (Finding 2, KRITISCH): removed HERE, immediately after
            // the one synchronous call above that can ever legitimately trigger the boot-time
            // auto-show `cancelInitialAutoShow` exists to cancel -- unconditionally, whether or not
            // that call actually mounted the sidebar (desktop: it did, and the event was just
            // intercepted; mobile: it never mounts at boot, so this is simply tidying up an armed
            // listener that never fired). Explicit removal here, rather than the event handler
            // removing itself after its first match, is what keeps this listener from also being
            // armed for -- and wrongly swallowing -- the mobile hamburger button's own first,
            // genuinely user-triggered `show.bs.offcanvas` event later on (`toggleButton.onClick` in
            // `refreshNavbar` below): that first tap could otherwise become the "first `show.bs.
            // offcanvas` event ever" the old self-removing handler was waiting to match, silently
            // cancelling the very open the user just asked for.
            document.removeEventListener("show.bs.offcanvas", cancelInitialAutoShow)
            lapisAttribution()

            initNotifications()
            AppState.onSessionChange = ::refreshShell

            AppScope.launch {
                // Boot-time session probe -- deliberately NOT routed through `guarded()`: an
                // anonymous first-time visitor failing this call is the ordinary, expected case,
                // not a "your session just expired" event, so no error toast here (unlike every
                // other call site in this app, which DOES want that toast).
                val session =
                    try {
                        rpcService<IAuthService>().getSessionInfo()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                AppState.setSession(session)
                // Routing is initialized only AFTER the probe resolves, so the very first hash
                // resolution already sees the correct auth state -- see `initRouting` KDoc.
                initRouting(pageContainer)
            }
        }
    }
}

/**
 * Vertical-Sidebar-Umbau (2026-09-08): shrunk from the pre-umbau version's ~230 lines (six
 * role-gated dropdowns) down to just the navbar's own remaining chrome -- language switcher +
 * either a bare "Anmelden" link (no session) or the account menu (session present). Every route
 * link that used to live here now lives in `Sidebar.kt`'s `buildSidebar` instead -- see that
 * file's own KDoc for the 1:1 role-gating correspondence. Deliberately does NOT touch
 * [NavHighlight] any more (no `reset()`/`apply()` call here) -- none of this function's links are
 * route destinations that should ever carry an active-route highlight (Jobs' review call: the
 * navbar header is identity/session chrome, not "where am I", see [NavHighlight]'s own KDoc).
 *
 * [onLanguageChange] is `App.kt`'s `refreshShell` -- picking a language must also rebuild the
 * sidebar (every `tr()`-marked sidebar label needs to re-render in the new language too, not just
 * this navbar), which this function itself has no reference to.
 *
 * Review-Fund 2026-09-08 (Finding 1, KRITISCH) -- [sidebar]'s mobile toggle button is built HERE
 * now, on every call, instead of once in `App.start()`: `Navbar.add()` (verified against pinned
 * kvision-bootstrap 9.6.0 `Navbar.kt`) unconditionally routes every child added to a `Navbar` --
 * this button included -- into the navbar's own private collapsible `container` panel, and
 * `navbar.removeAll()` below clears exactly that panel. A button built once elsewhere and merely
 * referenced here would still be destroyed by that call; building it fresh here, every time,
 * keeps it in step with everything else this function already throws away and reconstructs
 * (language switcher, account dropdown) -- see [App.start] for the live-DOM verification that
 * found this (0 matches for `.lapis-sidebar-toggle`/`fa-bars`) and the previous, dead code.
 */
private fun refreshNavbar(
    navbar: Navbar,
    sidebar: Offcanvas,
    onLanguageChange: () -> Unit,
) {
    navbar.removeAll()
    // Sprachumschalter-Feature 2026-08-14: the navbar is no longer hidden for anonymous sessions
    // (previously `navbar.hide(); return` here) -- a first-time visitor on the login/registration
    // screens needs the language switcher just as much as an authenticated member does, arguably
    // more (they haven't yet reached anything else translatable).
    navbar.show()
    val session = AppState.session

    // Vertical-Sidebar-Umbau (2026-09-08): a real `<button>` (not a hand-rolled div), so
    // Enter/Space/click all just work -- `ButtonStyle.LINK` strips Bootstrap's default button
    // chrome (border/background), `.lapis-sidebar-toggle` in theme.css then paints it to match the
    // dark navbar's other controls (and hides it entirely at >=992px, see that rule's own
    // comment -- desktop has nothing to toggle). Built BEFORE the `session == null` early return
    // below, not after, so it stays present regardless of session state, exactly like the
    // language switcher -- the ORIGINAL pre-Finding-1 code also built it unconditionally.
    val toggleButton =
        navbar.button(
            "",
            icon = "fas fa-bars",
            style = ButtonStyle.LINK,
            className = "lapis-sidebar-toggle",
        )
    toggleButton.setAttribute("aria-label", tr("Menü"))
    toggleButton.setAttribute("aria-controls", sidebar.id ?: "")
    // Review-Fund 2026-09-08 (Finding 3, MINOR/Barrierefreiheit, carried forward): reflects
    // [sidebar]'s ACTUAL current state, not a hardcoded "false" -- this function reruns on every
    // language switch too, and the mobile drawer may already be open when that happens.
    toggleButton.setAttribute("aria-expanded", sidebar.visible.toString())
    toggleButton.onClick {
        sidebar.toggle()
        // `sidebar.visible` is KVision's own tracked flag, updated synchronously by `toggle()`'s
        // `show()`/`hide()` call before this line runs, so it always reflects the state this very
        // click just produced.
        toggleButton.setAttribute("aria-expanded", sidebar.visible.toString())
    }

    val rightNav: Nav = navbar.nav(rightAlign = true)
    addLanguageSwitcher(rightNav, onLanguageChange)

    if (session == null) {
        // Design-Team-Review Runde 6 (2026-09-08): shown unconditionally whenever there is no
        // session -- NOT only during the brief boot-time probe gap. `Routing.kt`'s `requireAuth`/
        // `requireRole` redirect every OTHER route to `Routes.LOGIN` on a missing session, but
        // LOGIN/REGISTER/REGISTER_FRIEND/PASSWORD_RESET/VERIFY_EMAIL themselves stay genuinely,
        // durably reachable without one (verified against `Routing.kt` Z. 463-483/644-649) -- so
        // this is a real, sustained anonymous state, not a theoretical millisecond window, and
        // deserves a real way back into a session. Deliberately NOT [NavHighlight]-registered --
        // see this function's own KDoc.
        rightNav.navLink(tr("Anmelden"), url = "#${Routes.LOGIN}", icon = "fas fa-right-to-bracket")
        return
    }

    val accountLabel =
        if (session.isGuest && session.homeserverUrl != null) {
            gettext("%1 (Gast)", session.displayName)
        } else {
            gettext("%1 (%2)", session.displayName, session.role)
        }
    rightNav.dropDown(accountLabel, icon = "fas fa-user", forNavbar = true) {
        // V0.8.4 Guest Badge, moved here from the old disabled navbar span (Vertical-Sidebar-
        // Umbau, 2026-09-08): the dropdown's own trigger stays plain text (no icon widget in a
        // `DropDown`'s button label slot, see `DropDown.text`/`DropDownButton` -- only a `String`
        // is accepted there), so the badge -- and the popover interaction it carries -- moves into
        // the dropdown BODY as a non-interactive first item instead. `dropdown-item-text` is
        // Bootstrap's own class for exactly this ("content row that isn't itself a clickable
        // `.dropdown-item`"). `homeserverUrl != null` defensive guard -- see `GuestBadge.kt`
        // `guestBadge` KDoc.
        if (session.isGuest && session.homeserverUrl != null) {
            span(className = "dropdown-item-text d-flex align-items-center gap-2") {
                guestBadge(session.homeserverUrl!!)
                span(gettext("Gast von %1", session.homeserverUrl!!))
            }
            separator()
        }
        // Deliberately plain `ddLink`, NOT [NavHighlight]-registered -- see this function's own
        // KDoc "Jobs' review call".
        ddLink(tr("Mein Konto"), url = "#${Routes.DASHBOARD}")
        ddLink(tr("Meine Daten"), url = "#${Routes.DSGVO_RIGHTS}")
        separator()
        // dataNavigo = false: rein lokaler Klick-Handler (kein Ziel-Route) -- ohne dieses Opt-out
        // feuert navigo (globales Link.useDataNavigoForLinks = true, siehe main()) auf demselben
        // Klick zusaetzlich seinen notFound-Handler und navigiert; funktioniert bisher nur
        // zufaellig, weil AuthHttp.logout() ohnehin bei Routes.LOGIN landet (V1.2.4-Audit,
        // dataNavigo-Sweep).
        val logoutLink =
            ddLink(
                tr("Abmelden"),
                url = "javascript:void(0)",
                icon = "fas fa-right-from-bracket",
                dataNavigo = false,
            )
        logoutLink.onClick {
            AppScope.launch {
                AuthHttp.logout()
                AppState.setSession(null)
                navigateTo(Routes.LOGIN)
            }
        }
    }
}

/**
 * Sprachumschalter-Feature 2026-08-14: a compact `fas fa-globe` dropdown showing every supported
 * language by its own native name ([SUPPORTED_LANGUAGES]), button label = the active language's
 * two-letter code. Placed first in [rightNav] so it survives the anonymous-session early return
 * in [refreshNavbar] above (everything after it in that function is session-gated). Selecting an
 * entry calls [setLanguage], which sets [io.kvision.i18n.I18n.language] -- KVision's own
 * mechanism for this re-resolves every `tr()`/`gettext()`-marked label across the WHOLE app on its
 * own (see `Root.restart()` in `I18n.language`'s setter), so this function only needs to trigger
 * [onLanguageChange] (App.kt's `refreshShell`) afterward, not rebuild anything itself.
 */
private fun addLanguageSwitcher(
    rightNav: Nav,
    onLanguageChange: () -> Unit,
) {
    val current = SUPPORTED_LANGUAGES.firstOrNull { it.first == I18n.language } ?: SUPPORTED_LANGUAGES.first()
    rightNav.dropDown(current.first.uppercase(), icon = "fas fa-globe", forNavbar = true) {
        SUPPORTED_LANGUAGES.forEach { (code, nativeName) ->
            // dataNavigo = false: rein lokaler Klick-Handler, keine Route (V1.2.4-Audit,
            // dataNavigo-Sweep) -- siehe Kommentar bei "Abmelden" oben.
            val link = ddLink(nativeName, url = "javascript:void(0)", dataNavigo = false)
            if (code == current.first) {
                link.addCssClass("active")
            }
            link.onClick {
                setLanguage(code)
                onLanguageChange()
            }
        }
    }
}

fun main() {
    // Critical fix (found+fixed during V0.7.3 review round 1): every `navLink(...)`/`link(...)`
    // call in this app (Routing.kt's own KDoc notwithstanding) passes only `url = "#/x"`, never
    // `dataNavigo = true` -- and `io.kvision.html.Link.useDataNavigoForLinks` defaults to `false`.
    // Without one of those two, `Link.buildAttributeSet` never emits the `data-navigo` attribute,
    // so kvision-routing-navigo-ng's own click-hijacking (`linksSelector`) never recognizes these
    // anchors as SPA-routed links: a real click just performs the browser's native, un-intercepted
    // hash-fragment update -- `location.hash` changes, but no `Routing.kvOn(...)` handler ever
    // fires, so the visible screen never changes. Verified end-to-end in a real browser against
    // both the production and development webpack bundles: every nav-link/tile click (Beiträge,
    // Dokumente, Kommunikation, Mitgliederverwaltung, the Dashboard "Bereiche" tiles) silently did
    // nothing -- only the explicit, programmatic `routing.navigate(...)` call sites (post-login,
    // post-logout, the boot-time `/` resolve, `guarded()`'s session-expiry redirect) worked, because
    // those bypass link-hijacking entirely. Setting this flag globally, once, before any `Link` is
    // ever constructed (i.e. here in `main()`, before `startApplication`) is the standard KVision
    // fix -- see `io.kvision.html.Link` companion object KDoc -- and is simpler and less error-prone
    // than threading `dataNavigo = true` through every individual `navLink`/`link`/`navTile` call
    // site across every screen file.
    Link.useDataNavigoForLinks = true
    // Sprachumschalter-Feature 2026-08-14: sets the active language BEFORE the first render (a
    // post-render set would flash German content, then immediately re-render in the saved
    // language). `I18n.manager` uses `I18nCatalogManager` -- see that class's own KDoc for why
    // it exists instead of KVision's own `kvision-i18n` module (`DefaultI18nManager` crashes the
    // app on load, an upstream `gettext.js` interop bug, not something to route around here).
    // The catalogs below are AI-translated (2026-08-15, all 1491 extracted strings) from the
    // compiled `messages-<lang>.json` resources (`generatePotFile` -> translated `.po` ->
    // `convertPoToJson`); German itself needs no catalog since it's the source language baked
    // directly into every `tr()`/`gettext()` call's own argument.
    I18n.language = initialLanguage()
    I18n.manager =
        I18nCatalogManager(
            mapOf(
                "en" to messagesEn,
                "fr" to messagesFr,
                "es" to messagesEs,
                "it" to messagesIt,
                "nl" to messagesNl,
                "pl" to messagesPl,
                "ru" to messagesRu,
            ),
        )
    // UI/UX-Design-Team-Review 2026-08-14, revised 2026-08-20 (theme redesign wave): loads
    // theme.css into the webpack bundle -- white ground + near-black navbar + a more saturated
    // lapis blue, a deliberate divergence from cloud.lapisproject.dev's own papyrus/cream palette
    // for this admin app specifically (see that file's own header comment). `js("require(...)")`
    // is the standard Kotlin/JS idiom for a raw stylesheet import under `cssSupport { enabled.set(true) }`
    // (see lapis-client/build.gradle.kts) -- css-loader/style-loader inject it as a `<style>` tag at
    // runtime, same mechanism BootstrapCssModule already relies on internally for Bootstrap's own CSS.
    js("require('./theme.css')")
    registerRemoteTypes()
    // Bug fix: startApplication() previously registered no CSS modules at all -- KVision only
    // requires Bootstrap's CSS (and its own base styles) when the corresponding module is passed
    // here explicitly; it is NOT pulled in automatically just because kvision-bootstrap is a
    // Gradle dependency. Without this, the app rendered as completely unstyled HTML (raw browser
    // default link/button styling, no navbar chrome, no icons) despite every Bootstrap CSS class
    // name being present in the DOM -- found live on the first real deployment (2026-08-14), where
    // it had gone unnoticed because prior verification only checked RPC/functional behavior, never
    // a visual screenshot of the production bundle.
    startApplication(::App, null, CoreModule, BootstrapModule, BootstrapCssModule, FontAwesomeModule)
}
