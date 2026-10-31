package network.lapis.cloud.client

import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto

/**
 * V1.9.220 -- what the navigation needs to know about a person: the session and the (asynchronously loaded) answer of the member finder.
 * The predicates of [NavRegistry] read ONLY this, never [AppState], so a test can pass any person explicitly.
 */
internal data class NavContext(
    val session: SessionInfoDto,
    val memberFinderOffered: Boolean?,
) {
    /** Same meaning as [AppState.hasRole], but for the session of this context. */
    fun hasRole(vararg roles: AccountRole): Boolean = session.role in roles
}

/** Label and icon of a sidebar group (shared by the sidebar header and the headings of the dashboard). */
internal data class NavGroupMeta(
    val label: String,
    val icon: String,
)

/**
 * V1.9.224 -- the two sidebar entries that carry a live "(aktiv/gesamt)" suffix (see [NavCountsPoller]). The suffix is information, not a
 * request: a badge (the approval counters of the finance group) says "something waits for you", the suffix says "this is how busy it is".
 */
internal enum class NavLiveSuffix { CONFERENCE, ENCOUNTER }

/**
 * One destination of the navigation. [group] is `null` for a flat (top-level) link. [visible] is the row-level gate; the gate of the group
 * ([NavRegistry.groupVisible]) is a separate predicate on purpose, so a group without a visible row is not built any differently than before.
 */
internal data class NavEntry(
    val route: String,
    val label: String,
    val icon: String,
    val group: SidebarGroupId?,
    val visible: (NavContext) -> Boolean,
    val liveSuffix: NavLiveSuffix? = null,
)

/**
 * V1.9.220 -- the single list of navigation destinations. The sidebar ([buildSidebar]) and the dashboard (its signal cards need the icon, the title and
 * the visibility of a module) both use it, so they cannot drift apart. A new menu item belongs here and nowhere else (plus an enhancement in `Sidebar.kt` if it needs a live counter).
 *
 * Order = the order the sidebar had before the registry (pinned by `SidebarGoldenTest`). Flat entries come first, then the groups in
 * [SidebarGroupId] order; the only flat entry that used to stand between groups (Meine Daten for a friend or guest) is never shown together
 * with the group Mitgliedschaft, so "all flat entries first" is the same order.
 */
internal object NavRegistry {
    private val always: (NavContext) -> Boolean = { true }

    val groupMeta: Map<SidebarGroupId, NavGroupMeta> =
        mapOf(
            SidebarGroupId.MEMBERSHIP to NavGroupMeta(tr("Mitgliedschaft"), "fas fa-id-card"),
            SidebarGroupId.SELF_GOVERNANCE to NavGroupMeta(tr("Selbstverwaltung"), "fas fa-people-group"),
            SidebarGroupId.ECONOMY to NavGroupMeta(tr("Wirtschaft"), "fas fa-coins"),
            SidebarGroupId.FINANCE to NavGroupMeta(tr("Finanzen"), "fas fa-chart-line"),
            SidebarGroupId.ADMINISTRATION to NavGroupMeta(tr("Verwaltung"), "fas fa-user-gear"),
            SidebarGroupId.SYSTEM to NavGroupMeta(tr("System"), "fas fa-server"),
        )

