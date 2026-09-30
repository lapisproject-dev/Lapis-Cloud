package network.lapis.cloud.server.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.backup.TestDatabaseFactory
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.h2.tools.RunScript
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.InputStreamReader
import java.sql.DriverManager
import kotlin.uuid.Uuid

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- `V62__public_profiles.sql` on the real Flyway-migrated
 * schema: the constraints of the new table and columns, the widened `ranking_kind` column with its
 * replaced CHECK, and that running the script a SECOND time (with data present) is a clean no-op --
 * the additive/idempotent promise of its file header.
 */
class PublicProfilesMigrationTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fixtures.cleanup() }

        fun insertBio(
            member: Uuid,
            grantedAt: Boolean,
            version: String?,
        ) = transaction {
            MemberPublicBioTable.insert {
                it[id] = Uuid.random()
                it[memberId] = member
                it[bioText] = "Text"
                it[updatedAt] = DbClock.nowLocalDateTime()
                it[consentGrantedAt] = if (grantedAt) DbClock.nowLocalDateTime() else null
                it[consentTextVersion] = version
            }
        }

        fun updateChapterCrest(
            chapter: Uuid,
            imageId: Uuid?,
            token: String?,
            contentType: String?,
        ) = transaction {
            RegionalChapterTable.update({ RegionalChapterTable.id eq chapter }) {
                it[crestImageId] = imageId
                it[crestPublicToken] = token
                it[crestContentType] = contentType
            }
        }

        test("member_public_bio: one row per member, the consent columns are both NULL or both set, the member FK is enforced") {
            val member = fixtures.newMember()
            insertBio(member = member, grantedAt = false, version = null)
            shouldThrow<Exception> { insertBio(member = member, grantedAt = false, version = null) } // uq_member_public_bio_member

            val other = fixtures.newMember()
            insertBio(member = other, grantedAt = true, version = "member-bio-public-v1") // both set: fine
            shouldThrow<Exception> { insertBio(member = fixtures.newMember(), grantedAt = true, version = null) }
            shouldThrow<Exception> { insertBio(member = fixtures.newMember(), grantedAt = false, version = "member-bio-public-v1") }
            shouldThrow<Exception> { insertBio(member = Uuid.random(), grantedAt = false, version = null) }
        }

        test(
            "regional_chapter crest: the three crest columns are all NULL or all set; only image/jpeg and image/png; tokens are unique, NULLs are not",
        ) {
            val first = fixtures.newChapter()
            val second = fixtures.newChapter()
            val third = fixtures.newChapter()
            // all NULL (default) for many chapters: fine
            updateChapterCrest(first, imageId = null, token = null, contentType = null)
            // all set: fine
            updateChapterCrest(first, imageId = Uuid.random(), token = "T".repeat(43), contentType = "image/png")
            // partial states violate chk_regional_chapter_crest_state
            shouldThrow<Exception> { updateChapterCrest(second, imageId = Uuid.random(), token = null, contentType = "image/png") }
            shouldThrow<Exception> { updateChapterCrest(second, imageId = null, token = "U".repeat(43), contentType = null) }
            // foreign content types violate chk_regional_chapter_crest_content_type
            shouldThrow<Exception> {
                updateChapterCrest(
                    second,
                    imageId = Uuid.random(),
                    token = "V".repeat(43),
                    contentType = "image/svg+xml",
                )
            }
            shouldThrow<Exception> {
                updateChapterCrest(
                    second,
                    imageId = Uuid.random(),
                    token = "V".repeat(43),
                    contentType = "image/gif",
                )
            }
            // a token is unique across chapters (uq_regional_chapter_crest_token)
            shouldThrow<Exception> {
                updateChapterCrest(
                    third,
                    imageId = Uuid.random(),
                    token = "T".repeat(43),
                    contentType = "image/jpeg",
                )
            }
            updateChapterCrest(third, imageId = Uuid.random(), token = "W".repeat(43), contentType = "image/jpeg")
        }

        test(
            "public_ranking_consent_event: POLITICIAN_LISTING (18 characters) fits and is accepted, the two old kinds still work, anything else is rejected",
        ) {
            val member = fixtures.newMember()

            fun insertKind(kind: String) =
                transaction {
                    exec(
                        "INSERT INTO public_ranking_consent_event (id, member_id, ranking_kind, event_type, occurred_at, superseded_at, " +
                            "consent_version, consent_sha256) VALUES ('${Uuid.random()}', '$member', '$kind', 'GRANTED', " +
                            "CURRENT_TIMESTAMP, NULL, 'v', '${"0".repeat(64)}')",
                    )
                }
            insertKind("LTR_HOLDINGS")
            insertKind("DONATIONS")
            insertKind("POLITICIAN_LISTING")
            shouldThrow<Exception> { insertKind("POLITICIAN_LISTINGS") }
            shouldThrow<Exception> { insertKind("SOMETHING_ELSE") }

            // And through the typed table: the enum column maps the new literal (length 24 matches the widened column).
            val disclaimer = PublicRankingConsentDisclaimer.of(PublicRankingKind.POLITICIAN_LISTING)
            transaction {
                PublicRankingConsentEventTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = member
                    it[rankingKind] = PublicRankingKind.POLITICIAN_LISTING
                    it[eventType] = PublicRankingConsentEventType.REVOKED
                    it[occurredAt] = DbClock.nowLocalDateTime()
                    it[supersededAt] = null
                    it[consentVersion] = disclaimer.version
                    it[consentSha256] = disclaimer.sha256
                }
            }
        }

        test("running V62 a second time, with data present, is a clean no-op (additive and idempotent)") {
            val name = "public-profiles-migration-${Uuid.random()}"
            TestDatabaseFactory.freshMigratedH2Database(name)
            val jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.autoCommit = true
                val chapterId = Uuid.random()
                val memberId = Uuid.random()
                connection.createStatement().use { stmt ->
                    stmt.execute(
                        "INSERT INTO member (id, display_name, email, status, joined_at) VALUES " +
                            "('$memberId', 'Migrationstest', 'mig-$memberId@example.org', 'ACTIVE', DATE '2026-01-01')",
                    )
                    stmt.execute(
                        "INSERT INTO regional_chapter (id, name, name_key, created_at, description, crest_image_id, " +
                            "crest_public_token, crest_content_type) VALUES ('$chapterId', 'LV', 'lv', CURRENT_TIMESTAMP, " +
                            "'Beschreibung', '${Uuid.random()}', '${"X".repeat(43)}', 'image/png')",
                    )
                    stmt.execute(
                        "INSERT INTO public_ranking_consent_event (id, member_id, ranking_kind, event_type, occurred_at, superseded_at, " +
                            "consent_version, consent_sha256) VALUES ('${Uuid.random()}', '$memberId', 'POLITICIAN_LISTING', 'GRANTED', " +
                            "CURRENT_TIMESTAMP, NULL, 'v', '${"0".repeat(64)}')",
                    )
                    stmt.execute(
                        "INSERT INTO member_public_bio (id, member_id, bio_text, updated_at, consent_granted_at, consent_text_version) " +
                            "VALUES ('${Uuid.random()}', '$memberId', 'Text', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'member-bio-public-v1')",
                    )
                }
                val script =
                    requireNotNull(PublicProfilesMigrationTest::class.java.getResourceAsStream("/db/migration/V62__public_profiles.sql"))
                InputStreamReader(script, Charsets.UTF_8).use { reader -> RunScript.execute(connection, reader) }

                fun count(table: String): Long =
                    connection.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                count("regional_chapter") shouldBe 1L
                count("member_public_bio") shouldBe 1L
                count("public_ranking_consent_event") shouldBe 1L
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT description, crest_content_type FROM regional_chapter").use { rs ->
                        rs.next()
                        rs.getString(1) shouldBe "Beschreibung"
                        rs.getString(2) shouldBe "image/png"
                    }
                }
            }
        }
    })
