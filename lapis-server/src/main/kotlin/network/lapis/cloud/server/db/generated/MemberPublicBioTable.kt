// Hand-written, mirroring the generated-file convention every other db/generated table in this
// package establishes (see MemberPhotoTable.kt) -- kept in sync with 60-member-public-bio.kuml.kts
// by the schema drift test. Welle V1.9.20 "Öffentliche Seiten".

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object MemberPublicBioTable : Table("member_public_bio") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)

    // Application-validated to 500 code points; VARCHAR(2000) is UTF-16 headroom.
    public val bioText: Column<String> = varchar("bio_text", 2000)
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")

    // Both NULL (private) or both set (published) -- chk_member_public_bio_consent_state.
    public val consentGrantedAt: Column<LocalDateTime?> = datetime("consent_granted_at").nullable()
    public val consentTextVersion: Column<String?> = varchar("consent_text_version", 40).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
