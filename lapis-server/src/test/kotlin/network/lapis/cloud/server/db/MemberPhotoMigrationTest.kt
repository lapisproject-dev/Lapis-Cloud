package network.lapis.cloud.server.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- `V61__member_photo.sql` constraints on the real Flyway-migrated
 * schema: unique index per member, unique token (but any number of NULL tokens), the FK, and the
 * content-type / visibility CHECKs. The state CHECK itself is covered in `MemberPhotoServiceTest`.
 */
class MemberPhotoMigrationTest :
    FunSpec({
        val fixtures = MemberPhotoFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fixtures.cleanup() }

        fun insertRow(
            member: Uuid,
            token: String? = null,
            contentType: String = "image/jpeg",
        ) = transaction {
            MemberPhotoTable.insert {
                it[id] = Uuid.random()
                it[memberId] = member
                it[storageKey] = "${Uuid.random()}.jpg"
                it[MemberPhotoTable.contentType] = contentType
                it[widthPx] = 800
                it[heightPx] = 800
                it[sizeBytes] = 1L
                it[uploadedAt] = DbClock.nowLocalDateTime()
                it[visibility] = if (token == null) MemberPhotoVisibility.PRIVATE else MemberPhotoVisibility.PUBLIC
                it[publicToken] = token
                it[consentGrantedAt] = if (token == null) null else DbClock.nowLocalDateTime()
                it[consentTextVersion] = if (token == null) null else "member-photo-public-v1"
            }
        }

        test("many members may have NULL tokens at the same time (unique index ignores NULLs)") {
            repeat(3) { insertRow(member = fixtures.newMember()) }
        }

        test("one photo per member: a second row for the same member violates uq_member_photo_member") {
            val member = fixtures.newMember()
            insertRow(member = member)
            shouldThrow<Exception> { insertRow(member = member) }
        }

        test("a public token is unique across members") {
            val token = "U".repeat(43)
            insertRow(member = fixtures.newMember(), token = token)
            shouldThrow<Exception> { insertRow(member = fixtures.newMember(), token = token) }
        }

        test("only image/jpeg is allowed as stored content type") {
            shouldThrow<Exception> { insertRow(member = fixtures.newMember(), contentType = "image/png") }
        }

        test("the member FK is enforced") {
            shouldThrow<Exception> { insertRow(member = Uuid.random()) }
        }
    })
