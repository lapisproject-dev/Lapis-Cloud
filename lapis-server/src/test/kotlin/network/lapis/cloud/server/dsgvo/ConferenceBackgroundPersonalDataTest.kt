package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceBackgroundImageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- exercises
 * [ConferenceBackgroundPersonalData] directly (no HTTP layer needed), same house style
 * [MemberCardPersonalDataTest] establishes. [PersonalDataCoverageTest] only proves
 * [ConferenceBackgroundImageTable] is covered by SOME contributor -- this file pins the
 * security-relevant behaviors the object's own KDoc claims: export carries ONLY metadata
 * (never storage keys, never bytes), and erasure hard-deletes both the DB row AND both files.
 */
class ConferenceBackgroundPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdStorageKeys = mutableListOf<String>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    ConferenceBackgroundImageTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
            val actualStorageRootForCleanup = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")
            createdStorageKeys.forEach { actualStorageRootForCleanup.resolve(it).delete() }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ConferenceBackgroundPersonalDataTest Mitglied"
                    it[email] = "conferencebackgrounddsgvo-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2038, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        // Uses the SAME env-var-derived root ConferenceBackgroundPersonalData itself resolves
        // (LAPIS_DOCUMENT_STORAGE_ROOT, default "build/document-storage") -- see that object's own
        // KDoc "Same root ... re-derived here".
        val actualStorageRoot = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")

        fun insertRowWithFiles(memberId: Uuid): Triple<Uuid, File, File> {
            val id = Uuid.random()
            val storageKey = "conference-backgrounds/$memberId/$id.jpg"
            val thumbStorageKey = "conference-backgrounds/$memberId/$id.thumb.jpg"
            transaction {
                ConferenceBackgroundImageTable.insert {
                    it[ConferenceBackgroundImageTable.id] = id
                    it[ConferenceBackgroundImageTable.memberId] = memberId
                    it[ConferenceBackgroundImageTable.storageKey] = storageKey
                    it[ConferenceBackgroundImageTable.thumbStorageKey] = thumbStorageKey
                    it[width] = 640
                    it[height] = 480
                    it[sizeBytes] = 12345L
                    it[sha256] = "a".repeat(64)
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            val mainFile = actualStorageRoot.resolve(storageKey)
            val thumbFile = actualStorageRoot.resolve(thumbStorageKey)
            mainFile.parentFile.mkdirs()
            mainFile.writeBytes(byteArrayOf(1, 2, 3))
            thumbFile.writeBytes(byteArrayOf(4, 5, 6))
            createdStorageKeys += storageKey
            createdStorageKeys += thumbStorageKey
            return Triple(id, mainFile, thumbFile)
        }

        test("exportMember lists id/width/height/sizeBytes/createdAt but NEVER storageKey/thumbStorageKey/sha256") {
            val memberId = createMember()
            val (imageId, _, _) = insertRowWithFiles(memberId)

            val export = transaction { ConferenceBackgroundPersonalData.exportMember(memberId) }
            val images = export["conferenceBackgroundImages"]!!.jsonArray
            images.size shouldBe 1
            val entry = images[0].jsonObject
            entry["id"]!!.jsonPrimitive.content shouldBe imageId.toString()
            entry.containsKey("width") shouldBe true
            entry.containsKey("height") shouldBe true
            entry.containsKey("sizeBytes") shouldBe true
            entry.containsKey("createdAt") shouldBe true
            entry.containsKey("storageKey") shouldBe false
            entry.containsKey("thumbStorageKey") shouldBe false
            entry.containsKey("sha256") shouldBe false
        }

        test("exportMember for a member with no images yields an empty array, not an error") {
            val memberId = createMember()
            val export = transaction { ConferenceBackgroundPersonalData.exportMember(memberId) }
            export["conferenceBackgroundImages"]!!.jsonArray.size shouldBe 0
        }

        test("eraseMember hard-deletes the row AND both files, reports the correct rowsDeleted count") {
            val memberId = createMember()
            val (imageId, mainFile, thumbFile) = insertRowWithFiles(memberId)
            mainFile.exists() shouldBe true
            thumbFile.exists() shouldBe true

            val outcomes =
                transaction {
                    ConferenceBackgroundPersonalData.eraseMember(memberId = memberId, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED)
                }

            val outcome = outcomes.single { it.table == "conference_background_image" }
            outcome.rowsDeleted shouldBe 1
            transaction {
                ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.id eq imageId }.count()
            } shouldBe 0L
            mainFile.exists() shouldBe false
            thumbFile.exists() shouldBe false
        }

        test("eraseMember for one member never touches another member's images/files") {
            val memberA = createMember()
            val memberB = createMember()
            val (_, mainA, thumbA) = insertRowWithFiles(memberA)
            val (imageIdB, mainB, thumbB) = insertRowWithFiles(memberB)

            transaction {
                ConferenceBackgroundPersonalData.eraseMember(
                    memberId = memberA,
                    mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                )
            }

            mainA.exists() shouldBe false
            thumbA.exists() shouldBe false
            mainB.exists() shouldBe true
            thumbB.exists() shouldBe true
            transaction {
                ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.id eq imageIdB }.count()
            } shouldBe 1L
        }
    })
