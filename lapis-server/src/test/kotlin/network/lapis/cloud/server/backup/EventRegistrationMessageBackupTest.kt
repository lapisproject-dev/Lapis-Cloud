package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * V1.9.204 -- the new `event_registration.message` column is found by the schema catalog on its own (no table list to extend), so it travels in the
 * whole-organization backup. Pinned with a text that has a line break, umlauts and a quote, next to a registration without a message (NULL).
 */
class EventRegistrationMessageBackupTest :
    FunSpec({
        test("the registrant message round-trips through export and restore, NULL stays NULL") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("reg-message-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("reg-message-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("reg-message-source").toFile()
            val targetRoot = Files.createTempDirectory("reg-message-target").toFile()
            val adminId = Uuid.random()
            val eventId = Uuid.random()
            val withMessage = Uuid.random()
            val withoutMessage = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)
            val text = "Größe 42:\nBitte \"vegetarisch\" – danke!"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Anmeldung Backup"
                    it[email] = "reg-message-backup@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it[role] = AccountRole.ADMIN
                }
                EventTable.insert {
                    it[id] = eventId
                    it[slug] = "reg-message-backup-$eventId"
                    it[title] = "Backup-Fest"
                    it[description] = "x"
                    it[locationText] = "Saal"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 20, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = at
                    it[createdBy] = adminId
                    it[cancelledAt] = null
                }
                listOf(withMessage to text, withoutMessage to null).forEachIndexed { index, (regId, msg) ->
                    EventRegistrationTable.insert {
                        it[id] = regId
                        it[EventRegistrationTable.eventId] = eventId
                        it[memberId] = null
                        it[guestName] = "Gast $index"
                        it[guestEmail] = "gast-$index@example.org"
                        it[activeParticipantKey] = "g:gast-$index@example.org"
                        it[status] = EventRegistrationStatus.CONFIRMED
                        it[feeAmount] = BigDecimal.ZERO
                        it[holdExpiresAt] = null
                        it[waitlistPosition] = null
                        it[cancelTokenSha256] = null
                        it[registeredAt] = at
                        it[confirmedAt] = at
                        it[message] = msg
                    }
                }
            }

            val bundle = File.createTempFile("reg-message-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    fun messageOf(id: Uuid) =
                        EventRegistrationTable
                            .selectAll()
                            .where {
                                EventRegistrationTable.id eq id
                            }.single()[EventRegistrationTable.message]
                    messageOf(withMessage) shouldBe text
                    messageOf(withoutMessage) shouldBe null
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
