# Lapis Cloud — Repo-Konventionen

Föderierte Mitgliederverwaltung für Vereine und Parteien mit meritokratischer Governance-Schicht.
Weiterentwicklung der PZB (PdV Parteizentralbank). Vollständiges Konzept und Roadmap liegen im
Obsidian-Vault des Nutzers (nicht Teil dieses Repos) — dieses Dokument beschreibt nur die
Repo-lokalen Arbeitskonventionen.

## Dokumentations-Konvention

- **Alle READMEs in Asciidoctor** (`.adoc`) — keine `README.md`.
- **Alle weitere Projektdokumentation in Asciidoctor** — Architekturdokumente, Setup-Guides,
  API-Doku, Operations-Handbücher etc.
- **Alle Diagramme in kUML** — keine PlantUML, Mermaid, draw.io, ASCII-Art-Diagramme oder
  vergleichbares parallel dazu.
- **Diagramm-Einbindung über das `[kuml]`-Macro** — Inline-Quelltext direkt im `.adoc`-Dokument,
  das Macro rendert zur Build-Zeit. Grundform:

  ```asciidoc
  [kuml, dateiname-ohne-extension, svg]
  ----
  <kUML-Quelltext>
  ----
  ```

  Keine externen Image-Referenzen auf vorgerenderte Diagramme — die Diagramm-Quelle gehört ins
  Dokument selbst, damit Änderungen im Diff sichtbar sind.
- **Macro-Implementierung**: `kuml-asciidoc` (`kuml-dev/kuml-asciidoc`, Maven Central), als
  reguläre Build-Dependency eingebunden — keine Eigenentwicklung, kein lokales Kopieren.
- **Markdown** nur für AI-Prompts und Arbeitsnotizen außerhalb der versionierten Projekt-Doku.

## Branch- und Entwicklungs-Workflow

1. **Niemals direkt auf `master` entwickeln.** Jede Änderung (Feature, Bugfix, Refactor,
   Experiment) bekommt einen separaten Branch von `master`.
   - Namen beschreibend: `feature/<kurzbeschreibung>`, `fix/<kurzbeschreibung>`,
     `refactor/<kurzbeschreibung>`.
   - Beliebig viele kleine Zwischen-Commits auf dem Branch erlaubt (WIP, Fix-ups, Experimente).
   - **Feature-Branches bleiben lokal** und werden **nicht** nach GitHub gepusht.
2. **Erst wenn eine Version veröffentlichungsreif ist**, wird lokal auf `master` integriert: alle
   Commits des Feature-Branches werden per `git merge --squash <branch>` + manuellem `git commit`
   zu **einem Squash-Commit auf `master`** zusammengefasst. Die Commit-Message beschreibt die
   gesamte Version (was ist neu, was gefixt, Breaking Changes).
3. **Erst nach dem Squash wird `master` nach GitHub gepusht** (`git push origin master`). Pro
   Release erscheint auf GitHub nur ein einziger Commit — die Master-History bleibt linear.
4. Der Feature-Branch wird nach dem Squash-Merge lokal gelöscht.
5. **Niemals** `--force` pushen oder `master`-History umschreiben ohne explizite Anweisung.

### Commit-SHA-Stabilität nach Squash

Pre-Squash-Commit-SHAs sind flüchtig — nach dem Squash auf `master` erscheinen neue SHAs. In
Notizen/Changelogs außerhalb dieses Repos (Daily Notes, Wellen-Tabellen) erst den finalen
`master`-SHA protokollieren, wenn der Squash-Commit tatsächlich gepusht ist.

## Technologie-Stack

Kotlin · Ktor (Server) · Exposed (DB-Zugriff) · Flyway (Migrationen) · KVision (Web-UI,
Kotlin/JS) · Kilua RPC (typsichere Client-Server-Kommunikation) · Koog (Multi-LLM-Agent-Layer,
JetBrains) — Details und Architektur-Hintergrund siehe `README.adoc` und `docs/features/`.

> **Koog-Notiz (V1.6.1):** Die erste KI-Welle (`network.lapis.cloud.server.ai`, Satzungs-Q&A) baut
> bewusst **ohne** Koog auf einem eigenen minimalen `LlmClient` (zwei schmale Ktor-Clients). Koog 1.2.0
> (Maven Central, 2026-09-19 geprüft) zieht transitiv `ktor-client-logging` (widerspricht der Regel
> „kein Logging-Plugin auf einem API-Key-Client", siehe `OracleHttpClient` und den
> `io.ktor.client`-INFO-Floor in `logback.xml`), eine zweite Ktor-Server-Engine (CIO + SSE), eine
> Ktor-3.3.3-Linie gegen die 3.5.2 dieses Repos und Jackson als zweiten Serialisierungs-Stack — für
> einen Retrieval-Schritt und einen Modellaufruf. Wechsel neu prüfen, sobald Koog auf der Ktor-Linie
> dieses Repos sitzt und ein logging-freies Client-Modul anbietet. Begründung ausführlich:
> `docs/architecture/ai-assistant.adoc`.

## Postgres-Testspur (V1.9.37)

Die normale Testsuite läuft auf H2. Alles, was von der Datenbank-Engine abhängt (Row-Locks, Isolation, Deadlocks, das Verhalten einer
Transaktion nach einem fehlgeschlagenen Statement, die echte Flyway-Kette), prüft die Postgres-Spur: `./gradlew :lapis-server:postgresTest`
(Teil von `check`, wird übersprungen, wenn `LAPIS_TEST_POSTGRES_URL` fehlt). Details: `docs/architecture/postgres-test-lane.adoc`.

- **Neue Concurrency- und Lock-Tests immer als `...Scenarios(db: TestDatabase)` anlegen** und zweimal einhängen: `FooTest : FooScenarios(TestDatabase.H2)`
  und `FooPostgresTest : FooScenarios(TestDatabase.Postgres())` mit `@Tags("Postgres")` und `@EnabledIf(PostgresConfigured::class)`. Gleiche
  Assertions auf beiden Datenbanken; wo H2 nur wegen seines 1-Sekunden-Lock-Timeouts tolerant ist, auf Postgres strenger prüfen, nie lockerer.
  Der Klassenname der Spur-Variante enthält `Postgres` (der Gradle-Filter arbeitet über den Namen; `PostgresLaneNamingTest` wacht darüber).
