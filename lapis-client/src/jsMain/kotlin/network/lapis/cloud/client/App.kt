package network.lapis.cloud.client

import io.kvision.Application
import io.kvision.BootstrapCssModule
import io.kvision.BootstrapModule
import io.kvision.CoreModule
import io.kvision.FontAwesomeModule
import io.kvision.dropdown.DropDown
import io.kvision.dropdown.ddLink
import io.kvision.dropdown.dropDown
import io.kvision.html.Link
import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.navbar.Nav
import io.kvision.navbar.Navbar
import io.kvision.navbar.nav
import io.kvision.navbar.navLink
import io.kvision.navbar.navLinkDisabled
import io.kvision.navbar.navbar
import io.kvision.panel.root
import io.kvision.panel.vPanel
import io.kvision.remote.registerRemoteTypes
import io.kvision.startApplication
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.IAuthService
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

/**
 * V1.2.5 White-Label-Branding -- builds a real `<img>` tag for [App.start]'s navbar brand-mark
 * `rich = true` span (see that call site's own comment for why this path, not `Link.image`).
 * `src`/`alt` are HTML-attribute-escaped even though both ultimately derive from server-injected,
 * already-escaped values (`Branding.logoUrl`/`Branding.title`, themselves sourced from
 * `network.lapis.cloud.server.branding.BrandingHtml`'s own JSON-context escaping) -- defense in
 * depth costs nothing here and this function has no other caller to rely on that upstream
 * discipline never changing.
 */
private fun brandLogoImgHtml(
    src: String,
    alt: String,
): String = """<img src="${escapeHtmlAttribute(src)}" alt="${escapeHtmlAttribute(alt)}">"""

private fun escapeHtmlAttribute(value: String): String =
    buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

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
            val navbar = navbar(label = Branding.title, link = "#${Routes.DASHBOARD}", className = "lapis-navbar")
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
            refreshNavbar(navbar)
            val pageContainer = vPanel()
            lapisAttribution()

            initNotifications()
            AppState.onSessionChange = { refreshNavbar(navbar) }

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
 * Nav-Highlight-Welle 2026-08-20: [Nav.navLink] wrapper that additionally registers the resulting
 * [Link] with [NavHighlight] under its target [route] -- every top-level nav link goes through
 * this instead of calling `navLink` directly, so the active-route highlight (see [NavHighlight])
 * covers it without each call site remembering to register itself.
 */
private fun Nav.routedNavLink(
    route: String,
    label: String,
    icon: String,
): Link = navLink(label, url = "#$route", icon = icon).also { NavHighlight.register(route, it) }

/**
 * Nav-Highlight-Welle 2026-08-20: [DropDown.ddLink] wrapper that additionally registers the
 * resulting [Link] with [NavHighlight] under its target [route], AND ties it to the enclosing
 * dropdown's own header button ([DropDown.button]) so the header itself also lights up when one of
 * its entries is the active route -- see [NavHighlight] KDoc.
 */
private fun DropDown.routedDdLink(
    route: String,
    label: String,
    icon: String,
): Link = ddLink(label, url = "#$route", icon = icon).also { NavHighlight.register(route, it, button) }

