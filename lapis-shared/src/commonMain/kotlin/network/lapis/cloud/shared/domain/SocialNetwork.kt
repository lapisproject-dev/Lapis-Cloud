package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" -- see
 * `lapis-server/src/main/kuml/32-social-network.kuml.kts` file header for the full fachlich model
 * this domain implements, and the vault concept notes ("03 Bereiche/Lapis Cloud/Soziales
 * Netzwerk.md", "03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md" § "Im sozialen
 * Netz -- Gewichtung von Posts") for the underlying mechanism (Initialgewicht, 10%/Tag Zerfall,
 * drei Sichtbarkeitsstufen, Unveraenderlichkeit, Unsichtbarmachen statt Loeschen).
 *
 * Literal order is load-bearing (matches `32-social-network.kuml.kts`'s own `socialPostVisibility`
 * enum, pinned by `SocialNetworkSchemaDriftTest`) -- cheap to extend, expensive to reorder, same
 * discipline every other domain enum in this codebase follows.
 *
 * - [PUBLIC]: sichtbar für alle, auch nicht angemeldete Besucher -- seit Welle V1.1.3 tatsächlich
 *   ohne Login erreichbar über den öffentlichen HTTP-Lesepfad
 *   (`network.lapis.cloud.server.routes.SocialPublicRoutes`, `GET /s`/`GET /s/{id}`), gefiltert über
 *   [network.lapis.cloud.server.rpc.SocialVisibility.publicReadableCondition]. Ein `PUBLIC`-Post ist
 *   ab Veröffentlichung dauerhaft von Suchmaschinen indexierbar.
 * - [MEMBERS_ONLY]: nur [MemberStatusSets.ORGANIZATION_MEMBER].
 * - [MEMBERS_AND_EXTERNAL]: zusätzlich [MemberStatusSets.NON_MEMBER] (GUEST/FRIEND).
 */
@Serializable
enum class SocialPostVisibility { PUBLIC, MEMBERS_ONLY, MEMBERS_AND_EXTERNAL }

/**
 * Literal order is load-bearing, same reason as [SocialPostVisibility].
 *
 * - [VISIBLE]: normal, in jeder für den Betrachter passenden Liste sichtbar.
 * - [HIDDEN_BY_AUTHOR]: die "stille Notbremse" des Autors -- irreversibel (siehe
 *   `SocialNetworkService.hideOwnPost` KDoc), keine LTR-Rückerstattung, Post bleibt per direkter
 *   ID-Abfrage erreichbar.
 * - [REMOVED_LEGAL]: BOARD/ADMIN-ausgelöste rechtliche Entfernung (DSA Art. 16/6), seit Welle
 *   V1.1.5 tatsächlich geschrieben von `removePostForLegalReason`. **`state_reason` ist ab dieser
 *   Welle öffentlicher Text** (Entscheidungspunkt E-B, 2026-08-19): für einen `PUBLIC`-Post wird er
 *   jedem anonymen Besucher auf einer 451-Hinweisseite angezeigt
 *   (`network.lapis.cloud.server.routes.SocialPublicHtml.legallyRemovedPage`); für einen
 *   nicht-öffentlichen Post erfährt ihn jeder Aufrufer, dessen Sichtbarkeitsstufe den Post
 *   umfasste, über `ISocialNetworkService.getRemovalNotice`. Er darf deshalb **keine internen
 *   Vorgangsdaten** enthalten (Kanzleinamen, Aktenzeichen, Klarnamen Dritter) -- interne Details
 *   gehören in `SocialPostReportDto`/`SocialPostErasureDto`'s `decisionNote`, die beide strikt
 *   intern bleiben.
 */
@Serializable
enum class SocialPostState { VISIBLE, HIDDEN_BY_AUTHOR, REMOVED_LEGAL }

/**
 * Welle V1.1.5 -- DSA Art. 16 Abs. 2 Meldegründe. Literal order is load-bearing (matches
 * `32-social-network.kuml.kts`'s `socialPostReportCategory` enum).
 */
@Serializable
enum class SocialPostReportCategory { ILLEGAL_CONTENT, DEFAMATION, COPYRIGHT, PERSONAL_DATA, HATE_SPEECH, SPAM, OTHER }

/**
 * Welle V1.1.5 -- Zustandsautomat einer Meldung: `OPEN --> UNDER_REVIEW --> ACTION_TAKEN` bzw.
 * `OPEN --> DISMISSED`. Literal order is load-bearing (matches `32-social-network.kuml.kts`'s
 * `socialPostReportStatus` enum).
 */
