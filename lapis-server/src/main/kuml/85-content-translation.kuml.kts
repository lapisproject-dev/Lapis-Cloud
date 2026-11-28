// Content translation domain -- content_translation (V177__content_translation.sql).
//
// Welle V1.9.342 "Uebersetzung oeffentlicher Inhalte" (core, events only, off by default). A cache of machine translations: one row per
// (content, field, SHA-256 of the exact source text, source language, target language). A changed source text has a new hash, so a stale row is
// never served; it is deleted by the hooks in EventStore.
//
// DECISIONS THAT SHAPE THE TABLE -- do not "repair" any of them:
//   * composite PRIMARY KEY over all six key columns (every one of them marked Id below).
//   * content_id has NO foreign key: the column is polymorphic (later auctions and carpool hints). Explicit delete hooks plus a tripwire test
//     (TranslationStructureTest) take the place of the constraint.
//   * Read and written ONLY by network.lapis.cloud.server.translation.ContentTranslationStore.
//   * NOT part of a backup (OrganizationSchemaCatalog.EXCLUDED_TABLES): a refillable cache that must not resurrect translations whose consent
//     has been revoked in the meantime.
//   * NOT expressible in the ERM profile and therefore only documented here (see V177): the CHECKs chk_content_translation_type,
//     chk_content_translation_field and chk_content_translation_lang, and the index idx_content_translation_content (declared below but, like
//     every index, "not emitted" into the Exposed object).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ContentTranslation") {
    applyProfile(ermMappingProfile)

    classOf(name = "ContentTranslation") {
        stereotype("Entity") { "tableName" to "content_translation"; "kotlinObjectName" to "ContentTranslationTable" }
        stereotype("Index") { "columns" to listOf("content_type", "content_id"); "name" to "idx_content_translation_content" }

        attribute(name = "contentType", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "content_type"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "contentId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "content_id" }
        }
        attribute(name = "field", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "field"; "sqlType" to "VARCHAR(24)" }
        }
        attribute(name = "sourceSha256", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "source_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "sourceLang", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "source_lang"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "targetLang", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "target_lang"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "translatedText", type = "String") {
            stereotype("Column") { "columnName" to "translated_text"; "sqlType" to "VARCHAR(20000)" }
        }
        attribute(name = "modelProfile", type = "String") {
            stereotype("Column") { "columnName" to "model_profile"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}
