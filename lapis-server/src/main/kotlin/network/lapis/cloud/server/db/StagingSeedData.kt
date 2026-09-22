package network.lapis.cloud.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
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

private fun LocalDateTime.plusDays(days: Int): LocalDateTime = toJavaLocalDateTime().plusDays(days.toLong()).toKotlinLocalDateTime()

private fun LocalDateTime.minusDays(days: Int): LocalDateTime = toJavaLocalDateTime().minusDays(days.toLong()).toKotlinLocalDateTime()

private fun LocalDateTime.plusHours(hours: Int): LocalDateTime = toJavaLocalDateTime().plusHours(hours.toLong()).toKotlinLocalDateTime()

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
 */
object StagingSeedData {
    const val ORGANIZATION_NAME: String = "Testverein Musterstadt e.V."
    const val EMAIL_DOMAIN: String = "staging.invalid"
    const val STAGING_ADMIN_EMAIL: String = "admin.test@$EMAIL_DOMAIN"

    private val organizationSettingsId: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f2")
    private val standardTierId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000001")

    private data class SeedMember(
        val id: Uuid,
        val firstName: String,
        val lastName: String,
        val status: MemberStatus,
        val accountRole: AccountRole? = null,
        val dateOfDeath: LocalDate? = null,
    ) {
        val displayName: String get() = "$firstName $lastName"
        val email: String get() = "${firstName.lowercase()}.${lastName.lowercase()}@$EMAIL_DOMAIN"
    }

    private fun memberId(n: Int): Uuid = Uuid.parse("0000000a-0000-0000-0000-1000000000%02x".format(n))

    /**
     * ~18 fictitious members -- a deliberately trimmed subset of the ~40 the design sketch
     * envisioned (see `deploy/example/README.adoc` "Staging seed mechanism"), enough to
     * cover every [MemberStatus] literal at least once and every [AccountRole] at least once while
     * keeping this object reviewable. Ids are sequential within the `0000000a-...-10...`
     * namespace, disjoint from every other fixed-id namespace in this codebase.
     */
    private val seedMembers: List<SeedMember> =
        listOf(
            SeedMember(
                id = memberId(1),
                firstName = "Anna",
                lastName = "Musterfrau",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.ADMIN,
            ),
            SeedMember(
                id = memberId(2),
                firstName = "Bernd",
                lastName = "Beispiel",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.BOARD,
            ),
            SeedMember(
                id = memberId(3),
                firstName = "Clara",
                lastName = "Testfrau",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.TREASURER,
            ),
            SeedMember(
                id = memberId(4),
                firstName = "Daniel",
                lastName = "Dummland",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.MEMBER,
            ),
            SeedMember(
                id = memberId(5),
                firstName = "Erika",
                lastName = "Erfunden",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.MEMBER,
            ),
            SeedMember(
                id = memberId(6),
                firstName = "Frank",
                lastName = "Fiktiv",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.MEMBER,
            ),
            SeedMember(
                id = memberId(7),
                firstName = "Gisela",
                lastName = "Gedacht",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.MEMBER,
            ),
            SeedMember(
                id = memberId(8),
                firstName = "Hans",
                lastName = "Hypothetisch",
                status = MemberStatus.ACTIVE,
                accountRole = AccountRole.MEMBER,
            ),
            SeedMember(
                id = memberId(9),
                firstName = "Ines",
                lastName = "Imaginaer",
                status = MemberStatus.ACTIVE,
            ),
            SeedMember(
                id = memberId(10),
                firstName = "Jonas",
                lastName = "Jenseits",
                status = MemberStatus.ACTIVE,
            ),
            SeedMember(
                id = memberId(11),
                firstName = "Karla",
                lastName = "Konstrukt",
                status = MemberStatus.APPLICATION,
            ),
            SeedMember(
                id = memberId(12),
                firstName = "Lukas",
                lastName = "Luegenhaft",
                status = MemberStatus.APPLICATION,
            ),
            SeedMember(
                id = memberId(13),
                firstName = "Mia",
                lastName = "Modell",
                status = MemberStatus.FRIEND,
            ),
            SeedMember(
                id = memberId(14),
                firstName = "Noah",
                lastName = "Nichtreal",
                status = MemberStatus.DONOR,
            ),
            SeedMember(
                id = memberId(15),
                firstName = "Olga",
                lastName = "Ohnegrund",
                status = MemberStatus.WITHDRAWN,
            ),
            SeedMember(
                id = memberId(17),
                firstName = "Quentin",
                lastName = "Quasi",
                status = MemberStatus.GUEST,
            ),
            SeedMember(
                id = memberId(18),
                firstName = "Rita",
                lastName = "Rueckweisung",
                status = MemberStatus.REJECTED,
            ),
            SeedMember(
                id = memberId(16),
                firstName = "Peter",
                lastName = "Platzhalter",
                status = MemberStatus.DECEASED,
                dateOfDeath = LocalDate(2026, 3, 1),
            ),
        )