- `MemberTable`-Zeilen nie mit `forUpdate()` sperren, sondern `forMemberUpdate()` (`db/RowLocks.kt`, `FOR NO KEY UPDATE`); Ausnahme nur bei Änderung einer Schlüsselspalte (id/email/member_number), siehe `docs/architecture/row-locks.adoc` (Tripwire: `MemberRowLockTripwireTest`).
- In Szenarien der Spur niemals `module()` oder `DatabaseConfig.connect()` aufrufen (sonst Rückfall auf H2); der Dialekt-Guard in `installLaneGuards` fängt das ab.
- **Die H2-Spur läuft seit V1.9.58 in mehreren Test-JVMs** (`-Plapis.test.forks=N`, CI: 4). Neue Tests dürfen deshalb keine festen Ports und keine geteilten
  Dateipfade nutzen (Port `0` bzw. ein Verzeichnis mit `Uuid.random()` im Namen). Jede JVM hat ihre eigene H2-Datenbank; eine Reihenfolge-Abhängigkeit zwischen
  Spec-Klassen ist ein Testfehler der Fixture, nie ein Grund, eine Assertion zu lockern. Die Postgres-Spur bleibt bei einer JVM.
- **Nie `LAPIS_DB_URL` setzen** (weder lokal noch in CI) und **nie gegen eine fremde oder produktive Datenbank testen**: die Spur lehnt alles außer einer
  Wegwerf-Instanz ab (URL-Whitelist, Instanz-Check, DROP nur für selbst angelegte `lapis_pgtest_*`). Lokal nur ein frischer Docker-Container, an `127.0.0.1` gebunden.
- Wer eine Constraint-Verletzung fängt und danach dieselbe Transaktion weiterbenutzt, braucht einen Savepoint (`withSavepoint`, `db/Savepoints.kt`):
  auf Postgres vergiftet die Verletzung die ganze Transaktion (`25P02`). Exposed wiederholt dann die GESAMTE Transaktion und maskiert den Fehler -- Tests zählen
  deshalb, wie oft der Block lief, nicht nur das Ergebnis.

- **Transaktionsregeln (V1.9.55, Tripwire-Tests wachen darüber)**: (1) Timeouts ausschließlich per `SET LOCAL` (`relaxSessionTimeouts`), nie sitzungsweit
  (`DbSessionTimeouts` ist die einzige Ausnahme); (2) externe Effekte (Provider-HTTP, Mail, Brief, Buchhaltungs-Push, `runBlocking`) nie in `transaction {}` --
  Exposed wiederholt den Block bei jeder `SQLException`; (3) ein gefangener `ExposedSQLException` auf Geldpfaden bedeutet nur Unique-Verletzung
  (`isUniqueViolation()`, SQLSTATE 23505), sonst wird er weitergeworfen. Details: `docs/architecture/database-timeouts-and-retries.adoc`.

## Verwandte Repositories

- `kuml-dev/kUML` — Modellierungssprache für alle Diagramme
- `kuml-dev/kuml-asciidoc` — Asciidoctor-Extension, die das `[kuml]`-Macro bereitstellt
- PZB (`gitlab.com/pdv7/pzb`) — Vorgänger-Repo, read-only Referenz für die Neuimplementierung
- Lapis Net — dezentrales P2P-Schwesterprojekt (eigenes Repo, noch anzulegen)

## Logging und Datenschutz (V1.9.65)

Details: `docs/architecture/logging-privacy.adoc`. Kurzregeln:

- **Logback-Patterns nur mit `%safeMsg`/`%safeEx`**, nie mit `%msg`, `%m`, `%message`, `%ex`, `%xEx`, `%rEx`, `%throwable` -- diese umgehen die Schwärzung von SQL-Text und
  Personendaten (Kilua loggt jede Exposed-Ausnahme mit Meldung und Ursachenkette). Ein Tripwire-Test prüft die Appender-Patterns.
- Kein `logback-test.xml` anlegen (die Tests müssen die Produktionskonfiguration laden); Logger `Exposed` bleibt mindestens INFO.

## Gast-Föderation (V1.9.99)

- Gast-Föderation: Die RP fordert ausschließlich `OidcScopes.FEDERATION` an; `OidcScopes.ALL` nur für Issuer/Discovery (Tripwire `OidcFederationScopeTripwireTest`). Details: `docs/architecture/oidc-guest-federation.adoc`.

## Buchhaltungs-Export (V1.9.65)

Details: `docs/architecture/accounting-export-idempotency.adoc`. Jeder Schreibzugriff nach einem `claim` trägt das Fencing-Token (`claim_generation`, nie zurückgesetzt) und liefert `FencedWrite`; neue
`mark*`-Funktionen brauchen dieselbe Bedingung. Ein Anbieter mit Beleg-Suche implementiert `findVouchersByNumber`; "nicht gefunden" setzt einen `UNKNOWN`-Posten nie automatisch auf
"nicht gesendet".

## Zeiten und Zeitzonen (V1.9.38)

Details: `docs/architecture/time-and-timezones.adoc`. Kurzregeln:

- **Keine implizite Prozesszone.** In `lapis-server/src/main` nie `currentSystemDefault`, `ZoneId.systemDefault` oder `LocalDateTime.now()`:
  `ServerClock.now()` (UTC-Systemstempel, Klasse A), `ServerClock.nowIn(orgZone)` (Wandzeit der Organisation, Klasse B) oder
  `ServerClock.todayIn(orgZone)` (Kalenderdatum, Klasse D); `OrganizationTimeZone.wallNow()/today()/wallNowOf(now)/dateOf(now)` liefern die
  Organisationszone. Ein Test pinnt die Uhr mit `TimeTestSupport.withServerClock("2026-07-01T10:00:00Z") { ... }`.
