package network.lapis.cloud.server.documents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.9.1, fix round (B2) -- the TOCTOU blocker. The "a document is never more visible than its
 * folder" invariant is enforced in application code, not by a database constraint, so the only thing
 * that makes it hold under concurrency is a real row lock taken BEFORE the deciding read. This spec
 * pins two independent halves of that:
 *
 * 1. **The lock is real and exclusive** -- a second transaction cannot acquire it while the first
 *    holds it (verified against the real database, not asserted about the source text).
 * 2. **Every write path actually goes through a locking entry point** -- a source-text guard, same
 *    idiom [network.lapis.cloud.server.rpc.AuditLogImmutabilityTest] uses for the append-only rule.
 *    This is the half that regresses silently: a future wave adding a fifth write path would
 *    re-open the race with nothing else catching it, since the race itself is not reproducible on
 *    demand.
 */
class FolderAccessLevelLockingTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        fun seedFolder(
            name: String,
            parentFolderId: Uuid? = null,
            level: DocumentAccessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                DocumentFolderTable.insert {
                    it[DocumentFolderTable.id] = id
                    it[DocumentFolderTable.name] = name
                    it[DocumentFolderTable.parentFolderId] = parentFolderId
                    it[DocumentFolderTable.accessLevel] = level
                }
            }
            return id
        }

        test("lockFolders takes a genuinely exclusive row lock -- a second transaction cannot acquire it concurrently") {
            val folderId = seedFolder("T-B2 Sperrordner")
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val holder =
                    executor.submit {
                        transaction {
                            FolderAccessLevels.lockFolders(listOf(folderId))
                            locked.countDown()
                            // Hold the lock while the contender below tries to take it.
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                locked.await(10, TimeUnit.SECONDS) shouldBe true

                val contender =
                    executor.submit<Boolean> {
                        // H2's LOCK_TIMEOUT (and Postgres' lock wait) makes this either throw or block
                        // past our own bound; both mean "the lock is exclusive". A silent success would
                        // mean lockFolders is not actually locking anything -- the failure mode this
                        // whole test exists for.
                        runCatching { transaction { FolderAccessLevels.lockFolders(listOf(folderId)) } }.isSuccess
                    }
                val contenderSucceeded = runCatching { contender.get(3, TimeUnit.SECONDS) }.getOrDefault(false)
                contenderSucceeded shouldBe false

                release.countDown()
                holder.get(10, TimeUnit.SECONDS)

                // Once released, the very same lock is acquirable -- the block above was contention, not a bug.
                transaction { FolderAccessLevels.lockFolders(listOf(folderId)) }
            } finally {
                executor.shutdownNow()
                transaction { DocumentFolderTable.deleteWhere { DocumentFolderTable.id eq folderId } }
            }
        }

        test("lockFolders orders its lock acquisitions by ascending UUID, whatever order the caller passes") {
            // Ordered locking is the ONLY thing making the scheme deadlock-free across differently shaped
            // lock sets (an ancestor chain in createDocument, a whole subtree in setFolderAccessLevel). The
            // ordering lives in lockFolders, so it is pinned here rather than at each call site.
            val ids = List(6) { Uuid.random() }.sortedBy { it.toString() }
            FolderAccessLevels.lockOrderOf(ids.reversed()) shouldBe ids
            FolderAccessLevels.lockOrderOf(ids.shuffled()) shouldBe ids
            // Duplicates collapse -- an ancestor chain plus a subtree overlaps at the root.
            FolderAccessLevels.lockOrderOf(ids + ids) shouldBe ids
        }

        // Renamed in the polish round (P6): this used to be called "a parent tighten must contend with a
        // child insert", which promised a contention scenario the body never sets up -- it asserts the
        // SHAPE of the lock set (the chain includes the parent, and lockOrderOf sorts it), nothing about
        // two transactions racing. The exclusivity half is the first test above; the name now says which
        // half this one is, so a reader does not take it for a concurrency proof it never was.
        test("the ancestor chain of a nested folder includes the parent, and lockOrderOf sorts that chain by UUID") {
            val parentId = seedFolder("T-B2 Eltern")
            val childId = seedFolder("T-B2 Kind", parentFolderId = parentId)
            try {
                val chain = transaction { FolderAccessLevels.snapshot().ancestorChain(childId) }
                chain shouldBe listOf(childId, parentId)
                FolderAccessLevels.lockOrderOf(chain) shouldBe listOf(childId, parentId).sortedBy { it.toString() }
            } finally {
                transaction {
                    DocumentFolderTable.deleteWhere { DocumentFolderTable.id eq childId }
                    DocumentFolderTable.deleteWhere { DocumentFolderTable.id eq parentId }
                }
            }
        }

        test("source guard: every write path that reads a folder level does so through a locking entry point") {
            // The race is not reproducible on demand, so this is what protects it long-term. A new write
            // path that calls the unlocked convenience `FolderAccessLevels.effectiveLevel(` turns this red.
            val sources =
                listOf(
                    "lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc/DocumentService.kt",
                    "lapis-server/src/main/kotlin/network/lapis/cloud/server/routes/DocumentArchiving.kt",
                ).associateWith { File(repoRoot(), it).readText() }

            sources.forEach { (path, _) -> File(repoRoot(), path).exists() shouldBe true }

            val documentService = sources.values.first()
            val archiving = sources.values.last()

            // createFolder / createDocument / setDocumentAccessLevel: one lockChainAndSnapshot each.
            countOccurrences(text = documentService, needle = "FolderAccessLevels.lockChainAndSnapshot(") shouldBe 3
            // setFolderAccessLevel locks the subtree as well, because it writes into it.
            countOccurrences(text = documentService, needle = "FolderAccessLevels.lockSubtreeAndSnapshot(") shouldBe 1
            // archiveGeneratedBytes + archiveGeneratedFile.
            countOccurrences(text = archiving, needle = "FolderAccessLevels.lockChainAndSnapshot(") shouldBe 2

            // The unlocked convenience is only used on genuinely read-only paths (listDocuments), never
            // where a decision is written. One call, and it is that one.
            countOccurrences(text = documentService, needle = "FolderAccessLevels.effectiveLevel(") shouldBe 1
            countOccurrences(text = archiving, needle = "FolderAccessLevels.effectiveLevel(") shouldBe 0
        }

        test("source guard: setFolderAccessLevel performs every UPDATE before its first AuditLogRecorder.record") {
            // AuditLogRecorder.record takes the application's single global chain-state row lock and
            // requires being the LAST lock-taking operation of its transaction (fix round, B3). Pinned
            // structurally: inside setFolderAccessLevel's body, the last `.update({` must precede the
            // first `AuditLogRecorder.record(`.
            // Polish round (P7): bounded at the method's own closing brace instead of running to END OF
            // FILE. The old `substring(start)` form happened to pass only because nothing below this
            // method contains `.update({`; the first helper added down there would have turned an
            // unrelated line into a failure of this assertion, i.e. exactly the "a test reports
            // something other than what it checks" class this round exists to close.
            val text = File(repoRoot(), "lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc/DocumentService.kt").readText()
            val start = text.indexOf("override suspend fun setFolderAccessLevel(")
            start shouldBe text.lastIndexOf("override suspend fun setFolderAccessLevel(")
            val body = declarationBody(text = text, startIndex = start)
            val lastUpdate = body.lastIndexOf(".update({")
            val firstRecord = body.indexOf("AuditLogRecorder.record(")
            // Both must actually be present in the bounded slice -- otherwise `in 1..<firstRecord` would
            // pass vacuously on a slice that accidentally cut the method short.
            lastUpdate shouldBeGreaterThan 0
            firstRecord shouldBeGreaterThan 0
            (lastUpdate < firstRecord) shouldBe true
        }
    })

/**
 * The source text of the declaration starting at [startIndex], ending at the brace that closes its
 * body -- a plain balanced-brace scan from its first `{`. Good enough here because the method it is
 * used on contains no brace inside a string literal or comment; a future one that does would need a
 * real parser rather than a wider slice.
 */
private fun declarationBody(
    text: String,
    startIndex: Int,
): String {
    val firstBrace = text.indexOf('{', startIndex)
    require(firstBrace > 0) { "no body brace after index $startIndex" }
    var depth = 0
    for (i in firstBrace..<text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(startIndex, i + 1)
            }
        }
    }
    error("unbalanced braces after index $startIndex")
}

/** Walks up from the working directory to the repository root (the directory holding `settings.gradle.kts`). */
private fun repoRoot(): File {
    var candidate: File? = File(".").absoluteFile
    while (candidate != null) {
        if (File(candidate, "settings.gradle.kts").exists()) return candidate
        candidate = candidate.parentFile
    }
    error("repository root (settings.gradle.kts) not found above ${File(".").absolutePath}")
}

private fun countOccurrences(
    text: String,
    needle: String,
): Int = text.split(needle).size - 1