@Serializable
enum class SocialPostReportStatus { OPEN, UNDER_REVIEW, ACTION_TAKEN, DISMISSED }

/**
 * Welle V1.1.5 -- Zustandsautomat eines post-bezogenen DSGVO-Content-Löschantrags:
 * `REQUESTED --approve--> APPROVED --execute--> EXECUTED` bzw. `REQUESTED --reject--> REJECTED`.
 * Bewusst NICHT [ErasureStatus] wiederverwenden -- siehe Plan § 2.3 ("Warum nicht ErasureStatus
 * wiederverwenden?"): der Endzustand heißt hier fachlich `EXECUTED` (der Post-Inhalt ist
 * getombstonet), nicht `COMPLETED` (der Contributor-Walk über alle Tabellen ist gelaufen), und
 * `32-social-network.kuml.kts` ist ein eigenständiges `classDiagram`, das kein Enum aus
 * `04-dsgvo.kuml.kts` referenzieren kann. Literal order is load-bearing.
 */
@Serializable
enum class SocialPostErasureStatus { REQUESTED, APPROVED, REJECTED, EXECUTED }

/**
 * Rolle: jeder Aufrufer, der `ISocialNetworkService.createPost` erreicht (Welle 1:
 * [MemberStatusSets.ORGANIZATION_MEMBER] via `requireActiveMembership`; ab Welle V1.1.4
 * `MemberStatusSets.LTR_ELIGIBLE`). [initialWeightLtr] wird als
 * [LtrLedgerEntryType.SOCIAL_POST_STAKE]-Debit gegen das eigene freie LTR-Guthaben gebucht -- siehe
 * `SocialNetworkService.createPost` KDoc für die genaue Validierungsreihenfolge.
 */
@Serializable
data class SocialPostInput(
    val content: String,
    val visibility: SocialPostVisibility,
    val initialWeightLtr: Decimal,
)

/**
 * `parentId`/`limit`/`offset`/`includeHidden`/`authorMemberId` sind bereits Teil der Welle-1-Form,
 * auch wenn Welle 1 noch keine Kommentare schreibt (jeder Post hat `parentId = null`) -- so ändert
 * sich die Interface-Form nicht mehr, seit Welle V1.1.2 Kommentare/Threads einführt.
 * `limit`/`offset` sind reine Pagination-Parameter über die nach Gesamtgewicht (Eigengewicht + Summe
 * aller Nachfahren-Gewichte, seit Welle V1.1.2 -- siehe [SocialPostDto.totalCurrentWeightLtr])
 * sortierte Wurzel-Post-Liste. Korrigiert (NEU-2, Review Runde 2, 2026-08-18): anders als hier zuvor
 * behauptet, ist der `SocialPostWeight.RANKING_HORIZON_DAYS`-Ranking-Horizont-Cutoff bereits SEIT
 * Review-Fund S1 (2026-08-18) aktiv -- siehe `SocialPostWeight.RANKING_HORIZON_DAYS` KDoc. Ausnahme:
 * die `includeHidden`-Selbstansicht des Autors auf die eigenen `authorMemberId`-Posts filtert NICHT
 * nach diesem Horizont (siehe `SocialNetworkService.listTimeline` KDoc). Seit Welle V1.1.2 gilt der
 * Horizont auch für Nachfahren/Boosts, die in die Gesamtgewichts-Aggregation einfließen.
 */
@Serializable
data class SocialTimelineQuery(
    /**
     * `null` = Wurzel-Posts (Timeline). Gesetzt = direkte Antworten unterhalb dieses Knotens. Seit
     * Welle V1.1.2 fachlich befüllbar -- für einen vollständigen, tief verschachtelten Thread
     * (nicht nur die direkten Antworten EINER Ebene) ist jedoch `ISocialNetworkService.getThread`
     * der vorgesehene Weg, nicht dieses Feld; siehe dessen KDoc.
     */
    val parentId: String? = null,
    val limit: Int = 12,
    val offset: Int = 0,
    /** Nur für den Autor selbst sinnvoll (BOARD-weite Moderationsansicht kommt erst mit Welle V1.1.5) -- zeigt zusätzlich eigene [SocialPostState.HIDDEN_BY_AUTHOR]-Posts. */
    val includeHidden: Boolean = false,
    val authorMemberId: String? = null,
)