- **Zwei Uhren, nie ein `now`.** Wer eine B-Spalte (`startsAt`, `closesAt`) mit "jetzt" vergleicht, nimmt `wallNow`, wer eine A-Spalte
  (`holdExpiresAt`, `expiresAt`) vergleicht, `now` (UTC). Parameter heißen `wallNow`, damit ein Aufrufer, der den UTC-Stempel übergibt, auffällt.
- **Neue Zeitfelder** (DTO-Property oder Exposed-Spalte) gehören in `lapis-server/src/test/resources/time-fields.tsv` (Klasse A/B/D, Begründung =
  die schreibende Stelle); sonst bricht `TimeFieldClassificationTripwireTest`.
- **Client:** Klasse A nur über `formatSystem*`/`systemDateTime*`/`systemTimestamp*` (`OrganizationTime.kt`), Klasse B über die einfachen
  Formatter aus `DateTime.kt`; "jetzt"/"heute" über `organizationNow()`/`organizationToday()`, nie über die Browser-Zone.
- **Container:** `TZ=UTC`, nie ändern und nie auf eine lokale Zone setzen. Die Testsuite läuft in beiden Prozesszonen grün:
  `./gradlew clean :lapis-server:test -PtestTimeZone=Europe/Berlin` und `-PtestTimeZone=UTC`.
- **Gremiensitze (V1.9.102):** Ein Statuswechsel nach `MemberStatusSets.COMMITTEE_SEAT_ENDING` beendet Gremiensitze nur über
  `CommitteeSeatLifecycle.endSeatsForStatusChange` (Phase 1) plus `recordAudits()` (Phase 2, direkt vor dem MEMBER-Audit). Wer `member.status`
  neu schreibt, muss in `CommitteeSeatLifecycleTripwireTest` klassifiziert werden (Hook-Aufruf oder "legt nur neue Mitglieder an"); wer
  `committee_membership.until`/`auto_end_reason` schreibt, braucht einen Allowlist-Eintrag mit Begründung.
- **Gremiensitze durch Wahl (V1.9.103):** Eine Wahl mit `incumbent_policy` setzt Sitze nur über `ElectionSeatSuccession` (einziger Aufruf:
  `tallyElectionLocked`); `started_by_election_id`/`ended_by_election_id` schreibt nur diese Klasse (die manuelle Beendigung setzt
  `ended_by_election_id` auf null). Wahlen ohne Policy (vor V1.9.103) nehmen den alten Pfad. Policy, Rolle und Zielgremium sind nach dem Öffnen
  unveränderlich; die Nachfolge schreibt nie `AccountRole`/`member.role`.
- **Regelgremien und eingefrorener Abstimmungskreis (V1.9.112):** Ein Gremium mit `membership_mode = RULE` hat nie offene Sitzzeilen; `CommitteeSeatLifecycle.endSeatsForRuleMode` ist der zweite (und letzte) Schreiber eines nicht-null `auto_end_reason` (`RULE_MODE`), in derselben allowlisteten Datei wie der Hook. Wer Stimmrecht oder den Nenner eines Quorums prüft, nutzt `frozenOrLiveElectorate` (Momentaufnahme `vote_eligible_voter` / `election_eligible_voter` / Konsensieren-Runde, sonst live), **nie** `eligibleMemberIds` direkt (Tripwire: `CommitteeElectorateTripwireTest`). Regeln dürfen nur manuelle Gremien referenzieren (`RuleReferences`, gemeinsam für Gremien- und Mailingregeln, sperrt Gremienzeilen in DB-`ORDER BY id`). Eine kaputte gespeicherte Regel ist ein `ConflictException`, nie eine leere Menge. Details: `docs/features/rule-based-committees.adoc`.

## Veranstaltungs-Konferenzen (V1.9.113)

- Eine Veranstaltungs-Konferenz wird ausschließlich über `IEventService.joinEventOnline` (Mitglieder) bzw. `POST /api/events/{slug}/online-guest` (Gäste ohne Konto) betreten. Die gewöhnlichen Konferenz-RPCs lehnen Veranstaltungsräume über `EventConferenceGuard` ab; neue `roomId`-Methoden in `ConferenceService` erzwingen eine Entscheidung (Tripwire `EventConferenceGuardTripwireTest`).
- `event_conference` wird nur von `EventConferenceStore` geschrieben; LiveKit wird nie innerhalb von `transaction {}` aufgerufen (Claim-Protokoll mit `claim_token`). Das Zeitfenster berechnet nur `EventOnlineWindow`.
- Gast-Identitäten tragen das Präfix `evg-`; Gäste werden nicht in die Teilnehmerhistorie geschrieben. Details: `docs/features/event-online-participation.adoc`.

## Konferenz-Ansichten (V1.9.115)

- `conference_recording_speaker_span` (die geglättete Sprecher-Zeitleiste einer Aufzeichnung) wird **nur** von `RecordingSpeakerTimelineStore` gelesen und geschrieben (Tripwire `SpeakerTimelineAccessTripwireTest`), ist aus dem Gesamt-Backup ausgenommen (`OrganizationSchemaCatalog.EXCLUDED_TABLES`), wird zusammen mit den Rohdateien gelöscht (Erfolgszweig mit `keepRaw=false`, `deleteRecording`) und ist über keine API sichtbar. Sie trägt weder Namen noch Identitäten; Personen sind die opake ID der ersten Spurzeile.
- **Keine Identitäten, Namen, Dateinamen oder Pfade in Logs** der Sprecheranalyse, der Zeitleiste und des Sprecher-Layouts des Composers (nur Zählwerte); die Dateinamen im TRACK-Modus enthalten die Identität.
- Bild und Zeitleiste nutzen **dieselbe Uhr**: Versatz und `atempo` kommen aus `AudioWallClock` (Composer und `SpeechActivity`). Die Glättung (`SpeakerStageSmoother`) ist dieselbe Datei für die Live-Ansicht und die Aufzeichnung.
- Im Client werden Kachelelemente (und ihre `<video>`) nur **umgehängt**, nie neu erzeugt (`ConferenceViewStage.apply`); `applyTileGrid` schreibt nur Zahlen. Gespeichert wird im Browser nur GALLERY/SPEAKER, nie eine Person. Details: `docs/architecture/conference-views.adoc`, `docs/architecture/conference-recording.adoc`.

