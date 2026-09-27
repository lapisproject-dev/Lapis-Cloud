package network.lapis.cloud.server.documents

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.moreRestrictive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar" -- the wirksame
 * (effective) [DocumentAccessLevel] of a [network.lapis.cloud.server.db.generated.DocumentFolderTable]
 * row is the MOST restrictive level along its own ancestor chain (its own level, or any ancestor's,
 * whichever is stricter). Since the fix round, a folder tighten MATERIALIZES that into every
 * descendant folder's OWN level as well (see
 * [network.lapis.cloud.server.rpc.DocumentService.setFolderAccessLevel]), so in a settled database
 * every folder's own level is AT LEAST as restrictive as what its ancestor chain enforces -- never
 * looser, possibly stricter, because nothing ever clamps a descendant DOWN to what its ancestors
 * merely permit. That is what makes [Snapshot.effectiveLevel] collapse to the row's own level for a
 * materialized row; it is deliberately NOT the stronger claim "a descendant's level mirrors its
 * parent's". The ancestor climb here stays as the safety net that makes a not-yet-materialized or
 * manually inserted row harmless rather than a leak. See
 * `docs/architecture/document-access.adoc` for the full model this implements.
 *
 * Every function here MUST be called from inside an already-open Exposed `transaction {}` block
 * (same convention `network.lapis.cloud.server.audit.AuditLogRecorder.record` enforces) -- none of
 * them opens its own transaction.
 *
 * `document_folder.parent_folder_id` deliberately carries NO foreign key (self-referential
 * association, `UmlToErmTransformer` skips those -- see `02-document.kuml.kts`'s file header), so a
 * cycle is not prevented at the schema level. Everything here is therefore cycle-safe by
 * construction (a visited-set, never re-descending into an id already seen) and FAILS CLOSED to
 * [DocumentAccessLevel.ADMIN_ONLY] the moment a cycle, a dangling reference, or an implausible
 * chain depth is detected -- "I don't know" must never resolve to "public", the direction every
 * other ambiguity in this codebase's access checks already resolves.
 *
 * **Locking contract (fix round, TOCTOU)**: the "a document is never more visible than its folder"
 * invariant is enforced in application code, not by a database constraint, so every write path that
 * READS a folder level in order to DECIDE something must first take a row lock on that folder and
 * its ancestors -- otherwise, under READ COMMITTED, a concurrent
 * [network.lapis.cloud.server.rpc.DocumentService.setFolderAccessLevel] tighten and a concurrent
 * insert can interleave into a permanently too-visible document (the cascade only runs on a level
 * CHANGE, so nothing ever repairs it afterwards). [lockChainAndSnapshot] is that entry point;
 * [lockFolders] is the primitive. **Every** caller locks in ASCENDING folder-UUID order, which is
 * what makes the scheme deadlock-free across differently-shaped lock sets (an ancestor chain, a
 * whole subtree): any two transactions acquire the rows they have in common in the same relative
 * order, so no lock-wait cycle can form. The global order across tables is
 * `document_folder` rows -> `document` rows -> `audit_log_chain_state` (the last is
 * `AuditLogRecorder`'s own "must be the last lock of the transaction" contract).
 *
 * Computing a lock set from an UNLOCKED read is sound for an ANCESTOR CHAIN because
 * `parent_folder_id` is immutable after insert: there is no re-parent operation anywhere in this
 * codebase, so an existing folder's ancestor chain cannot change underneath the snapshot. A
 * concurrently inserted folder can only appear as a new CHILD, and a child cannot loosen anything
 * (its own insert path takes the same locks and clamps itself).
 *
 * It is NOT sound for a SUBTREE, which is why [lockSubtreeAndSnapshot] re-reads and verifies instead
 * of trusting its pre-lock read (polish round, P1). A subtree is defined by a predicate ("every
 * descendant of X"), and a row lock cannot cover a row that does not exist yet: a `createFolder`
 * committing between the pre-lock read and the moment this transaction actually acquires X's own row
 * produces a descendant that is in the post-lock snapshot but was never in the ordered lock set.
 * Updating it anyway -- the cascade's natural behaviour -- acquires a lock OUT of ascending order
 * and re-opens exactly the lock-wait cycle the ordering exists to prevent (A holds the parent and
 * waits for the new child; a `createDocument` in that child holds the child, in ascending order, and
 * waits for the parent). [lockSubtreeAndSnapshot] therefore detects the discrepancy and makes its
 * caller retry the whole transaction with the enlarged lock set, so no acquisition is ever made out
 * of order and the "deadlock-free" claim above stays literally true.
 *
 * Why that terminates and why the verification is complete: EVERY path that touches a folder locks
 * that folder's whole ancestor chain, so once this transaction holds X's row, no concurrent
 * transaction can insert (or write) anywhere below X -- it would have to acquire X first. The subtree
 * is therefore frozen for the remainder of the transaction the moment one re-read agrees with the
 * lock set, and the only rows a retry can discover are those committed before X was acquired.
 */
internal object FolderAccessLevels {
    /**
     * Depth cap for the ancestor climb -- guards against a manually/test-inserted cycle in
     * `parent_folder_id` (no FK enforces acyclicity) turning into an infinite loop. 64 is generously
     * above any plausible real folder nesting depth.
     */
    const val MAX_DEPTH = 64

    internal data class FolderRow(
        val id: Uuid,
        val parentFolderId: Uuid?,
        val accessLevel: DocumentAccessLevel,
    )

    /**
     * One read of the whole `document_folder` table, reused for every question asked of it within a
     * single transaction (fix round, W5: the public entry points used to each re-read the table --
     * `setFolderAccessLevel` did so up to three times per call).
     *
     * A [Snapshot] is an immutable value: [withOwnLevel] returns a NEW snapshot rather than mutating
     * this one, which is how `setFolderAccessLevel` reasons about "the subtree as it will be after
     * my own UPDATE" without a second round trip to the database.
     */
    class Snapshot internal constructor(
        private val rows: Map<Uuid, FolderRow>,
    ) {
        /** Whether [folderId] exists at all -- lets callers report a genuine 404 instead of failing closed into one. */
        fun contains(folderId: Uuid): Boolean = folderId in rows

        /** [folderId]'s OWN level (not the effective one), or `null` when it does not exist. */
        fun ownLevel(folderId: Uuid): DocumentAccessLevel? = rows[folderId]?.accessLevel

        /** [folderId]'s parent, or `null` when it is top-level or does not exist. */
        fun parentFolderId(folderId: Uuid): Uuid? = rows[folderId]?.parentFolderId

        /** This snapshot with [folderId]'s own level replaced by [level] -- see the class KDoc. */
        fun withOwnLevel(
            folderId: Uuid,
            level: DocumentAccessLevel,
        ): Snapshot {
            val existing = rows[folderId] ?: return this
            return Snapshot(rows + (folderId to existing.copy(accessLevel = level)))
        }

        /**
         * [folderId] itself plus every ancestor above it, nearest first. Cycle-safe (stops at the
         * first id already seen) and depth-capped. A [folderId] that does not exist yields just
         * itself, so a caller can pass the result straight to [lockFolders] without a special case.
         */
        fun ancestorChain(folderId: Uuid): List<Uuid> {
            val chain = mutableListOf(folderId)
            val visited = mutableSetOf(folderId)
            var parentId = rows[folderId]?.parentFolderId
            var depth = 0
            while (parentId != null && depth < MAX_DEPTH) {
                if (!visited.add(parentId)) break
                chain += parentId
                parentId = rows[parentId]?.parentFolderId
                depth++
            }
            return chain
        }

        /** [rootId] plus every folder below it. Cycle-safe. Empty when [rootId] does not exist. */
        fun subtreeIds(rootId: Uuid): List<Uuid> {
            if (rootId !in rows) return emptyList()
            val result = mutableListOf(rootId)
            val visited = mutableSetOf(rootId)
            val queue = ArrayDeque<Uuid>()
            queue.add(rootId)
            while (queue.isNotEmpty()) {
                for (childId in childrenOf[queue.removeFirst()].orEmpty()) {
                    if (!visited.add(childId)) continue
                    result += childId
                    queue.add(childId)
                }
            }
            return result
        }

        private val childrenOf: Map<Uuid, List<Uuid>> by lazy {
            rows.values
                .filter { it.parentFolderId != null }
                .groupBy({ it.parentFolderId!! }, { it.id })
        }

        /** The effective [DocumentAccessLevel] of [folderId], inclusive of its own ancestor chain. */
        fun effectiveLevel(folderId: Uuid): DocumentAccessLevel {
            val start =
                rows[folderId] ?: run {
                    logger.warn { "FolderAccessLevels.effectiveLevel: folder $folderId not found -- failing closed to ADMIN_ONLY" }
                    return DocumentAccessLevel.ADMIN_ONLY
                }
            var level = start.accessLevel
            var parentId = start.parentFolderId
            val visited = mutableSetOf(folderId)
            var depth = 0
            while (parentId != null) {
                depth++
                if (depth > MAX_DEPTH || parentId in visited) {
                    logger.warn {
                        "FolderAccessLevels.effectiveLevel: cycle or excessive depth (> $MAX_DEPTH) in " +
                            "document_folder.parent_folder_id ancestor chain starting at $folderId -- " +
                            "failing closed to ADMIN_ONLY"
                    }
                    return DocumentAccessLevel.ADMIN_ONLY
                }
                visited += parentId
                val parent = rows[parentId] ?: break // dangling parent_folder_id (no FK) -- keep what we accumulated
                level = moreRestrictive(a = level, b = parent.accessLevel)
                parentId = parent.parentFolderId
            }
            return level
        }

        /**
         * The effective [DocumentAccessLevel] of every folder in the subtree rooted at [rootId]
         * (inclusive of [rootId] itself), walked top-down (BFS) so each descendant's effective level
         * is simply `moreRestrictive(itsOwnLevel, itsParent'sAlreadyComputedEffectiveLevel)`.
         * [rootId]'s own entry already accounts for ITS ancestor chain above [rootId], so a folder
         * whose ancestors above [rootId] are stricter than [rootId]'s own level is still handled
         * correctly. Cycle-safe. Empty map when [rootId] itself does not exist.
         */
        fun subtreeEffectiveLevels(rootId: Uuid): Map<Uuid, DocumentAccessLevel> {
            if (rootId !in rows) return emptyMap()
            val result = mutableMapOf(rootId to effectiveLevel(rootId))
            val visited = mutableSetOf(rootId)
            val queue = ArrayDeque<Uuid>()
            queue.add(rootId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val currentLevel = result.getValue(current)
                for (childId in childrenOf[current].orEmpty()) {
                    if (!visited.add(childId)) continue // cycle guard: never re-descend into an id already seen
                    result[childId] = moreRestrictive(a = rows.getValue(childId).accessLevel, b = currentLevel)
                    queue.add(childId)
                }
            }
            return result
        }

        /**
         * The effective [DocumentAccessLevel] of EVERY `document_folder` row in one pass -- used by
         * [network.lapis.cloud.server.rpc.DocumentService.listFolders], which must filter the whole
         * folder listing without issuing one ancestor-climb per folder. One combined BFS from every
         * root (a folder with no `parentFolderId`, or a dangling one); every folder is visited at
         * most once in total across all roots. A folder unreachable from any root (i.e. entirely
         * inside a cycle) fails closed to [DocumentAccessLevel.ADMIN_ONLY], logged, same posture as
         * [effectiveLevel]'s own cycle guard.
         */
        fun effectiveLevelsForAll(): Map<Uuid, DocumentAccessLevel> {
            val result = mutableMapOf<Uuid, DocumentAccessLevel>()
            val visited = mutableSetOf<Uuid>()
            val roots = rows.values.filter { it.parentFolderId == null || it.parentFolderId !in rows }
            for (root in roots) {
                if (!visited.add(root.id)) continue
                result[root.id] = root.accessLevel
                val queue = ArrayDeque<Uuid>()
                queue.add(root.id)
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val currentLevel = result.getValue(current)
                    for (childId in childrenOf[current].orEmpty()) {
                        if (!visited.add(childId)) continue
                        result[childId] = moreRestrictive(a = rows.getValue(childId).accessLevel, b = currentLevel)
                        queue.add(childId)
                    }
                }
            }
            // Any row not reached from a root is part of a cycle with no acyclic path up -- fail closed.
            for (row in rows.values) {
                if (row.id !in visited) {
                    logger.warn {
                        "FolderAccessLevels.effectiveLevelsForAll: folder ${row.id} unreachable from any " +
                            "root (cycle in document_folder.parent_folder_id) -- failing closed to ADMIN_ONLY"
                    }
                    result[row.id] = DocumentAccessLevel.ADMIN_ONLY
                }
            }
            return result
        }
    }

    /** Reads every `document_folder` row once. Callers must never query per-folder in a loop. */
    fun snapshot(): Snapshot =
        Snapshot(
            DocumentFolderTable
                .selectAll()
                .associate { row ->
                    row[DocumentFolderTable.id] to
                        FolderRow(
                            id = row[DocumentFolderTable.id],
                            parentFolderId = row[DocumentFolderTable.parentFolderId],
                            accessLevel = row[DocumentFolderTable.accessLevel],
                        )
                },
        )

    /**
     * Convenience for the common read-only single question. A write path that will DECIDE something
     * from the answer must use [lockChainAndSnapshot] instead -- see the class KDoc's locking
     * contract.
     */
    fun effectiveLevel(folderId: Uuid): DocumentAccessLevel = snapshot().effectiveLevel(folderId)

    /**
     * Takes a `SELECT ... FOR UPDATE` row lock on each of [folderIds], in ascending UUID order --
     * see the class KDoc's locking contract for why the order is load-bearing.
     *
     * One statement per id rather than a single `WHERE id IN (...) ORDER BY id FOR UPDATE`.
     * Corrected in the polish round (P2): an earlier revision of this comment justified that with
     * "PostgreSQL locks rows in the order its scan node produces them, which an `ORDER BY` does not
     * determine", which is not true as stated -- in the usual plan the `LockRows` node sits ABOVE the
     * `Sort`, so the rows are in fact locked in `ORDER BY` order. The real reason to keep one
     * statement per id is that the batched form would make the ordering a property of the chosen
     * PLAN rather than of this code: a plan that satisfies the ordering from an index and drops the
     * sort, a parallel or bitmap path, or EvalPlanQual re-fetching and re-locking a row that changed
     * under a concurrent update, can each produce a different acquisition order, and none of that is
     * visible in review. The per-id form is ordered by construction, on Postgres and on the H2
     * database the tests run against alike. Chains and subtrees are small (single digits in practice,
     * [MAX_DEPTH] as the hard cap on a chain), so the extra round trips are cheap next to making
     * deadlock freedom depend on a query plan.
     *
     * Ids that do not exist are simply not locked (no error) -- a caller may pass the ancestor chain
     * of a folder it has not yet verified exists.
     */
    fun lockFolders(folderIds: Collection<Uuid>) {
        lockOrderOf(folderIds).forEach { id ->
            DocumentFolderTable
                .selectAll()
                .where { DocumentFolderTable.id eq id }
                .forUpdate()
                .singleOrNull()
        }
    }

    /**
     * The order [lockFolders] acquires its locks in: de-duplicated, ascending by UUID string (which is
     * lower-case hex and therefore orders identically to the big-endian byte order). Extracted purely
     * so the ordering -- the single property that makes the whole locking scheme deadlock-free -- is
     * directly assertable in [network.lapis.cloud.server.documents.FolderAccessLevelLockingTest]
     * rather than only inferrable from behaviour under contention.
     */
    fun lockOrderOf(folderIds: Collection<Uuid>): List<Uuid> = folderIds.distinct().sortedBy { it.toString() }

    /**
     * Locks [folderId] and its whole ancestor chain, then returns a FRESH snapshot taken after the
     * locks are held -- the entry point every write path that decides something from a folder level
     * must use ([network.lapis.cloud.server.rpc.DocumentService.createFolder]/`createDocument`/
     * `setDocumentAccessLevel`, and the archiving pipeline in
     * `network.lapis.cloud.server.routes.DocumentArchiving`). Reading the chain from the pre-lock
     * snapshot is sound because `parent_folder_id` is immutable after insert -- see the class KDoc.
     */
    fun lockChainAndSnapshot(folderId: Uuid): Snapshot {
        lockFolders(snapshot().ancestorChain(folderId))
        return snapshot()
    }

    /**
     * Raised by [lockSubtreeAndSnapshot] when the subtree grew between its pre-lock read and the
     * moment the locks were actually held -- a concurrent `createFolder` committed a descendant that
     * is therefore NOT covered by the ordered lock set. The caller must roll its transaction back and
     * retry (see [network.lapis.cloud.server.rpc.DocumentService.setFolderAccessLevel]); silently
     * cascading into the unlocked row would acquire a lock out of ascending order, and skipping it
     * would leave a document that the racing writer inserted under the old, looser level permanently
     * too visible. Deliberately not a [network.lapis.cloud.shared.rpc.ConflictException]: this is an
     * internal retry signal, never an answer to a client.
     */
    class SubtreeGrewUnderLockException(
        val folderId: Uuid,
        val unlockedDescendantIds: Set<Uuid>,
    ) : RuntimeException(
            "document_folder subtree of $folderId gained ${unlockedDescendantIds.size} descendant(s) " +
                "between the pre-lock read and the lock acquisition -- transaction must retry",
        )

    /**
     * Locks [folderId]'s ancestor chain AND its whole subtree, then returns a fresh snapshot --
     * [network.lapis.cloud.server.rpc.DocumentService.setFolderAccessLevel]'s entry point, which
     * writes into descendant folders as well as reading ancestors, and must therefore hold locks on
     * both directions before it decides anything.
     *
     * Throws [SubtreeGrewUnderLockException] when the post-lock subtree contains a folder the lock
     * set did not -- see that exception and the class KDoc's "It is NOT sound for a SUBTREE"
     * paragraph. **Considered and rejected** as the alternative fix: ordering all folder locks
     * root-first (by depth, then UUID) instead of by UUID alone, which would make a
     * late-discovered descendant safe to lock in place, since every path to it must acquire its
     * ancestors first. Rejected because depth is only well defined on an acyclic
     * `parent_folder_id` -- which nothing enforces, this whole file exists to survive a cycle -- so
     * two transactions could disagree about the order and deadlock for real, whereas UUID order is
     * total and robust regardless of the data.
     */
    fun lockSubtreeAndSnapshot(folderId: Uuid): Snapshot {
        val pre = snapshot()
        val locked = lockOrderOf(pre.ancestorChain(folderId) + pre.subtreeIds(folderId))
        lockFolders(locked)
        val post = snapshot()
        val unlocked = post.subtreeIds(folderId).toSet() - locked.toSet()
        if (unlocked.isNotEmpty()) {
            logger.info {
                "FolderAccessLevels.lockSubtreeAndSnapshot: subtree of $folderId grew by " +
                    "${unlocked.size} folder(s) under lock acquisition -- signalling a transaction retry"
            }
            throw SubtreeGrewUnderLockException(folderId = folderId, unlockedDescendantIds = unlocked)
        }
        return post
    }
}
