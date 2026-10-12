package network.lapis.cloud.server.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LegacyOutcome
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.121 -- the bulk reconciliation of the legacy country / nationality texts: dry run writes nothing, groups below the
 * threshold never appear verbatim, apply is bound to the dry run's fingerprint and writes only compare-and-set, the audit entry
 * carries counts only, ADMIN accounts are skipped. The shared H2 database may hold legacy values of other specs, so every assertion
 * is about values made unique by [tag] (or about deltas).
 */
class CountryLegacyMappingTest :
    FunSpec({
        val fx = PeerFixture()
        val tag = Uuid.random().toString().take(8)
        val unrecognised = "Wakanda-$tag"

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.isolateAdmins() }
        afterTest { fx.restoreAdmins() }

        fun legacyMember(
            country: String? = null,
            nationality: String? = null,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = fx.member(role = role)
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[MemberTable.country] = country
                    it[MemberTable.nationality] = nationality
                }
            }
            return id
        }

        fun many(
            n: Int,
            country: String? = null,
            nationality: String? = null,
        ): List<Uuid> = (1..n).map { legacyMember(country = country, nationality = nationality) }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun codesOf(id: Uuid): List<String> =
            transaction {
                MemberNationalityTable
                    .selectAll()
                    .where { MemberNationalityTable.memberId eq id }
                    .map { it[MemberNationalityTable.countryCode] }
                    .sorted()
            }

        fun snapshot(): Pair<List<List<String?>>, Long> =
            transaction {
                MemberTable
                    .selectAll()
                    .map {
                        listOf(
                            it[MemberTable.id].toString(),
                            it[MemberTable.country],
                            it[MemberTable.countryCode],
                            it[MemberTable.nationality],
                        )
                    }.sortedBy { it[0] } to MemberNationalityTable.selectAll().count()
            }

        fun admin(): Uuid = fx.admin()

        test("the dry run writes nothing at all") {
            many(3, country = "Polska", nationality = "polnisch, deutsch")
            val actor = admin()
            val before = snapshot()
            val report = CountryLegacyMappingEngine.preview(actor)
            (report.fingerprint.length) shouldBe 64
            snapshot() shouldBe before
        }

        test("groups of three or more are listed with outcome and targets; rarer values appear only as counts") {
            val actor = admin()
            val polish = many(3, country = "Polska", nationality = "polnisch, deutsch")
            many(3, country = unrecognised, nationality = "Wakanda-nat-$tag")
            many(3, country = "Kongo", nationality = "Kongo")
            val rareCountry = "Frankreich"
            val rareTag = "Rare-$tag"
            legacyMember(country = rareCountry)
            legacyMember(country = rareTag)
            legacyMember(nationality = "rare-nat-$tag")
            val report = CountryLegacyMappingEngine.preview(actor)

            val polska = report.country.lines.single { it.value == "Polska" }
            polska.outcome shouldBe LegacyOutcome.RECOGNISED
            polska.targets shouldBe listOf("PL")
            (polska.count >= 3) shouldBe true
            report.country.lines
                .single { it.value == unrecognised }
                .outcome shouldBe LegacyOutcome.UNRECOGNISED
            report.country.lines
                .single { it.value == "Kongo" }
                .outcome shouldBe LegacyOutcome.AMBIGUOUS
            report.nationality.lines
                .single { it.value == "polnisch, deutsch" }
                .targets shouldBe listOf("DE", "PL")
            // a value shared by fewer than three members never appears verbatim
            report.country.lines.none { it.value == rareTag } shouldBe true
            report.nationality.lines.none { it.value == "rare-nat-$tag" } shouldBe true
            (report.country.rare.members >= 1) shouldBe true
            (report.country.rare.recognised >= 0) shouldBe true
            (polish.size) shouldBe 3
        }

        test(
            "apply sets the code, KEEPS the legacy text, leaves ambiguous and unrecognised values open, and a second run applies nothing of ours",
        ) {
            val actor = admin()
            val polish = many(3, country = "Polska", nationality = "polnisch, deutsch")
            val unknown = many(3, country = unrecognised, nationality = "Wakanda-nat-$tag")
            val kongo = many(3, country = "Kongo", nationality = "Kongo")
            val report = CountryLegacyMappingEngine.preview(actor)
            val result =
                CountryLegacyMappingEngine.apply(
                    actor = fx.actor(id = actor),
                    expectedFingerprint = report.fingerprint,
                    now = DbClock.nowLocalDateTime(),
                )

            polish.forEach {
                row(it)[MemberTable.countryCode] shouldBe "PL"
                row(it)[MemberTable.country] shouldBe "Polska"
                codesOf(it) shouldBe listOf("DE", "PL")
                row(it)[MemberTable.nationality] shouldBe "polnisch, deutsch"
            }
            (unknown + kongo).forEach {
                row(it)[MemberTable.countryCode] shouldBe null
                codesOf(it) shouldBe emptyList()
            }
            (result.country.applied >= 3) shouldBe true
            (result.nationality.applied >= 3) shouldBe true
            (result.country.ambiguous >= 3) shouldBe true

            val again = CountryLegacyMappingEngine.preview(actor)
            again.country.lines.none { it.value == "Polska" } shouldBe true
            val second =
                CountryLegacyMappingEngine.apply(
                    actor = fx.actor(id = actor),
                    expectedFingerprint = again.fingerprint,
                    now = DbClock.nowLocalDateTime(),
                )
            polish.forEach { codesOf(it) shouldBe listOf("DE", "PL") }
            (second.country.recognised) shouldBe 0
            (second.country.applied) shouldBe 0
        }

        test("a plan that changed since the dry run is refused and nothing is written") {
            val actor = admin()
            val polish = many(3, country = "Polska")
            val report = CountryLegacyMappingEngine.preview(actor)
            legacyMember(country = "Brand-neu-$tag") // a new distinct value changes the plan
            val before = snapshot()
            shouldThrow<ConflictException> {
                CountryLegacyMappingEngine.apply(
                    actor = fx.actor(id = actor),
                    expectedFingerprint = report.fingerprint,
                    now = DbClock.nowLocalDateTime(),
                )
            }
            snapshot() shouldBe before
            polish.forEach { row(it)[MemberTable.countryCode] shouldBe null }
            shouldThrow<ConflictException> {
                CountryLegacyMappingEngine.apply(
                    actor = fx.actor(id = actor),
                    expectedFingerprint = "0".repeat(64),
                    now = DbClock.nowLocalDateTime(),
                )
            }
        }

        test("a member that already has a code or rows is not touched (compare-and-set / plan filter)") {
            val actor = admin()
            val withCode = legacyMember(country = "Polska", nationality = "polnisch")
            transaction {
                MemberTable.update({ MemberTable.id eq withCode }) { it[countryCode] = "AT" }
                MemberNationalityTable.insert {
                    it[memberId] = withCode
                    it[countryCode] = "CH"
                }
            }
            many(3, country = "Polska", nationality = "polnisch")
            val report = CountryLegacyMappingEngine.preview(actor)
            CountryLegacyMappingEngine.apply(
                actor = fx.actor(id = actor),
                expectedFingerprint = report.fingerprint,
                now = DbClock.nowLocalDateTime(),
            )
            row(withCode)[MemberTable.countryCode] shouldBe "AT"
            codesOf(withCode) shouldBe listOf("CH")
        }

        test("ADMIN accounts other than the caller are skipped and counted; the caller's own record is included") {
            val actor = admin()
            transaction { MemberTable.update({ MemberTable.id eq actor }) { it[country] = "Polska" } }
            val otherAdmin = legacyMember(country = "Polska", nationality = "polnisch", role = AccountRole.ADMIN)
            val board = legacyMember(country = "Polska", nationality = "polnisch", role = AccountRole.BOARD)
            val before = CountryLegacyMappingEngine.preview(actor)
            before.country.protectedSkippedMembers shouldBe 1
            before.nationality.protectedSkippedMembers shouldBe 1
            CountryLegacyMappingEngine.apply(
                actor = fx.actor(id = actor),
                expectedFingerprint = before.fingerprint,
                now = DbClock.nowLocalDateTime(),
            )
            row(otherAdmin)[MemberTable.countryCode] shouldBe null
            codesOf(otherAdmin) shouldBe emptyList()
            row(board)[MemberTable.countryCode] shouldBe "PL"
            row(actor)[MemberTable.countryCode] shouldBe "PL"
        }

        test("the audit entry carries counts only: no value, no member id of a subject") {
            val actor = admin()
            val secret = "Polska"
            val subjects = many(3, country = secret, nationality = "polnisch")
            val report = CountryLegacyMappingEngine.preview(actor)
            CountryLegacyMappingEngine.apply(
                actor = fx.actor(id = actor),
                expectedFingerprint = report.fingerprint,
                now = DbClock.nowLocalDateTime(),
            )
            val entry =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { (AuditLogEntryTable.entityId eq actor) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
                        .single()
                }
            val after = entry[AuditLogEntryTable.afterSnapshot]!!
            after shouldMatch Regex("""MEMBER_COUNTRY_LEGACY_MAPPED country=\d+/\d+/\d+/\d+ nationality=\d+/\d+/\d+/\d+""")
            (listOfNotNull(entry[AuditLogEntryTable.beforeSnapshot], after)).forEach { text ->
                listOf(secret, "polnisch", "PL").forEach { text shouldNotContain it }
                subjects.forEach { text shouldNotContain it.toString() }
            }
        }

        test("the row cap stops an apply before anything is written") {
            val actor = admin()
            val polish = many(3, country = "Polska")
            val report = CountryLegacyMappingEngine.preview(actor)
            val before = snapshot()
            shouldThrow<ConflictException> {
                CountryLegacyMappingEngine.apply(
                    actor = fx.actor(id = actor),
                    expectedFingerprint = report.fingerprint,
                    now = DbClock.nowLocalDateTime(),
                    maxRows = 1,
                )
            }
            snapshot() shouldBe before
            polish.forEach { row(it)[MemberTable.countryCode] shouldBe null }
        }
    })