## Reaktionen und Wortmeldeliste (V1.9.127)

Details: `docs/features/video-conferencing.adoc`, Abschnitt "Reactions and raised hands". Kurzregeln:

- **Die Wortmeldeliste lebt nur im Server-Speicher** (`ConferenceHandQueueState`: Reihenfolge, `seq`, zufällige Prozess-Epoch): keine Datenbankzeile, keine gespeicherte Zeit, **kein Audit-Eintrag** (auch nicht, wenn die Moderation eine Hand senkt), in Logs nur Zählwerte (`ConferenceHandsLogPrivacyTripwireTest`). Zustand und Topic `lapis-conference-hands` berühren nur `ConferenceHandService` und `ConferenceHandQueueReconciler` (`ConferenceHandServiceEventGuardTripwireTest`). Neue `roomId`-Methoden in `IConferenceHandService` laufen über `authorize(roomId = roomId, ...)` (Event-Raum-Guard, Begegnungsraum-Ablehnung, einheitliche `ForbiddenException`).
- **Reaktionen sind Peer-Pakete** (`lapis-conference-reaction`): der Server sieht, zählt und speichert sie nie; `HAND` ist keine Reaktion. Der Raumschalter `setRoomReactions` wirkt nur über die Clients (`canPublishData` bleibt unberührt, der Chat nutzt es mit).
- **Overlays und Hand-Abzeichen sind Client-DOM und damit nicht in Aufzeichnung und Stream.** Das gilt nur, solange `LiveKitEgressClient` keinen Custom-Template-/Web-Egress kennt und die Aufzeichnung aus Rohspuren zusammengesetzt wird: `ConferenceReactionsRecordingTripwireTest` bricht sonst den Build.
- Im Client werden Reaktions-Schicht und Hand-Abzeichen **einmal mit der Kachel gebaut** und nur gefüllt bzw. ein-/ausgeblendet (`ConferenceViewStage` hängt Kacheln nur um).

## Timer in Videokonferenzen (V1.9.133)

Details: `docs/features/video-conferencing.adoc`, Abschnitt "Timer". Kurzregeln:

- **Der Timer lebt nur im Server-Speicher** (`ConferenceTimerState`: Phase, Restzeit auf der **monotonen** Uhr, `seq`, zufällige Prozess-Epoch): keine Tabelle, keine Migration, **kein Audit**, in Logs nur Zählwerte. "Abgelaufen" wird abgeleitet, nie gespeichert und erhöht kein `seq`. Die Wanduhr füllt nur das Paketfeld `t` (nie für Logik verwenden).
- **Absender ist nur der Server.** Topic `lapis-conference-timer` (`conferenceTimerPacket` im Client lehnt jedes Paket mit sendendem Teilnehmer ab; der Zweig steht in `LiveKitRoomSession` VOR der Teilnehmerprüfung). Zustand und Topic berühren nur `ConferenceTimerService`, `ConferenceTimerReconciler`, `Application.kt` (und `LiveKitAdminClient.kt` für das Größenlimit) -- Tripwires `ConferenceTimerServiceEventGuardTripwireTest`, `ConferenceTimerNoPersistenceTripwireTest`, `ConferenceTimerLogPrivacyTripwireTest`, `ConferenceTimerClientTripwireTest`.
- **Der Moderator-Check steht in `moderatorRoom` vor dem Rate-Limit und vor jeder Änderung**; jede Ablehnung ist dieselbe `ForbiddenException` (Raum unbekannt/beendet/Begegnung/Breakout, kein Moderator, nicht anwesend). Neue `roomId`-Methoden laufen über `authorize(roomId = roomId, ...)`.
- **Neue Dienste kommen in `Application.kt` immer ans ENDE** (Kilua nummeriert Routen nach Deklarationsreihenfolge). Der Tripwire pinnt die Tail-Reihenfolge: `IConferenceTimerService`, danach `IAgreementTextAdminService`, danach `IPulpitRecordingService`, danach `IMemberFinderService` (die letzte Registrierung, seit V1.9.144).
- **`SendData` hat ein Größenlimit je Topic** (`sendDataPayloadLimit` in `LiveKitAdminClient.kt`: Moderation 128, Wortmeldungen 6144, Timer 256, sonst 16). Ein neues Topic ohne Eintrag fällt auf 16 Byte zurück und wirft eine `IllegalArgumentException` (keine `LiveKitAdminException`!) -- Fake-Clients in Tests merken das nicht, `LiveKitAdminClientPayloadLimitTest` schon.
- **Nicht in Aufzeichnung, Stream und Video-Overlay.** Der Timer ist Seitenelement (Raw-DOM, einmal gebaut, danach nur befüllt); die `ConferenceTimer*`-Dateien des Clients dürfen weder `canvas` noch `captureStream`/`publishTrack` enthalten, die `ConferenceVideoOverlay*`-Dateien das Wort Timer nicht (`ConferenceTimerRecordingTripwireTest`). Der Countdown nutzt `performance.now()`, nie `Date.now()`.
- **Der Puls der letzten 10 s steht nur im `no-preference`-Block** von `theme.css` (ein `animation: none` im Reduktionsblock am Dateiende bricht R54); der Client setzt `is-pulsing` bei "weniger Bewegung" ohnehin nicht.

## Rederecht im Begegnungsraum (V1.9.138)

Details: `docs/architecture/encounter-space.adoc`, Abschnitt "Temporary floor grant"; Feature-Seite `docs/features/encounter-space.adoc`. Kurzregeln:

- **Der Zustand lebt nur im Server-Speicher** (`EncounterFloorState`: Halter, Restzeit auf der **monotonen** Uhr, Serial, höchstens 64 Sitzungen): keine Tabelle, keine Migration, **kein Audit**, in Logs nie Person, Raum oder Name über DEBUG. Ein Neustart beendet jedes Rederecht (der erste Poller-Lauf trennt Gemeinde-Publisher).
- **Die Berechtigung ist die EINE `encounterFloorPermission(granted, canPublishData)`** (`LiveKitAdminClient.kt`): `canSubscribe = true`, `canPublishSources = ["MICROPHONE"]` **auch beim Entzug** (leere Liste = "alle Quellen" bei LiveKit). Nie `conferencePermission` (Kamera, Bildschirm), nie ein Token mit `canPublish = true` für die Gemeinde.
- **Zustand setzen VOR `UpdateParticipant`**, sonst trennt der Poller den frisch erteilten Sprecher. **Entzug**: `EncounterFloorEnforcer.revoke` (drei Versuche, dann `removeParticipant`, dann "revoking" mit Wiederholung alle 5 s) -- kein Pfad löscht einen Grant ohne erfolgreichen Entzug/Trennung.
- **Nur PULPIT/STEWARD des Raums, anwesend, Raum offen und nicht archiviert** erteilen/verlängern (`floorAuthorityInTx`); jede Ablehnung ist dieselbe nackte `ForbiddenException`. BOARD/ADMIN ohne Amt dürfen nicht. `endFloor` darf zusätzlich der Halter. Ziel: Gemeinde-Rolle, anwesend, nicht stummgeschaltet, nicht am Tisch, verbunden, ohne Track.
- **Topic `lapis-encounter-floor` sendet nur `EncounterFloorEnforcer`** (`ON` an alle, `YOU` an den Halter, `OFF`); `SendData`-Limit 64 Byte in `sendDataPayloadLimit`. Der Client nimmt das Paket nur ohne sendenden Teilnehmer an (Zweig in `LiveKitRoomSession` VOR der Teilnehmerprüfung) und parst es mit dem strengen `encounterFloorPacket` aus `lapis-shared`.
- **Client**: ein Zuhörer hat genau EINEN Medienaufruf, `floorMicrophone(FloorGrant, on)`; `FloorGrant` hat einen privaten Konstruktor und entsteht nur in `EncounterFloor.kt` aus einem Serverpaket. Das Mikrofon wird nie von selbst eingeschaltet (Klick) und beim Ende **unpublisht** (nicht nur stummgeschaltet). Countdown nur über `performance.now()`, nie `Date.now()`; die Gemeinde sieht **keinen Namen**.
- **Kilua überträgt nur den Exception-Typ**: die Conflict-Gründe erreichen den Client nicht; er verhindert sie vorab (Knöpfe deaktiviert) und zeigt sonst den einen festen Text. Ein `Unit`-Ergebnis beantwortet ein Test-Stub mit `"{}"`, nicht `"null"` (sonst scheitert die Dekodierung still).
- Neue RPC-Methoden stehen **am Ende** von `IEncounterSpaceService` (`grantFloor`, `extendFloor`, `endFloor`, `floorState`); die Dienstreihenfolge in `Application.kt` ändert sich nicht. Tripwires: `EncounterPrivacyTripwireTest` (V1.9.138-Block), `ClientEncounterPrivacyTripwireTest` (V1.9.138-Block), `EncounterFloorI18nCatalogTest`.

## Video-Overlays (V1.9.129)

Details: `docs/features/video-overlays.adoc`. Kurzregeln:

- **Video-Overlays stecken IM gesendeten Kamerabild** und sind damit in Aufnahme und Livestream -- das Gegenteil der DOM-Overlays aus V1.9.127 (Reaktionen, Hand-Abzeichen). Im Code `VideoOverlay*`, im Text "Video-Overlays (im Kamerabild)"; beide Begriffe nie vermischen.
- `video_overlay_asset` schreibt **nur** `VideoOverlayStore` (Tripwire `VideoOverlayStoreTripwireTest`); kein SVG, nie `SvgCrestSanitizer`; Auslieferung nur authentifiziert und same-origin ohne CORS.
- Clientseitig gelangen Bilder **nur** über `fetch` -> Blob -> `ImageBitmap` ins Canvas (nie `<img>`, `crossOrigin`, Object-URL; sonst Canvas-Taint und `new VideoFrame(canvas)` wirft). Die einzige URL-Quelle ist `videoOverlayAssetPath`; ein **Prozessor pro Track** (nur `ConferenceBackgroundController` ruft `setProcessor`/`stopProcessor`), Overlay ohne Effekt = OVERLAY_ONLY ohne MediaPipe (Tripwire `VideoOverlayClientTripwireTest`). Kein Spiegeln.

## Kanzel-Aufzeichnung im Begegnungsraum (V1.9.143)

Details: `docs/features/pulpit-recording.adoc`, Abschnitt "Pulpit recording (V1.9.143)" in `docs/architecture/encounter-space.adoc`. Kurzregeln:

