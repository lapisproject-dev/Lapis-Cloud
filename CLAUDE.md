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
- Bild und Zeitleiste nutzen **dieselbe Uhr**: Versatz und `atempo` kommen aus `AudioWallClock` (Composer und `SpeechActivity`); die `atempo`-Entscheidung kommt **nur** aus `RecordingComposeAudioInput.effectiveTempo()` (Composer und Zeitleiste, V1.9.154). Die Glättung (`SpeakerStageSmoother`) ist dieselbe Datei für die Live-Ansicht und die Aufzeichnung.
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
- Reihenfolge der Detailseite: Fakten, Titelbild, Beschreibung, Hinweis, Anmeldekarte (`id="anmeldung"`), Teilen-Leiste; der Link „Zur Anmeldung“ nur bei möglicher Anmeldung und nicht leerer Beschreibung, ohne `target`.
- Artikel-Textlinks: `ArticleMarkdown.render(md)` ohne `embedHost` bleibt byte-gleich (Golden-Test); nur im Embed-Modus bekommen absolute Links auf den eigenen Host `target="_top"`, jedes `_blank` trägt `noopener`.

## Teilen-Leiste (V1.9.217)

Details: `docs/features/public-sharing.adoc`. Kurzregeln:

- **Allowlist: genau drei Seiten** (`/veranstaltung/{slug}`, `/aktuelles/{slug}`, `/s/{id}`, nur Standard-Darstellung). `renderShareBar(` darf nur dort aufgerufen werden (`ShareBarTripwireTest`). Nie in Embed-, Token-, Fehler- oder Übersichtsseiten.
- **Ein `ShareBarModel?` bestimmt Leiste UND CSP.** Die Route baut es (`ShareConfig.barFor`), leitet daraus `scriptSrcSelf` ab und reicht dasselbe Modell an den Renderer; nie getrennt entscheiden (auch nicht im 304-Pfad von `/s/{id}`).
- **Share-URLs entstehen nur aus der konfigurierten Basis-URL**, nie aus dem Host-Header oder `X-Forwarded-*`. Ziel-Hosts sind feste Präfixe in `ShareLinks`; Nutzerwerte sind nur prozent-kodierte Parameter.
- **`unsafe` nur für den JSON-LD-Block** der Veranstaltung, escaped nach der Serialisierung (`EventShareMeta.escapeForScriptBlock`). `share.js` bleibt eine Konstante ohne `innerHTML`/Netz/Speicher.
- **Route-Funktionen haben `shareConfig = ShareConfig.DISABLED` als Default** (Bestands-CSP-Tests bleiben byte-gleich); die Produktion übergibt die geladene Konfiguration, der Tripwire prüft die Verdrahtung in `Application.kt`.
- **Compose: `${LAPIS_SHARE_NETWORKS-all}` ohne Doppelpunkt**, sonst wird „nicht gesetzt“ zu leer und die Leiste überall abgeschaltet; in `.env.example` bleibt die Variable auskommentiert.

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

## Begegnungsraum: Rasterkarte (V1.9.166)

Details: Abschnitt "Stage 3 wave 1" in `docs/architecture/encounter-space.adoc`. Kurzregeln:

