package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.rpc.carpoolTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.CarpoolTeaser
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.projectCarpoolTeaser
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 -- the consent (`setCarpoolPostingFederationOptIn`), the availability read, the visibility of the flag and
 * the one projection, on H2 AND PostgreSQL with the same assertions. Nothing is transferred in this wave: only the switches are tested.
 */
abstract class CarpoolFederationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest { transaction { CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2) } }
        afterSpec {
            try {
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun switchOff() = transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }

        fun storedOptIn(id: Uuid) = transaction { CarpoolFederationStore.optIn(id) }

        fun auditRows(id: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.CARPOOL_FEDERATION_OPT_IN) and
                            (AuditLogEntryTable.entityId eq id)
                    }.toList()
            }

        fun shareable(author: Uuid) = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")

        test("a member reads the switch; a guest is refused") {
            carpoolTestApplication {
                val member = data.member(label = "fed-avail")
                val guest = data.member(label = "fed-avail-guest", status = MemberStatus.GUEST)
                call(member = member) { getCarpoolFederationAvailability() }.enabled shouldBe true
                switchOff()
                call(member = member) { getCarpoolFederationAvailability() }.enabled shouldBe false
                attempt(member = guest) { getCarpoolFederationAvailability() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("the owner gives and withdraws consent: one audit per real change, none when unchanged, no place or date in the snapshot") {
            carpoolTestApplication {
                val owner = data.member(label = "fed-owner")
                val id = shareable(owner)
                val on = call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = true) }
                on.federationOptIn shouldBe true
                storedOptIn(id) shouldBe true
                auditRows(id) shouldHaveSize 1
                call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = true) }.federationOptIn shouldBe
                    true
                auditRows(id) shouldHaveSize 1
                val off = call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false) }
                off.federationOptIn shouldBe false
                storedOptIn(id) shouldBe false
                call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false) }
                val rows = auditRows(id)
                rows shouldHaveSize 2
                val snapshots = rows.map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
                snapshots.toSet() shouldBe setOf("{\"optIn\":true}", "{\"optIn\":false}")
                snapshots.forEach {
                    it shouldNotContain "Braunschweig"
                    it shouldNotContain "Hannover"
                    it shouldNotContain "38100"
                }
            }
        }

        test("consent is refused (conflict) with the switch off, without a postcode, and on a request; withdrawal is always allowed") {
            carpoolTestApplication {
                val owner = data.member(label = "fed-conflict")
                val noPlz = data.posting(author = owner, fromPlace = "Braunschweig", toPlace = "Hannover")
                val request = data.posting(author = owner, type = CarpoolPostingType.REQUEST, fromPlace = "38100 X", toPlace = "30159 Y")
                attempt(member = owner) {
                    setCarpoolPostingFederationOptIn(postingId = noPlz.toString(), optIn = true)
                }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                attempt(member = owner) {
                    setCarpoolPostingFederationOptIn(postingId = request.toString(), optIn = true)
                }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                // withdrawing something that is not set is a plain no-op, also on a request
                call(
                    member = owner,
                ) { setCarpoolPostingFederationOptIn(postingId = request.toString(), optIn = false) }.federationOptIn shouldBe
                    false
                val ok = shareable(owner)
                call(member = owner) { setCarpoolPostingFederationOptIn(postingId = ok.toString(), optIn = true) }
                switchOff()
                attempt(member = owner) { setCarpoolPostingFederationOptIn(postingId = shareable(owner).toString(), optIn = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                // the switch is off, yet the owner can still withdraw
                call(member = owner) { setCarpoolPostingFederationOptIn(postingId = ok.toString(), optIn = false) }.federationOptIn shouldBe
                    false
                storedOptIn(ok) shouldBe false
            }
        }

        test("a foreign trip gets the constant not-found answer (also for an admin); a guest is refused; the flag never leaks to others") {
            carpoolTestApplication {
                val owner = data.member(label = "fed-own2")
                val other = data.member(label = "fed-other")
                val admin = data.member(label = "fed-admin", role = AccountRole.ADMIN)
                val guest = data.member(label = "fed-guest", status = MemberStatus.GUEST)
                val id = shareable(owner)
                call(member = owner) { setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = true) }
                attempt(member = other) {
                    setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = admin) {
                    setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = guest) {
                    setCarpoolPostingFederationOptIn(postingId = id.toString(), optIn = false)
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(member = other) { setCarpoolPostingFederationOptIn(postingId = Uuid.random().toString(), optIn = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                storedOptIn(id) shouldBe true
                call(member = owner) { listMyPostings() }.single { it.id == id.toString() }.federationOptIn shouldBe true
                call(member = other) { listPostings(null) }.single { it.id == id.toString() }.federationOptIn shouldBe false
                call(member = guest) { listPostings(null) }.single { it.id == id.toString() }.federationOptIn shouldBe false
            }
        }

        test("a type change to a request resets the consent; a new trip starts without consent; updating a place keeps it") {
            carpoolTestApplication {
                val owner = data.member(label = "fed-reset")
                val future = data.today().plus(DatePeriod(days = 12))
                val created =
                    call(member = owner) {
                        createPosting(
                            CarpoolPostingInput(
                                type = CarpoolPostingType.OFFER,
                                fromPlace = "38100 Braunschweig",
                                toPlace = "30159 Hannover",
                                departureDate = future,
                                departureTime = LocalTime(9, 0),
                                seatsOffered = 2,
                            ),
                        )
                    }
                created.federationOptIn shouldBe false
                call(member = owner) { setCarpoolPostingFederationOptIn(postingId = created.id, optIn = true) }
                val moved =
                    call(member = owner) {
                        updatePosting(
                            id = created.id,
                            input =
                                CarpoolPostingInput(
                                    type = CarpoolPostingType.OFFER,
                                    fromPlace = "38100 Braunschweig",
                                    toPlace = "30161 Hannover",
                                    departureDate = future,
                                    seatsOffered = 2,
                                ),
                        )
                    }
                moved.federationOptIn shouldBe true
                val asRequest =
                    call(member = owner) {
                        updatePosting(
                            id = created.id,
                            input =
                                CarpoolPostingInput(
                                    type = CarpoolPostingType.REQUEST,
                                    fromPlace = "38100 Braunschweig",
                                    toPlace = "30161 Hannover",
                                    departureDate = future,
                                ),
                        )
                    }
                asRequest.federationOptIn shouldBe false
                storedOptIn(Uuid.parse(created.id)) shouldBe false
            }
        }

        test("marker leak: the projection of a trip whose every column carries a marker contains none of them") {
            val author = data.member(label = "fed-leak", displayName = "MARKER-ANZEIGENAME")
            val id =
                data.posting(
                    author = author,
                    seats = 7,
                    fromPlace = "MARKER-ORT-A 38100",
                    toPlace = "MARKER-ORT-B 30159",
                    notes = "MARKER-NOTIZ",
                    meetingNote = "MARKER-TREFFPUNKT",
                )
            transaction {
                CarpoolPostingTable.update({ CarpoolPostingTable.id eq id }) { it[departureTime] = java.time.LocalTime.of(13, 37) }
            }
            val row = transaction { CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq id }.single() }
            val teaser =
                projectCarpoolTeaser(
                    fromPlace = row[CarpoolPostingTable.fromPlace],
                    toPlace = row[CarpoolPostingTable.toPlace],
                    departureDate = row[CarpoolPostingTable.departureDate],
                    teaserId = CarpoolTeaser.PREVIEW_ID,
                )!!
            val json = Json.encodeToString(CarpoolTeaser.serializer(), teaser)
            listOf("MARKER", "13:37", author.toString(), id.toString(), "NOTIZ", "TREFFPUNKT").forEach { json shouldNotContain it }
            json shouldContain "\"fromPostcodeArea\":\"38\""
            json shouldContain "\"toPostcodeArea\":\"30\""
        }
    })
