package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * V1.9.274 -- stream key material never leaves the instance in a backup: the ciphertexts of a persistent destination key and of a broadcast key
 * are exported as NULL, the fingerprint (up to 3+3 plaintext characters of the key) as `{redacted}`. The column set is unchanged, so the bundle
 * restores; the restored destination holds no key (the start then refuses it, see `ConferenceStreamBroadcastKeyTest`), the viewer address and the
 * key kind survive.
 */
class StreamKeyBackupRedactionTest :
    FunSpec({
        test("a bundle holds none of the key material, and it restores with the keys empty") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("stream-key-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("stream-key-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("stream-key-source").toFile()
            val targetRoot = Files.createTempDirectory("stream-key-target").toFile()
            val adminId = Uuid.random()
            val roomId = Uuid.random()
            val persistentId = Uuid.random()
            val perBroadcastId = Uuid.random()
            val streamId = Uuid.random()
            val persistentTargetId = Uuid.random()
            val perBroadcastTargetId = Uuid.random()
            val at = LocalDateTime(2026, 11, 4, 9, 0)
            val canaryDestinationCiphertext = "v1:CANARYDESTIV:CANARYDESTINATIONCIPHERTEXT"
            val canaryBroadcastCiphertext = "v1:CANARYBROADIV:CANARYBROADCASTCIPHERTEXT"
            val canaryFingerprintPlain = "{CNR...987}"

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Stream Backup"
                    it[email] = "stream-key-backup@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it[role] = AccountRole.ADMIN
                }
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Backup-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-backup-$roomId"
                    it[createdByMemberId] = adminId
                    it[createdAt] = at
                    it[endedAt] = at
                    it[maxParticipants] = 25
                }

                fun destination(
                    id: Uuid,
                    kind: ConferenceStreamKeyKind,
                    ciphertext: String?,
                ) = ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "backup-$id"
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmps://ingest.example.org/live"
                    it[keyKind] = kind
                    it[streamKeyCiphertext] = ciphertext
                    it[streamKeySetAt] = if (ciphertext != null) at else null
                    it[createdByMemberId] = adminId
                    it[createdAt] = at
                    it[enabled] = true
                }
                destination(persistentId, ConferenceStreamKeyKind.PERSISTENT, canaryDestinationCiphertext)
                destination(perBroadcastId, ConferenceStreamKeyKind.PER_BROADCAST, null)
                ConferenceStreamTable.insert {
                    it[id] = streamId
                    it[ConferenceStreamTable.roomId] = roomId
                    it[startedByMemberId] = adminId
                    it[status] = ConferenceStreamStatus.ENDED
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[startedAt] = at
                    it[endedAt] = at
                    it[restartCount] = 0
                }

                fun target(
                    id: Uuid,
                    destination: Uuid,
                    broadcastKey: String?,
                    viewerUrl: String?,
                ) = ConferenceStreamTargetTable.insert {
                    it[ConferenceStreamTargetTable.id] = id
                    it[ConferenceStreamTargetTable.streamId] = streamId
                    it[destinationId] = destination
                    it[status] = ConferenceStreamTargetStatus.FINISHED
                    it[urlFingerprint] = "rtmps://ingest.example.org/live/$canaryFingerprintPlain"
                    it[broadcastKeyCiphertext] = broadcastKey
                    it[ConferenceStreamTargetTable.viewerUrl] = viewerUrl
                    it[retries] = 0
                }
                target(persistentTargetId, persistentId, null, null)
                target(
                    perBroadcastTargetId,
                    perBroadcastId,
                    canaryBroadcastCiphertext,
                    "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ",
                )
            }

            val bundle = File.createTempFile("stream-key-bundle-", ".zip")
            try {
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceRoot).streamExport(actor = actor, sink = out)
                }
                // Nothing of the key material is anywhere in the bundle (every entry, every line).
                ZipFile(bundle).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val text = zip.getInputStream(entry).bufferedReader().readText()
                        listOf(canaryDestinationCiphertext, canaryBroadcastCiphertext, "CANARY", "CNR...987", "CNR").forEach { canary ->
                            (text.contains(canary)) shouldBe false
                        }
                    }
                    val targets = zip.getInputStream(zip.getEntry("data/conference_stream_target.jsonl")).bufferedReader().readText()
                    targets.contains("{redacted}") shouldBe true
                    targets.contains("https://www.youtube.com/live_chat") shouldBe true
                }

                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)

                transaction(targetDb) {
                    val persistent =
                        ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq persistentId }.single()
                    persistent[ConferenceStreamDestinationTable.keyKind] shouldBe ConferenceStreamKeyKind.PERSISTENT
                    persistent[ConferenceStreamDestinationTable.streamKeyCiphertext] shouldBe null
                    persistent[ConferenceStreamDestinationTable.streamKeySetAt] shouldBe null
                    val perBroadcast =
                        ConferenceStreamDestinationTable
                            .selectAll()
                            .where { ConferenceStreamDestinationTable.id eq perBroadcastId }
                            .single()
                    perBroadcast[ConferenceStreamDestinationTable.keyKind] shouldBe ConferenceStreamKeyKind.PER_BROADCAST
                    val restoredTarget =
                        ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.id eq perBroadcastTargetId }.single()
                    restoredTarget[ConferenceStreamTargetTable.broadcastKeyCiphertext] shouldBe null
                    restoredTarget[ConferenceStreamTargetTable.urlFingerprint] shouldBe "{redacted}"
                    restoredTarget[ConferenceStreamTargetTable.viewerUrl] shouldBe
                        "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ"
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
