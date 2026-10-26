package network.lapis.cloud.server.gallery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.dsgvo.AttachedImagePersonalData
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.util.Base64
import kotlin.uuid.Uuid

private val AUCTION = AttachedImageOwnerType.AUCTION_LISTING
private val PROJECT = AttachedImageOwnerType.CROWDFUNDING_PROJECT
private val SOCIAL = AttachedImageOwnerType.SOCIAL_POST

/** V1.9.222 -- the DSGVO contributor: the export carries the picture, the erasure is hard in every mode and closes the gaps. */
class AttachedImagePersonalDataTest :
    FunSpec({
        val fx = GalleryFixtures(sharedStorage = AttachedImageStorage.fromEnvironment(documentStorageRoot = File("build/document-storage")))

        beforeSpec {
            DatabaseConfig.connect()
            fx.setAuctionEnabled(true)
        }
        afterSpec { fx.cleanUp() }

        test("the export carries metadata and the picture as base64, oldest first, and never the storage key or a path") {
            val member = fx.member(label = "subject")
            val project = fx.project(submitter = member)
            val row = fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = member), byteSize = 64)
            transaction {
                AttachedImageStore.updateCaptionInTx(
                    imageId = row.id,
                    caption = "Meine Beschriftung",
                    altText = "Alternativtext",
                )
            }
            val json = transaction { AttachedImagePersonalData.exportMember(member) }
            val image =
                json.jsonObject
                    .getValue("images")
                    .jsonArray
                    .single()
                    .jsonObject
            image.getValue("caption").jsonPrimitive.content shouldBe "Meine Beschriftung"
            image.getValue("altText").jsonPrimitive.content shouldBe "Alternativtext"
            image.getValue("ownerType").jsonPrimitive.content shouldBe "CROWDFUNDING_PROJECT"
            image.getValue("mimeType").jsonPrimitive.content shouldBe "image/jpeg"
            Base64.getDecoder().decode(image.getValue("imageBase64").jsonPrimitive.content).size shouldBe 64
            json.toString() shouldNotContain row.storageKey.toString()
            json.toString() shouldNotContain "attached-images"
        }

        test("a picture whose file is gone is exported as imageMissing") {
            val member = fx.member(label = "subject")
            val project = fx.project(submitter = member)
            val row = fx.addImage(type = PROJECT, ownerId = project, caller = fx.current(memberId = member))
            fx.storage.delete(row.storageKey)
            val json = transaction { AttachedImagePersonalData.exportMember(member) }
            json.jsonObject
                .getValue("images")
                .jsonArray
                .single()
                .jsonObject
                .getValue("imageMissing")
                .jsonPrimitive.content shouldBe "true"
        }

        test("erasure deletes rows AND files in every mode, closes the gaps of the same owner, and leaves other members' pictures alone") {
            for (mode in ErasureMode.entries) {
                val a = fx.member(label = "creator-$mode")
                val b = fx.member(label = "other-uploader-$mode")
                val auction = fx.auction(seller = a)
                val first = fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = a))
                // a picture of ANOTHER uploader in the middle of the same gallery (cannot happen through the API; it proves the gap
                // closing)
                val middleKey = Uuid.random()
                fx.storage.write(
                    storageKey = middleKey,
                    format = network.lapis.cloud.server.events.CoverImageFormat.JPEG,
                    bytes = ByteArray(5),
                )
                val middle =
                    transaction {
                        AttachedImageStore.insertInTx(
                            id = Uuid.random(),
                            type = AUCTION,
                            ownerId = auction,
                            storageKey = middleKey,
                            mimeType = "image/jpeg",
                            byteSize = 5,
                            width = 10,
                            height = 10,
                            uploadedBy = b,
                            createdAt = DbClock.nowLocalDateTime(),
                        )
                    }
                val last = fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = a))
                val bProject = fx.project(submitter = b)
                val bImage = fx.addImage(type = PROJECT, ownerId = bProject, caller = fx.current(memberId = b))
                val outcome = transaction { AttachedImagePersonalData.eraseMember(memberId = b, mode = mode) }
                outcome.single().table shouldBe "attached_image"
                outcome.single().rowsDeleted shouldBe 2
                fx.storage.resolve(storageKey = middle.storageKey, mimeType = "image/jpeg") shouldBe null
                fx.storage.resolve(storageKey = bImage.storageKey, mimeType = "image/jpeg") shouldBe null
                fx.rows(type = AUCTION, ownerId = auction).map { it.id } shouldContainExactly listOf(first.id, last.id)
                fx.rows(type = AUCTION, ownerId = auction).map { it.position } shouldContainExactly listOf(0, 1)
                // the creator's pictures keep their files until the creator is erased as well
                fx.storage.resolve(storageKey = first.storageKey, mimeType = "image/jpeg") shouldNotBe null
                fx.rows(type = PROJECT, ownerId = bProject) shouldHaveSize 0
                transaction { AttachedImagePersonalData.eraseMember(memberId = a, mode = mode) }.single().rowsDeleted shouldBe 2
                fx.rows(type = AUCTION, ownerId = auction) shouldHaveSize 0
                fx.storage.resolve(storageKey = first.storageKey, mimeType = "image/jpeg") shouldBe null
                fx.storage.resolve(storageKey = last.storageKey, mimeType = "image/jpeg") shouldBe null
            }
        }

        test("V1.9.223: pictures of a member's social posts are exported as SOCIAL_POST and erased with the member in every mode") {
            for (mode in ErasureMode.entries) {
                val member = fx.member(label = "poster-$mode")
                val post = fx.socialPost(author = member)
                val rows = (1..2).map { fx.addImage(type = SOCIAL, ownerId = post, caller = fx.current(memberId = member), byteSize = 32) }
                val exported =
                    transaction { AttachedImagePersonalData.exportMember(member) }
                        .jsonObject
                        .getValue("images")
                        .jsonArray
                exported shouldHaveSize 2
                exported.all {
                    it.jsonObject
                        .getValue("ownerType")
                        .jsonPrimitive.content == "SOCIAL_POST"
                } shouldBe true
                transaction { AttachedImagePersonalData.eraseMember(memberId = member, mode = mode) }.single().rowsDeleted shouldBe 2
                fx.rows(type = SOCIAL, ownerId = post) shouldHaveSize 0
                rows.forEach { fx.storage.resolve(storageKey = it.storageKey, mimeType = "image/jpeg") shouldBe null }
            }
        }
    })
