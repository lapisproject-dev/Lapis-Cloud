package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.DeploymentMode
import network.lapis.cloud.server.security.PasswordHasher
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
 * (V0.1.2-V0.1.4), so without this there would be no way to obtain a member id/login for the
 * "current member" and exercise anything in this wave. Seeds a fixed, deterministic set of demo
 * members/accounts — one per [AccountRole] — the first time the member table is empty.
 *
 * **Secure by default: opt-IN, not opt-out.** [seedIfEmpty] is a no-op unless
 * `LAPIS_SEED_DEMO_DATA=true` is set explicitly (local/dev convenience only). Even then it
 * hard-refuses to run against anything but the H2 in-memory default — i.e. it never touches a
 * real deployment reachable via `LAPIS_DB_URL`. This matters because the seeded ADMIN account
 * has a fixed, guessable id (`00000000-0000-0000-0000-000000000001`) and, since V0.7.1, a real
 * (if guessable/published-in-source) [DEMO_PASSWORD] hash: since this is opt-in, H2-only, dev/demo
 * seed data, a guessable dev password is an acceptable, well-documented convenience — the same
 * `check(DeploymentMode.isH2InMemory())` guard below that always applied here now also protects
 * a real deployment from ever ending up with these hashed dev credentials.
 */
object DevSeedData {
    /**
     * V0.7.1: fixed, published-in-source password for every [demoMembers] account — H2/opt-in
     * only (see class KDoc), never usable against a real deployment. `./gradlew run` with
     * `LAPIS_SEED_DEMO_DATA=true` therefore supports a real password login end to end without
     * requiring a manual [network.lapis.cloud.server.bootstrap.AdminBootstrap] run first.
     */
    const val DEMO_PASSWORD: String = "correct-horse-battery-staple"

    data class SeedMember(
        val id: Uuid,
        val displayName: String,
        val email: String,
        val role: AccountRole,
    )

