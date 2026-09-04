package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- [EventPersonalData]'s export/erase behavior, mirroring
 * `PublicRankingConsentPersonalDataTest`'s house style (direct `transaction { Contributor.export/
 * erase(...) }` calls, no route/service layer). [PersonalDataCoverageTest] only proves the tables
 * are covered by SOME contributor -- this file exercises THIS contributor's specific
 * "retain-with-reason" posture (see class KDoc).
 */
class EventPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        test("exportMember includes the member's own registrations and the events they created") {
            val organizer = createTestMember("event-pd-organizer-${Uuid.random()}@example.org")
            val now = DbClock.nowLocalDateTime()
            val eventId = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = eventId
                    it[slug] = "event-pd-test-$eventId"
                    it[title] = "PD-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = now
                    it[endsAt] = now
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = now
                    it[EventTable.createdBy] = organizer
                    it[cancelledAt] = null
                }
                EventRegistrationTable.insert {
                    it[id] = Uuid.random()
                    it[EventRegistrationTable.eventId] = eventId
                    it[memberId] = organizer
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = "m:$organizer"
                    it[status] = EventRegistrationStatus.CONFIRMED
                    it[feeAmount] = BigDecimal.ZERO
                    it[holdExpiresAt] = null
                    it[waitlistPosition] = null
                    it[cancelTokenSha256] = null
                    it[registeredAt] = now
                    it[confirmedAt] = now
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
            createdEventIds += eventId

            val export = transaction { EventPersonalData.exportMember(organizer) }
            val registrations = export.jsonObject["registrations"]!!.jsonArray
            val createdEvents = export.jsonObject["createdEvents"]!!.jsonArray
            registrations.size shouldBe 1
            createdEvents.size shouldBe 1
        }

        test("eraseMember retains both event and event_registration rows with a written reason, never deletes") {
            val organizer = createTestMember("event-pd-erase-${Uuid.random()}@example.org")
            val now = DbClock.nowLocalDateTime()
            val eventId = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = eventId
                    it[slug] = "event-pd-erase-test-$eventId"
                    it[title] = "PD-Erase-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = now
                    it[endsAt] = now
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = now
                    it[EventTable.createdBy] = organizer
                    it[cancelledAt] = null
                }
                EventRegistrationTable.insert {
                    it[id] = Uuid.random()
                    it[EventRegistrationTable.eventId] = eventId
                    it[memberId] = organizer
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = "m:$organizer"
                    it[status] = EventRegistrationStatus.CONFIRMED
                    it[feeAmount] = BigDecimal.ZERO
                    it[holdExpiresAt] = null
                    it[waitlistPosition] = null
                    it[cancelTokenSha256] = null
                    it[registeredAt] = now
                    it[confirmedAt] = now
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
            createdEventIds += eventId

            val outcomes = transaction { EventPersonalData.eraseMember(memberId = organizer, mode = ErasureMode.ANONYMIZE) }
            val registrationOutcome = outcomes.single { it.table == "event_registration" }
            val eventOutcome = outcomes.single { it.table == "event" }
            registrationOutcome.rowsRetained shouldBe 1
            registrationOutcome.rowsDeleted shouldBe 0
            registrationOutcome.retentionReason?.isNotBlank() shouldBe true
            eventOutcome.rowsRetained shouldBe 1
            eventOutcome.rowsDeleted shouldBe 0
            eventOutcome.retentionReason?.isNotBlank() shouldBe true

            // Rows genuinely still exist -- "retained" is not a euphemism for silently deleted.
            val stillThere =
                transaction {
                    EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq eventId }.count()
                }
            stillThere shouldBe 1L
        }
    })
