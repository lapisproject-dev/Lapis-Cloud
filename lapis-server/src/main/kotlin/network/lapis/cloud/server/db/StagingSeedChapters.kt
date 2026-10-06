package network.lapis.cloud.server.db

import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterOfficerSnapshot
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.domain.RegionalChapterSnapshot
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import kotlin.uuid.Uuid

/**
 * Staging seed, area "regional chapters" (Welle V1.9.63): three fictitious chapters, no crest images, and one or two
 * officers each.
 *
 * Split in two steps because of the foreign keys: the chapter rows must exist BEFORE the members (a member row carries its
 * `regional_chapter_id` in the one single INSERT -- the seed never updates a member row), the audit entries and the officer
 * grants need the members and therefore come AFTER them. This object never writes the member table.
 */
internal object StagingSeedChapters {
    class ChapterSeed(
        val id: Uuid,
        val name: String,
        val description: String,
    )

    val chapters: List<ChapterSeed> =
        listOf(
            ChapterSeed(
                id = SeedIds.community(1),
                name = "Bezirk Musterstadt-Nord",
                description = "Fiktiver Bezirk im Norden von Musterstadt (Demodaten).",
            ),
            ChapterSeed(
                id = SeedIds.community(2),
                name = "Bezirk Musterstadt-Sued",
                description = "Fiktiver Bezirk im Sueden von Musterstadt (Demodaten).",
            ),
            ChapterSeed(
                id = SeedIds.community(3),
                name = "Bezirk Musterstadt-Mitte",
                description = "Fiktiver Bezirk im Zentrum von Musterstadt (Demodaten).",
            ),
        )

    fun JdbcTransaction.insertChapters(clock: SeedClock) {
        chapters.forEach { chapter ->
            val normalized = RegionalChapterRules.normalizeName(chapter.name)
            RegionalChapterTable.insert {
                it[id] = chapter.id
                it[name] = normalized
                it[nameKey] = RegionalChapterRules.nameKey(normalized)
                it[description] = chapter.description
                it[createdAt] = clock.utcAt(daysAgo = 700)
            }
        }
    }

    /** CREATE audit per chapter plus the officer grants -- the members must exist by now. */
    fun JdbcTransaction.auditAndGrantOfficers(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        chapters.forEach { chapter ->
            AuditLogRecorder.record(
                actorMemberId = actors.admin.id,
                actorRole = actors.adminActor.role,
                entityType = AuditEntityType.REGIONAL_CHAPTER,
                entityId = chapter.id,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        RegionalChapterSnapshot.serializer(),
                        RegionalChapterSnapshot(name = RegionalChapterRules.normalizeName(chapter.name)),
                    ),
            )
        }
        // An officer must be ACTIVE, belong to the chapter and hold an account (RegionalChapterService.grantOfficer eligibility).
        chapters.forEach { chapter ->
            val eligible =
                actors.members.filter {
                    it.chapterId == chapter.id && it.status == MemberStatus.ACTIVE && it.accountRole != null
                }
            eligible.take(2).forEach { officer ->
                val grantId = Uuid.random()
                RegionalChapterOfficerTable.insert {
                    it[id] = grantId
                    it[memberId] = officer.id
                    it[regionalChapterId] = chapter.id
                    it[grantedAt] = clock.now
                    it[grantedByMemberId] = actors.admin.id
                    it[revokedAt] = null
                    it[activeForMemberId] = officer.id
                }
                AuditLogRecorder.record(
                    actorMemberId = actors.admin.id,
                    actorRole = actors.adminActor.role,
                    entityType = AuditEntityType.REGIONAL_CHAPTER_OFFICER,
                    entityId = grantId,
                    action = AuditAction.CREATE,
                    before = null,
                    after =
                        Json.encodeToString(
                            RegionalChapterOfficerSnapshot.serializer(),
                            RegionalChapterOfficerSnapshot(memberId = officer.id.toString(), regionalChapterId = chapter.id.toString()),
                        ),
                )
            }
        }
    }
}
