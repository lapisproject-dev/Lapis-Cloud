package network.lapis.cloud.server.memberbio

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.chapters.ChapterCrestFormat
import network.lapis.cloud.server.chapters.ChapterCrestPolicy
import network.lapis.cloud.server.chapters.ChapterCrestStorage
import network.lapis.cloud.server.chapters.ChapterCrestStore
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

// A minimal, already-sanitized SVG crest (what the sanitizer would store).
internal const val SAMPLE_SVG_CREST =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"100\" height=\"100\">\n" +
        "<circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"#c00\"/></svg>"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- members, board seats, politician profiles, bios, consents,
 * photos and chapters shared by the public-profile specs. One instance per spec; [cleanup] removes
 * everything created through it (children first -- every FK here is non-cascading).
 */

internal class PublicProfilesFixtures {
    private val memberIds = mutableListOf<Uuid>()
    private val committeeIds = mutableListOf<Uuid>()
    private val chapterIds = mutableListOf<Uuid>()
    private val photoFixtures = MemberPhotoFixtures()

    fun newMember(
        displayName: String = "Erika Mustermann",
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[email] = "public-profiles-$id@example.org"
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
        memberIds += id
        return id
    }

    fun setStatus(
        memberId: Uuid,
        status: MemberStatus,
    ) {
        transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[MemberTable.status] = status } }
    }

    /** A fresh EXECUTIVE_BOARD committee with one OPEN seat for [memberId]; returns the committee id. */
    fun addBoardSeat(
        memberId: Uuid,
        role: CommitteeRole = CommitteeRole.CHAIR,
        committeeActive: Boolean = true,
        until: LocalDate? = null,
    ): Uuid {
        val committeeId = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[id] = committeeId
                it[name] = "Test-Vorstand $committeeId"
                it[type] = CommitteeType.EXECUTIVE_BOARD
                it[description] = "Fixture fuer die Public-Profiles-Tests"
                it[active] = committeeActive
                it[quorumPercent] = 50
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[CommitteeMembershipTable.committeeId] = committeeId
                it[CommitteeMembershipTable.memberId] = memberId
                it[CommitteeMembershipTable.role] = role
                it[since] = LocalDate(2020, 1, 1)
                it[CommitteeMembershipTable.until] = until
            }
        }
        committeeIds += committeeId
        return committeeId
    }

    fun makePolitician(
        memberId: Uuid,
        mandateText: String? = "Landtagsabgeordnete",
        status: PoliticianProfileStatus = PoliticianProfileStatus.ACTIVE,
        grantedBy: Uuid = memberId,
    ) {
        transaction {
            PoliticianProfileTable.insert {
                it[id] = Uuid.random()
                it[PoliticianProfileTable.memberId] = memberId
                it[PoliticianProfileTable.status] = status
                it[PoliticianProfileTable.mandateText] = mandateText
                it[grantedAt] = DbClock.nowLocalDateTime()
                it[grantedByMemberId] = grantedBy
                it[revokedAt] = if (status == PoliticianProfileStatus.ACTIVE) null else DbClock.nowLocalDateTime()
                it[revokedByMemberId] = if (status == PoliticianProfileStatus.ACTIVE) null else grantedBy
            }
        }
    }

    /** Grants a consent of [kind] with the CURRENT wording (or a stale one) by writing the event row directly. */
    fun grantConsent(
        memberId: Uuid,
        kind: PublicRankingKind = PublicRankingKind.POLITICIAN_LISTING,
        stale: Boolean = false,
    ) {
        val disclaimer = PublicRankingConsentDisclaimer.of(kind)
        transaction {
            PublicRankingConsentEventTable.insert {
                it[id] = Uuid.random()
                it[PublicRankingConsentEventTable.memberId] = memberId
                it[rankingKind] = kind
                it[eventType] = PublicRankingConsentEventType.GRANTED
                it[occurredAt] = DbClock.nowLocalDateTime()
                it[supersededAt] = null
                it[consentVersion] = if (stale) "stale-version.v0" else disclaimer.version
                it[consentSha256] = if (stale) "0".repeat(64) else disclaimer.sha256
            }
        }
    }

    fun seedBio(
        memberId: Uuid,
        text: String = "Ich bin seit Jahren engagiert.",
        publish: Boolean = false,
        consentVersion: String = MemberPublicBioRules.CONSENT_TEXT_VERSION,
    ) {
        val now = DbClock.nowLocalDateTime()
        transaction {
            MemberPublicBioStore.upsertText(memberId = memberId, text = text, now = now)
            if (publish) MemberPublicBioStore.publish(memberId = memberId, version = consentVersion, now = now)
        }
    }

    fun bioRow(memberId: Uuid) =
        transaction {
            MemberPublicBioTable.selectAll().where { MemberPublicBioTable.memberId eq memberId }.singleOrNull()
        }

    fun bioRowCount(memberId: Uuid): Long =
        transaction { MemberPublicBioTable.selectAll().where { MemberPublicBioTable.memberId eq memberId }.count() }

    /** Stores a real JPEG under a fresh key and writes the photo row; returns the public token if [publish]. */
    fun seedPhoto(
        storage: MemberPhotoStorage,
        memberId: Uuid,
        publish: Boolean,
    ): String? = photoFixtures.seedPhoto(storage = storage, memberId = memberId, publish = publish)

    fun newChapter(
        name: String = "Landesverband Test ${Uuid.random()}",
        description: String? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            RegionalChapterTable.insert {
                it[RegionalChapterTable.id] = id
                it[RegionalChapterTable.name] = name
                it[nameKey] = name.lowercase().take(80)
                it[createdAt] = DbClock.nowLocalDateTime()
                it[RegionalChapterTable.description] = description
            }
        }
        chapterIds += id
        return id
    }

    /** Writes a real PNG/JPEG/SVG file and points the chapter at it; returns the public token. */
    fun seedCrest(
        storage: ChapterCrestStorage,
        chapterId: Uuid,
        format: ChapterCrestFormat = ChapterCrestFormat.PNG,
    ): String {
        val image = MemberPhotoTestImages.solid(width = 128, height = 128)
        val bytes =
            when (format) {
                ChapterCrestFormat.PNG -> MemberPhotoTestImages.png(image)
                ChapterCrestFormat.JPEG -> MemberPhotoTestImages.jpeg(image)
                ChapterCrestFormat.SVG -> SAMPLE_SVG_CREST.toByteArray()
            }
        val imageId = Uuid.random()
        storage.write(id = imageId, format = format, bytes = bytes)
        val token = ChapterCrestStore.newPublicToken()
        transaction {
            RegionalChapterTable.update({ RegionalChapterTable.id eq chapterId }) {
                it[crestImageId] = imageId
                it[crestPublicToken] = token
                it[crestContentType] = ChapterCrestPolicy.contentTypeOf(format)
            }
        }
        return token
    }

    /** The `after_snapshot` of every MEMBER audit entry for [memberId], oldest first. */
    fun memberAuditAfterSnapshots(memberId: Uuid): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq memberId) }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
        }

    /** The `after_snapshot` of every REGIONAL_CHAPTER audit entry for [chapterId], oldest first. */
    fun chapterAuditAfterSnapshots(chapterId: Uuid): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where {
                    (AuditLogEntryTable.entityType eq AuditEntityType.REGIONAL_CHAPTER) and (AuditLogEntryTable.entityId eq chapterId)
                }.orderBy(AuditLogEntryTable.sequenceNumber)
                .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
        }

    fun cleanup(
        photoStorage: MemberPhotoStorage? = null,
        crestStorage: ChapterCrestStorage? = null,
    ) {
        if (crestStorage != null && chapterIds.isNotEmpty()) {
            val imageIds =
                transaction {
                    RegionalChapterTable
                        .selectAll()
                        .where { RegionalChapterTable.id inList chapterIds }
                        .mapNotNull { it[RegionalChapterTable.crestImageId] }
                }
            imageIds.forEach { crestStorage.delete(it) }
        }
        photoFixtures.cleanup(photoStorage)
        transaction {
            if (memberIds.isNotEmpty()) {
                MemberPublicBioTable.deleteWhere { memberId inList memberIds }
                PublicRankingConsentEventTable.deleteWhere { memberId inList memberIds }
                PoliticianProfileTable.deleteWhere { memberId inList memberIds }
                CommitteeMembershipTable.deleteWhere { memberId inList memberIds }
                AuditLogEntryTable.deleteWhere { actorMemberId inList memberIds }
            }
            if (committeeIds.isNotEmpty()) CommitteeTable.deleteWhere { id inList committeeIds }
            if (chapterIds.isNotEmpty()) RegionalChapterTable.deleteWhere { id inList chapterIds }
            if (memberIds.isNotEmpty()) {
                MemberPhotoTable.deleteWhere { memberId inList memberIds }
                AccountTable.deleteWhere { memberId inList memberIds }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList memberIds }
                MemberTable.deleteWhere { id inList memberIds }
            }
        }
    }

    /**
     * Test isolation for the shared process-wide H2: every EXECUTIVE_BOARD committee and every ACTIVE
     * politician profile left behind by other specs is switched off, so emptiness assertions are
     * deterministic. Specs run sequentially (Kotest default); nothing is deleted.
     */
    fun neutralizeForeignProfiles() {
        transaction {
            CommitteeTable.update({ CommitteeTable.type eq CommitteeType.EXECUTIVE_BOARD }) { it[active] = false }
            PoliticianProfileTable.update({ PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE }) {
                it[status] = PoliticianProfileStatus.FORMER
                it[revokedAt] = DbClock.nowLocalDateTime()
            }
        }
    }
}
