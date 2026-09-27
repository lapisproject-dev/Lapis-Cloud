package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentAccessLevel { PUBLIC_MEMBERS, BOARD_ONLY, ADMIN_ONLY }

/**
 * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar". `0` = am
 * weitesten sichtbar, `2` = am engsten. Reine Ordnungszahl für Vergleiche
 * (`FolderAccessLevels.effectiveLevel`, [moreRestrictive]) -- keine eigenständige
 * Berechtigungsentscheidung, die bleibt ausschließlich bei
 * `network.lapis.cloud.server.security.canAccessDocumentAtLevel`. Bewusst als `val`-Extension mit
 * `when`, nicht als Enum-Konstruktor-Parameter: das Enum ist `@Serializable` und wird per Name
 * serialisiert, ein zusätzliches Feld wäre eine unnötige Formänderung der Wire-Repräsentation.
 */
val DocumentAccessLevel.restrictiveness: Int
    get() =
        when (this) {
            DocumentAccessLevel.PUBLIC_MEMBERS -> 0
            DocumentAccessLevel.BOARD_ONLY -> 1
            DocumentAccessLevel.ADMIN_ONLY -> 2
        }

/** Die restriktivere der beiden Stufen -- Kern der "nur verschärfen, nie lockern"-Invariante dieser Welle. */
fun moreRestrictive(
    a: DocumentAccessLevel,
    b: DocumentAccessLevel,
): DocumentAccessLevel = if (a.restrictiveness >= b.restrictiveness) a else b

@Serializable
data class DocumentFolderDto(
    val id: String,
    val name: String,
    val parentFolderId: String?,
    val documentCount: Int,
    /**
     * Welle V1.9.1 -- die EIGENE Zugriffsstufe dieses Ordners. Eine Verschärfung eines Elternordners
     * materialisiert sich seit der Fix-Runde (B5) in die eigenen Stufen ALLER Nachfahren-Ordner
     * (`setFolderAccessLevel`), deshalb ist die eigene Stufe in einer eingeschwungenen Datenbank
     * tatsächlich auch die wirksame Stufe -- und dieses Feld damit eine wahrheitsgemäße Grundlage für
     * das "Sichtbarkeit"-Abzeichen der Oberfläche. Vorher konnte ein Unterordner formal
     * `PUBLIC_MEMBERS` bleiben, obwohl er wegen eines `ADMIN_ONLY`-Elternteils faktisch
     * `ADMIN_ONLY` war, und das Abzeichen zeigte das Gegenteil der Wahrheit.
     *
     * Die Autorität bleibt beim Server: `FolderAccessLevels.effectiveLevel` klettert bei JEDEM
     * Zugriff weiterhin die Ahnenkette hoch (Sicherheitsnetz für eine noch nicht materialisierte
     * oder von Hand eingefügte Zeile), und ein für den Aufrufer unsichtbarer Ordner erscheint in
     * `listFolders` überhaupt nicht -- deshalb gibt es weiterhin kein separates
     * `effectiveAccessLevel`-Feld.
     */
    val accessLevel: DocumentAccessLevel,
)

/** Ergebnis von [network.lapis.cloud.shared.rpc.IDocumentService.setFolderAccessLevel]. */
@Serializable
data class FolderAccessLevelChangeDto(
    val folder: DocumentFolderDto,
    /** Zahl der Dokumente im Teilbaum, deren Stufe durch die Verschärfung mitgestutzt wurde. */
    val tightenedDocuments: Int,
    /**
     * Zahl der NACHFAHREN-ORDNER im Teilbaum, deren eigene Stufe durch die Verschärfung mitgestutzt
     * wurde (Fix-Runde B5). Der geänderte Ordner selbst zählt hier nicht mit.
     */
    val tightenedFolders: Int = 0,
)

@Serializable
data class DocumentDto(
    val id: String,
    val folderId: String,
    val title: String,
    val currentVersionId: String?,
    val createdBy: String,
    val createdByDisplayName: String,
    val createdAt: LocalDateTime,
    val accessLevel: DocumentAccessLevel,
    val isDeleted: Boolean,
)

