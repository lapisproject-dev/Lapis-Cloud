package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.SocialPostReportTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** DSA Art. 16 Abs. 2 lit. a -- Pflichtfeld, Spaltenbreite `social_post_report.description`. */
private const val MAX_DESCRIPTION_LENGTH = 4_000

/** Spaltenbreite `social_post_report.reporter_contact`. */
private const val MAX_CONTACT_LENGTH = 320

/**
 * Welle V1.1.5, Teil 4.1 -- die geteilte Kernlogik hinter BEIDEN Meldewegen (DSA Art. 16):
 * [ISocialNetworkService.reportPost] (authentifizierter RPC-Pfad, jede lesbare Sichtbarkeitsstufe)
 * UND `POST /s/{id}/report` (öffentlicher, kontenloser HTML-Formular-Pfad, nur `PUBLIC`-Posts) --
 * nach dem Vorbild von [SocialVisibility]/`SocialReadPipeline`: "die einzige Stelle, an der diese
 * Frage beantwortet wird". Muss in der bereits offenen Transaktion des Aufrufers laufen.
 *
 * **Zwei Einstiegspunkte, bewusst unterschiedliches Fehlerverhalten**, weil die beiden Aufrufer
 * unterschiedliche Antwortformen brauchen:
 * - [submitAuthenticated] wirft `ConflictException` bei ungültiger Eingabe (Pflichtfeld-Verletzung)
 *   -- der RPC-Aufrufer bekommt einen Fehler-Toast, das ist hier kein Existenz-Orakel (die
 *   Feldvalidierung hängt nicht vom Post ab).
 * - [submitPublic] wirft NIE -- der öffentliche Formular-Handler liefert IMMER dieselbe
 *   Bestätigungsseite (Plan § 4.3: "Antwort immer identisch ... ob die Meldung gespeichert wurde,
 *   ob der Post existiert, ob das Honeypot-Feld ausgefüllt war"). Eine strukturell ungültige
 *   Eingabe (leere Pflichtfelder, keine Gutgläubigkeitserklärung) wird dort schlicht NICHT
 *   gespeichert, ohne dass der anonyme Absender das am Antwortverhalten ablesen könnte.
 *
 * Beide teilen sich [insertIfEligible] -- die eigentliche Enumeration-Härtung (Post existiert,
 * lesbar, nicht der eigene Autor) lebt genau EINMAL dort.
 */
internal object SocialReportSubmission {
    /** Authentifizierter RPC-Pfad -- wirft bei ungültiger Eingabe. */
    fun submitAuthenticated(
        postId: Uuid,
        category: SocialPostReportCategory,
        description: String,
        reporterContact: String?,
        goodFaithConfirmed: Boolean,
        reporterMemberId: Uuid,
        readableCondition: Op<Boolean>,
        now: LocalDateTime,
    ) {
        requireValid(description = description, goodFaithConfirmed = goodFaithConfirmed, contact = reporterContact)
        insertIfEligible(
            postId = postId,
            category = category,
            description = description,
            reporterContact = reporterContact,
            goodFaithConfirmed = goodFaithConfirmed,
            reporterMemberId = reporterMemberId,
            readableCondition = readableCondition,
            now = now,
        )
    }

    /**
     * Öffentlicher, unauthentifizierter Pfad (`POST /s/{id}/report`) -- wirft NIE. Ungültige Werte
     * (leere `description`, `goodFaithConfirmed = false`, ein Category-String, der sich nicht
     * parsen lässt) führen zu einem stillen No-Op, nicht zu einer Exception -- der Aufrufer
     * (`SocialPublicRoutes`) zeigt in JEDEM Fall dieselbe Bestätigungsseite.
     */
    fun submitPublic(
        postId: Uuid,
        category: SocialPostReportCategory?,
        description: String,
        reporterContact: String?,
        goodFaithConfirmed: Boolean,
        now: LocalDateTime,
    ) {
        if (category == null) return
        if (description.isBlank() || description.length > MAX_DESCRIPTION_LENGTH) return
        if (!goodFaithConfirmed) return
        if (reporterContact != null && reporterContact.length > MAX_CONTACT_LENGTH) return
        insertIfEligible(
            postId = postId,
            category = category,
            description = description,
            reporterContact = reporterContact,
            goodFaithConfirmed = goodFaithConfirmed,
            // reporterMemberId = null -- anonyme oeffentliche Meldung, per Definition dieses Pfads.
            reporterMemberId = null,
            readableCondition = SocialVisibility.publicReadableCondition(),
            now = now,
        )
    }

    private fun requireValid(
        description: String,
        goodFaithConfirmed: Boolean,
        contact: String?,
    ) {
        if (description.isBlank()) throw ConflictException("description must not be blank")
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            throw ConflictException("description exceeds the maximum length of $MAX_DESCRIPTION_LENGTH characters")
        }
        if (!goodFaithConfirmed) {
            throw ConflictException("goodFaithConfirmed must be true (DSA Art. 16 Abs. 2 lit. d)")
        }
        if (contact != null && contact.length > MAX_CONTACT_LENGTH) {
            throw ConflictException("reporterContact exceeds the maximum length of $MAX_CONTACT_LENGTH characters")
        }
    }

    /**
     * Enumeration-Härtung (wichtigste Designentscheidung dieses Objekts): nur wenn [readableCondition]
     * die Zeile findet, wird eine Report-Zeile geschrieben; andernfalls stiller No-Op -- ohne das
     * wäre eine Meldung ein perfektes Existenz-Orakel für `MEMBERS_ONLY`-Posts. Der Autor darf
     * seinen eigenen Post nicht melden -- ebenfalls stiller No-Op (nicht `ConflictException`, sonst
     * wieder ein Signal-Unterschied).
     */
    private fun insertIfEligible(
        postId: Uuid,
        category: SocialPostReportCategory,
        description: String,
        reporterContact: String?,
        goodFaithConfirmed: Boolean,
        reporterMemberId: Uuid?,
        readableCondition: Op<Boolean>,
        now: LocalDateTime,
    ) {
        val row =
            SocialPostTable
                .selectAll()
                .where { (SocialPostTable.id eq postId) and readableCondition }
                .singleOrNull() ?: return
        if (reporterMemberId != null && row[SocialPostTable.authorMemberId] == reporterMemberId) return
        SocialPostReportTable.insert {
            it[id] = Uuid.random()
            it[SocialPostReportTable.postId] = postId
            it[reportedAt] = now
            it[SocialPostReportTable.reporterMemberId] = reporterMemberId
            it[SocialPostReportTable.reporterContact] = reporterContact
            it[SocialPostReportTable.category] = category
            it[SocialPostReportTable.description] = description
            it[SocialPostReportTable.goodFaithConfirmed] = goodFaithConfirmed
            it[status] = SocialPostReportStatus.OPEN
        }
    }
}
