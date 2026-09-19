// Welle V1.6.1 "KI-Fundament + Pilot Satzungs-Q&A" -- see network.lapis.cloud.server.ai for the
// (optional, default-OFF) AI assistance layer these five tables back, and
// docs/architecture/ai-assistant.adoc for the architecture.
//
// **This file models exactly five new tables:**
//   * `ai_knowledge_release`     -- which documents an administrator (BOARD/ADMIN) released to the
//                                   AI knowledge base. Without a row here a document is never read.
//   * `ai_knowledge_index_state` -- per released document: PENDING/INDEXED/UNSUPPORTED_FORMAT/FAILED.
//   * `ai_knowledge_chunk`       -- the extracted, chunked text of a document version (retrieval unit).
//   * `ai_member_opt_in`         -- per-member, per-feature consent. Default is OFF (no row == off).
//   * `ai_call_audit`            -- one usage row per model call: hashes only, never clear text.
//
// **Why neither `ai_knowledge_chunk` nor `ai_knowledge_index_state` carries an `access_level`.** The
// document's access level is joined LIVE from `document` at retrieval time
// (network.lapis.cloud.server.ai.retrieval). A copied column would silently go stale when a
// document is re-classified (PUBLIC_MEMBERS -> BOARD_ONLY) and thereby bypass the check -- the
// exact failure mode this design excludes. The retriever additionally requires that a chunk belongs
// to the document's CURRENT version and that the document is not soft-deleted and still released.
//
// **Why no `tsvector`/GIN column here.** Neither parses on H2 MODE=PostgreSQL, which every test
// migrates against. The Postgres full-text index is an optional accelerator created at runtime by
// PostgresFullTextIndexInitializer (a missing index makes retrieval slower, never wrong).
//
// **Why `ai_member_opt_in` is a plain unique index on (member_id, feature)** and not a partial one:
// H2's PostgreSQL mode rejects partial unique indexes (same limitation 39-events/51-event-volunteer
// document).
//
// **DSGVO**: three of the five tables carry a member FK -- `ai_member_opt_in.member_id`,
// `ai_call_audit.member_id`, `ai_knowledge_release.released_by` -- and are covered by
// AiAssistantPersonalData: the opt-in rows are hard-deleted on erasure, the audit and release rows
// are retained but their member reference is nulled (retain-and-redact; both columns are therefore
// nullable). `ai_call_audit` stores only SHA-256 hashes of the (PII-redacted) question and the
// answer -- there is no clear text to redact. `ai_knowledge_chunk`/`ai_knowledge_index_state` carry
// no member FK: chunks are derivatives of document content, whose personal-data handling lives in
// DocumentPersonalData.
//
// Cross-domain stubs: minimal id-only Member, Document and DocumentVersion (owned by
// 00-foundation/02-document) -- same single-file-evaluation pattern every later domain file uses,
// purely so UmlToErmTransformer can resolve this file's FK columns.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "AiAssistant") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val documentVersion = classOf(name = "DocumentVersion") {
        stereotype("Entity") { "tableName" to "document_version"; "kotlinObjectName" to "DocumentVersionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val aiKnowledgeRelease = classOf(name = "AiKnowledgeRelease") {
        stereotype("Entity") { "tableName" to "ai_knowledge_release"; "kotlinObjectName" to "AiKnowledgeReleaseTable" }
        stereotype("Index") { "columns" to listOf("document_id"); "name" to "uq_ai_knowledge_release_document"; "unique" to true }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "documentId", type = "UUID") {
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        // Nullable: nulled on Art. 17 erasure of the releasing member (retain-and-redact).
        attribute(name = "releasedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "released_by"; "fkEntity" to "Member" }
        }
        attribute(name = "releasedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "released_at" }
        }
    }

    val aiKnowledgeIndexState = classOf(name = "AiKnowledgeIndexState") {
        stereotype("Entity") { "tableName" to "ai_knowledge_index_state"; "kotlinObjectName" to "AiKnowledgeIndexStateTable" }
        stereotype("Index") { "columns" to listOf("document_id"); "name" to "uq_ai_knowledge_index_state_document"; "unique" to true }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "documentId", type = "UUID") {
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "documentVersionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "document_version_id"; "fkEntity" to "DocumentVersion" }
        }
        // AiIndexStatus (lapis-shared): PENDING/INDEXED/UNSUPPORTED_FORMAT/FAILED; longest literal
        // UNSUPPORTED_FORMAT (18) -> VARCHAR(20).
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "chunkCount", type = "Int") {
            stereotype("Column") { "columnName" to "chunk_count" }
        }
        attribute(name = "indexedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "indexed_at" }
        }
        attribute(name = "failureCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "failure_code"; "sqlType" to "VARCHAR(40)" }
        }
    }

    val aiKnowledgeChunk = classOf(name = "AiKnowledgeChunk") {
        stereotype("Entity") { "tableName" to "ai_knowledge_chunk"; "kotlinObjectName" to "AiKnowledgeChunkTable" }
        stereotype("Index") { "columns" to listOf("document_id"); "name" to "idx_ai_knowledge_chunk_document" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "documentId", type = "UUID") {
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "documentVersionId", type = "UUID") {
            stereotype("Column") { "columnName" to "document_version_id"; "fkEntity" to "DocumentVersion" }
        }
        attribute(name = "chunkIndex", type = "Int") {
            stereotype("Column") { "columnName" to "chunk_index" }
        }
        attribute(name = "sectionLabel", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "section_label"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "pageNumber", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "page_number" }
        }
        attribute(name = "content", type = "String") {
            stereotype("Column") { "columnName" to "content"; "sqlType" to "TEXT" }
        }
        attribute(name = "charCount", type = "Int") {
            stereotype("Column") { "columnName" to "char_count" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val aiMemberOptIn = classOf(name = "AiMemberOptIn") {
        stereotype("Entity") { "tableName" to "ai_member_opt_in"; "kotlinObjectName" to "AiMemberOptInTable" }
        stereotype("Index") { "columns" to listOf("member_id", "feature"); "name" to "uq_ai_member_opt_in_member_feature"; "unique" to true }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // AiFeature (lapis-shared), currently only STATUTE_QA.
        attribute(name = "feature", type = "String") {
            stereotype("Column") { "columnName" to "feature"; "sqlType" to "VARCHAR(30)" }
        }
        attribute(name = "enabled", type = "Boolean") {
            stereotype("Column") { "columnName" to "enabled" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }

    val aiCallAudit = classOf(name = "AiCallAudit") {
        stereotype("Entity") { "tableName" to "ai_call_audit"; "kotlinObjectName" to "AiCallAuditTable" }
        stereotype("Index") { "columns" to listOf("occurred_at"); "name" to "idx_ai_call_audit_occurred_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        // Nullable: nulled on Art. 17 erasure (retain-and-redact), see file header "DSGVO".
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "agentType", type = "String") {
            stereotype("Column") { "columnName" to "agent_type"; "sqlType" to "VARCHAR(40)" }
        }
        attribute(name = "provider", type = "String") {
            stereotype("Column") { "columnName" to "provider"; "sqlType" to "VARCHAR(30)" }
        }
        attribute(name = "model", type = "String") {
            stereotype("Column") { "columnName" to "model"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "inputHash", type = "String") {
            stereotype("Column") { "columnName" to "input_hash"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "outputHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "output_hash"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "tokensIn", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tokens_in" }
        }
        attribute(name = "tokensOut", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tokens_out" }
        }
        attribute(name = "toolsCalled", type = "String") {
            stereotype("Column") { "columnName" to "tools_called"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "outcome", type = "String") {
            stereotype("Column") { "columnName" to "outcome"; "sqlType" to "VARCHAR(30)" }
        }
        attribute(name = "retrievedChunkCount", type = "Int") {
            stereotype("Column") { "columnName" to "retrieved_chunk_count" }
        }
    }
}