- **Opt-in, standardmäßig aus, doppelt geschaltet**: Instanz-Schalter (ADMIN, mit bestätigter Hinweis-Version `PULPIT_RECORDING_NOTICE_VERSION`) und Raum-Option (`encounter_space.pulpit_recording_allowed`, BOARD/ADMIN, nur bei geschlossenem Raum einschaltbar, immer ausschaltbar). Die Rechtslage ist landesabhängig: Lapis Cloud entscheidet sie nicht, der Betreiber bestätigt die Prüfung für sein Land. Die Spalten sind NICHT im Sammel-Update `updateOrganizationSettings`.
- **Wer aufgezeichnet wird, entscheidet allein `PulpitRecordingSources.select`** (aktiver PULPIT-Amtsträger, anwesend, in LiveKit verbunden; nie STEWARD, nie die Gemeinde, nie eine Person mit befristetem Rederecht; höchstens 2 je Raum, 8 je Server). Kein Parameter eines Clients nennt eine Person. `startParticipantFileEgress` rufen nur `RecordingPoller` und `PulpitRecordingCoordinator` auf (`PulpitRecordingTripwireTest`); es gibt KEINE neue Egress-Methode, und `ConferenceRecordingService` bleibt für Begegnungsräume gesperrt.
- **Ein Gate** (`officerTargetInTx`): jede Ablehnung ist dieselbe nackte `ForbiddenException`; ein Stopp braucht weder Schalter noch Hinweis-Version. LiveKit-Aufrufe NIE innerhalb von `transaction {}`; nach dem Egress-Start wird in der Abschluss-Transaktion neu geprüft und auditiert.
- **Kein Speicher über das Nötige hinaus**: Zustand und Zustimmung (`PulpitRecordingState`) nur im Arbeitsspeicher; die Marker-Tabelle `pulpit_recording_document` hat keine Personenspalte. Logs und Audit nennen keine Person, Zahl, keinen Pfad oder Titel. Das Topic `lapis-encounter-recording` braucht einen Eintrag in `sendDataPayloadLimit`.
- **Banner nie auf Verdacht aus**: bei unerreichbarem LiveKit bleibt der Zustand. Echo-Regel: kein Rederecht während einer Aufnahme (Start verweigert, Rederecht beendet sie).
- **Löschung ist hart** (Bytes und Zeilen, auch über `DocumentService.deleteDocument`); Aufbewahrung unbegrenzt, nichts wird automatisch gelöscht. Ablage `BOARD_ONLY`, Ordner `BOARD_ONLY` (`archiveGeneratedFile(folderAccessLevel = ...)`).
- **Texte**: Agreement-Kinds `REC_ROOM`/`REC_PULPIT` mit Platzhaltern je Raumprofil; die zwei alten Einwilligungstexte bleiben byteweise gleich, in Räumen mit Option gelten die Varianten `EncounterRecordingConsentDisclaimer`/`EncounterAssemblyRecordingConsentDisclaimer`. Das Wort Kanzel/Podium im Client nur über `EncounterVocabulary`.

## Mitglieder-Finder (V1.9.144)

Details: `docs/features/member-finder.adoc`, `docs/architecture/member-finder.adoc`. Eine Parteimitgliedschaft ist ein besonders geschütztes Datum (Art. 9 DSGVO). Kurzregeln:

- **Opt-in, standardmäßig aus**: Instanz-Schalter nur über `updateMemberFinderEnabled` (ADMIN, mit bestätigter Hinweis-Version `MEMBER_FINDER_NOTICE_VERSION`), nie über das Sammel-Update. Die Rechtslage ist landesabhängig: Lapis Cloud entscheidet sie nicht, der Betreiber bestätigt sie.
- **Die Region wird nie gespeichert**: `MemberFinderRegion.keyOf` leitet sie bei jeder Abfrage aus der PLZ ab (leeres Altland zählt hier NICHT als Deutschland). Der einzige Regionsschlüssel steht in `member_finder_region_band` (ohne Mitgliedsspalte). `MemberFinderEligibility` ist der einzige Leser von PLZ, Land und Geburtsdatum.
- **Schwelle 5 ist fest** (`MEMBER_FINDER_MIN_REGION_SIZE`, kein Setting, keine Umgebungsvariable); nur die Bänder 5-9 / 10-19 / 20+, einmal je Organisationstag neu berechnet (`MemberFinderRetention`), nie bei Beitritt oder Austritt.
- **Neutralität für den Absender wird im Datenmodell durchgesetzt**: `MemberFinderRequestStore.outgoingStateOf` ist die einzige Sender-Sicht (`PENDING`/`ENDED`/`WITHDRAWN`/`CONNECTED`); der gespeicherte Status (`DECLINED`) verlässt den Server nie (auch nicht im DSGVO-Export). Findet sich niemand, entsteht eine Phantom-Anfrage (`to_member_id` NULL). Jede Ablehnung zu fremden Anfragen ist dieselbe `NotFoundException("Not found")`. Geht der Empfänger, wird die Zeile gelöst (nicht gelöscht).
- **Alle Schreibzugriffe unter einer globalen Sperre** (`MemberFinderSettingsStore.lockAndLoad`, Zeilensperre der Organisations-Einstellungen); Lesen ohne Sperre. Die Lifecycle-Hooks der Statuspfade (`MemberFinderLifecycle.onStatusChange`) löschen/lösen nur und nehmen keine Sperre.
- **Nichts auditiert, nichts geloggt**: Opt-in, Opt-out, Anfragen und Sperren stehen NICHT im Audit-Log (unlöschbar); auditiert werden nur der Instanz-Schalter (Version + Hash, keine Zahl) und die Veröffentlichung des Einwilligungstexts. Kein Log nennt Mitglied, Region, Notiz oder Zahl (`MemberFinderTripwireTest`). Keine E-Mail-Benachrichtigung.
- **Opt-out ist immer sofort möglich** (auch bei ausgeschaltetem Schalter, nie gedrosselt); die 24-Stunden-Sperre gilt nur für eine erneute Einwilligung nach Widerruf. Schalter AUS löscht Anfragen/Sperren/Bänder und beendet Einwilligungen per System-Widerruf (`INSTANCE_OFF`), ohne sie später wiederzubeleben.
- **Datenschutzseite (V1.9.147)**: `/datenschutz` nennt den Finder nur, solange er `active()` ist (Schalter an UND aktuelle Hinweis-Version bestätigt); `registerLegalRoutes` bekommt dafür den Provider `memberFinderEnabled: () -> Boolean` (pro Request, "aus" bei Fehler), beide Varianten sind vorberechnet, `max-age=300`. Der Text ist eine Vorlage mit Prüfhinweis; jede Aussage muss gegen den Code stimmen (kein "alles gelöscht").
- **Texte**: Agreement-Kind `FINDER` (Platzhalter ein Entwurf); eine neu veröffentlichte Fassung beendet alle wirksamen Einwilligungen; der Zähler "ältere Bestätigungen" ist für `FINDER` immer 0.
- **Backup und DSGVO**: die vier Tabellen sind aus dem Backup ausgeschlossen; `MemberFinderPersonalData` exportiert Ausgänge nur in der Sender-Sicht. Neue Methoden von `IMemberFinderService` kommen ans ENDE des Interface; der Dienst wird als letzter in `Application.kt` registriert.

