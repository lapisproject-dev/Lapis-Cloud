package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationMessage
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Sample data for events (V1.9.205): a past event, a nearly full one with registration messages, a full one with a waiting list and a
 * cancellation, an online event with guests, and one whose registration window has just closed. All free of charge.
 *
 * Written through [EventStore] like the live registration: the active-participant key comes from [EventPolicy.activeParticipantKey] (`m:<id>` for
 * a member, `g:<address>` for a guest -- the `member:` key the very first seed wrote differs and was corrected), a cancelled row has no key and a
 * cancellation time, a waitlisted row has its position, a message passes [EventRegistrationMessage.normalize]. No ticket codes, no cancel tokens,
 * no pending-payment holds (they would need open items and deadlines). Guests use addresses on the never-resolvable `staging.invalid` domain only.
 * An event is written whole or not at all; its slug is checked beforehand (never a caught unique violation).
 */
internal object StagingSeedEvents : StagingSeedPart {
    override val name: String = "events"

    data class PlannedRegistration(
        val slot: Int,
        /** Seed member number, or null for a guest. */
        val member: Int?,
        val guestIndex: Int?,
        val status: EventRegistrationStatus,
        val daysAgo: Int,
        val message: String? = null,
        val waitlistPosition: Int? = null,
        val cancelledDaysAgo: Int? = null,
    )

    data class PlannedEvent(
        val index: Int,
        val slug: String,
        val title: String,
        val description: String,
        val location: String?,
        val onlineUrl: String?,
        val startOffsetDays: Int,
        val startHour: Int,
        val durationHours: Int,
        val capacity: Int?,
        val visibility: EventVisibility,
        val registrationClosesOffsetDays: Int?,
        val createdDaysAgo: Int,
        val registrations: List<PlannedRegistration>,
    ) {
        val id: Uuid get() = SeedIds.events(index)
    }

    private fun members(
        numbers: List<Int>,
        firstSlot: Int = 1,
        firstDaysAgo: Int,
        status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
        messages: Map<Int, String> = emptyMap(),
    ): List<PlannedRegistration> =
        numbers.mapIndexed { i, n ->
            PlannedRegistration(
                slot = firstSlot + i,
                member = n,
                guestIndex = null,
                status = status,
                daysAgo = maxOf(1, firstDaysAgo - i),
                message = messages[n],
                waitlistPosition = if (status == EventRegistrationStatus.WAITLISTED) i + 1 else null,
            )
        }