- **Positionen gibt es nur in `EncounterMapState`** (Arbeitsspeicher, ein Monitor): kein `suspend`, keine Transaktion, kein Logger, kein Audit, keine Tabelle, keine Spalte, kein Verlauf (Tripwire `EncounterPrivacyTripwireTest`, V1.9.166-Block). Neue Wege, die eine Person aus dem Raum oder von der Karte nehmen (Sitzen, Tisch, Amt, Moderation, Ende, Poller), rufen `mapState.leave`/`leaveAll`/`clear` auf; der Reconciler (`EncounterMapBroadcaster`, alle 5 s) fängt Statuswechsel mitten in der Sitzung ab -- das Fenster ist dokumentiert, nie "sofort" behaupten.
- **Das Topic `lapis-encounter-map` geht nur über `sendDataTo` an Mitglieder** (nie `sendData`, nie an Gäste), gesendet vom Broadcaster außerhalb jeder Transaktion; die Grenze (4096 Bytes) steht in `sendDataPayloadLimit` -- ohne diesen Eintrag gilt der 16-Byte-Rückfall und jeder echte Versand scheitert (Fake-Clients merken das nicht).
- **Teleport-Sperre 1 s (V1.9.168)**: Anker-Knöpfe nutzen `aria-disabled` + `is-cooling`, nie natives `disabled`; jeder Klick wird quittiert (innerhalb der Sperre eine Ansage pro Phase, kein Request). Ein Sprung aufs eigene Feld setzt keine Sperre.
- **Die fünf Methoden `mapEnter`..`mapSnapshot` stehen am Ende von `IEncounterSpaceService`** (Indizes 28 bis 32 in `scripts/loadtest/lib/routes.json`, sonst bricht `LoadTestRouteIndexTripwireTest`). Es gibt keinen Koordinaten- und keinen `memberId`-Parameter. `mapStep`/`mapTeleport` berühren die Datenbank nie; jede Ablehnung des Gates ist dieselbe nackte `ForbiddenException`, ein Gast wird **vor** `loadVisibleSpace` abgewiesen (kein Existenz-Orakel). Drosseln werfen `ServiceBusyException`, nie `ConflictException`.
- **`+map` gehört dem Code** (wie `+rec`): `EncounterConsentLayout.MAP_SUFFIX`, Absatz `mapParagraph` (ENTWURF ohne Rechtsprüfung, per Hash gepinnt in `EncounterMapConsentTextTest`), `encounterConsentFor(..., mapEnabled)`. `encounter_consent_acknowledgment.consent_version` ist seit V111 `VARCHAR(58)`; `ENC_LABEL_MAX` bleibt 46. Ein Admin-Label kann `+` nicht enthalten.
- **V1.9.167 (Kartenebene, Theater):** Die Karte steht nicht mehr in der Bühne, sondern in `EncounterMapOverlay` (Dialog, `position: fixed`, endet über dem Dock; Kind des Raums neben dem Dock, wie dieses mit `margin: 0 !important`). Der Dock-Knopf "Karte öffnen" und sein Zwilling in "Mehr" entstehen **nur** in `buildMapButton()`, das `ensureMap()` nach `if (me.isGuest) return` aufruft (Tripwire in `ClientEncounterPrivacyTripwireTest`); `EncounterMapOverlay.kt` gehört zu den Karten-Dateien (kein Speicher, keine Konsole, kein `title =`). Schließen verlässt die Karte nie. Der Saal MIT Tischen ist ein Theater (`EncounterSceneLayout(theatre = ...)`, einmal im Konstruktor aus `HALL && space.tables.enabled`; Halle = Gruppe "Sitzplan", Parterre mit den Tischen, linke/rechte Loge mit je einem Block jeder Reihe): kein `transform`/`filter`/`container-type`. Die Ebene hat nie ein eigenes Scroll-Verhalten außer dem Panel (Ledger E1).
- Der Client baut die Karte nur, wenn `listPresent` zeigt, dass die Person kein Gast ist; er verzweigt nur auf `space.mapEnabled`, nie auf das Profil ("Zum Podium"/"Zur Kanzel" steht nur in `EncounterVocabulary`). Kein Name im Datenkanal, am Brett oder in einem Attribut; die Namen stehen nur in der Liste "In Ihrer Nähe". Figuren bewegen sich per `transform` auf der Figur selbst, nie auf einem Vorfahren des Docks; die Transition steht unter `prefers-reduced-motion: no-preference` und im einen Reduktionsblock (R54).

## Aufzeichnung in Veranstaltungsräumen (V1.9.156)

Details: `docs/features/event-capture.adoc`, Abschnitt "Event rooms (V1.9.156)" in `docs/architecture/conference-recording.adoc`. Kurzregeln:

- **Genau zwei Aufrufstellen.** `EventConferenceGuard.requireEventCaptureAllowed` steht nur in `ConferenceRecordingService.startRecordingInTransaction` und `ConferenceStreamingService.startStream` (Tripwire `EventConferenceGuardTripwireTest`); überall sonst bleibt `requireNotEventRoom`. Die Entscheidung: Instanzschalter an **mit aktuellem Betreiberhinweis**, Veranstaltung freigegeben (`event.capture_allowed`), Lapis-Konferenz.
- **Die Freigabe ist unveränderlich, solange der Raum aktiv ist** (`EventCaptureLock.isActive`: Fenster offen, Raum lebt, Aufzeichnung/Stream läuft, offene Teilnahme) -- in beide Richtungen, und auch das Einschalten des Instanzschalters. Gesperrt wird ab Fensteröffnung, nicht erst bei der ersten Teilnahme: Gäste ohne Konto haben keine Teilnahmezeile, aber ein gültiges Token.
- **Die Einwilligung erzwingt nur `EventOnlineJoin`** (Mitglieder und Gäste ohne Konto), nach Sperrreihenfolge **Veranstaltung vor Raum** (`lockEventForUpdate` zuerst, dann die Raumzeile). Kein anderer Weg mintet ein Token für einen Veranstaltungsraum (`EventCapturePrivacyTripwireTest`). Ohne veröffentlichten `EVENT_REC`-Text wird niemand eingelassen -- der Platzhalter ist nie eine Grundlage.
- **`EventGuestNotice` nie ändern** (Nachweis früherer Bestätigungen, byteweise gepinnt). Die Fassung für freigegebene Veranstaltungen ist `EventGuestNoticeCapture` (eigene Version und eigener Hash).
- **Der Nachweis** (`event_capture_acknowledgment`) hat weder Namen noch Raum noch Uhrzeit (`acknowledged_on` ist ein DATE); er wird nur von `EventCaptureAcknowledgments` geschrieben. Kein `ON DELETE CASCADE`: wer eine Veranstaltung hart löscht, löscht ihre Nachweise zuerst (`EventStore.deleteEventHard`).
- **Neue RPC-Methoden immer ans Ende** der Interfaces (Route = Deklarationsreihenfolge). Signaturen bestehender Methoden (`joinEventOnline`) bleiben; neue DTO-Felder bekommen Standardwerte.
- **Das Banner-Thema `lapis-event-capture`** steht in `sendDataPayloadLimit`; der Client nimmt nur Pakete **ohne** sendende Person an (ein Gast darf Daten senden). Gesendet wird nach dem Commit (`EventCaptureLiveBroadcaster`), nie in einer Transaktion; ein Fehler schaltet das Banner nie ab.
- **V1.9.158 (nicht verfügbare Optionen).** `startRecordingInTransaction` und `startStreamInternal` prüfen die **Moderatorin vor** `requireEventCaptureAllowed` (die typisierten Gründe `EventRoomCaptureBlockedBy…Exception` dürfen nur Moderatorinnen erreichen; `EventConferenceGuardTripwireTest` prüft die Reihenfolge, dass nur `EventConferenceGuard` sie wirft und `eventCaptureAvailability(` nur dort gelesen wird). Der DTO-Pfad nutzt `eventCaptureStateFor`, nie `requireEventCaptureAllowed` (Zwei-Aufrufer-Wächter). Jede neue Service-Exception braucht einen Text in `AppState.typedRefusalText`/`handleGuardedFailure` oder einen lokalen `catch` (`ClientServiceExceptionMappingTripwireTest`); eine nicht verfügbare Schaltfläche ist `aria-disabled` + `aria-describedby`, nicht `disabled`.

## Ausschreibungen (V1.9.201)

- **Sichtbarkeit nur über `TenderVisibilityPolicy`**; keine andere Stelle entscheidet über Modus, Frist oder Rolle, um ein fremdes Angebot oder einen Zähler zu zeigen. Reads schreiben nie (Status wird berechnet), nur erfolgreiche Schreibwege persistieren `CLOSED`.
- **Befangenheit nur über `TenderConflicts`** (jede Angebotszeile zählt, auch zurückgezogene). Die Governance-Haken subtrahieren die Menge, rufen `eligibleMemberIds` nicht erneut auf.
- **Sperrreihenfolge:** Antrag -> Ausschreibung -> Angebote -> Mitglied (`forMemberUpdate`) -> Audit zuletzt. `ITenderService` ist seit V1.9.226 die sechstletzte `registerService` (danach `IOnlineCountsService`, `IAttachedImageService`, `IDashboardService`, `IPartyReportService`, `ILupaBbSyncService`, `IProtocolDraftService`, `IMeetingProtocolService`, zuletzt `IPartnerServerService`); neue Methoden ans Ende.
- **EUR ist unerreichbar**, es wird nichts gebucht (`TenderAccessTripwireTest`: kein `LtrLedger*`, kein Balance-Provider). `tender*`-Tabellen nur in `TenderStore`/`TenderPersonalData` abfragen.

## Themes per Datei (V1.9.216)

- **Nur die 16 Kern-Tokens sind themebar** (`ThemeTokens.COLOR_KEYS`). Ein neues Token ist Schema-`version` 2: `ThemeTokens`, `ThemeDefaults`, `theme.schema.json`, `theming.adoc` und Tests im selben Schritt (`ThemeConsistencyTest` hält Defaults, `theme.css` und Schema zusammen).
- **Das CSS entsteht nur in `ThemeCssRenderer`** aus festen Vorlagen, normalisierten Hex-Werten und regex-geprüften Namen, nie aus freien Strings der Datei (auch `name`/`description` nie). Schriftbytes werden nur beim Start gelesen (`ThemeFontLoader`), Anfragen bedienen nur den Speicher.
- **Die Spezifität `html:root` nicht entfernen:** das Client-`theme.css` wird vom Bundler zur Laufzeit angehängt und käme sonst nach dem statischen Link.
- **Jede neue `style-src 'self'`-Policy braucht `font-src 'self'`**, jede öffentliche Seite bindet das Stylesheet nur über `publicStylesheetLinks()` (Tripwire `PublicThemeTripwireTest`).
- **Neue UI nutzt ausschließlich Tokens, nie Farbliterale** (sonst ist sie nicht themebar); der Tripwire R9 bleibt maßgeblich.

## Bildergalerie (V1.9.222)

