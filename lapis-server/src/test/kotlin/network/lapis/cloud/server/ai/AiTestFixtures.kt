package network.lapis.cloud.server.ai

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.ai.audit.AiCallAuditEntry
import network.lapis.cloud.server.ai.audit.AiCallAuditSink
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.llm.LlmClient
import network.lapis.cloud.server.ai.llm.LlmRequest
import network.lapis.cloud.server.ai.llm.LlmResult
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AiCallAuditTable
import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.server.db.generated.AiKnowledgeIndexStateTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.AiMemberOptInTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

/** A fully configured [AiConfig] for tests -- built through the real [AiConfig.load] parser. */
internal fun operationalAiConfig(extra: Map<String, String> = emptyMap()): AiConfig {
    val env =
        mapOf(
            AiConfig.ENV_ENABLED to "true",
            AiConfig.ENV_PROVIDER to "anthropic",
            AiConfig.ENV_MODEL to "test-model",
            AiConfig.ENV_API_KEY to "sk-test-secret-value",
        ) + extra
    return AiConfig.load { env[it] }
}

/** Programmable [LlmClient]; counts calls and remembers the last request. **Never touches the network.** */
internal class FakeLlmClient(
    private val responder: (LlmRequest) -> LlmResult,
) : LlmClient {
    var callCount = 0
        private set
    var lastRequest: LlmRequest? = null
        private set

    override suspend fun complete(request: LlmRequest): LlmResult {
        callCount++
        lastRequest = request
        return responder(request)
    }
}

internal class RecordingAuditSink : AiCallAuditSink {
    val entries = mutableListOf<AiCallAuditEntry>()

    override fun record(entry: AiCallAuditEntry) {
        entries += entry
    }
}

internal class TestDocument(
    val id: Uuid,
    val versionId: Uuid,
    val title: String,
)

/**
 * Creates and cleans up members, documents, versions, releases and chunks for AI tests. Every
 * row is tracked and removed by [cleanup] in FK-safe order.
 */
internal class AiTestFixtures {
    val storageRoot: File =
        kotlin.io.path
            .createTempDirectory("ai-test-storage")
            .toFile()
    private val memberIds = mutableListOf<Uuid>()
    private val documentIds = mutableListOf<Uuid>()
    private val folderIds = mutableListOf<Uuid>()

    fun member(
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "AiTest Mitglied"
                it[email] = "aitest-${Uuid.random()}@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
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

    /** Creates folder + document + version 1. [fileBytes] (if given) is written to the storage root. */
    fun document(
        title: String = "AiTest-Satzung ${Uuid.random()}",
        level: DocumentAccessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
        author: Uuid = member(role = AccountRole.ADMIN),
        fileBytes: ByteArray? = null,
        mimeType: String = "text/plain",
        fileName: String = "satzung.txt",
    ): TestDocument {
        val folderId = Uuid.random()
        val documentId = Uuid.random()
        val versionId = Uuid.random()
        val storageKey = "$documentId/$versionId.bin"
        if (fileBytes != null) {
            val target = File(storageRoot, storageKey)
            target.parentFile.mkdirs()
            target.writeBytes(fileBytes)
        }
        val now = DbClock.nowLocalDateTime()
        transaction {
            DocumentFolderTable.insert {
                it[id] = folderId
                it[name] = "AiTest-Ordner"
                it[parentFolderId] = null
            }
            DocumentTable.insert {
                it[id] = documentId
                it[DocumentTable.folderId] = folderId
                it[DocumentTable.title] = title
                it[DocumentTable.createdBy] = author
                it[createdAt] = now
                it[accessLevel] = level
                it[isDeleted] = false
            }
            insertVersion(
                documentId = documentId,
                versionId = versionId,
                number = 1,
                storageKey = storageKey,
                mimeType = mimeType,
                fileName = fileName,
                uploadedBy = author,
            )
            DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }
        }
        folderIds += folderId
        documentIds += documentId
        return TestDocument(id = documentId, versionId = versionId, title = title)
    }