private fun refreshNavbar(navbar: Navbar) {
    navbar.removeAll()
    NavHighlight.reset()
    // Sprachumschalter-Feature 2026-08-14: the navbar is no longer hidden for anonymous sessions
    // (previously `navbar.hide(); return` here) -- a first-time visitor on the login/registration
    // screens needs the language switcher just as much as an authenticated member does, arguably
    // more (they haven't yet reached anything else translatable). It now always shows brand +
    // language switcher; the rest (left-side app nav, session display, Abmelden) stays
    // session-gated exactly as before.
    navbar.show()
    val session = AppState.session

    val rightNav: Nav = navbar.nav(rightAlign = true)
    addLanguageSwitcher(rightNav, navbar)

    if (session == null) {
        return
    }

    // UI/UX-Design-Team-Review 2026-08-14 (Norman/Raskin): the previous 20-entry flat `navLink`
    // list overflowed the viewport width and hid entries with no visual indication anything was
    // cut off. Regrouped by mental model into dropdowns instead of narrowing/wrapping the same
    // flat list -- "Dashboard"/"Videokonferenz" stay top-level as the two highest-frequency
    // destinations, everything else moves into a themed dropdown. Role-gating is UNCHANGED from
    // the prior flat list: every route/role pair below is identical to before, only the grouping
    // changed -- the three `if (AppState.hasRole(...))` blocks map exactly onto the three
    // role-gated dropdowns (Finanzen/Verwaltung/System) because the original flat list already
    // grouped its role-gated entries contiguously by tier, see each dropdown's own comment for
    // the KDoc cross-references the flat-list version carried per link.
    val leftNav: Nav = navbar.nav()
    leftNav.routedNavLink(Routes.DASHBOARD, tr("Dashboard"), icon = "fas fa-house")
    leftNav.routedNavLink(Routes.CONFERENCE, tr("Videokonferenz"), icon = "fas fa-video")

    // requireAuth-tier, unconditional for every authenticated ORGANIZATION member -- see
    // `Routes.CONTRIBUTIONS`/`DOCUMENTS`/`COMMUNICATION`/`DSGVO_RIGHTS` KDoc for the per-route
    // verification. "Meine Daten" self-adapts its content per role (ADMIN-only "Anträge verwalten"
    // queue lives inside the screen) rather than forking the nav label, unchanged from the prior
    // flat-list comment.
    //
    // V0.11.0, umgebaut in Welle V1.1.4: die drei Dropdowns unten waren bis V1.1.3 pauschal auf
    // `session.status != MemberStatus.FRIEND` gegattert. Seit V1.1.4 ist ein FRIEND
    // [network.lapis.cloud.shared.domain.MemberStatusSets.LTR_ELIGIBLE] und darf LTR halten/im
    // sozialen Netz ausgeben (siehe [MembershipGuards.requireLtrEligibleMembership] KDoc) --
    // "Wirtschaft" (LTR-Konto, Soziales Netzwerk) und "Meine Daten" (DSGVO-Betroffenenrechte)
    // öffnen sich deshalb jetzt fein granular über [NavVisibility]s sieben Prädikate, während
    // Governance/Crowdfunding/Auktion/Politiker/Beiträge/Dokumente/Kommunikation weiterhin
    // ausschliesslich ORGANIZATION_MEMBER (ACTIVE) vorbehalten bleiben -- für die genau diese Sets
    // wäre eine RPC von einem FRIEND aus weiterhin ein garantiertes 403.
    if (NavVisibility.showsMembershipSection(session.status)) {
        leftNav.dropDown(tr("Mitgliedschaft"), icon = "fas fa-id-card", forNavbar = true) {
            routedDdLink(Routes.CONTRIBUTIONS, tr("Beiträge"), icon = "fas fa-coins")
            routedDdLink(Routes.DOCUMENTS, tr("Dokumente"), icon = "fas fa-file-lines")
            routedDdLink(Routes.COMMUNICATION, tr("Kommunikation"), icon = "fas fa-envelope")
            // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- reachable by every
            // authenticated member, see Routes.DONATE KDoc.
            routedDdLink(Routes.DONATE, tr("Spenden"), icon = "fas fa-hand-holding-heart")
            routedDdLink(Routes.DSGVO_RIGHTS, tr("Meine Daten"), icon = "fas fa-shield-halved")
        }
    } else if (NavVisibility.showsDsgvoRights(session.status)) {
        // Welle V1.1.4: ein FRIEND hat kein volles "Mitgliedschaft"-Dropdown (Beiträge/Dokumente/
        // Kommunikation bleiben ORGANIZATION_MEMBER-exklusiv), braucht aber trotzdem einen
        // erreichbaren Betroffenenrechte-Einstieg, sobald er eigene, potenziell öffentlich
        // indexierte Inhalte erzeugt (Art. 12 Abs. 2 DSGVO) -- siehe Plan Teil 0.7. Einzelner
        // Top-Level-Link statt eines Ein-Eintrag-Dropdowns.
        leftNav.routedNavLink(Routes.DSGVO_RIGHTS, tr("Meine Daten"), icon = "fas fa-shield-halved")
    }
    if (NavVisibility.showsSelfGovernance(session.status)) {
        // requireAuth-tier -- see `Routes.COMMITTEES`/`MEETINGS`/`MOTIONS` KDoc.
        leftNav.dropDown(tr("Selbstverwaltung"), icon = "fas fa-people-group", forNavbar = true) {
            routedDdLink(Routes.COMMITTEES, tr("Gremien"), icon = "fas fa-people-group")
            routedDdLink(Routes.MEETINGS, tr("Sitzungen"), icon = "fas fa-calendar-days")
            routedDdLink(Routes.MOTIONS, tr("Anträge"), icon = "fas fa-file-signature")
        }
    }
    if (NavVisibility.showsEconomySection(session.status)) {
        // requireAuth-tier -- see `Routes.LTR_LEDGER`/`CROWDFUNDING`/`AUCTION`/`POLITICIANS` KDoc for
        // the per-route verification; narrower role-gated sub-sections inside these screens (e.g.
        // Auktion's ADMIN-only Verwaltung, Politiker's BOARD/ADMIN Verwaltung) are unchanged, still
        // gated INSIDE the screen, not via separate nav entries.
        leftNav.dropDown(tr("Wirtschaft"), icon = "fas fa-coins", forNavbar = true) {
            if (NavVisibility.showsLtrLedger(session.status)) {
                routedDdLink(Routes.LTR_LEDGER, tr("LTR-Konto"), icon = "fas fa-wallet")
            }
            if (NavVisibility.showsMemberOnlyEconomy(session.status)) {
                routedDdLink(Routes.CROWDFUNDING, tr("Crowdfunding"), icon = "fas fa-hand-holding-heart")
                routedDdLink(Routes.AUCTION, tr("Auktion"), icon = "fas fa-gavel")
                routedDdLink(Routes.POLITICIANS, tr("Politiker"), icon = "fas fa-landmark")
            }
            // Soziales Netzwerk, Welle V1.1.1 -- see `Routes.SOCIAL_NETWORK` KDoc for the role-gate
            // verification. Placed in "Wirtschaft" (not "Mitgliedschaft"/"Selbstverwaltung") because
            // `createPost` binds LTR from the author's free balance, same economic-weight posture as
            // Crowdfunding/Auktion/Politiker above, not a pure membership/self-governance feature.
            // Seit V1.1.4 auch für FRIEND (LTR_ELIGIBLE) sichtbar.
            if (NavVisibility.showsSocialNetwork(session.status)) {
                routedDdLink(Routes.SOCIAL_NETWORK, tr("Soziales Netzwerk"), icon = "fas fa-comments")
            }
        }
    }
    // Accounting UI wave, design decision D15 -- gated on TREASURER/BOARD/ADMIN (the same three
    // roles the LEDGER route itself requires), so a plain MEMBER never even sees this dropdown
    // render. See `Routes.LEDGER`/`FINANCIAL_REPORTS`/`COMPLIANCE_REPORTS`/`COST_CENTERS`/
    // `DONORS`/`AUDIT_LOG`/`POSTAL_MAIL`/`PRICE_ORACLE` KDoc for the per-route verification.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.dropDown(tr("Finanzen"), icon = "fas fa-chart-line", forNavbar = true) {
            routedDdLink(Routes.LEDGER, tr("Kontenplan & Journal"), icon = "fas fa-book")
            routedDdLink(Routes.FINANCIAL_REPORTS, tr("Finanzberichte"), icon = "fas fa-chart-pie")
            routedDdLink(
                Routes.COMPLIANCE_REPORTS,
                tr("Gemeinnützigkeits-Berichte"),
                icon = "fas fa-scale-balanced",
            )
            routedDdLink(Routes.COST_CENTERS, tr("Kostenstellen"), icon = "fas fa-tags")
            routedDdLink(Routes.DONORS, tr("Spender"), icon = "fas fa-heart")
            routedDdLink(Routes.AUDIT_LOG, tr("Prüfprotokoll"), icon = "fas fa-magnifying-glass")
            routedDdLink(Routes.POSTAL_MAIL, tr("Postversand"), icon = "fas fa-envelope-open-text")
            routedDdLink(Routes.PRICE_ORACLE, tr("Price-Oracle"), icon = "fas fa-chart-simple")
            // V1.2.2 SEPA-Client-UI wave -- see Routes.SEPA_MANDATES/SEPA_BATCHES KDoc for the
            // TREASURER/BOARD/ADMIN role-gate verification.
            routedDdLink(Routes.SEPA_MANDATES, tr("SEPA-Mandate"), icon = "fas fa-file-contract")
            routedDdLink(Routes.SEPA_BATCHES, tr("SEPA-Lastschrift"), icon = "fas fa-money-check-dollar")
            // Client-UI wave for GitHub Issue #5 -- see Routes.DUNNING_CASES KDoc for the
            // TREASURER/BOARD/ADMIN role-gate verification.
            routedDdLink(Routes.DUNNING_CASES, tr("Mahnwesen"), icon = "fas fa-file-invoice-dollar")
            // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- see
            // Routes.PAYMENT_TRANSACTIONS KDoc for the TREASURER/BOARD/ADMIN role-gate verification.
            routedDdLink(Routes.PAYMENT_TRANSACTIONS, tr("Zahlungseingänge"), icon = "fas fa-credit-card")
        }
    }
    // BOARD/ADMIN-tier -- see `Routes.MEMBERS`/`DSGVO_COMPLIANCE`/`BOARD_MEMBERSHIP` KDoc.
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.dropDown(tr("Verwaltung"), icon = "fas fa-user-gear", forNavbar = true) {
            routedDdLink(Routes.MEMBERS, tr("Mitgliederverwaltung"), icon = "fas fa-users-gear")
            routedDdLink(Routes.DSGVO_COMPLIANCE, tr("DSGVO-Compliance"), icon = "fas fa-shield-halved")
            routedDdLink(
                Routes.BOARD_MEMBERSHIP,
                tr("Vorstand & Transparenzregister"),
                icon = "fas fa-landmark-flag",
            )
            // Welle V1.1.5 -- siehe `Routes.SOCIAL_MODERATION` KDoc für die Rollen-Verifikation.
            routedDdLink(Routes.SOCIAL_MODERATION, tr("Moderation"), icon = "fas fa-flag")
            // Welle V1.3.1 "API-Fundament, lesend" -- siehe `Routes.API_KEYS` KDoc für die
            // Rollen-Verifikation. Kein eigener Hauptmenüpunkt (Design-Team-Entscheidung #10) --
            // Einstieg über dieses bereits vorhandene BOARD/ADMIN-Dropdown.
            routedDdLink(Routes.API_KEYS, tr("API-Schlüssel"), icon = "fas fa-key")
        }
    }
    // ADMIN-only-tier -- see `Routes.BACKUP`/`CONFERENCE_STREAM_DESTINATIONS` KDoc.
    if (AppState.hasRole(AccountRole.ADMIN)) {
        leftNav.dropDown(tr("System"), icon = "fas fa-server", forNavbar = true) {
            routedDdLink(Routes.BACKUP, tr("Backup & Wiederherstellung"), icon = "fas fa-database")
            routedDdLink(
                Routes.CONFERENCE_STREAM_DESTINATIONS,
                tr("Stream-Ziele"),
                icon = "fas fa-satellite-dish",
            )
            // V1.2.2 SEPA-Client-UI wave -- see Routes.SEPA_SETTINGS KDoc for the ADMIN-only
            // role-gate verification.
            routedDdLink(Routes.SEPA_SETTINGS, tr("SEPA-Konfiguration"), icon = "fas fa-building-columns")
            // Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- see
            // Routes.PAYMENT_GATEWAY_SETTINGS KDoc for the ADMIN-only role-gate verification.
            routedDdLink(Routes.PAYMENT_GATEWAY_SETTINGS, tr("Zahlungs-Konfiguration"), icon = "fas fa-hand-holding-dollar")
            // Client-UI wave for GitHub Issue #5 -- see Routes.DUNNING_SETTINGS KDoc for the
            // ADMIN-only role-gate verification.
            routedDdLink(Routes.DUNNING_SETTINGS, tr("Mahnwesen-Konfiguration"), icon = "fas fa-scale-unbalanced")
            // Welle V1.4.1a "Öffentliche Website-Integration" -- see Routes.EMBED_INTEGRATION KDoc
            // for the ADMIN-only role-gate verification.
            routedDdLink(Routes.EMBED_INTEGRATION, tr("Website-Integration"), icon = "fas fa-code")
        }
    }

    // V0.8.4 Guest Badge: a federated OIDC guest session gets a visual indicator in place of the
    // ordinary "(role)" display -- a non-guest session's display below is completely unchanged.
    // homeserverUrl != null is a defensive guard (see GuestBadge.kt guestBadge KDoc): it should
    // always be set for a real guest (OidcGuestProfileTable is 1:1 with a GUEST member), but the
    // DTO models it as nullable, so we don't force-unwrap without a check -- falling back to the
    // ordinary display is safer than crashing or showing a badge with no home-server text.
    if (session.isGuest && session.homeserverUrl != null) {
        rightNav.span(className = "nav-item nav-link disabled d-flex align-items-center gap-2") {
            guestBadge(session.homeserverUrl!!)
            span(gettext("%1 (Gast)", session.displayName))
        }
    } else {
        rightNav.navLinkDisabled(gettext("%1 (%2)", session.displayName, session.role), icon = "fas fa-user")
    }
    // dataNavigo = false: rein lokaler Klick-Handler (kein Ziel-Route) -- ohne dieses Opt-out
    // feuert navigo (globales Link.useDataNavigoForLinks = true, siehe main()) auf demselben Klick
    // zusaetzlich seinen notFound-Handler und navigiert; funktioniert bisher nur zufaellig, weil
    // AuthHttp.logout() ohnehin bei Routes.LOGIN landet (V1.2.4-Audit, dataNavigo-Sweep).
    val logoutLink =
        rightNav.navLink(
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
    NavHighlight.apply()
}

/**
 * Sprachumschalter-Feature 2026-08-14: a compact `fas fa-globe` dropdown showing every supported
 * language by its own native name ([SUPPORTED_LANGUAGES]), button label = the active language's
 * two-letter code. Placed first in [rightNav] so it survives the anonymous-session early return
 * in [refreshNavbar] above (everything after it in that function is session-gated). Selecting an
 * entry calls [setLanguage], which sets [io.kvision.i18n.I18n.language] -- KVision's own
 * mechanism for this re-resolves every `tr()`/`gettext()`-marked label across the WHOLE app on its
 * own (see `Root.restart()` in `I18n.language`'s setter), so this function only needs to rebuild
 * the switcher's own button text afterward, not the rest of the navbar.
 */
private fun addLanguageSwitcher(
    rightNav: Nav,
    navbar: Navbar,
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
                refreshNavbar(navbar)
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