    /** Append-only: the index is the id; slugs are unique. */
    val events: List<PlannedEvent> =
        listOf(
            PlannedEvent(
                index = 1,
                slug = "sommerwanderung-demodaten",
                title = "Sommerwanderung (Demodaten)",
                description = "Eine gemuetliche Wanderung rund um Musterstadt. Fiktive Veranstaltung der Demodaten, bereits vorbei.",
                location = "Treffpunkt Vereinsheim Musterstadt",
                onlineUrl = null,
                startOffsetDays = -20,
                startHour = 10,
                durationHours = 5,
                capacity = 20,
                visibility = EventVisibility.PUBLIC,
                registrationClosesOffsetDays = null,
                createdDaysAgo = 40,
                registrations =
                    members(numbers = listOf(1, 2, 4, 5, 6, 7), firstDaysAgo = 35) +
                        listOf(
                            PlannedRegistration(
                                slot = 7,
                                member = null,
                                guestIndex = 1,
                                status = EventRegistrationStatus.CONFIRMED,
                                daysAgo = 29,
                            ),
                            PlannedRegistration(
                                slot = 8,
                                member = null,
                                guestIndex = 2,
                                status = EventRegistrationStatus.CONFIRMED,
                                daysAgo = 28,
                            ),
                            PlannedRegistration(
                                slot = 9,
                                member = 9,
                                guestIndex = null,
                                status = EventRegistrationStatus.CANCELLED,
                                daysAgo = 33,
                                cancelledDaysAgo = 27,
                            ),
                        ),
            ),
            PlannedEvent(
                index = 2,
                slug = "kochkurs-demodaten",
                title = "Kochkurs (Demodaten)",
                description = "Gemeinsam kochen und essen. Fiktive Veranstaltung der Demodaten, fast ausgebucht.",
                location = "Lehrkueche Musterstadt",
                onlineUrl = null,
                startOffsetDays = 9,
                startHour = 18,
                durationHours = 3,
                capacity = 12,
                visibility = EventVisibility.PUBLIC,
                registrationClosesOffsetDays = null,
                createdDaysAgo = 15,
                registrations =
                    members(
                        numbers = listOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 19, 20),
                        firstDaysAgo = 14,
                        messages =
                            mapOf(
                                3 to "Ich bringe gerne ein Rezept mit.",
                                4 to "Darf ich jemanden mitbringen?",
                                7 to "Vegetarisch bitte, danke!",
                            ),
                    ),
            ),
            PlannedEvent(
                index = 3,
                slug = "werkstattabend-demodaten",
                title = "Werkstattabend (Demodaten)",
                description = "Offene Werkstatt mit begrenzten Plaetzen. Fiktive Veranstaltung der Demodaten, ausgebucht mit Warteliste.",
                location = "Vereinswerkstatt Musterstadt",
                onlineUrl = null,
                startOffsetDays = 16,
                startHour = 18,
                durationHours = 3,
                capacity = 6,
                visibility = EventVisibility.MEMBERS_ONLY,
                registrationClosesOffsetDays = null,
                createdDaysAgo = 12,
                registrations =
                    members(numbers = listOf(21, 23, 24, 25, 27, 28), firstDaysAgo = 11) +
                        members(
                            numbers = listOf(29, 30, 32),
                            firstSlot = 7,
                            firstDaysAgo = 5,
                            status = EventRegistrationStatus.WAITLISTED,
                        ) +
                        listOf(
                            PlannedRegistration(
                                slot = 10,
                                member = 33,
                                guestIndex = null,
                                status = EventRegistrationStatus.CANCELLED,
                                daysAgo = 8,
                                cancelledDaysAgo = 4,
                            ),
                        ),
            ),
            PlannedEvent(
                index = 4,
                slug = "online-infoabend-demodaten",
                title = "Online-Infoabend (Demodaten)",
                description = "Informationen fuer Interessierte, bequem von zuhause. Fiktive Veranstaltung der Demodaten.",
                location = null,
                onlineUrl = "https://meet.staging.invalid/demo-infoabend",
                startOffsetDays = 6,
                startHour = 19,
                durationHours = 1,
                capacity = null,
                visibility = EventVisibility.PUBLIC,
                registrationClosesOffsetDays = null,
                createdDaysAgo = 10,
                registrations =
                    members(
                        numbers = listOf(34, 35, 37, 38),
                        firstDaysAgo = 9,
                        messages =
                            mapOf(
                                35 to "Ich schalte mich etwas spaeter dazu.",
                            ),
                    ) +
                        listOf(
                            PlannedRegistration(
                                slot = 5,
                                member = null,
                                guestIndex = 3,
                                status = EventRegistrationStatus.CONFIRMED,
                                daysAgo = 4,
                                message = "Wird die Aufzeichnung geteilt?",
                            ),
                            PlannedRegistration(
                                slot = 6,
                                member = null,
                                guestIndex = 4,
                                status = EventRegistrationStatus.CONFIRMED,
                                daysAgo = 3,
                            ),
                        ),
            ),
            PlannedEvent(
                index = 5,
                slug = "planung-herbstfest-demodaten",
                title = "Planung Herbstfest (Demodaten)",
                description = "Gemeinsame Planung des Herbstfests. Fiktive Veranstaltung der Demodaten, Anmeldung bereits geschlossen.",
                location = "Vereinsheim Musterstadt",
                onlineUrl = null,
                startOffsetDays = 25,
                startHour = 16,
                durationHours = 2,
                capacity = 30,
                visibility = EventVisibility.PUBLIC,
                registrationClosesOffsetDays = -1,
                createdDaysAgo = 20,
                registrations = members(numbers = listOf(39, 40, 9, 10), firstDaysAgo = 6),
            ),
        )

    private fun guestName(): String = "Gast Beispielperson (Demodaten)"

    private fun guestEmail(index: Int): String = "gast$index@staging.invalid"

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val statuses = statusesOf(events.flatMap { e -> e.registrations.mapNotNull { it.member } } + 2)
        var inserted = 0
        var skipped = 0
        events.forEach { event ->
            val free = EventStore.getEventOrNull(event.id) == null && !EventStore.slugTaken(slug = event.slug, excludingId = null)
            if (!free || statuses[2] != MemberStatus.ACTIVE) {
                skipped++
                return@forEach
            }
            // A registration of a member who is not ACTIVE on this instance (a FRIEND, a withdrawn member) is dropped on its own;
            // the event and every other registration are still seeded. Exception: an event WITH a waiting list is skipped whole,
            // because dropping a confirmed seat would leave a free seat next to a waiting list (a state the real service never reaches).
            val registrations = event.registrations.filter { it.member == null || statuses[it.member] == MemberStatus.ACTIVE }
            val hasWaitlist = event.registrations.any { it.status == EventRegistrationStatus.WAITLISTED }
            if (hasWaitlist && registrations.size != event.registrations.size) {
                skipped++
                return@forEach
            }
            skipped += event.registrations.size - registrations.size
            val starts = clock.wallAt(daysFromToday = event.startOffsetDays, hour = event.startHour)
            EventStore.insertEvent(
                id = event.id,
                slug = event.slug,
                title = event.title,
                description = event.description,
                locationText = event.location,
                onlineUrl = event.onlineUrl,
                startsAt = starts,
                endsAt = clock.shiftWall(base = starts, hours = event.durationHours),
                capacity = event.capacity,
                feeAmount = BigDecimal("0.00"),
                feeCurrency = "EUR",
                visibility = event.visibility,
                registrationClosesAt = event.registrationClosesOffsetDays?.let { clock.wallAt(daysFromToday = it, hour = 12) },
                createdAt = clock.utcAt(daysAgo = event.createdDaysAgo, hour = 9),
                createdBy = SeedIds.member(2),
                status = EventStatus.PUBLISHED,
                onlineKind = if (event.onlineUrl != null) EventOnlineKind.EXTERNAL_LINK else EventOnlineKind.NONE,
            )
            registrations.forEach { reg ->
                val normalizedEmail = if (reg.guestIndex != null) EventPolicy.normalizeGuestEmail(guestEmail(reg.guestIndex)) else null
                val active = reg.status != EventRegistrationStatus.CANCELLED && reg.status != EventRegistrationStatus.EXPIRED
                val message =
                    (EventRegistrationMessage.normalize(reg.message) as? EventRegistrationMessage.Outcome.Ok)?.value
                val at = clock.utcAt(daysAgo = reg.daysAgo, hour = 14)
                val regId = SeedIds.events(0x2000 + event.index * 0x40 + reg.slot)
                EventStore.insertRegistration(
                    id = regId,
                    eventId = event.id,
                    memberId = reg.member?.let { SeedIds.member(it) },
                    guestName = if (reg.guestIndex != null) guestName() else null,
                    guestEmail = normalizedEmail,
                    activeParticipantKey =
                        if (active) {
                            EventPolicy.activeParticipantKey(
                                memberId = reg.member?.let { SeedIds.member(it) },
                                normalizedGuestEmail = normalizedEmail,
                            )
                        } else {
                            null
                        },
                    status = reg.status,
                    feeAmount = BigDecimal("0.00"),
                    holdExpiresAt = null,
                    waitlistPosition = reg.waitlistPosition,
                    cancelTokenSha256 = null,
                    registeredAt = at,
                    confirmedAt = if (reg.status == EventRegistrationStatus.CONFIRMED) at else null,
                    message = message,
                )
                if (reg.cancelledDaysAgo != null) {
                    EventStore.cancelRegistration(id = regId, now = clock.utcAt(daysAgo = reg.cancelledDaysAgo, hour = 9))
                }
            }
            inserted++
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