V1.9.145 (Client) -- drei Kurzregeln:

- **Im Client erscheint keine Mitglieds-ID**; `data-*` nur für `data-request-id`, `data-connection-id` und `data-block-id` (zufällige UUIDs). Der Name der Gegenseite steht nur in der Verbindungskarte, nie in `aria-label`, Live-Region, Toast oder Titel; Notiz und Name nur über `untrusted*` (`ClientMemberFinderPrivacyTripwireTest`).
- **Die Hinweisabsätze stehen wörtlich in `MemberFinderSettings.kt`** (`MemberFinderNoticePinTest`); eine Textänderung braucht eine neue Version. Der Lösch-Satz ("Gut zu wissen: ...") gehört nicht zum gehashten Text V1.
- **`getState` nur für ACTIVE aufrufen** (der Server verweigert alle anderen mit einem nackten Forbidden und es kostet eine der 240 Lese-Anfragen je Stunde); nach Aktionen nur die betroffene Liste neu lesen, kein Polling.

## Vertragstexte (V1.9.134)

Details: `docs/features/agreement-texts.adoc`. Kurzregeln:

- **`agreement_text_version` ist append-only**; sie und `agreement_text_draft` liest und schreibt **nur** `AgreementTextStore` (Ausnahme: der lesende DSGVO-Contributor). Nie `update`, `deleteWhere`, `upsert` auf der Versionstabelle; der einzige DELETE der Funktion löscht den Entwurf (Tripwire `AgreementTextStoreTripwireTest`).
- **Nie der Text in Audit oder Log**: der Audit-Snapshot hat nur `event`, `kind`, `versionLabel`, `sha256`, `charCount`, `validFrom` (+ `sequenceNo`); Logzeilen nennen weder Text noch Hash noch Label (`AgreementTextPrivacyTripwireTest`).
- **Die Platzhalter-Konstanten nie ändern** (`MembershipAgreementDisclaimer`, `FriendTermsDisclaimer`: Text, `VERSION`, `SHA256`): bestehende Nachweise beziehen sich darauf (`AgreementTextBuiltinPinTest` pinnt Label und Hash als Literale). Eine neue Fassung kommt über die Verwaltung.
- **Ein neues Label darf nie dem eingebauten Label der Art gleichen** (der Nachweis wäre mehrdeutig); ein Text gleich dem Platzhalter wird abgelehnt.
- **Hash = `sha256("$label\n$body")` über den normalisierten Text** (`AgreementTextRules.normalizeBody`); der Client rechnet nie selbst.
- Ein Test, der eine Fassung veröffentlicht, **räumt in `afterEach` per Test-SQL auf** (`AgreementTextTestState.reset()`): die H2 ist je Test-JVM geteilt, sonst bricht jede Registrierung mit dem Platzhalter.
- Kilua überträgt nur den Exception-**Typ**: jeder unterscheidbare Fehler braucht einen eigenen Typ und einen festen Toast (`AgreementTextOutdated/Invalid/VersionLabelTaken/DraftChangedException`).
- **Eintrittshinweise Begegnungsraum (V1.9.148, Arten `ENC_CHURCH`/`ENC_ASSEM`)**: Ein ENC-Label ist **über beide Arten eindeutig** und darf kein Label sein, das der Code je geschrieben hat (`EncounterConsentLabels.reserved`), weil `encounter_consent_acknowledgment` keine Art-Spalte hat (und nie eine bekommen darf). `+rec` ist reserviert (das System hängt es an Räume mit Aufzeichnung an), also höchstens 46 Zeichen. Der Aufzeichnungsabsatz gehört dem Code, nie dem Admin. Die vier Altobjekte (`EncounterConsentDisclaimer` usw.) **nie ändern** (Nachweis, `@Deprecated`, werden nicht mehr ausgeliefert); ausgeliefert wird nur `encounterConsentFor`. Zählung der Bestätigungen bleibt 0.
- **R59/E3d**: `.lapis-agreement-frame` ist der einzige Scroller dieser Klasse und nur ab 768 px (`ScrollSurfaceTripwireTest`); unter 768 px wächst der Rahmen mit dem Text. Kein `overflowY`/`overflow-auto` im Kotlin-Code.

## Anmelde-/Einwilligungsseiten (V1.9.135)

Details: `docs/features/auth-and-consent-pages.adoc`. Kurzregeln:

- Jede HTML-Seite der Anmelde-/Einwilligungsflüsse läuft über `renderAuthPage` und wird nach `applyAuthPageHeaders` beantwortet (genau einmal je Antwort). Kein Raw-String-HTML, kein `unsafe {}`, kein `<script>`, kein `style=`.
- Seiten, deren Formular-POST mit einem 302 an einen fremden Origin antwortet (Einwilligungsseiten: fremde `redirect_uri`; Heimatserver-Formular: fremder `authorization_endpoint`), tragen nie `form-action` in der CSP (`AuthFormAction.UNRESTRICTED`); Chrome prüft das Redirect-Ziel dagegen. Nur Fehlerseiten (ohne Formular) behalten `'self'`. Der Aufrufer-Wert wird per Route-Test auf den ausgelieferten Headern geprüft.
- Formularseiten mit Origin-Prüfung (`isSameOriginRequest`) nie mit `Referrer-Policy: no-referrer`: der Browser sendet sonst `Origin: null` und der POST scheitert mit 403.
- Fehlertexte sind Enum-Konstanten, nie Nutzereingaben.
- V1.9.136: Ausweis-Prüfseiten (`/ausweis`) und die Mailing-404/429-Seiten (`/m/c/`) laufen ebenfalls über `renderAuthPage`, werden aber mit `applyPublicPageHeaders` beantwortet (nicht `applyAuthPageHeaders`): die URL trägt einen Ausweis-Code bzw. ein Tracking-Token, also `Referrer-Policy: no-referrer`; es gibt kein Formular.
- Die negative Ausweisseite bleibt byte-identisch und neutral (kein `--error`, kein `role="alert"`, nichts Eingabeabhängiges).

