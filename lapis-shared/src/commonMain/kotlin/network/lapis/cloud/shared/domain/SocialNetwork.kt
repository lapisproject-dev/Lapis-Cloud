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
 * - [PUBLIC]: sichtbar für alle, auch nicht angemeldete Besucher (erreichbar erst ab Welle V1.1.3's
 *   öffentlichem HTTP-Lesepfad -- diese Welle filtert nur bereits authentifizierte Aufrufer).
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
 * - [REMOVED_LEGAL]: BOARD/ADMIN-ausgelöste rechtliche Entfernung (DSA Art. 16/6) --
 *   `removePostForLegalReason` schreibt diesen Wert erstmals in Welle V1.1.5; die Spalte existiert
 *   bereits jetzt, damit `V4__social_network_core.sql`'s CHECK-Constraint nicht in einer späteren
 *   Welle erneut angefasst werden muss.
 */
@Serializable
enum class SocialPostState { VISIBLE, HIDDEN_BY_AUTHOR, REMOVED_LEGAL }

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
