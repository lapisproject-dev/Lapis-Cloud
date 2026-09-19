package network.lapis.cloud.server.ai.kb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.server.db.generated.AiKnowledgeIndexStateTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AiIndexStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class KnowledgeIndexerTest :
    FunSpec({
        val fixtures = AiTestFixtures()
        val indexer = KnowledgeIndexer(storageRoot = fixtures.storageRoot)

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { fixtures.dispose() }

        fun chunkCount(documentId: Uuid): Int =
            transaction {
                AiKnowledgeChunkTable
                    .selectAll()
                    .where {
                        AiKnowledgeChunkTable.documentId eq
                            documentId
                    }.count()
                    .toInt()
            }

        fun entryOf(documentId: Uuid) = KnowledgeReleaseStore.entryFor(documentId = documentId, visibleLevels = DocumentAccessLevel.entries)

        fun stateOf(documentId: Uuid): AiIndexStatus? =
            transaction {
                AiKnowledgeIndexStateTable
                    .selectAll()
                    .where {
                        AiKnowledgeIndexStateTable.documentId eq documentId
                    }.singleOrNull()
                    ?.get(AiKnowledgeIndexStateTable.status)
            }

        val text = "§ 1 Name\nDer Verein heißt Beispielverein.\n\n§ 2 Zweck\nDer Verein fördert die Bildung.".toByteArray()

        test("an unreleased document is never indexed") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.NOT_RELEASED
            chunkCount(doc.id) shouldBe 0
            stateOf(doc.id) shouldBe null
        }

        test("releasing then indexing a text document persists chunks and an INDEXED state") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            stateOf(doc.id) shouldBe AiIndexStatus.PENDING
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.INDEXED
            (chunkCount(doc.id) >= 1) shouldBe true
            stateOf(doc.id) shouldBe AiIndexStatus.INDEXED
            entryOf(doc.id)!!.status shouldBe AiIndexStatus.INDEXED
        }

        test("listScope reports the current version number of released documents only") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            val other = fixtures.document(author = admin, fileBytes = text) // never released
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.INDEXED
            val scope = KnowledgeReleaseStore.listScope(allowedLevels = listOf(DocumentAccessLevel.PUBLIC_MEMBERS))
            val item = scope.indexed.single { it.documentTitle == doc.title }
            item.versionNumber shouldBe 1
            (scope.indexed + scope.unindexed).none { it.documentTitle == other.title } shouldBe true
        }

        test("release is idempotent") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            KnowledgeReleaseStore.isReleased(doc.id) shouldBe true
        }

        test("re-indexing replaces the chunks without duplicates") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id)
            val first = chunkCount(doc.id)
            indexer.indexDocument(doc.id)
            chunkCount(doc.id) shouldBe first
        }

        test("a new version marks the index stale; re-indexing replaces all chunks with the new version's") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id)
            val newVersion =
                fixtures.newVersion(
                    document = doc,
                    number = 2,
                    fileBytes = "§ 9 Neu\nGanz neuer Text der Version zwei.".toByteArray(),
                    uploadedBy = admin,
                )
            KnowledgeIndexer.markStale(doc.id)
            stateOf(doc.id) shouldBe AiIndexStatus.PENDING
            // The read model reports a stale index as PENDING, never INDEXED.
            entryOf(doc.id)!!.status shouldBe AiIndexStatus.PENDING
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.INDEXED
            transaction {
                AiKnowledgeChunkTable.selectAll().where { AiKnowledgeChunkTable.documentId eq doc.id }.all {
                    it[AiKnowledgeChunkTable.documentVersionId] ==
                        newVersion
                }
            } shouldBe true
        }

        test("markStale is a no-op for a document that was never released") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeIndexer.markStale(doc.id)
            stateOf(doc.id) shouldBe null
        }

        test("revoking the release removes chunks, index state and release row") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id)
            KnowledgeReleaseStore.revoke(doc.id)
            chunkCount(doc.id) shouldBe 0
            stateOf(doc.id) shouldBe null
            KnowledgeReleaseStore.isReleased(doc.id) shouldBe false
        }

        test("a soft-deleted document is purged on indexing") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id)
            fixtures.softDelete(doc)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.NOT_RELEASED
            chunkCount(doc.id) shouldBe 0
        }

        test("an unsupported format yields UNSUPPORTED_FORMAT and zero chunks") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc =
                fixtures.document(
                    author = admin,
                    fileBytes = byteArrayOf(1, 2, 3),
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    fileName = "satzung.docx",
                )
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.UNSUPPORTED_FORMAT
            chunkCount(doc.id) shouldBe 0
            entryOf(doc.id)!!.status shouldBe AiIndexStatus.UNSUPPORTED_FORMAT
        }

        test("a corrupt PDF yields FAILED, not UNSUPPORTED_FORMAT") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc =
                fixtures.document(
                    author = admin,
                    fileBytes = "kein pdf".toByteArray(),
                    mimeType = "application/pdf",
                    fileName = "satzung.pdf",
                )
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.FAILED
            chunkCount(doc.id) shouldBe 0
        }

        test("a real multi-page PDF is indexed with page numbers on the chunks") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc =
                fixtures.document(
                    author = admin,
                    fileBytes =
                        network.lapis.cloud.server.ai.kb
                            .pdfBytes(listOf("Seite eins Mitgliedsbeitrag", "Seite zwei Vorstand")),
                    mimeType = "application/pdf",
                    fileName = "satzung.pdf",
                )
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.INDEXED
            val pages =
                transaction {
                    AiKnowledgeChunkTable
                        .selectAll()
                        .where {
                            AiKnowledgeChunkTable.documentId eq doc.id
                        }.map { it[AiKnowledgeChunkTable.pageNumber] }
                }
            (pages.filterNotNull().isNotEmpty()) shouldBe true
        }

        test("a storage key that escapes the storage root is rejected") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, fileBytes = text)
            transaction {
                DocumentVersionTable.update({ DocumentVersionTable.id eq doc.versionId }) {
                    it[storageKey] =
                        "../../../../etc/hostname"
                }
            }
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.FAILED
            chunkCount(doc.id) shouldBe 0
        }

        test("a released document without any version stays PENDING") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin)
            transaction {
                network.lapis.cloud.server.db.generated.DocumentTable.update({
                    network.lapis.cloud.server.db.generated.DocumentTable.id eq
                        doc.id
                }) { it[currentVersionId] = null }
            }
            KnowledgeReleaseStore.release(documentId = doc.id, releasedBy = admin)
            indexer.indexDocument(doc.id) shouldBe AiIndexStatus.PENDING
        }

        test("the scope read model splits indexed from not indexable documents with a reason code") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val good = fixtures.document(author = admin, fileBytes = text)
            val bad = fixtures.document(author = admin, fileBytes = byteArrayOf(1), mimeType = "application/zip", fileName = "x.zip")
            listOf(good, bad).forEach { KnowledgeReleaseStore.release(documentId = it.id, releasedBy = admin) }
            indexer.indexDocument(good.id)
            indexer.indexDocument(bad.id)
            val scope = KnowledgeReleaseStore.listScope(listOf(network.lapis.cloud.shared.domain.DocumentAccessLevel.PUBLIC_MEMBERS))
            scope.indexed.any { it.documentTitle == good.title && it.versionNumber == 1 } shouldBe true
            scope.unindexed.single { it.documentTitle == bad.title }.unindexedReason shouldBe "UNSUPPORTED_FORMAT"
        }
    })