## Öffentliche Veranstaltungsseite (V1.9.128/V1.9.140)

Details: `docs/features/public-event-page.adoc`. Kurzregeln:

- Token- und Folgeseiten (Anmelde-POST, danke, Warteliste, abgebrochen, Storno, Zahlung, Ticket inkl. `ticket.svg`/`ticket.pdf`) setzen `X-Robots-Tag: noindex, nofollow` als **erste Anweisung** im Handler (`markEventNoIndex()`, vor Rate-Limit und Fehlerbehandlung, damit auch 400/404/429/500 und Redirects ihn tragen) und rendern mit `skeleton(noIndex = true)`; dasselbe gilt für alle Fehlerseiten der Familie. Die Detailseite (200) nie. Nie zwei `name="robots"`-Metas in einem Dokument.
- Reihenfolge der Detailseite: Fakten, Titelbild, Beschreibung, Hinweis, Anmeldekarte (`id="anmeldung"`); der Link „Zur Anmeldung“ nur bei möglicher Anmeldung und nicht leerer Beschreibung, ohne `target`.
- Artikel-Textlinks: `ArticleMarkdown.render(md)` ohne `embedHost` bleibt byte-gleich (Golden-Test); nur im Embed-Modus bekommen absolute Links auf den eigenen Host `target="_top"`, jedes `_blank` trägt `noopener`.

## Lasttest-Werkzeug (V1.9.149)

Details: `scripts/loadtest/README.adoc`, Laufzettel `docs/architecture/encounter-load-test.adoc`. **Geschrieben und gegen einen Fake getestet, nie gegen eine echte Instanz gelaufen.** Kurzregeln:

- **Die Allowlist ist standardmäßig leer** (`LAPIS_LT_ALLOWED_HOSTS`, `LAPIS_LT_ALLOWED_LIVEKIT_HOSTS`): nichts ist erlaubt, bis der Betreiber es in seiner Umgebung einträgt. **Keine Hostnamen, IP-Adressen oder Domains im Repo** (öffentlich; die Topologie liegt in Lapis-Cloud-Ops), außer Loopback und `example.*`/`*.test` -- `LoadTestNoHostLiteralTripwireTest` prüft Werkzeug und Laufzettel.
- **Geheimnisse nur aus der Umgebung** (`LAPIS_LT_PASSWORD`, Konto-Muster); sie stehen nie in Argumenten, Konfigurations-JSON oder Ausgaben. Alle Ausgaben laufen durch `redact()`; Fehler werden nur als Art gemeldet, nie Antworttext.
- **Testkonten-Präfix ist Pflicht** (`LT-`): ein Konto, dessen Anzeigename im `enterSpace`-Ergebnis nicht so beginnt, handelt nicht weiter; eine Person ohne Präfix in `listPresent` stoppt den Lauf.
- **Nie Mitglieds-IDs, E-Mails, Tokens oder Raumnamen in Ergebnissen** (Teilnehmende heißen `p01`..`p40`). Das Werkzeug nutzt nie Admin- oder Amtsträger-Zugänge und löscht keine Konten (`cleanup` gibt nur die E-Mail-Liste aus).
- **`scripts/loadtest/lib/routes.json` ist an die Methodenreihenfolge von `IEncounterSpaceService` gebunden** (Route = `/rpc/routeEncounterSpaceServiceManager<Index>`): Tripwire `LoadTestRouteIndexTripwireTest`, Drahtformat `LoadTestWireContractTest` (gleiche Fixtures wie die Node-Tests). **Neue RPC-Methoden ans ENDE** des Interfaces, nie in die Mitte.
- Node-Tests (`node --test scripts/loadtest/test/*.test.mjs`) laufen als eigener CI-Job `loadtest`, ohne `npm install`; die optionale Medienschicht (`@livekit/rtc-node`) wird in CI nie installiert und nur per `import()` bei `--media=livekit` geladen.

## Begegnungsraum: Raumvorlagen (V1.9.152)

Details: Abschnitt "Stage 2c" in `docs/architecture/encounter-space.adoc`. Kurzregeln:

- **Die Vorlage beschreibt den Raum, nie eine Person** und ist für alle Anwesenden gleich (`encounter_space.room_layout`, V109). Sie geht nie in `encounterConsentFor` ein (der Eintrittshinweis und sein Hash hängen nicht von ihr ab) und nie in eine Platz- oder Tischlogik. `EncounterRoomLayouts.kt`/`EncounterRoomLayoutChoice.kt` kennen keine Person (Tripwire `ClientEncounterPrivacyTripwireTest`, V1.9.152-Block).
- **Die Regel "welche Vorlage zu welcher Raumart passt" steht an EINER Stelle**: `EncounterRoomLayout.allowedFor` in `lapis-shared` (Server, Formular und der SQL-CHECK `chk_encounter_space_room_layout_profile` spiegeln sie). Eine neue Vorlage ändert alle vier Stellen.
- **Die zehn Zeichnungen in `encounter-objects-v1/` sind unveränderlich versioniert**: jede Änderung an einer SVG braucht den neuen SHA-256 in `lapis-client/build.gradle.kts` (`videoEffectExpectedSha256`), sonst bricht `stageVideoEffectAssets` auf dem Staging UND auf der Webpack-Kopie (`check` allein prüft die Webpack-Kopie nicht; `:lapis-client:jsBrowserProductionWebpack` ist Pflicht). Eine inhaltlich neue Fassung bekommt einen neuen Ordner (`encounter-objects-v2`).
- Im Client verzweigt nichts auf das Profil außer `EncounterVocabulary.kt` (Tripwire `ClientEncounterVocabularyTripwireTest`): die Raumvorlagen-Texte stehen dort (`layoutName`, `layoutLockedReason`, ...).