/**
 * [rankingHorizonFrom] ist der älteste tatsächlich zurückgegebene `publishedAt`-Wert -- macht das
 * Sortierfenster für den Client sichtbar statt still. Korrigiert (NEU-2, Review Runde 2,
 * 2026-08-18): anders als hier zuvor behauptet, gibt es SEIT Review-Fund S1 (2026-08-18) sehr wohl
 * einen aktiven `SocialPostWeight.RANKING_HORIZON_DAYS`-Cutoff (Posts älter als 400 Tage fallen aus
 * der Kandidatenmenge, siehe [SocialTimelineQuery] KDoc) -- ausgenommen die `includeHidden`-
 * Selbstansicht des Autors, für die [rankingHorizonFrom] weiterhin einfach der älteste
 * zurückgegebene Wert ohne Cutoff-Bedeutung ist.
 */
@Serializable
data class SocialTimelinePageDto(
    val posts: List<SocialPostDto>,
    val totalRankedCount: Int,
    val rankingHorizonFrom: LocalDateTime,
)

/**
 * Rolle: jeder Aufrufer, der `ISocialNetworkService.createComment` erreicht (dieselbe Mitglieds-Rolle
 * wie [SocialPostInput]/`createPost`). [initialWeightLtr] wird -- exakt wie bei einem Wurzel-Post --
 * als [LtrLedgerEntryType.SOCIAL_POST_STAKE]-Debit gegen das eigene freie LTR-Guthaben gebucht: ein
 * Kommentar ist ein vollwertiger Post, kein Sonderfall.
 *
 * **Bewusst KEINE `visibility`** (S5): sie wird vom WURZEL-Post übernommen, nicht vom Client
 * gewählt -- ein öffentlicher Kommentar unter einem internen Post würde schon durch seine bloße
 * Existenz den internen Kontext leaken. Absichtlich nicht als vom Server ignoriertes Feld
 * mitgeführt: ein Feld, das der Server stillschweigend verwirft, ist eine Falle.
 */
@Serializable
data class SocialCommentInput(
    val parentId: String,
    val content: String,
    val initialWeightLtr: Decimal,
)

/**
 * Ergebnis von `ISocialNetworkService.getThread`. [nodes] ist eine flache Präorder-Liste (Wurzel
 * zuerst, [SocialPostDto.depth] ist bereits befüllt) -- die Baumstruktur ergibt sich aus
 * `parentId`/`depth`, es gibt keinen verschachtelten DTO-Baum.
 */
@Serializable
data class SocialThreadDto(
    /** Präorder, Wurzel zuerst. Geschwister nach [SocialPostDto.totalCurrentWeightLtr] absteigend (K1). */
    val nodes: List<SocialPostDto>,
    /** `true`, wenn der Teilbaum [network.lapis.cloud.server.rpc.SocialPostWeight.THREAD_MAX_NODES] überschritten hat und abgeschnitten wurde. */
    val truncated: Boolean,
    /** Knotenzahl VOR der Deckelung/Sichtbarkeitsfilterung -- macht die Deckelung sichtbar statt still. */
    val totalNodeCount: Int,
)

/**
 * Seit Welle V1.1.2 um die rekursive Gewichtsaggregation über Kommentare/Boosts erweitert -- siehe
 * `SocialPostWeight` KDoc für die Berechnung, `ISocialNetworkService.createComment`/`.getThread`/
 * `.boostPost` für die schreibenden/lesenden Zugriffe.
 */
