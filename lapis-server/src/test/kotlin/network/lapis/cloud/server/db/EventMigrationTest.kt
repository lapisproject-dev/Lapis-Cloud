package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventCateringOrderTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val ADMIN_UUID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- `V18__events.sql`'s CHECK constraints actually fire against
 * the real migrated H2 schema. Same "CHECK-Sonde" pattern [CrmMigrationTest]/
 * [SocialNetworkSchemaDriftTest] already establish: a raw `exec()` INSERT with an invalid-but-
 * column-width-fitting value, expecting an [ExposedSQLException] naming the violated constraint.
 */
class EventMigrationTest :
    FunSpec({
        // Review MINOR fix: every successful `probeInsert` (the positive controls, plus every
        // `createRealEvent()` fixture) previously committed a permanent `event`/`event_registration`
        // row into the shared H2 database with NO cleanup -- unlike this spec's siblings
        // (`EventCapacityTest`/`EventPersonalDataTest`), which both `afterSpec`-delete their own
        // fixtures. `newEventId()` is the single choke point every event id in this file now flows
        // through, so `afterSpec` below can delete every row this spec ever created (successfully or
        // not -- deleting a nonexistent id is simply a no-op).
        val createdEventIds = mutableListOf<Uuid>()

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    // Welle V1.4.3.5 "Catering-Management" addendum -- event_catering_order.event_id
                    // FK-references event(id) (V37__event_catering.sql), so any catering order this
                    // spec created (see the "V1.4.3.5" section below) must be deleted BEFORE the
                    // events themselves, in this SAME transaction -- otherwise this delete fails with
                    // a referential-integrity violation and rolls back, which in turn leaves the
                    // room-referencing events undeleted for the Room section's own afterSpec below.
                    EventCateringOrderTable.deleteWhere { eventId inList createdEventIds }
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
            }
        }

        fun newEventId(): Uuid = Uuid.random().also { createdEventIds += it }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun probeInsert(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        fun eventColumns(
            id: Uuid,
            locationText: String? = "Testort",
            onlineUrl: String? = null,
            startsAt: String = "2026-09-10 18:00:00",
            endsAt: String = "2026-09-10 22:00:00",
            capacity: Int? = 10,
            feeAmount: String = "0",
            status: String = "DRAFT",
            visibility: String = "PUBLIC",
        ): String {
            val locationSql = locationText?.let { "'$it'" } ?: "NULL"
            val onlineSql = onlineUrl?.let { "'$it'" } ?: "NULL"
            val capacitySql = capacity?.toString() ?: "NULL"
            return "INSERT INTO event (id, slug, title, description, location_text, online_url, starts_at, ends_at, " +
                "capacity, fee_amount, fee_currency, status, visibility, registration_closes_at, created_at, " +
                "created_by, cancelled_at) VALUES ('$id', 'probe-$id', 'Probe', 'Probe-Beschreibung', $locationSql, " +
                "$onlineSql, TIMESTAMP '$startsAt', TIMESTAMP '$endsAt', $capacitySql, $feeAmount, 'EUR', '$status', " +
                "'$visibility', NULL, TIMESTAMP '2026-01-01 00:00:00', '$ADMIN_UUID', NULL)"
        }

        test("chk_event_venue rejects both locationText and onlineUrl NULL") {
            val exception = probeInsert(eventColumns(id = newEventId(), locationText = null, onlineUrl = null))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_venue", ignoreCase = true) shouldBe true
        }

        test("chk_event_venue accepts onlineUrl alone") {
            val exception = probeInsert(eventColumns(id = newEventId(), locationText = null, onlineUrl = "https://example.org"))
            exception shouldBe null
        }

        test("chk_event_time_order rejects endsAt before startsAt") {
            val exception =
                probeInsert(eventColumns(id = newEventId(), startsAt = "2026-09-10 20:00:00", endsAt = "2026-09-10 19:00:00"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_time_order", ignoreCase = true) shouldBe true
        }

        test("chk_event_capacity rejects a zero capacity") {
            val exception = probeInsert(eventColumns(id = newEventId(), capacity = 0))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_capacity", ignoreCase = true) shouldBe true
        }

        test("chk_event_fee rejects a negative fee_amount") {
            val exception = probeInsert(eventColumns(id = newEventId(), feeAmount = "-1"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_fee", ignoreCase = true) shouldBe true
        }

        test("chk_event_status rejects an invalid literal that still fits VARCHAR(9)") {
            val exception = probeInsert(eventColumns(id = newEventId(), status = "BOGUS"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_status", ignoreCase = true) shouldBe true
        }

        test("chk_event_visibility rejects an invalid literal that still fits VARCHAR(12)") {
            val exception = probeInsert(eventColumns(id = newEventId(), visibility = "BOGUS"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_visibility", ignoreCase = true) shouldBe true
        }

        // ── event_registration ────────────────────────────────────────────────────────

        fun createRealEvent(): Uuid {
            val id = newEventId()
            val exception = probeInsert(eventColumns(id = id, status = "PUBLISHED"))
            check(exception == null) { "fixture event insert failed: $exception" }
            return id
        }

        fun registrationColumns(
            id: Uuid,
            eventId: Uuid,
            memberId: Uuid? = null,
            guestName: String? = "Gast",
            guestEmail: String? = "gast@example.org",
            activeParticipantKey: String? = "g:gast@example.org",
            status: String = "CONFIRMED",
            holdExpiresAt: String? = null,
            feeAmount: String = "0",
            // Welle V1.4.3.2 -- all four default NULL (omitted entirely would work the same way for
            // an INSERT with an explicit column list, but spelling them out here keeps every probe
            // below self-documenting about which of the four it is actually exercising).
            ticketCodeSha256: String? = null,
            ticketIssuedAt: String? = null,
            checkedInAt: String? = null,
            checkedInBy: Uuid? = null,
        ): String {
            val memberSql = memberId?.let { "'$it'" } ?: "NULL"
            val guestNameSql = guestName?.let { "'$it'" } ?: "NULL"
            val guestEmailSql = guestEmail?.let { "'$it'" } ?: "NULL"
            val keySql = activeParticipantKey?.let { "'$it'" } ?: "NULL"
            val holdSql = holdExpiresAt?.let { "TIMESTAMP '$it'" } ?: "NULL"
            val ticketHashSql = ticketCodeSha256?.let { "'$it'" } ?: "NULL"
            val ticketIssuedSql = ticketIssuedAt?.let { "TIMESTAMP '$it'" } ?: "NULL"
            val checkedInAtSql = checkedInAt?.let { "TIMESTAMP '$it'" } ?: "NULL"
            val checkedInBySql = checkedInBy?.let { "'$it'" } ?: "NULL"
            return "INSERT INTO event_registration (id, event_id, member_id, guest_name, guest_email, " +
                "active_participant_key, status, fee_amount, hold_expires_at, waitlist_position, " +
                "cancel_token_sha256, registered_at, confirmed_at, cancelled_at, waitlist_offered_at, " +
                "ticket_code_sha256, ticket_issued_at, checked_in_at, checked_in_by) VALUES (" +
                "'$id', '$eventId', $memberSql, $guestNameSql, $guestEmailSql, $keySql, '$status', $feeAmount, " +
                "$holdSql, NULL, NULL, TIMESTAMP '2026-01-01 00:00:00', NULL, NULL, NULL, " +
                "$ticketHashSql, $ticketIssuedSql, $checkedInAtSql, $checkedInBySql)"
        }

        test("chk_event_registration_identity rejects both member_id and guest fields set") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        memberId = ADMIN_UUID,
                        guestName = "X",
                        guestEmail = "x@example.org",
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_identity", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_identity rejects neither member_id nor guest fields set") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(id = Uuid.random(), eventId = eventId, memberId = null, guestName = null, guestEmail = null),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_identity", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_status rejects an invalid literal that still fits VARCHAR(15)") {
            val eventId = createRealEvent()
            val exception = probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, status = "BOGUS_STATUS"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_status", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_active_key rejects a CANCELLED row that still carries a key") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        status = "CANCELLED",
                        activeParticipantKey = "g:gast@example.org",
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_active_key", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_active_key rejects a CONFIRMED row with a NULL key") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, status = "CONFIRMED", activeParticipantKey = null))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_active_key", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_active_key accepts a CANCELLED row with a NULL key") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, status = "CANCELLED", activeParticipantKey = null))
            exception shouldBe null
        }

        test("chk_event_registration_hold rejects PENDING_PAYMENT with a NULL hold_expires_at") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, status = "PENDING_PAYMENT", holdExpiresAt = null))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_hold", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_hold accepts PENDING_PAYMENT with a hold_expires_at set") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        status = "PENDING_PAYMENT",
                        holdExpiresAt = "2026-09-10 18:30:00",
                    ),
                )
            exception shouldBe null
        }

        test("chk_event_registration_fee rejects a negative fee_amount") {
            val eventId = createRealEvent()
            val exception = probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, feeAmount = "-1"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_fee", ignoreCase = true) shouldBe true
        }

        test("uq_event_registration_active_participant allows two CANCELLED (NULL-key) rows but rejects a duplicate active key") {
            val eventId = createRealEvent()
            val c1 =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, status = "CANCELLED", activeParticipantKey = null))
            val c2 =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, status = "CANCELLED", activeParticipantKey = null))
            c1 shouldBe null
            c2 shouldBe null

            val sharedKey = "g:dup-${Uuid.random()}@example.org"
            val first = probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, activeParticipantKey = sharedKey))
            first shouldBe null
            val second = probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, activeParticipantKey = sharedKey))
            (second is ExposedSQLException) shouldBe true
        }

        // ── Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- V19__event_tickets.sql ────────

        test("chk_event_registration_ticket_issued rejects a hash without an issued_at") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = "a".repeat(64),
                        ticketIssuedAt = null,
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_ticket_issued", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_ticket_issued rejects an issued_at without a hash") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = null,
                        ticketIssuedAt = "2026-01-01 00:00:00",
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_ticket_issued", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_ticket_issued accepts both set together") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = "b".repeat(64),
                        ticketIssuedAt = "2026-01-01 00:00:00",
                    ),
                )
            exception shouldBe null
        }

        test("chk_event_registration_checkin_pair rejects checked_in_at without checked_in_by") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = "c".repeat(64),
                        ticketIssuedAt = "2026-01-01 00:00:00",
                        checkedInAt = "2026-01-01 12:00:00",
                        checkedInBy = null,
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_checkin_pair", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_checkin_pair rejects checked_in_by without checked_in_at") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = "d".repeat(64),
                        ticketIssuedAt = "2026-01-01 00:00:00",
                        checkedInAt = null,
                        checkedInBy = ADMIN_UUID,
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_checkin_pair", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_checkin_ticket rejects a check-in without a ticket") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = null,
                        ticketIssuedAt = null,
                        checkedInAt = "2026-01-01 12:00:00",
                        checkedInBy = ADMIN_UUID,
                    ),
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_checkin_ticket", ignoreCase = true) shouldBe true
        }

        test("chk_event_registration_checkin_ticket accepts a check-in WITH a ticket") {
            val eventId = createRealEvent()
            val exception =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventId,
                        ticketCodeSha256 = "e".repeat(64),
                        ticketIssuedAt = "2026-01-01 00:00:00",
                        checkedInAt = "2026-01-01 12:00:00",
                        checkedInBy = ADMIN_UUID,
                    ),
                )
            exception shouldBe null
        }

        test("uq_event_registration_ticket_code is global -- rejects the same hash on a DIFFERENT event") {
            val eventA = createRealEvent()
            val eventB = createRealEvent()
            val sharedHash = "f".repeat(64)
            val first =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventA,
                        activeParticipantKey = "g:ticket-dup-a@example.org",
                        ticketCodeSha256 = sharedHash,
                        ticketIssuedAt = "2026-01-01 00:00:00",
                    ),
                )
            first shouldBe null
            val second =
                probeInsert(
                    registrationColumns(
                        id = Uuid.random(),
                        eventId = eventB,
                        activeParticipantKey = "g:ticket-dup-b@example.org",
                        ticketCodeSha256 = sharedHash,
                        ticketIssuedAt = "2026-01-01 00:00:00",
                    ),
                )
            (second is ExposedSQLException) shouldBe true
            (second?.message ?: "").contains("uq_event_registration_ticket_code", ignoreCase = true) shouldBe true
        }

        test("uq_event_registration_ticket_code allows multiple NULL hashes") {
            val eventId = createRealEvent()
            val first =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, activeParticipantKey = "g:null-a@example.org"))
            val second =
                probeInsert(registrationColumns(id = Uuid.random(), eventId = eventId, activeParticipantKey = "g:null-b@example.org"))
            first shouldBe null
            second shouldBe null
        }

        // ── Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- V36__event_rooms.sql ───────────

        val createdRoomIds = mutableListOf<Uuid>()

        afterSpec {
            transaction {
                if (createdRoomIds.isNotEmpty()) {
                    EventRoomTable.deleteWhere { EventRoomTable.id inList createdRoomIds }
                }
            }
        }

        fun newRoomId(): Uuid = Uuid.random().also { createdRoomIds += it }

        fun roomColumns(
            id: Uuid,
            name: String = "Probe-Raum-$id",
            capacity: Int? = 10,
            status: String = "ACTIVE",
        ): String {
            val capacitySql = capacity?.toString() ?: "NULL"
            return "INSERT INTO event_room (id, name, capacity, equipment_tags, status, created_at, created_by) VALUES (" +
                "'$id', '$name', $capacitySql, '', '$status', TIMESTAMP '2026-01-01 00:00:00', '$ADMIN_UUID')"
        }

        test("chk_event_room_capacity rejects a zero capacity") {
            val exception = probeInsert(roomColumns(id = newRoomId(), capacity = 0))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_room_capacity", ignoreCase = true) shouldBe true
        }

        test("chk_event_room_capacity accepts a NULL capacity") {
            val exception = probeInsert(roomColumns(id = newRoomId(), capacity = null))
            exception shouldBe null
        }

        test("chk_event_room_status rejects an invalid literal that still fits VARCHAR(8)") {
            val exception = probeInsert(roomColumns(id = newRoomId(), status = "BOGUS"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_room_status", ignoreCase = true) shouldBe true
        }

        test("uq_event_room_name rejects a duplicate name") {
            val sharedName = "Duplikat-Raum-${Uuid.random()}"
            val first = probeInsert(roomColumns(id = newRoomId(), name = sharedName))
            first shouldBe null
            val second = probeInsert(roomColumns(id = newRoomId(), name = sharedName))
            (second is ExposedSQLException) shouldBe true
        }

        test("event.room_id is nullable and FK-references event_room, fk_event_room rejects an unknown id") {
            val eventId = createRealEvent()
            val bogusRoomId = Uuid.random()
            val exception =
                probeInsert(
                    "UPDATE event SET room_id = '$bogusRoomId' WHERE id = '$eventId'",
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("fk_event_room", ignoreCase = true) shouldBe true
        }

        test("event.room_id accepts a real event_room id") {
            val eventId = createRealEvent()
            val roomId = newRoomId()
            val roomInsert = probeInsert(roomColumns(id = roomId))
            roomInsert shouldBe null
            val exception = probeInsert("UPDATE event SET room_id = '$roomId' WHERE id = '$eventId'")
            exception shouldBe null
        }

        // ── Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- V37__event_catering.sql ──

        val createdOrderIds = mutableListOf<Uuid>()

        afterSpec {
            transaction {
                if (createdOrderIds.isNotEmpty()) {
                    EventCateringOrderTable.deleteWhere { EventCateringOrderTable.id inList createdOrderIds }
                }
            }
        }

        fun newOrderId(): Uuid = Uuid.random().also { createdOrderIds += it }

        fun cateringOrderColumns(
            id: Uuid,
            eventId: Uuid,
            quantity: Int = 10,
            status: String = "PLANNED",
        ): String =
            "INSERT INTO event_catering_order (id, event_id, description, quantity, allergen_notes, status, " +
                "created_at, created_by) VALUES ('$id', '$eventId', 'Probe-Position', $quantity, NULL, '$status', " +
                "TIMESTAMP '2026-01-01 00:00:00', '$ADMIN_UUID')"

        test("chk_event_catering_order_quantity rejects a zero quantity") {
            val eventId = createRealEvent()
            val exception = probeInsert(cateringOrderColumns(id = newOrderId(), eventId = eventId, quantity = 0))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_catering_order_quantity", ignoreCase = true) shouldBe true
        }

        test("chk_event_catering_order_quantity rejects a negative quantity") {
            val eventId = createRealEvent()
            val exception = probeInsert(cateringOrderColumns(id = newOrderId(), eventId = eventId, quantity = -1))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_catering_order_quantity", ignoreCase = true) shouldBe true
        }

        test("chk_event_catering_order_quantity accepts a positive quantity") {
            val eventId = createRealEvent()
            val exception = probeInsert(cateringOrderColumns(id = newOrderId(), eventId = eventId, quantity = 1))
            exception shouldBe null
        }

        test("chk_event_catering_order_status rejects an invalid literal that still fits VARCHAR(9)") {
            val eventId = createRealEvent()
            val exception = probeInsert(cateringOrderColumns(id = newOrderId(), eventId = eventId, status = "BOGUSSSS"))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_catering_order_status", ignoreCase = true) shouldBe true
        }

        test("fk_event_catering_order_event rejects an unknown event id") {
            val bogusEventId = Uuid.random()
            val exception = probeInsert(cateringOrderColumns(id = newOrderId(), eventId = bogusEventId))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("fk_event_catering_order_event", ignoreCase = true) shouldBe true
        }

        test("fk_event_catering_order_event accepts a real event id") {
            val eventId = createRealEvent()
            val exception = probeInsert(cateringOrderColumns(id = newOrderId(), eventId = eventId))
            exception shouldBe null
        }

        // ── Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen" -- V38__event_invoice.sql ──

        val createdOpenItemIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        afterSpec {
            transaction {
                if (createdOpenItemIds.isNotEmpty()) {
                    // event_registration.open_item_id FK-references open_item(id) -- clear it
                    // before deleting the referenced open_item row, same ordering reasoning the
                    // Catering-order cleanup above already documents.
                    EventRegistrationTable.update({ EventRegistrationTable.openItemId inList createdOpenItemIds }) {
                        it[openItemId] = null
                        it[invoiceIssuedAt] = null
                        it[invoiceIssuedBy] = null
                    }
                    OpenItemTable.deleteWhere { OpenItemTable.id inList createdOpenItemIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
            }
        }

        fun newIncomeLedgerAccount(): Uuid {
            val id = Uuid.random()
            val number = "I${id.toString().filter { it.isDigit() }.take(9)}"
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Test-Ertragskonto $number"
                    it[accountClass] = 4
                    it[type] = LedgerAccountType.INCOME
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun newOpenItem(contraAccountId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                OpenItemTable.insert {
                    it[OpenItemTable.id] = id
                    it[direction] = OpenItemDirection.RECEIVABLE
                    it[counterpartyName] = "Testgast"
                    it[counterpartyKey] = "testgast"
                    it[crmContactId] = null
                    it[reference] = null
                    it[itemDate] = LocalDate(2026, 1, 1)
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[amount] = BigDecimal("10.00")
                    it[OpenItemTable.contraAccountId] = contraAccountId
                    it[sphere] = GemeinnuetzigkeitSphere.ZWECKBETRIEB
                    it[status] = OpenItemStatus.OPEN
                    it[note] = null
                    it[createdByMemberId] = ADMIN_UUID
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdOpenItemIds += id
            return id
        }

        test("chk_event_registration_invoice_consistency rejects invoice_issued_at set without open_item_id") {
            val eventId = createRealEvent()
            val registrationId = Uuid.random()
            val insertException = probeInsert(registrationColumns(id = registrationId, eventId = eventId))
            insertException shouldBe null
            val exception =
                probeInsert(
                    "UPDATE event_registration SET invoice_issued_at = TIMESTAMP '2026-01-01 00:00:00' WHERE id = '$registrationId'",
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_invoice_consistency", ignoreCase = true) shouldBe true
        }

        // Review MINOR fix: the constraint's own `(open_item_id IS NULL) = (invoice_issued_at IS
        // NULL)` shape is symmetric, but only the ONE direction above (invoice_issued_at set
        // without open_item_id) had a probe -- the mirror-image asymmetry (open_item_id set,
        // invoice_issued_at left NULL) was never actually exercised. `open_item_id` also has its
        // own FK against a real `open_item` row, so this probe needs one (via [newOpenItem]) --
        // otherwise a bogus id would fail on `fk_event_registration_open_item` first and never
        // reach the CHECK constraint this test targets.
        test("chk_event_registration_invoice_consistency rejects open_item_id set without invoice_issued_at") {
            val eventId = createRealEvent()
            val registrationId = Uuid.random()
            val insertException = probeInsert(registrationColumns(id = registrationId, eventId = eventId))
            insertException shouldBe null
            val accountId = newIncomeLedgerAccount()
            val openItemId = newOpenItem(accountId)
            val exception =
                probeInsert(
                    "UPDATE event_registration SET open_item_id = '$openItemId' WHERE id = '$registrationId'",
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_event_registration_invoice_consistency", ignoreCase = true) shouldBe true
        }

        test("fk_event_registration_open_item rejects an unknown open_item_id") {
            val eventId = createRealEvent()
            val registrationId = Uuid.random()
            val insertException = probeInsert(registrationColumns(id = registrationId, eventId = eventId))
            insertException shouldBe null
            val bogusOpenItemId = Uuid.random()
            val exception =
                probeInsert(
                    "UPDATE event_registration SET open_item_id = '$bogusOpenItemId', " +
                        "invoice_issued_at = TIMESTAMP '2026-01-01 00:00:00' WHERE id = '$registrationId'",
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("fk_event_registration_open_item", ignoreCase = true) shouldBe true
        }

        test("event_registration.open_item_id accepts a real open_item id, together with invoice_issued_at/invoice_issued_by") {
            val eventId = createRealEvent()
            val registrationId = Uuid.random()
            val insertException = probeInsert(registrationColumns(id = registrationId, eventId = eventId))
            insertException shouldBe null
            val accountId = newIncomeLedgerAccount()
            val openItemId = newOpenItem(accountId)
            val exception =
                probeInsert(
                    "UPDATE event_registration SET open_item_id = '$openItemId', " +
                        "invoice_issued_at = TIMESTAMP '2026-01-01 00:00:00', invoice_issued_by = '$ADMIN_UUID' " +
                        "WHERE id = '$registrationId'",
                )
            exception shouldBe null
        }

        test("event_registration billing_* columns accept a full address") {
            val eventId = createRealEvent()
            val registrationId = Uuid.random()
            val insertException = probeInsert(registrationColumns(id = registrationId, eventId = eventId))
            insertException shouldBe null
            val exception =
                probeInsert(
                    "UPDATE event_registration SET billing_street = 'Teststrasse 1', billing_postal_code = '38100', " +
                        "billing_city = 'Braunschweig', billing_country = 'Deutschland' WHERE id = '$registrationId'",
                )
            exception shouldBe null
        }

        test("V38 migration is idempotent -- a billing_*/open_item UPDATE round-trip still succeeds against the already-migrated schema") {
            val eventId = createRealEvent()
            val registrationId = Uuid.random()
            val insertException = probeInsert(registrationColumns(id = registrationId, eventId = eventId))
            insertException shouldBe null
            val accountId = newIncomeLedgerAccount()
            val openItemId = newOpenItem(accountId)
            val updateException =
                probeInsert(
                    "UPDATE event_registration SET billing_city = 'Braunschweig', open_item_id = '$openItemId', " +
                        "invoice_issued_at = TIMESTAMP '2026-01-01 00:00:00', invoice_issued_by = '$ADMIN_UUID' " +
                        "WHERE id = '$registrationId'",
                )
            updateException shouldBe null
            val revertException =
                probeInsert(
                    "UPDATE event_registration SET billing_city = NULL, open_item_id = NULL, invoice_issued_at = NULL, " +
                        "invoice_issued_by = NULL WHERE id = '$registrationId'",
                )
            revertException shouldBe null
        }

        test("V37 migration is idempotent -- re-running the DDL on an already-migrated schema does not fail") {
            // Same "CREATE TABLE/INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD" idiom
            // every migration in this repo follows (see V37__event_catering.sql header) -- probing
            // a second, real INSERT/DELETE round-trip after the schema is already live is the
            // simplest proof that the constraints Flyway already applied are still exactly the ones
            // this test suite expects (an actual second `flyway migrate` run is exercised by
            // `DatabaseConfig.connect()` itself at `beforeSpec` time across the whole test suite).
            val eventId = createRealEvent()
            val orderId = newOrderId()
            val insertException = probeInsert(cateringOrderColumns(id = orderId, eventId = eventId))
            insertException shouldBe null
            val deleteException = probeInsert("DELETE FROM event_catering_order WHERE id = '$orderId'")
            deleteException shouldBe null
        }
    })
