package network.lapis.cloud.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.StagingSeedChapters.auditAndGrantOfficers
import network.lapis.cloud.server.db.StagingSeedChapters.insertChapters
import network.lapis.cloud.server.db.StagingSeedCommunity.seedCommunity
import network.lapis.cloud.server.db.StagingSeedFinance.seedFinance
import network.lapis.cloud.server.db.StagingSeedGovernance.seedGovernance
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.member.MemberStatusHistory
import network.lapis.cloud.server.member.MemberStatusHistorySource
import network.lapis.cloud.server.payment.sepa.SepaConfig
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.time.OrganizationTimeZoneRules
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Seeds a dedicated, entirely fictitious "Testverein Musterstadt e.V." dataset on a **real**
 * (typically Postgres) staging deployment, gated behind [StagingSeedConfig] — see that object's
 * KDoc for the first of four independent locks.
 *
 * **Deliberately NOT [DevSeedData]** and deliberately does NOT check
 * [network.lapis.cloud.server.security.DeploymentMode.isH2InMemory] the way [DevSeedData] does —
 * the whole point of this object is to populate a real Postgres staging instance so an
 * interactive/agentic tester has something to click through. The four locks that replace that
 * missing H2 guard:
 * 1. [StagingSeedConfig.decide] — `LAPIS_STAGING_MODE=true` must be set explicitly, and a strong,
 *    non-[DevSeedData.DEMO_PASSWORD] `LAPIS_STAGING_SEED_PASSWORD` must be supplied, or [seedIfEmpty]
 *    refuses to start the process at all (`error(...)`, fail-fast — see that object's KDoc).
 * 2. The `member`-table-not-empty guard inside [seedWithLockedTransaction] below — the single
 *    strongest real-world protection: no real production instance ever has an empty `member`
 *    table once live, so even a misconfigured env there would still no-op.
 * 3. A real production instance's `docker-compose.yml` does not forward
 *    `LAPIS_STAGING_MODE`/`LAPIS_STAGING_SEED_PASSWORD` into the container at all — those real
 *    deployments' env vars never reach this code path in the first place, regardless of what is in
 *    their `.env` files (see `deploy/example/README.adoc` "Running more than one instance on the
 *    same host").
 * 4. Everything inserted here uses the `@staging.invalid` (RFC 2606, never resolvable/deliverable)
 *    email domain, a fixed UUID namespace (`0000000a-...`) disjoint from both [DevSeedData]'s
 *    (`00000000-...`) and the migration sentinels (`...-f1`/`...-f2`/`...-f3`), and an org name
 *    that is obviously a placeholder ("Testverein Musterstadt e.V.") — nothing here can be
 *    mistaken for real PdV/ELB data even if these four locks were ever all defeated at once.
 *
 * **Welle V1.9.63 "reichere Demo-Daten".** The dataset covers four areas, each in its own object so that this file keeps
 * the one and only `member` write: members with a 24-month status history (here), finance ([StagingSeedFinance]),
 * governance ([StagingSeedGovernance]) and community ([StagingSeedCommunity], [StagingSeedChapters]). Everything runs in
 * ONE transaction (all or nothing), is computed relative to one captured [SeedClock] and goes through the same code paths
 * the live services use wherever those can run without an HTTP call (posting bridges, resolution book, audit recorder,
 * election ballot/tally core, event-series materializer). It never touches an external system: no mail, no letter, no
 * webhook, no file.
 */
object StagingSeedData {
    const val ORGANIZATION_NAME: String = "Testverein Musterstadt e.V."
    const val EMAIL_DOMAIN: String = "staging.invalid"
    const val STAGING_ADMIN_EMAIL: String = "admin.test@$EMAIL_DOMAIN"

    internal const val ACCOUNT_BANK: String = "18000"
    internal const val ACCOUNT_CONTRIBUTION_INCOME: String = "40000"
    internal const val ACCOUNT_RECEIVABLES: String = "12000"
    internal const val ACCOUNT_PAYABLES: String = "34000"
    private const val LEDGER_ID_OFFSET: Int = 0x10

    private val organizationSettingsId: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f2")
    private val standardTierId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000001")
    private val reducedTierId: Uuid = SeedIds.base(2)
    private val supporterTierId: Uuid = SeedIds.base(3)

    /** One step of a member's status timeline; [daysAgo] >= 1 so the instant always lies before "now". */
    private data class StatusStep(
        val status: MemberStatus,
        val daysAgo: Int,
    )

    private data class SeedMember(
        val id: Uuid,
        val firstName: String,
        val lastName: String,
        /** Chronological, the LAST step is the current status. */
        val timeline: List<StatusStep>,
        val accountRole: AccountRole? = null,
        val tierId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000001"),
        val chapterIndex: Int? = null,
    ) {
        val displayName: String get() = "$firstName $lastName"
        val email: String get() = "${firstName.lowercase()}.${lastName.lowercase()}@$EMAIL_DOMAIN"
        val status: MemberStatus get() = timeline.last().status
        val firstActive: StatusStep? get() = timeline.firstOrNull { it.status == MemberStatus.ACTIVE }
        val leftStep: StatusStep? get() =
            timeline.last().takeIf {
                it.status == MemberStatus.WITHDRAWN || it.status == MemberStatus.DECEASED
            }
    }

    private fun memberId(n: Int): Uuid = SeedIds.member(n)

    private fun founder() = listOf(StatusStep(status = MemberStatus.ACTIVE, daysAgo = 720))

    /** Application a week before the admission. */
    private fun joiner(activeDaysAgo: Int) =
        listOf(
            StatusStep(status = MemberStatus.APPLICATION, daysAgo = activeDaysAgo + 7),
            StatusStep(status = MemberStatus.ACTIVE, daysAgo = activeDaysAgo),
        )

    private fun leaver(
        activeDaysAgo: Int,
        leftDaysAgo: Int,
        left: MemberStatus = MemberStatus.WITHDRAWN,
    ) = joiner(activeDaysAgo) + StatusStep(status = left, daysAgo = leftDaysAgo)

    /**
     * 40 fictitious members whose status history spans 24 months: 8 founders, a steady stream of admissions, 5 withdrawals,
     * one death, one rejection, two open applications and one friend / donor / guest each. Ids 1-18 and their e-mail
     * addresses are those of the original 18-member seed (the login accounts stay the same), 19-40 are new. Chapter
     * indices refer to [StagingSeedChapters.chapters].
     */
    private val seedMembers: List<SeedMember> =
        listOf(
            SeedMember(
                id = memberId(1),
                firstName = "Anna",
                lastName = "Musterfrau",
                timeline = founder(),
                accountRole = AccountRole.ADMIN,
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(2),
                firstName = "Bernd",
                lastName = "Beispiel",
                timeline = founder(),
                accountRole = AccountRole.BOARD,
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(3),
                firstName = "Clara",
                lastName = "Testfrau",
                timeline = founder(),
                accountRole = AccountRole.TREASURER,
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(4),
                firstName = "Daniel",
                lastName = "Dummland",
                timeline = founder(),
                accountRole = AccountRole.MEMBER,
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(5),
                firstName = "Erika",
                lastName = "Erfunden",
                timeline = founder(),
                accountRole = AccountRole.MEMBER,
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(6),
                firstName = "Frank",
                lastName = "Fiktiv",
                timeline = founder(),
                accountRole = AccountRole.MEMBER,
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(7),
                firstName = "Gisela",
                lastName = "Gedacht",
                timeline = founder(),
                accountRole = AccountRole.MEMBER,
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(8),
                firstName = "Hans",
                lastName = "Hypothetisch",
                timeline = founder(),
                accountRole = AccountRole.MEMBER,
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(9),
                firstName = "Ines",
                lastName = "Imaginaer",
                timeline = joiner(640),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(10),
                firstName = "Jonas",
                lastName = "Jenseits",
                timeline = joiner(590),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(11),
                firstName = "Karla",
                lastName = "Konstrukt",
                timeline = listOf(StatusStep(status = MemberStatus.APPLICATION, daysAgo = 4)),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(12),
                firstName = "Lukas",
                lastName = "Luegenhaft",
                timeline = listOf(StatusStep(status = MemberStatus.APPLICATION, daysAgo = 2)),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(13),
                firstName = "Mia",
                lastName = "Modell",
                timeline = listOf(StatusStep(status = MemberStatus.FRIEND, daysAgo = 60)),
            ),
            SeedMember(
                id = memberId(14),
                firstName = "Noah",
                lastName = "Nichtreal",
                timeline = listOf(StatusStep(status = MemberStatus.DONOR, daysAgo = 150)),
                tierId = SeedIds.base(3),
            ),
            SeedMember(
                id = memberId(15),
                firstName = "Olga",
                lastName = "Ohnegrund",
                timeline = leaver(activeDaysAgo = 600, leftDaysAgo = 420),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(16),
                firstName = "Peter",
                lastName = "Platzhalter",
                timeline = leaver(activeDaysAgo = 560, leftDaysAgo = 200, left = MemberStatus.DECEASED),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(17),
                firstName = "Quentin",
                lastName = "Quasi",
                timeline = listOf(StatusStep(status = MemberStatus.GUEST, daysAgo = 30)),
            ),
            SeedMember(
                id = memberId(18),
                firstName = "Rita",
                lastName = "Rueckweisung",
                timeline =
                    listOf(
                        StatusStep(status = MemberStatus.APPLICATION, daysAgo = 90),
                        StatusStep(status = MemberStatus.REJECTED, daysAgo = 80),
                    ),
            ),
            SeedMember(
                id = memberId(19),
                firstName = "Kevin",
                lastName = "Kulisse",
                timeline = joiner(540),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(20),
                firstName = "Lena",
                lastName = "Legende",
                timeline = joiner(500),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(21),
                firstName = "Max",
                lastName = "Muster",
                timeline = joiner(470),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(22),
                firstName = "Nele",
                lastName = "Nirgendwo",
                timeline = leaver(activeDaysAgo = 430, leftDaysAgo = 270),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(23),
                firstName = "Oskar",
                lastName = "Ohnehin",
                timeline = joiner(400),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(24),
                firstName = "Paula",
                lastName = "Phantasie",
                timeline = joiner(370),
                tierId = SeedIds.base(2),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(25),
                firstName = "Rolf",
                lastName = "Rechenbeispiel",
                timeline = joiner(340),
                tierId = SeedIds.base(2),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(26),
                firstName = "Sabine",
                lastName = "Szenario",
                timeline = leaver(activeDaysAgo = 300, leftDaysAgo = 150),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(27),
                firstName = "Tim",
                lastName = "Traumhaft",
                timeline = joiner(270),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(28),
                firstName = "Uta",
                lastName = "Unwirklich",
                timeline = joiner(240),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(29),
                firstName = "Viktor",
                lastName = "Vorstellung",
                timeline = joiner(210),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(30),
                firstName = "Wiebke",
                lastName = "Wunschtraum",
                timeline = joiner(180),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(31),
                firstName = "Xaver",
                lastName = "Xenon",
                timeline = leaver(activeDaysAgo = 150, leftDaysAgo = 55),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(32),
                firstName = "Yara",
                lastName = "Yonder",
                timeline = joiner(120),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(33),
                firstName = "Zoe",
                lastName = "Zufall",
                timeline = joiner(100),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(34),
                firstName = "Arne",
                lastName = "Annahme",
                timeline = joiner(85),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(35),
                firstName = "Britta",
                lastName = "Behauptet",
                timeline = joiner(70),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(36),
                firstName = "Carlo",
                lastName = "Choreo",
                timeline = leaver(activeDaysAgo = 55, leftDaysAgo = 20),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(37),
                firstName = "Dora",
                lastName = "Denkbar",
                timeline = joiner(40),
                chapterIndex = 1,
            ),
            SeedMember(
                id = memberId(38),
                firstName = "Emil",
                lastName = "Erdacht",
                timeline = joiner(28),
                chapterIndex = 2,
            ),
            SeedMember(
                id = memberId(39),
                firstName = "Fiona",
                lastName = "Fantasie",
                timeline = joiner(18),
                chapterIndex = 0,
            ),
            SeedMember(
                id = memberId(40),
                firstName = "Gustav",
                lastName = "Gedicht",
                timeline = joiner(10),
                chapterIndex = 1,
            ),
        )

    /**
     * Production entry point — called ONLY from `main()` in `Application.kt`, NEVER from
     * `Application.module()`. See [DevSeedData.seedIfEmpty]'s own KDoc/`Application.kt` comment for
     * why: `module()` re-runs on every `testApplication { application { module() } }` across this
     * whole test suite, and this function's own "already seeded?" check is a real DB read, not free.
     *
     * [database] exists only so tests can aim the (otherwise unchanged) gate at an isolated database.
     */
    fun seedIfEmpty(
        env: (String) -> String? = System::getenv,
        database: Database? = null,
    ) {
        when (val decision = StagingSeedConfig.decide(env)) {
            is StagingSeedDecision.Disabled -> return
            is StagingSeedDecision.Refused -> error(decision.reason)
            is StagingSeedDecision.Enabled -> {
                logger.warn {
                    "LAPIS_STAGING_MODE aktiv -- diese Instanz seedet FREI ERFUNDENE Testdaten " +
                        "(Organisation '$ORGANIZATION_NAME', Domain @$EMAIL_DOMAIN). Niemals gegen eine " +
                        "Instanz mit echten Mitgliederdaten verwenden."
                }
                // The same loader the application uses; the key is never logged. Null when unset/invalid -> no SEPA demo data.
                val sepaKey = SepaConfig.load(env).secretEncryptionKey
                synchronized(this) {
                    seedWithLockedTransaction(seedPassword = decision.seedPassword, database = database, sepaKey = sepaKey)
                }
            }
        }
    }

    /** Reine DB-Arbeit ohne Env-Gate -- nur fuer Tests gegen eine isolierte H2-DB gedacht. */
    internal fun seedWith(
        seedPassword: String,
        database: Database? = null,
        sepaKey: ByteArray? = null,
    ) {
        synchronized(this) {
            seedWithLockedTransaction(seedPassword = seedPassword, database = database, sepaKey = sepaKey)
        }
    }

    private fun seedWithLockedTransaction(
        seedPassword: String,
        database: Database?,
        sepaKey: ByteArray?,
    ) {
        transaction(database) {
            // Strongest real-world lock (see class KDoc point 2): a non-empty member table means
            // this either isn't a fresh staging volume, or (structurally impossible per points 3+4,
            // but checked anyway as defense in depth) it is PdV/ELB. FIRST statement on purpose: nothing is read, computed or
            // hashed before it, so a populated instance is never touched, not even indirectly.
            val alreadyHasMembers = MemberTable.selectAll().limit(1).any()
            if (alreadyHasMembers) {
                logger.info { "member-Tabelle nicht leer -- Staging-Seeding uebersprungen." }
                return@transaction
            }

            val clock = SeedClock.capture(orgZone = readOrganizationZone())

            insertTiers()
            val ledgerIds = insertLedgerAccounts()
            configureOrganization(ledgerIds)
            with(StagingSeedChapters) { insertChapters(clock) }

            // Hashed once, reused for every login-capable seed member -- see DevSeedData's own
            // comment for why (bcrypt is deliberately expensive per call).
            val seedPasswordHash = PasswordHasher.hash(seedPassword)
            val refs = insertMembers(clock = clock, seedPasswordHash = seedPasswordHash)
            val actors =
                SeedActors(
                    admin = refs.first { it.accountRole == AccountRole.ADMIN },
                    board = refs.first { it.accountRole == AccountRole.BOARD },
                    treasurer = refs.first { it.accountRole == AccountRole.TREASURER },
                    members = refs,
                )

            with(StagingSeedChapters) { auditAndGrantOfficers(clock = clock, actors = actors) }
            seedFinance(clock = clock, actors = actors, ledgerIds = ledgerIds, sepaKey = sepaKey)
            seedGovernance(clock = clock, actors = actors)
            seedCommunity(clock = clock, actors = actors)
        }
    }

    private fun JdbcTransaction.readOrganizationZone(): TimeZone {
        val raw =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq organizationSettingsId }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.timezone)
        return if (raw != null && OrganizationTimeZoneRules.isValid(raw)) {
            TimeZone.of(raw)
        } else {
            TimeZone.of(OrganizationTimeZoneRules.DEFAULT_ZONE_ID)
        }
    }

    private fun JdbcTransaction.insertTiers() {
        MembershipTierTable.insert {
            it[id] = standardTierId
            it[nameKey] = "standardbeitrag"
            it[name] = "Standardbeitrag"
            it[description] = "Regulaerer Mitgliedsbeitrag, monatlich."
            it[contributionAmount] = BigDecimal("10.00")
            it[billingInterval] = BillingInterval.MONTHLY
            it[active] = true
        }
        MembershipTierTable.insert {
            it[id] = reducedTierId
            it[nameKey] = "ermaessigt"
            it[name] = "Ermaessigt"
            it[description] = "Ermaessigter Mitgliedsbeitrag fuer Schueler, Studierende und Rentner, monatlich."
            it[contributionAmount] = BigDecimal("5.00")
            it[billingInterval] = BillingInterval.MONTHLY
            it[active] = true
        }
        MembershipTierTable.insert {
            it[id] = supporterTierId
            it[nameKey] = "foerdermitgliedschaft"
            it[name] = "Foerdermitgliedschaft"
            it[description] = "Foerdermitgliedschaft ohne Stimmrecht, jaehrlich."
            it[contributionAmount] = BigDecimal("120.00")
            it[billingInterval] = BillingInterval.YEARLY
            it[active] = true
        }
    }

    /** Reuses DevSeedData's chart of accounts (read-only access to the data list, not its H2-only gate); ids are now fixed. */
    private fun JdbcTransaction.insertLedgerAccounts(): Map<String, Uuid> {
        val ids = mutableMapOf<String, Uuid>()
        DevSeedData.demoLedgerAccounts.forEachIndexed { index, seed ->
            val ledgerId = SeedIds.base(LEDGER_ID_OFFSET + index)
            ids[seed.accountNumber] = ledgerId
            LedgerAccountTable.insert {
                it[id] = ledgerId
                it[accountNumber] = seed.accountNumber
                it[name] = seed.name
                it[accountClass] = seed.accountClass
                it[type] = seed.type
                it[active] = true
                it[reserveType] = seed.reserveType
                it[isCashRegister] = seed.isCashRegister
            }
        }
        return ids
    }

    private fun JdbcTransaction.configureOrganization(ledgerIds: Map<String, Uuid>) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq organizationSettingsId }) {
            it[name] = ORGANIZATION_NAME
            it[street] = "Musterweg 1"
            it[postalCode] = "12345"
            it[city] = "Musterstadt"
            it[country] = "Deutschland"
            it[isPoliticalParty] = false
            // Account mapping, so contribution payments and open items reach the general ledger (the bridges degrade
            // silently without it, which the seed treats as an error).
            it[paymentBankAccountId] = ledgerIds.getValue(ACCOUNT_BANK)
            it[contributionIncomeAccountId] = ledgerIds.getValue(ACCOUNT_CONTRIBUTION_INCOME)
            it[receivablesAccountId] = ledgerIds.getValue(ACCOUNT_RECEIVABLES)
            it[payablesAccountId] = ledgerIds.getValue(ACCOUNT_PAYABLES)
            it[postalMailEnabled] = false
        }
    }

    /**
     * Inserts the 40 members with the FINAL status in ONE insert each (the only `member` write of the seed, pinned by
     * `MemberStatusWriteTripwireTest`) and replays the timeline into `member_status_history` right after it, oldest step
     * first, with the back-dated `effective_from` -- so the member-statistics chart shows 24 months of real history.
     * The last step equals `member.status`, hence `MemberStatusHistoryConsistency.countMismatches()` stays 0.
     */
    private fun JdbcTransaction.insertMembers(
        clock: SeedClock,
        seedPasswordHash: String,
    ): List<SeedMemberRef> {
        val chapterIds = StagingSeedChapters.chapters.map { it.id }
        val adminId = seedMembers.first { it.accountRole == AccountRole.ADMIN }.id
        return seedMembers.map { seed ->
            val firstActive = seed.firstActive
            val joinedDay = clock.dayAgo((firstActive ?: seed.timeline.first()).daysAgo)
            val decidedStep = seed.timeline.lastOrNull { it.status == MemberStatus.REJECTED }
            val admissionStep = if (firstActive != null && seed.timeline.size > 1) firstActive else decidedStep
            val chapterId = seed.chapterIndex?.let { chapterIds[it] }
            MemberTable.insert {
                it[id] = seed.id
                it[displayName] = seed.displayName
                it[email] = seed.email
                it[status] = seed.status
                it[joinedAt] = joinedDay
                it[membershipTierId] = seed.tierId
                it[regionalChapterId] = chapterId
                if (seed.status == MemberStatus.DECEASED) it[dateOfDeath] = clock.dayAgo(requireNotNull(seed.leftStep).daysAgo)
                if (seed.status == MemberStatus.FRIEND) it[friendSince] = joinedDay
                if (admissionStep != null) {
                    it[reviewedBy] = adminId
                    it[reviewedAt] = clock.utcAt(daysAgo = admissionStep.daysAgo)
                }
                if (seed.status == MemberStatus.REJECTED) it[rejectionReason] = "Demodaten: Antrag abgelehnt."
            }
            seed.timeline.forEach { step ->
                MemberStatusHistory.recordLocked(
                    memberId = seed.id,
                    newStatus = step.status,
                    now = clock.now,
                    source = MemberStatusHistorySource.SEED,
                    effectiveFrom = clock.utcAt(daysAgo = step.daysAgo),
                )
            }
            if (seed.accountRole != null) {
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = seed.id
                    it[role] = seed.accountRole
                    it[passwordHash] = seedPasswordHash
                }
            }
            SeedMemberRef(
                id = seed.id,
                displayName = seed.displayName,
                status = seed.status,
                accountRole = seed.accountRole,
                tierId = seed.tierId,
                chapterId = chapterId,
                activeSinceDaysAgo = firstActive?.daysAgo,
                leftDaysAgo = seed.leftStep?.daysAgo,
            )
        }
    }
}