    val demoMembers =
        listOf(
            SeedMember(
                id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                displayName = "Amara Admin",
                email = "amara.admin@example.org",
                role = AccountRole.ADMIN,
            ),
            SeedMember(
                id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                displayName = "Boris Board",
                email = "boris.board@example.org",
                role = AccountRole.BOARD,
            ),
            SeedMember(
                id = Uuid.parse("00000000-0000-0000-0000-000000000003"),
                displayName = "Theresa Treasurer",
                email = "theresa.treasurer@example.org",
                role = AccountRole.TREASURER,
            ),
            SeedMember(
                id = Uuid.parse("00000000-0000-0000-0000-000000000004"),
                displayName = "Max Mitglied",
                email = "max.mitglied@example.org",
                role = AccountRole.MEMBER,
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
        val isCashRegister: Boolean = false,
    )

    val demoLedgerAccounts =
        listOf(
            // Klasse 0 -- Anlagevermögen. (LOW confidence -- SKR04-consistent candidate.)
            SeedLedgerAccount(
                accountNumber = "06500",
                name = "Betriebs- und Geschäftsausstattung",
                accountClass = 0,
                type = LedgerAccountType.ASSET,
            ),
            // Klasse 1 -- liquide Mittel. (HIGH confidence.) "16000 Kasse" is the physical cash
            // register (V0.3.5 Kassenbuch) -- accountClass alone can't distinguish it from
            // "18000 Bank"/"12000 Forderungen" below, all class 1, see 10-accounting.kuml.kts
            // file header.
            SeedLedgerAccount(
                accountNumber = "16000",
                name = "Kasse",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
                isCashRegister = true,
            ),
            SeedLedgerAccount(accountNumber = "18000", name = "Bank (Girokonto)", accountClass = 1, type = LedgerAccountType.ASSET),
            // Klasse 1 -- Forderungen. (MED confidence.)
            SeedLedgerAccount(
                accountNumber = "12000",
                name = "Forderungen aus Lieferungen und Leistungen",
                accountClass = 1,
                type = LedgerAccountType.ASSET,
            ),
            // Klasse 3 -- Verbindlichkeiten/USt. (LOW/MED confidence.)
            SeedLedgerAccount(
                accountNumber = "34000",
                name = "Verbindlichkeiten aus Lieferungen und Leistungen",
                accountClass = 3,
                type = LedgerAccountType.LIABILITY,
            ),
            SeedLedgerAccount(accountNumber = "37500", name = "Umsatzsteuer", accountClass = 3, type = LedgerAccountType.LIABILITY),
            // Klasse 4 -- Erträge/Umsatzerlöse. Covers all four Gemeinnützigkeit spheres' income
            // (ideeller Bereich, Vermögensverwaltung, Zweckbetrieb, wirtschaftlicher
            // Geschäftsbetrieb) -- SKR42 does not partition income by account-number range; sphere
            // is assigned per posting via cost center (KOST1). (HIGH confidence.)
            SeedLedgerAccount(accountNumber = "40000", name = "Echte Mitgliedsbeiträge", accountClass = 4, type = LedgerAccountType.INCOME),
            SeedLedgerAccount(
                accountNumber = "40450",
                name = "Geldzuwendungen (Spenden) gegen Zuwendungsbestätigung",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
            ),
            SeedLedgerAccount(
                accountNumber = "42010",
                name = "Erlöse aus Eintrittsgeldern (Zweckbetrieb)",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
            ),
            SeedLedgerAccount(
                accountNumber = "44000",
                name = "Erlöse wirtschaftlicher Geschäftsbetrieb",
                accountClass = 4,
                type = LedgerAccountType.INCOME,
            ),
            // Klasse 5 -- Wareneingang / Aufwendungen für Roh-, Hilfs- und Betriebsstoffe. This is
            // itself an EXPENSE class under SKR42, not an income class. (MED confidence.)
            SeedLedgerAccount(
                accountNumber = "50000",
                name = "Wareneinsatz / Materialaufwand",
                accountClass = 5,
                type = LedgerAccountType.EXPENSE,
            ),
            // Klasse 6 -- sonstige betriebliche Aufwendungen. Sphere-neutral by design under SKR42
            // -- which sphere a booking to one of these belongs to is assigned per posting via cost
            // center (KOST1), not derivable from the account itself. (MED/LOW confidence.)
            SeedLedgerAccount(
                accountNumber = "63000",
                name = "Aufwand (z.B. Miete) -- Sphäre via KOST1",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
            ),
            SeedLedgerAccount(
                accountNumber = "64000",
                name = "Bürobedarf / Verwaltungsaufwand",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
            ),
            SeedLedgerAccount(
                accountNumber = "64200",
                name = "Sonstiger Aufwand -- Sphäre via KOST1",
                accountClass = 6,
                type = LedgerAccountType.EXPENSE,
            ),
            // Klasse 7 -- Finanzergebnis. (MED confidence.)
            SeedLedgerAccount(
                accountNumber = "71100",
                name = "Zinserträge Bankguthaben (Vermögensverwaltung)",
                accountClass = 7,
                type = LedgerAccountType.INCOME,
            ),
            // Klasse 2/9 -- Eigenkapital/Vortrags-/statistische Konten. (LOW/MED confidence.)
            SeedLedgerAccount(
                accountNumber = "20000",
                name = "Vereinsvermögen / Ergebnisvortrag",
                accountClass = 2,
                type = LedgerAccountType.EQUITY,
            ),
            SeedLedgerAccount(
                accountNumber = "90000",
                name = "Saldenvorträge Sachkonten / Eröffnungsbilanz",
                accountClass = 9,
                type = LedgerAccountType.EQUITY,
            ),
            // §62 AO Rücklagen (V0.3.4) -- ordinary EQUITY accounts, machine-classified via
            // reserveType. See ReserveType KDoc / 10-accounting.kuml.kts file header.
            SeedLedgerAccount(
                accountNumber = "21000",
                name = "Projektrücklage (§62 Abs.1 Nr.1 AO)",
                accountClass = 2,
                type = LedgerAccountType.EQUITY,
                reserveType = ReserveType.PROJEKTRUECKLAGE,
            ),
            SeedLedgerAccount(
                accountNumber = "21500",
                name = "Freie Rücklage (§62 Abs.1 Nr.3 AO)",
                accountClass = 2,
                type = LedgerAccountType.EQUITY,
                reserveType = ReserveType.FREIE_RUECKLAGE,
            ),
        )

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
        check(DeploymentMode.isH2InMemory()) {
            "Refusing to seed demo data: LAPIS_DB_URL points at a non-H2-in-memory database. " +
                "Demo seeding (fixed, guessable member ids with a published, hashed dev password) " +
                "must never run against a real deployment."
        }
        // Synchronized around the whole check-then-insert body: many test Spec classes call
        // seedIfEmpty(force = true) from their own beforeSpec, and Kotest/coroutine-dispatched test
        // execution can run several of those concurrently within one JVM. Without this lock, two
        // concurrent callers can both observe `alreadySeeded == false` before either has committed
        // (classic TOCTOU race), both then attempt to INSERT the same fixed-id rows, and the loser
        // throws a UNIQUE-constraint violation out of its own transaction {} block -- which,
        // depending on exactly which INSERT lost the race, can also leave OTHER Spec classes'
        // beforeSpec (and every test that depends on `demoMembers` existing) failing with a
        // confusing downstream "member not found"/FK-violation error instead of a clear seeding
        // failure. synchronized(this) serializes every call within this one JVM -- cheap, since
        // seedIfEmpty is only ever called from test setup (and once from main()), never from a
        // request-handling hot path.
        synchronized(this) {
            seedIfEmptyLocked()
        }
    }

    private fun seedIfEmptyLocked() {
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

            // Hashed once, reused for every demoMembers row -- bcrypt is deliberately expensive
            // per call (see PasswordHasher KDoc), no need to pay that cost N times for the same
            // fixed DEMO_PASSWORD.
            val demoPasswordHash = PasswordHasher.hash(DEMO_PASSWORD)
            demoMembers.forEach { seed ->
                MemberTable.insert {
                    it[id] = seed.id
                    it[displayName] = seed.displayName
                    it[email] = seed.email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = standardTierId
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = seed.id
                    it[role] = seed.role
                    it[passwordHash] = demoPasswordHash
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
                    it[isCashRegister] = seed.isCashRegister
                }
            }
        }
    }
}