@Serializable
data class SocialPostDto(
    val id: String,
    val parentId: String?,
    val rootId: String,
    val depth: Int,
    val authorMemberId: String,
    val authorDisplayName: String,
    /**
     * "Gewicht des Autors" aus dem Meritokratie-Konzept: sein aktueller freier LTR-Bestand.
     *
     * Security-Audit-Fund S-1 (2026-08-18): dieser Wert ist finanziell sensibel (cent-genauer
     * freier LTR-Bestand eines fremden Mitglieds) und wird deshalb nicht mehr an jeden Leser
     * ausgeliefert -- `LtrLedgerService.getMemberBalance` verlangt für fremde Konten bereits
     * `LTR_TREASURY_ROLES`; dieselbe Zahl über die Timeline ungeschützt zu zeigen hätte diese
     * Schranke umgangen. `null`, wenn der aufrufende Leser nicht
     * [network.lapis.cloud.shared.domain.MemberStatusSets.ORGANIZATION_MEMBER] ist (siehe
     * `SocialNetworkService.toDtos` KDoc) -- z. B. für ein selbst-registriertes `FRIEND`-Konto, das
     * `PUBLIC`/`MEMBERS_AND_EXTERNAL`-Posts lesen darf, aber keinen fremden LTR-Bestand tracken
     * können soll.
     */
    val authorFreeBalanceLtr: Decimal?,
    val content: String,
    val visibility: SocialPostVisibility,
    val state: SocialPostState,
    val stateReason: String?,
    /**
     * Welle V1.1.5. `null` == niemals per Art.-17-Antrag getombstonet -- das eigentliche Flag, an
     * dem Renderer/Client erkennen, dass [content] kein Nutzertext mehr ist, sondern einer der
     * beiden `network.lapis.cloud.server.rpc.SocialContentTombstone`-Marker. [content] selbst
     * bleibt UNVERÄNDERT durchgereicht (es *ist* nach dem Tombstoning der Marker-Text) -- kein
     * Renderer muss den Marker-Wortlaut kennen, nur diesen Zeitstempel abfragen. Orthogonal zu
     * [state]/[stateReason]: eine rechtliche Entfernung (`REMOVED_LEGAL`) fasst `content`/dieses
     * Feld NIE an, ein Tombstoning fasst `state` NIE an -- beide können gleichzeitig gelten.
     */
    val contentErasedAt: LocalDateTime? = null,
    val initialWeightLtr: Decimal,
    /**
     * Eigengewicht -- seit Welle V1.1.2 zerfallener [initialWeightLtr] PLUS Summe der je ab ihrem
     * eigenen Zeitpunkt zerfallenen eigenen Boosts (siehe
     * [network.lapis.cloud.server.rpc.SocialPostWeight.ownWeightUnrounded] Überladung). NICHT das
     * Sortierkriterium der Timeline -- siehe [totalCurrentWeightLtr].
     */
    val ownCurrentWeightLtr: Decimal,
    /**
     * NEU Welle V1.1.2. [ownCurrentWeightLtr] + rekursive Summe der Gesamtgewichte aller Nachfahren.
     * DAS Sortierkriterium der Timeline (Konzept: ein viel diskutierter Beitrag steigt, auch wenn
     * sein Eigeneinsatz klein war). Enthält AUCH das Gewicht unsichtbar gemachter/rechtlich
     * entfernter Nachfahren (E3) -- Sichtbarkeit und Ökonomie sind getrennte Belange.
     */
    val totalCurrentWeightLtr: Decimal,
    /** NEU Welle V1.1.2. Anzahl direkter, für den Betrachter sichtbarer Kind-Posts. */
    val directCommentCount: Int,
    /** NEU Welle V1.1.2. Anzahl aller sichtbaren Nachfahren (transitiv). */
    val totalDescendantCount: Int,
    /** NEU Welle V1.1.2. Anzahl Boosts auf DIESEN Knoten (nicht auf Nachfahren). */
    val boostCount: Int,
    val publishedAt: LocalDateTime,
)

// ================================================================================================
// Welle V1.1.5 "Moderation, DSA-Melde-Mechanismus, DSGVO-Content-Hard-Delete" -- siehe
// `ISocialNetworkService` KDoc für die neun RPC-Methoden dieser Welle und
// `32-social-network.kuml.kts` file header für das Schema.
// ================================================================================================

/**
 * Eingabe für `ISocialNetworkService.reportPost` (authentifizierter Pfad) UND den öffentlichen
 * `POST /s/{id}/report`-Formular-Handler (`network.lapis.cloud.server.rpc.SocialReportSubmission`,
 * die geteilte Kernlogik hinter beiden Wegen). [reporterContact] ist bewusst OPTIONAL (DSA Art. 16
 * Abs. 2 lit. c erlaubt Anonymität bei Meldungen zu Straftaten nach Art. 3-7 RL 2011/93/EU;
 * Entscheidungspunkt E-F macht sie generell optional, weil es noch keinen funktionierenden
 * Mailversand gibt, über den eine Pflichtangabe ohnehin folgenlos bliebe).
 */
@Serializable
data class SocialPostReportInput(
    val postId: String,
    val category: SocialPostReportCategory,
    /** DSA Art. 16 Abs. 2 lit. a, Pflichtfeld -- die "hinreichend begründete Erläuterung". */
    val description: String,
    val reporterContact: String? = null,
    /** DSA Art. 16 Abs. 2 lit. d, Pflichtfeld. */
    val goodFaithConfirmed: Boolean = false,
)