    /** Adds a further version and makes it current. */
    fun newVersion(
        document: TestDocument,
        number: Int,
        fileBytes: ByteArray? = null,
        mimeType: String = "text/plain",
        fileName: String = "satzung.txt",
        uploadedBy: Uuid,
    ): Uuid {
        val versionId = Uuid.random()
        val storageKey = "${document.id}/$versionId.bin"
        if (fileBytes != null) {
            val target = File(storageRoot, storageKey)
            target.parentFile.mkdirs()
            target.writeBytes(fileBytes)
        }
        transaction {
            insertVersion(
                documentId = document.id,
                versionId = versionId,
                number = number,
                storageKey = storageKey,
                mimeType = mimeType,
                fileName = fileName,
                uploadedBy = uploadedBy,
            )
            DocumentTable.update({ DocumentTable.id eq document.id }) { it[currentVersionId] = versionId }
        }
        return versionId
    }

    private fun insertVersion(
        documentId: Uuid,
        versionId: Uuid,
        number: Int,
        storageKey: String,
        mimeType: String,
        fileName: String,
        uploadedBy: Uuid,
    ) {
        DocumentVersionTable.insert {
            it[id] = versionId
            it[DocumentVersionTable.documentId] = documentId
            it[versionNumber] = number
            it[DocumentVersionTable.fileName] = fileName
            it[DocumentVersionTable.mimeType] = mimeType
            it[fileSizeBytes] = 1L
            it[DocumentVersionTable.storageKey] = storageKey
            it[checksumSha256] = "0".repeat(64)
            it[DocumentVersionTable.uploadedBy] = uploadedBy
            it[uploadedAt] = DbClock.nowLocalDateTime()
            it[changeNote] = null
        }
    }

    fun setAccessLevel(
        document: TestDocument,
        level: DocumentAccessLevel,
    ) {
        transaction { DocumentTable.update({ DocumentTable.id eq document.id }) { it[accessLevel] = level } }
    }

    fun softDelete(document: TestDocument) {
        transaction { DocumentTable.update({ DocumentTable.id eq document.id }) { it[isDeleted] = true } }
    }

    fun release(
        document: TestDocument,
        by: Uuid,
    ) {
        transaction {
            AiKnowledgeReleaseTable.insert {
                it[id] = Uuid.random()
                it[documentId] = document.id
                it[releasedBy] = by
                it[releasedAt] = DbClock.nowLocalDateTime()
            }
        }
    }

    /** Inserts one chunk directly, bypassing extraction. */
    fun chunk(
        document: TestDocument,
        text: String,
        versionId: Uuid = document.versionId,
        index: Int = 0,
        label: String? = null,
        page: Int? = null,
    ) {
        transaction {
            AiKnowledgeChunkTable.insert {
                it[id] = Uuid.random()
                it[documentId] = document.id
                it[documentVersionId] = versionId
                it[chunkIndex] = index
                it[sectionLabel] = label
                it[pageNumber] = page
                it[content] = text
                it[charCount] = text.length
                it[createdAt] = DbClock.nowLocalDateTime()
            }
        }
    }

    fun cleanup() {
        transaction {
            if (documentIds.isNotEmpty()) {
                AiKnowledgeChunkTable.deleteWhere { documentId inList documentIds }
                AiKnowledgeIndexStateTable.deleteWhere { documentId inList documentIds }
                AiKnowledgeReleaseTable.deleteWhere { documentId inList documentIds }
                DocumentVersionTable.deleteWhere { documentId inList documentIds }
                DocumentTable.deleteWhere { id inList documentIds }
            }
            if (folderIds.isNotEmpty()) DocumentFolderTable.deleteWhere { id inList folderIds }
            if (memberIds.isNotEmpty()) {
                AiCallAuditTable.deleteWhere { memberId inList memberIds }
                AiMemberOptInTable.deleteWhere { memberId inList memberIds }
                AiKnowledgeReleaseTable.deleteWhere { releasedBy inList memberIds }
                AccountTable.deleteWhere { memberId inList memberIds }
                MemberTable.deleteWhere { id inList memberIds }
            }
        }
        memberIds.clear()
        documentIds.clear()
        folderIds.clear()
        storageRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Final cleanup: also removes the storage root itself. */
    fun dispose() {
        cleanup()
        storageRoot.deleteRecursively()
    }
}