- **Store-Monopol:** `attached_image` wird nur in `AttachedImageStore` gelesen und geschrieben (Ausnahme: `AttachedImagePersonalData` nennt die Tabelle in `coveredTables`); `GalleryTripwireTest` prüft das.
- **`GalleryOwnerPolicy` ist der einzige Rechte-Resolver** (sichtbar, bearbeitbar, gesperrt). Unbekannt und unsichtbar sind dasselbe `null` und damit dasselbe 404. Sie sperrt die Besitzerzeile (`FOR UPDATE`, dieselbe Zeile wie `placeBid`/`approveProject`) und löst **nie** das Lazy-Close einer Auktion aus. Crowdfunding sperrt nach dem **effektiven** Status (Schweigefrist), nicht nach der Spalte.
- **Löscht je ein Pfad eine Auktion oder ein Projekt, muss er `AttachedImageStore.deleteAllForOwnerInTx` in derselben Transaktion rufen** und die Dateien danach per `deleteAfterCommit` entfernen (`GalleryTripwireTest` bricht sonst). Heute gibt es keinen solchen Pfad.
- **Umsortieren und Lückenschließen sind zweiphasig** (`1000 + i`, dann `i`), weil der Unique-Index auf Postgres nicht aufschiebbar ist. Positionen sind lückenlos, Position 0 ist das Titelbild.
- **Sperrreihenfolge:** Besitzerzeile -> Bildzeilen -> Audit zuletzt. Keine Mitgliedssperre. Dateien werden vor der Transaktion geschrieben und nach dem Commit gelöscht, nie darin (Exposed wiederholt den Block); der DSGVO-Contributor ist die dokumentierte Ausnahme.
- **Alle Prüfungen des Uploads werden unter der Besitzersperre wiederholt** (`GalleryUploadGate.commitInTx`, TOCTOU); der Vorab-Check dient nur dazu, keinen Decode an eine aussichtslose Anfrage zu verschwenden.
- **Ausgabe ist nie WebP, nie größer als 5 MiB** (der Backup-Restore verweigert größere Dateien): ein großes PNG ohne Transparenz wird JPEG. `CoverImageFormat` bekommt kein WEBP-Literal.
- **Fehlerantworten der Upload-Route sind JSON mit festem Code** (`GalleryUploadErrorCode`), nie Freitext; Antworten für unbekannt, unsichtbar, fehlende Datei und anonym sind byte-gleich. Die Auslieferung setzt `Cross-Origin-Resource-Policy: same-origin` und `private`, **nicht** `respondCoverFile` (öffentliche Titelbilder, `cross-origin`).
- **`IAttachedImageService` ist seit V1.9.226 die viertletzte `registerService`** (danach `IDashboardService`, `IPartyReportService`, `ILupaBbSyncService`, `IProtocolDraftService`, `IMeetingProtocolService`, zuletzt `IPartnerServerService`); neue Methoden ans Ende, nie dazwischen. Pinned von `ConferenceTimerServiceEventGuardTripwireTest` und `GalleryTripwireTest`.
- **Im Log nur IDs und Zahlen** (`GalleryTripwireTest`): keine Beschriftung, kein `storage_key`, kein Pfad.
- **Soziale Beiträge als Besitzer (V1.9.223, `SOCIAL_POST`, Migration V120):** nur Wurzelbeiträge. Sichtbarkeit steht in `socialImagesReadable` (strenger als `SocialVisibility.isReadable`: `VISIBLE`, nicht gelöscht), die Policy bekommt deshalb den Betrachter. Kein Entwurfszustand: der Autor darf 10 Minuten nach `published_at` Bilder anhängen/entfernen (`SOCIAL_POST_PUBLISHED`); Umsortieren, Titelbild und Bildunterschrift gibt es nie, der Alternativtext ist einmal setzbar. Auslieferung `private, no-store`. Moderation **entfernt** (kein Verstecken).
- **`executeContentErasure` ruft `deleteAllForOwnerInTx`** (Bild-Audit vor dem Post-Audit, Dateien nach dem Commit). `SocialPostDto` bekommt nie ein Bildfeld: öffentliche Seiten, Mails und MCP kennen keine Bilder (`GalleryTripwireTest`). Neue Methode `listSocialPostPreviews` steht am Ende von `IAttachedImageService`.

## Angemeldete Nutzer (V1.9.215)

- **Nur Zählwerte** (`OnlineCountsDto`): keine IDs, keine Namen, nichts Neues gespeichert, keine Logzeile mit Zahlen. Nur ADMIN (`requireRole(ADMIN)` als erste Anweisung).
- **`OnlineSessionCounts.groupOf` ist die einzige Zuordnung Status -> Gruppe**, ein `when` ohne `else`; Zählregeln stehen im KDoc (gleicher Join wie `SessionStore.resolve`).
- **`IOnlineCountsService` ist seit V1.9.226 die fünftletzte `registerService`** (danach `IAttachedImageService`, `IDashboardService`, `IPartyReportService`, `ILupaBbSyncService`, `IProtocolDraftService`, `IMeetingProtocolService`, zuletzt `IPartnerServerService`; Kilua nummeriert nach Deklarationsreihenfolge); neue Methoden ans Ende, nie dazwischen. Pinned von `ConferenceTimerServiceEventGuardTripwireTest`.

