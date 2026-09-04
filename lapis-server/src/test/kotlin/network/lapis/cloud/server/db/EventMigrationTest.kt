package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        ): String {
            val memberSql = memberId?.let { "'$it'" } ?: "NULL"
            val guestNameSql = guestName?.let { "'$it'" } ?: "NULL"
            val guestEmailSql = guestEmail?.let { "'$it'" } ?: "NULL"
            val keySql = activeParticipantKey?.let { "'$it'" } ?: "NULL"
            val holdSql = holdExpiresAt?.let { "TIMESTAMP '$it'" } ?: "NULL"
            return "INSERT INTO event_registration (id, event_id, member_id, guest_name, guest_email, " +
                "active_participant_key, status, fee_amount, hold_expires_at, waitlist_position, " +
                "cancel_token_sha256, registered_at, confirmed_at, cancelled_at, waitlist_offered_at) VALUES (" +
                "'$id', '$eventId', $memberSql, $guestNameSql, $guestEmailSql, $keySql, '$status', $feeAmount, " +
                "$holdSql, NULL, NULL, TIMESTAMP '2026-01-01 00:00:00', NULL, NULL, NULL)"
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
    })
