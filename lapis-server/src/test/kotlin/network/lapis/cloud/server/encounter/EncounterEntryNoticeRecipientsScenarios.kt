package network.lapis.cloud.server.encounter

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.FakeEncounterEntryNoticeMailer
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.76 -- the recipient query of the entry notice and the "only a NEW presence row counts" rule, on H2 AND on PostgreSQL
 * (the engine-sensitive parts: the join, the status filter, the notify_mode column and its default, the enrolment through the real
 * service). One body, two thin subclasses (`test` and `postgresTest`).
 */
abstract class EncounterEntryNoticeRecipientsScenarios(
    private val db: TestDatabase,
) : FunSpec({
        installLaneGuards(db = db)
        val fx = EncounterFixtures()

        beforeSpec { db.activate() }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun emailOf(id: Uuid): String = "encounter-$id@example.org"

        test(
            "noticeRecipients: ACTIVE PULPIT/STEWARD who are absent, lower-cased and deduplicated; present, non-ACTIVE and .invalid are left out",
        ) {
            val board = fx.createMember(role = AccountRole.BOARD)
            val space = fx.createSpace(createdBy = board, notifyMode = EncounterNotifyMode.EVERY_GUEST)
            val absentPulpit = fx.createMember()
            val absentSteward = fx.createMember()
            val presentSteward = fx.createMember()
            val withdrawn = fx.createMember()
            val anonymised = fx.createMember()
            val sameAddressOtherCase = fx.createMember()
            listOf(
                absentPulpit to EncounterSpaceRole.PULPIT,
                absentSteward to EncounterSpaceRole.STEWARD,
                presentSteward to EncounterSpaceRole.STEWARD,
                withdrawn to EncounterSpaceRole.STEWARD,
                anonymised to EncounterSpaceRole.PULPIT,
                sameAddressOtherCase to EncounterSpaceRole.STEWARD,
            ).forEach { (member, role) -> fx.setRole(spaceId = space, memberId = member, role = role) }
            fx.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
            transaction {
                MemberTable.update({ MemberTable.id eq anonymised }) { it[email] = "geloescht-$anonymised@anon.invalid" }
                // the same address as absentPulpit, upper-case: both map to one recipient
                MemberTable.update({ MemberTable.id eq sameAddressOtherCase }) { it[email] = emailOf(absentPulpit).uppercase() }
            }
            val room = fx.insertSession(spaceId = space, openedBy = board)
            fx.insertParticipation(roomId = room, memberId = presentSteward)
            val notifier = EncounterEntryNotifier(state = EncounterEntryNoticeState(), mailer = FakeEncounterEntryNoticeMailer())
            val recipients = transaction { notifier.noticeRecipients(spaceId = space, sessionRoomId = room) }
            recipients shouldContainExactly listOf(emailOf(absentPulpit), emailOf(absentSteward)).map { it.lowercase() }.sorted()
        }

        test(
            "the real entry path: only a NEW presence row of a person without an office counts -- a reconnect and an office holder do not",
        ) {
            val rig = EncounterRig()
            val board = fx.createMember(role = AccountRole.BOARD)
            val space = fx.createSpace(createdBy = board, notifyMode = EncounterNotifyMode.EVERY_GUEST)
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val guest = fx.createMember()
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            encounterApp {
                rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
                rig.asMember(client = client, member = pulpit) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
                repeat(
                    3,
                ) {
                    rig
                        .asMember(
                            client = client,
                            member = guest,
                        ) { it.enterSpace(spaceId = space.toString(), consent = null) }
                        .getOrThrow()
                }
                // pulpit's own entry was not counted; the guest's three calls are ONE entry
                network.lapis.cloud.server.time.TimeTestSupport
                    .withServerClock(instant = "2099-01-01T00:00:00Z") { rig.entryNotifier.flushDue() }
            }
            val notice = rig.entryMailer.calls.single()
            notice.entries shouldBe 1
            notice.presentCount shouldBe 2
            notice.recipients shouldContainExactly listOf(emailOf(steward).lowercase())
        }
    })

class EncounterEntryNoticeRecipientsH2Test : EncounterEntryNoticeRecipientsScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EncounterEntryNoticeRecipientsPostgresTest : EncounterEntryNoticeRecipientsScenarios(TestDatabase.Postgres())