## Dashboard-Signale und Menüzähler (V1.9.224)

- **Das Dashboard zeigt nur, was das Menü nicht hat** (`docs/features/dashboard.adoc`): Signale, die den Aufrufer jetzt betreffen. Jede Quelle (`DashboardSignalSource` in `dashboard/sources/`) liest **nur eigene Zeilen** oder zählt rollenbasiert (`applications.open`, `treasury.*`: nackter `COUNT`, `objectId` immer `null`). Kein Titel, kein Name, kein Betrag eines anderen im Signal; `DashboardSignalTest` serialisiert und sucht nach Geheimnissen.
- **Keine Freitext-Parameter:** `params` enthalten nur Ziffern oder ISO-Daten (`DashboardSignalCollector.sanitizeAndSort` verwirft den Rest, der Client prüft nochmals). Der Server schickt nie eine Route: `objectId` (nur bei `count == 1`) plus Modul-Schlüssel, die Route baut der Client aus einer festen Tabelle (`DashboardSignalRoutes`).
- **Gates nur über `SignalGates`/`MemberStatusSets`** (Spiegel von `NavVisibility`), nie inline `== ACTIVE`. Neue Auszählung bestehender Logik (Auktion: `computeAuctionOutcome`; Beteiligung: `*OwnParticipation`; Gremium: `isCommitteeEligible`) wird **aufgerufen, nicht nachgebaut**.
- **Eine fehlerhafte Quelle kostet nur ihre Signale** (Savepoint je Quelle, Log nur Modulschlüssel und Exception-Klasse). Jede neue Abfrage muss indiziert sein: `DashboardQueryPlanPostgresTest` erklärt alle Statements mit `enable_seqscan = off`.
- **`NavCountsQuery` kennt kein LiveKit** (Tripwire): die Zähler "(aktiv/gesamt)" kommen aus der Datenbank und sind bis zu ca. 90 s alt. "Gesamt" heißt "gerade offen".
- **`IDashboardService` ist seit V1.9.226 die drittletzte `registerService`** (danach `IPartyReportService`, `ILupaBbSyncService`, `IProtocolDraftService`, `IMeetingProtocolService`, zuletzt `IPartnerServerService`); neue Methoden ans Ende, nie dazwischen. Cache 20 s/30 s je (Mitglied, Rolle, Status), 12 ungecachte Aufrufe pro Minute je Methode.

## Staging-Seed Teil 2 (V1.9.205)

- **Jeder Teil von `StagingSeedTopUp.PARTS` läuft in einer eigenen Transaktion, `instanceCheck()` ist die erste Anweisung.** Ein Teil schreibt eine Entität nur ganz (Wurzel-ID fehlt, Mitglieder passend, aktuelles freies Guthaben deckt jede Abbuchung) und ändert nie eine vorhandene Zeile; Unique-Konflikte vorher prüfen, nie fangen. Ausschreibungs-Tabellen nur über `TenderStore` (`TenderAccessTripwireTest`).

## Finanz-Einrichtung (V1.9.206)

- **Die Kontenzuordnung hat genau einen Ort:** Reiter "Einrichtung" von "Kontenplan & Journal" (`FinanceSetupTab`). Andere Seiten zeigen nur eine Statuszeile mit Link. Deep-Links nutzen eine Hash-Query: `#/ledger?tab=einrichtung&step=bank` (ein zweites `#` gibt es im Hash-Routing nicht); ein Tabwechsel ruft `history.replaceState`, nie `location.hash =`.
- **Welche Konten ein Schritt anbietet und wann die Einrichtung vollständig ist, entscheidet nur `FinanceSetup.kt` in `lapis-shared`** (`accepts`, `financeSetupStatusOf`); Client, `getFinanceSetupStatus` und die Sperre in `enablePaymentGateway` rufen dieselbe Funktion (`FinanceSetupParityTest`). Das Gebührenkonto ist nie Pflicht.
- **`createStandardChart` ist idempotent und schreibt höchstens einen aggregierten `ORGANIZATION_SETTINGS`/`UPDATE`-Eintrag.** Der Kontenplan ist ein Vorschlag (Steuerberatung bestätigt); die Sphäre liegt nie am Konto.

## Rechenschaftsbericht (V1.9.225)

Welle R1 des PartG-Rechenschaftsberichts: Positionsmodell, Direkterfassung und Excel-Import je Einheit und Jahr. Es entsteht **kein Bericht und kein PDF** (R2). Siehe `docs/features/partg-report.adoc` und `docs/architecture/partg-report.adoc`.

