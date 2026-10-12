package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * V1.9.111 -- what depends on the database ENGINE in the dynamic mailing lists, on H2 and on PostgreSQL: the message row lock that makes the
 * recipient resolution happen exactly once even if two workers race, the unique opt-out whose violation must not poison the transaction
 * (savepoint), and the recovery exception for unresolved messages. (The HTTP-level paths need the trusted test header, which exists on H2 only.)
 */
abstract class DynamicMailingListScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = DynamicFixture()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            fx.cleanup()
            db.deactivate()
        }

        fun idleWorker() =
            MailingDeliveryWorker(
                transport = NoOpMailTransport(),
                branding = MailBranding.notConfigured(),
                mode = MailingDeliveryMode.LOG,
                trackingToken = testTrackingToken(),
                baseUrl = TEST_TRACKING_BASE_URL,
                scope = CoroutineScope(Job().apply { cancel() }),
                sendDelay = 0.milliseconds,
            )

        test("two workers racing to resolve one message: the rows exist once and ONE audit entry is written") {
            val c = fx.committee()
            val sender = fx.member()
            val recipients = List(5) { fx.member(email = "race-$it-${Uuid.random()}@example.org") }
            recipients.forEach { fx.seat(committeeId = c, memberId = it) }
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    List(2) {
                        pool.submit<Boolean> {
                            barrier.await(10, TimeUnit.SECONDS)
                            idleWorker().resolveRecipientsIfNeeded(message)
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
                results shouldBe listOf(true, true)
            } finally {
                pool.shutdownNow()
            }
            fx.deliveries(message).keys shouldBe recipients.toSet()
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq message) and (AuditLogEntryTable.entityType eq AuditEntityType.MAILING_MESSAGE)
                    }.count()
            } shouldBe 1L
        }

        test(
            "a duplicate opt-out inside a savepoint is a unique violation that leaves the transaction usable; deleting the list takes the opt-outs along",
        ) {
            val c = fx.committee()
            val sender = fx.member()
            val member = fx.member()
            val list = fx.dynamicList(committeeId = c, createdBy = sender)

            fun insertOptOut() {
                MailingListOptOutTable.insert {
                    it[id] = Uuid.random()
                    it[mailingListId] = list
                    it[memberId] = member
                    it[optedOutAt] = DbClock.nowLocalDateTime()
                }
            }
            transaction {
                withSavepoint(name = "first") { insertOptOut() }
                var violation = false
                try {
                    withSavepoint(name = "second") { insertOptOut() }
                } catch (e: ExposedSQLException) {
                    violation = e.isUniqueViolation()
                }
                violation shouldBe true
                // the transaction is still healthy: a later statement works and the first row is there
                MailingListOptOutTable.selectAll().where { MailingListOptOutTable.mailingListId eq list }.count() shouldBe 1L
            }
            transaction {
                // detach the dynamic list's dependants the way the application would, then check the cascade of the opt-out itself
                network.lapis.cloud.server.db.generated.MailingListTable
                    .deleteWhere { network.lapis.cloud.server.db.generated.MailingListTable.id eq list }
            }
            transaction { MailingListOptOutTable.selectAll().where { MailingListOptOutTable.mailingListId eq list }.count() } shouldBe 0L
        }

        test("recoverInterrupted keeps an unresolved dynamic message QUEUED while an empty manual one is closed") {
            val c = fx.committee()
            val sender = fx.member()
            val dynamicList = fx.dynamicList(committeeId = c, createdBy = sender)
            val manualList = fx.list(createdBy = sender)
            val dynamicMessage = fx.message(listId = dynamicList, sentBy = sender)
            val manualMessage = fx.message(listId = manualList, sentBy = sender)

            runBlocking { idleWorker().recoverInterrupted() }

            fx.messageRow(dynamicMessage)[network.lapis.cloud.server.db.generated.MailingMessageTable.status] shouldBe
                MailingMessageStatus.QUEUED
            fx.messageRow(manualMessage)[network.lapis.cloud.server.db.generated.MailingMessageTable.status] shouldBe
                MailingMessageStatus.FAILED
        }
    })

class DynamicMailingListTest : DynamicMailingListScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class DynamicMailingListPostgresTest : DynamicMailingListScenarios(TestDatabase.Postgres())