/**
 * Audit-Snapshot für `AuditEntityType.DOCUMENT` -- Welle V1.9.1. [cascadedFromFolderId] ist nur bei
 * einer Ordner-Kaskade (`setFolderAccessLevel`-Verschärfung) gesetzt, sonst `null` (Anlegen, Ändern,
 * Löschen eines einzelnen Dokuments direkt über [network.lapis.cloud.shared.rpc.IDocumentService]).
 *
 * **Kein `title`** (Fix-Runde B4, bewusst weggelassen statt gefiltert): `AuditLogService.listAuditLog`
 * gibt die Nutzlast ungefiltert an TREASURER/BOARD/ADMIN heraus, und zwar nach `entityType`
 * filterbar. Ein Dokumenttitel ("Kündigung Mitarbeiter Müller") ist genau die Metainformation, die
 * eine `ADMIN_ONLY`-Stufe verbergen soll -- ein TREASURER, der das Dokument selbst nicht lesen darf,
 * hätte den Titel über diesen mit dieser Welle NEU entstandenen Kanal gelesen. Ein Nutzlast-Filter
 * im Leseweg wäre die Alternative gewesen, aber der wäre fail-open: er müsste pro `entityType` und
 * pro Feld gepflegt werden, und jedes künftige Feld, das ihn vergisst, leckt wieder. Ein Feld, das
 * nie geschrieben wird, kann nicht lecken.
 *
 * **Bewusst in Kauf genommener Zielkonflikt**: nach einem endgültigen Löschen ist der Titel nicht
 * mehr aus dem Log rekonstruierbar. Für die Revisionssicherheit genügt [folderId] plus die
 * `entityId` des Audit-Eintrags (die `document`-Zeile); solange sie existiert, ist der Titel über die
 * reguläre gestufte Leseschnittstelle auflösbar -- und zwar genau von denen, die ihn sehen dürfen.
 * Ein Dokument wird ohnehin nur weich gelöscht (`is_deleted`, Versionen bleiben), ein Hard-Delete
 * existiert in dieser Codebase nicht.
 */
@Serializable
data class DocumentAccessLevelSnapshot(
    val accessLevel: DocumentAccessLevel,
    val folderId: String,
    val cascadedFromFolderId: String? = null,
)

/**
 * Audit-Snapshot für `AuditEntityType.DOCUMENT_FOLDER` -- Welle V1.9.1. [tightenedDocuments]/
 * [tightenedFolders] sind nur im `after` einer Verschärfung gesetzt (Zahl der im selben Zug
 * mitgestutzten Dokumente bzw. Nachfahren-Ordner), sonst `null`. [cascadedFromFolderId] ist gesetzt,
 * wenn dieser Ordner selbst durch die Verschärfung eines Vorfahren mitgestutzt wurde (Fix-Runde B5).
 *
 * **Kein `name`** -- dieselbe Begründung wie bei [DocumentAccessLevelSnapshot]s fehlendem `title`,
 * und hier sogar schärfer: ein Ordnername ("Personalakten") ist oft die eigentlich sensible
 * Information, noch vor seinem Inhalt.
 *
 * **Restkanal, bewusst in Kauf genommen (Politur-Runde, P5)**: was ohne `name` bleibt, ist kein
 * Nichts. Aus der `entityId` des Eintrags, [parentFolderId], dem Stufenübergang und [tightenedDocuments]/
 * [tightenedFolders] liest ein TREASURER, der den Ordner selbst nicht öffnen darf, immer noch
 * "Ordner X wurde auf ADMIN_ONLY verschärft und enthielt N Dokumente und M Unterordner". Deutlich
 * schwächer als ein Name, aber vorhanden. Die Zähler zu streichen würde den Kanal allerdings **nicht**
 * schließen: die Kaskade schreibt pro mitgestutzter Zeile einen eigenen Audit-Eintrag mit
 * [cascadedFromFolderId], und die kann derselbe Leser einfach abzählen. Die Zahl wäre also weiterhin
 * ableitbar, nur unbequemer -- und die Zusammenfassung, mit der ein Prüfer eine Kaskade auf einen
 * Blick gegen ihre Einzeleinträge abgleicht, wäre weg. Deshalb bleiben die Zähler drin und der
 * Restkanal ist hier benannt, statt scheinbar geschlossen zu werden. Wer ihn wirklich schließen will,
 * muss am Leseweg ansetzen (`AuditLogService.listAuditLog` filtert die Nutzlast heute überhaupt
 * nicht) -- eine eigene Welle, kein Nebeneffekt dieser.
 */
@Serializable
data class DocumentFolderAccessLevelSnapshot(
    val accessLevel: DocumentAccessLevel,
    val parentFolderId: String?,
    val tightenedDocuments: Int? = null,
    val tightenedFolders: Int? = null,
    val cascadedFromFolderId: String? = null,
)

@Serializable
data class DocumentVersionDto(
    val id: String,
    val documentId: String,
    val versionNumber: Int,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val checksumSha256: String,
    val uploadedBy: String,
    val uploadedByDisplayName: String,
    val uploadedAt: LocalDateTime,
    val changeNote: String?,
    val downloadCount: Long,
)