- **Store-Monopol:** `PartgReportStore` ist der einzige Leser/Schreiber von `partg_report_position`/`_sheet`/`_value` (Ausnahme `PartgReportPersonalData`); `PartgReportStoreTripwireTest`. Eine Wertzeile darf nur auf eine `LEAF`-Position zeigen, das erzwingt der Service (kein Trigger).
- **Zugriff:** `requirePartyReportAccess(call)` ist die erste Anweisung jeder RPC-Methode und jedes Route-Handlers: erst Rolle BOARD/ADMIN, dann `isPoliticalParty` (sonst `PartyReportNotEnabledException`); `PartyReportAccessTripwireTest`. Ein Nicht-Vorstand erfährt so nie, ob die Organisation eine Partei ist.
- **Schreibweg:** nur `PartgSheetWriter` (RPC `saveSheet` und Import-Apply). Sperrreihenfolge Landesverband -> Kopfzeile des älteren Jahres -> Kopfzeile des jüngeren Jahres, Revisionen unter der Sperre prüfen, Audit zuletzt. Neue Kopfzeile unter Savepoint, nur die Unique-Verletzung wird gefangen.
- **Registrierung:** `IPartyReportService` steht in der Registrierungsreihenfolge nach `IDashboardService` und vor `ILupaBbSyncService` (seit V1.9.244 folgen danach noch `IProtocolDraftService`, `IMeetingProtocolService` und `IPartnerServerService`); neue Methoden ans Ende. Pins: `ConferenceTimerServiceEventGuardTripwireTest`, `GalleryTripwireTest`.
- **XLSX:** eigener Leser/Schreiber ohne Bibliothek (`partg/xlsx/`). `OoxmlZipReader` ist eine **angepasste Kopie** des `ArticleImportZipReader`: die Sicherheitslogik existiert doppelt, Korrekturen immer in beiden Dateien. Formeln werden nie ausgewertet. Import-Vorschauen liegen nur im Speicher (`PartgImportPreviewStore`, ein Neustart löscht sie).
- **Spalte `value_source`**, nicht `source` (Kollision mit Exposeds `ColumnSet.source`). `partg_report_position.code` ist ein UNIQUE-*Constraint* (H2 nimmt als FK-Ziel keinen Unique-Index). V121 darf bis zum Release auf dem Feature-Branch korrigiert werden, danach nur noch per neuer Migration.
- **Backup:** `partg_report_position` ist eine geseedete Menge (frisch, solange kein `org_hint` gesetzt ist); der Restore überschreibt die 42 Zeilen per Id.
- **Ehrlichkeit:** kein Knopf und kein Text verspricht einen fertigen Bericht (`PartyReportScreenDomTest`). Positionsliste, Bezeichnungen und Gliederungsebenen sind noch nicht gegen das Bundestags-Muster und den Gesetzestext geprüft.

## LupaBB-Abgleich (V1.9.226)

Manueller, einseitiger Stammdatenabgleich der Mitglieder mit LupaBB (ADMIN, kein Cron). Siehe `docs/features/lupabb-sync.adoc` und `docs/architecture/lupabb-sync.adoc`.

- **Zugriff:** `resolveCurrentMember(call).requireRole(AccountRole.ADMIN)` ist die erste Anweisung jeder RPC-Methode von `LupaBbSyncService`; `LupaBbSyncAccessTripwireTest`.
- **Store-Monopol:** `lupabb_sync_config` nur `LupaBbCredentialStore`/`LupaBbRunGuard` (+ `LupaBbPersonalData`), `member_lupabb_profile` nur Loader/Applier, `lupabb_sync_suppression` nur `LupaBbSuppression`; `LupaBbStoreTripwireTest`.
- **Einzige URL:** `LupaBbEndpoint.PRODUCTION`; der Host steht nur in `LupaBbEndpoint.kt`, kein Redirect, nur GET; `LupaBbEndpointTripwireTest`. Die Test-Naht ist der `internal`-Konstruktor, der nur Loopback-`http` zusätzlich zulässt (Host aus der **geparsten** URL, kein Prefix-Check).
- **Ausnahmen ohne Ursache:** jede Ktor-/IO-Ausnahme wird zu `LupaBbFetchException(code)` **ohne `cause`** (sie trägt die URL, bei Query-Transport den API-Schlüssel); kein `logger.warn(e)` im Paket, `LupaBbLogPrivacyTripwireTest`, `LupaBbNoSecretLeakTest`. `io.ktor.client` bleibt in `logback.xml` auf INFO.
- **Statuswechsel** nur über `MemberRoleStatusMutations.applyStatusChangeLocked(..., historySource = LUPABB_SYNC)`; weder Mail noch Benachrichtigung. Die E-Mail eines bestehenden Mitglieds wird **nie** geschrieben (Konto-Übernahme).
- **Registrierung:** `ILupaBbSyncService` steht nach `IPartyReportService` (seit V1.9.244 folgen danach `IProtocolDraftService`, `IMeetingProtocolService` und zuletzt `IPartnerServerService`); neue Methoden ans Ende. Pins: `ConferenceTimerServiceEventGuardTripwireTest`, `GalleryTripwireTest`.
- **DSGVO:** `LupaBbPersonalData` steht VOR `FoundationPersonalData` (liest die Personennummer vor der Anonymisierung und legt den HMAC auf die Sperrliste). `lupabb_sync_config` ist vom Backup ausgenommen (an den Schlüssel gebunden), Lauf-Log, Profil und Sperrliste werden gesichert.
- **Ehrlichkeit:** nichts lief gegen das echte LupaBB; Übertragung des API-Schlüssels (Header zuerst, dann Query) und die optionalen Felder `landesverband`/`eintritt` sind unbestätigt. V122 darf bis zum Release auf dem Feature-Branch korrigiert werden.