    private val boardCommitteeId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000010")
    private val pressCommitteeId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000011")
    private val plannedMeetingId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000020")
    private val heldMeetingId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000021")
    private val stagingEventId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000030")

    /**
     * Production entry point — called ONLY from `main()` in `Application.kt`, NEVER from
     * `Application.module()`. See [DevSeedData.seedIfEmpty]'s own KDoc/`Application.kt` comment for
     * why: `module()` re-runs on every `testApplication { application { module() } }` across this
     * whole test suite, and this function's own "already seeded?" check is a real DB read, not free.
     */
    fun seedIfEmpty(env: (String) -> String? = System::getenv) {
        when (val decision = StagingSeedConfig.decide(env)) {
            is StagingSeedDecision.Disabled -> return
            is StagingSeedDecision.Refused -> error(decision.reason)
            is StagingSeedDecision.Enabled -> {
                logger.warn {
                    "LAPIS_STAGING_MODE aktiv -- diese Instanz seedet FREI ERFUNDENE Testdaten " +
                        "(Organisation '$ORGANIZATION_NAME', Domain @$EMAIL_DOMAIN). Niemals gegen eine " +
                        "Instanz mit echten Mitgliederdaten verwenden."
                }
                synchronized(this) {
                    seedWithLockedTransaction(seedPassword = decision.seedPassword, database = null)
                }
            }
        }
    }

    /** Reine DB-Arbeit ohne Env-Gate -- nur fuer Tests gegen eine isolierte H2-DB gedacht. */
    internal fun seedWith(
        seedPassword: String,
        database: Database? = null,
    ) {
        synchronized(this) {
            seedWithLockedTransaction(seedPassword = seedPassword, database = database)
        }
    }

    private fun seedWithLockedTransaction(
        seedPassword: String,
        database: Database?,
    ) {
        transaction(database) {
            // Strongest real-world lock (see class KDoc point 2): a non-empty member table means
            // this either isn't a fresh staging volume, or (structurally impossible per points 3+4,
            // but checked anyway as defense in depth) it is PdV/ELB.
            val alreadyHasMembers = MemberTable.selectAll().limit(1).any()
            if (alreadyHasMembers) {
                logger.info { "member-Tabelle nicht leer -- Staging-Seeding uebersprungen." }
                return@transaction
            }

            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq organizationSettingsId }) {
                it[name] = ORGANIZATION_NAME
                it[street] = "Musterweg 1"
                it[postalCode] = "12345"
                it[city] = "Musterstadt"
                it[country] = "Deutschland"
                it[isPoliticalParty] = false
            }

            MembershipTierTable.insert {
                it[id] = standardTierId
                it[name] = "Standardbeitrag"
                it[description] = "Regulaerer Mitgliedsbeitrag, monatlich."
                it[contributionAmount] = BigDecimal("10.00")
                it[billingInterval] = BillingInterval.MONTHLY
                it[active] = true
            }

            // Hashed once, reused for every login-capable seed member -- see DevSeedData's own
            // comment for why (bcrypt is deliberately expensive per call).
            val seedPasswordHash = PasswordHasher.hash(seedPassword)

