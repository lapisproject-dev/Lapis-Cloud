package network.lapis.cloud.server.memberphoto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.uuid.Uuid

/** Welle V1.9.19 "Mitglieder-Foto" -- [MemberPhotoStorage]: key discipline, path containment, atomic write, env root. */
class MemberPhotoStorageTest :
    FunSpec({
        test("write stores <uuid>.jpg flat under the root, resolve finds it, delete is idempotent") {
            val root = MemberPhotoFixtures.freshRoot("storage")
            val storage = MemberPhotoStorage(root)
            val key = storage.write(key = Uuid.random(), bytes = byteArrayOf(1, 2, 3))
            Regex("^[0-9a-f-]{36}\\.jpg$").matches(key) shouldBe true
            storage.resolve(key)?.readBytes()?.toList() shouldBe listOf<Byte>(1, 2, 3)
            MemberPhotoFixtures.filesIn(root).map { it.name } shouldBe listOf(key)
            storage.delete(key)
            storage.delete(key)
            storage.resolve(key) shouldBe null
        }

        test("resolve and delete refuse anything that is not a server-generated key -- traversal, separators, other extensions") {
            val root = MemberPhotoFixtures.freshRoot("storage-bad")
            val storage = MemberPhotoStorage(root)
            val outside = File(root.parentFile, "outside-${Uuid.random()}.jpg").also { it.writeBytes(byteArrayOf(9)) }
            try {
                listOf(
                    "../${outside.name}",
                    "..%2F${outside.name}",
                    "sub/${Uuid.random()}.jpg",
                    "${Uuid.random()}.png",
                    "${Uuid.random()}.jpg.tmp",
                    "",
                    "/etc/passwd",
                ).forEach { bad ->
                    storage.resolve(bad) shouldBe null
                    storage.delete(bad) // must not throw, must not touch anything
                }
                outside.exists() shouldBe true
            } finally {
                outside.delete()
            }
        }

        test("a failed write leaves no .tmp file behind") {
            val root = MemberPhotoFixtures.freshRoot("storage-fail")
            val storage = MemberPhotoStorage(root)
            val key = Uuid.random()
            // make the final target a non-empty directory so the atomic move must fail
            File(root, "$key.jpg").apply {
                mkdirs()
                File(this, "x").writeBytes(byteArrayOf(1))
            }
            shouldThrow<Exception> { storage.write(key = key, bytes = byteArrayOf(1)) }
            root.listFiles { f -> f.name.endsWith(".tmp") }?.size shouldBe 0
        }

        test("fromEnvironment: explicit override wins, else <document root>/member-photos, else build/document-storage/member-photos") {
            val explicit = MemberPhotoFixtures.freshRoot("env-explicit")
            val documents = MemberPhotoFixtures.freshRoot("env-documents")
            val viaOverride =
                MemberPhotoStorage.fromEnvironment { name -> if (name == "LAPIS_MEMBER_PHOTO_STORAGE_ROOT") explicit.path else null }
            val overrideKey = viaOverride.write(key = Uuid.random(), bytes = byteArrayOf(1))
            File(explicit, overrideKey).exists() shouldBe true

            val viaDocuments =
                MemberPhotoStorage.fromEnvironment { name -> if (name == "LAPIS_DOCUMENT_STORAGE_ROOT") documents.path else null }
            val documentsKey = viaDocuments.write(key = Uuid.random(), bytes = byteArrayOf(1))
            File(documents, "member-photos/$documentsKey").exists() shouldBe true

            val blankOverride =
                MemberPhotoStorage.fromEnvironment { name ->
                    when (name) {
                        "LAPIS_MEMBER_PHOTO_STORAGE_ROOT" -> "  "
                        "LAPIS_DOCUMENT_STORAGE_ROOT" -> documents.path
                        else -> null
                    }
                }
            val blankKey = blankOverride.write(key = Uuid.random(), bytes = byteArrayOf(1))
            File(documents, "member-photos/$blankKey").exists() shouldBe true
        }
    })