## Sitzung <-> Veranstaltung (V1.9.227)

- `event.meeting_id` (V123) ist die optionale 1:1-Verknüpfung. **Einziger Schreiber** ist das Paket `server/governance/meetingevent` (`MeetingEventLinkStore`); `EventStore.insertEvent/updateEvent` erwähnen die Spalte nie (`MeetingEventLinkWriterTripwireTest`). Eine Bearbeitung der Veranstaltung kann die Verknüpfung also nicht verschieben.
- **Sperrreihenfolge** in jeder Operation (`MeetingEventLinkOperations`): Sitzungszeile `FOR UPDATE` -> Veranstaltungszeile `FOR UPDATE` -> Audit zuletzt. `cancelEvent` sperrt nur die Veranstaltung. Recht und Gremium werden aus der **gesperrten** Zeile gelesen.
- Brücken-Regel: Vorsitz/Schriftführung dürfen nur einen **DRAFT mit `MEMBERS_ONLY`** erzeugen (die Signaturen tragen nur Ids), nicht öffnen, veröffentlichen oder ändern. Lese-Rechte auf Veranstaltungen wurden nicht erweitert.
- Die fünf Methoden (`announceMeetingAsEvent`, `linkMeetingToEvent`, `unlinkMeetingEvent`, `alignEventToMeeting`, `listLinkableMeetings`) stehen **am Ende** von `IGovernanceService`; die Registrierung bleibt `GovernanceService(call, eventOnline)`. Audit: ein Entity-Typ `MEETING_EVENT_LINK`, die Art steht im Snapshot (`kind`).
- Absagen (Sitzung `CANCELLED`, `cancelEvent`, `cancelSeriesEvent`) lösen die Verknüpfung im selben Schritt (`MeetingEventLinkOperations.Lifecycle`). `HELD` behält sie.
- **Kein Raum wird wiederverwendet:** `conference_room.meeting_id` und `EventConferenceGuard` bleiben unberührt; die Veranstaltung bekommt ihren Raum erst beim ersten Beitritt. Öffentliche Ausgaben (`EventPublicRoutes`, ICS, Embed, JSON-LD) kennen die Verknüpfung nicht (`MeetingEventLinkNoLeakTest`); `MeetingDto.linkedEvent` füllt nur der authentifizierte RPC, nie die öffentliche API.

## Neue Nachricht (V1.9.237)

- **Eine einheitliche Ablehnung:** ein Empfänger, der nicht angeschrieben werden kann (unbekannt, fehlerhafte Id, kein Mitglied, anonymisiert, gesperrt), ist IMMER `DirectMessageRecipientUnavailableException` -- nie eine Id im Text, nie
  `NotFound`/`Conflict` mit Unterscheidung. Die Regel steht in `directMessageRecipientRefusal` (`rpc/DirectMessaging.kt`) und gilt für alle fünf Eingänge; nicht umgehen, nicht kopieren.
- `DirectMessageBlockStore` ist der EINZIGE Schreiber von `direct_message_block` (außer dem DSGVO-Beitragsleistenden); die Tabelle ist nicht `member_finder_block` und bleibt im Backup.
- Der `DirectMessageRateLimiter` wird NIE innerhalb von `transaction {}` aufgerufen (Exposed wiederholt den Block bei SQLException und würde doppelt zählen); `DirectMessageComposeTripwireTest` prüft das.
- Neue Methoden von `IDirectMessageService` werden nur AM ENDE angehängt (Kilua nummeriert nach Reihenfolge).
- Gruppen-Mitgliedschaft nie nachbauen: `eligibleMemberIds`/`isCommitteeEligible` (deckt manuelle UND Regel-Gremien ab). Gruppennachricht: Audit zuletzt, nur Anzahl im Snapshot.
- Detail: `docs/architecture/direct-messages.adoc`, Abschnitt "Composing a new message".

