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
JetBrains) — Details und Architektur-Hintergrund siehe `README.adoc`.

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
- In Szenarien der Spur niemals `module()` oder `DatabaseConfig.connect()` aufrufen (sonst Rückfall auf H2); der Dialekt-Guard in `installLaneGuards` fängt das ab.
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
