package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.carpool.CarpoolDeps
import network.lapis.cloud.server.carpool.CarpoolNotice
import network.lapis.cloud.server.carpool.CarpoolNoticeMailer
import network.lapis.cloud.server.carpool.CarpoolNotifier
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.CarpoolMessageLimits
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

/**
 * V1.9.139 -- the text of a ride message exists in `carpool_message` and NOWHERE else: not in a log line, not in an audit entry, not in an
 * exception message, not in a mail notice. A sentinel text is sent through every path (success, rate limit, ended, full) and searched for.
 */
class CarpoolMessageNoContentLeakTest :
    FunSpec({
        val data = CarpoolTestData()
        val sentinel = "SENTINEL-7f3a-GEHEIM"

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { data.cleanUp() }

        test("the sentinel appears in no log line, no audit entry, no mail notice and no error message") {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val previousLevel = root.level
            root.level = Level.TRACE
            root.addAppender(appender)
            val notices = mutableListOf<CarpoolNotice>()
            val mailer =
                object : CarpoolNoticeMailer {
                    override val enabled = true

                    override fun send(
                        to: String,
                        notice: CarpoolNotice,
                    ) {
                        notices += notice
                    }
                }
            val errors = mutableListOf<String>()
            try {
                carpoolTestApplication(deps = CarpoolDeps(notifier = CarpoolNotifier(mailer))) {
                    val driver = data.member(label = "leak-driver", email = "leak-driver@example.org")
                    val person = data.member(label = "leak-person", status = MemberStatus.FRIEND, email = "leak-person@example.org")
                    val offer = data.posting(author = driver)
                    val pid =
                        data
                            .participation(
                                postingId = offer,
                                personId = person,
                                status = CarpoolParticipationStatus.CONFIRMED,
                            ).toString()
                    repeat(
                        CarpoolMessageLimits.PER_MINUTE,
                    ) { call(member = person) { sendCarpoolMessage(participationId = pid, body = "$sentinel $it") } }
                    // rate limit
                    errors +=
                        attempt(
                            member = person,
                        ) { sendCarpoolMessage(participationId = pid, body = sentinel) }.exceptionOrNull()?.message.orEmpty()
                    // too long
                    errors +=
                        attempt(
                            member = driver,
                        ) {
                            sendCarpoolMessage(
                                participationId = pid,
                                body = sentinel + "x".repeat(1100),
                            )
                        }.exceptionOrNull()?.message.orEmpty()
                    // ended
                    call(member = driver) { endCarpoolThread(pid) }
                    errors +=
                        attempt(
                            member = driver,
                        ) { sendCarpoolMessage(participationId = pid, body = sentinel) }.exceptionOrNull()?.message.orEmpty()
                    // a stranger
                    val stranger = data.member(label = "leak-stranger", status = MemberStatus.GUEST)
                    errors +=
                        attempt(
                            member = stranger,
                        ) { sendCarpoolMessage(participationId = pid, body = sentinel) }.exceptionOrNull()?.message.orEmpty()
                    errors +=
                        attempt(
                            member = stranger,
                        ) { carpoolMessages(participationId = pid, beforeId = null) }.exceptionOrNull()?.message.orEmpty()
                }
            } finally {
                root.detachAppender(appender)
                root.level = previousLevel
            }
            errors.size shouldBe 5
            errors.forEach { it shouldNotContain sentinel }
            appender.list.forEach { event ->
                event.formattedMessage shouldNotContain sentinel
                event.throwableProxy?.message.orEmpty() shouldNotContain sentinel
            }
            notices.forEach { it.toString() shouldNotContain sentinel }
            transaction {
                AuditLogEntryTable.selectAll().forEach { row ->
                    row[AuditLogEntryTable.beforeSnapshot].orEmpty() shouldNotContain sentinel
                    row[AuditLogEntryTable.afterSnapshot].orEmpty() shouldNotContain sentinel
                }
            }
        }

        test("DM boundary: the ride messages never use the direct-message system, and the DM service keeps its member-only gate") {
            val serverMain = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }
            val messages = File(serverMain, "network/lapis/cloud/server/carpool/CarpoolMessages.kt").readText()
            listOf(
                "insertDirectMessage",
                "DirectMessage",
                "direct_message",
                "logger",
                "KotlinLogging",
                "AuditLogRecorder",
                "AuditLog",
            ).forEach {
                (it in messages) shouldBe false
            }
            val service = File(serverMain, "network/lapis/cloud/server/rpc/CarpoolService.kt").readText()
            val messageSection =
                service
                    .substringAfter(
                        "override suspend fun sendCarpoolMessage(",
                    ).substringBefore("private fun baseQuery()")
            listOf("insertDirectMessage", "DirectMessage", "AuditLogRecorder", "logger").forEach { (it in messageSection) shouldBe false }
        }
    })
