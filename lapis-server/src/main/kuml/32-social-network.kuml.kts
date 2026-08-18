// Soziales Netzwerk domain, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum,
// Boosts, rekursive Gesamtgewichtung" + Welle V1.1.3 "Oeffentlicher SEO-Lesepfad" -- see the concept
// documents ("03 Bereiche/Lapis Cloud/Soziales Netzwerk.md" and "03 Bereiche/Lapis Cloud/
// Meritokratisches System und Libertaler.md", "Im sozialen Netz -- Gewichtung von Posts" section,
// vault) for the full fachlich specification this implements, plus the accompanying implementation
// plans ("V1.1 Soziales Netzwerk", its "V1.1.2"-delta plan, and the "V1.1.3"-delta plan, all
// 2026-08-18) for the researched code-reuse basis.
//
// **Welle V1.1.3 adds NO new class/enum/attribute here** -- the public read path
// (`network.lapis.cloud.server.routes.SocialPublicRoutes`, `GET /s`/`GET /s/{id}`/`GET
// /sitemap.xml`/`GET /robots.txt`) reads through the EXISTING `SocialPost` table and the EXISTING
// `SocialPostVisibility.PUBLIC`/`SocialPostState.VISIBLE` values -- see the `content` attribute's
// own comment below for the one load-bearing consequence of this wave (content now reaches an
// unauthenticated HTML response body).
//
// **`SocialPost` plus the two Welle-1 enums plus `SocialPostBoost` (Welle V1.1.2) exist in this
// file.** `SocialPostReport`/`SocialPostReportCategory`/`SocialPostReportStatus` (Welle V1.1.5,
// Moderation) and `SocialPostErasure` (Welle V1.1.5, DSGVO-Hard-Delete) are deliberately NOT
// modelled yet -- adding their enums/classes here before the corresponding tables/migrations exist
// would let this file drift ahead of `SocialNetworkSchemaDriftTest`'s own three-way comparison
// (model <-> migrated schema <-> hand-written Table object), which is exactly the class of bug that
// test exists to catch. Each later wave extends this file when its own migration lands, never
// before.
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
// `rootId = id`, `depth = 0`. Comments (Welle V1.1.2, `SocialNetworkService.createComment`) reuse
// every column unchanged -- same author/content/weight/decay/visibility/state shape the concept
// document itself describes as "Child-Posts". `social_post` itself is NOT altered by V1.1.2 -- the
// `parent_id`/`root_id`/`depth` columns already existed since V4 and are simply populated with
// non-default values for the first time; the best evidence the V4 "post and comment in one table"
// decision (S2) actually holds up.
//
// **Sichtbarkeit is chosen once, at publication, and is never re-derived from an ancestor at read
// time** (S5, UMGESETZT seit Welle V1.1.2): a comment inherits its ROOT post's `visibility` at
// INSERT time (`SocialNetworkService.createComment` reads the root row's `visibility` and copies it
// onto the new comment row) rather than joining through `root_id` on every read. Deliberately the
// ROOT post's visibility, not the direct parent's -- in a consistent data set the two are identical
// once this invariant is enforced at every write, but reading from `root_id` is the single place
// the invariant is established and cannot silently propagate a once-introduced inconsistent row
// further down the tree.
//
// **S4 (Welle V1.1.2)**: a `SocialPostBoost` decays from its OWN `boosted_at`, never from the
// boosted post's `published_at` -- otherwise a boost on a 100-day-old post would be economically
// near-worthless (0.9^100 ~= 3e-5 of its value) the instant it is cast, defeating the entire point
// of "boost a post you still find valuable, however old it is".
//
// **S3 (Welle V1.1.2)**: `social_post_boost` deliberately carries NO `UNIQUE(post_id, member_id)`
// constraint. A boost is a genuine LTR payment, exactly like `crowdfunding_reaction`'s Like/Dislike
// is NOT a payment (that table DOES enforce one reaction per member per project) -- two payments
// from the same member on the same post are two payments, not a toggle. Accidental rapid double-
// submission is guarded in the service layer instead (`SocialPostWeight.BOOST_DUPLICATE_WINDOW`,
// see `SocialNetworkService.boostPost` KDoc "E6") -- a real DB constraint would also outlaw a
// legitimate second boost from the same member the following day.
//
// **E3 (Welle V1.1.2)**: a `HIDDEN_BY_AUTHOR`/`REMOVED_LEGAL` comment KEEPS its weight in its
// parent's/every ancestor's aggregated total -- economic history and read-time visibility are
// deliberately separate concerns (see `SocialPostWeight.totalWeightsUnrounded`/`.suppressedIds`
// KDoc); excluding a hidden node's weight would destroy weight OTHER members contributed by
// replying to it, and would contradict this domain's "no refund, ever" posture.
//
// **Kein Cascade-Write beim Unsichtbarmachen (K2, Welle V1.1.2)**: `hideOwnPost` still only ever
// writes its own row (unchanged since Welle V1.1.1) -- suppressing a hidden subtree from `getThread`
// happens entirely at READ time, via `SocialPostWeight.suppressedIds` walking the already-loaded
// subtree top-down by `depth`. A cascading `UPDATE` across a node's descendants would (a) falsely
// attribute another author's `state_changed_by` to the ancestor's author, (b) be unboundedly large
// (a 5000-node thread = 5000 rows in one transaction, contending with `lockForDebit` locks), and
// (c) not be cleanly reversible if this posture is ever revisited. See `SocialNetworkService
// .hideOwnPost` KDoc for the full reasoning this file header only summarizes.
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
        literal(name = "PUBLIC") // Stufe 1 -- auch ohne Login erreichbar, seit Welle V1.1.3 tatsaechlich ueber GET /s, /s/{id} (SocialPublicRoutes)
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
        // Plain text only, still true as of Welle V1.1.3 (S7) -- no Markdown/HTML at write time.
        // FORTGESCHRIEBEN (Welle V1.1.3): ab dieser Welle landet der Inhalt roh in einem
        // unauthentifizierten HTML-Response-Body (SocialPublicRoutes/SocialPublicHtml, GET /s,
        // /s/{id}) -- die vorherige Aussage "kein Rendering-Pfad in der Naehe dieser Spalte" ist
        // NICHT mehr wahr. Sicherheit ruht jetzt auf kotlinx.html's Textknoten-/Attribut-Escaping
        // (SocialPublicHtml.kt Datei-Header) PLUS einem Quelltext-Scan-Test, der die
        // Escape-umgehende kotlinx.html-API aus SocialPublicHtml.kt/SocialPublicRoutes.kt fernhaelt
        // (SocialPublicHtmlTest "T6"). Wer hier weiterhin "kein Rendering-Pfad" liest, uebersieht
        // die groesste Sicherheits-Aenderung dieser Welle.
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

    // Welle V1.1.2 -- monetary "Like". Deliberately its own table, not a column on SocialPost: a
    // post can be boosted arbitrarily many times (S3), each one its own decaying contribution (S4),
    // so a single row per boost is the only shape that can hold that.
    val socialPostBoost = classOf(name = "SocialPostBoost") {
        stereotype("Entity") { "tableName" to "social_post_boost"; "kotlinObjectName" to "SocialPostBoostTable" }
        stereotype("Index") { "columns" to listOf("post_id"); "name" to "idx_social_post_boost_post" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_social_post_boost_member" }
        stereotype("Index") {
            "columns" to listOf("post_id", "member_id", "boosted_at")
            "name" to "idx_social_post_boost_dup"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> social_post (id), NOT NULL.
        attribute(name = "postId", type = "UUID") {
            stereotype("Column") { "columnName" to "post_id"; "fkEntity" to "SocialPost" }
        }
        // Real FK -> member (id), NOT NULL.
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "amountLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Own decay anchor (S4) -- deliberately NOT the boosted post's publishedAt.
        attribute(name = "boostedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "boosted_at" }
        }
    }
}
