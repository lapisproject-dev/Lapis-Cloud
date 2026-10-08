package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.EncounterEntryNotice
import network.lapis.cloud.server.mail.EncounterEntryNoticeMailer
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.76 -- the anonymous entry notice for office holders, end to end through [network.lapis.cloud.server.rpc.EncounterSpaceService]
 * on H2 with a fake LiveKit and a recording fake mailer. The state is pinned in [EncounterEntryNoticeStateTest], the wording in
 * [network.lapis.cloud.server.mail.MailTemplatesTest], the anonymity in [EncounterEntryNoticeAnonymityTest].
 */
class EncounterEntryNoticeServiceTest :
    FunSpec({
        val fx = EncounterFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }

        class World(
            val rig: EncounterRig,
            val board: Uuid,
            val pulpit: Uuid,
            val steward: Uuid,
            val space: Uuid,
        ) {
            fun emailOf(member: Uuid): String =
                transaction { MemberTable.selectAll().where { MemberTable.id eq member }.single()[MemberTable.email] }
        }

        fun world(mode: EncounterNotifyMode): World {
            val board = fx.createMember(role = AccountRole.BOARD)
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val space = fx.createSpace(createdBy = board, title = "Sonntagsgottesdienst", notifyMode = mode)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            return World(rig = EncounterRig(), board = board, pulpit = pulpit, steward = steward, space = space)
        }

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.open(w: World) {
            w.rig.asMember(client = client, member = w.steward) { it.openSpace(w.space.toString()) }.getOrThrow()
        }

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.enter(
            w: World,
            member: Uuid,
        ) = w.rig.asMember(client = client, member = member) { it.enterSpace(spaceId = w.space.toString(), consent = null) }

        test("NONE: a guest enters, no notice, and nothing is remembered") {
            val w = world(EncounterNotifyMode.NONE)
            val guest = fx.createMember()
            encounterApp {
                open(w)
                enter(w, guest).getOrThrow()
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
                w.rig.entryState.trackedSessions() shouldBe 0
            }
        }

        test(
            "FIRST_GUEST: one notice with title, minute, number present and the absent office holders; none for the second guest; again after a reopening",
        ) {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val g1 = fx.createMember()
            val g2 = fx.createMember()
            encounterApp {
                open(w)
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:03:40Z") { enter(w, g1).getOrThrow() }
                val notice =
                    w.rig.entryMailer.calls
                        .single()
                notice.kind shouldBe EncounterEntryNotice.Kind.FIRST_GUEST
                notice.spaceTitle shouldBe "Sonntagsgottesdienst"
                notice.entries shouldBe 1
                notice.presentCount shouldBe 1
                notice.windowEnd shouldBe null
                notice.recipients shouldContainExactly listOf(w.emailOf(w.pulpit), w.emailOf(w.steward)).map { it.lowercase() }.sorted()
                val expected = Instant.parse("2026-10-08T10:03:40Z").toLocalDateTime(OrganizationTimeZone.current())
                notice.at.hour shouldBe expected.hour
                notice.at.minute shouldBe expected.minute

                enter(w, g2).getOrThrow()
                w.rig.entryMailer.calls shouldHaveSize 1

                // close and reopen: a NEW session, the first guest of it is announced again
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.space.toString()) }.getOrThrow()
                open(w)
                enter(w, g1).getOrThrow()
                w.rig.entryMailer.calls shouldHaveSize 2
            }
        }

        test("EVERY_GUEST: entries of one slot are summarised into one mail after the slot, once, with the window bounds") {
            val w = world(EncounterNotifyMode.EVERY_GUEST)
            val guests = List(3) { fx.createMember() }
            encounterApp {
                open(w)
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:01:00Z") { guests.forEach { enter(w, it).getOrThrow() } }
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:04:59Z") { w.rig.entryNotifier.flushDue() }
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:05:01Z") { w.rig.entryNotifier.flushDue() }
                val notice =
                    w.rig.entryMailer.calls
                        .single()
                notice.kind shouldBe EncounterEntryNotice.Kind.WINDOW
                notice.entries shouldBe 3
                notice.presentCount shouldBe 3
                val zone = OrganizationTimeZone.current()
                notice.at shouldBe Instant.parse("2026-10-08T10:00:00Z").toLocalDateTime(zone)
                notice.windowEnd shouldBe Instant.parse("2026-10-08T10:05:00Z").toLocalDateTime(zone)
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:20:00Z") { w.rig.entryNotifier.flushDue() }
                w.rig.entryMailer.calls shouldHaveSize 1
            }
        }

        test("EVERY_GUEST: the next entry after the window flushes the due window opportunistically") {
            val w = world(EncounterNotifyMode.EVERY_GUEST)
            val g1 = fx.createMember()
            val g2 = fx.createMember()
            encounterApp {
                open(w)
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:01:00Z") { enter(w, g1).getOrThrow() }
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:06:00Z") { enter(w, g2).getOrThrow() }
                w.rig.entryMailer.calls
                    .single()
                    .entries shouldBe 1
            }
        }

        test("a reconnect (existing presence row) never counts, in either mode") {
            val w = world(EncounterNotifyMode.EVERY_GUEST)
            val guest = fx.createMember()
            encounterApp {
                open(w)
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:01:00Z") { repeat(3) { enter(w, guest).getOrThrow() } }
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:05:01Z") { w.rig.entryNotifier.flushDue() }
                w.rig.entryMailer.calls
                    .single()
                    .entries shouldBe 1
            }
        }

        test("PULPIT, STEWARD and BOARD/ADMIN without an office never trigger a notice -- and never consume the first-guest claim") {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val admin = fx.createMember(role = AccountRole.ADMIN)
            val guest = fx.createMember()
            encounterApp {
                open(w)
                enter(w, w.pulpit).getOrThrow()
                enter(w, w.steward).getOrThrow()
                enter(w, w.board).getOrThrow()
                enter(w, admin).getOrThrow()
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
                w.rig.entryState.trackedSessions() shouldBe 0
                enter(w, guest).getOrThrow()
                // pulpit and steward are present: the mail has no recipient left (the claim is consumed, see the architecture note)
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
            }
        }

        test("an office holder who is present is not a recipient; the absent one is") {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val guest = fx.createMember()
            encounterApp {
                open(w)
                enter(w, w.pulpit).getOrThrow()
                enter(w, guest).getOrThrow()
                val notice =
                    w.rig.entryMailer.calls
                        .single()
                notice.recipients shouldContainExactly listOf(w.emailOf(w.steward).lowercase())
                notice.presentCount shouldBe 2
            }
        }

        test("office holders with an anonymised (.invalid) address or without the status ACTIVE are not recipients") {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val guest = fx.createMember()
            transaction { MemberTable.update({ MemberTable.id eq w.pulpit }) { it[email] = "anon-${w.pulpit}@geloescht.invalid" } }
            encounterApp {
                open(w)
                // the steward leaves the organisation AFTER opening the room (a non-ACTIVE person cannot open it)
                fx.setStatus(memberId = w.steward, status = MemberStatus.WITHDRAWN)
                enter(w, guest).getOrThrow()
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
            }
        }

        test("the notify mode can be changed while the room is open; a real change resets the marker, an unchanged mode does not") {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val g1 = fx.createMember()
            val g2 = fx.createMember()
            val g3 = fx.createMember()
            encounterApp {
                open(w)
                enter(w, g1).getOrThrow()
                w.rig.entryMailer.calls shouldHaveSize 1

                fun input(mode: EncounterNotifyMode?) = EncounterSpaceInput(title = "Sonntagsgottesdienst", notifyMode = mode)
                // the same mode again, and "unchanged" (null): the marker stays
                w.rig
                    .asMember(
                        client = client,
                        member = w.board,
                    ) { it.updateSpace(spaceId = w.space.toString(), input = input(EncounterNotifyMode.FIRST_GUEST)) }
                    .getOrThrow()
                w.rig
                    .asMember(
                        client = client,
                        member = w.board,
                    ) { it.updateSpace(spaceId = w.space.toString(), input = input(null)) }
                    .getOrThrow()
                    .notifyMode shouldBe
                    EncounterNotifyMode.FIRST_GUEST
                enter(w, g2).getOrThrow()
                w.rig.entryMailer.calls shouldHaveSize 1

                // a real change (and back): the marker is reset, the next guest is announced again
                w.rig
                    .asMember(
                        client = client,
                        member = w.board,
                    ) { it.updateSpace(spaceId = w.space.toString(), input = input(EncounterNotifyMode.EVERY_GUEST)) }
                    .getOrThrow()
                    .notifyMode shouldBe EncounterNotifyMode.EVERY_GUEST
                w.rig
                    .asMember(
                        client = client,
                        member = w.board,
                    ) { it.updateSpace(spaceId = w.space.toString(), input = input(EncounterNotifyMode.FIRST_GUEST)) }
                    .getOrThrow()
                enter(w, g3).getOrThrow()
                w.rig.entryMailer.calls shouldHaveSize 2
            }
        }

        test("the end of the session discards a running window without a mail") {
            val w = world(EncounterNotifyMode.EVERY_GUEST)
            val guest = fx.createMember()
            encounterApp {
                open(w)
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:01:00Z") { enter(w, guest).getOrThrow() }
                w.rig.entryState.trackedSessions() shouldBe 1
                w.rig.asMember(client = client, member = w.steward) { it.closeSpace(w.space.toString()) }.getOrThrow()
                w.rig.entryState.trackedSessions() shouldBe 0
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:30:00Z") { w.rig.entryNotifier.flushDue() }
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
            }
        }

        test("a window whose session was ended behind the notifier's back is dropped by the re-check, not mailed") {
            val w = world(EncounterNotifyMode.EVERY_GUEST)
            val guest = fx.createMember()
            encounterApp {
                open(w)
                val room = fx.openSessionRoom(w.space)!!
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:01:00Z") { enter(w, guest).getOrThrow() }
                transaction {
                    network.lapis.cloud.server.db.generated.ConferenceRoomTable.update({
                        network.lapis.cloud.server.db.generated.ConferenceRoomTable.id eq room
                    }) {
                        it[endedAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                TimeTestSupport.withServerClock(instant = "2026-10-08T10:30:00Z") { w.rig.entryNotifier.flushDue() }
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
            }
        }

        test("mailer disabled: the entry works, nothing is remembered and nothing is read") {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val guest = fx.createMember()
            w.rig.entryMailer.enabled = false
            encounterApp {
                open(w)
                val entry = enter(w, guest).getOrThrow()
                entry.join.token.isNotBlank() shouldBe true
                fx.participationCount(fx.openSessionRoom(w.space)!!) shouldBe 1L
                w.rig.entryMailer.calls
                    .shouldBeEmpty()
                w.rig.entryState.trackedSessions() shouldBe 0
            }
        }

        test("a failing mailer never blocks the entry") {
            val w = world(EncounterNotifyMode.FIRST_GUEST)
            val guest = fx.createMember()
            w.rig.entryMailer.throwOnSend = true
            encounterApp {
                open(w)
                enter(w, guest)
                    .getOrThrow()
                    .join.token
                    .isNotBlank() shouldBe true
                fx.participationCount(fx.openSessionRoom(w.space)!!) shouldBe 1L
                w.rig.entryMailer.calls shouldHaveSize 1
            }
        }

        test("an internal failure of the notifier (the mailer's own state check throws) never blocks the entry") {
            val w = world(EncounterNotifyMode.EVERY_GUEST)
            val guest = fx.createMember()
            val broken =
                object : EncounterEntryNoticeMailer {
                    override val enabled: Boolean get() = error("simulated failure")

                    override fun send(notice: EncounterEntryNotice) = Unit
                }
            val notifier = EncounterEntryNotifier(state = EncounterEntryNoticeState(), mailer = broken)
            notifier.onGuestEntered(spaceId = w.space, sessionRoomId = Uuid.random(), mode = EncounterNotifyMode.EVERY_GUEST)
            notifier.flushDue()
            encounterApp {
                open(w)
                enter(w, guest)
                    .getOrThrow()
                    .join.token
                    .isNotBlank() shouldBe true
            }
        }

        test("audit: changing the mode is audited with before/after, an entry that sends a mail adds no audit entry") {
            val w = world(EncounterNotifyMode.NONE)
            val guest = fx.createMember()
            encounterApp {
                w.rig
                    .asMember(client = client, member = w.board) {
                        it.updateSpace(
                            spaceId = w.space.toString(),
                            input = EncounterSpaceInput(title = "Sonntagsgottesdienst", notifyMode = EncounterNotifyMode.FIRST_GUEST),
                        )
                    }.getOrThrow()
                val audit = auditEntriesOf(w.space)
                audit.last().first shouldBe "UPDATE"
                audit.last().second!! shouldContain "\"notifyMode\":\"FIRST_GUEST\""
                open(w)
                val before = fx.auditCount()
                enter(w, guest).getOrThrow()
                w.rig.entryMailer.calls shouldHaveSize 1
                fx.auditCount() shouldBe before
                transaction {
                    EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq w.space }.single()[EncounterSpaceTable.notifyMode]
                } shouldBe "FIRST_GUEST"
            }
        }
    })
