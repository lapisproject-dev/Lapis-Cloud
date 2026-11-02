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
 * V1.9.261 -- the new `event_registration.embed_origin` column is found by the schema catalog on its own (no table list to extend), so it travels
 * in the whole-organization backup. Pinned with one registration that carries an origin and one without (NULL).
 */
class EventRegistrationEmbedOriginBackupTest :
    FunSpec({
        test("the embed origin round-trips through export and restore, NULL stays NULL") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("reg-origin-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("reg-origin-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("reg-origin-source").toFile()
            val targetRoot = Files.createTempDirectory("reg-origin-target").toFile()
            val adminId = Uuid.random()
            val eventId = Uuid.random()
            val withOrigin = Uuid.random()
            val withoutOrigin = Uuid.random()
            val at = LocalDateTime(2027, 1, 2, 9, 0)
            val origin = "https://partei.example:8443"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Anmeldung Backup"
                    it[email] = "reg-origin-backup@example.org"
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
                    it[slug] = "reg-origin-backup-$eventId"
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
                listOf(withOrigin to origin, withoutOrigin to null).forEachIndexed { index, (regId, value) ->
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
                        it[embedOrigin] = value
                    }
                }
            }

            val bundle = File.createTempFile("reg-origin-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    fun originOf(id: Uuid) =
                        EventRegistrationTable
                            .selectAll()
                            .where {
                                EventRegistrationTable.id eq id
                            }.single()[EventRegistrationTable.embedOrigin]
                    originOf(withOrigin) shouldBe origin
                    originOf(withoutOrigin) shouldBe null
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