            seedMembers.forEach { seed ->
                MemberTable.insert {
                    it[id] = seed.id
                    it[displayName] = seed.displayName
                    it[email] = seed.email
                    it[status] = seed.status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = standardTierId
                    it[dateOfDeath] = seed.dateOfDeath
                }
                if (seed.accountRole != null) {
                    AccountTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = seed.id
                        it[role] = seed.accountRole
                        it[passwordHash] = seedPasswordHash
                    }
                }
            }

            // Reuse DevSeedData's chart of accounts rather than duplicating it -- read-only access
            // to the data list, not the H2-only seedIfEmpty() gate (see class KDoc).
            DevSeedData.demoLedgerAccounts.forEach { seed ->
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

            val activeMembers = seedMembers.filter { it.status == MemberStatus.ACTIVE }
            val contributionStatuses = listOf(ContributionStatus.PAID, ContributionStatus.OPEN, ContributionStatus.OVERDUE)
            activeMembers.forEach { member ->
                (0..2).forEach { monthOffset ->
                    val contributionPeriodStart = LocalDate(2026, 1 + monthOffset, 1)
                    val contributionStatus = contributionStatuses[monthOffset % contributionStatuses.size]
                    ContributionTable.insert {
                        it[id] = Uuid.random()
                        it[periodStart] = contributionPeriodStart
                        it[periodEnd] = LocalDate(2026, 1 + monthOffset, 28)
                        it[amountDue] = BigDecimal("10.00")
                        it[status] = contributionStatus
                        it[memberId] = member.id
                        it[membershipTierId] = standardTierId
                        it[dueDate] = contributionPeriodStart
                        it[paymentMethod] = ContributionPaymentMethod.MANUAL
                        it[createdAt] = DbClock.nowLocalDateTime()
                        if (contributionStatus == ContributionStatus.PAID) {
                            it[paidAt] = LocalDateTime(contributionPeriodStart, LocalTime(9, 0)).plusDays(3)
                            it[paidAmount] = BigDecimal("10.00")
                        }
                    }
                }
            }

            CommitteeTable.insert {
                it[id] = boardCommitteeId
                it[name] = "Vorstand"
                it[type] = CommitteeType.EXECUTIVE_BOARD
                it[description] = "Geschaeftsfuehrender Vorstand des Testvereins."
                it[active] = true
                it[quorumPercent] = 50
                it[createdAt] = DbClock.nowLocalDateTime()
            }
            CommitteeTable.insert {
                it[id] = pressCommitteeId
                it[name] = "AG Oeffentlichkeitsarbeit"
                it[type] = CommitteeType.WORKING_GROUP
                it[description] = "Arbeitsgruppe fuer Presse- und Oeffentlichkeitsarbeit."
                it[active] = true
                it[quorumPercent] = 50
                it[createdAt] = DbClock.nowLocalDateTime()
            }

            val admin = seedMembers[0]
            val board = seedMembers[1]
            val treasurer = seedMembers[2]
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[role] = CommitteeRole.CHAIR
                it[since] = LocalDate(2026, 1, 1)
                it[committeeId] = boardCommitteeId
                it[memberId] = admin.id
            }
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[role] = CommitteeRole.DEPUTY_CHAIR
                it[since] = LocalDate(2026, 1, 1)
                it[committeeId] = boardCommitteeId
                it[memberId] = board.id
            }
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[role] = CommitteeRole.SECRETARY
                it[since] = LocalDate(2026, 1, 1)
                it[committeeId] = boardCommitteeId
                it[memberId] = treasurer.id
            }
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[role] = CommitteeRole.MEMBER
                it[since] = LocalDate(2026, 1, 1)
                it[committeeId] = pressCommitteeId
                it[memberId] = seedMembers[3].id
            }

            val now = DbClock.nowLocalDateTime()
            MeetingTable.insert {
                it[id] = plannedMeetingId
                it[title] = "Vorstandssitzung Q3"
                it[scheduledAt] = now.plusDays(14)
                it[format] = MeetingFormat.ONLINE
                it[status] = MeetingStatus.PLANNED
                it[chairMemberId] = admin.id
                it[createdAt] = now
                it[committeeId] = boardCommitteeId
            }
            MeetingTable.insert {
                it[id] = heldMeetingId
                it[title] = "Vorstandssitzung Q2"
                it[scheduledAt] = now.minusDays(30)
                it[format] = MeetingFormat.IN_PERSON
                it[status] = MeetingStatus.HELD
                it[chairMemberId] = admin.id
                it[createdAt] = now.minusDays(37)
                it[committeeId] = boardCommitteeId
            }

            val plannedAgendaItem1 =
                insertAgendaItem(meetingId = plannedMeetingId, position = 1, title = "Begruessung", presenter = admin.id)
            insertAgendaItem(meetingId = plannedMeetingId, position = 2, title = "Kassenbericht", presenter = treasurer.id)
            insertAgendaItem(meetingId = plannedMeetingId, position = 3, title = "Antraege", presenter = admin.id)
            insertAgendaItem(meetingId = plannedMeetingId, position = 4, title = "Sonstiges", presenter = null)
            insertAgendaItem(meetingId = heldMeetingId, position = 1, title = "Begruessung", presenter = admin.id)
            insertAgendaItem(meetingId = heldMeetingId, position = 2, title = "Kassenbericht", presenter = treasurer.id)
            insertAgendaItem(meetingId = heldMeetingId, position = 3, title = "Sonstiges", presenter = null)

            MotionTable.insert {
                it[id] = Uuid.random()
                it[targetCommitteeId] = boardCommitteeId
                it[title] = "Anschaffung neuer Vereinswebseite"
                it[rationale] = "Die bestehende Webseite ist veraltet."
                it[text] = "Der Vorstand moege beschliessen, ein Budget fuer eine neue Webseite freizugeben."
                it[submitterMemberId] = board.id
                it[status] = MotionStatus.SUBMITTED
                it[submittedAt] = now
            }
            MotionTable.insert {
                it[id] = Uuid.random()
                it[targetCommitteeId] = boardCommitteeId
                it[title] = "Erhoehung des Foerdermitgliedsbeitrags"
                it[rationale] = "Anpassung an gestiegene Kosten."
                it[text] = "Der Vorstand moege beschliessen, den Foerdermitgliedsbeitrag auf 150 EUR/Jahr anzuheben."
                it[submitterMemberId] = treasurer.id
                it[status] = MotionStatus.SCHEDULED
                it[submittedAt] = now.minusDays(5)
                it[meetingId] = plannedMeetingId
                it[agendaItemId] = plannedAgendaItem1
            }

            EventTable.insert {
                it[id] = stagingEventId
                it[slug] = "testverein-mitgliederabend"
                it[title] = "Mitgliederabend"
                it[description] = "Geselliger Abend fuer alle Mitglieder des Testvereins."
                it[locationText] = "Vereinsheim Musterstadt"
                it[startsAt] = now.plusDays(21)
                it[endsAt] = now.plusDays(21).plusHours(3)
                it[capacity] = 50
                it[feeAmount] = BigDecimal("0.00")
                it[feeCurrency] = "EUR"
                it[status] = EventStatus.PUBLISHED
                it[visibility] = EventVisibility.MEMBERS_ONLY
                it[createdAt] = now
                it[createdBy] = admin.id
            }
            seedMembers.take(5).forEach { member ->
                EventRegistrationTable.insert {
                    it[id] = Uuid.random()
                    it[eventId] = stagingEventId
                    it[memberId] = member.id
                    it[activeParticipantKey] = "member:${member.id}"
                    it[status] = EventRegistrationStatus.CONFIRMED
                    it[feeAmount] = BigDecimal("0.00")
                    it[registeredAt] = now
                    it[confirmedAt] = now
                }
            }
        }
    }

    private fun JdbcTransaction.insertAgendaItem(
        meetingId: Uuid,
        position: Int,
        title: String,
        presenter: Uuid?,
    ): Uuid {
        val agendaItemId = Uuid.random()
        AgendaItemTable.insert {
            it[id] = agendaItemId
            it[this.position] = position
            it[this.title] = title
            it[presenterMemberId] = presenter
            it[this.meetingId] = meetingId
        }
        return agendaItemId
    }
}
