package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ReserveType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Foundation stub (see CLAUDE.md "Vorab-Befund"): there is no member onboarding flow yet
 * (V0.1.2-V0.1.4), so without this there would be no way to obtain a member id to put in the
 * `X-Member-Id` header (see [network.lapis.cloud.server.security.resolveCurrentMember]) and
 * exercise anything in this wave. Seeds a fixed, deterministic set of demo members/accounts —
 * one per [AccountRole] — the first time the member table is empty.
 *
 * **Secure by default: opt-IN, not opt-out.** [seedIfEmpty] is a no-op unless
 * `LAPIS_SEED_DEMO_DATA=true` is set explicitly (local/dev convenience only). Even then it
 * hard-refuses to run against anything but the H2 in-memory default — i.e. it never touches a
 * real deployment reachable via `LAPIS_DB_URL`. This matters because the seeded ADMIN account
 * has a fixed, guessable id (`00000000-0000-0000-0000-000000000001`) and a `NULL`
 * `password_hash`: with identity currently resolved from the client-supplied `X-Member-Id`
 * header, that id would otherwise be an unauthenticated full-admin login on a fresh production
 * database whose member table happens to be empty.
 */
object DevSeedData {
    data class SeedMember(
        val id: Uuid,
        val displayName: String,
        val email: String,
        val role: AccountRole,
    )

