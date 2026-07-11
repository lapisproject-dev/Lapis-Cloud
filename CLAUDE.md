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

## Verwandte Repositories

- `kuml-dev/kUML` — Modellierungssprache für alle Diagramme
- `kuml-dev/kuml-asciidoc` — Asciidoctor-Extension, die das `[kuml]`-Macro bereitstellt
- PZB (`gitlab.com/pdv7/pzb`) — Vorgänger-Repo, read-only Referenz für die Neuimplementierung
- Lapis Net — dezentrales P2P-Schwesterprojekt (eigenes Repo, noch anzulegen)
