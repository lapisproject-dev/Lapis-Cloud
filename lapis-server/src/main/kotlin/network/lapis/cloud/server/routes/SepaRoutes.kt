package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.payment.sepa.SepaConfig
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * Security Round 1 (2026-08-20, MAJOR-1): narrowed from `FINANCIAL_DOC_ROLES`
 * (TREASURER/BOARD/ADMIN) -- deliberately this route's OWN, file-private constant, never shared
 * with [network.lapis.cloud.server.routes.registerMailmergeRoutes]'s identically-named-but-separate
 * `FINANCIAL_DOC_ROLES`, so narrowing here cannot affect that route's own, legitimate BOARD-level
 * Beitragsrechnung/Spendenbescheinigung access. This route serves every debited member's FULL,
 * PLAINTEXT IBAN -- the ONLY place in this entire wave that does (the RPC surface elsewhere only
 * ever exposes `debtorIbanLast4`, see `ISepaService` KDoc rule 5). A BOARD member has no treasury
 * function and must not be able to download it -- matches this codebase's own
 * `network.lapis.cloud.server.security.canAccessDocumentAtLevel` KDoc ("BOARD must NOT collapse
 * into ADMIN-level access"), which the underlying `Document` row's own `DocumentAccessLevel.ADMIN_ONLY`
 * already encodes (see `SepaService.generateBatchFile`) -- this route is the SANCTIONED path for
 * TREASURER (who cannot otherwise read an `ADMIN_ONLY` document, see that KDoc's "gap" note) to
 * reach it, not a deliberately wider bypass for BOARD too.
 */
private val SEPA_FILE_DOWNLOAD_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/** Outcome of resolving a batch id to its (possibly absent) archived pain.008 file -- see [registerSepaRoutes]. */
private data class SepaFileLookup(
    val status: SepaDebitBatchStatus,
    val messageId: String,
    val storageKey: String?,
)

/**
 * V1.2.2 "SEPA-Lastschriftmandate". Binary route for the generated pain.008 file -- mirrors
 * [registerMailmergeRoutes]'s own idiom for binary payloads over plain Ktor (JSON-RPC is the wrong
 * shape for a multi-kilobyte XML file).
 *
 * **Access.** [SEPA_FILE_DOWNLOAD_ROLES] (TREASURER/ADMIN only, see that constant's own KDoc for the
 * Security Round 1 MAJOR-1 narrowing from the original, too-wide TREASURER/BOARD/ADMIN). Also
 * rejects (404) a soft-deleted document and (409) a CANCELLED batch -- see the route body's own
 * comments (MAJOR-1/MINOR-1).
 *
 * **Encryption at rest (Security Round 1, 2026-08-20, MAJOR-2).** The archived file is
 * [SecretBox]-sealed, exactly like `sepa_mandate.debtor_iban_ciphertext` -- see
 * `SepaService.generateBatchFile` KDoc "Phase 2" and [network.lapis.cloud.server.dsgvo.PaymentsPersonalData]
 * KDoc "Security Round 1 MAJOR-2" for the full retention-gap reasoning this closes. This route is
 * therefore the ONLY place the plaintext pain.008 bytes are ever reconstructed outside
 * `generateBatchFile` itself, and does so purely in memory, never writing the decrypted bytes back
 * to disk.
 *
 * ## NIT-5 (preventive note for a future maintainer)
 *
 * `sepa_debit_batch` has TWO separate foreign keys into `document`: `generated_document_id` and
 * `prenotification_document_id`. A naive `SepaDebitBatchTable innerJoin DocumentTable` (or any
 * other bare Exposed join across these two tables) throws Exposed's "multiple primary key <-> foreign
 * key references" `IllegalStateException` at RUNTIME, not at compile time -- this exact bug pattern
 * has already hit this wave TWICE elsewhere (`SepaBatchPoller`'s own join and
 * `SepaService.listMyPrenotifications`, both already fixed by naming the join column explicitly via
 * `.join(Table, JoinType.INNER, col1, col2)`). If a future wave (e.g. V1.2.3's planned
 * prenotification-PDF route) needs to join through `prenotification_document_id` instead of
 * `generated_document_id`, disambiguate the same way.
 */
fun Route.registerSepaRoutes(
    documentStorageRoot: File,
    sepaConfig: SepaConfig,
) {
    get("/api/sepa/batches/{batchId}/file.xml") {
        val batchId = call.parameters["batchId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (batchId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid batchId")
            return@get
        }
        val current = resolveCurrentMember(call)
        current.requireRole(*SEPA_FILE_DOWNLOAD_ROLES)

        val lookup =
            transaction {
                val batchRow =
                    SepaDebitBatchTable.selectAll().where { SepaDebitBatchTable.id eq batchId }.singleOrNull() ?: return@transaction null
                val status = batchRow[SepaDebitBatchTable.status]
                val messageId = batchRow[SepaDebitBatchTable.messageId]
                // MINOR-1 (Security Round 1, 2026-08-20): cancelBatch resets a GENERATED batch's
                // contributions to OPEN, but does not itself soft-delete the archived document --
                // guarded here on the batch's own status instead, so a cancelled batch's file cannot
                // be accidentally re-uploaded (double-debit risk against a run that was explicitly
                // called off).
                if (status == SepaDebitBatchStatus.CANCELLED) {
                    return@transaction SepaFileLookup(status = status, messageId = messageId, storageKey = null)
                }
                val storageKey =
                    batchRow[SepaDebitBatchTable.generatedDocumentId]?.let { documentId ->
                        val documentRow = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
                        // MAJOR-1 (Security Round 1, 2026-08-20): mirrors DocumentRoutes' own
                        // isDeleted check -- a soft-deleted document (e.g. via SepaService
                        // .revokeMandate's reset-after-revocation, MAJOR-3) must 404, not silently
                        // keep serving a now-stale file.
                        if (documentRow == null || documentRow[DocumentTable.isDeleted]) {
                            null
                        } else {
                            documentRow[DocumentTable.currentVersionId]?.let { versionId ->
                                DocumentVersionTable
                                    .selectAll()
                                    .where { DocumentVersionTable.id eq versionId }
                                    .singleOrNull()
                                    ?.get(DocumentVersionTable.storageKey)
                            }
                        }
                    }
                SepaFileLookup(status = status, messageId = messageId, storageKey = storageKey)
            }
        if (lookup == null) {
            call.respond(HttpStatusCode.NotFound, "Lauf nicht gefunden.")
            return@get
        }
        if (lookup.status == SepaDebitBatchStatus.CANCELLED) {
            call.respond(HttpStatusCode.Conflict, "Dieser Lauf wurde storniert -- die Datei ist nicht mehr abrufbar.")
            return@get
        }
        val storageKey = lookup.storageKey
        if (storageKey == null) {
            call.respond(HttpStatusCode.NotFound, "No generated file for this batch")
            return@get
        }
        val file = documentStorageRoot.resolve(storageKey)
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "File not found on disk")
            return@get
        }

        val encryptionKey = sepaConfig.secretEncryptionKey
        if (encryptionKey == null) {
            call.respond(
                HttpStatusCode.Conflict,
                "LAPIS_SECRET_ENCRYPTION_KEY ist nicht gesetzt -- die archivierte Datei kann nicht entschluesselt werden.",
            )
            return@get
        }
        val sealed = file.readText(Charsets.UTF_8)
        val plaintextXml =
            try {
                SecretBox(encryptionKey).open(sealed = sealed, aad = batchId.toString())
            } catch (e: SecretBoxException) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Entschluesselung fehlgeschlagen.")
                return@get
            }
        val bytes = plaintextXml.toByteArray(Charsets.UTF_8)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(
                    ContentDisposition.Parameters.FileName,
                    "sepa-lastschrift-${lookup.messageId}.xml",
                ).toString(),
        )
        call.respondBytes(bytes = bytes, contentType = ContentType.Application.Xml)
    }
}
