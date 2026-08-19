package network.lapis.cloud.server.rpc

/**
 * Welle V1.1.5 (E-A). Die Texte, mit denen `social_post.content` bei einer Art.-17-Löschung
 * überschrieben wird. Der Nutzer hat sich am 2026-08-19 bewusst gegen EINEN festen Marker und für
 * eine Unterscheidung nach ANLASS entschieden.
 *
 * **Server-seitig, nicht in `lapis-shared`, und niemals i18n.** Diese Strings sind GESPEICHERTE
 * DATEN, kein Oberflächentext: sie werden genau einmal geschrieben und danach wie jeder andere
 * Beitragsinhalt gelesen. Sie durch `gettext(...)` zu schicken, wäre ein Kategorienfehler -- die
 * Datenbank kann die Sprache des späteren Lesers nicht kennen. Aus demselben Grund erkennt KEIN
 * Renderer den Marker per String-Vergleich; die Erkennung läuft ausschließlich über
 * `SocialPostTable.contentErasedAt IS NOT NULL` (bzw. [network.lapis.cloud.shared.domain
 * .SocialPostDto.contentErasedAt] != null).
 *
 * **Änderungen an diesen Konstanten wirken nicht rückwirkend.** Bereits getombstonete Zeilen
 * behalten den zum Zeitpunkt der Löschung gültigen Wortlaut. Das ist gewollt (eine Migration, die
 * gespeicherte Löschvermerke umschreibt, wäre ein Eingriff in einen dokumentierten Vorgang) und
 * muss bei jeder späteren Textkorrektur mitgedacht werden.
 *
 * **Der Anlassunterschied ist bewusst der EINZIGE Informationsgehalt.** Beide Texte nennen dieselbe
 * Rechtsgrundlage, keiner nennt eine Person, keiner nennt ein Aktenzeichen, keiner verrät, ob der
 * Antrag von einem Dritten oder vom Autor selbst kam -- außer in genau der Granularität, die E-A
 * bestellt hat. Insbesondere behauptet [ON_AUTHOR_REQUEST] NICHT, dass ein Konto gelöscht wurde:
 * "auf Antrag der Autorin oder des Autors" ist für den mitglieds-weiten Löschantrag zutreffend,
 * ohne den Kontostatus des Autors öffentlich zu machen.
 *
 * **Erster Schreiber gewinnt.** Weder [SocialNetworkService.executeContentErasure] (schreibt
 * [ON_POST_REQUEST]) noch `network.lapis.cloud.server.dsgvo.SocialNetworkPersonalData.erase`
 * (schreibt [ON_AUTHOR_REQUEST]) überschreibt einen bereits gesetzten Marker -- beide Schreibpfade
 * prüfen `contentErasedAt IS NULL`, bevor sie schreiben.
 */
internal object SocialContentTombstone {
    /** Anlass (A): post-bezogener Antrag über `social_post_erasure` (`executeContentErasure`). */
    const val ON_POST_REQUEST: String =
        "Der Inhalt dieses Beitrags wurde auf Antrag einer betroffenen Person nach Art. 17 DSGVO entfernt."

    /** Anlass (B): mitglieds-weiter Löschantrag über `DsgvoService.executeErasure`. */
    const val ON_AUTHOR_REQUEST: String =
        "Der Inhalt dieses Beitrags wurde auf Antrag der Autorin oder des Autors nach Art. 17 DSGVO entfernt."

    /** Für Tests und für den Schema-Drift-/Datenqualitäts-Check: alle jemals geschriebenen Marker. */
    val ALL: Set<String> = setOf(ON_POST_REQUEST, ON_AUTHOR_REQUEST)
}