    /** Gate of the group header (a group is built only if this holds AND at least one of its rows is visible). */
    val groupVisible: Map<SidebarGroupId, (NavContext) -> Boolean> =
        mapOf(
            SidebarGroupId.MEMBERSHIP to { ctx -> NavVisibility.showsMembershipSection(ctx.session.status) },
            SidebarGroupId.SELF_GOVERNANCE to { ctx -> NavVisibility.showsSelfGovernance(ctx.session.status) },
            SidebarGroupId.ECONOMY to { ctx -> NavVisibility.showsEconomySection(ctx.session.status) },
            SidebarGroupId.FINANCE to { ctx -> ctx.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) },
            SidebarGroupId.ADMINISTRATION to { ctx -> ctx.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) },
            SidebarGroupId.SYSTEM to { ctx -> ctx.hasRole(AccountRole.ADMIN) },
        )

    private fun entry(
        route: String,
        label: String,
        icon: String,
        group: SidebarGroupId?,
        visible: (NavContext) -> Boolean = always,
    ) = NavEntry(route = route, label = label, icon = icon, group = group, visible = visible)

    private val boardAdmin: (NavContext) -> Boolean = { it.hasRole(AccountRole.BOARD, AccountRole.ADMIN) }

    val entries: List<NavEntry> =
        buildList {
            // ── flat entries ───
            add(entry(Routes.DASHBOARD, tr("Dashboard"), "fas fa-house", null))
            add(entry(Routes.CONFERENCE, tr("Videokonferenz"), "fas fa-video", null).copy(liveSuffix = NavLiveSuffix.CONFERENCE))
            // V1.9.62: a guest has no group to find the room in, and the room is a destination of its own.
            add(
                entry(
                    Routes.ENCOUNTER,
                    tr("Begegnungsräume"),
                    "fas fa-church",
                    null,
                ) { NavVisibility.showsEncounterSpaces(it.session.status) }.copy(liveSuffix = NavLiveSuffix.ENCOUNTER),
            )
            // V1.9.125: a guest or friend has no "Mitgliedschaft" group -- the carpool centre is a flat entry for them.
            add(
                entry(
                    Routes.CARPOOL,
                    tr("Mitfahrzentrale"),
                    "fas fa-car-side",
                    null,
                ) { NavVisibility.showsCarpoolFlatLink(it.session.status) },
            )
            // Welle V1.1.4: a friend or guest has no full "Mitgliedschaft" group but needs a reachable data-rights entry.
            add(
                entry(Routes.DSGVO_RIGHTS, tr("Meine Daten"), "fas fa-shield-halved", null) {
                    !NavVisibility.showsMembershipSection(it.session.status) && NavVisibility.showsDsgvoRights(it.session.status)
                },
            )

            // ── Mitgliedschaft ───
            val m = SidebarGroupId.MEMBERSHIP
            add(entry(Routes.CONTRIBUTIONS, tr("Beiträge"), "fas fa-coins", m))
            add(entry(Routes.DOCUMENTS, tr("Dokumente"), "fas fa-file-lines", m))
            add(
                entry(Routes.STATUTE_QA, tr("Fragen zur Satzung"), "fas fa-magnifying-glass", m) {
                    NavVisibility.showsStatuteQa(it.session.status, it.session.aiAssistantEnabled)
                },
            )
            add(entry(Routes.MESSAGES, tr("Nachrichten"), "fas fa-envelope", m))
            add(entry(Routes.MAILING_LISTS, tr("Mailinglisten"), "fas fa-envelopes-bulk", m))
            add(
                entry(Routes.MY_CHAPTER, tr("Mein Landesverband"), "fas fa-users-rectangle", m) {
                    NavVisibility.showsChapterRoster(it.session.chapterScope)
                },
            )
            add(entry(Routes.CARPOOL, tr("Mitfahrzentrale"), "fas fa-car-side", m))
            add(
                entry(Routes.MEMBER_FINDER, tr("Mitglieder-Finder"), "fas fa-user-group", m) {
                    NavVisibility.showsMemberFinder(it.session.status, it.memberFinderOffered)
                },
            )
            add(entry(Routes.ARTICLES, tr("Artikel"), "fas fa-newspaper", m))
            add(entry(Routes.DONATE, tr("Spenden"), "fas fa-hand-holding-heart", m))
            add(entry(Routes.DSGVO_RIGHTS, tr("Meine Daten"), "fas fa-shield-halved", m))
            add(entry(Routes.TRAVEL_EXPENSES, tr("Reisekosten"), "fas fa-car", m))
            add(entry(Routes.VOLUNTEER_ALLOWANCES, tr("Ehrenamtspauschalen"), "fas fa-hands-helping", m))
            add(entry(Routes.MY_VOLUNTEER_SHIFTS, tr("Helferschichten"), "fas fa-calendar-check", m))
            add(entry(Routes.MY_EVENTS, tr("Veranstaltungen"), "fas fa-ticket", m))

            // ── Selbstverwaltung ───
            val s = SidebarGroupId.SELF_GOVERNANCE
            add(entry(Routes.COMMITTEES, tr("Gremien"), "fas fa-people-group", s))
            add(entry(Routes.MEETINGS, tr("Sitzungen"), "fas fa-calendar-days", s))
            add(entry(Routes.MOTIONS, tr("Anträge"), "fas fa-file-signature", s))
            add(entry(Routes.ELECTIONS, tr("Wahlen"), "fas fa-check-to-slot", s) { NavVisibility.showsElections(it.session.status) })
            add(entry(Routes.CONSENSUS, tr("Konsensieren"), "fas fa-scale-balanced", s) { NavVisibility.showsConsensus(it.session.status) })
            add(entry(Routes.POLLS, tr("Umfragen"), "fas fa-square-poll-horizontal", s) { NavVisibility.showsPolls(it.session.status) })
            add(entry(Routes.ACTIVISM, tr("Aktionen"), "fas fa-bullhorn", s) { NavVisibility.showsActivism(it.session.status) })

            // ── Wirtschaft ───
            val e = SidebarGroupId.ECONOMY
            add(entry(Routes.LTR_LEDGER, tr("LTR-Konto"), "fas fa-wallet", e) { NavVisibility.showsLtrLedger(it.session.status) })
            add(
                entry(Routes.CROWDFUNDING, tr("Crowdfunding"), "fas fa-hand-holding-heart", e) {
                    NavVisibility.showsMemberOnlyEconomy(it.session.status)
                },
            )
            add(entry(Routes.AUCTION, tr("Auktion"), "fas fa-gavel", e) { NavVisibility.showsMemberOnlyEconomy(it.session.status) })
            add(
                entry(
                    Routes.POLITICIANS,
                    tr("Politiker"),
                    "fas fa-landmark",
                    e,
                ) { NavVisibility.showsMemberOnlyEconomy(it.session.status) },
            )
            // V1.9.202 -- outside the member-only block: FRIEND may bid on a tender as well.
            add(entry(Routes.TENDERS, tr("Ausschreibungen"), "fas fa-handshake", e) { NavVisibility.showsTenders(it.session.status) })
            add(
                entry(Routes.SOCIAL_NETWORK, tr("Soziales Netzwerk"), "fas fa-comments", e) {
                    NavVisibility.showsSocialNetwork(it.session.status)
                },
            )
            // Welle V1.8.2b -- bound to mcpEnabled, deliberately NOT mcpWriteEnabled (see NavVisibility.showsAiDrafts KDoc).
            add(
                entry(Routes.AI_DRAFTS, tr("KI-Entwürfe"), "fas fa-wand-magic-sparkles", e) {
                    NavVisibility.showsAiDrafts(it.session.status, it.session.mcpEnabled)
                },
            )

            // ── Finanzen ───
            val f = SidebarGroupId.FINANCE
            add(entry(Routes.LEDGER, tr("Kontenplan & Journal"), "fas fa-book", f))
            add(entry(Routes.OPEN_ITEMS, tr("Offene Posten"), "fas fa-right-left", f))
            add(entry(Routes.FINANCIAL_REPORTS, tr("Finanzberichte"), "fas fa-chart-pie", f))
            add(entry(Routes.COMPLIANCE_REPORTS, tr("Gemeinnützigkeits-Berichte"), "fas fa-scale-balanced", f))
            add(
                entry(Routes.PARTY_REPORT, tr("Rechenschaftsbericht (PartG)"), "fas fa-file-signature", f) {
                    NavVisibility.showsPartyReport(it.session)
                },
            )
            add(entry(Routes.COST_CENTERS, tr("Kostenstellen"), "fas fa-tags", f))
            add(entry(Routes.DONORS, tr("Spender"), "fas fa-heart", f))
            add(entry(Routes.AUDIT_LOG, tr("Prüfprotokoll"), "fas fa-magnifying-glass", f))
            add(entry(Routes.POSTAL_MAIL, tr("Postversand"), "fas fa-envelope-open-text", f))
            add(entry(Routes.PRICE_ORACLE, tr("Price-Oracle"), "fas fa-chart-simple", f))
            add(entry(Routes.SEPA_MANDATES, tr("SEPA-Mandate"), "fas fa-file-contract", f))
            add(entry(Routes.SEPA_BATCHES, tr("SEPA-Lastschrift"), "fas fa-money-check-dollar", f))
            add(entry(Routes.DUNNING_CASES, tr("Mahnwesen"), "fas fa-file-invoice-dollar", f))
            add(entry(Routes.PAYMENT_TRANSACTIONS, tr("Zahlungseingänge"), "fas fa-credit-card", f))
            add(entry(Routes.BANK_ACCOUNTS, tr("Bankkonten"), "fas fa-piggy-bank", f))
            add(entry(Routes.BANK_IMPORT, tr("Kontoauszüge"), "fas fa-building-columns", f))
            // Welle V1.9.18 -- narrower than the rest of the group (TREASURER/ADMIN, NOT BOARD), see `Routes.MEMBERSHIP_TIERS` KDoc.
            add(
                entry(Routes.MEMBERSHIP_TIERS, tr("Mitgliedschaftsstufen"), "fas fa-layer-group", f) {
                    it.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)
                },
            )
            // BOARD/ADMIN only (NOT TREASURER). The start labels are the label functions, so the sidebar counter and the dashboard say the same.
            add(entry(Routes.CONTRIBUTION_RELIEF, reliefSidebarLabel(null), "fas fa-percent", f, boardAdmin))
            add(entry(Routes.TRAVEL_EXPENSE_APPROVALS, travelExpenseSidebarLabel(null), "fas fa-route", f, boardAdmin))
            add(entry(Routes.VOLUNTEER_ALLOWANCE_APPROVALS, volunteerAllowanceSidebarLabel(null), "fas fa-people-carry-box", f, boardAdmin))

            // ── Verwaltung ───
            val a = SidebarGroupId.ADMINISTRATION
            add(entry(Routes.MEMBERS, tr("Mitgliederverwaltung"), "fas fa-users-gear", a))
            add(
                entry(Routes.REGIONAL_CHAPTERS, tr("Gliederungsverwaltung"), "fas fa-sitemap", a) {
                    NavVisibility.showsRegionalChapterAdmin(it.session.role)
                },
            )
            add(entry(Routes.DSGVO_COMPLIANCE, tr("DSGVO-Compliance"), "fas fa-shield-halved", a, boardAdmin))
            add(entry(Routes.BOARD_MEMBERSHIP, tr("Vorstand & Transparenzregister"), "fas fa-landmark-flag", a, boardAdmin))
            add(entry(Routes.SOCIAL_MODERATION, tr("Moderation"), "fas fa-flag", a, boardAdmin))
            add(entry(Routes.API_KEYS, tr("API-Schlüssel"), "fas fa-key", a, boardAdmin))
            add(entry(Routes.CRM, tr("Kontakte & Interessenten"), "fas fa-address-book", a, boardAdmin))
            add(entry(Routes.EVENT_MANAGEMENT, tr("Veranstaltungen"), "fas fa-calendar-days", a, boardAdmin))
            add(entry(Routes.EVENT_CHECKIN, tr("Veranstaltungs-Check-in"), "fas fa-qrcode", a, boardAdmin))
            add(entry(Routes.EVENT_ROOMS, tr("Raumverwaltung"), "fas fa-door-open", a, boardAdmin))
            add(entry(Routes.CATERING, tr("Catering"), "fas fa-utensils", a, boardAdmin))
            add(entry(Routes.EVENT_VOLUNTEERS, tr("Helfer-Schichten"), "fas fa-clipboard-check", a, boardAdmin))
            add(entry(Routes.MEMBER_ANNIVERSARIES, tr("Geburtstage & Jubiläen"), "fas fa-cake-candles", a, boardAdmin))
            add(entry(Routes.MEMBER_HONORS, tr("Ehrungen & Auszeichnungen"), "fas fa-medal", a, boardAdmin))
            add(entry(Routes.MEMBER_FAMILIES, tr("Familienmitgliedschaften"), "fas fa-people-roof", a, boardAdmin))
            add(entry(Routes.MEMBER_MAP, tr("Mitgliederkarte"), "fas fa-map-location-dot", a, boardAdmin))
            add(entry(Routes.MEMBER_STATISTICS, tr("Mitgliederentwicklung"), "fas fa-chart-column", a, boardAdmin))

            // ── System ───
            val y = SidebarGroupId.SYSTEM
            add(entry(Routes.ORGANIZATION, tr("Organisation"), "fas fa-building", y))
            add(entry(Routes.BACKUP, tr("Backup & Wiederherstellung"), "fas fa-database", y))
            add(entry(Routes.AGREEMENT_TEXTS, tr("Vertragstexte"), "fas fa-scroll", y))
            add(entry(Routes.CONFERENCE_STREAM_DESTINATIONS, tr("Stream-Ziele"), "fas fa-satellite-dish", y))
            add(entry(Routes.SEPA_SETTINGS, tr("SEPA-Konfiguration"), "fas fa-building-columns", y))
            add(entry(Routes.TRAVEL_EXPENSE_SETTINGS, tr("Reisekosten-Einstellungen"), "fas fa-sliders", y))
            add(entry(Routes.PAYMENT_GATEWAY_SETTINGS, tr("Zahlungs-Konfiguration"), "fas fa-hand-holding-dollar", y))
            add(entry(Routes.DUNNING_SETTINGS, tr("Mahnwesen-Konfiguration"), "fas fa-scale-unbalanced", y))
            add(entry(Routes.RECEIVABLE_DUNNING_SETTINGS, tr("Forderungs-Mahnstufen"), "fas fa-bell", y))
            add(entry(Routes.EMBED_INTEGRATION, tr("Website-Integration"), "fas fa-code", y))
            add(entry(Routes.MAIL_DELIVERY, tr("E-Mail-Versand"), "fas fa-envelopes-bulk", y))
        }

    /** The flat (top-level) entries [ctx] may see, in sidebar order. */
    fun flat(ctx: NavContext): List<NavEntry> = entries.filter { it.group == null && it.visible(ctx) }

    /** The rows of [group] that [ctx] may see, in sidebar order (the gate of the group itself is NOT applied here, see [visibleGroups]). */
    fun visibleInGroup(
        group: SidebarGroupId,
        ctx: NavContext,
    ): List<NavEntry> = entries.filter { it.group == group && it.visible(ctx) }

    /** Groups [ctx] sees: the group gate holds and at least one row is visible. In [SidebarGroupId] order. */
    fun visibleGroups(ctx: NavContext): List<SidebarGroupId> =
        SidebarGroupId.entries.filter { id -> groupVisible.getValue(id)(ctx) && visibleInGroup(id, ctx).isNotEmpty() }
}