/**
 * Eine Meldung, wie sie `ISocialNetworkService.listReports`/`.decideReport` (BOARD/ADMIN) sehen.
 * [postExcerpt]/[postState]/[postVisibility] geben dem Moderationsteam Kontext auf den gemeldeten
 * Post, OHNE ihn separat per `getPost` nachladen zu müssen -- dieser Lesepfad schließt bewusst auch
 * bereits `REMOVED_LEGAL`-Posts ein (§ 5.1 des Plans), sonst könnte eine Meldung zu einem bereits
 * entfernten Post nicht mehr im Kontext angezeigt werden.
 */
@Serializable
data class SocialPostReportDto(
    val id: String,
    val postId: String,
    val postExcerpt: String,
    val postState: SocialPostState,
    val postVisibility: SocialPostVisibility,
    val reportedAt: LocalDateTime,
    /** `null` == anonyme öffentliche Meldung. */
    val reporterMemberId: String?,
    /**
     * Security-Audit-Fund MINOR-4 (Runde 1, 2026-08-19): vorher wurde `social_post_report
     * .reporter_contact` gespeichert (und das öffentliche Meldeformular versprach, sie zur
     * Ergebnismitteilung nach Art. 16 Abs. 4/5 DSA zu nutzen), aber dieses DTO trug das Feld nicht
     * bis in die BOARD/ADMIN-Moderationswarteschlange weiter -- die Daten wurden für einen
     * angegebenen Zweck erhoben, den kein Codepfad erfüllen konnte (Datenminimierungs-/
     * Zweckbindungsproblem, unabhängig davon, ob ein automatischer Mailer bereits existiert).
     * `null` sowohl bei einer anonymen öffentlichen Meldung ohne Kontaktangabe als auch bei einer
     * authentifizierten Meldung ohne `reporterContact`.
     */
    val reporterContact: String?,
    val category: SocialPostReportCategory,
    val description: String,
    val goodFaithConfirmed: Boolean,
    val status: SocialPostReportStatus,
    val decidedBy: String?,
    val decidedAt: LocalDateTime?,
    /** Rein intern -- wird NIE auf dem öffentlichen Pfad gerendert (im Unterschied zu [SocialPostDto.stateReason]). */
    val decisionNote: String?,
)

/**
 * Eingabe für `ISocialNetworkService.requestContentErasure` -- ein post-bezogener DSGVO-Art.-17-
 * Antrag (Plan § 0.4: für eine betroffene Person OHNE eigenes Lapis-Cloud-Konto, im Unterschied zum
 * bestehenden, mitglieds-bezogenen `IDsgvoService.requestErasure`-Pfad). [subjectMemberId] `null` ==
 * externe betroffene Person ohne Konto ODER der Aufrufer beantragt für sich selbst.
 */
@Serializable
data class SocialPostErasureInput(
    val postId: String,
    val reason: String,
    val subjectMemberId: String? = null,
    val requesterContact: String? = null,
)

/** Ein post-bezogener DSGVO-Content-Löschantrag, wie ihn `ISocialNetworkService.listContentErasures` (ADMIN) sieht. */
@Serializable
data class SocialPostErasureDto(
    val id: String,
    val postId: String,
    val requestedAt: LocalDateTime,
    val requestedBy: String?,
    val subjectMemberId: String?,
    val requesterContact: String?,
    val reason: String,
    val status: SocialPostErasureStatus,
    val decidedBy: String?,
    val decidedAt: LocalDateTime?,
    val decisionNote: String?,
    val executedAt: LocalDateTime?,
    val sourceReportId: String?,
)

/**
 * Welle V1.1.5 (E-B). Was ein Leser über einen rechtlich entfernten Beitrag erfahren darf -- und
 * mehr nicht. Rückgabetyp von `ISocialNetworkService.getRemovalNotice`. Bewusst KEIN abgespeckter
 * [SocialPostDto]: kein `content` (auch kein Marker), kein `authorDisplayName`, keine Gewichte,
 * keine Zähler -- ein DTO, das diese Felder hätte, würde früher oder später mit Platzhaltern
 * befüllt und damit falsche Zahlen behaupten.
 */
@Serializable
data class SocialPostRemovalNoticeDto(
    val postId: String,
    val visibility: SocialPostVisibility,
    val removedAt: LocalDateTime,
    val reason: String,
    /** Dient dem Client nur zur "Ihr Beitrag"-Kennzeichnung (DSA Art. 17, E-C) -- verrät für jeden anderen Aufrufer nichts über den Autor. */
    val isOwnPost: Boolean,
)
