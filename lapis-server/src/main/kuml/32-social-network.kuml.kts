// Soziales Netzwerk domain, Welle V1.1.1 "Fundament & Post-Kern" -- see the concept documents
// ("03 Bereiche/Lapis Cloud/Soziales Netzwerk.md" and "03 Bereiche/Lapis Cloud/Meritokratisches
// System und Libertaler.md", "Im sozialen Netz -- Gewichtung von Posts" section, vault) for the
// full fachlich specification this implements, plus the accompanying implementation plan
// ("V1.1 Soziales Netzwerk", 2026-08-18) for the researched code-reuse basis.
//
// **Only `SocialPost` plus the two Welle-1 enums exist in this file.** `SocialPostBoost`
// (Welle V1.1.2, rekursive Kommentargewichtung + Boosts), `SocialPostReport`/
// `SocialPostReportCategory`/`SocialPostReportStatus` (Welle V1.1.5, Moderation) and
// `SocialPostErasure` (Welle V1.1.5, DSGVO-Hard-Delete) are deliberately NOT modelled yet -- adding
// their enums/classes here before the corresponding tables/migrations exist would let this file
// drift ahead of `SocialNetworkSchemaDriftTest`'s own three-way comparison (model <-> migrated
// schema <-> hand-written Table object), which is exactly the class of bug that test exists to
// catch. Each later wave extends this file when its own migration lands, never before.
//
// **`root_id` is a denormalized, but structurally-immutable column** (S1 in the plan's open-decision
// table): a post never changes its parent after publication (Unveraenderlichkeit, see the
// Meritokratie concept doc), so `root_id` can never drift out of sync the way a cached *weight*
// would. It exists so a single Thread/Timeline read can filter `root_id = ?` / `root_id inList ...`
// instead of a recursive per-request tree walk -- see `SocialVisibility`/`SocialNetworkService`
// KDoc in `lapis-server/.../rpc/`. This is NOT a re-opening of the project's anti-denormalization
// stance against cached *weights* (see `WeightDecayClock`/`SocialPostWeight` KDoc): no weight value
// is ever persisted anywhere in this domain, only the immutable tree-shape pointer.
//
// **Post and comment share one table** (S2): a Welle-1 post always has `parentId = null`,
// `rootId = id`, `depth = 0`. Comments (Welle V1.1.2) reuse every column unchanged -- same author/
// content/weight/decay/visibility/state shape the concept document itself describes as "Child-Posts".
//
// **Sichtbarkeit is chosen once, at publication, and is never re-derived from an ancestor at read
// time** (S5, prepared for Welle V1.1.2): a comment inherits its root post's `visibility` at INSERT
// time (`SocialNetworkService.createPost` sets `visibility = rootId's own value` once comments
// exist) rather than joining through `root_id` on every read.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "SocialNetwork") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own cross-domain Member stub
    // (see e.g. 17-crowdfunding.kuml.kts).
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: SocialNetworkSchemaDriftTest asserts ErmDataType.Enum.values
    // in exactly this order, matching network.lapis.cloud.shared.domain.SocialPostVisibility.
    val socialPostVisibility = enumOf(name = "SocialPostVisibility") {
        literal(name = "PUBLIC") // Stufe 1 -- auch ohne Login (Welle V1.1.3 macht das erreichbar)
        literal(name = "MEMBERS_ONLY") // Stufe 2 -- nur ORGANIZATION_MEMBER
        literal(name = "MEMBERS_AND_EXTERNAL") // Stufe 3 -- + NON_MEMBER (GUEST/FRIEND)
    }

    // Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.SocialPostState.
    val socialPostState = enumOf(name = "SocialPostState") {
        literal(name = "VISIBLE")
        literal(name = "HIDDEN_BY_AUTHOR") // Autor-Selbstbedienung, irreversibel (S6)
        literal(name = "REMOVED_LEGAL") // BOARD/ADMIN, Pflicht-Begruendung -- Welle V1.1.5 schreibt diesen Wert erstmals, die Spalte existiert aber schon jetzt
    }

    val socialPost = classOf(name = "SocialPost") {
        stereotype("Entity") { "tableName" to "social_post"; "kotlinObjectName" to "SocialPostTable" }
        stereotype("Index") { "columns" to listOf("parent_id"); "name" to "idx_social_post_parent" }
        stereotype("Index") { "columns" to listOf("root_id"); "name" to "idx_social_post_root" }
        stereotype("Index") { "columns" to listOf("author_member_id"); "name" to "idx_social_post_author" }
        stereotype("Index") {
            "columns" to listOf("state", "visibility", "published_at")
            "name" to "idx_social_post_timeline"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> social_post (id), nullable (null for a Welle-1 root post). Plain «Column» UUID
        // attribute — a self-referencing UML association would work too, but the plain-Column shape
        // matches every other self-referencing/optional FK in this codebase (e.g.
        // crowdfunding_project.reviewed_by).
        attribute(name = "parentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "parent_id"; "fkEntity" to "SocialPost" }
        }
        // Real FK -> social_post (id), NOT NULL -- points at itself for a root post. See file header
        // "root_id is a denormalized, but structurally-immutable column".
        attribute(name = "rootId", type = "UUID") {
            stereotype("Column") { "columnName" to "root_id"; "fkEntity" to "SocialPost" }
        }
        // 0 for a root post, parent.depth + 1 for a comment. Capped at 64 (SocialPostWeight.MAX_DEPTH)
        // by the service layer, not a CHECK constraint value the model expresses directly here.
        attribute(name = "depth", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "depth" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — same idiom as
        // crowdfunding_project.submitter_member_id (association-to-FK naming would derive
        // "member_id", not this domain's "author_member_id").
        attribute(name = "authorMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "author_member_id"; "fkEntity" to "Member" }
        }
        // Plain text only in this and every wave through V1.1.3 (S7) -- no Markdown/HTML, no
        // `unsafe` rendering path exists anywhere near this column.
        attribute(name = "content", type = "String") {
            stereotype("Column") { "columnName" to "content"; "sqlType" to "TEXT" }
        }
        attribute(name = "visibility", type = socialPostVisibility) {
            stereotype("Column") {
                "columnName" to "visibility"
                "enumType" to "network.lapis.cloud.shared.domain.SocialPostVisibility"
            }
        }
        // Immutable once published (Unveraenderlichkeit nach Veroeffentlichung) -- the *current*,
        // decayed weight is never persisted, only computed on read (see WeightDecayClock/
        // SocialPostWeight KDoc), same "derive, don't cache" idiom `crowdfunding_project
        // .initial_weight_ltr` already established.
        attribute(name = "initialWeightLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "initial_weight_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "publishedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "published_at" }
        }
        attribute(name = "state", type = socialPostState) {
            defaultValue = "VISIBLE"
            stereotype("Column") {
                "columnName" to "state"
                "enumType" to "network.lapis.cloud.shared.domain.SocialPostState"
            }
        }
        attribute(name = "stateChangedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "state_changed_at" }
        }
        // Real FK -> member (id), nullable (null until hideOwnPost/removePostForLegalReason is called).
        attribute(name = "stateChangedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "state_changed_by"; "fkEntity" to "Member" }
        }
        // Populated by the Welle-V1.1.5 rechtliche-Entfernung path (BOARD/ADMIN, Pflichtfeld dort).
        // hideOwnPost (this wave) leaves it null -- the author's own reason, if any, is not modelled.
        attribute(name = "stateReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "state_reason"; "sqlType" to "VARCHAR(2000)" }
        }
    }
}
