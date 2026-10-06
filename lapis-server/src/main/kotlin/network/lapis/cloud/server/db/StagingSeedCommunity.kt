package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.articles.ArticlePolicy
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.series.EventSeriesLimits
import network.lapis.cloud.server.events.series.EventSeriesMaterializer
import network.lapis.cloud.server.events.series.RecurrenceRuleBuilder
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.RecurrenceFrequency
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.RecurrenceWeekday
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Staging seed, area "community" (Welle V1.9.63): articles in three states, the folder structure of the document store, events
 * (a finished one, a public upcoming one, the members' evening) plus a recurring series, and a few carpool postings.
 *
 * **Folders only, no documents.** A `document_version` needs a stored file; writing one is an external effect that must not run
 * inside the seed's single transaction. So the folder tree exists and is empty ("What it deliberately does not contain").
 *
 * **Carpool postings and the retention poller.** The poller deletes postings whose departure date has passed, so every
 * departure here lies in the future. The notes carry no personal data and the places are fictitious.
 *
 * Articles are inserted in the state the review workflow would leave them in (no audit entry: the live service writes none
 * when a draft is created, and the later transitions are not replayed).
 */
internal object StagingSeedCommunity {
    private val stagingEventId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000030")

    fun JdbcTransaction.seedCommunity(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        seedArticles(clock = clock, actors = actors)
        seedFolders()
        seedEvents(clock = clock, actors = actors)
        seedCarpool(clock = clock, actors = actors)
    }

    // ---- articles ---------------------------------------------------------------------------------------------------------

    private class ArticleSeed(
        val title: String,
        val excerpt: String,
        val body: String,
        val status: ArticleStatus,
        val authorIndex: Int,
        val publishedDaysAgo: Int? = null,
    )

    private fun JdbcTransaction.seedArticles(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val authors = actors.active.filter { it.accountRole != null }.ifEmpty { actors.active }
        listOf(
            ArticleSeed(
                title = "Rueckblick auf die Mitgliederversammlung",
                excerpt = "Die wichtigsten Beschluesse der Ordentlichen Mitgliederversammlung im Ueberblick.",
                body = "Die Mitgliederversammlung hat die neue Beitragsordnung beraten und beschlossen. (Demodaten, frei erfunden.)",
                status = ArticleStatus.PUBLISHED,
                authorIndex = 0,
                publishedDaysAgo = 60,
            ),
            ArticleSeed(
                title = "Stammtisch ab sofort woechentlich",
                excerpt = "Jeden Dienstagabend treffen sich Mitglieder und Interessierte im Vereinsheim.",
                body = "Der Stammtisch findet ab sofort jede Woche statt. Gaeste sind herzlich willkommen. (Demodaten, frei erfunden.)",
                status = ArticleStatus.PUBLISHED,
                authorIndex = 1,
                publishedDaysAgo = 20,
            ),
            ArticleSeed(
                title = "So funktioniert die Online-Wahl",
                excerpt = "Eine kurze Anleitung zur geheimen Online-Wahl der Kassenpruefung.",
                body = "Die Wahl ist geheim, jedes Mitglied hat genau eine Stimme. (Demodaten, frei erfunden.)",
                status = ArticleStatus.PUBLISHED,
                authorIndex = 2,
                publishedDaysAgo = 5,
            ),
            ArticleSeed(
                title = "Neues aus der AG Oeffentlichkeitsarbeit",
                excerpt = "Die Arbeitsgruppe plant eine neue Webseite und einen Newsletter.",
                body = "Die Arbeitsgruppe hat erste Entwuerfe vorgestellt. Rueckmeldungen sind willkommen. (Demodaten, frei erfunden.)",
                status = ArticleStatus.SUBMITTED,
                authorIndex = 0,
            ),
            ArticleSeed(
                title = "Ideen fuer das Sommerfest",
                excerpt = "Erste Gedanken zu Programm und Ablauf eines moeglichen Sommerfests.",
                body = "Dieser Entwurf ist noch nicht fertig. (Demodaten, frei erfunden.)",
                status = ArticleStatus.DRAFT,
                authorIndex = 1,
            ),
        ).forEach { seed ->
            val author = authors[seed.authorIndex % authors.size]
            val createdAt = clock.utcAt(daysAgo = (seed.publishedDaysAgo ?: 3) + 3)
            val articleId = Uuid.random()
            val slug =
                if (seed.status == ArticleStatus.PUBLISHED) {
                    ArticlePolicy.slugFor(
                        title = seed.title,
                    ) { candidate -> ArticleStore.slugTaken(slug = candidate, excludingId = articleId) }
                } else {
                    null
                }
            ArticleTable.insert {
                it[id] = articleId
                it[ArticleTable.slug] = slug
                it[title] = seed.title
                it[excerpt] = seed.excerpt
                it[body] = seed.body
                it[coverImageId] = null
                it[authorId] = author.id
                it[status] = seed.status
                it[ArticleTable.createdAt] = createdAt
                it[updatedAt] = clock.utcAt(daysAgo = seed.publishedDaysAgo ?: 1)
                if (seed.status != ArticleStatus.DRAFT) it[submittedAt] = clock.utcAt(daysAgo = (seed.publishedDaysAgo ?: 1) + 1)
                if (seed.status == ArticleStatus.PUBLISHED) {
                    it[reviewedBy] = actors.board.id
                    it[reviewedAt] = clock.utcAt(daysAgo = seed.publishedDaysAgo ?: 1)
                    it[publishedAt] = clock.utcAt(daysAgo = seed.publishedDaysAgo ?: 1)
                }
            }
        }
    }

    // ---- documents --------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.seedFolders() {
        val statutes = SeedIds.community(0x20)
        val minutes = SeedIds.community(0x21)
        val boardMinutes = SeedIds.community(0x22)
        val templates = SeedIds.community(0x23)
        insertFolder(id = statutes, name = "Satzung und Ordnungen", parent = null, access = DocumentAccessLevel.PUBLIC_MEMBERS)
        insertFolder(id = minutes, name = "Protokolle", parent = null, access = DocumentAccessLevel.PUBLIC_MEMBERS)
        insertFolder(id = boardMinutes, name = "Vorstand", parent = minutes, access = DocumentAccessLevel.BOARD_ONLY)
        insertFolder(id = templates, name = "Vorlagen", parent = null, access = DocumentAccessLevel.PUBLIC_MEMBERS)
    }

    private fun JdbcTransaction.insertFolder(
        id: Uuid,
        name: String,
        parent: Uuid?,
        access: DocumentAccessLevel,
    ) {
        DocumentFolderTable.insert {
            it[DocumentFolderTable.id] = id
            it[DocumentFolderTable.name] = name
            it[parentFolderId] = parent
            it[accessLevel] = access
        }
    }

    // ---- events -----------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.seedEvents(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val now = clock.now
        // The original members' evening, upcoming, members only.
        val eveningStart = clock.wallAt(daysFromToday = 21, hour = 19)
        insertEvent(
            id = stagingEventId,
            slug = "testverein-mitgliederabend",
            title = "Mitgliederabend",
            description = "Geselliger Abend fuer alle Mitglieder des Testvereins.",
            location = "Vereinsheim Musterstadt",
            startsAt = eveningStart,
            endsAt = clock.shiftWall(base = eveningStart, hours = 3),
            capacity = 50,
            visibility = EventVisibility.MEMBERS_ONLY,
            createdAt = now,
            createdBy = actors.admin.id,
        )
        actors.active.take(5).forEach { member -> register(eventId = stagingEventId, memberId = member.id, now = now) }

        // A finished event with a real attendance list.
        val pastStart = clock.wallAt(daysFromToday = -30, hour = 17)
        val pastId = SeedIds.community(0x30)
        insertEvent(
            id = pastId,
            slug = "fruehjahrsputz-im-vereinsheim",
            title = "Fruehjahrsputz im Vereinsheim",
            description = "Gemeinsames Aufraeumen und anschliessend Kaffee und Kuchen (Demodaten).",
            location = "Vereinsheim Musterstadt",
            startsAt = pastStart,
            endsAt = clock.shiftWall(base = pastStart, hours = 4),
            capacity = 25,
            visibility = EventVisibility.MEMBERS_ONLY,
            createdAt = clock.utcAt(daysAgo = 50),
            createdBy = actors.admin.id,
        )
        actors.active.take(12).forEach { member ->
            register(eventId = pastId, memberId = member.id, now = clock.utcAt(daysAgo = 40))
        }

        // A public upcoming event.
        val publicStart = clock.wallAt(daysFromToday = 35, hour = 15)
        insertEvent(
            id = SeedIds.community(0x31),
            slug = "tag-der-offenen-tuer",
            title = "Tag der offenen Tuer",
            description = "Der Testverein stellt sich vor. Eintritt frei, alle Interessierten sind eingeladen (Demodaten).",
            location = "Vereinsheim Musterstadt",
            startsAt = publicStart,
            endsAt = clock.shiftWall(base = publicStart, hours = 5),
            capacity = null,
            visibility = EventVisibility.PUBLIC,
            createdAt = now,
            createdBy = actors.board.id,
        )

        seedStammtischSeries(clock = clock, actors = actors)
    }

    private fun JdbcTransaction.insertEvent(
        id: Uuid,
        slug: String,
        title: String,
        description: String,
        location: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        capacity: Int?,
        visibility: EventVisibility,
        createdAt: LocalDateTime,
        createdBy: Uuid,
    ) {
        EventStore.insertEvent(
            id = id,
            slug = slug,
            title = title,
            description = description,
            locationText = location,
            onlineUrl = null,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = capacity,
            feeAmount = BigDecimal("0.00"),
            feeCurrency = "EUR",
            visibility = visibility,
            registrationClosesAt = null,
            createdAt = createdAt,
            createdBy = createdBy,
            status = EventStatus.PUBLISHED,
        )
    }

    private fun JdbcTransaction.register(
        eventId: Uuid,
        memberId: Uuid,
        now: LocalDateTime,
    ) {
        EventRegistrationTable.insert {
            it[id] = Uuid.random()
            it[EventRegistrationTable.eventId] = eventId
            it[EventRegistrationTable.memberId] = memberId
            it[activeParticipantKey] = "member:$memberId"
            it[status] = EventRegistrationStatus.CONFIRMED
            it[feeAmount] = BigDecimal("0.00")
            it[registeredAt] = now
            it[confirmedAt] = now
        }
    }

    /** "Stammtisch": weekly, eight dates, starting next week at 19:00 -- through the live series code (rule builder + materializer). */
    private fun JdbcTransaction.seedStammtischSeries(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val seriesZone = ZoneId.of(EventSeriesLimits.SUPPORTED_TIMEZONES.first())
        val dtstart = clock.wallAt(daysFromToday = 7, hour = 19)
        val duration = 120
        val rule =
            RecurrenceRuleInput(
                frequency = RecurrenceFrequency.WEEKLY,
                byWeekdays = setOf(RecurrenceWeekday.entries[dtstart.dayOfWeek.ordinal]),
                count = 8,
            )
        val rrule =
            when (val built = RecurrenceRuleBuilder.build(rule = rule, dtstart = dtstart, zone = seriesZone)) {
                is RecurrenceRuleBuilder.Result.Invalid -> error("staging seed: invalid series rule: ${built.messages}")
                is RecurrenceRuleBuilder.Result.Ok -> built.rrule
            }
        val seriesId = SeedIds.community(0x40)
        EventStore.insertSeries(
            id = seriesId,
            rrule = rrule,
            dtstart = dtstart,
            timezone = seriesZone.id,
            durationMinutes = duration,
            splitFromSeriesId = null,
            createdBy = actors.admin.id,
            createdAt = clock.now,
        )
        EventSeriesMaterializer.materialize(
            seriesId = seriesId,
            rrule = rrule,
            dtstart = dtstart,
            zone = seriesZone,
            durationMinutes = duration,
            template =
                EventInput(
                    title = "Stammtisch",
                    description = "Woechentlicher Stammtisch fuer Mitglieder und Interessierte (Demodaten).",
                    locationText = "Vereinsheim Musterstadt",
                    startsAt = dtstart,
                    endsAt = clock.shiftWall(base = dtstart, hours = 2),
                    capacity = null,
                    feeAmount = BigDecimal("0.00"),
                    visibility = EventVisibility.PUBLIC,
                ),
            createdBy = actors.admin.id,
            now = clock.now,
        )
    }

    // ---- carpool ----------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.seedCarpool(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val authors = actors.active.filter { it.accountRole == null }

        data class Posting(
            val type: CarpoolPostingType,
            val from: String,
            val to: String,
            val daysAhead: Int,
            val hour: Int,
            val seats: Int?,
            val notes: String,
        )
        listOf(
            Posting(
                CarpoolPostingType.OFFER,
                "Beispielhausen",
                "Musterstadt",
                21,
                17,
                3,
                "Fahrt zum Mitgliederabend, Treffpunkt am Bahnhof.",
            ),
            Posting(CarpoolPostingType.OFFER, "Phantasiedorf", "Musterstadt", 21, 18, 2, "Zwei Plaetze frei, Rueckfahrt nach Absprache."),
            Posting(
                CarpoolPostingType.REQUEST,
                "Nirgendwo-Nord",
                "Musterstadt",
                35,
                14,
                null,
                "Suche eine Mitfahrgelegenheit zum Tag der offenen Tuer.",
            ),
        ).forEachIndexed { index, posting ->
            CarpoolPostingTable.insert {
                it[id] = Uuid.random()
                it[authorMemberId] = authors[index % authors.size].id
                it[type] = posting.type
                it[fromPlace] = posting.from
                it[toPlace] = posting.to
                it[departureDate] = clock.dayAgo(-posting.daysAhead)
                it[departureTime] = LocalTime.of(posting.hour, 0)
                it[seatsOffered] = posting.seats
                it[notes] = posting.notes
                it[createdAt] = clock.utcAt(daysAgo = 2)
                it[updatedAt] = clock.utcAt(daysAgo = 2)
            }
        }
    }
}
