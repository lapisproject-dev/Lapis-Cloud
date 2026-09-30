package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Base64

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- [MemberPhotoPersonalData]: the export carries metadata and the
 * JPEG (Art. 15/20) but never token or storage key; erasure is a hard delete of row AND file in
 * EVERY [ErasureMode]. The storage root is the one [MemberPhotoStorage.fromEnvironment] derives --
 * the very same call the contributor makes, which is the point of that single source of truth.
 */
class MemberPhotoPersonalDataTest :
    FunSpec({
        val fixtures = MemberPhotoFixtures()
        val storage = MemberPhotoStorage.fromEnvironment()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup(storage) }

        fun keyOf(member: kotlin.uuid.Uuid) =
            transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single()[MemberPhotoTable.storageKey] }

        test("section key, display name and covered table are registered in the PersonalDataRegistry") {
            MemberPhotoPersonalData.sectionKey shouldBe "memberPhoto"
            (MemberPhotoPersonalData in PersonalDataRegistry.contributors) shouldBe true
            MemberPhotoPersonalData.coveredTables shouldBe setOf(MemberPhotoTable)
        }

        test("export: metadata plus a base64 JPEG that decodes to a valid image -- never the public token or the storage key") {
            val member = fixtures.newMember()
            val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
            val key = keyOf(member)

            val export = transaction { MemberPhotoPersonalData.exportMember(member) }
            val json = export.jsonObject
            json.getValue("hasPhoto").jsonPrimitive.content shouldBe "true"
            json.getValue("visibility").jsonPrimitive.content shouldBe "PUBLIC"
            json.getValue("widthPx").jsonPrimitive.content shouldBe "800"
            json.getValue("consentTextVersion").jsonPrimitive.content shouldBe "member-photo-public-v1"
            val image = Base64.getDecoder().decode(json.getValue("imageJpegBase64").jsonPrimitive.content)
            MemberPhotoTestImages.dimensions(image) shouldBe (800 to 800)

            val text = export.toString()
            text.contains(token) shouldBe false
            text.contains(key) shouldBe false
            json.keys.any { it.contains("token", ignoreCase = true) || it.contains("storage", ignoreCase = true) } shouldBe false
        }

        test("export for a member without a photo says so; with the file gone it flags imageMissing instead of failing") {
            val none = fixtures.newMember()
            transaction { MemberPhotoPersonalData.exportMember(none) }
                .jsonObject
                .getValue("hasPhoto")
                .jsonPrimitive.content shouldBe
                "false"

            val member = fixtures.newMember()
            fixtures.seedPhoto(storage = storage, memberId = member)
            storage.delete(keyOf(member))
            val json = transaction { MemberPhotoPersonalData.exportMember(member) }.jsonObject
            json.getValue("imageMissing").jsonPrimitive.content shouldBe "true"
            json.containsKey("imageJpegBase64") shouldBe false
        }

        test("erase: row and file are hard-deleted in EVERY erasure mode, another member's photo is untouched") {
            ErasureMode.entries.forEach { mode ->
                val member = fixtures.newMember()
                val other = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member, publish = true)
                fixtures.seedPhoto(storage = storage, memberId = other)
                val key = keyOf(member)
                val otherKey = keyOf(other)
                (storage.resolve(key) != null) shouldBe true

                val outcomes = transaction { MemberPhotoPersonalData.eraseMember(memberId = member, mode = mode) }
                outcomes.single().table shouldBe "member_photo"
                outcomes.single().rowsDeleted shouldBe 1
                fixtures.rowCount(member) shouldBe 0
                storage.resolve(key) shouldBe null
                fixtures.rowCount(other) shouldBe 1
                (storage.resolve(otherKey) != null) shouldBe true
            }
        }
    })