## Partnerserver (V1.9.244)

Föderierter, öffentlicher Veranstaltungs-Feed zwischen direkt verbundenen Lapis-Servern (Pull). Details: `docs/features/partner-servers.adoc`, `docs/architecture/partner-server-feed.adoc`.

- **Zwei Schreiber, sonst keiner:** `PartnerServerStore` für `partner_server`, `partner_blocklist`, die Events der Direktpartner (`hops = 1`) und die Spalten `partner_list_hidden` / `partner_server_id`; seit V1.9.245 `PartnerDiscoveryStore` für `partner_discovered_server`, die Events mit `hops > 1` und die `partner_recursion_*`-Spalten (Tripwire `PartnerServerTripwireTest`). Die beiden Spalten stehen bewusst nicht in `OrganizationSettingsDto`/`Input`.
- **Abruf nur über `PartnerFeedFetcher`**, immer **außerhalb** einer Transaktion und nie aus einem Request-Pfad, außer `PartnerServerService.probeAndSave` (eine Anfrage, Rate-Limit je Admin vor dem Abruf, nicht in `transaction`). Der Fetcher wird nur mit seinen Standardwerten gebaut. Adressprüfung: `checkWebhookUrl` + `FederationIpPinningPlugin` (nicht `requireSafeFederationUrl`: kein CGNAT/Mapped/6to4/Teredo/NAT64, Exception nennt die IP).
- **Die Sperre schlägt den Partner:** gesperrte Hosts werden vor jedem DNS-/Netzzugriff aussortiert, und `applyFetchSuccess` prüft die Sperrliste in derselben Transaktion nochmals. `addBlock` löscht den Cache passender Partner in derselben Transaktion.
- **Server-ID ist an einen Host gebunden** (`UNIQUE(remote_server_id)`); In V131 war `partner_event` pro **Partnerzeile** eindeutig (Cache-Vergiftung); seit V132 ist der Event-Schlüssel `(remote_server_id, remote_event_id)`; das ist nur sicher, weil eine gemeldete Server-ID tabellenübergreifend an genau einen Host gebunden ist. **Der Direktpartner gewinnt:** `insertProbedPartner`/`applyFetchSuccess` löschen vorher eine entdeckte Zeile mit gleichem Host/gleicher ID. `remote_partners_json` wird nur von `PartnerServerStore.remotePartnerHosts`, `PartnerDiscoveryStore` und dem Parser gelesen (Hosts werden erneut normalisiert).
- **Fremdtext** nur über `ForeignText.clean` (NFC, ohne Steuer-/Bidi-Zeichen, nach Codepoints gekürzt, HTML nie interpretiert); im Client nur über die `untrusted*`-Helfer. Ereignis-URLs müssen auf den eigenen Host des Partners zeigen.
- **Zeiten:** der Feed trägt ISO-8601 mit Offset plus IANA-Zone; der Cache speichert UTC-Instants (Klasse A, `time-fields.tsv`).
- **Registrierung:** `IPartnerServerService` ist die letzte `registerService` (nach `IMeetingProtocolService`); neue Methoden ans Ende. Pins: `ConferenceTimerServiceEventGuardTripwireTest`, `GalleryTripwireTest`.
- **Rekursion (V1.9.245, V132):** `PartnerRecursionRunner` wird **nur** vom `PartnerFeedPoller` aufgerufen (nie aus Request-Pfad oder `probeAndSave`) und bekommt den Fetcher des Pollers, baut keinen eigenen. Grenzen zentral in `PartnerRecursionLimits` (20/15/40, 100/2000 Events, 60/200 Zeilen, 5 min). **Sperrreihenfolge:** `partner_server` -> Einstellungszeile (nur für die Speicher-Obergrenzen, `lockStorageBudget`) -> `partner_discovered_server` -> `partner_event`; Schreibpfade sperren zuerst die Wurzel-Partnerzeile und prüfen die Sperrliste für Host UND Kette erneut. Neue RPC-Methoden von V1.9.245 stehen am Ende von `IPartnerServerService`. Anzeige: `PartnerEventDisplay` filtert beim Lesen (Sperre/Tiefe/Schalter wirken sofort), `PartnerDisplayEpoch` steckt im Memo-Schlüssel von `/veranstaltungen` und wird **nach** dem Commit erhöht. Externe Links nur in `PublicPartnerEventHtml`.
- **Backup:** `partner_event` und `partner_discovered_server` sind ausgeschlossen (nachfüllbare Caches), `partner_server`/`partner_blocklist` nicht. Bekannt: ein aus einem Produktiv-Backup wiederhergestelltes Staging hat dieselbe Server-ID wie Produktion.
