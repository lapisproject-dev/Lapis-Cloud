package network.lapis.cloud.server.memberphoto

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

/** Members, photo rows and cleanup shared by the member photo specs. One instance per spec. */
internal class MemberPhotoFixtures {
    val createdMemberIds = mutableListOf<Uuid>()

    fun newMember(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Foto Testmitglied"
                it[email] = "member-photo-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2020, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        createdMemberIds += id
        return id
    }

    fun setStatus(
        memberId: Uuid,
        status: MemberStatus,
    ) {
        transaction {
            MemberTable.update({ MemberTable.id eq memberId }) { it[MemberTable.status] = status }
        }
    }

    /** Stores a real 800x800 JPEG under a fresh key and writes the row; returns the public token if [publish]. */
    fun seedPhoto(
        storage: MemberPhotoStorage,
        memberId: Uuid,
        publish: Boolean = false,
    ): String? {
        val bytes = MemberPhotoTestImages.jpeg(MemberPhotoTestImages.solid(width = 800, height = 800))
        val key = storage.write(key = Uuid.random(), bytes = bytes)
        val now = DbClock.nowLocalDateTime()
        transaction {
            MemberPhotoStore.upsertAfterUpload(
                memberId = memberId,
                storageKey = key,
                widthPx = 800,
                heightPx = 800,
                sizeBytes = bytes.size.toLong(),
                now = now,
            )
            if (publish) {
                MemberPhotoStore.publish(memberId = memberId, consentTextVersion = MemberPhotoRules.CONSENT_TEXT_VERSION, now = now)
            }
        }
        return tokenOf(memberId)
    }

    fun tokenOf(memberId: Uuid): String? =
        transaction {
            MemberPhotoTable
                .selectAll()
                .where { MemberPhotoTable.memberId eq memberId }
                .singleOrNull()
                ?.get(MemberPhotoTable.publicToken)
        }

    fun rowCount(memberId: Uuid): Long =
        transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq memberId }.count() }

    /** The `after_snapshot` of every MEMBER audit entry for [memberId], oldest first. */
    fun memberAuditAfterSnapshots(memberId: Uuid): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq memberId) }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
        }

    fun cleanup(storage: MemberPhotoStorage? = null) {
        if (storage != null) {
            val keys =
                transaction {
                    MemberPhotoTable
                        .selectAll()
                        .where { MemberPhotoTable.memberId inList createdMemberIds }
                        .map { it[MemberPhotoTable.storageKey] }
                }
            keys.forEach { storage.delete(it) }
        }
        transaction {
            MemberPhotoTable.deleteWhere { memberId inList createdMemberIds }
            AuditLogEntryTable.deleteWhere { actorMemberId inList createdMemberIds }
            AccountTable.deleteWhere { memberId inList createdMemberIds }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
            MemberTable.deleteWhere { id inList createdMemberIds }
        }
    }

    companion object {
        fun freshRoot(name: String): File = File("build/test-member-photo-storage/$name-${Uuid.random()}").also { it.mkdirs() }

        fun filesIn(root: File): List<File> = root.listFiles { f -> f.isFile }?.toList().orEmpty()
    }
}