    val demoMembers =
        listOf(
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000001"),
                "Amara Admin",
                "amara.admin@example.org",
                AccountRole.ADMIN,
            ),
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000002"),
                "Boris Board",
                "boris.board@example.org",
                AccountRole.BOARD,
            ),
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000003"),
                "Theresa Treasurer",
                "theresa.treasurer@example.org",
                AccountRole.TREASURER,
            ),
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000004"),
                "Max Mitglied",
                "max.mitglied@example.org",
                AccountRole.MEMBER,
            ),
        )

    val standardTierId: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f1")

    /**
     * A representative SKR42 (DATEV's current Kontenrahmen for Vereine/Stiftungen/gGmbHs, based on
     * SKR04, 5-digit account numbers; it replaced SKR49, which DATEV has maintained no further
     * since 01.01.2025) chart of accounts, spanning every Kontenklasse this codebase's
     * `10-accounting.kuml.kts` documents (0/1/2/3/4/5/6/7/9) -- see that file's header for why the
     * four Gemeinnützigkeit spheres are *not* derivable from these classes under SKR42, and for why
     * class 4 holds all income, class 5 is itself an expense class (Wareneingang), class 6 holds
     * the remaining operating expenses, and class 7 is the Finanzergebnis. This is a
     * *reference-data candidate*: a real deployment would likely want the complete SKR42 (hundreds
     * of accounts) seeded via a dedicated import rather than this hand-picked subset, but this set
     * is enough to exercise `AccountingService` end to end and gives a treasurer a plausible
     * starting point in dev/demo environments.
     *
     * Account numbers below carry a confidence note: HIGH = independently confirmed by 2+ sources
     * (clubdesk.de, commu-core.com, vibss.de), MED/LOW = SKR04-consistent candidates that should be
     * verified against the official DATEV SKR42 Kontenplan before a real deployment relies on them
     * (see V0.3.1.1 research notes).
     */
    data class SeedLedgerAccount(
        val accountNumber: String,
        val name: String,
        val accountClass: Int,
        val type: LedgerAccountType,
        val reserveType: ReserveType? = null,
    )

    val demoLedgerAccounts =
        listOf(
            // Klasse 0 -- Anlagevermögen. (LOW confidence -- SKR04-consistent candidate.)
            SeedLedgerAccount("06500", "Betriebs- und Geschäftsausstattung", 0, LedgerAccountType.ASSET),
            // Klasse 1 -- liquide Mittel. (HIGH confidence.)
            SeedLedgerAccount("16000", "Kasse", 1, LedgerAccountType.ASSET),
            SeedLedgerAccount("18000", "Bank (Girokonto)", 1, LedgerAccountType.ASSET),
            // Klasse 1 -- Forderungen. (MED confidence.)
            SeedLedgerAccount("12000", "Forderungen aus Lieferungen und Leistungen", 1, LedgerAccountType.ASSET),
            // Klasse 3 -- Verbindlichkeiten/USt. (LOW/MED confidence.)
            SeedLedgerAccount("34000", "Verbindlichkeiten aus Lieferungen und Leistungen", 3, LedgerAccountType.LIABILITY),
            SeedLedgerAccount("37500", "Umsatzsteuer", 3, LedgerAccountType.LIABILITY),
            // Klasse 4 -- Erträge/Umsatzerlöse. Covers all four Gemeinnützigkeit spheres' income
            // (ideeller Bereich, Vermögensverwaltung, Zweckbetrieb, wirtschaftlicher
            // Geschäftsbetrieb) -- SKR42 does not partition income by account-number range; sphere
            // is assigned per posting via cost center (KOST1). (HIGH confidence.)
            SeedLedgerAccount("40000", "Echte Mitgliedsbeiträge", 4, LedgerAccountType.INCOME),
            SeedLedgerAccount("40450", "Geldzuwendungen (Spenden) gegen Zuwendungsbestätigung", 4, LedgerAccountType.INCOME),
            SeedLedgerAccount("42010", "Erlöse aus Eintrittsgeldern (Zweckbetrieb)", 4, LedgerAccountType.INCOME),
            SeedLedgerAccount("44000", "Erlöse wirtschaftlicher Geschäftsbetrieb", 4, LedgerAccountType.INCOME),
            // Klasse 5 -- Wareneingang / Aufwendungen für Roh-, Hilfs- und Betriebsstoffe. This is
            // itself an EXPENSE class under SKR42, not an income class. (MED confidence.)
            SeedLedgerAccount("50000", "Wareneinsatz / Materialaufwand", 5, LedgerAccountType.EXPENSE),
            // Klasse 6 -- sonstige betriebliche Aufwendungen. Sphere-neutral by design under SKR42
            // -- which sphere a booking to one of these belongs to is assigned per posting via cost
            // center (KOST1), not derivable from the account itself. (MED/LOW confidence.)
            SeedLedgerAccount("63000", "Aufwand (z.B. Miete) -- Sphäre via KOST1", 6, LedgerAccountType.EXPENSE),
            SeedLedgerAccount("64000", "Bürobedarf / Verwaltungsaufwand", 6, LedgerAccountType.EXPENSE),
            SeedLedgerAccount("64200", "Sonstiger Aufwand -- Sphäre via KOST1", 6, LedgerAccountType.EXPENSE),
            // Klasse 7 -- Finanzergebnis. (MED confidence.)
            SeedLedgerAccount("71100", "Zinserträge Bankguthaben (Vermögensverwaltung)", 7, LedgerAccountType.INCOME),
            // Klasse 2/9 -- Eigenkapital/Vortrags-/statistische Konten. (LOW/MED confidence.)
            SeedLedgerAccount("20000", "Vereinsvermögen / Ergebnisvortrag", 2, LedgerAccountType.EQUITY),
            SeedLedgerAccount("90000", "Saldenvorträge Sachkonten / Eröffnungsbilanz", 9, LedgerAccountType.EQUITY),
            // §62 AO Rücklagen (V0.3.4) -- ordinary EQUITY accounts, machine-classified via
            // reserveType. See ReserveType KDoc / 10-accounting.kuml.kts file header.
            SeedLedgerAccount("21000", "Projektrücklage (§62 Abs.1 Nr.1 AO)", 2, LedgerAccountType.EQUITY, ReserveType.PROJEKTRUECKLAGE),
            SeedLedgerAccount("21500", "Freie Rücklage (§62 Abs.1 Nr.3 AO)", 2, LedgerAccountType.EQUITY, ReserveType.FREIE_RUECKLAGE),
        )

    /**
     * Returns `true` only when [network.lapis.cloud.server.db.DatabaseConfig] is (or will be)
     * pointed at the H2 in-memory default — i.e. `LAPIS_DB_URL` is unset or explicitly an
     * `jdbc:h2:mem:` URL. A real deployment always sets `LAPIS_DB_URL` to a `jdbc:postgresql://...`
     * URL, so this is the same signal [DatabaseConfig] itself uses to pick a driver.
     */
    private fun isH2InMemory(): Boolean {
        val jdbcUrl = System.getenv("LAPIS_DB_URL")
        return jdbcUrl == null || jdbcUrl.startsWith("jdbc:h2:mem:")
    }

    /**
     * Seeds the fixed demo members/accounts the first time the member table is empty.
     *
     * @param force Bypasses the `LAPIS_SEED_DEMO_DATA` opt-in gate. Intended for test setup
     *   only (tests always run against the H2 default, never a real deployment) — production
     *   code must never pass `true` here. The H2-in-memory safety check below always applies,
     *   even with `force = true`.
     */
    fun seedIfEmpty(force: Boolean = false) {
        if (!force) {
            val seedRequested = System.getenv("LAPIS_SEED_DEMO_DATA")?.equals("true", ignoreCase = true) == true
            if (!seedRequested) return
        }
        check(isH2InMemory()) {
            "Refusing to seed demo data: LAPIS_DB_URL points at a non-H2-in-memory database. " +
                "Demo seeding (fixed, guessable member ids with a NULL password_hash) must never run " +
                "against a real deployment."
        }
        transaction {
            val alreadySeeded = MemberTable.selectAll().limit(1).any()
            if (alreadySeeded) return@transaction

            MembershipTierTable.insert {
                it[id] = standardTierId
                it[name] = "Standardbeitrag"
                it[description] = "Regulaerer Mitgliedsbeitrag, monatlich."
                it[contributionAmount] = BigDecimal("10.00")
                it[billingInterval] = BillingInterval.MONTHLY
                it[active] = true
            }

            demoMembers.forEach { seed ->
                MemberTable.insert {
                    it[id] = seed.id
                    it[displayName] = seed.displayName
                    it[email] = seed.email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = standardTierId
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = seed.id
                    it[role] = seed.role
                }
            }

            demoLedgerAccounts.forEach { seed ->
                LedgerAccountTable.insert {
                    it[id] = Uuid.random()
                    it[accountNumber] = seed.accountNumber
                    it[name] = seed.name
                    it[accountClass] = seed.accountClass
                    it[type] = seed.type
                    it[active] = true
                    it[reserveType] = seed.reserveType
                }
            }
        }
    }
}
