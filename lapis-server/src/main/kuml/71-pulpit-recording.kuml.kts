// Welle V1.9.143 "Kanzel-Aufzeichnung im Begegnungsraum" -- see network.lapis.cloud.server.encounter.PulpitRecordingDocuments (the only reader and
// writer) and docs/features/pulpit-recording.adoc.
//
// **One marker table.** `pulpit_recording_document` marks a document of the Ablage as a pulpit recording. It deliberately has NO member column:
// only the space and the moment are stored, so the table is no trail of who stood at the pulpit. It is the basis of the HARD deletion (bytes and
// rows) and of the redirect inside DocumentService.deleteDocument. The table is organization data and therefore part of the admin backup.
//
// **FK-naming choice**: `document_id` / `space_id` are plain «Column» UUID attributes with «Column».fkEntity, never a UML association --
// the domain-wide policy 21-auction.kuml.kts documents. Minimal id-only stubs (owned by the Document and EncounterSpace models) resolve them.
//
// **time-fields:** `recorded_at` is class A (DbClock, UTC) -- see lapis-server/src/test/resources/time-fields.tsv.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PulpitRecording") {
    applyProfile(ermMappingProfile)

    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val encounterSpace = classOf(name = "EncounterSpace") {
        stereotype("Entity") { "tableName" to "encounter_space"; "kotlinObjectName" to "EncounterSpaceTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val pulpitRecordingDocument = classOf(name = "PulpitRecordingDocument") {
        stereotype("Entity") { "tableName" to "pulpit_recording_document"; "kotlinObjectName" to "PulpitRecordingDocumentTable" }
        stereotype("Index") { "columns" to listOf("space_id"); "name" to "idx_pulpit_recording_document_space" }

        attribute(name = "documentId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "spaceId", type = "UUID") {
            stereotype("Column") { "columnName" to "space_id"; "fkEntity" to "EncounterSpace" }
        }
        attribute(name = "recordedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }
}
