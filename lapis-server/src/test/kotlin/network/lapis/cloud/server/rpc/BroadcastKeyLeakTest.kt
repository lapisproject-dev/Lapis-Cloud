package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamBroadcastInput
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * V1.9.274 canary: a recognisable key goes through the whole life of a stream (start, pause, resume, a failing resume, stop, a new key, refused
 * inputs). Afterwards the COMPLETE value must be nowhere: not in a log line (message or stack), not in an exception message or its cause chain, not
 * in a failure reason, not in the audit log, not in any DTO the service returned (as JSON), not in `toString()` of the input. (The backup bundle is
 * covered by `StreamKeyBackupRedactionTest`.) The same for a persistent destination key.
 */
class BroadcastKeyLeakTest :
    FunSpec({
        val box = SecretBox(Base64.getDecoder().decode(BK_KEY_B64))
        val canaryBroadcast = "CANARYk3y0123456789zz"
        val canaryPersistent = "CANARYpersist987654321"
        val canaryReplacement = "CANARYreplace55555555"
        val canaryReconnect = "CANARYreconnect4444444"
        val memberIds = mutableListOf<Uuid>()
        val roomIds = mutableListOf<Uuid>()
        val destinationIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId inList roomIds
                        }.map { it[ConferenceStreamTable.id] }
                streams.forEach { s -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq s } }
                streams.forEach { s -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq s } }
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList destinationIds }
                roomIds.forEach { r -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq r } }
                memberIds.forEach { m -> AccountTable.deleteWhere { AccountTable.memberId eq m } }
                memberIds.forEach { m -> MemberTable.deleteWhere { MemberTable.id eq m } }
            }
        }

        test("a key typed at the start appears nowhere afterwards, and neither does a stored persistent key") {
            val owner = Uuid.random()
            val room = Uuid.random()
            val perBroadcast = Uuid.random()
            val persistent = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                MemberTable.insert {
                    it[id] = owner
                    it[displayName] = "Canary Owner"
                    it[email] = "canary-$owner@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = owner
                    it[role] = AccountRole.MEMBER
                }
                ConferenceRoomTable.insert {
                    it[id] = room
                    it[title] = "Canary-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-canary-$room"
                    it[createdByMemberId] = owner
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }

                fun destination(
                    id: Uuid,
                    kind: ConferenceStreamKeyKind,
                    key: String?,
                ) = ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "canary-$id"
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://sink.example.org:1935/live"
                    it[keyKind] = kind
                    it[streamKeyCiphertext] = key?.let { k -> box.seal(plaintext = k, aad = id.toString()) }
                    it[streamKeySetAt] = if (key != null) now else null
                    it[createdByMemberId] = owner
                    it[createdAt] = now
                    it[enabled] = true
                }
                destination(perBroadcast, ConferenceStreamKeyKind.PER_BROADCAST, null)
                destination(persistent, ConferenceStreamKeyKind.PERSISTENT, canaryPersistent)
            }
            memberIds += owner
            roomIds += room
            destinationIds += listOf(perBroadcast, persistent)

            val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            val exceptionMessages = mutableListOf<String>()
            val dtoJson = mutableListOf<String>()
            val json = Json
            val egress = BkEgress()
            val input =
                ConferenceStreamBroadcastInput(
                    destinationId = perBroadcast.toString(),
                    streamKey = canaryBroadcast,
                    viewerUrl = "https://example.org/live",
                )
            try {
                runBkHarness(egress = egress, exceptionSink = exceptionMessages) {
                    val started =
                        call(member = owner) {
                            it.startStream(
                                roomId = room.toString(),
                                destinationIds = listOf(perBroadcast.toString(), persistent.toString()),
                                layout = ConferenceStreamLayout.GRID,
                                latencyMode = ConferenceStreamLatencyMode.STANDARD,
                                participantIdentity = null,
                                broadcastInputs = listOf(input),
                            )
                        }
                    dtoJson += json.encodeToString(ConferenceStreamDto.serializer(), started)
                    dtoJson += json.encodeToString(ConferenceStreamDto.serializer(), call(member = owner) { it.pauseStream(started.id) })
                    dtoJson += json.encodeToString(ConferenceStreamDto.serializer(), call(member = owner) { it.resumeStream(started.id) })
                    // refused inputs that carry the canary
                    shouldThrow<RemoteFailure> {
                        call(member = owner) {
                            it.setBroadcastKey(
                                streamId = started.id,
                                destinationId = perBroadcast.toString(),
                                streamKey = "$canaryBroadcast with space",
                            )
                        }
                    }
                    shouldThrow<RemoteFailure> {
                        call(
                            member = owner,
                        ) { it.setBroadcastKey(streamId = started.id, destinationId = persistent.toString(), streamKey = canaryBroadcast) }
                    }
                    shouldThrow<RemoteFailure> {
                        call(member = owner) {
                            it.startStream(
                                roomId = room.toString(),
                                destinationIds = listOf(persistent.toString()),
                                layout = ConferenceStreamLayout.GRID,
                                latencyMode = ConferenceStreamLatencyMode.STANDARD,
                                participantIdentity = null,
                                broadcastInputs =
                                    listOf(
                                        ConferenceStreamBroadcastInput(
                                            destinationId = persistent.toString(),
                                            streamKey = canaryBroadcast,
                                            viewerUrl = null,
                                        ),
                                    ),
                            )
                        }
                    }
                    shouldThrow<RemoteFailure> {
                        call(member = owner) {
                            it.setViewerUrl(
                                streamId = started.id,
                                destinationId = perBroadcast.toString(),
                                viewerUrl = "javascript:alert('$canaryBroadcast')",
                            )
                        }
                    }
                    call(member = owner) {
                        it.setBroadcastKey(streamId = started.id, destinationId = perBroadcast.toString(), streamKey = canaryReplacement)
                    } shouldBe true

                    // V1.9.275: the reconnect action with a canary key -- success, a refused key, a refused UpdateStream
                    fun failTheTarget() =
                        transaction {
                            ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.destinationId eq perBroadcast }) {
                                it[status] = network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus.FAILED
                            }
                        }

                    fun reconnectInput(key: String) =
                        ConferenceStreamBroadcastInput(destinationId = perBroadcast.toString(), streamKey = key)
                    failTheTarget()
                    dtoJson +=
                        json.encodeToString(
                            ConferenceStreamDto.serializer(),
                            call(
                                member = owner,
                            ) {
                                it.reconnectStreamTarget(
                                    streamId = started.id,
                                    destinationId = perBroadcast.toString(),
                                    broadcastKey = reconnectInput(canaryReconnect),
                                )
                            },
                        )
                    failTheTarget()
                    shouldThrow<RemoteFailure> {
                        call(member = owner) {
                            it.reconnectStreamTarget(
                                streamId = started.id,
                                destinationId = perBroadcast.toString(),
                                broadcastKey = reconnectInput("$canaryReconnect with space"),
                            )
                        }
                    }
                    egress.failAdd = true
                    shouldThrow<RemoteFailure> {
                        call(
                            member = owner,
                        ) {
                            it.reconnectStreamTarget(
                                streamId = started.id,
                                destinationId = perBroadcast.toString(),
                                broadcastKey = reconnectInput(canaryReconnect),
                            )
                        }
                    }
                    egress.failAdd = false
                    // a resume that cannot find the key
                    call(member = owner) { it.pauseStream(started.id) }
                    transaction {
                        ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq Uuid.parse(started.id) }) {
                            it[broadcastKeyCiphertext] = null
                        }
                    }
                    dtoJson += json.encodeToString(ConferenceStreamDto.serializer(), call(member = owner) { it.resumeStream(started.id) })
                    dtoJson += json.encodeToString(ConferenceStreamDto.serializer(), call(member = owner) { it.stopStream(started.id) })
                    dtoJson +=
                        call(member = owner) { it.getActiveStream(room.toString()) }.joinToString { s ->
                            json.encodeToString(ConferenceStreamDto.serializer(), s)
                        }
                    dtoJson +=
                        call(member = owner) { it.listStreamTargets() }.joinToString { t ->
                            json.encodeToString(ConferenceStreamTargetDto.serializer(), t)
                        }
                    dtoJson +=
                        call(member = Uuid.parse(BK_ADMIN_ID)) { it.listDestinations() }.joinToString { d ->
                            json.encodeToString(ConferenceStreamDestinationDto.serializer(), d)
                        }
                }
            } finally {
                root.detachAppender(appender)
            }

            val logged =
                appender.list.joinToString("\n") { event ->
                    event.formattedMessage +
                        (
                            event.throwableProxy?.let {
                                "\n" + ThrowableProxy::class.simpleName +
                                    (it as? ThrowableProxy)?.throwable?.stackTraceToString()
                            }
                                ?: ""
                        )
                }
            val failureReasons =
                transaction {
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId eq room
                        }.mapNotNull { it[ConferenceStreamTable.failureReason] } +
                        ConferenceStreamTargetTable
                            .selectAll()
                            .where { ConferenceStreamTargetTable.destinationId inList listOf(perBroadcast, persistent) }
                            .mapNotNull { it[ConferenceStreamTargetTable.failureReason] }
                }
            val audit =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.actorMemberId eq owner }
                        .joinToString("\n") { "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}" }
                }
            // The capture itself works (a vacuous "nothing found" would prove nothing).
            appender.list.isNotEmpty() shouldBe true
            val canaries = listOf(canaryBroadcast, canaryPersistent, canaryReplacement, canaryReconnect)
            val surfaces =
                mapOf(
                    "log" to logged,
                    "exceptions" to exceptionMessages.joinToString("\n"),
                    "failure reasons" to failureReasons.joinToString("\n"),
                    "audit log" to audit,
                    "DTO JSON" to dtoJson.joinToString("\n"),
                    "input toString" to input.toString(),
                )
            surfaces.forEach { (name, text) ->
                canaries.forEach { canary ->
                    withClueOf(surface = name) { text.contains(canary) shouldBe false }
                    withClueOf(surface = name) { text.contains(canary.take(12)) shouldBe false }
                }
            }
            input.toString() shouldBe
                "ConferenceStreamBroadcastInput(destinationId=$perBroadcast, streamKey=<redacted>, viewerUrl=<redacted>)"
        }
    })

private inline fun withClueOf(
    surface: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue("the canary leaked into: $surface") { block() }
