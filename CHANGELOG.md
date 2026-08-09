# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

**Videokonferenzen (Kleinsitzung), V1.0 Wave 3 „Externes Streaming" — RTMP-composited-egress live
streaming to external platforms (YouTube/Twitch/PeerTube/Owncast/generic RTMP), on
`feature/video-konferenz-wave3-streaming`.** Everything here was verified against the REAL running
LiveKit v1.13.5 + egress v1.13.0 stack, not reconstructed from documentation alone (see "Live
verification" below); seven live findings materially shaped the design, most importantly finding 7
below, which is a launch-blocking correctness fix, not an enhancement.

- **Persistence + crypto** — three new tables (`conference_stream_destination`/`conference_stream`/
  `conference_stream_target`), and this codebase's **first at-rest encryption primitive**:
  `network.lapis.cloud.server.crypto.SecretBox` (AES-256-GCM, fresh `Cipher` per call, the
  destination's own UUID as GCM AAD so a ciphertext copied between rows fails to decrypt, versioned
  `v1:<iv>:<ct>` storage format, fail-fast at startup if streaming is enabled and
  `LAPIS_SECRET_ENCRYPTION_KEY` is missing/undecodable/wrong-length — never a silent downgrade to
  plaintext). Deliberately generic so later waves (SMTP passwords, PSP credentials) reuse the same
  primitive rather than inventing a second scheme.
- **`IConferenceStreamingService`** — a THIRD, separate conference RPC service (not folded into
  `IConferenceService`/`IConferenceRecordingService`): ADMIN-only destination credential CRUD
  (`listDestinations`/`createDestination`/`updateDestination`/`setDestinationEnabled`/
  `deleteDestination`), a narrower moderator-facing target picker (`listStreamTargets`, no url/key),
  and the room-creator-or-BOARD/ADMIN stream lifecycle (`startStream`/`pauseStream`/`resumeStream`/
  `stopStream`/`getActiveStream`/`listStreams`). The stream key is **never** returned to any client,
  under any role, at any time — `ConferenceStreamDestinationDto.streamKeyMask` is always the constant
  `"********"`. `startStream` calls LiveKit synchronously (the one deliberate divergence from Wave
  2's `startRecording`) — see that interface's own KDoc for the full two-transaction ordering.
- **`StreamPoller`** — mirrors `RecordingPoller`'s shape (one application-scoped coroutine, no
  in-memory state), matches per-URL `stream_results` back to `conference_stream_target` rows via a
  `url_fingerprint` computed server-side (LiveKit both REDACTS and REORDERS the URLs it echoes back —
  live-verified, neither exact-URL nor index matching works), drives `LIVE -> FAILED`, reconciles
  orphan `STARTING` rows, enforces `maxDurationMinutes`, auto-stops on room end, and maps LiveKit's
  raw per-URL errors (which can echo the destination HOST back, live-verified) onto a fixed sanitized
  German vocabulary — raw LiveKit text never reaches a DTO.
- **Client — `ConferenceStreamDestinationsScreen.kt`** (new, ADMIN-only, `Routes.CONFERENCE_STREAM_DESTINATIONS`,
  nav entry "Stream-Ziele" alongside "Backup & Wiederherstellung"): list/create/edit/enable/delete.
  Stream-key field is `type="password"`, always empty on edit with placeholder "unverändert lassen",
  never prefilled, with a lock glyph and a real, VISIBLE re-masking confirmation on save ("Gespeichert
  — Schlüssel wird nicht erneut angezeigt.") rather than a silent reset.
- **Client — `ConferenceScreen.kt`, THE WAVE 2 BADGE FIX (finding 7, launch-blocking, see Jobs'
  conditional-go verdict item 1)**: live-verified that LiveKit sets `Room.isRecording`
  (`active_recording`) to `true` for ANY active egress, including a STREAMING-only one with no
  recording at all — the pre-Wave-3 badge, which trusted that boolean directly, would have shown a
  false "● Aufzeichnung läuft" on every participant's screen the moment a stream-only egress started,
  a DSGVO-relevant false statement in exactly the surface Wave 2 built for legal transparency. Fixed:
  `onRecordingStatusChanged` is now used PURELY as an instant refresh trigger; the badge always
  renders from SERVER state (`getActiveRecording` + `getActiveStream`), so "Aufzeichnung läuft",
  "Live-Stream läuft → <Labels>", and both together render as DISTINCT, independently stacked rows
  (never merged into one line, distinct glyphs "●"/"◆" so the distinction never relies on red-vs-red
  alone). `document.title`'s "● " prefix logic extended to cover both signals.
- **Client — persistent stream indicator + moderator controls**: a danger-styled badge naming
  destination LABELS only (never url/key), a `role="alert"` `aria-live="assertive"` notice banner
  ("Diese Besprechung wird ab jetzt live gestreamt.", "Verstanden"/"Besprechung verlassen", shown to
  every participant including late joiners, source-of-truth is always a fresh `getActiveStream` read,
  never a stale cached value). Recording and streaming controls live in SPATIALLY SEPARATE groups,
  each under its own "Aufzeichnung:"/"Live-Stream:" sub-header (never a shared row/dropdown) — the
  sharpest risk the design review identified (three destructive-adjacent buttons — end meeting, stop
  recording, stop streaming — in one control surface). "Live-Stream starten …" opens a dialog whose
  destination checklist DOUBLES as the confirm surface itself (no re-typing): a live summary line
  names the selected destinations by label and restates irrevocability as the selection changes,
  primary button reads "Jetzt live gehen" (never "OK"), plus a mandatory static Hinweis that secret
  ballots require a MANUAL pause (no automatic protection exists this version — the concept note's
  hard-wired lock needs a Governance-module integration that does not exist yet, a half-built version
  would be worse than none). "Stream unterbrechen"/"Stream fortsetzen"/"Stream beenden" each behind
  their own `ConfirmDialog`, restating the noun they act on; the pause dialog states plainly that
  the platform sees an interruption and may end the broadcast (LiveKit has NO pause primitive —
  pause is honestly stop, resume is honestly a fresh egress with a new `livekit_egress_id`, never
  implied seamless). Per-destination status renders THREE distinct states ("Verbindung wird
  hergestellt…"/"Live"/"Beendet"/"Fehlgeschlagen"), never a binary "streaming: yes" — a partial
  failure (one of three platforms down) stays visible.
- **Infrastructure** (`deploy/local/`) — `rtmp-sink` (`bluenviron/mediamtx`, digest-pinned) joins the
  stack as a real RTMP test destination, so an end-to-end stream is verifiable without any
  YouTube/Twitch account: RTMP ingest (1935) reachable only on the compose-internal network, ONLY
  the HLS playback port published (`127.0.0.1:8888`, same loopback-only bind posture every other port
  in this stack uses). `egress.yaml` gains a documented, commented-out `template_base` knob naming
  the exact Room-Composite failure signature (`error_code 412`, `"Start signal not received"`) this
  wave's own live verification hit when `template.livekit.io` is unreachable.

**Explicitly out of scope this wave** (see `IConferenceStreamingService` KDoc for the full,
authoritative list) — none of the following is implied anywhere in the UI: automatic stream pause
during secret ballots (no Governance/voting integration exists), a Restream/StreamYard integration
(generic RTMP fully covers the manual case), a YouTube Data API auto-create-live-event hook,
simulcast/quality-ladder tuning beyond the two fixed latency profiles, automatic backup-recording on
stream drop, mid-stream destination add/remove (the destination set is fixed at start), and
self-hosting the Room-Composite template.

**Live verification (2026-08-09, client-UI + infra step)**: `docker compose -f deploy/local/docker-compose.yml
up -d` brought up the full stack including the new `rtmp-sink` service; a real `ffmpeg` H.264/AAC
test-pattern push from a throwaway container on the same compose network to
`rtmp://rtmp-sink:1935/live/lapis-e2e` produced the exact expected log line (`stream is available
and online, 2 tracks (H264, MPEG-4 Audio)`), and `http://127.0.0.1:8888/live/lapis-e2e/index.m3u8`
resolved through a real HLS-session redirect to a genuine `#EXTM3U` playlist naming both renditions —
confirming real media reachable from the host, not merely "the container started". `docker compose
... config` validates cleanly with the digest-pinned `rtmp-sink` image and its loopback-only port
binding. `egress.yaml`'s `template_base` comment addition was confirmed comment-only (the `egress`
container still reaches `service ready` against Redis after a restart, unaffected). The full
moderator-facing client flow against a real browser session and the `error_code 412` signature
itself were verified in this wave's later verification step (below), not in this one.

**Live verification (2026-08-09, dedicated end-to-end verification step)**: the full moderator flow
was driven against a real running server (`LAPIS_STREAMING_ENABLED=true`, real
`LAPIS_SECRET_ENCRYPTION_KEY`) and a real browser session (ADMIN `amara.admin@example.org`, then a
separate, genuinely different MEMBER login `max.mitglied@example.org` — not a same-identity
reconnect), plus a real `livekit/livekit-cli room join --publish-demo` synthetic participant for
real published media. Every mandatory proof from the wave plan was closed: a real ciphertext
(`v1:`-prefixed, plaintext absent) landed in `conference_stream_destination.stream_key_ciphertext`;
every captured RPC response (`createDestination`/`startStream`/`getActiveStream`, ADMIN and MEMBER
alike) never carried the plaintext key; a real multi-destination `StartParticipantEgress` reached
`EGRESS_ACTIVE` with BOTH `rtmp-sink` targets publishing simultaneously (`stream is available and
online, 2 tracks (H264, MPEG-4 Audio)` for both keys, real HLS playback confirmed at
`http://127.0.0.1:8888/live/<key>/index.m3u8`); a destination pointed at an unresolvable host
surfaced the FIXED sanitized German failure text within one poll tick while the OTHER, good
destination in the SAME stream kept running unaffected — closing the plan's own "does one bad URL
kill the whole egress?" open question: **no, per-target failure is isolated**; `pauseStream` produced
a real RTMP EOF on the sink side while the meeting stayed connected, `resumeStream` produced a
genuinely NEW `livekit_egress_id` and `restartCount: 1`; and the plain MEMBER's `getActiveStream`
response, joining an already-live stream as a true late joiner, carried destination labels and
platforms only — no url, no key — with the D3 banner shown immediately on join. New opt-in automated
coverage: `LiveKitStreamEgressLiveIntegrationTest.kt` (same `LAPIS_LIVEKIT_IT=true` gate as its
sibling `LiveKitEgressLiveIntegrationTest.kt`, spawns real `livekit-cli` publishers via `docker run`,
confirmed hermetically SKIPPED — not failed — when the gate is unset).

**Two real client bugs found live during this step, both fixed in place (not deferred)**: (1) the
top status badge kept reading "◆ Live-Stream läuft → …" for the ENTIRE duration a stream was
`PAUSED` — a literal false statement in the exact transparency surface finding 7/D8 exists to keep
honest, caused by `conferenceStatusBadgeRows` hardcoding the verb "läuft" regardless of
`ConferenceStreamDto.status` while the correctly-worded `conferenceStreamStatusLabel` mapping
function sat unused as dead code (only ever exercised by its own unit test). Fixed by wiring
`conferenceStreamBadgeVerbPhrase(status)` into the badge row, so `PAUSED` now reads "ist
unterbrochen" in a calm `secondary` color, matching `conferenceStreamStatusColor`. (2) the
per-destination "Live" chips stayed frozen at their last value indefinitely while `PAUSED`, because
`StreamPoller.handlePaused` deliberately does not touch `conference_stream_target` rows once there is
no live egress left to poll — `updateStreamTargetsPanel` now hides the (necessarily stale) per-target
chips while `PAUSED` rather than rendering a contradicting "Live" status underneath the now-honest
"ist unterbrochen" badge. Four new `ConferenceStreamingUiTest.kt` unit tests pin both the fix and the
underlying `conferenceStreamBadgeVerbPhrase` mapping.

**Videokonferenzen (Kleinsitzung), V1.0 Wave 4 „Politur" — closes the three items deliberately
deferred from Wave 1's mandatory UI/UX design review (D1, D3, D10), on
`feature/video-konferenz-wave4-politur`.** No server-security-critical surface beyond one new
moderator-gated RPC method; the review and security audit each approved on the first pass.

- **D1 — single-button room creation.** The Lobby's title-entry form is gone. "Besprechung jetzt
  starten" creates a room immediately with an auto-generated German-dated default title
  (`"Besprechung vom TT.MM.JJJJ, HH:MM"`, non-deprecated kotlinx-datetime 0.8.0 `.day`/`.month.number`/
  `.year` API) and joins it in one step. The title stays editable afterwards via a new inline
  "Bearbeiten" affordance in the in-call header, backed by a new `IConferenceService.renameRoom`
  RPC — same `requireModeratorOrPrivileged` gate as `endRoom`/`removeParticipant`, blocks renaming an
  already-ended room, reuses `createRoom`'s own title validation (non-blank, ≤`MAX_TITLE_LENGTH`).
- **D3 — participant-grid reflow above ~12 attendees.** `LiveKitJs.kt` now wires the previously-
  unused `RoomEvent.ActiveSpeakersChanged`. A new pure function, `conferenceGridLayout`, partitions
  participants into a speaking-priority zone (capped at `CONFERENCE_PRIORITY_ZONE_MAX`, always
  non-empty via a join-order fallback when nobody is currently speaking, the local participant never
  demoted out of view) and a compact strip for the rest, active once attendance exceeds
  `CONFERENCE_GRID_REFLOW_THRESHOLD`. At or below threshold, layout is byte-for-byte unchanged from
  Wave 1-3. Ten new `ConferenceGridLayoutTest.kt` unit tests cover the threshold boundary, the
  priority-zone cap and fallback, and the exhaustive-partition invariant (every identity in exactly
  one zone).
- **D10 — named, testable connection-state machine.** The ad-hoc `leftCall` boolean (read/written
  from five separate call sites) is gone, replaced by a sealed `ConferenceConnectionState`
  (`Disconnected`/`Connecting`/`Connected`/`Reconnecting`/`Failed`/`Ended`, `Ended` terminal) driven
  by a pure `conferenceConnectionReduce(state, event)` reducer. `Ended` is reachable via
  `DisconnectedSignal` from BOTH `Connected` and `Reconnecting` — a forcibly-terminated/kicked session
  cannot get stuck showing "connected" after the server has actually closed the room. 19 new
  `ConferenceConnectionStateTest.kt` unit tests pin every modeled transition, including that
  unlisted (state, event) pairs are ignored rather than throwing.
- **Review and security audit both approved on the first round** — no fix cycles needed. Non-blocking
  findings only: a pre-existing (not introduced by this wave) coroutine-leak on repeated failed
  connect attempts, a low-impact double-submit race on the inline rename's Enter-key handler, and a
  narrow state-machine label edge case (`Connecting` + a mid-handshake `Disconnected` signal has no
  explicit reducer arm) that has no user-visible effect because `enterCall`'s `onDisconnected`
  callback tears the call panel down unconditionally regardless of the reducer's return value.
- **Live verification (2026-08-09)**: driven against a real running server and a real browser session
  (`boris.board@example.org`, BOARD). One click on "Besprechung jetzt starten" created and joined a
  room with the correctly-formatted default title (server round-trip confirmed via
  `POST /rpc/routeConferenceServiceManager9` returning 200 OK); the inline "Bearbeiten" flow renamed
  the room via a real `renameRoom` RPC call, reflected immediately in both the in-call header and the
  browser tab title; "Für alle beenden" produced a clean LiveKit disconnect
  (`connection state changed: connected -> disconnected` in the console log) and returned the UI to a
  fresh, empty Lobby with no stale "connected" indicator anywhere — confirming D10's `Ended`-state
  teardown. D3's 12+-participant reflow was verified via its dedicated unit test suite rather than a
  live many-participant session (impractical to stand up in this verification pass); the partition
  logic's threshold/cap/fallback/exhaustiveness properties are all directly tested.

**Videokonferenzen (Kleinsitzung), V1.0 Wave 2 „Aufzeichnung" — server-side meeting recording via
LiveKit Track Egress plus an own asynchronous ffmpeg composition, on
`feature/video-konferenz-wave2-aufzeichnung`.** Implements exactly the concept note's 2026-08-01
decision: the server records raw per-participant tracks in real time, and a separate, later poller-
driven step composes them into one gallery-layout video. Backend, RPC, persistence, poller,
composition, storage/access wiring, and a functional client UI are all complete and live-verified
end to end (see "Live verification" below); two real bugs and one real, disclosed client-side gap
were found during this wave's own live verification and are documented honestly below rather than
silently patched over or hidden — the same posture Wave 1 established for its own bug disclosures.

- **Infrastructure** (`deploy/local/`) — `redis:7.4-alpine` (compose-internal only, no published
  ports) and `livekit/egress:v1.13.0` join the Wave 1 stack; `livekit.yaml` gains a `redis:` block,
  switching `livekit-server` from its Wave 1 single-node router to the Redis-backed one Egress needs
  to talk to it — re-verified live that this change does **not** regress plain Wave 1 conferencing
  (`LiveKitLiveIntegrationTest` still green against the Redis-enabled stack) before any recording
  code was written. New `egress.yaml` (same DEV-ONLY committed-secret posture as `livekit.yaml`).
  Two deliberately separate env vars for the container-vs-host output-path split
  (`LAPIS_EGRESS_OUTPUT_CONTAINER_DIR`/`LAPIS_EGRESS_OUTPUT_HOST_DIR`) — see "Bugs found" below for
  why collapsing them (or trusting the shipped default blindly) breaks every recording.
- **Server-side LiveKit Egress integration** — `LiveKitEgressClient`/`HttpLiveKitEgressClient`
  (`StartTrackEgress`/`StopEgress`/`ListEgress`, mirrors `HttpLiveKitAdminClient`'s existing shape),
  a third `LiveKitAccessToken.mintEgressToken` shape (`roomRecord`-only grant, never mixed with
  `roomJoin`/`roomCreate`/`roomAdmin`), and `LiveKitParticipantInfo` extended with real, live-verified
  `tracks[]` data — closing Wave 1's own disclosed "only verified for the empty-roster case" gap. Every
  wire shape was captured against a real LiveKit v1.13.5 + egress v1.13.0 container, not reconstructed
  from documentation alone; `ListEgress`'s response array field name is confirmed `items`.
- **`RecordingPoller`** — the single application-scoped coroutine (one `while (isActive)` loop, not
  one coroutine per recording) driving `RECORDING -> STOPPING -> PROCESSING -> READY`/`FAILED`.
  `RECORDING` discovers newly-published tracks via `ListParticipants` and starts one Track Egress per
  track; `STOPPING` requests `StopEgress` and waits for terminal track status (bounded by an egress
  timeout, composing from survivors if ≥1 video track completed, else `FAILED`); `PROCESSING` runs
  composition under a `Semaphore(1)` (one ffmpeg process system-wide), capped at 2 attempts. Every
  non-terminal state carries a wall-clock deadline, not a retry counter, so nothing can get stuck
  forever — server-restart reconciliation picks up crashed rows on the first tick after boot, mirroring
  Wave 1's own lazy reconciliation.
- **`FfmpegGalleryComposer`** — real `ProcessBuilder`/ffmpeg composition (external binary, not
  `org.bytedeco:ffmpeg-platform`, to avoid ~150 MB of per-platform native binaries in a build that
  already carries a Kotlin/JS client), black-canvas-plus-per-input-`overlay` with
  `enable='gte(t,offset)'` gating — deliberately not `xstack`, since tracks start/stop at different
  offsets as people join/leave — gallery-grid vs. presentation (screen-share + camera strip) layout.
  The argument-list construction is split into a pure, process-free `FfmpegArgumentBuilder` so the
  filter graph is unit-testable without ever running ffmpeg. Live-verified against the real binary:
  synthetic clips with a late "join" composed into a real, valid MP4 with the late joiner's cell
  correctly black until its own offset.
- **Storage and access** — reuses the existing Dokumentenablage as the recording's storage backend
  (new streaming `DocumentArchiving.archiveGeneratedFile` sibling of `archiveGeneratedPdf`, never
  buffers the composed file into a `ByteArray`) rather than inventing a fourth, standalone access
  axis: a composed recording becomes a real `document`/`document_version` under a `"Aufzeichnungen"`
  folder, with the moderator-chosen `DocumentAccessLevel` (default `BOARD_ONLY`) as its access rule,
  widened by exactly one predicate (`ConferenceRecordingAccess.mayAccess`) so a non-BOARD moderator
  never loses access to their own recording — used identically at all three call sites (`listRecordings`
  filter, `mediaUrl` computation, and the media route itself). New `GET
  /api/conference/recordings/{id}/media` route: bytes never travel over Kilua RPC, `respondFile` +
  `video/mp4` + `Content-Disposition: inline`, Range/206 seeking for free from the already-installed
  `PartialContent` plugin. **Fixed a real pre-existing bug this wave would otherwise have made worse**:
  `registerDocumentRoutes`' download handler used to `Files.readAllBytes` the whole file into memory —
  fine for the 25 MiB document cap it was written for, a live OOM risk once a hundreds-of-MB recording
  reaches the very same route as an ordinary document. Replaced with a streaming response; verified with
  a new regression test asserting a `Range:` request returns a real `206`.
- **`RecordingRawFiles.resolveWithin`** — the *only* way a LiveKit-reported raw filename becomes a
  `File`: basename-only, rejects `..`, resolves strictly under `{hostRawRoot}/{recordingId}/`, and
  requires `toRealPath()` containment (defeats a symlink planted in the bind mount) — the highest-value
  security test in the wave. **Raw files are retained, never deleted, on any `FAILED` transition**
  (design review's D13, a Jobs' won't-ship-without item) — deleted only on the successful-compose
  branch, and only then unless `LAPIS_RECORDING_KEEP_RAW`. Both halves of this rule are live-verified
  with real bytes, not just log lines (see "Live verification" below).
- **Persistence** — new `conference_recording`/`conference_recording_track` tables
  (`28-conference-recording.kuml.kts` + baseline DDL + hand-written Exposed tables, same
  edit-the-baseline-in-place posture Wave 1 established), `AuditEntityType` gains
  `CONFERENCE_RECORDING` (audited on start/stop, justified by the concept note's own §32 BGB/GoBD
  framing), `ConferencePersonalData` extended to cover both new tables with retain-with-reason
  outcomes (`PersonalDataCoverageTest` would otherwise fail the build). **Operator note**: composed
  recordings now live under `documentStorageRoot`, which `OrganizationExportService` walks in full for
  every backup — a single Vorstandssitzung recording easily runs into the hundreds of MB, so backups
  will noticeably grow in size and duration the moment recording is used for real meetings. Excluding
  recordings from backups is an explicit, undecided question for a later wave, not a silent choice
  made here.
- **RPC** — a new, separate `IConferenceRecordingService` (not new methods on `IConferenceService`,
  following the `IPriceOracleService`-vs-`ILtrLedgerService` precedent): `getRecordingAvailability`/
  `startRecording`/`stopRecording`/`getActiveRecording`/`listRecordings`. Write operations gate on
  creator-or-BOARD/ADMIN; reads gate on `DocumentAccessLevel`, a completely different predicate
  `IConferenceService` never touches. `getActiveRecording` is deliberately **never** gated on
  `DocumentAccessLevel` — everyone in the room has a legal right to know it is being recorded,
  regardless of who may later watch it back. `failureReason` is a security boundary: populated only
  from fixed German constants, raw ffmpeg stderr/Twirp bodies go to `kotlin-logging` and nowhere near a
  DTO. No `deleteRecording` — deleting a recording is deleting a document, and
  `IDocumentService.deleteDocument` already does exactly that.
- **Client UI** (`ConferenceScreen.kt`/new `ConferenceRecordingsPanel.kt`) — ran the mandatory
  UI/UX-Design-Team review before writing code (11 designers, Jobs' final "GO, conditional on six
  must-fix items" verdict; all six landed in this wave). A persistent, chrome-level "● Aufzeichnung
  läuft" badge and a non-blocking notice banner ("Verstanden"/"Besprechung verlassen"), both driven
  entirely by LiveKit's own `RoomEvent.RecordingStatusChanged`/`Room.isRecording` signal — server-
  authoritative, pushed instantly, correct for late joiners, unspoofable by a participant — never by
  RPC polling. "Aufzeichnung starten"/"-beenden" live in the separate moderator row next to "Für alle
  beenden" (disclosive WARNING styling, not destructive DANGER, per Tesler's precedent from Wave 1's
  own D5/D6), gated invisible (not disabled) when recording is unconfigured. Bespoke confirm dialogs
  for both start (PostalMailScreen-bar copy, `Zugriffsebene` select, default Vorstand) and stop
  (lighter but real — no bare single-click stop of a legally significant recording). The Lobby gains a
  new "Aufzeichnungen" section — recordings outlive their room, so this is reachable independent of any
  live call — with FAILED items sorted to the front, an inline `<video controls>` player, and a
  separate download link for READY items. Double-submit protection and non-optimistic UI state
  (checking the `guarded {}` result before updating any label) on every button, per this wave's own
  non-negotiable rules and Wave 1's own mic/camera-toggle bug-fix precedent.
- **Testing** — hermetic coverage across config parsing, wire shapes (`MockEngine`, real captured
  fixtures), the poller's state machine, the pure ffmpeg argument builder, the raw-file security
  resolver, the recording-routes authorization matrix, personal-data coverage, and 32 client-side
  `ConferenceScreenTest` cases (recording-can-start, status labels, banner text, duration formatting,
  document-title prefix, FAILED-sort ordering). New opt-in `LiveKitEgressLiveIntegrationTest`
  (`LAPIS_LIVEKIT_IT=true`, same skip-unless-enabled posture as `LiveKitLiveIntegrationTest`):
  `ListEgress` on a fresh room returns empty, and `StartTrackEgress` for a bogus track id proves the
  `roomRecord` grant is honoured (accepted with a real `EgressInfo`, not rejected with a `401`) —
  **one real, empirically-observed correction to the wave's own plan, recorded rather than silently
  adjusted**: `StartTrackEgress` for a track that will never exist does **not** fail synchronously as
  the plan expected; Track Egress is SDK-based (the egress worker subscribes and waits), so the call
  returns a normal `EGRESS_STARTING` immediately and only fails ~30 s later, once the worker's own
  subscribe-timeout elapses — this test's second half closes `LiveKitEgressInfo`'s own long-standing
  "`EGRESS_FAILED` remains unverified" disclosure by observing exactly that.
- **Live verification (2026-08-09)**, against a real running `deploy/local/` stack plus a real browser
  session: the D7 start-confirm dialog, the D1/D2 badge, the D3 notice banner, the D4 late-joiner
  banner (via a fresh reconnect to an already-recording room), the D8 stop-confirm, and the D12 FAILED
  presentation with its sanitized failure reason all matched their specified copy exactly. A full
  successful recording — seeded with a real synthetic video track published via the official
  `livekit-cli` Docker image, since this sandbox's own browser cannot grant camera/microphone access —
  reached `READY` with a real, playable 49-second MP4: archived as a real document under a real
  "Aufzeichnungen" folder, served through the new media route with real, byte-exact `206 Partial
  Content`/`Range` semantics, and rendered in the Lobby with a working inline player and a separate
  download link. D13 raw-file retention on `FAILED` was confirmed with real bytes (a real 30 MB raw
  file left untouched after a failed composition), and raw-file cleanup on success was equally
  confirmed (the raw directory was empty again immediately after `READY`). Full detail, exact
  reproduction steps, and everything that could **not** be verified in this environment (true
  concurrent multi-member browser sessions; the badge's survival across every Wave 1 layout mode and
  fullscreen) are in `deploy/local/README.adoc`'s "Live verification results (Wave 2 completion step)".
- **Two real bugs found live during this wave's own verification, neither caught by the shipped unit
  tests, both documented rather than silently patched around**:
  1. **Every recording failed 100% of the time against the exact recipe this README itself
     documents.** `ConferenceRecordingConfig`'s default for `LAPIS_EGRESS_OUTPUT_HOST_DIR` is the
     relative string `deploy/local/egress-out`, correct only if the server process's working
     directory is the repo root — but the documented recipe does `cd lapis-server` first, and
     Gradle's `application` plugin's `run` task defaults its child process's working directory to the
     *subproject* dir. Every recording therefore looked for its raw files in
     `lapis-server/deploy/local/egress-out` (nonexistent) and failed with "no resolvable video track".
     Fixed in `deploy/local/README.adoc`'s own recipe (an explicit absolute-path override); the
     shipped source default itself was deliberately left unchanged, since a functional code fix is
     outside this step's documentation-only scope — flagged as a candidate fast-follow.
  2. A Colima bind-mount quirk (observed once, not fully root-caused): clearing `egress-out/`'s
     contents while the `egress` container keeps running can silently break that container's
     subsequent writes into the same mount — the container's own log still claims a normal, error-free
     `egress_complete`, but zero bytes land on disk. A `docker compose restart egress` immediately
     resolved it. Documented in `deploy/local/README.adoc`'s Troubleshooting table.
- **One real, disclosed client-side gap found live — fixed in review-round-1 of this wave's own code
  review (2026-08-09), after being deliberately left open in the step that first found it**: the
  in-call moderator's recording button could get stuck on a permanently disabled "Aufzeichnung wird
  beendet …" label past the recording's actual terminal state, because `ConferenceScreen.kt` only
  refreshed that label reactively from LiveKit's own `RecordingStatusChanged` push — which stops
  firing usefully once the *last* egress track itself ends, well before this repository's own, often
  much longer, composition phase actually finishes. Observed inconsistently (stuck once, self-corrected
  once under what appeared to be the same repro steps); the Lobby's independently-loaded
  "Aufzeichnungen" list was correct in every case tested. **Fix**: `enterCall` now runs a periodic
  `pollInFlightRecordingStatus` loop (15s interval) while the tracked recording sits in
  `STOPPING`/`PROCESSING`, falling back to `listRecordings(roomId)` — not just
  `getActiveRecording(roomId)`, which server-side (`ACTIVE_RECORDING_STATUSES`) never returns anything
  past `STOPPING` — to find the same recording id's true, possibly-terminal status. On reaching
  `READY`/`FAILED` the control unsticks (reverts to an actionable "Aufzeichnung starten") and a toast
  now surfaces the outcome, closing the review's explicit ask about whether the UI honestly reflects a
  terminal state, including `FAILED`, rather than hanging. Not run through `guarded {}` (would re-toast
  a transient network hiccup on every tick); five new `ConferenceScreenTest` cases cover the two pure
  helpers behind the loop (`conferenceRecordingNeedsPoll`/`conferenceFindRecordingById`). One narrow
  residual gap, disclosed rather than silently left: `listRecordings`' access-level filter can still
  hide the recording from a moderator who is neither its starter nor privileged enough for its
  `accessLevel` — for that case the button stays exactly as stuck as before this fix, never worse. Full
  detail: `deploy/local/README.adoc`'s Troubleshooting table.
- **Two real server-side bugs found and fixed in review-round-2 of this wave's own code review
  (2026-08-09), both closing check-then-act/error-path gaps missed by the shipped unit tests
  (which only ever exercised the sequential or happy-path shape of each)**:
  1. **`startRecording`'s "one active recording per room" invariant was a plain read-then-insert
     with no row lock.** Under Postgres `READ_COMMITTED` (this codebase's isolation level, no
     override in `DatabaseConfig`), two genuinely concurrent `startRecording` calls for the same
     room — two moderators, two browser tabs, or a double-click before the confirm dialog even
     opens — could both read "no active recording" and both insert a `RECORDING` row, producing two
     simultaneous recordings for one room. Same bug class this codebase has closed with a row lock
     several times before (`LtrBalanceProvider`, `PasswordResetTokenStore`, `AuditLogRecorder`,
     `FederationRelationshipStore`, `CrowdfundingService.approveProject`/`rejectProject`,
     membership `approveApplication`/`rejectApplication`, auction reservations) — this service had
     simply never gotten the same treatment. **Fix**: the room row is now read with `.forUpdate()`
     before the active-recording check, serializing concurrent attempts on the same room; a new
     genuinely-concurrent two-thread test (`ConferenceRecordingServiceTest`) replaces the previous
     sequential-only regression test. Verified against a real, throwaway Postgres 16 container
     (not just the default H2 test database) with a deliberate control experiment: with
     `.forUpdate()` removed, the exact same test reproduces a double-insert on every run; with it
     restored, exactly one attempt wins every time.
  2. **`RecordingPoller`'s `STOPPING`-egress-timeout-to-`FAILED` safety net was unreachable during
     a sustained LiveKit Egress outage.** `handleStopping` returned immediately from its
     `ListEgress`-failure `catch` block, before ever reaching the elapsed-time check a few lines
     below — so a recording that entered `STOPPING` during (or just before) a LiveKit Egress
     Twirp-API outage or misconfiguration stayed `STOPPING` forever, regardless of wall-clock time,
     directly contradicting the class KDoc's own claim that this deadline is what prevents an
     indefinite hang. Client-side this manifested exactly as the round-1 fix above was meant to
     prevent: a permanently stuck "Aufzeichnung wird beendet …" button, because the server-side row
     genuinely never changed. **Fix**: the egress-timeout check is now factored into its own
     `applyEgressTimeout` helper and run on BOTH exit paths of `handleStopping` — the normal
     "`ListEgress` succeeded, tracks still non-terminal" path (using the freshly-refreshed track
     statuses) and the "`ListEgress` itself is failing" path (using the last DB-known track statuses
     from before the failed call) — so a sustained outage can no longer defeat the one safety net
     designed specifically for a stuck egress. Three new `RecordingPollerTest` cases cover the
     outage-past-timeout-with-survivors, outage-past-timeout-without-survivors, and
     outage-before-timeout (no premature `FAILED`) shapes.
- **Still open from the Wave 2 design review, deliberately deferred, not silently dropped**: D11's
  "partial-composition flag" — a recording composed "from the survivors" after an egress timeout (at
  least one video track completed, but not all of them) renders with the identical "Bereit" badge as a
  clean, complete recording; `ConferenceRecordingDto` has no `composedFromPartialTracks`-shaped field
  yet. Named as a Jobs'-final-verdict "must-fix" item in both `ConferenceScreen.kt`'s and
  `ConferenceRecordingsPanel.kt`'s own file KDoc (search `D11`) — a genuine, disclosed gap for a
  follow-up step, not an oversight.
- **Explicitly out of scope for this wave** (see the concept note and `IConferenceRecordingService`'s
  own class KDoc for the complete list): auto-transcript/live subtitles, chapter markers tied to
  Tagesordnungspunkte, WebM/VP9 alternate output, RTMP live-streaming (Wave 3 — the Redis+Egress
  infrastructure this wave adds is exactly Wave 3's prerequisite, making it substantially cheaper),
  "Termin → Konferenzraum" integration, S3-compatible object storage (the concept note's own default
  is local Dokumentenablage for small instances, both pilots qualify), a four-tier
  Moderator/Präsentator/Teilnehmer/Zuhörer role model, per-participant recording opt-out, recording
  retention/automatic deletion, federated guest access to recordings, webhooks of any kind (the
  signature-verification recipe is recorded in `RecordingPoller`'s own KDoc so the option stays cheap
  later, but no route is added), and multiple simultaneous recordings per room.

**Videokonferenzen (Kleinsitzung), V1.0 Wave 1 — self-hosted LiveKit-based video conferencing for
small meetings, on `feature/video-konferenz-wave1`.** First application (per the concept note):
Vorstandssitzungen. Own infrastructure on LiveKit rather than an embedded third-party widget or a
BigBlueButton integration — consistent with this project's "own stack, own data" posture. Backend,
RPC, persistence, and a functional client UI are complete and live-verified end to end (see
"Live verification" below); four UI-polish items the wave's own design review flagged as
non-blocking for a functional first pass remain explicitly open for a follow-up step (see "Still
open" below) — this wave is deliberately **not** presented as a fully polished screen the way the
Governance/Accounting UI waves were.

- **Infrastructure** (`deploy/local/` — the first Docker setup in this repository):
  `livekit/livekit-server:v1.13.5` + `coturn/coturn:4.17.0-alpine` via `docker compose`, with
  `rtc.node_ip: 127.0.0.1` set explicitly (the load-bearing fix for ICE completing at all from a
  macOS-host browser through Colima's port forwarding — silently missing this produces a black call
  with no error) and an explicit 38-byte `keys:` secret instead of `livekit-server --dev` (whose
  hardcoded 48-bit secret makes this project's already-present `nimbus-jose-jwt` throw
  `KeyLengthException`). Full recipe, troubleshooting, and a two-profile manual-verification
  walkthrough: `deploy/local/README.adoc`.
- **Server-side LiveKit integration, no SDK** — `LiveKitAccessToken` (participant tokens pinned to
  one room with `roomJoin`/`canPublish`/`canSubscribe`/`canPublishData` only; 60-second
  server-internal admin tokens with `roomCreate`/`roomAdmin`/`roomList`, minted fresh per Twirp
  call, never serialized to a DTO) and `LiveKitAdminClient` (a thin Twirp-over-JSON client for the
  five `RoomService` methods this wave needs, over the already-present `ktor-client-cio` — net new
  third-party dependency for the entire server side of this wave: **zero**, a deliberate decision
  against `io.livekit:livekit-server` given this project's own established "only take a JWT
  dependency you can justify" bar). Wire shapes (snake_case, not the camelCase LiveKit's own docs
  would suggest) verified against a real running container, not just documentation.
- **Persistence** — two new tables (`conference_room`, `conference_participation`), modelled in
  `lapis-server/src/main/kuml/27-conference.kuml.kts` following the established `«Column»`/
  `fkEntity` idiom. **Operator note, not a silent decision**: both `CREATE TABLE`s were appended to
  the existing `V1__baseline.sql` in place, per this repository's established convention for every
  prior schema wave (only one Flyway migration file exists). Editing a baseline changes its Flyway
  checksum — an already-migrated live instance (`cloud.lapisproject.dev`) needs either
  `flyway repair` or a genuine `V2__conference.sql` before this wave can be deployed there. That is
  an operator decision for whoever deploys this wave and is deliberately not made silently by this
  changelog entry or any implementation step.
- **RPC** — `IConferenceService` (`getAvailability`/`listActiveRooms`/`getRoom`/`createRoom`/
  `joinRoom`/`leaveRoom`/`endRoom`/`listParticipants`/`removeParticipant`), two-tier authorization
  (MODERATOR = room creator, PARTICIPANT = everyone else, with a global BOARD/ADMIN escalation on
  `endRoom`/`removeParticipant`), room names server-generated as `lc-<uuid4>` (never derived from
  user text), `createRoom` throttled via the existing `LoginRateLimiter` reused as a generic
  per-caller throttle (same reuse pattern `Application.kt` already established for OIDC Dynamic
  Client Registration). **No LiveKit webhook consumer** — deliberate: every client-visible need is
  already covered by the LiveKit SDK's own `RoomEvent` stream and `endRoom` is synchronous; the one
  resulting gap (a room whose participants all merely left) is closed by lazy reconciliation inside
  `listActiveRooms`. Chat is ephemeral by design — it rides the LiveKit data channel only, is never
  persisted, and carries no GoBD/DSGVO retention obligation as a result.
- **Client UI** (`ConferenceScreen.kt`) — room list/creation, a responsive video-tile grid (avatar-
  initials placeholder instead of a black rectangle for camera-off, a dedicated full-width stage for
  screen-share), a persistent control bar (Mikrofon/Kamera/Bildschirm teilen/Chat/Verlassen) with
  "Für alle beenden" spatially separated into its own moderator-only row, a live participant roster
  with a moderator-only "Entfernen" action, and a collapsible ephemeral chat panel — all gated
  through bespoke confirm modals for the two irreversible moderator actions, matching this project's
  `BackupScreen.kt`-restore-grade confirm-dialog rigor. `livekit-client` 2.21.0 is the first
  hand-declared `npm()` dependency in this codebase (`lapis-client/build.gradle.kts`), resolved
  through the same Kotlin/JS → Yarn → webpack chain KVision's own transitive npm dependencies
  already exercise.
- **Chat trust boundary, verified by code, not just by testing the happy path**: a `DataReceived`
  payload's own `senderMemberId`/`senderDisplayName` fields are attacker-controllable by any room
  participant (anyone holding a valid join token can publish an arbitrary data-channel payload
  directly, bypassing this app's own chat-send UI entirely) — `LiveKitRoomSession`'s `DataReceived`
  handler unconditionally overwrites both fields with the SDK-verified
  `RemoteParticipant.identity`/`.name` before the message ever reaches `ConferenceScreen.kt`.
  Rendered via KVision's default escaped `content`, never `rich = true`.
- **Testing** — hermetic unit/integration coverage for token shape, Twirp wire shape
  (`ktor-client-mock`, using the real verified fixtures above, not guessed ones), and the full
  authorization matrix; a new opt-in, env-gated `LiveKitLiveIntegrationTest`
  (`LAPIS_LIVEKIT_IT=true`, a no-op/skipped everywhere else) that runs a real
  `CreateRoom -> ListRooms -> DeleteRoom -> ListRooms` round trip against a running container —
  Testcontainers was deliberately **not** introduced (this repository's ~1300-test suite is
  hermetic by design; CI runs a bare `./gradlew clean check` with no services). `DomainModelMergerTest`
  and `PersonalDataCoverageTest` (a `ConferencePersonalData` contributor for the two new
  `member`-referencing FKs) updated accordingly.
- **Live verification (2026-08-09)**, against a real running `deploy/local/` stack, two independent
  browser sessions logged in as two different seeded members: real signaling connects and a real
  SDP/ICE/DTLS handshake for both participants (proving the Colima `node_ip` fix, not merely that an
  HTTP call succeeded); a live, real-time roster in both directions; a normal chat message and an
  XSS-attempt payload (`<script>...</script><img src=x onerror=...>`) both delivered over the real
  LiveKit data channel and rendered HTML-escaped, never executed; a real moderator kick
  (`RemoveParticipant`, HTTP 200, real signaling-level disconnect on the kicked side); a direct
  `endRoom` call fired from the browser console/devtools by a seeded TREASURER account (neither the
  room's creator nor BOARD/ADMIN) rejected with a real server-side `ForbiddenException` — proving the
  server, not the client UI, is the authority boundary; and a real moderator "Für alle beenden" with
  the exact confirm-dialog copy the design review specified. Full detail and the exact
  reproduction steps: `deploy/local/README.adoc` "Live verification results".
- **One real bug found during the implementation's own live verification, found again independently
  and fixed during merge verification**: `ConferenceScreen.kt`'s mic/camera/screen-share toggle
  buttons used to flip their `micEnabled`/`cameraEnabled`/`screenShareEnabled` flag and button label
  *before* awaiting the underlying `getUserMedia`-backed `LiveKitRoomSession.setCamera`/
  `setMicrophone`/`setScreenShare` call's result, without checking whether it actually succeeded — so
  a user whose browser denied camera/microphone permission saw a false "an" ("on") state with no
  error surfaced anywhere. Independently reproduced (clicking the camera toggle with `getUserMedia`
  blocked really did flip the label to "Kamera an") and fixed: all three toggle handlers, plus the
  initial post-connect auto-publish, now only apply the optimistic state change when the underlying
  call actually succeeded, reverting to the truthful prior label on failure. See
  `deploy/local/README.adoc`'s Troubleshooting table for the fix detail. The broader D2 design-review
  item (a first-class, non-technical permission-preflight interstitial, asked before LiveKit's own
  device prompt) remains legitimately open for a later polish step.
- **Still open from the Wave 1 design review, deliberately deferred, not silently dropped**: D1
  (single-button "Besprechung jetzt starten" instead of today's title-entry form), D2 (the
  permission-preflight interstitial above), D3 (a speaking-priority reflow above roughly 12
  participants), and D10 (a fully named, testable client-side connection state machine — today's
  `ConferenceScreen.kt` only distinguishes "not yet connected" and "ended"). None of these are
  regressions; all four are named, tracked open items in `ConferenceScreen.kt`'s own file KDoc.
- **Explicitly out of scope for this wave** (see the concept note and design review for the full
  list): recording/streaming (LiveKit Egress, RTMP), whiteboard/document sharing, breakout rooms,
  live subtitles/translation, hand-raise/reactions, a lobby/Warteraum, the full four-tier
  Moderator/Präsentator/Teilnehmer/Zuhörer role model, E2EE, "Termin → Konferenzraum" integration
  with the Sitzungen/Gremien module, voting-module integration, and federated guest join
  (`joinRoom` requires an AKTIV local member; `MemberStatus.GAST` is excluded, same posture as the
  existing LTR/Crowdfunding/Auktion gates).
- **Audit-round-1 security fixes (2026-08-09)** — three findings from the wave's first review/
  security-loop pass, all closed before the wave's own commit step:
  - **Request-rate throttling beyond `createRoom`** — `joinRoom`/`leaveRoom`/`listActiveRooms`/
    `getRoom`/`listParticipants` had zero rate limiting (only `createRoom` was throttled), letting a
    scripted join/leave loop grow `conference_participation` unbounded and hammer the self-hosted
    LiveKit SFU/coturn relay and the LiveKit Twirp admin API indirectly. Fixed with three new
    per-member request-rate limiters (`joinRoomRateLimiter`/`leaveRoomRateLimiter`/`listRateLimiter`,
    reusing `FederationInboxRateLimiter`'s generic sliding-window `checkAndRecord`, deliberately NOT
    `LoginRateLimiter`'s failure-counting model, which would wrongly penalize legitimate repeated
    joins/list-refreshes) — see `ConferenceService` KDoc "Request-rate throttling beyond createRoom".
  - **Short-lived, scoped TURN credentials replacing a static, indefinitely-valid shared secret** —
    `deploy/local/livekit.yaml`'s `rtc.turn_servers` block used to hand every client the same
    forever-valid TURN username/password on every connect, independent of room membership or session
    length (unlike the deliberately TTL-bounded LiveKit participant JWT). Replaced with coturn's
    `use-auth-secret`/`static-auth-secret` "REST API for Access to TURN Services" scheme:
    `TurnCredentialMinter.kt` mints a fresh HMAC-SHA1 credential per `joinRoom` call (same TTL as the
    JWT), returned as `ConferenceJoinTokenDto.turnServers` and passed through to `livekit-client` as
    `RoomOptions.rtcConfig.iceServers` (`LiveKitRoomSession.connect`) — never baked into static
    server config again. Live-verified against a real running `deploy/local/` coturn container
    (`turnutils_uclient`): a minted credential authenticates and allocates a relay address
    immediately; a tampered or expired one never completes.
  - **`deploy/local/docker-compose.yml` published ports now bind `127.0.0.1:` explicitly** (was the
    Docker default `0.0.0.0`, every interface) — combined with `livekit.yaml`'s committed, real
    LiveKit admin API key/secret, an unrestricted bind would have let anyone reachable on the LAN
    mint their own LiveKit admin token client-side and call `CreateRoom`/`DeleteRoom`/`ListRooms`/
    `ListParticipants`/`RemoveParticipant` directly, bypassing every `ConferenceService` authorization
    check. Loopback-only binding closes this off for local development; the compose file now carries
    an explicit warning against copying the loopback-bind-plus-committed-secret combination toward a
    real deployment without changing both.
- **One more real bug found during independent merge verification (2026-08-09), fixed the same day**:
  a recording stopped before any participant ever published an unmuted audio/video track (e.g. no
  camera/microphone permission ever granted for the whole meeting) fell through `RecordingPoller`'s
  `STOPPING` handler into the same `egressTimeoutMinutes` wait (default 30 minutes) a genuinely-stuck
  egress uses — even though `handleRecording` (the only `StartTrackEgress` call site) only ever runs
  while `status == RECORDING`, so a `STOPPING` row's final track set can never grow and there was
  categorically nothing to wait for. Reproduced live (a moderator who started and immediately stopped
  a recording with no device permission ever granted saw "Aufzeichnung wird beendet …" hang with no
  ETA); fixed with an immediate `trackRows.isEmpty()` fast-fail (`FAILED`, "Es wurde keine Audio- oder
  Videospur aufgezeichnet.", zero LiveKit calls made) instead of the pointless wait, re-verified live
  after the fix (FAILED within one poll tick instead of up to 30 minutes) plus a new
  `RecordingPollerTest` case. Separately, this same verification pass independently confirmed real
  Track Egress recording end to end against a fresh `deploy/local/` stack (a genuine `.ogg` audio file
  captured to disk, `egress_complete` with code 0) and re-ran the opt-in `LiveKitEgressLiveIntegrationTest`
  against a live container.

**LTR-Wirtschaft UI wave — LTR-Konto, Crowdfunding, Auktion, Politiker, Price-Oracle, on
`feature/ltr-economy-ui`. Wave complete — the fifth and final wave of the pilots' (PdV, ELB)
UI-gap-closure plan, after Governance, Accounting, Compliance, and Mail-merge/Postal-Dispatch
UI.** Surfaces the alternative/libertarian LTR internal-currency economy layer end to end for the
first time: five self-contained screens over six already-implemented, already-tested backends
(`ILtrLedgerService`, `ICrowdfundingService`, `IAuctionService`, `IPeerTransferService`,
`IPoliticianService`, `IPriceOracleService`). **No backend/RPC changes were needed anywhere in
this wave** — every method's role-gating matched the plan's verified role table exactly.

- **`LtrLedgerScreen.kt`** (`#/ltr-ledger`, "LTR-Konto") — the LTR-economy home: own balance +
  transaction ledger (always self-service), TREASURER/BOARD/ADMIN lookup of another member's
  balance/entries, self-service peer transfer, LTR minting, and a privileged
  arbitration-correction transfer. Peer-transfer history is shown via a client-side
  `referenceType` filter over the same ledger-entries list rather than a new RPC, matching
  `IPeerTransferService`'s own documented design (no dedicated history method). New
  `formatLtr()`/`ltrSpan()` in `Money.kt` as the LTR-denominated sibling of
  `formatMoney()`/`moneySpan()`.
- **`CrowdfundingScreen.kt`** (`#/crowdfunding`) — project submission with a non-refundable LTR
  stake, BOARD/ADMIN approve/reject, member Like/Dislike reactions (approved projects only), and
  TREASURER/BOARD/ADMIN monthly distribution calculation. Surfaces both `status` vs.
  `effectiveStatus` (14-day silence-is-approval) and `initialWeightLtr` vs. `currentWeightLtr`
  (10%/day decay) explicitly rather than collapsing either pair into one figure.
- **`AuctionScreen.kt`** (`#/auction`) — English proxy-bid auction with second-price settlement,
  optional Sofortkauf, listing creation, bidding, an ADMIN-only enable/disable flow gated behind a
  legal-disclaimer acknowledgment (the disclaimer text is held read-only and resent verbatim, never
  editable), and value-cap configuration. `maxBidLtr` is never shown for other bidders, only in the
  caller's own "Meine Gebote".
- **`PoliticianScreen.kt`** (`#/politicians`) — profile browsing/rating open to members and guests,
  BOARD/ADMIN grant/revoke of politician status and weight-snapshot triggers, and an inline
  ADMIN-only toggle for the `politicianRankingEnabled` feature flag. Shows
  `memberTrustWeight`/`guestTrustWeight`/`combinedTrustWeight` as three explicitly separate,
  non-summable figures, matching the DTO's own documentation rather than collapsing them into one
  score.
- **`PriceOracleScreen.kt`** (`#/price-oracle`) — kept as its own screen rather than folded into
  `LtrLedgerScreen.kt` since every one of its four methods is TREASURER/BOARD/ADMIN-gated: ADMIN-only
  oracle configuration (peg, quorum, outlier/spread thresholds), a diagnostic live-price fetch, and
  donation-to-LTR conversion booking. Makes real outbound HTTP calls to Coinbase/Kraken/Bitstamp —
  live-verified against the real internet during this wave, not mocked.
- **Two real bugs found during this wave's own independent live-browser verification (role ADMIN
  and MEMBER, real dev server, real DOM) — not caught by the automated review/security loops, since
  neither mounts real DOM**, both in `AuctionScreen.kt`:
  1. `listMyBids()`/`listMyAuctions()` sit behind the same `requireAuctionEnabled` gate as
     `listAuctions()`, but only `listAuctions()` got the "friendly banner instead of a toast"
     treatment — the other two left their "Wird geladen …" placeholder stuck forever while firing
     duplicate "im Konflikt" error toasts, hit on every ADMIN's very first visit since
     `auctionEnabled` defaults `false`. Fixed with a shared `loadOrShowDisabledNotice()` helper.
  2. Enabling/disabling the auction didn't refresh those same two panels, so an ADMIN who just
     enabled it kept seeing "deaktiviert" until a manual reload. Fixed by refreshing all three
     panels on enable/disable.
- **Live verification** as ADMIN: minted and transferred LTR, watched the peer-transfer
  double-entry booking on the ledger, submitted and approved a crowdfunding project and liked it,
  created an auction listing, completed the auction enable-disclaimer flow, granted politician
  status and rated it (weight computed correctly from real LTR balance), fetched a real live BTC
  price from all three configured sources and converted a real donation to LTR at the live rate. As
  MEMBER: confirmed role-appropriate disabled-feature banners (no admin controls, no raw error
  toasts), placed a real competing bid on the ADMIN's auction listing, and confirmed max-bid
  privacy (other bidders never see it) and correct confirm-dialog framing throughout.
- New `jsTest` coverage per screen (`AuctionScreenTest.kt`, `CrowdfundingScreenTest.kt`,
  `LtrLedgerScreenTest.kt`, `PoliticianScreenTest.kt`, `PriceOracleScreenTest.kt`) plus
  `MoneyTest.kt` additions for `formatLtr()`/`ltrSpan()`. `./gradlew clean check` green throughout,
  1300+ tests, 0 failures.

**Mail-merge/Postal-Dispatch UI wave — admin mailing-list authoring, invoice/receipt/Einladung PDF
documents, and real Letterxpress postal dispatch, on `feature/mailmerge-ui`. Wave complete — the
fourth and final wave of the pilots' (PdV, ELB) UI-gap-closure plan, after Governance, Accounting, and
Compliance UI.** Surfaces the already-implemented, already-tested `IMailingService`/`IPostalMailService`
backends plus the two `/api/mailmerge/...pdf` HTTP routes end to end for the first time. The wave's own
scope-narrowing finding held up under review: `CommunicationScreen.kt` (V0.7.3) already covered the
member-facing mailing-list self-service side, so the real remaining surface was six previously-
unreachable admin-authoring RPC methods, two PDF download routes, and `IPostalMailService`'s four
methods — smaller than the domain name suggests. **One load-bearing design finding shapes every postal
dispatch confirm dialog**: no RPC or route in this wave's scope ever returns a member's raw postal
address to the client (`MemberSummaryDto` is `{id, displayName}` only, `PostalDeliveryLogDto` carries
only a display name, the PDF routes stream bytes) — the "member's postal address data is only shown to
appropriately-privileged staff" requirement is satisfied *by construction*, not by a client-side check,
so every dispatch confirm dialog shows a recipient's display name and a plain statement that a letter
goes "to the address on file, resolved server-side," never a fetched/fabricated address line. Because
postal dispatch triggers a real external Letterxpress API call with real cost and mails a real physical
letter, every dispatch trigger gets `BackupScreen.kt`-restore/`DsgvoRightsScreen.kt`-erasure-grade
irreversibility rigor (bespoke `Modal`, bold "ENDGÜLTIG"/real-cost warning, recipient + document detail
row) — and because all three dispatch RPCs return their result normally even on failure (a
`PostalDispatchOutcome.Failed` is a legitimate business outcome, not a thrown exception), every dispatch
call site renders the outcome inline and distinctly for SENT vs. FAILED, never a bare success toast that
could misreport a real per-letter failure as if it went fine. **No backend/RPC changes were needed** —
every method's role-gating (including the two PDF routes deliberately NOT offering the self-service
carve-out `IContributionService`/donors get elsewhere, and `dispatchEinladungByPost` deliberately
excluding TREASURER while the other two dispatch methods include it) matched the plan's verified role
table exactly.

- **`CommunicationScreen.kt`** admin extension (BOARD/ADMIN, additive block below the existing
  member-facing Mailinglisten/Postfach panels, never a second tab) — mailing-list creation, an
  admin-forced-subscribe member picker, message compose/draft, and a `confirmDialog`-tier "Senden"
  action, plus a permanent caption stating plainly that "gesendet" is today an internal log entry
  (one `MailingDeliveryLogDto` row per active subscriber, `DeliveryStatus.SENT` unconditionally) —
  not a real external send yet, matching this codebase's stub-mailer honesty precedent
  (`NoOpPasswordResetMailer`) rather than implying real delivery.
- **`ContributionsScreen.kt`/`LedgerScreen.kt`** — "Rechnung (PDF)"/"Spendenbescheinigung (PDF)"
  download links (staff-facing views only — `renderOrgWideContributions`/`renderJournalEntryDetail`,
  never the member's own summary; both PDF routes are TREASURER/BOARD/ADMIN-gated server-side,
  deliberately more conservative than `IContributionService`'s own "member can see their own data"
  carve-out) plus, next to each, a real "Per Post versenden" postal-dispatch trigger for the same
  document (`dispatchBeitragsrechnungByPost`/`dispatchSpendenbescheinigungByPost`).
- **`MeetingsScreen.kt`** Einladung section (gated on the existing meeting-level `canManage`, but its
  two actions narrowed further to global BOARD/ADMIN — strictly narrower than `canManage`, which also
  admits a Committee's CHAIR/DEPUTY_CHAIR/SECRETARY; that narrower case renders a plain-language
  explanation instead of a vanished control) — a free PDF download (hidden-form POST-download, the
  first POST-triggered-file-download idiom in this client, since `/api/mailmerge/invitations` is a
  multipart POST no plain `<a href>` can trigger) and a batch postal dispatch
  (`dispatchEinladungByPost`, capped client-side at the same 50-recipient limit the server enforces,
  unchecked-by-default recipient checklist sourced from the same `eligibleMembers` this screen already
  computes for attendance — no new RPC call needed). The aggregate toast reflects partial failure
  (`"$n von $total Briefen erfolgreich übergeben"` vs. an explicit failure count), never a blanket
  success toast when any recipient's letter failed.
- **`PostalMailScreen.kt`** (new, `#/postal-mail`, TREASURER/BOARD/ADMIN) — read-only Letterxpress
  dispatch audit trail (`listPostalDeliveryLog`), plus the shared confirm-dialog/outcome-rendering
  helpers every dispatch trigger above reuses. A top banner explains plainly when
  `OrganizationSettings.postalMailEnabled` is off (no update-settings UI exists yet — out of scope for
  this "standard frontend over an existing surface" wave) rather than letting every dispatch trigger
  fail with an unexplained `ConflictException` toast.
- New `MailmergeHttp.submitEinladungPdfDownload` (hidden-form POST-download) alongside the existing
  `invoiceUrl`/`receiptUrl` GET-URL builders; new `PostalMailScreenTest.kt` covering the pure
  `PostalDeliveryStatus` label/color table (including the documented-but-dead-today `QUEUED` branch,
  kept per this codebase's `legalHoldIndicator` precedent, not deleted as unreachable).
  `./gradlew clean check` green throughout every commit, including ktlint.

**Compliance UI wave — five new screens (Audit Log, Backup & Restore, DSGVO-Compliance, DSGVO
Rights, Board Membership), on `feature/compliance-ui`. Wave complete.** The pilots (PdV, ELB) picked
Compliance as their #3 UI-gap-closure priority, after Governance and Accounting UI (both v0.10.0).
All five screens surface the already-implemented, already-tested `IAuditLogService`/
`IBackupService`/`IDsgvoComplianceService`/`IDsgvoService`/`IBoardMembershipService` backends end to
end for the first time, per the approved plan + UI/UX-Design-Team review (root `CLAUDE.md`
"UI/UX-Design-Team"). Because this domain covers legally load-bearing GoBD audit-trail integrity,
GDPR erasure rights, and board-composition transparency reporting, the design review paid particular
attention to three things carried consistently across the wave: the audit log's immutability is
never contradicted by an edit affordance anywhere in the UI; a GDPR erasure request's
approve/decide/execute distinction and irreversibility is unmistakable (bespoke confirmation modals
matching `LedgerScreen.kt`'s `postingConfirmDialog` rigor); and every compliance-verdict-shaped
display (risk bands, deadline clocks, reminder acknowledgements) reads as a documentation aid, never
an automated legal verdict — the same "Nachweis-Hilfe, not automated compliance verdict" honesty
precedent Accounting UI's Mittelverwendungsrechnung banner already established, repeated as its own
unconditional, non-dismissible banner on the DSFA, Breach, and Transparenzregister-reminder tabs.
**No backend/RPC changes were needed anywhere in this wave** — all six pre-existing service
interfaces matched their own documented contract exactly, with one real build-config fix along the
way (`lapis-client` had no `kotlinx-serialization` compiler plugin applied — needed once
`BackupHttp.kt` became the module's first locally-defined `@Serializable` class).

- **`AuditLogScreen.kt`** (`#/audit-log`, TREASURER/BOARD/ADMIN) — the GoBD hash-chain audit log:
  keyset-paginated, filterable (entity type/id/actor/date range) list; a per-entry detail view with
  structured before/after snapshot rendering (decoded against the four existing snapshot DTOs, with
  a raw-text fallback for a future entity type or malformed data); and a one-button "Kette prüfen"
  chain-integrity check surfacing the real cryptographic `verifyChainIntegrity` result as an
  unambiguous pass/fail, never a default "assumed valid" state. `IAuditLogService` has no write
  method at all by design, so this screen carries zero edit affordance anywhere — makes proving the
  audit trail hasn't been tampered with a self-service task instead of a developer-run query.
- **`BackupScreen.kt`** (`#/backup`, ADMIN only — the first ADMIN-only route/nav-entry in this
  client) — full-organization export/restore against the two raw HTTP routes (bundle bytes never
  travel over Kilua RPC) plus the operations-log audit trail of who exported/restored, when, and with
  what outcome. Restore gets `postingConfirmDialog`-grade irreversibility rigor (bold "NICHT
  rückgängig zu machen" warning, file name/size shown before commit, an extra line when the target
  organization already holds data that would be merged/overwritten); the three real server exceptions
  (`IncompatibleBundleException`/400, `NonEmptyTargetException`/409,
  `RestoreIncompleteException`/422) each render their own distinct message instead of one generic
  error toast. Makes organization backup/restore usable without direct server/API access.
- **`DsgvoComplianceScreen.kt`** (`#/dsgvo-compliance`, BOARD/ADMIN) — the DSGVO-Vollausbau admin
  tooling: four sub-registers (Verarbeitungsverzeichnis/AVV, technisch-organisatorische
  Maßnahmen/TOM, Datenschutz-Folgenabschätzung/DSFA, Datenpannen) as one screen, reusing
  `NonprofitComplianceReportsScreen.kt`'s toggle-button tab pattern. Write-form visibility differs per
  tab (AVV/TOM: ADMIN only; DSFA/Breach: BOARD/ADMIN), matching the server's own role split exactly.
  The Breach tab re-sorts the server's list client-side into an escalation-first order (OVERDUE first,
  each group by deadline ascending, with an extra `border-danger` outline on overdue rows) so a missed
  Art. 33 72-hour notification window is never below the fold. Makes AVV-Register, TOM, DSFA/DPIA, and
  Datenpannenmeldung — previously developer/API-only — board-self-service compliance record-keeping.
- **`DsgvoRightsScreen.kt`** (`#/dsgvo-rights`, unconditional nav entry "Meine Daten") — member-facing
  Auskunft (Art. 15/20 DSGVO export) and Löschung/"Recht auf Vergessenwerden" (Art. 17 DSGVO) for any
  authenticated member's own data, plus an ADMIN-only decide/execute queue and DSGVO audit trail
  stacked below when present. Every erasure request's REQUESTED → APPROVED/REJECTED → COMPLETED
  progress renders as a shared three-pill step tracker on both the requester's own status card and
  every ADMIN queue row, so the two-party workflow (member requests, ADMIN decides, ADMIN separately
  executes — never the same click) is visually undeniable; the chosen `ErasureMode` is explained in
  plain language three times across the workflow, ending in a `BackupScreen.kt`-grade irreversibility
  confirm dialog before the final delete. Makes GDPR data-subject rights (self-service export/erasure
  request, and the board's decide/execute/audit obligations) usable without a developer in the loop
  for the first time. **Known, deliberately undisguised gap**: `IDsgvoService` has no self-facing "get
  my own erasure request" read endpoint, so a member's post-submit status card is session-only (not
  persisted across reloads); a permanent caption under the submit button says so explicitly, and this
  is flagged here as a candidate follow-up (a small `getMyErasureRequest`-shaped backend addition) for
  a future wave rather than worked around with a fake client-side cache.
- **`BoardMembershipScreen.kt`** (`#/board-membership`, BOARD/ADMIN) — the wave's fifth and final
  screen: the live board ("Vorstand") roster, an administrative appoint/end form, the §20 GwG
  Transparenzregister beneficial-owner-completeness report (each data gap named by the specific
  missing field, e.g. Geburtsdatum/Staatsangehörigkeit), and the reminder-acknowledgement history.
  Confirmed via `BoardMembershipEvents.kt`/`GovernanceService.kt`/`ElectionService.kt` that the board
  roster is a committee-agnostic read-model kept automatically in sync with `EXECUTIVE_BOARD`
  committee membership — not a second, independently-entered dataset — so the screen links back to
  `CommitteesScreen.kt`'s `EXECUTIVE_BOARD` committee rather than presenting itself as the only place
  a board seat changes; a displaced-incumbent heads-up (single-holder seats like CHAIR/DEPUTY_CHAIR/
  SECRETARY) is surfaced client-side before submission as a purely informational confirm dialog. The
  reminder list's resolve button is labeled "Ich habe das Register aktualisiert" rather than a generic
  "Erledigt", so acknowledging a reminder states the exact human claim being made — this system never
  verifies or files a Transparenzregister entry itself. Makes §20 GwG board-transparency reporting and
  reminder tracking usable without a developer querying the database directly.
- Shared `ComplianceLabels.kt` (mirroring `AccountingLabels.kt`'s shape) holds the wave's badge
  label/color tables across all five screens, growing to its full twelve-enum set by the final screen
  (`AuditAction`/`AuditEntityType`/`BackupOperationType`/`BackupOperationStatus`/`AvvStatus`/
  `TomCategory`/`DsfaStatus`/`BreachStatus`/`BreachDeadlineStatus`/`DpiaRiskBand`/`RiskLevel`/
  `ErasureStatus`/`ErasureMode`/`DsgvoAuditAction`/`BoardChangeType`).
- New `jsTest` coverage per screen (`AuditLogScreenTest.kt`, `BackupHttpTest.kt`,
  `DsgvoComplianceScreenTest.kt`, `DsgvoRightsScreenTest.kt`, `BoardMembershipScreenTest.kt`) plus
  matching `ComplianceLabelsTest.kt` additions for every new enum, covering the pure filter-parsing,
  chain-verification-copy, HTTP-status-to-outcome mapping, breach re-sort/rank, step-tracker state
  machine, and displaced-incumbent/beneficial-owner-gap builder functions factored out of each screen.
  `./gradlew clean check` green throughout every screen's commit, including ktlint.

## [0.10.0] — 2026-08-04

### Added

**Accounting UI wave — five new screens (Ledger & Journal, Financial Reports, Nonprofit Compliance
Reports, Cost Centers, Donors), on `feature/accounting-ui`. Wave complete.** The pilots (PdV, ELB)
picked Accounting as their #2 UI priority after Governance ("Schatzmeister-Tagesgeschäft" — the
treasurer's day-to-day tool). All five screens surface the already-implemented, already-tested
`IAccountingService`/`AccountingService` SKR42 double-entry backend end to end for the first time —
accounting/treasury work that previously required a developer with direct RPC/API access is now
usable from the browser. Consistently gated TREASURER/BOARD/ADMIN at the route level, with every
mutating action further narrowed to TREASURER/ADMIN in-screen (`TREASURY_ROLES`/
`ACCOUNTING_READ_ROLES`, matching the server's own split exactly) — a BOARD caller reaches every
screen but sees write affordances on none of them. Every monetary figure across all five screens is
a `Decimal` returned verbatim by the server and rendered through the shared `Money.kt`
(`formatMoney`/`moneySpan`) — no client-side re-rounding or re-deriving of a figure the server has
already computed, the one recurring exception being typed sign comparisons used purely to drive
`warnIfNegative` styling. **No backend/RPC changes across the whole wave** — the pre-existing
`IAccountingService` surface was sufficient as-is for all five screens, so unlike the Governance UI
wave below, no gap was found here that needed a new server-side method.

- **`LedgerScreen.kt`** (`#/ledger`) — SKR42 Kontenplan (list/create/deactivate) and the Journal
  (Grundbuch) draft/post workflow, plus a per-account Hauptbuch/Kassenbuch drill-down. A bespoke
  posting-confirmation modal renders the full balanced Soll/Haben table before an irreversible post;
  since neither RPC offers an update/delete path for an existing draft, an "Als neuen Entwurf
  duplizieren" action fills a new-entry form from a wrong draft's data instead of silently resubmitting
  it. Makes day-to-day SKR42 bookkeeping (opening/managing accounts, drafting and posting journal
  entries, reading the general/cash ledger) usable without developer access for the first time.
- **`FinancialReportsScreen.kt`** (`#/financial-reports`) — GuV, Bilanz (with a visible "ausgeglichen"
  sanity badge for the server-guaranteed `balanced` flag) and Jahresabschluss, purely read-only.
  Jahresabschluss reuses the same GuV/Bilanz rendering functions as the standalone tabs so it can
  never drift from them. Makes org-wide financial statements — previously only obtainable via a raw
  RPC call — a self-service report for TREASURER/BOARD.
- **`NonprofitComplianceReportsScreen.kt`** (`#/compliance-reports`) — Vier-Sphären-Ergebnisrechnung
  (all four `GemeinnuetzigkeitSphere` rows in server-returned order, expand-in-place income/expense
  detail reusing `FinancialReportsScreen`'s own line-table renderer) and Mittelverwendungsrechnung
  (§55/§62 AO), with a persistent, non-dismissible banner stating this is a Nachweis-Hilfe for the
  board, not an automated compliance verdict, and the timely-use-window figure interpolated live from
  the DTO rather than hardcoded. The single most gemeinnützigkeitsrechtlich sensitive report pair in
  the system, surfaced to the board for the first time without needing a developer to run a query.
- **`CostCentersScreen.kt`** (`#/cost-centers`) — Kostenstellen-/Projektbuchhaltung (DATEV KOST2 sense):
  list/create/deactivate plus a date-range-scoped report with a distinct "Nicht zugeordnet" row for
  untagged postings and a bold grand-total row, both reconciled figures rendered verbatim from the
  server. Makes project/event-scoped cost tracking (e.g. tagging postings to "SOMMERFEST-2027") a
  treasurer self-service task.
- **`DonorsScreen.kt`** (`#/donors`) — external-donor CRM-lite CRUD (a distinct, non-Member entity,
  never merged into the member list) plus the calendar-year-scoped §25 PartG
  Spendenrecht-Pflichten-Report: per-donor open-duties table and a separate per-donation
  anonymous-forwarding table, both captioned to make clear this is not a prohibited-donation list —
  those are hard-blocked server-side at post time. Makes party-donation-law disclosure-duty tracking
  usable without a developer querying the database directly.
- New `jsTest` coverage per screen (`LedgerScreenTest.kt`, `FinancialReportsScreenTest.kt`,
  `NonprofitComplianceReportsScreenTest.kt`, `CostCentersScreenTest.kt`, `DonorsScreenTest.kt`), plus
  first-consumer coverage for the shared `AccountingLabels.kt`/`Money.kt` files. `./gradlew clean
  check` green throughout every screen's commit, including ktlint.

**Governance UI wave — three new screens (Committees & Membership "Gremien", Meetings "Sitzungen",
Motions & Voting "Anträge"), on `feature/governance-ui`.** The V1.0 pre-production readiness check
found 13 backend domains with zero client UI, reachable only via Kilua RPC/raw HTTP; the pilots
(PdV, ELB) picked Governance first (committees, meetings, motions/voting are day-to-day board
business). All three screens ship against the already-implemented, already-tested
`IGovernanceService`/`GovernanceService` backend, routed at `#/committees`, `#/meetings` and
`#/motions` with matching nav links and dashboard tiles, open to any authenticated member
(`IGovernanceService`'s reads require no role at all — role gates apply only to the write actions
below). Governance business that previously required a developer with direct RPC/API access is now
usable end to end from the browser:

- **Gremien** (`CommitteesScreen.kt`): committee directory (list + BOARD/ADMIN-only create/edit) and
  per-committee membership roster (BOARD/ADMIN-only add-member, sourced from the already
  AKTIV-filtered `IMemberService.listMembers()`, and end-membership via a small dated-confirmation
  modal). All four write actions are strictly global BOARD/ADMIN — committee leadership does not
  qualify here, unlike the two screens below.
- **Sitzungen** (`MeetingsScreen.kt`): filterable meeting list with a single combined
  agenda+attendance+resolutions+quorum detail view; BOARD/ADMIN/committee-leadership-gated creation
  and PLANNED → HELD/CANCELLED status transitions; ordered agenda add/remove; per-eligible-member
  attendance recording with a live pass/fail quorum display; a Committee-Quorum resolution book; and
  an always-visible protocol draft (inline preview + browser-print, backed by a new `@media print`
  stylesheet in `index.html`) — deliberately no client-generated downloadable file this wave, browser
  print covers the interim need until the future Serienbrief-/PDF-Engine (V0.4).
- **Anträge** (`MotionsScreen.kt`): the full Motion lifecycle — submit (target-Committee picker
  scoped to what the caller actually qualifies for), amend (rendered indented beneath its parent,
  with the amendment-ordering guard surfaced proactively as a warning rather than left to a server
  error), review, schedule (against a Committee's PLANNED Meetings), and resolve via either a
  Committee-Quorum vote or a Meritocratic Vote — plus that Vote's own sub-flow (`openVote`/
  `castVoteBallot`/`closeVote`/`abortVote`) as one screen rather than a separate `VotesScreen.kt`,
  since a Vote only ever exists in the context of one `SCHEDULED` Motion. Sealed-bid by design while
  OPEN (ballot count and the caller's own ballot only, never running totals or a leaderboard — a
  deliberate UI-level choice per the design review's Jobs' final call: "if the mechanism wants sealed
  bids, the interface should feel sealed"), full reveal once CLOSED. Reuses the Meetings screen's own
  resolution rendering so a Resolution looks identical on both screens.
- **Real backend gap found and fixed while building the Motions screen (not a UI-authoring
  mistake): `IGovernanceService.listVotes(motionId, status)`.** Every Vote-scoped method
  (`getVote`/`castVoteBallot`/`closeVote`/`abortVote`/`listVoteBallots`) required already knowing a
  specific Vote's id, which only ever reached a client as the return value of the one `openVote` call
  that created it — a second visitor to a Motion, or a page reload, had no RPC path back to an
  already-OPEN Vote's id at all. Added as a small, read-only, no-role-required, purely additive
  method mirroring `listMotions`'s own optional-filter shape, in `GovernanceService.kt`, with new
  `GovernanceServiceTest.kt` coverage. No other backend/RPC behavior changed — the Committees and
  Meetings screens needed no backend changes at all.
- Shared infrastructure introduced across the wave: `StatusBadge.kt` (`statusBadge`/`typeBadge`,
  solid = lifecycle status vs. outline = fixed category, per the UI/UX-Design-Team review) and
  `GovernanceAuthzUi.kt` (`canRecordForMeeting`, a pure client-side mirror of
  `GovernanceAuthorization.canRecordForMeeting`), both reused across all three screens' role gating
  and badges.
- New `jsTest` coverage per screen — `CommitteesScreenTest.kt`, `MeetingsScreenTest.kt`,
  `GovernanceAuthzUiTest.kt`, `MotionsScreenTest.kt` (label/color completeness, hue coverage, and
  authorization branch coverage) — plus the `GovernanceServiceTest.kt` addition above.
- `./gradlew clean check` green throughout, including ktlint.

**V1.0 "Pilot-Produktivbetrieb" end-to-end integration test wave — six real, cross-domain journey
scenarios, on top of the pre-existing 1,079 `lapis-server` per-service tests.** Every existing
`*ServiceTest`/`*RoutesTest`/`*SchemaDriftTest` file (the codebase's overwhelming majority of test
coverage) verifies exactly one service in isolation, mounting only that service's own hand-picked
routes and constructing it directly — a proven, fast, house-standard pattern, but one that cannot by
construction catch a defect that only manifests where two or more domains actually meet: a session
that outlives the status change that should have killed it, a status gate that one write path
enforces and a structurally identical sibling path forgets, a response type that only breaks once a
real success path is driven through the real HTTP route instead of called as a plain Kotlin method.
This wave adds a new, deliberately different second layer: `E2eSupport.kt` mounts the **real, fully
wired `network.lapis.cloud.server.module()`** — every route, every one of `initRpc`'s
`registerService` calls, the complete `StatusPages`/session-cookie/`CallLogging` middleware stack —
in a single `testApplication`, and each of the six scenarios drives it as one continuous story using
**real `/api/auth/login` calls and real session cookies** (not a bypass header) wherever a scenario's
narrative logs in as somebody, real plain-HTTP routes (mailmerge PDFs, backup export/restore) via
`client.get/post`, and small per-scenario throwaway routes that construct an RPC service class
directly (`GovernanceService(call)`, `AccountingService(call)`, ...) — the same construction
`initRpc`'s own factories use — layered onto that same real `module()`-wired application, so a
genuinely wire-level Kilua JSON-RPC call is the only thing elided (see "Known limitations" below for
why). Each scenario is written as a single Kotest `test()` block spanning several existing waves' RPC
surfaces end to end (self-registration → board approval → LTR economy → governance vote → GoBD audit
chain, in one continuous session), asserting the **seam** between domains — e.g. an LTR balance drop
proven through an independent `LtrLedgerService` read rather than the writer's own return value, or a
Committee seat's live eligibility proven to pick up a just-minted row rather than a stale snapshot —
not re-litigating any single domain's already-covered internal logic.

- **Scenario 1 — membership-to-governance journey** (`MembershipToGovernanceJourneyTest`): real
  self-registration → real login → the V0.9.0 ANTRAG vote-gate proven to actually hold for a funded
  applicant (ruling out "insufficient balance" as an alternative explanation for the 403, and probing
  a non-existent `voteId` to distinguish "gate present" from "gate silently removed") → board approval
  → the **same** session cookie now succeeds (proving the status gate re-reads live DB state on every
  call, not a cached login-time claim) → a real Contribution generated, paid, and manually booked into
  accounting (the real two-call seam, not an automatic posting) → an invoice PDF generated from that
  same paid Contribution, verified via its archived-document title → a full governance cycle with a
  real Vickrey-settled Meritocratic vote → the GoBD audit hash chain recording the settlement
  Resolution, with `verifyChainIntegrity()` still passing over the scenario's own chain segment. No
  production bug found — confirms `markContributionPaid` posts correctly against a member created via
  real self-registration, not just `DevSeedData`'s fixed seed IDs.
- **Scenario 2 — LTR economy journey** (`LtrEconomyJourneyTest`): a pre-existing seeded AKTIV member
  logs in for real, is minted LTR by TREASURER, stakes it into a real Crowdfunding project submission
  under the same session (balance drop proven via an independent `LtrLedgerService` read, not
  Crowdfunding's own write path), gets board approval, receives a real Like from a second, freshly
  registered member via a real session, and two more scenario-private members fund the EUR pool through
  real TREASURER-generated-and-paid Contributions — `computeMonthlyDistribution`'s `amountEur` is
  asserted as an **exact** `BigDecimal` derived from the real paid sum minus the per-payer platform
  deduction (not merely `> 0`), and a re-run over the identical period is asserted idempotent (no
  duplicate distribution row). No production bug found — the Contribution-funds-the-pool /
  Crowdfunding-apportions-it seam works as documented.
- **Scenario 3 — federation guest journey** (`FederationGuestJourneyTest`): one continuous story behind
  a single OIDC-federated guest session (minted directly via `OidcGuestMemberStore` +
  `SessionStore.createSession`, since this sandbox has no outbound network egress for a real
  browser-redirect RP-callback flow) that casts a real Politician rating (200 OK,
  `raterType = GAST` persisted), is refused (403, with a DB check proving no row was created) on a real
  LTR-economy write, and is excluded from a `PUBLIC_MEMBERS` document while a BOARD read of the
  identical call includes it — proving access-level filtering, not an empty result for everyone. The
  wave's highest-value assertion: a guest Like is verified, in two stages, to actually move
  `guestTrustWeight` and `combinedTrustWeight` (guest-only, then combined with a second real AKTIV
  member's rating, asserted equal to the literal sum) — both stages passed on the first run against
  current production code. No production bug found; this scenario combines several independently
  already-correct gates behind one guest identity rather than uncovering a new defect.
- **Scenario 4 — governance status machine journey** (`GovernanceStatusMachineJourneyTest`): real
  self-registration → the V0.9.0 `addCommitteeMember` status gate refuses seating a still-ANTRAG
  applicant onto a Committee → board approval → the **identical** seat call now succeeds → the
  newly-seated member casts a real ballot eligible only via that just-created seat (proving
  `eligibleMemberIds` reads live state, not a stale snapshot) → self-service `leaveMembership`
  (AKTIV → AUSGETRETEN) → a fresh vote-casting attempt from the exited member's own prior session.
  Confirms, via direct DB read, the known-and-deliberately-deferred gap that `leaveMembership` does not
  retire the member's open `CommitteeMembershipTable` row (see "Known limitations" below). Also traces
  and documents a stronger-than-planned guarantee: `leaveMembership` revokes every live session
  belonging to the caller, including the one that just called it, so the final replay attempt fails
  with 401 (dead session) rather than the originally-expected 403 (live session, stale status) — a
  live session paired with `AUSGETRETEN` status is not actually reachable via this exit path at all.
- **Scenario 5 — exit/rejection consequences cascade journey** (`ExitCascadeJourneyTest`): two real
  self-registrations (A, B). BOARD rejects B, proving the V0.9.0 session-hygiene fix (rejection now
  revokes a session that was genuinely live at rejection time, not a hand-inserted row). BOARD approves
  A, who accumulates real cross-domain history (LTR mint + Crowdfunding stake, a paid Contribution, a
  donation JournalEntry) before calling `leaveMembership()` herself. Proves continued lockout across
  two independent layers after exit — A's own dead session now fails 401 on both an LTR call and a
  Governance call, and separately, the H2-only `X-Member-Id` trusted-header fallback still resolves A's
  identity (no status check at that layer) but `requireActiveMembership` re-reads A's live,
  now-AUSGETRETEN status and refuses with 403, proving the gate holds via the fallback authentication
  path too, not just the cookie path. The payoff: after A's exit, a privileged TREASURER read shows A's
  LTR balance unchanged, `AccountingService.listJournal` still returns A's real JournalEntry unchanged
  and POSTED, and `AuditLogService.verifyChainIntegrity()` still passes over the chain segment covering
  it — concrete proof that exit does not retroactively alter or break the GoBD hash chain covering a
  former member's own prior postings.
- **Scenario 6 — organization backup/restore snapshot consistency journey**
  (`OrganizationSnapshotJourneyTest`): a condensed register → approve → contribute → vote → audit
  journey, written entirely by real HTTP/RPC calls against a fresh source H2 instance, exported via the
  real ADMIN-only `GET /api/backup/export`, restored into a second fresh target H2 instance via the
  real `POST /api/backup/restore`, then re-verified entirely through the target's own real RPC/HTTP
  surface — including an unscoped GoBD hash-chain re-verification, the first time in this codebase a
  hash chain built by real business-logic writes is proven to survive a byte-for-byte export/restore
  round trip. Found and fixed a real production bug — see "Fixed" below.

`./gradlew clean check` (rerun from clean, not from build cache) — **1,085 `lapis-server` tests, 0
failures, 0 errors, ktlint clean** (6 new tests, one per scenario above, each a single continuous
Kotest `test()` block; up from the pre-existing 1,079).

### Fixed

**`POST /api/backup/restore`'s success response crashed every genuinely successful restore over real
HTTP with a 500 — found and fixed by Scenario 6.** The route replied with
`mapOf("tablesRestored" to Int, "totalRowCount" to Long, "blobsRestored" to Int, "warnings" to
List<String>)` — a raw `Map` whose *values* span three different types. Ktor's `kotlinx.serialization`
content negotiation infers a serializer for an untyped `Map`/`List` by inspecting its element type,
which only works when every value is the same type; a mixed-type map throws
`IllegalStateException: Serializing collections of different element types is not yet supported`,
unhandled by any `StatusPages` mapping. This had gone completely undetected because every pre-existing
test either only exercised the ADMIN-only role-check rejection path (never reaches this `respond`
call) or called `OrganizationRestoreService.restore()` directly as a plain Kotlin method, bypassing
the HTTP route entirely — Scenario 6 is the first test in this codebase to drive a genuinely
successful restore through the real HTTP surface. Fixed by replying with a typed `@Serializable
RestoreResultResponse` data class instead of the raw map. `BackupRoutes.kt`.

### Known limitations (tracked for later versions)

- **Kilua RPC's JVM client stub is a no-op — a genuinely wire-level Kilua JSON-RPC call cannot be
  driven from a JVM test in this codebase at all.** This is why `E2eSupport`'s real-`module()`
  scenarios, like every pre-existing `*ServiceTest`/`ServiceIntegrationTest`, construct RPC service
  classes directly rather than issuing a literal RPC envelope over the wire — the same,
  already-house-endorsed definition of "real RPC call" this codebase has always used, just now layered
  onto a fully (not partially) wired application with real login/session flows on top. A test-tooling
  gap, not a production defect.
- **`leaveMembership` does not retire an already-open `CommitteeMembershipTable` seat.** Confirmed live
  by Scenario 4 through the real `GovernanceService.listCommitteeMembers(activeOnly = true)` read path
  (plus a direct DB read of the underlying row): after a member self-exits (AKTIV → AUSGETRETEN), that
  call still lists them as an active seat holder. The V0.9.0
  `addCommitteeMember`/`appointElectionBoard`/`tally` status gates close the *seating* side of this gap
  (a non-AKTIV member can no longer be newly seated) but nothing yet retires a seat that was already
  open before the seatholder's status changed — structurally the same open question applies to any
  other status transition away from AKTIV (e.g. `rejectApplication`), though only the `leaveMembership`
  path has been live-verified by this wave.
- **`computeMonthlyDistribution` writes a decision/allocation record only — it never posts a
  `JournalEntry`.** `CrowdfundingService.computeMonthlyDistribution` inserts
  `CrowdfundingDistributionTable` rows (who gets how much of the monthly EUR pool) but calls no
  accounting-posting path at all; the actual EUR transfer implied by that allocation is not booked into
  the GoBD-audited ledger by this method. Scenario 2 asserts this as the wave's own documented,
  deliberate scope cut, not as a newly discovered gap — pinned down by a global `JournalEntryTable`
  before/after row-count comparison across the distribution run, so that wiring up automatic posting
  later breaks the assertion and forces this entry to be revisited rather than silently rotting.
- **Scenario 6 additionally flagged, but did not fix, two further backup/restore findings:** (1) the
  HTTP restore route can never reach `OrganizationRestoreService`'s "primary supported" fresh-target
  path in practice, since ADMIN-only auth requires an existing member row that a truly empty target
  does not have — a chicken-and-egg gap independently confirmed by `AdminBootstrap`'s own KDoc; (2)
  `session` rows are **not** excluded from the organization export/restore bundle
  (`OrganizationSchemaCatalog`'s only exclusion is `flyway_schema_history`, a deliberate V0.5.4
  security-loop scope decision) — proven concretely by replaying a source-issued raw session token
  against the restored target, where it is still live.

## [0.9.0] — 2026-07-30

### Fixed

**DNS-rebinding TOCTOU gap closed in the federation SSRF guard — disclosed since V0.8.1,
`requireSafeFederationUrl`/`federationHttpClient`.** The guard previously validated a resolved address
and then let Ktor's CIO client engine perform its own, independent, later DNS resolution for the actual
connection — a malicious DNS server could answer with a public, safe-looking address at
`requireSafeFederationUrl`-check-time and a private/internal address (`127.0.0.1`, a cloud metadata
endpoint, an internal service) at actual-connect-time, completely bypassing the SSRF guard.

`requireSafeFederationUrl` now returns a `SafeFederationTarget` (the original hostname plus the specific
validated `InetAddress`, the first of an `InetAddress.getAllByName` result where — unchanged from the
original design — ALL resolved addresses must be safe, not just one; a resolver answering with one safe
and one unsafe address for the same name is itself untrustworthy). A new Ktor client plugin
(`FederationIpPinningPlugin`, installed by `federationHttpClient(target)`) rewrites the outgoing request's
URL host to that pinned address's literal string immediately before the request reaches the engine —
`java.net.InetSocketAddress` (which Ktor CIO's own `Endpoint.connect()` builds directly from
`request.url.host`) performs no DNS lookup for a literal IP, so there is no second resolution anywhere in
the fetch path. There is no fallback to a sibling address on connect failure — one resolution, one
address, one connection attempt, matching this project's existing no-retry-queue posture for federation
delivery.

**TLS certificate validation was not weakened by this change.** TLS SNI and hostname verification are
explicitly pinned to the ORIGINAL hostname (`CIOEngineConfig.https.serverName = target.originalHost`,
which `Endpoint.connect()`'s handshake block never overwrites once explicitly set), and an explicit
`Host:` header preserves correct virtual-host routing for the remote server. Confirmed by two new tests
against a REAL CIO+TLS connection to a self-signed certificate (`FederationIpPinningTest` "T3"/"T3b"): a
certificate covering the SNI'd hostname is accepted even though the socket target is a loopback IP, and a
certificate that does NOT cover the hostname actually used for SNI is still correctly rejected — IP
pinning does not silently bypass hostname verification.

Chosen over a CIO-internal resolver/connector hook (verified against the actual pinned Ktor 3.5.1
`ktor-client-cio` sources: `CIOEngineConfig` exposes no such hook, and the classes that actually resolve
and connect, `Endpoint`/`ConnectionFactory`, are `internal` to that module — no clean extension point
exists) and over an engine swap to `ktor-client-java` (`java.net.http.HttpClient` has no DNS-resolver hook
either, so the same literal-IP-rewrite trick would still be required, while adding an unprecedented
dependency and requiring the entire verification pass to be redone against a different engine's
Host-header-override and connection-reuse behavior, for no additional robustness).

Applied once, in `FederationHttpClient.kt` — every one of the 9 call sites across 6 files that build a
federation HTTP request (`fetchActorDocument`, `TrustAnchorResolver.fetchCompactJwt`,
`OidcClientRegistrar.register`, `OidcBackChannelLogoutNotifier.notify`,
`FederationService.deliverActivity`, and four sites in `OidcRoutes.kt` — RP-side token exchange, RP-side
and Issuer-side back-channel-logout JWKS fetches, and OIDC discovery-document fetch) was mechanically
updated to capture and pass the `SafeFederationTarget`; the old zero-argument `federationHttpClient()`
no longer exists, so the compiler enforces that no call site can silently keep using the unpinned path.

New test coverage (`FederationIpPinningTest`, 7 cases): a genuine DNS-rebinding simulation via a
`java.net.spi.InetAddressResolverProvider` test double (`RebindingSimulationInetAddressResolverProvider`,
narrowly scoped to one synthetic `.invalid` hostname, verified transparent to every other test in this
module) proving the attack precondition is real and that the plugin uses the captured, pinned address
rather than re-resolving; the TLS hostname-verification pair described above; and a regression test for a
real bug found live while building this suite — `HttpRequestBuilder.url` (a mutable `URLBuilder`) leaves
an unspecified port as the raw `0` sentinel rather than normalizing it to the protocol's default port the
way the immutable `Url.port` getter does, so the plugin's Host-header logic had to replicate that
normalization itself to avoid emitting `host:0` for every ordinary (no-explicit-port) federation request.
The existing `FederationHttpClientSsrfTest` suite (11 cases, including the V0.8.1 IPv6-ULA fix) needed no
changes and passes unchanged — its assertions only ever check `runCatching { requireSafeFederationUrl(...) }.isFailure`,
unaffected by the return type changing from `Unit` to `SafeFederationTarget`.

`README.adoc`'s "What doesn't work yet" section names this exact gap by name — intentionally not edited
in this wave per this project's standing convention (README/version-bump/tag catch-up happens only after
human merge review); needs a matching edit once this fix lands on `master`.

**ANTRAG membership-gate audit — closes the gap disclosed since V0.7.2.** `PeerTransferService.transferLtr`
and `GovernanceService.castVoteBallot` now call `requireActiveMembership` before any state-changing
read/write, closing the gap V0.7.2's own "Known limitations" first disclosed (an `ANTRAG` applicant —
who can log in by design to check their pending application status, see `AuthRoutes.kt`'s login-gate
KDoc — could in principle stake/transfer LTR or cast a governance vote before board approval). The gap
was confirmed still open by reading both methods directly on `master` HEAD: neither called
`requireActiveMembership`/`requireActiveOrGuestMembership` (`rpc/MembershipGuards.kt`), unlike
`CrowdfundingService`/`AuctionService`/`PoliticianService`, which already reuse those gates correctly.

A systematic audit of every LTR-spending and vote/ballot/rating/resistance-casting RPC method across
`PeerTransferService`, `GovernanceService`, `ElectionService`, `SystemicConsensusService`,
`LtrLedgerService`, `CrowdfundingService`, `AuctionService`, and `PoliticianService` found three sibling
gaps in the same class, all fixed identically: `ElectionService.castElectionBallot` and
`SystemicConsensusService.castResistanceBallot` relied solely on a Committee-eligibility snapshot
(`ElectionEligibleVoterTable`/`SystemicConsensusEligibleVoterTable`, both derived from
`CommitteeEligibility.eligibleMemberIds`) that never re-checks the caller's live membership status for a
non-`GENERAL_ASSEMBLY` Committee — root cause: `GovernanceService.addCommitteeMember` never validates the
seated member's own status before seating them (flagged, not fixed, in this wave — see below); and
`AuctionService.buyNow` — despite `createListing`/`placeBid`/`settleAuction` in the same file already
calling `requireActiveMembership` correctly — was itself missing the gate entirely, spending LTR
(`AUCTION_SALE_OUT`) and settling ownership transfer with no membership check at all.

All five fixes use `requireActiveMembership` (AKTIV-only), not the guest-inclusive
`requireActiveOrGuestMembership`: LTR transfer/auction actions cannot involve GAST members at all
(V0.8.2's own disclosed "no guest participation in the LTR economy yet" limitation), and binding
governance votes are member-only per this project's own concept ("Keine Stimmrechte für Gäste" — guests
never get vote weight, full stop). No case in this audit was found where the guest-inclusive variant
would be correct for any of the five fixed methods.

**Complete audit inventory:**
- **Fixed (gap → `requireActiveMembership` added):** `PeerTransferService.transferLtr`,
  `GovernanceService.castVoteBallot`, `ElectionService.castElectionBallot`,
  `SystemicConsensusService.castResistanceBallot`, `AuctionService.buyNow`.
- **Audited and confirmed already correct, unchanged:** `CrowdfundingService.submitProject`,
  `AuctionService.createListing`/`placeBid`/`settleAuction`, `PoliticianService.castRating`/
  `retractRating` (correctly using the guest-inclusive `requireActiveOrGuestMembership`),
  `LtrLedgerService.mintLtr` (privileged-only, `TREASURER`/`BOARD`/`ADMIN`, never member-initiated),
  `PeerTransferService.executeArbitrationTransfer` (privileged-only, same reasoning),
  `ElectionService.submitCandidacy` (`canStandAsCandidate()` already does a live AKTIV check).
- ~~**Not fixed in this wave, flagged for follow-up:** `GovernanceService.addCommitteeMember` never
  validates the target member's status before seating them into a Committee~~ **Resolved — see
  "addCommitteeMember status gate closes the root cause" below.** — the actual root cause
  enabling the Committee-membership-based eligibility gap above; `GovernanceService.submitMotion` and
  `SystemicConsensusService.addOption` share the same structural "Committee-membership without a live
  status recheck" pattern (via `canSubmitMotion`/`eligibleMembersOf`) but carry no direct LTR/binding-vote
  consequence on their own and were judged lower priority/out of this audit's explicit scope
  (LTR spend/stake/transfer and vote/ballot/rating/resistance casting).
- `README.adoc`'s "What doesn't work yet" section still names this exact gap — intentionally not edited
  in this wave per this project's standing convention (README/version-bump/tag catch-up happens only
  after human merge review); needs a matching edit once this fix lands on `master`.

**Independent round-1 security review found and closed one more sibling gap the wave's own inventory
missed: `CrowdfundingService.castReaction`/`retractReaction`.** Neither call was in the "Complete audit
inventory" above — only `submitProject` was checked in this file. The Verteilungs-Korb (distribution
basket) reaction is documented as "LTR-**unweighted**" (`17-crowdfunding.kuml.kts` header point 2), which
is why it slipped past a search scoped to LTR-spending calls, but it is still a binding one-member-one-vote
decision that directly drives the real monthly EUR donation pool's proportional split
(`computeMonthlyDistribution`) — squarely the same "binding governance action reachable by a non-AKTIV
caller" class this wave otherwise fixed. Neither method called `requireActiveMembership`; both now do, as
the first statement inside their `transaction {}` (same idiom as `submitProject` in the same file).
Severity is compounded by a second, narrower finding from the same review: `RegistrationService
.rejectApplication` does not call `SessionStore.revokeAllForMember` (unlike `leaveMembership`, which
does) — a rejected (`ABGELEHNT`) applicant's session(s) from their `ANTRAG` period remain valid until
natural 8-hour expiry, so the missing gate was reachable by a rejected applicant, not just a still-pending
one. Not fixed in this round — see "Known limitations" below; every state-changing method that matters is
already independently protected by its own live-status `requireActiveMembership` check, which reads
current DB state and is unaffected by a lingering session, so the practical exposure window closing this
one CrowdfundingService gap removes is the only one that mattered. Both `castReaction`/`retractReaction`
now have ANTRAG-rejected / ABGELEHNT-rejected / AUSGETRETEN-rejected / AKTIV-still-succeeds regression
tests in `CrowdfundingServiceTest`, matching the house style the original wave established.

**addCommitteeMember status gate closes the root cause the ANTRAG membership-gate audit above
identified but deliberately deferred.** `GovernanceService.addCommitteeMember` now calls
`requireActiveMembership` on `input.memberId` — the member being seated — before writing the
`CommitteeMembershipTable` row, rejecting `ANTRAG`/`AUSGETRETEN`/`ABGELEHNT`/`GAST` targets with
`403 Forbidden`. Checked on the seatee, not the caller: the caller's `BOARD`/`ADMIN` role is
already separately enforced by the existing `requireRole` call above. `ElectionService
.appointElectionBoard` got the identical per-appointee gate for the same reason — an election-board
seat grants `isElectionBoardMember`/`isElectionBoard` authority (Vier-Augen tally-approval counting
via `approveTally`, operational control via `openVoting`/`closeVoting`/`tally`) to whoever is
appointed, so it is exposed to the same "seat a non-AKTIV member" gap class as Committee seating.
`castVoteBallot`/`castElectionBallot`/`castResistanceBallot`'s own `requireActiveMembership` calls
(added by the audit above) remain as an explicit second layer, since Committee/election-board
membership can still exist from a legacy pre-fix row or a future seating path that bypasses these
two methods — their KDoc/inline comments were updated to say so accurately rather than implying the
gap is fully closed everywhere.

**Round-2 review of this fix (2026-07-30) found and closed one more sibling gap the fix's own
inventory missed: `ElectionService.tally`'s winner-seating branch.** `tally`'s `EXECUTIVE_BOARD`/
Committee winner-seating loop writes `CommitteeMembershipTable` directly — it is a second,
independent seat-creation path that was never routed through `addCommitteeMember`, so that method's
new gate did not cover it despite comments elsewhere in `ElectionService` assuming Committee seats
only ever originate there. `canStandAsCandidate` only re-checks live `AKTIV` status at
`submitCandidacy` time; a candidate who is genuinely `AKTIV` when they stand, then calls
`leaveMembership` (→ `AUSGETRETEN`) any time before the election board runs `tally`, would otherwise
have been seated with no live status recheck at all — for an `EXECUTIVE_BOARD` targetCommittee that
means a departed member becoming a real, `BoardMembershipEvents`-audited Vorstand seat
(Transparenzregister-relevant). `tally` now calls `requireActiveMembership(winnerMemberId)` per
winning candidate before the existing single-active-membership-row seating logic runs; since the
whole method is one `transaction {}`, a disqualified winner aborts the entire tally (fail-closed —
the election board must resolve the situation and re-tally, rather than silently skipping just that
seat). New tests: `addCommitteeMember`/`appointElectionBoard` now have direct ANTRAG/AUSGETRETEN/
ABGELEHNT/GAST-rejected and AKTIV-still-succeeds coverage (`GovernanceServiceTest`/
`ElectionServiceTest`, previously only exercised indirectly as setup for the `castVoteBallot`/
`castElectionBallot` defense-in-depth tests — those two tests now seed the Committee-membership row
directly via the table instead of through the now-gated RPC call, since seating a non-AKTIV member
through the public API is exactly what the fix prevents); `tally` has a new regression test proving
a winner who leaves membership after voting closes but before tally is rejected and nothing is
seated.

**Round-3 review of this fix (2026-07-30) found and closed a TOCTOU gap in all three seat-minting
call sites the round-1/round-2 fixes added.** `requireActiveMembership` performed a plain, non-locking
`SELECT` — under the project's Postgres/READ COMMITTED setup that neither blocks a concurrent writer
nor is blocked by one, so a concurrent `leaveMembership()` (or any other status-changing transaction)
could commit its `UPDATE MemberTable SET status = ...` in the gap between this check and the later
`INSERT` that mints a new seat, seating a member from a status read that was already stale by commit
time. Every other structurally identical "check a row's status, then act on it later in the same
transaction" call site in this codebase already closes exactly this race with a `SELECT ... FOR
UPDATE` row lock (`RegistrationService.approveApplication`/`rejectApplication`,
`PoliticianService`'s revoke-vs-rate guard, `AuctionService`, `CrowdfundingService`,
`GovernanceService.resolveMotion`/`closeVote`, `ElectionService.tally`'s own Motion-row read) — the
three new `requireActiveMembership` call sites that mint a Committee/election-board seat
(`GovernanceService.addCommitteeMember`, `ElectionService.appointElectionBoard`,
`ElectionService.tally`'s winner-seating loop) were the only exception. `requireActiveMembership` now
takes an optional `forUpdate: Boolean = false` parameter (default preserves the historical behavior
for the many pre-existing callers that only gate an in-place action — casting a ballot, placing a
bid, staking LTR — rather than minting a new persistent row); all three seat-minting call sites now
pass `forUpdate = true`. (Implementation note: the row lock could not simply be bolted onto the old
`count() > 0` existence check — Postgres rejects `FOR UPDATE` combined with an aggregate function —
so the helper was rewritten to `singleOrNull()`-and-compare, matching the style
`requireActiveOrGuestMembership` already used.) `./gradlew clean check` reconfirmed green (1077+
tests, zero failures) both before and after, including a from-cache re-run for reproducibility.

### Known limitations (tracked for later versions)

- ~~**`RegistrationService.rejectApplication` does not revoke the applicant's existing session(s).**~~
  **Resolved — see "Rejected applicants' pre-existing sessions are now revoked" below.** Found during
  the round-1 security review of the ANTRAG membership-gate audit above. Every state-changing RPC
  method with LTR/binding-governance consequences independently re-checks live `MemberTable.status`
  via `requireActiveMembership`/`requireActiveOrGuestMembership` inside its own transaction, so a
  lingering post-rejection session could not bypass any of those gates — but a future method that
  forgets the gate (as `CrowdfundingService.castReaction`/`retractReaction` did until this round) would
  have been reachable for up to the remainder of the session's 8-hour lifetime after rejection, not
  just during the `ANTRAG` window. Fixing this at the source (revoke on `rejectApplication`, same
  `SessionStore.revokeAllForMember` call `leaveMembership` already makes) removes that residual
  exposure window entirely regardless of future per-method gate coverage — it was a defense-in-depth
  hardening, not a currently-exploitable path against any live method, but is now closed rather than
  deferred.

**Rejected applicants' pre-existing sessions are now revoked — session-hygiene gap the V0.7.2
ANTRAG-membership-gate audit (commit `5082d55`) found and deliberately deferred, now closed.**
`RegistrationService.rejectApplication` transitions a Member from `ANTRAG` to `ABGELEHNT` but
previously never revoked any session the applicant had already established while still `ANTRAG` --
unlike the sibling `leaveMembership` (`AKTIV` -> `AUSGETRETEN`), which has always called
`SessionStore.revokeAllForMember` immediately after its transaction commits. `rejectApplication`
now does the same, for the applicant being acted on (not the BOARD/ADMIN caller). This is
complementary to, not a replacement for, `AuthRoutes.kt`'s existing V0.7.2 login gate, which
already blocks a NEW login for an `ABGELEHNT` account but did nothing about a session that already
existed before the rejection decision. Practical exposure was already contained (every LTR/
governance write path is independently AKTIV-gated per the same audit), but the session itself
outliving the rejection was a real, avoidable hygiene gap. New tests in `RegistrationServiceTest`
confirm genuine revocation (both a live-session case with multiple sessions, and a no-live-session
regression case that must not throw).

### Security

Adds explicit `requireActiveMembership` gates to `PeerTransferService.transferLtr`,
`GovernanceService.castVoteBallot`, `ElectionService.castElectionBallot`,
`SystemicConsensusService.castResistanceBallot`, and `AuctionService.buyNow` — all previously reachable
by an authenticated `ANTRAG`/`AUSGETRETEN`/`ABGELEHNT` caller under specific conditions (the first two
confirmed directly reachable by any such caller; the latter three additionally required the caller to
already be seated in a non-`GENERAL_ASSEMBLY` Committee via an unguarded `addCommitteeMember` call, or —
for `buyNow` — simply required `auctionEnabled=true`, no Committee involved at all). Verified end to end
against a live server (H2 in-memory, real self-registration → `ANTRAG` → real `transferLtr`/
`castVoteBallot` RPC call → `403 Forbidden`), not just at the unit-test layer. 26 new test cases across
`PeerTransferServiceTest`/`GovernanceServiceTest`/`ElectionServiceTest`/`SystemicConsensusServiceTest`/
`AuctionServiceTest`, each covering an `ANTRAG` rejection, an `AUSGETRETEN` (or `ABGELEHNT`) rejection,
and an explicit `AKTIV` regression proving the legitimate case is unaffected; the Committee-membership
paths (`castVoteBallot`/`castElectionBallot`/`castResistanceBallot`) deliberately seat the non-AKTIV
member into the Committee first, proving the fix closes the real gap-class and not just the trivial
"never a Committee member" case the pre-existing outsider/authz tests already covered.

### Fixed

**GoBD audit-log hash-chain tamper-evidence guarantee undermined by a timestamp-precision mismatch (root-cause fix) — discovered via a GitHub Actions CI failure that had gone unactioned for 7+ days.** `Clock.System.now()` can return nanosecond-precision `Instant`s (confirmed on the Linux CI runner: `2026-07-29T00:42:18.185317372`), but every `TIMESTAMP` column in this schema — H2 running in `MODE=PostgreSQL` locally, real PostgreSQL in production, since Postgres has never supported sub-microsecond `TIMESTAMP` precision — silently truncates stored values to 6 fractional digits on write. `AuditHashChain.canonicalPayload` folds `ChainInput.occurredAt.toString()` into its SHA-256 input; `AuditLogRecorder.record` computed `entryHash` from the full-nanosecond-precision value BEFORE the INSERT truncated it, so any later read-back-and-recompute (`verifyChainIntegrity`'s entire purpose) produced a hash mismatch indistinguishable from real tampering — a genuine correctness defect in a compliance-critical (GoBD §146 AO revision-safety) feature, not a cosmetic test-flakiness issue. Verification runs on this codebase's own developer machine never caught it: macOS's JDK wall-clock resolution never produces sub-microsecond `Instant` values in the first place (confirmed empirically — every sampled `Instant.now()` nanosecond field is already an exact multiple of 1000), so the truncation was always a silent no-op locally; only Linux CI, which genuinely does return nanosecond-jitter timestamps, ever exercised the bug. All four `AuditLogServiceTest` failures (including both `verifyChainIntegrity` cases) plus three `SessionStoreTest`/`AuthServiceTest`-family failures reported by CI trace to this one root cause.

New `network.lapis.cloud.server.db.DbClock.nowLocalDateTime()` — truncates to microsecond precision via `java.time.LocalDateTime.truncatedTo(ChronoUnit.MICROS)` at the moment of capture, before the value is used for anything (hashing, business logic, or insertion), verified against a real H2-in-`MODE=PostgreSQL` round-trip test (`DbClockTest`). Every one of the 25 duplicated `nowLocalDateTime()`/`nowUtc()`/`trustAnchorNowLocalDateTime()` function definitions across the codebase (23 matching the `nowLocalDateTime` name exactly, plus `dsgvo/DsgvoSupport.kt`'s `nowUtc()` and `federation/TrustAnchorKeyMaterial.kt`'s `trustAnchorNowLocalDateTime()`), plus 14 further inline (never-wrapped-in-a-function) `Clock.System.now().toLocalDateTime(...)` call sites across 10 more files, now delegate to this single utility — eliminating both the precision bug and the duplication-and-drift risk that made the bug possible to reintroduce in the first place. `PriceOracleService`'s externally-sourced `priceTimestamp` (not itself hash-dependent, but persisted) is also routed through the new `LocalDateTime.truncatedToDbPrecision()` extension for storage-value hygiene/consistency.

Audited every other timestamp-then-persist-then-compare pattern in `federation/*`/`security/*` for the same bug class — none found to carry the same risk: HTTP-Signature `date` headers and OIDC/Trust-Anchor JWT `iat`/`exp` claims are inherently whole-second-resolution by their own wire formats (RFC 1123 / RFC 7519) and are verified with clock-skew tolerance, never byte-exact comparison; `FederationReplayGuard`/rate limiters are pure in-memory `ConcurrentHashMap`s that never round-trip through the DB; `TrustAnchorEventStore`/`OidcLoginAuditRecorder`'s forensic logs are deliberately NOT hash-chained (their own KDoc says so) so a truncation mismatch there produces no false-tamper signal. `AuditLogRecorder`/`AuditHashChain` was the only genuine instance of a DB-persisted timestamp folded into a cryptographic hash later re-derived from storage.

### Verification

New regression tests: `AuditLogServiceTest` gains a `DbClock.nowLocalDateTime()` nanosecond-multiple-of-1000 sanity assertion (platform-independent, unlike the pre-existing hash-recomputation test, which only ever detects this bug on a clock with genuine sub-microsecond jitter — never on this codebase's macOS developer machines) and a full capture → hash → INSERT → fresh SELECT (new transaction) → recompute → compare test proving the actual previously-broken invariant now holds; a new `DbClockTest` exercises the same truncation guarantee directly against a live H2-in-`MODE=PostgreSQL` round trip, independent of the audit-log domain. `./gradlew clean check` green locally (all `lapis-server`/`lapis-client`/`lapis-shared` tests, ktlint clean). The four previously-CI-failing `AuditLogServiceTest` cases (including both `verifyChainIntegrity` cases) and the `SessionStoreTest`/`AuthServiceTest` cases named in the originating CI failure are expected to pass on the next CI run — a clean local `./gradlew clean check` on this machine is explicitly *not* sufficient evidence of the fix by itself (see above), so CI confirmation on this branch before merge is recommended.

### Added

**Änderungsantrag / amendment-motion support (V0.2.6) — closes a gap found during the 2026-07-28 feature-gap re-audit.** `MotionDto`'s own KDoc has said, since V0.2.2, "deliberately no amendment/'Aenderungsmotion' support in this wave... out of scope here" — but this project's own original Antragsverwaltung requirement named Änderungsanträge explicitly. Nothing revisited that scope cut across V0.2.3–V0.8.5 until this wave.

`amendsMotionId` (new, nullable, genuinely self-referential `motion.amends_motion_id` FK — no `.references()` at the Exposed layer, mirroring `document_folder.parent_folder_id`'s and `member.reviewed_by`'s established precedent) attaches an amendment to its target main Motion and reuses the identical `MotionStatus` lifecycle end to end (submit/review/schedule/resolve/withdraw) rather than a second state machine — the same committee-leadership due diligence genuinely applies to an amendment as to any other motion, and reuse keeps this Standard-CRUD-artig rather than a Robert's-Rules-of-Order engine. `submitMotion` validates the target on the way in: it must exist, must not itself already be an amendment (no amendments-of-amendments), must share the amendment's own `targetCommitteeId`, and must still be in a non-terminal status. `scheduleMotion` now enforces, server-side, that an amendment lands on the EXACT SAME Meeting *and* AgendaItem as its target main motion (reusing the target's own AgendaItem row rather than creating a second one — `position` is ignored for an amendment) — voting on an amendment separately from its own motion's meeting/agenda point makes no procedural sense, and this is checked, not merely trusted from the caller.

`resolveMotion` and `closeVote` (the Meritokratische-Vote/Vickrey finalization path) both reject resolving a main motion with a real `ConflictException` while any of its amendments is still SUBMITTED/REVIEWED/SCHEDULED/POSTPONED. Adoption is full-text replacement: an amendment resolved ADOPTED copies its own text into the main motion's new `currentText` column; the main motion's own later resolution copies `MotionDto.effectiveText` (`currentText ?: text`), not the immutable original `text`. `text` itself is never mutated after submission, so every existing read path that already used it (protocol drafts, agenda-item titles, `ElectionService`/`SystemicConsensusService`) keeps seeing the as-submitted record without needing to know about amendments.

**Soundness extension beyond the two primary paths, disclosed as a deliberate addition:** `ElectionService.tally` and `SystemicConsensusService.evaluate` (BINDING branch) are two further paths capable of transitioning a scheduled Motion to a terminal status — leaving them unguarded would silently bypass the same ordering invariant `resolveMotion`/`closeVote` enforce, even though amending an Election's or SystemicConsensus's underlying Motion has no real procedural meaning. Both now call the same `requireNoPendingAmendments` guard and use `MotionDto.effectiveText` for their resulting `Resolution.text`, for full invariant soundness across every finalization path in this codebase, not just the two where an amendment naturally makes sense.

**Deliberate scope simplifications, disclosed not hidden:** (1) full-text replacement, no diff/patch mechanic — an amendment always proposes a complete replacement text, never a partial edit; (2) no competing-amendment ranking/precedence engine — real Geschäftsordnung procedure has non-trivial rules here (e.g. "weitestgehender Antrag zuerst") that are explicitly NOT implemented; any number of amendments may be independently scheduled/resolved in whatever order the board chooses, and each ADOPTED amendment overwrites `currentText` ("last-adopted-wins"). Both are named explicitly rather than silently under-built, mirroring V0.8.3's own precedent for scoping a complex real-world spec down to a documented subset.

Withdrawing a main motion, or rejecting it at the preliminary review stage, auto-cascades `WITHDRAWN` onto any still-pending amendment (procedurally moot once its target no longer exists) — POSTPONED deliberately does NOT cascade, since a postponed main motion is still alive and will be rescheduled, so its amendments correctly stay pending too. Withdrawing/rejecting an amendment itself needed no new code at all: `withdrawMotion`/`reviewMotion` already operate generically on any `motion` row, and `resolveMotion`/`closeVote`'s pre-existing REJECTED/POSTPONED branches already skip the `currentText` write, so a rejected amendment is a pure no-op against its target's working text.

Schema: `motion.amends_motion_id`/`motion.current_text` — `05-governance.kuml.kts`, hand-written `MotionTable`, and `V1__baseline.sql` updated together (ADR-0016 Option B), `GovernanceSchemaDriftTest` extended. No client UI change — Governance/Motions have no `lapis-client` screen at all (confirmed during the audit), matching this feature area's own existing RPC-only precedent; this wave is backend/RPC catch-up, not a new UI surface.

Testing: 7 new `GovernanceServiceTest` cases (20 → 27) covering amendment submission validation (not-found/amendment-of-amendment/committee-mismatch/already-terminal target), the same-meeting/agendaItem scheduling constraint, the resolve-ordering guard on both the Committee-Quorum (`resolveMotion`) and Vickrey (`closeVote`) paths, adoption's working-text update with the resulting `Resolution.text` verified end to end, sequential-amendment last-adopted-wins, rejected-amendment no-op, withdrawal/rejection cascade (including the POSTPONED non-cascade), and `listMotions(amendsMotionId=...)`. `GovernanceSchemaDriftTest` extended in place for `amends_motion_id`'s no-FK shape (23 tests, same count — extended assertions, no new test block needed). `ElectionServiceTest`/`SystemicConsensusServiceTest` re-run unchanged and green, confirming the new guard doesn't regress either path's existing behavior. `./gradlew clean check` green (1038 `lapis-server` tests total, 0 failures), ktlint clean across all three modules.

**Politician Guest Rating (V0.8.5) — closes the V0.6.4 scope cut.** V0.6.4 (Politiker-Profile und Politiker-Ranking) shipped an explicitly documented, product-owner-signed-off scope cut: the concept's three-way member/guest/combined rating was reduced to member-only, "for as long as no operational Gast identity model exists in this codebase" (see `20-politician.kuml.kts`'s own file header). V0.8.2's OIDC guest-identity federation closed that condition months ago — every federated OIDC guest is a real `Member(status = GAST)` row (`OidcGuestMemberStore`, `CurrentMember.isGuest`) — but nothing revisited the politician-rating scope cut until now. This wave reopens the two-basket mechanic.

`castRating`/`retractRating` (`PoliticianService`) now accept a GAST-status caller too, via a new `requireActiveOrGuestMembership` gate (`MembershipGuards.kt`) that allows AKTIV *and* GAST specifically — ANTRAG/AUSGETRETEN/ABGELEHNT remain excluded exactly as `requireActiveMembership` always excluded them (explicit negative tests for all three added, not just "GAST works"). `politician_reaction` gains `rater_type` (`MEMBER`/`GAST`, frozen at cast time from `CurrentMember.isGuest`, re-frozen on every recast rather than assumed stable) so member-cast and guest-cast reactions can be aggregated separately. `PoliticianProfileDto`/`PoliticianWeightSnapshotDto` gain `guestTrustWeight`/`guestLikeCount`/`guestDislikeCount`/`combinedTrustWeight` alongside the pre-existing `member*` fields. `getTopPoliticians` now sorts its Top-6 by `combinedTrustWeight` (member + guest), per the concept's explicit "Top-6... die Repräsentanten mit dem höchsten aktuellen Gesamt-Vertrauensgewicht (Mitglieder + Gäste zusammengefasst)" — verified with a dedicated test engineered so the ordering actually flips versus the old member-only sort key, not just "additive fields present." `listPoliticians`/`getPoliticianProfile` expose member/guest/combined weights separately so a client can build the concept's separate member-only/guest-only ranking views. `revokePoliticianStatus`'s existing whole-row `deleteWhere`s on `politician_reaction`/`politician_weight_snapshot` already wipe member AND guest reactions plus every persisted `member_*`/`guest_*`/`combined_*` snapshot column together — a direct, tested consequence of this domain's single-table (not per-kind-table) schema, matching the concept's explicit "Bewertungsstatistik wird gelöscht: ... Vertrauensgewichte (Mitglieder, Gäste, Gesamt) verschwinden vollständig." Rating remains free (no LTR cost) for both members and guests, unchanged.

**The central open design question, resolved and disclosed, not silently shipped as if settled: guest weighting is deliberately NOT LTR-weighted.** The member-side pool mechanic (`PoliticianTrustWeightCalculator.computeMemberTrustWeights`) apportions a shared pool of raters' real LTR-ledger balances across politicians by basket ratio (`LargestRemainderApportionment`) — but a guest structurally cannot hold LTR yet; no guest-earning mechanism exists anywhere in this codebase (V0.8.2's own CHANGELOG entry says so explicitly, and nothing since has closed that gap). A literal port of the member-side mechanic to guests would therefore always compute a guest weight of exactly `0` for every politician, regardless of how many guests voted — a feature that looks built but never produces a real number. Instead, the new `computeGuestTrustWeights` computes `guestTrustWeight = max(0, guestLikeCount − guestDislikeCount)` — a plain, unweighted vote count, the identical shape `17-crowdfunding.kuml.kts`'s Verteilungs-Korb basket already establishes for its own completely-unweighted democratic vote. This is explicit, disclosed, interim-by-design — documented in `PoliticianTrustWeightCalculator`/`PoliticianProfileDto` KDoc and here, not presented as the final intended mechanic. The alternative considered and rejected — wrapping a synthetic "1 credit per distinct guest" pool in `LargestRemainderApportionment` to reuse the member-side machinery verbatim — was rejected because that apportionment machinery exists specifically to protect a real, conserved-resource invariant (`Σ result == pool exactly`) that would be meaningless for a fictitious guest pool, and because it would not even equal a plain per-politician vote count once any guest rates more than one politician (a shared pool counts a distinct rater's contribution once, not once per vote). The forward path once real guest LTR-earning ships: swap `computeGuestTrustWeights`'s body for a second call into the untouched `computeMemberTrustWeights`, fed real guest balances through the same `LtrBalanceProvider`-style seam — a one-function swap, not a rewrite. `combinedTrustWeight` is the literal sum `memberTrustWeight + guestTrustWeight`; because the two addends are not commensurable units (LTR wealth share vs. raw vote count), this is documented as a literal sum, not presented as a normalized "fair" blend.

**Known limitation carried forward, flagged not fixed:** guest identities are cheap to mint — V0.8.2's Dynamic Client Registration is fully open/admission-free, and V0.8.3's Trust Anchors are explicitly "UX comfort, not a security mechanism" and do not gate guest login — so this wave's unweighted, unbounded-supply guest vote count is Sybil-vulnerable: an operator of their own OIDC home server can mint arbitrarily many guest identities to inflate or deflate any politician's `guestTrustWeight` (and therefore `combinedTrustWeight`). This is a pre-existing gap in the federation trust model, not introduced by this wave, but this is the first wave to attach non-zero product-visible weight to it. Flagged explicitly for product-owner sign-off, the same treatment the original V0.6.4 member-only scope cut received, rather than silently shipped as a settled, safe mechanic.

Schema: `politician_reaction.rater_type` (`PoliticianRaterType` enum, `MEMBER`/`GAST`), `politician_weight_snapshot.guest_trust_weight`/`guest_like_count`/`guest_dislike_count`/`combined_trust_weight` — hand-written Exposed `Table` objects and `V1__baseline.sql` updated alongside `20-politician.kuml.kts` (ADR-0016 Option B), `PoliticianSchemaDriftTest` extended for the new enum and columns. No client UI change — `lapis-client` has no Politician screen at all (V0.7.3's UI wave scope was explicitly limited to "core domains"), matching V0.6.4's own precedent of RPC/backend-only delivery; this wave is backend/RPC catch-up for the concept's three-way metric, not a new UI surface.

Testing: 14 new `lapis-server` tests in `PoliticianServiceTest` (27 → 41: GAST cast/retract/recast positive path; explicit negative tests for ANTRAG/AUSGETRETEN/ABGELEHNT on both `castRating` and `retractRating`; member/guest basket isolation in both directions; combined-weight arithmetic; Top-N ordering engineered to flip under the new combined sort key; revocation wipes member+guest reactions and every snapshot column; a real two-thread concurrency test racing a GAST `castRating` against `revokePoliticianStatus`, mirroring the pre-existing member-side race test), 6 new pure-unit tests in `PoliticianTrustWeightCalculatorTest` (6 → 12: `computeGuestTrustWeights` empty/basic/floor-at-zero/multi-profile-independence/empty-reaction-list cases, plus one explicit regression test confirming `computeMemberTrustWeights`'s own formula is untouched by this wave), `PoliticianSchemaDriftTest` extended for `rater_type` and the four new snapshot columns (4 tests, unchanged count, extended assertions). `./gradlew clean check` green (1031 `lapis-server` tests total, 0 failures), ktlint clean across all three modules.

**Guest Badge (V0.8.4) — closes the V0.8 Federation arc's originally-planned four sub-waves (V0.8.1 server-to-server federation, V0.8.2 OIDC guest-identity federation, V0.8.3 Trust-Anchor-Governance, V0.8.4 this wave).** The low-risk, pure-frontend wave the project's own wave table flagged it as: no new backend mechanism, just surfacing a federated OIDC guest's presence in the existing V0.7.3 KVision client. A visual, WCAG-AA-verified indicator — a violet (`#A855F7`) circular badge with a wanderer/hiker glyph, paired with a "(Gast)" text label so color is never the sole channel (WCAG 1.4.1) — replaces the ordinary "{displayName} ({role})" navbar identity display with "[badge] {displayName} (Gast)" specifically for a guest session; a real local member's navbar display is completely unchanged. A hover/focus/tap-triggered popover ("Gast von {homeserverUrl}" / "Angemeldet über den OIDC-Heimserver {homeserverUrl}.") and an `aria-label` on the badge itself both surface the home server, so screen-reader users get the information without needing to trigger the popover at all. Design (icon choice — a passport/visa-stamp icon was considered and rejected for poor legibility at badge scale — color `#A855F7`/`#FFFFFF`, 18×18px size, and the hover+focus+tap interaction model) was decided through this project's mandatory UI/UX-Design-Team review (root `CLAUDE.md` "UI/UX-Design-Team" — Kare, Tesler, Atkinson, Kay, Norman, Raskin, Rams, Ive, Forstall, Duarte, Zhuo, with Jobs' final call), actually convened for this feature before implementation started; this wave implements that fixed spec rather than redesigning it. (A round-2 review pass incorrectly claimed this review hadn't happened, reasoning only from the vault's separate "Offene Fragen Federation" open-questions note, which predates and doesn't yet reflect this review's outcome — corrected here and in `GuestBadge.kt`'s KDoc.)

`SessionInfoDto` (`lapis-shared`) gains `isGuest: Boolean` and `homeserverUrl: String?` (both defaulted, purely additive). `AuthService.getSessionInfo()` populates `isGuest` from the same `CurrentMember.isGuest` V0.8.2 already resolves, and `homeserverUrl` via a `leftJoin` onto `OidcGuestProfileTable` — `null` for a non-guest by construction of the join (no matching row), no separate branch needed. Built as a reusable `GuestBadge` KVision component (`lapis-client/.../GuestBadge.kt`) with the badge/glyph colors as named constants (`GuestBadgeColors`) rather than a hardcoded hex sprinkled at the one call site — this exact pattern is expected to reappear once a real content/Timeline wave eventually ships avatars/posts.

**Scope boundary, explicit**: the navbar identity display is the ONLY real, shipped call site today — this codebase has no "Timeline"/"Post" content entity yet (confirmed across three prior federation waves, V0.8.1–V0.8.3), so nothing in this wave claims to mark guest-authored content; that is deferred until such an entity exists. The design spec's optional "Profil auf Heimserver ansehen →" popover link is likewise omitted this wave rather than invented — no home-profile-URL field exists anywhere in this codebase (`OidcGuestClaims`, `OidcGuestProfileTable`) to back it.

### Security

- `homeserverUrl` is remote-controlled data — it ultimately originates from the guest's home OIDC server via V0.8.2's federation flow, a server this instance does not control. It reaches the DOM only through KVision `PopoverOptions.title`/`content` (Bootstrap Popover's own `html: false` default — `rich` is never set for this data) and through a plain `aria-label` attribute value — both are text sinks, never raw-HTML string interpolation. The only `rich = true` (raw-HTML) content path in `GuestBadge.kt` is the wanderer-glyph SVG, a compile-time constant that never incorporates `homeserverUrl` or any other request-derived value.
- No new personal-data disclosure surface: `homeserverUrl` already exists in `OidcGuestProfileTable` (covered by the existing `OidcGuestPersonalData` DSGVO contributor since V0.8.2) — this wave only returns it to the guest's own session ("whoami"), which the guest already knows.

### Verification

`./gradlew clean check` — 1011 `lapis-server` tests (2 new in `AuthServiceTest`: a real member has `isGuest=false`/`homeserverUrl=null`, a `GAST` member created via the real `OidcGuestMemberStore` has `isGuest=true` and the seeded `homeserverUrl` surfaced verbatim), 22 `lapis-client` `jsTest` tests (3 new in `GuestBadgeTest`: the pure popover-title/body/aria-label text functions factored out of the `GuestBadge` component), 0 failures, ktlint clean across all three modules. No DOM/rendering test exists for the badge itself (whether the popover actually fires on hover/focus/tap, whether the badge visually replaces the role text, whether `pointer-events: auto` genuinely restores interactivity under Bootstrap's `.disabled` ancestor) — no such test harness exists in this module, same precedent `ValidationTest` already established in V0.7.3; a Karma/DOM-interaction harness for one badge would be disproportionate scope for this wave. Manual QA substitute: log in as a seeded guest, hover/tab-focus/tap the badge, confirm the popover text, and confirm a screen reader (or the DOM inspector's accessibility tree) reports the `aria-label` without needing to trigger the popover.

**Trust-Anchor-Governance (V0.8.3)** — a deliberately-scoped, **single-level CORE subset** of [OpenID Federation 1.0 (RFC 9678)](https://openid.net/specs/openid-federation-1_0.html), layered on top of V0.8.2's OIDC guest-identity federation. **CRITICAL FRAMING, unchanged from the concept**: a Trust Anchor is UX comfort, *not* a security mechanism — it never gates federation itself, guest login, or Dynamic Client Registration, all of which remain exactly as open as V0.8.2 left them. Its only effect is a positive, purely-informational signal.

**Deliberate scope cut vs. the full spec** — single-level only (a Trust Anchor vouches DIRECTLY for its pool members, no nested Trust-Anchor → Intermediate → Leaf authority chains), no Trust Marks, no Metadata Policy Language. Every server can independently (a) opt in to acting as its own Trust Anchor by publishing a self-signed Entity Configuration (`GET /.well-known/openid-federation`) and signed, short-lived Subordinate Statements about the home servers in its own ADMIN-managed pool (`GET /federation/trust-anchor/fetch?sub=<uri>`), and (b) configure which OTHER Trust Anchor entity URIs it chooses to trust, and resolve a one-hop trust chain against them as an informational signal (`ITrustAnchorService.resolveTrustChain`).

**Key lifecycle — the concept's own explicitly-flagged open question, now answered with a real, working mechanism.** `trust_anchor_signing_key` is rotation-capable (unlike the genesis-singleton `federation_actor_key`/`oidc_signing_key`): exactly one `ACTIVE` key signs everything new; `rotateSigningKey()` retires the current key to `RETIRED` (still published in this server's own `jwks`, so already-issued, still-unexpired statements keep verifying — a real grace period) and activates a fresh one. `revokeSigningKey(kid)` is the ADMIN-triggered compromise-response path: the key is marked `REVOKED` and immediately excluded from the published `jwks`; if it was the `ACTIVE` key, a replacement is minted and activated in the same operation so the anchor never goes without a signing key. **Why revocation needs more than expiry alone** (verified, not assumed): removing a pool member is fully handled by expiry, since Subordinate Statements are generated fresh on every fetch — but a compromised *key* could still have signed an already-issued, not-yet-expired statement that would keep verifying under expiry-only revocation. The real fix: every verifier (including this server's own resolver toward other anchors) re-fetches the anchor's `jwks` FRESH at verification time rather than caching it, so a revoked key's public key disappearing from that set immediately invalidates anything signed by it, past or future.

New tables: `trust_anchor_signing_key` (rotation-capable, `ACTIVE`/`RETIRED`/`REVOKED`, first row provisioned idempotently at boot like `federation_actor_key`/`oidc_signing_key`, registered in `OrganizationRestoreService.SEEDED_SINGLETON_ROWS`), `trust_anchor_pool_member` (this server's own vouched-for home-server pool — opt-in is expressed structurally by a non-empty table, no separate "role enabled" flag), `trusted_external_anchor` (the set of external anchors this server chooses to trust), `trust_anchor_event` (append-only, non-hash-chained governance log — mirrors `federation_relationship_event`'s shape, deliberately not `audit_log_entry`, whose `AuditEntityType` is bounded to GoBD financial/legal scope). No table in this domain has any FK to `member` — Trust-Anchor governance is entirely server-to-server/organization-level, same as `24-federation.kuml.kts`.

**JWT signing reuses `OidcJwt.sign`/`OidcJwt.verifySignature` verbatim** — no new JOSE/JWT code was written this wave. The one-hop resolver (`TrustAnchorResolver`) is split into a thin network-fetching shell and a pure, network-free cryptographic core (`TrustAnchorChainVerification`), the latter directly exercised with real, locally-generated RSA-2048 keypairs and hand-crafted (including deliberately tampered) JWTs. Every outbound fetch reuses `requireSafeFederationUrl`/`federationHttpClient`/`readCappedFederationBodyOrNull` from V0.8.1 UNCHANGED — no new SSRF-guard code.

### Security

- A forged/tampered Subordinate Statement or Entity Configuration signature (single-byte flip, payload substitution, wrong signing key) is rejected.
- An expired or not-yet-valid statement/configuration is rejected.
- A statement signed by a key that is not the claimed anchor's actual current key (unknown `kid`, or a `kid` present in the anchor's `jwks` but signed with different key material) is rejected.
- Key rollover genuinely allows grace-period verification of a `RETIRED` key while a truly `REVOKED` key never verifies again, even for previously-issued, still-unexpired statements — exercised both as pure unit tests (`TrustAnchorChainVerificationTest`) and end to end through the real routes (`TrustAnchorRoutesTest`).
- `addPoolMember`/`addTrustedAnchor`/`resolveTrustChain` all reject a malformed/non-HTTPS/private-range URI via the reused SSRF guard before any row is written or any fetch attempted.
- ADMIN-only throughout (`ITrustAnchorService`), same tier as `IFederationService`.

### Scope boundary (deliberate, not silently omitted)

Single-level trust chains only — no intermediate/subordinate authority nesting. No Trust Marks, no Metadata Policy Language. Trust-chain resolution is wired as an informational signal only; using it to change guest-login UI/behavior (e.g. a trust indicator) is explicitly deferred to V0.8.4, which owns UI. No automatic purge of `RETIRED` keys after a grace period — they remain published until an ADMIN explicitly revokes them (flagged, not silently decided). Subordinate Statements are generated fresh on every fetch rather than pre-issued and periodically reissued by a background job — deliberately simpler, and at least as fresh.

### Verification

`./gradlew clean check` — 1009 `lapis-server` tests (43 new: `TrustAnchorChainVerificationTest` (17, pure cryptographic core, real RSA-2048 keypairs, every adversarial case named above), `TrustAnchorServiceTest` (14, RPC-layer role enforcement, key lifecycle, pool/trusted-anchor CRUD, event log, `resolveTrustChain`), `TrustAnchorRoutesTest` (7, the real `/.well-known/openid-federation` + `/federation/trust-anchor/fetch` routes end to end, including the key-rollover/revocation round trip against freshly-fetched `jwks`), `TrustAnchorSchemaDriftTest` (5, kUML model vs. real migrated schema vs. hand-written Exposed tables), plus the updated `DomainModelMergerTest`), 0 failures, ktlint clean.

### Fixed

**Closes the guest/`PUBLIC_MEMBERS` document-access gap V0.8.2 itself disclosed.** `canAccessDocumentAtLevel(PUBLIC_MEMBERS)` was role-only and returned `true` for ANY resolved `CurrentMember` — and a federated OIDC guest (V0.8.2) always resolves with `role = MEMBER` (see `OidcGuestMemberStore`), so a guest session could read `PUBLIC_MEMBERS`-tier documents (statutes, meeting minutes, board correspondence) exactly like a real local member. V0.8.2's own `CurrentMember` KDoc and CHANGELOG entry ("Scope boundary") flagged this explicitly at the time as a deliberate, not-yet-decided product-scope question rather than silently shipping it as settled behavior.

`PUBLIC_MEMBERS` means "visible to members of *this* organization" — a fundamentally different, internal-document-storage content domain from the Timeline (social posts/reactions) the project's own Gastzugang concept describes for guests ("Inhalte konsumieren, kommentieren und ... eigene Beiträge sichtbar machen"); that concept is explicit that anything beyond baseline Timeline read/comment access is a local-server-policy decision, not an automatic grant, and a guest — while technically holding `role = MEMBER` as an implementation detail of how V0.8.2 represents a guest identity — is not actually a member of the visited organization. `canAccessDocumentAtLevel(PUBLIC_MEMBERS)` now additionally requires `CurrentMember.isGuest == false`. `BOARD_ONLY`/`ADMIN_ONLY` needed no change and were verified unaffected: a guest's `Account.role` is always `MEMBER` (never `BOARD`/`ADMIN`), and no write path anywhere in this codebase elevates a guest's role after creation (`OidcGuestMemberStore` only ever inserts `role = MEMBER`, and every other `AccountTable`-role-write call site either creates an unrelated brand-new `AKTIV` member or writes an unrelated `CommitteeMembership`/`ElectionOption` role, not `Account.role`) — `isPrivileged`/`role == ADMIN` were therefore already structurally unreachable by a guest before this fix, confirmed rather than assumed.

Applies uniformly everywhere document access is gated — `DocumentService.listDocuments`/`listVersions` and the `/api/documents/{id}/download` HTTP route — since both call sites share the one `canAccessDocumentAtLevel` function; no call site needed its own separate fix. `CurrentMember`'s KDoc "Known gap, flagged not silently fixed" paragraph is updated to describe this closed state.

### Verification

`./gradlew clean check` — 966 `lapis-server` tests (6 new: 4 pure unit tests directly table-driving `canAccessDocumentAtLevel`/`isPrivileged` across every `DocumentAccessLevel` × role × `isGuest` combination, 1 `DocumentService`-layer integration test proving a guest is filtered out of `listDocuments`/rejected by `listVersions` on a `PUBLIC_MEMBERS` document while a real member's access is unchanged, 1 HTTP-route-level test proving the same on the real `/api/documents/{id}/download` route end to end), 0 failures, ktlint clean.

## [0.8.2] — 2026-07-27

### Added

**OIDC guest-identity federation** — individual-MEMBER identity federation (V0.8.2), letting a member of "home server A" log into "visited server B" using their home-server identity via **OpenID Connect Authorization Code Flow + PKCE (RFC 7636)**. A completely separate mechanism from V0.8.1's server-to-server *content* federation (`FederationRelationship`/HTTP Signatures) — home server = OIDC Issuer/Identity Provider, visited server = OAuth client/Relying Party, guest = Resource Owner. This server acts as **both** Issuer (for its own members going out as guests elsewhere) **and** Relying Party (accepting guests from other Lapis Cloud instances), with **Dynamic Client Registration (RFC 7591)** as the open-federation default — no trust-anchor pool yet (that's V0.8.3).

New public endpoints: `GET /.well-known/openid-configuration`, `GET /federation/oidc/jwks`, `GET /federation/oidc/authorize` + `POST /federation/oidc/authorize/consent` (Authorization Code + PKCE, redirects an unauthenticated visitor to the existing V0.7.1 login page), `POST /federation/oidc/token` (code exchange + refresh, with rotation), `POST /federation/oidc/register` (DCR, rate-limited, HTTPS-only redirect/backchannel URIs), `GET`/`POST /federation/oidc/rp/login` ("log in with your home server" — plain-domain input, WebFinger discovery deliberately deferred), `GET /federation/oidc/rp/callback` (code exchange + JWKS-verified ID token, mints a local guest session), `POST /federation/oidc/backchannel-logout` (inbound Back-Channel Logout receiver). Every outbound fetch (discovery, JWKS, token, registration, our own outbound Logout Token delivery) reuses V0.8.1's `requireSafeFederationUrl`/`federationHttpClient` SSRF-hardening verbatim — no new SSRF-guard code was written this wave, and its documented DNS-rebinding TOCTOU gap is inherited unchanged, not re-litigated.

**JWT/JOSE via `com.nimbusds:nimbus-jose-jwt`, a deliberate departure from V0.8.1's hand-rolled HTTP-Signatures posture.** HTTP Signatures (draft-cavage) is a narrow, fixed, single-algorithm scheme with no attacker-exposed algorithm negotiation; JOSE/JWT is the opposite shape — the `alg` header is attacker-controlled and is the root cause of the format's entire multi-year CVE history (`alg:none` bypass, RS256→HS256 confusion). Nimbus is Apache-2.0, pure JVM, zero transitive deps, and is the de-facto-standard JVM JOSE library. `OidcJwt.verifySignature` hard-pins `RS256` at the *code* level — the token's own `alg` header is read only to decide "is this RS256 at all", never to select which verifier runs, and exactly one `RSASSAVerifier` (constructed from the known public key) is the only verifier this object ever builds.

**Guest identity = a real `Member` row with `status = GAST`, paired with a real `Account` row.** `MemberStatus.GAST` has existed since V0.1 for exactly this purpose (its own KDoc says so), and `account.oidc_subject` was reserved in V0.7.1 with the explicit stated intent that "an OIDC path can later mint sessions via the same `SessionStore`" — this wave completes both. A guest is created once per federated identity (`account.oidc_issuer` + `account.oidc_subject`, jointly unique, globally unique per OIDC spec) and reused on repeat visits, found by that composite key before ever inserting. `member.email` (`UNIQUE NOT NULL`, no `email` claim in the concept's minimum ID-token claim set) is a deterministic synthetic value — `guest+sha256(iss|sub)[..32]@federation.invalid` (`.invalid` is the RFC 2606 reserved, guaranteed-non-deliverable TLD). Reusing the real `Member`/`Account`/`Session` shape means every existing status-checking gate (`requireActiveMembership` and friends) already excludes `GAST` structurally — **voting is never a scope, full stop**, enforced by that pre-existing exclusion, not by an OIDC scope grant/deny, so there is no scope string a malicious/misconfigured home server could even attempt to smuggle a vote-weight claim through. `CurrentMember` gains a new `isGuest: Boolean` field (set for free off the same join `SessionStore.resolve` already performs) as a positive, greppable signal for future call sites — flagged, not silently fixed, that role-only gates (`isPrivileged`, `canAccessDocumentAtLevel(PUBLIC_MEMBERS)`) do not yet consult it.

Scopes: `openid` (always), `profile_basic` (always), `membership_status` (optional), `pzb:read` (always), `pzb:comment`/`pzb:post_paid` (recognized as scope literals, granted per the home server's own token response, but **not wired into any write path this wave** — see scope boundary below).

New tables: `oidc_signing_key` (singleton, this server's own JWS signing keypair — a *separate* RSA-2048 key from V0.8.1's federation Actor key, different cryptographic purpose, same "genuinely round-trippable secret, DB-is-the-trust-boundary" posture as `federation_actor_key`), `oidc_client_registration` + `oidc_client_redirect_uri` (Issuer side: RPs registered against us), `oidc_authorization_code` (single-use, PKCE-bound, 60s TTL, atomically consumed), `oidc_issued_token` (access+refresh pairs, refresh rotation), `oidc_home_server_registration` (RP side: our own DCR registration against a guest's claimed home server — the one *other* genuinely round-trippable secret this wave adds, `client_secret`, same posture), `oidc_rp_login_attempt` (pre-auth PKCE/state/nonce scratch state, 10min TTL, no member FK), `oidc_guest_profile` (guest-specific profile fields, covered by a new `OidcGuestPersonalData` DSGVO contributor), `oidc_guest_login_event` (forensic, non-hash-chained login/logout audit trail — deliberately **not** `audit_log_entry`, whose `AuditEntityType` literal set is explicitly bounded to GoBD financial/legal scope, same reasoning V0.8.1's own `federation_inbox_delivery_log` already established; `member_id` on this one table is deliberately a plain, non-FK column, pinned by a dedicated schema-drift regression test).

### Security

- PKCE `S256` only (this codebase never implements the `plain` method); `redirect_uri` validated by **exact** string match, both at `/authorize` (against the client's registered set) and at `/token` (against the specific value stored on the authorization code itself) — defeats a "register two URIs, redirect to one, redeem against the other" mix-up on top of the baseline open-redirect defense.
- `state` (RP-side CSRF defense on `/rp/callback`) and `nonce` (ID-token replay defense across login attempts) are both unguessable, server-generated, single-use, and validated exactly once.
- Back-Channel Logout receiver rejects an unregistered `iss` via a DB lookup **before** attempting any JWKS fetch — closes the "make us SSRF-fetch an arbitrary attacker-controlled JWKS URL" path before any network call happens, defense in depth on top of `requireSafeFederationUrl`. Logout Tokens are structurally distinguished from ID Tokens (a spec-mandated `events` claim marker) and must **never** carry a `nonce` claim (reserved for ID Tokens; presence is treated as a smuggling attempt, not ignored).
- DCR registration (`/federation/oidc/register`) requires HTTPS for every `redirect_uri`/`backchannel_logout_uri` and is rate-limited per caller IP.
- Client secrets (ours, issued to RPs) are stored SHA-256-hashed only, compared via `MessageDigest.isEqual`, never round-tripped.

### Scope boundary (deliberate, not silently omitted)

This wave builds the OIDC Issuer + Relying Party + the guest identity/session model needed to represent "a logged-in guest" server-side. It does **NOT** build: real LTR-earning-as-a-guest mechanics (`pzb:comment`/`pzb:post_paid` are recognized scope literals with no wiring into the LTR ledger or any posting/reaction write path yet), the guest timeline badge/UI (V0.8.4, separate), or OpenID-Federation/Trust-Anchor governance (V0.8.3, separate — DCR is this wave's only, fully open, admission-free client-registration mechanism). Also deferred, flagged rather than silently decided either way: whether `PUBLIC_MEMBERS`-level documents should be scoped away from guests (currently readable, since `canAccessDocumentAtLevel` is role-only and a guest always has `role = MEMBER`) — a product-scope decision for a later wave, not an oversight; RFC 7592 client-configuration management; JWKS caching (fetched fresh on every verification this wave); outbound Back-Channel Logout retry queueing (best-effort, awaited inline bounded by the federation HTTP client's own timeouts, no background-job infrastructure exists in this codebase); signing-key rotation (single active key, JWKS already returns an array so a second key is additive later).

### Known limitations (tracked for later versions)

- No LTR-economy wiring for guest actions — see scope boundary above.
- No guest timeline badge/UI — V0.8.4.
- No Trust-Anchor/OpenID-Federation governance — open DCR admission only, V0.8.3.
- The RP-side "log in with your home server" entry point is a single, server-rendered, non-SPA page reachable via one new link on the existing login screen — no dedicated multi-step SPA flow this wave.
- Outbound Back-Channel Logout notification is best-effort with no retry queue, and is awaited inline (bounded by HTTP-client timeouts) rather than dispatched onto a background coroutine scope, since no such scope exists in this codebase yet.

### Verification

`./gradlew clean check` — 960 `lapis-server` tests (53 new OIDC-specific tests across 5 new test classes: `OidcJwtTest` (17), `OidcClientRegistrarTest` (2), `OidcRoutesTest` (18), `OidcGuestSessionTest` (5), `OidcGuestFederationSchemaDriftTest` (11), plus the updated `DomainModelMergerTest`), 0 failures, ktlint clean. `OidcJwtTest` exercises every adversarial case against real, locally-generated RSA-2048 keypairs (`alg:none`, RS256→HS256 confusion using the real RSA public key as an HMAC secret, tampered payload/signature, expired/not-yet-valid, `iss`/`aud`/`nonce` substitution and replay, Logout-Token-specific structural checks) — no mocks. `OidcRoutesTest` drives the full Issuer-side Authorization Code + PKCE flow end to end through the real, fully-wired `Application.module()` (DCR → authorize → consent → token → JWKS-verified ID token → refresh rotation), plus the PKCE-tamper/single-use-code/redirect-mismatch/expired-code/invalid-client negative paths and the SSRF-guard-reuse/reject-before-fetch paths that don't require real network egress (this sandbox has no general internet egress, same documented limitation V0.8.1's own `FederationRoutesTest` already states for its outbound-fetch happy paths).

## [0.8.1] — 2026-07-27

### Added

**Federation protocol Grundgerüst** — the foundational, content-agnostic infrastructure for server-to-server federation between Lapis Cloud instances (V0.8), using a deliberate **hybrid protocol**: an ActivityPub-compatible core (Actor documents, inbox/outbox, `Follow`/`Accept`/`Reject`/`Undo` handshake, HTTP Signature delivery) plus a namespaced `lapis:` JSON-LD extension vocabulary for this project's own differentiator (LTR amounts, vote weights, pseudonym-reputation-anchors). Rationale: a pure ActivityPub approach has no native vocabulary for Meritokratie-specific data, while a pure custom protocol would forgo real Fediverse tooling/interoperability — a strategic goal in its own right (broader reach amplifies adoption of the underlying libertarian structural mechanics by other organizations federating or forking, "Ideologie-Übernahme durch Reichweite"). Mirrors the sibling identity decision (OIDC core + custom Trust-Anchor governance) already used elsewhere in this project.

Each server instance federates as a single ActivityPub Actor representing the *organization* itself (this codebase is single-tenant — one `organization_settings` row per deployment), not individual members, with an RSA-2048 keypair used for **HTTP Signatures (draft-cavage scheme)** — chosen deliberately over the newer RFC 9421 because essentially all deployed Fediverse software (Mastodon, Pleroma/Akkoma, Misskey/Firefish) still speaks draft-cavage as of this wave, and real interoperability with that software is this wave's explicit strategic goal; RFC 9421 support can be added additively later if adoption shifts (the signing-string construction is already isolated so this is a pure addition, not a rewrite). Signed headers: `(request-target) host date digest`, algorithm `rsa-sha256`.

New public endpoints: `GET /federation/actor` (Actor document, JSON-LD `application/activity+json`), `POST /federation/inbox` (signed Activity delivery from untrusted remote servers — HTTP-Signature-verified with a 5-minute freshness/replay window, rate-limited per source IP, payload-size- and JSON-nesting-depth-bounded, with signature verification happening *before* any JSON parsing), `GET /federation/outbox` (a minimal, capped `OrderedCollection` of outbound Activities). A new ADMIN-only RPC surface (`IFederationService`) manages the `Follow`/`Accept`/`Reject`/`Undo` relationship lifecycle between organizations — inbound `Follow` requires explicit ADMIN approval, deliberately no auto-accept — recorded in a dedicated, append-only event log (`federation_relationship_event`) alongside a full inbox-delivery audit trail (`federation_inbox_delivery_log`) for forensics on every request the public inbox receives, verified or not. Remote actor-key/document fetches reuse the price-oracle's SSRF-hardening *pattern* (HTTPS-only, no redirects, bounded timeouts/response size) but not its fixed-hostname allowlist mechanism, which cannot apply to inherently open-ended federation targets — instead resolving DNS and rejecting private/loopback/link-local/reserved IP ranges (a known residual DNS-rebinding TOCTOU gap between address-check and connection is documented, not silently accepted).

`federation_relationship.remote_actor_uri` carries a hard `UNIQUE` constraint (one row per remote actor for the server's lifetime) — re-establishing federation after a terminal (`REJECTED`/`UNDONE`) status therefore *updates* that same row back to `PENDING` rather than inserting a second one; the row's full history remains reconstructable via the still-append-only event log regardless of how many times its status cycles through terminal and back.

**Explicit scope boundary**: this wave builds the federation protocol layer only. No existing content type (crowdfunding projects, politician profiles, governance resolutions) is wired into outbound federation yet — which content federates first, and how, is a separate product-scope decision left to a later wave. The `lapis:` extension vocabulary is proven with a serialization round-trip test (a populated extension survives encode→decode byte-for-byte; a vanilla/non-Lapis-Cloud ActivityPub parser decodes the same JSON cleanly, ignoring the unknown block; an unused extension is entirely absent from the wire, not null-valued) but carries no real content type's data yet.

Also out of scope for V0.8.1 (separate, already-planned waves): OIDC guest access (V0.8.2, a different identity mechanism authenticating individual members, not server-to-server delivery), automatic inter-server Trust-Anchor governance (V0.8.3 — this wave's Follow handshake requires explicit ADMIN approval for every inbound relationship), and the guest timeline badge/UI (V0.8.4).

### Security

- New public, unauthenticated-until-signature-verified surface (`/federation/inbox`) — hardened with a dedicated per-IP rate limiter (checked before any body read), a hard request-body size cap enforced before JSON parsing, and a linear, non-recursive JSON-nesting-depth scan before typed decoding (even building a `JsonElement` tree is itself recursive and could otherwise overflow the stack on deep-but-small attacker input), on top of HTTP Signature verification and a replay guard.
- `federation_actor_key.private_key_pem` is this codebase's first genuinely round-trippable secret (every prior secret — password hashes, session tokens — is a one-way digest, never read back); stored as plaintext PEM, same DB-is-the-trust-boundary posture already applied to every other sensitive column in this schema (e.g. `organization_settings.bank_iban`), not a new exception. Included in the full-organization export/restore bundle at the same sensitivity tier as `account.password_hash`, since the restore mechanism exists for genuine organization secession and a migrating organization should keep its federation identity.

### Known limitations (tracked for later versions)

- No content type is actually federated yet — planned for a later V0.8.x wave once the product decision on which content type federates first is made.
- Inbound Follow requires manual ADMIN approval; no automatic inter-server trust pools yet — planned for V0.8.3 (Trust-Anchor governance).
- The remote-actor SSRF guard has a known DNS-rebinding TOCTOU gap between address-check and actual connection — full closure requires pinning the resolved IP for the connection itself.
- No key rotation for the local Actor's keypair yet.
- No delivery retry — a failed outbound POST (network error, remote unreachable) is logged but not retried/queued; no background-job infrastructure exists anywhere in this codebase yet.

### Verification

`./gradlew clean check` — 919 tests total (67 new federation-specific tests across 8 test classes: `HttpSignaturesTest`, `FederationHttpClientSsrfTest`, `FederationInboxRateLimiterTest`, `FederationRelationshipStateMachineTest`, `ActivityPubExtensionRoundTripTest`, `FederationSchemaDriftTest`, `FederationServiceTest`, `FederationRoutesTest`), 0 failures, ktlint clean.

## [0.7.4] — 2026-07-23

### Fixed

**RPC service exceptions are visible to `lapis-shared`'s KSP again, closing the JS deserialization crash V0.7.3 flagged and deferred.** The 7 `@RpcServiceException` subclasses (`UnauthenticatedException`, `ForbiddenException`, `WeakPasswordException`, `InvalidPasswordException`, `NotFoundException`, `ConflictException`, `BadRequestException`) moved from `lapis-server` (JVM-only) into a new `lapis-shared/.../rpc/ServiceExceptions.kt` (`commonMain`, compiled for both `jvm` and `js`). Kilua RPC's KSP processor only ever runs against `lapis-shared` (confirmed: only that module applies the `ksp`/`kilua.rpc` Gradle plugins) — with these classes living in the JVM-only module, the polymorphic serializers module KSP generates (`GeneratedRpcServiceExceptions.kt`) never registered them, so a JS client deserializing any RPC error response hit
`SerializationException: Serializer for subclass '<Name>' is not found in the polymorphic scope of 'AbstractServiceException'` instead of receiving a typed exception. `AbstractServiceException`/`@RpcServiceException` were already transitively resolvable from `lapis-shared`'s `commonMain` before this fix (`kilua-rpc-ktor`, already an `api` dependency there, depends on both `kilua-rpc-core`/`kilua-rpc-annotations` at the common/metadata level) — this was a straight move, not a redesign; every throw site, message, and authorization check is unchanged. ~60 call sites across `lapis-server` (main + test) needed only an import-path fix (most were same-package implicit references before the move); 9 `lapis-shared` KDoc mentions were upgraded from inert backticks to real `[ClassName]` doc links now that the types are genuinely visible from that module, and two stale fully-qualified references (`network.lapis.cloud.server.rpc.ConflictException`/`NotFoundException`, pre-dating this fix) were corrected in the process.

**`lapis-client`'s `guarded()` now catches these by type instead of string-matching `e.message`.** `AppState.kt`'s shared RPC-call wrapper previously matched on `message.contains("Missing, invalid, or expired session")` for session-expiry detection — fragile, and moot once the exception failed to deserialize at all. It now catches `UnauthenticatedException` directly for the login-redirect path. **Empirically discovered while verifying this fix** (booted the server with seeded demo data, drove the actual bug scenarios — an anonymous `getSessionInfo()` probe and a wrong-current-password `changePassword` — through a real browser, not just unit tests): Kilua RPC's polymorphic exception wire format only ever transmits the `AbstractServiceException` subclass discriminator, never the subclass's own `message` text — confirmed against the raw JSON-RPC response body (`exceptionJson` contains only `{"type":"..."}`) and the KSP-generated `registerRpcServiceExceptions()`. This is `kilua-rpc-core` 0.0.45's own protocol behavior, not something introduced by or fixable from this project's exception classes. Consequently `guarded()` now also catches each of the other 6 named exceptions individually and shows a static, type-appropriate German message (e.g. "Aktuelles Passwort ist falsch." for `InvalidPasswordException`) rather than the server's own crafted message text, which cannot survive the wire for a named subclass.

### Known limitations (tracked for later versions)

- Server-side exception **message text does not survive the wire** for any named `@RpcServiceException` subclass (only the type discriminator does) — this is `kilua-rpc-core` 0.0.45 protocol behavior. Concretely, `WeakPasswordException`'s three distinct server-side reasons (too short / too long / same as email) all collapse into one generic client-side message, since the client cannot distinguish which one fired without the original text. A future improvement would need either an upstream Kilua RPC change or a project-side convention for passing structured detail alongside the type (e.g. a dedicated DTO field on the relevant response types, or a documented error-code enum on each exception).

### Verification

`./gradlew clean check` (831 `lapis-server` tests, 2 `lapis-shared`/19 `lapis-client` `jsTest`, ktlint) — zero test-assertion changes, only import lines and the 7 class bodies moved. Additionally built the production client bundle and drove the two documented bug scenarios through a real, running server (`LAPIS_SEED_DEMO_DATA=true`) in a browser: confirmed via the raw network response and browser console that the client no longer crashes, and now shows a clean typed toast ("Aktuelles Passwort ist falsch.") instead of either a raw deserialization exception or a silent failure.

## [0.7.3] — 2026-07-23

### Added

**Basis-Mehrseiten-UI (V0.7.3)** — the third and last of V0.7's deploy-blockers: replaces `lapis-client`'s 232-line, two-file V0.1.5 tech demo (a single dashboard behind a raw `X-Member-Id` "acting as" member switcher) with a real, multi-screen KVision SPA that actually uses V0.7.1's session-cookie auth and V0.7.2's registration/admin-creation/exit for the first time. One file per screen, mirroring the flat, one-file-per-concern convention `lapis-server/.../rpc/` already uses for its services: **Login** (`LoginScreen.kt`, hand-written `fetch()` against `POST /api/auth/login` — deliberately not RPC, see `IAuthService` KDoc — with the server's own account-enumeration-hardened response text shown verbatim, never further differentiated client-side), **Registrierung** (`RegistrationScreen.kt`, the registrant must see and explicitly accept the current versioned+hashed Beitrittsvertrag before submitting, then lands on a clear "Antrag eingereicht, wird geprüft" pending state — never a dashboard, and never auto-logged-in, matching `registerApplication`'s account-enumeration-hardened `Unit`-always-return), **Dashboard** (`DashboardScreen.kt`, own session info, working logout, self-service Passwort-ändern, and a self-service Austritt gated behind a real two-step confirmation modal since it is irreversible from the member's perspective), **Mitgliederverwaltung** (`MemberAdministrationScreen.kt`, BOARD/ADMIN only — route-guarded so it is never even rendered for a plain MEMBER caller, not just hidden from navigation — pending-application approve/reject with a mandatory non-blank rejection reason, an AKTIV-member name/search directory, and direct member creation with escalated BOARD/TREASURER/ADMIN role options disabled in the UI for a BOARD-only caller rather than letting the submit round-trip and fail server-side), **Beitragsübersicht** (`ContributionsScreen.kt`, own summary for everyone; org-wide table for TREASURER/BOARD/ADMIN, matching `listContributions`'s own `isPrivileged || TREASURER` authorization; tier administration/period-generation TREASURER/ADMIN only; "als bezahlt markieren" TREASURER/ADMIN, "als erlassen markieren" BOARD/ADMIN only — TREASURER may pay but not waive, mirroring `markContributionWaived`'s own role check), and **Dokumentenablage** (`DocumentsScreen.kt`, folder/document/version browser against `IDocumentService`'s metadata RPC plus the dedicated upload/download HTTP routes file bytes travel over — upload sends the selected file's native bytes directly rather than round-tripping through `KFile`'s base64 `content` field, avoiding a ~33% size inflation for uploads up to the server's 25 MB cap). Governance/Buchhaltung/Compliance/LTR-Wirtschaft get no UI in this wave, exactly as scoped — they remain reachable only via RPC/API, same as before.

**Same-origin static serving replaces the "no CORS story" gap this wave would otherwise have hit.** `Application.kt` now serves the built client bundle at `/` via Ktor's `staticFiles` (`PartialContent`/`AutoHeadResponse` installed — both dependencies were already declared, unused until now), configurable via `LAPIS_CLIENT_DIST_ROOT`. Every RPC call already sends the `lapis_session` cookie automatically regardless (`credentials: "include"` is baked into Kilua RPC's own `CallAgent`, verified against the pinned `kilua-rpc-core-js` 0.0.45 artifact) — the actual gap was that nothing served the client from the same origin as the API at all, which would have made login itself impossible cross-origin with no CORS plugin installed anywhere. Routing is hash-based (`#/dashboard`, ...) specifically so the server never needs a SPA-fallback/catch-all route for deep links — every request the server ever sees is `/`, a static asset, an `/api/...` route, or an RPC POST path; the fragment never leaves the browser. The placeholder `get("/") { respondText(Greeting.message()) }` moved to `/api/ping` (still exercised by `ApplicationTest`) now that `/` serves the SPA shell.

**A real, testable BOARD-vs-ADMIN role-gating helper.** `selectableRolesFor(callerRole)` in `Validation.kt` returns only `MEMBER` for a BOARD/TREASURER/MEMBER caller and every role for ADMIN, mirroring `createMemberDirect`'s own server-side `ESCALATED_ROLES` check — the Mitgliederverwaltung screen disables the escalated options in the `Select` for a non-ADMIN caller rather than letting a BOARD account submit and get rejected. This mirrors, client-side, the exact `ADMIN_ONLY`-vs-`BOARD_ONLY` distinction `canAccessDocumentAtLevel` already makes server-side for `DocumentAccessLevel.ADMIN_ONLY`.

### Testing approach

This module had **no `jsTest` source set at all before this wave** (only stray `build/tmp` artifacts existed). Added `lapis-client/src/jsTest/kotlin/.../ValidationTest.kt` (19 cases), covering every pure, DOM-independent function with real branching logic: `Validation` (email-shape/password-length/password-equals-email/passwords-match checks — a UX nicety mirroring but never duplicating `PasswordPolicy`'s security logic), `selectableRolesFor` (the BOARD-vs-ADMIN escalated-role gating), and `isRouteAllowed` (the auth/role route-guard predicate extracted as a pure function in `Routing.kt` specifically so it is unit-testable without a router or DOM). These run under the Karma+ChromeHeadless `testTask` already configured in `lapis-client/build.gradle.kts` — genuinely zero new test infrastructure, just source files that were never added; `kotlin-test` is the only new test dependency. **Deliberately not in scope**: component-rendering/DOM tests and an E2E browser-automation framework (Playwright/Selenium/etc.) — this wave has no existing UI-test harness to extend, KVision has no lightweight first-party component-test utility to reach for, and bolting on a full E2E framework for a first UI wave would be disproportionate scope creep. The substitute is a documented manual QA pass against all four seeded demo roles (`LAPIS_SEED_DEMO_DATA=true ./gradlew :lapis-server:run`, all four accounts share `DevSeedData.DEMO_PASSWORD`): login/logout round-trip, wrong-password generic error, registration → pending state → board approve/reject cycle, Mitgliederverwaltung unreachable for a MEMBER caller, contribution generate/pay/waive role splits, document create/upload/download/delete, Austritt double-confirmation plus subsequent login rejection, and session-expiry redirect. `lapis-server`'s existing ~700 tests (905 `X-Member-Id` call sites) are unaffected by this wave — no RPC method or DTO changed — and `ApplicationTest` gained one adjusted case (`/api/ping` instead of `/`) and one new case (`/` 404s with no client build present) for the static-serving change.

### Deviations from the approved plan

- **Password self-service (forgot-password + change-password) and a minimal Mailinglisten/Postfach screen were included**, per the plan's own "Open Question 3" recommendation — both have complete, already-merged backends (`/api/auth/password-reset/*` since V0.7.2, `IMailingService`/`IDirectMessageService` since V0.1.5) and dropping them would have been a silent functional regression versus what the pre-V0.7.3 demo already exercised, or would have shipped a login-only wave with no recovery path for a locked-out member.
- **The double-submit CSRF token `AuthRoutes.kt`'s own KDoc names for "the V0.7.3 UI wave" was deferred again**, not built — implementing it needs a new backend token-issuance mechanism the server does not have yet (backend scope beyond "just" the UI wave), and that same KDoc already documents why `SameSite=Strict` alone is adequate interim coverage for the classic cross-site attack shape. Flagged explicitly rather than silently skipped; tracked for a future wave alongside OIDC (V0.8).
- **`IMemberService.listMembers()`'s id+displayName-only shape directly bounds the Mitgliederverzeichnis** in Mitgliederverwaltung to name-only search — there is no privileged read RPC for another member's email/role/address (only reachable transiently as a write-call return value, which this wave deliberately does not (ab)use for a read). Shipped as-is against the existing method with zero new backend surface, per the plan; the limitation is stated plainly in the screen's own help text. A follow-up micro-wave adding a BOARD/ADMIN-gated detailed read would improve this.
- **Component naming corrections versus the plan's KVision API references**, found only once actually building against the pinned `kvision`/`kvision-bootstrap` 9.6.0 sources: the plan's `io.kvision.toast.Toast` is actually `io.kvision.toast.ToastContainer` (`showToast(...)`), and `NavbarNav` is actually `io.kvision.navbar.Nav`. No functional difference, just corrected names.

### Known limitations (tracked for later versions)

- No `listMembersDetailed()`/`getMember(id)` RPC — see "Deviations" above.
- No forced-password-change-on-first-login UI (no such mechanism exists in the backend yet, see V0.7.2's own known limitations).
- Document folders render as a flat list, not a nested tree (`DocumentFolderDto.parentFolderId` exists but this wave's UI does not yet group by it).
- No compose/send UI for Mailinglisten or Direktnachrichten — carried forward read/subscribe-only, matching the pre-V0.7.3 demo's own scope exactly (see "Deviations" above).
- Federation (multi-server operation) is not yet built — planned for V0.8.

### Security

No new backend surface, no new RPC methods, no DTO changes, no migrations — this wave is client-side plus the minimal same-origin static-serving addition to `Application.kt`. Every privileged action's UI-level gating (Mitgliederverwaltung route guard, escalated-role `Select` disabling, pay-vs-waive button visibility, document-management buttons) is a UX nicety layered on top of, never a substitute for, the server's own `requireRole`/`isPrivileged`/`canAccessDocumentAtLevel` checks, which remain the sole actual authority and were not touched. Client-side input validation (`Validation.kt`) mirrors but never duplicates `PasswordPolicy`'s security logic and is never trusted as a security boundary. `staticFiles` serves only the client bundle directory, not the working directory; `LAPIS_CLIENT_DIST_ROOT` is an operator-controlled env var, not client input.

### Round-2 review findings (fixed same-day, before this wave's first push)

An independent round-2 review actually built the production client bundle, booted the server against the seeded demo data, and drove all four demo roles through a real browser rather than trusting the "manual QA pass" claim above at face value. That live pass found the claim did not hold: **`Widget.addCssClass(css: String)` treats its whole argument as one literal CSS class token** (KVision hands it straight to `Element.classList.add(token)`, which throws `InvalidCharacterError` if the token itself contains a space) — but nine call sites across this wave (`DashboardScreen.kt`, `ContributionsScreen.kt` x2, `DocumentsScreen.kt` x3, `MemberAdministrationScreen.kt` x2, `RegistrationScreen.kt`) passed a space-separated multi-class Bootstrap utility string (e.g. `"btn btn-outline-primary text-start"`) to a single call, exactly this mistake. The resulting exception was thrown from inside Navigo's own route-resolution `callHandler` step with no surrounding `try`/`catch` anywhere in that call chain, so it unwound silently — no console output, no crash dialog, just a screen that stopped rendering partway through. Concretely, before the fix: `DashboardScreen`'s nav tiles, "Konto" heading, password-change form, and logout/Austritt buttons never rendered; `RegistrationScreen`'s actual form (name/email/password fields, the legal-agreement checkbox, and the submit button) never rendered past the agreement text box; and — because the same exception aborted `callHandler` before Navigo's post-handler `updatePageLinks()` re-scan could run — the top navbar's own links never got hooked into Navigo's click-hijacking, so clicking Beiträge/Dokumente/Kommunikation/Mitgliederverwaltung silently did nothing. In short: as actually built and run, only the Login screen worked; nothing else in this wave was reachable by a real user clicking through the UI, contradicting essentially every item the manual-QA-pass paragraph above claims to have exercised.

Fixed by adding `CssClasses.kt`'s `Widget.addCssClasses(css: String)` (splits on whitespace, calls `addCssClass` once per token) and switching all nine call sites to it; `Routing.kt`'s `show()` also now wraps every screen render in a `try`/`catch` that logs to the console and shows an error toast instead of failing silently, so a future defect of this shape surfaces immediately instead of masquerading as "nothing happens." Re-verified against all four seeded demo roles in a real browser after the fix: dashboard tiles/Konto section render fully, every navbar link navigates, Mitgliederverwaltung is genuinely hidden from nav and route-guarded (redirects to Dashboard with a "Kein Zugriff" toast) for a MEMBER caller, BOARD's role `Select` is genuinely restricted to MEMBER while ADMIN's offers all four roles, Austritt's confirmation modal genuinely blocks the action until confirmed (and Abbrechen genuinely cancels it), and a full self-registration → board-approval round-trip works end to end. `./gradlew clean check` re-run clean afterward (852 tests, 0 failures).

A separate, unrelated round-1 finding — `getSessionInfo()`'s boot-time anonymous probe throwing an unhandled `SerializationException` inside Kilua RPC's own polymorphic-exception encoding, because `@RpcServiceException` subclasses live in `lapis-server` and are invisible to `lapis-shared`'s KSP processing — was investigated but not fixed in this pass: it is a pre-existing (since V0.7.1), cross-cutting architecture issue touching roughly 30 files, and the authorization boundary itself is not affected (operations are still correctly rejected; only the error's wire shape is wrong). Tracked as a dedicated follow-up wave rather than rushed here.

## [0.7.2] — 2026-07-23

### Added

**Join/registration workflow (V0.7.2)** — the second of V0.7's three deploy-blockers, delivering the admission/exit lifecycle `IAuthService` (V0.7.1) explicitly deferred. Self-registration (`IRegistrationService.registerApplication`, unauthenticated) creates a `MemberStatus.ANTRAG` applicant after the registrant echoes back a versioned, SHA-256-hashed Beitrittsvertrag/Satzungs-text unmodified — the exact same constant-time-hash-verification mechanism `AuctionComplianceDisclaimer`/`AuctionService.enableAuction` already established for a different legal-acknowledgment need, now applied to membership admission itself (`membership_agreement_acknowledgment` is the resulting append-only proof record; a real deployment must replace `MembershipAgreementDisclaimer.TEXT` with its own lawyer-reviewed Satzung under a new version before relying on it). Board admission is always an explicit decision, never silence-is-approval (unlike Internes Crowdfunding's 14-day auto-approval clock — membership is a more consequential, harder-to-undo decision than a crowdfunding project): `approveApplication`/`rejectApplication` use the exact row-lock + compare-and-swap concurrency contract `CrowdfundingService.approveProject`/`rejectProject` established, verified under a real two-thread concurrent-decision test (exactly one of a simultaneous approve/reject on the same applicant wins, the other gets a conflict). A rejected application becomes the new `MemberStatus.ABGELEHNT` (retained with `rejectionReason`/`reviewedBy`/`reviewedAt` — never silently reused as `AUSGETRETEN`, which means something structurally different: "left after having been admitted"). BOARD/ADMIN can also create a member directly at `AKTIV` with an admin-set temporary password (`createMemberDirect`, for paper-based admissions/migration) — creating a BOARD/TREASURER/ADMIN-role account this way additionally requires ADMIN specifically, not just BOARD, mirroring the existing `ADMIN_ONLY` vs `BOARD_ONLY` distinction `canAccessDocumentAtLevel` already makes for `DocumentAccessLevel`.

**Austritt (exit) — corrects a stale roadmap description.** `leaveMembership` is member-initiated, self-service, requires no board approval (mirrors the project's own concept document: "Eintritt und Austritt sind ausschliesslich Willenserklaerungen der Vertragspartner"), and transitions `AKTIV → AUSGETRETEN` — **not** to `GAST`, as an earlier vault roadmap note incorrectly described. `GAST` is a separate, larger, still-unbuilt pre-membership guest-identity concept (see the V0.6.4 guest-basket scope cut); conflating the two would have been wrong, and this wave deliberately does not build any transition into `GAST`. Every session is revoked on exit, and `/api/auth/login` itself now rejects `AUSGETRETEN`/`ABGELEHNT` accounts — the exact same generic "Invalid credentials" response as a wrong password, checked only after password verification so no extra branch could leak status via response timing.

**"Forgot password" (V0.7.2)** — a real, tested reset-token mechanism: a 256-bit random token (`SessionTokens`, reused unchanged), only its SHA-256 hash ever persisted (`password_reset_token`), a 1-hour expiry (deliberately much shorter than a session's 8 hours — a reset token is a stronger bearer credential), single-use via atomic row-lock + compare-and-swap consumption (verified under a tamper/replay test: consuming the same token twice returns `null` the second time), a rate-limited request endpoint (`LoginRateLimiter`'s existing per-email/per-IP pattern, reused, not duplicated), and an identical response whether or not the requested email is registered (same account-enumeration posture `/api/auth/login` already established). **Email delivery is honestly NOT implemented** — this codebase has no SMTP transport anywhere; `NoOpPasswordResetMailer` logs that a reset "would be sent" and nothing more (never logging the raw token itself, which would defeat the whole mechanism), the exact same disclosed-not-claimed posture `MailingService.sendMailingMessage` already established for mailing-list sends since V0.4/V0.1.5. This was a deliberate choice, not a shortcut: there is no SMTP test double / verifiable relay in this environment, and shipping an *unverified* real integration would itself violate this project's own "no overclaiming capability" norm (see README "What doesn't work yet" and the Letterxpress/postal-mail precedent of treating real external delivery as its own explicit scope item). `PasswordResetMailer` is a clean interface seam (same shape `PostalMailProvider` already establishes) — a real SMTP-backed implementation is a drop-in replacement whenever an operator can actually verify it end to end; until then, `AdminBootstrap --force` remains the interim path for a genuinely locked-out operator.

**Self-registration and the picker share the same enumeration-hardening posture, extended by judgment beyond the task's literal ask.** `registerApplication` returns the identical response for a brand-new registrant and a duplicate email (no second row created either way) — the task only explicitly required this discipline for password-reset, but membership in an organization like this one (see `OrganizationSettings.isPoliticalParty`) can itself be sensitive information worth protecting the same way. `IMemberService.listMembers()` (the unauthenticated "current member" picker) is now filtered to `AKTIV` only — it was previously unfiltered, which became actively wrong once self-registration started producing real `ANTRAG`/`ABGELEHNT`/`AUSGETRETEN` rows (an unauthenticated caller should not see who applied, was rejected, or left).

### Known limitations (tracked for later versions)

- No real email transport anywhere in this codebase (mailing lists since V0.4/V0.1.5, password reset since this wave) — `NoOpPasswordResetMailer`/`MailingService`'s simulated-success paths are both honestly disclosed stubs, not claimed working delivery.
- No forced-password-change-on-first-login for admin-created accounts (no such mechanism exists in this codebase yet) — the admin-set temporary password is usable indefinitely until the member changes it themselves via `changePassword`.
- No dedicated "admin resets an EXISTING member's forgotten password" endpoint — that member can always use the self-service password-reset flow instead.
- Pre-existing gap, not introduced by this wave, flagged not fixed: `PeerTransferService.transferLtr` and `GovernanceService.castVoteBallot` do not gate on `requireActiveMembership` — an `ANTRAG` member (who can still log in, by design, to check on their pending application) could in principle already stake/transfer LTR before board approval. Recommend a dedicated follow-up hardening wave.
- No usable multi-screen web UI yet — still planned for V0.7.3.

### Security

Board-approval race closed via the same row-lock + compare-and-swap contract established for Crowdfunding project decisions, verified under a real concurrent two-thread test (not just a sequential double-decision test). Registration and password-reset-request both apply the identical-response account-enumeration discipline `/api/auth/login` established. Password-reset tokens follow the exact hash-only-persisted, single-use, short-TTL pattern `session`/`SessionStore` already established, with an atomic compare-and-swap consumption verified under an explicit tamper/replay test. Creating an escalated-role (BOARD/TREASURER/ADMIN) account via `createMemberDirect` requires the caller to be ADMIN specifically, closing an obvious privilege-escalation path (a BOARD account minting a new ADMIN account).

## [0.7.0] — 2026-07-22

### Added

**Real authentication and revocable sessions (V0.7.1)** — replaces the `X-Member-Id` HTTP-header stand-in that `RequestContext.kt` has carried since V0.1 with a real password-login + server-side, revocable session mechanism, the first of three deploy-blockers found in a 2026-07-22 readiness review (no auth, no registration, no usable UI). Password hashing is bcrypt (cost 12) via `at.favre.lib:bcrypt`, chosen over Argon2id because the only mature JVM Argon2 binding ships native JNI code; passwords are SHA-256-pre-hashed and Base64-encoded before bcrypt to neutralize its 72-byte truncation and NUL-byte-stop behavior. Sessions are server-side and DB-persisted rather than stateless JWT (this codebase treats auditability/revocability as first-class, see the V0.5.3 GoBD audit chain and V0.5.4 backup) — a 256-bit `SecureRandom` token is issued on login, only its SHA-256 hash is ever persisted, delivered via an `HttpOnly`+`Secure`+`SameSite=Strict` cookie (a `Bearer` header is also accepted). Logout and `changePassword` revoke server-side immediately. `resolveCurrentMember` (`security/RequestContext.kt`) remains the single designed switch point every RPC service resolves the caller through, so swapping the resolution mechanism touched only this one file — all ~25 existing services and their `requireRole`/`isPrivileged`/`canAccessDocumentAtLevel` checks work unchanged. Hardening: identical error response + always-executed dummy-hash bcrypt compare for unknown-email/wrong-password/no-password-set (account-enumeration and timing-attack resistant), per-email and per-IP login rate limiting, `SameSite=Strict` as the interim CSRF control. Bootstrap for the very first admin password is an env-var-only CLI task, never a network-reachable "first login sets the password" path, and never overwrites an existing hash.

### Known limitations (tracked for later versions)

- OIDC login is not built (`account.oidc_subject` stays reserved) — planned for V0.8 (Federation).
- The join/registration workflow (self-service signup, board approval, admin member-creation) does not exist yet — this wave only logs in already-existing accounts. Planned for V0.7.2.
- No "forgot password" email flow yet — deferred to V0.7.2, where email infrastructure is added anyway. Only authenticated self-service `changePassword` exists.
- No admin-reset-of-others'-passwords path.
- Full double-submit CSRF tokens are deferred to the UI wave (V0.7.3) — `SameSite=Strict` is the interim control.
- The 905 existing `header("X-Member-Id", ...)` test call sites across ~40 `testApplication` blocks were deliberately not rewritten. Session-token resolution always runs first; only if it yields nothing does a trusted-header fallback run, gated behind two independent structural locks (a JVM system property set solely by the Gradle test task, and H2-in-memory detection) plus a third inner check in the fallback itself — a real Postgres deployment can never reach it.
- No usable multi-screen web UI yet (see V0.6.5/known limitations below for the client's current state) — planned for V0.7.3.
- Federation (multi-server operation) is not yet built — planned for V0.8.

## [0.6.0] — 2026-07-22

The LTR economy arc — internal currency, meritocratic marketplace mechanics, and the money-to-LTR
conversion boundary — including the auction, which the original V0.6 scope had deferred pending
legal review.

### Added

**Real LTR ledger + Internes Crowdfunding (V0.6.1)** — replaces the provisional LTR balance snapshot (`ltr_balance`) with a real, append-only, member-scoped ledger (`ltr_ledger_entry`, signed amounts, balance derived live as `SUM(amount_ltr)`). `LedgerBackedLtrBalanceProvider` swaps in for the earlier placeholder at `GovernanceService`'s single seam. Adds Internes Crowdfunding on top, with the two mechanisms the concept keeps deliberately separate: a **Sichtbarkeits-Gewicht** (LTR-staked project weight, decays 10%/day, entry hurdle requires matching the current top project's weight, race-safe via a genesis-singleton row lock) and a **Verteilungs-Korb** (one Like or Dislike per member per project, purely democratic, never LTR-weighted). Monthly EUR distribution deducts a fixed per-payer minimum contribution before apportioning the remainder across baskets with a new, exact BigInteger-cent `LargestRemainderApportionment` (also backported into the existing election-settlement rounding, which used a less precise method before). During this wave's own security loop, a real pre-existing gap was found and fixed: `castVoteBallot` (V0.2.3) validated `stake <= freeBalance` but never actually wrote a debiting ledger entry, so a member could stake the same LTR across unlimited concurrent votes and again via crowdfunding — now correctly debited via `LtrLedgerEntryType.VOTE_STAKE`.

**Direct LTR peer-to-peer transfer (V0.6.3)** — a member sends LTR directly to any other member, no auction/project/platform action in between. Extends `LtrLedgerEntryType` additively with `PEER_TRANSFER_OUT`/`PEER_TRANSFER_IN`. `transferLtr` (self-initiated, always debits the caller's own account) and `executeArbitrationTransfer` (TREASURER/BOARD/ADMIN only, mandatory non-blank purpose) as the sole correction path for fraud/identity-theft/coerced-donation cases — a regular, fully documented transfer, never a technical revert; there is deliberately no storno/cancel endpoint anywhere. Both accounts (not just the sender) are locked in canonical lexicographic-UUID order before any balance read, structurally preventing the classic A-to-B/B-to-A deadlock.

**Politiker-Profile und Politiker-Ranking (V0.6.4)** — an explicit, member-only Like/Dislike ranking layer for politicians, built on the LTR ledger. A BOARD/ADMIN grants/revokes `PoliticianProfile` status per member (upsert-by-member: a re-grant after revocation reactivates the same profile row, starting back at Korb=0 with no persisted rating history); any `AKTIV` member can cast one Like/Dislike per politician. Trust weight is a **single shared LTR pool** — the current free-LTR balance of every distinct rater across every active politician, summed once per person — apportioned across politicians in proportion to their basket via `LargestRemainderApportionment`, recomputed fresh on every read. `OrganizationSettings.politicianRankingEnabled` (default off) gates every endpoint. A manually-triggered, idempotent-per-month `snapshotWeights` action persists a historical trend line. Revoking status deletes all of that politician's ratings and snapshots; the profile row itself is retained.

**Price-Oracle für die Anker-Bindung (V0.6.5)** — the first real money-to-LTR conversion boundary this codebase has had. Three independent, free, no-API-key public exchange feeds (Coinbase, Kraken, Bitstamp) are queried in parallel; a provisional median is computed, outlier sources dropped, and if the survivors' own spread is still too wide the quote is rejected rather than trusted. A quote is `LIVE`, `DEGRADED`, or `CACHED`, governed by a single-row, ADMIN-tunable `price_oracle_config`. The load-bearing `convertDonationToLtr` (TREASURER/BOARD/ADMIN) books an already-received donation: fetches a quote and, if not halted, MINTs the computed LTR and writes a permanent `price_oracle_conversion` provenance row in the same transaction. Every oracle source resolves against a compile-time-fixed hostname allowlist — `price_oracle_config` carries no URL/host field at all — HTTPS-only, no redirects, bounded timeouts, 64 KiB response cap.

**LTR-Auktion, disabled by default (V0.6.2)** — the English proxy-bid auction from the concept doc, gated behind an opt-in the legal-risk analysis in that same document forced: ZAG/MiCAR/GewO/tax/consumer-protection/PartG/GwG classification depends on jurisdiction and organization type, which no single blanket legal review can resolve for every future deployment. `auctionEnabled` defaults to `false` and stays `false` until an ADMIN explicitly acknowledges a versioned, SHA-256-hashed disclaimer naming all six risk areas — responsibility for the enable decision moves to the organization operator. Mechanics: eBay-style proxy bidding, second-price settlement at close, optional Buy-It-Now, lazy close on next read (no scheduler). LTR-only, no platform commission, flat 0.01 LTR listing fee. Reservation design is real ledger holds (`AUCTION_HOLD`), not a derived calculation — only the current leader holds one, released on outbid/buyNow/settle, so every other debit path automatically sees the reservation without needing to know about auctions at all. `auctionEnabled`/`auctionMaxValueLtr` are deliberately absent from the generic `updateOrganizationSettings` write-set; the only way to flip the auction on is the dedicated `enableAuction` RPC with its constant-time disclaimer-hash re-verification.

### Fixed

**Build breakage in V0.6.4/V0.6.5, found and repaired before this release.** Both waves were originally authored in a sandboxed session that could never run a real `./gradlew clean check` (Gradle 9.6.1 wrapper download blocked by egress policy, local Gradle 8.14.3 incompatible with the Kilua/KVision plugins) — both waves' own changelog entries disclosed this and asked for a real build-verification pass. That pass found 7 genuine defects, all fixed prior to this release: two missing imports (`io.ktor.utils.io.readAvailable`, `kotlinx.datetime.atTime`) and one entirely missing import (`org.jetbrains.exposed.v1.jdbc.update`, breaking `politicianRankingEnabled` wiring at the test level); a variable-shadowing bug in `PriceOracleService.kt` where local `val`s with the same name as table columns broke the Exposed insert DSL; a real kUML modeling bug where `politician_reaction.rater_member_id` was declared as a UML association with a custom `role`, which does not rename the generated column in this kUML setup (fixed by switching to the established plain-`«Column»`+`fkEntity` idiom); a structural DSGVO capacity bug where the shared `outcome_summary` column (`VARCHAR(8000)`) overflowed to 8670 characters once seven more `PersonalDataContributor`s had been added since V0.2.5 (widened to unbounded `text`, matching the fix already applied to the V0.5.3 audit-log's analogous columns); and a test bug (not a product bug) in `PoliticianServiceTest`'s ordering assertion, which assumed a rater's own LTR balance directly inflates the politician they voted for — contradicting the shared-pool design the concept document actually specifies.

### Security

Every oracle source resolves against a compile-time-fixed hostname allowlist, HTTPS-only, no redirects, bounded timeouts, 64 KiB response cap, and a catch-all that maps every source failure to `null` without ever logging a response body or raw exception message (V0.6.5). V0.6.1's review/security loop closed a TOCTOU race on LTR debits by having every debit-causing write take a row lock on the member's own row before reading `freeBalance`. V0.6.2's auction reservation model uses real ledger holds specifically so no other debit path can be blind to an open reservation. V0.6.3's peer transfer locks both accounts in canonical UUID order, verified deadlock-free under a real two-thread concurrent test.

### Known limitations (tracked for later versions)

- **Guest (Gast) rating basket for Politiker-Profile (V0.6.4) is entirely cut — accepted scope, product-owner sign-off received 2026-07-22.** The concept's Mitglied/Gast two-basket mechanic needs an operational Gast identity that does not exist anywhere in this codebase yet (`MemberStatus.GAST` is an inert enum literal nothing currently sets or transitions into) — building a permanently-empty guest basket against it would be decorative, not functional. `PoliticianProfileDto` has `memberTrustWeight` only; a future wave adds `guestTrustWeight`/`combinedTrustWeight` additively once a real Gast identity model lands (tracked for V0.7.2/V0.8). Flagged during V0.6.4's own review loop as needing explicit product-owner sign-off before it could be considered accepted rather than merely documented — that sign-off is now given ("can stay like that for now"); revisit once a real Gast identity model exists.
- No LTR ↔ Gold/Fiat anchor sources wired (`AnchorAsset.GOLD_XAU`/`FIAT` are reserved enum literals only Bitcoin has real price sources for).
- The price-oracle quote cache is in-memory/per-server, not shared across a federation — tracked for V0.8.
- No persistent price-oracle halt-queue (`PriceStatus.DEFERRED` reserved-and-unused).
- Bound LTR stakes (Vote and Crowdfunding project stakes) are not released on vote-close/project-rejection — no release path built yet.
- Disabling the auction strands any already-open auction's holds until re-enabled (no fund loss, settle/release paths also require the gate to be on).
- No guest/Gast participants anywhere in the LTR economy yet (Crowdfunding, Peer-Transfer, Auction, Politician ratings) — all Member-only, since no operational Gast identity model exists. Tracked for V0.7.2/V0.8.
- No comment/discussion feed under a Crowdfunding project or Politician profile.
- No scheduler/cron infrastructure exists anywhere in this codebase — all periodic actions (monthly EUR distribution, politician-weight snapshots) are manually triggered by BOARD/ADMIN.
- Federation (multi-server operation) is not yet built — planned for V0.8.

## [0.5.1] — 2026-07-21

### Added

Completes the V0.5 compliance bundle that 0.5.0 deliberately narrowed in scope — the three remaining items from that release's "known limitations" list.

**GoBD audit log** — a hash-chained (SHA-256), append-only `AuditLogEntry` log written in the same transaction as the business mutation it records, serialized via a genesis-singleton `AuditLogChainState` row (`SELECT ... FOR UPDATE`). Covers the JournalEntry lifecycle (draft/post), Resolution creation, BoardMembership changes, and PartyDonationCompliance verdicts for postings that actually committed. Deliberately out of scope: ledger/cost-center master-data CRUD, DSGVO erasure (has its own separate, unchanged `dsgvo_audit_log`), and any retention/archival policy. Read access is TREASURER/BOARD/ADMIN-gated with capped pagination; before/after snapshots are excluded from a member's own GDPR export.

**Full-organization backup/restore/export** — an ADMIN-only, streamed ZIP export/restore covering every table in the schema (discovered dynamically via `information_schema`, not a hand-maintained list — any table a future domain wave adds is automatically in scope) plus document blobs. Export streams row-by-row without materializing the database in memory; restore is upsert-based, gated by a formatVersion + SHA-256 schema-checksum compatibility check and a non-empty-target pre-flight guard against accidental cross-organization merges. Zip-Slip is guarded on both the export and restore paths. Infrastructure-level backup (`pg_dump`/WAL archiving) remains explicitly out of scope — an operations concern, not solved here.

**DSGVO-Vollausbau (AVV, TOMs, DSFA, Datenpannenmeldung)** — four record-keeping/workflow tools, none of them automated legal advice: an AVV register for third-party processors (status/dates/document reference, coupled to the existing postal-mail opt-in only as a non-blocking advisory log, never a hard gate); TOM documentation across the eight Art. 32 / Anlage §64 BDSG categories; a DPIA template where the required-or-not verdict is always a stored human judgment (a `DpiaRiskMatrix` helper only renders a display band, it never decides); and a data-breach-incident workflow that surfaces the Art. 33 72-hour clock as a read-time warning without ever auto-filing a notification. Authorization is ADMIN-only for AVV/TOM writes, BOARD/ADMIN for DPIA/breach read and write.

### Known limitations (tracked for later versions)

- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Federation (multi-server operation) is not yet built — planned for V0.7.
- Audit log's hash chain is plain SHA-256 (no HMAC/external anchoring) and immutability is enforced only at the application layer (no DB-level UPDATE/DELETE grant restriction) — both accepted, documented residual risks, not defects against this wave's own requirements.
- Backup/restore has no decompression-ratio/zip-bomb cap beyond the 512 MiB compressed-upload limit — low severity given the actor is already ADMIN-only.

## [0.5.0] — 2026-07-19

### Added

**§25 PartG donation-acceptance check** — a pure, DB-free `PartyDonationComplianceCalculator` (same idiom as `JournalEntryBalance`/`UseOfFundsCalculator`) returning ALLOWED/PROHIBITED verdicts plus additional-duty flags (anonymous-forwarding, prompt Bundestag report, annual Rechenschaftsbericht disclosure) for donations to political parties, with all thresholds as named constants explicitly flagged as current understanding requiring legal verification. The accounting model gains an `ExternalDonor` entity and `DonorCategory` enum so a `JournalEntry` can attribute a donation to a non-member donor (mutually exclusive with the existing `donorMemberId`). The check is hooked into `postJournalEntry`/`postDraftEntry`, gated strictly on `OrganizationSettings.isPoliticalParty`, hard-blocking PROHIBITED donations while never blocking ALLOWED-with-duties postings. A new read-only, TREASURER/BOARD/ADMIN-gated report lists open prompt-report and annual-disclosure duties for a given calendar year.

**§20 GwG Transparenzregister board-change reminders** — a queryable board roster with history (`BoardMembership`: member, committee role, start/end), written in lockstep with the existing `CommitteeMembership` seating at election-tally time and via a new manual appoint/end-membership action for co-options, resignations, and recalls that don't go through a fresh election. `Member` gains the two missing beneficial-owner fields (date of birth, nationality), both nullable and covered by GDPR export/erasure. A persisted `TransparenzregisterReminder` log records every JOINED/LEFT board-change event, plus a read-only report of open reminders and members still missing beneficial-owner data — reminder/acknowledgement only, no automated filing to transparenzregister.de (no suitable public API exists). Unlike the PartG check, this duty is **not** gated on `isPoliticalParty` — §20 GwG transparency duties apply to every Verein/Partei.

### Known limitations (tracked for later versions)

- No automated filing to transparenzregister.de — reminders and reports only, filing itself stays a manual, human-triggered step.
- Audit-log/GoBD tamper-evidence, retention enforcement, and TSE integration, plus a full backup/restore/data-export guarantee and full GDPR build-out (AVV, TOMs, DSFA, breach reporting), are not yet implemented — the original V0.5 scope for these was narrowed to the two donation/transparency compliance checks above; the rest remains open, tentatively folded into a later wave.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Federation (multi-server operation) is not yet built — planned for V0.7.

## [0.4.0] — 2026-07-19

### Added

**Mail-merge/PDF engine** — Beitragsrechnung (membership dues invoice), a §50 EStDV Spendenbescheinigung (donation receipt, following the official BMF Muster pattern, distinguishing §10b EStG association donations from §34g EStG political-party donations), and an Einladung (invitation letter), all rendered with Apache PDFBox and delivered as raw PDF bytes over plain Ktor HTTP routes rather than Kilua RPC, mirroring the existing document-download idiom. Guessed or simplified legal wording in the donation receipt is explicitly flagged in code for human/tax-advisor review before real-world use. To make the templates fillable, this release also adds: a minimal nullable postal address on `Member` (with a new `updateMemberAddress` endpoint), a single-row admin-editable `OrganizationSettings` entity (letterhead, bank details, Gemeinnützigkeit tax-exemption reference), and an optional `donorMemberId` bridge on `JournalEntry` so a posted donation can be traced back to its donor for receipt generation. Beitragsrechnung and Spendenbescheinigung PDFs are additionally archived into the existing document store for retention.

**Letterxpress postal-mail dispatch** — an explicit, human-triggered path to mail a generated Beitragsrechnung, Spendenbescheinigung, or Einladung to members without email, via a new `PostalMailProvider` abstraction with a Letterxpress implementation. Gated behind a new `OrganizationSettings.postalMailEnabled` opt-in (default off), since enabling it in real operation requires a Data Processing Agreement (Auftragsverarbeitungsvertrag/AVV) with Letterxpress; defaults to Letterxpress's sandbox/non-live mode until explicitly switched to live dispatch. A new `PostalDeliveryLog` records every dispatch attempt (status, provider reference, a sanitized error message — never a raw exception or provider response body). Dispatch requires the same authorization tier as PDF generation and a bounded, explicit recipient list (no unbounded batch sends). The Letterxpress wire format could not be verified against live documentation in the build environment and is explicitly flagged in code as needing a human check before production use.

### Known limitations (tracked for later versions)

- The Letterxpress integration's exact API wire format (endpoints, field names, auth flow) is implemented from general knowledge, not verified against live/current Letterxpress documentation — verify before enabling live dispatch.
- Spendenbescheinigung is issued per single donation entry, not aggregated into an official BMF-style Sammelbestätigung across a period — aggregation rules need a human/tax-advisor check.
- No compliance bundle yet (§25 PartG donation-acceptance check, §20 GwG transparency-register reporting, full GoBD audit-log/tamper-evidence/retention/TSE, backup/restore guarantee, full GDPR build-out) — planned for V0.5.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).

## [0.3.0] — 2026-07-19

### Added

**Accounting core** — SKR42 chart of accounts and double-entry bookkeeping (originally modeled on SKR49, switched to SKR42 since that is DATEV's current recommendation for new non-profit clients): ledger accounts, journal entries/postings with a server-enforced balance invariant (Σdebit = Σcredit, validated independently of client input, immutable once `POSTED`), a general ledger view, and treasurer/board/admin-tiered authorization throughout.

**Financial statements** — `GuV` (income statement), `Bilanz` (balance sheet), and a combined `Jahresabschluss` (annual financial statement), all derived purely from `POSTED` journal postings with no new persisted state. The balance sheet surfaces an explicit cumulative-result equity line so Aktiva = Passiva always holds, since income/expense are not closed to equity in this version.

**Four-sphere Gemeinnützigkeit separation** — every posting now carries a mandatory sphere (Ideeller Bereich / Vermögensverwaltung / Zweckbetrieb / Wirtschaftlicher Geschäftsbetrieb, DATEV-KOST1-flavored), enforced with no default and no nullable transition period, plus a per-sphere income-statement report.

**§55 AO Mittelverwendungsrechnung and §62 AO Rücklagenbildung** — reserve categories (Projektrücklage, freie Rücklage, Wiederbeschaffungsrücklage, Betriebsmittelrücklage) as an optional classification on equity ledger accounts, funded via ordinary double-entry transfers, plus a derived use-of-funds statement with a FIFO timely-use carry-forward and overdue-amount tracking anchored at inception. The freie-Rücklage percentage cap and the §55 small-organization exemption are deliberately not hard-coded — both are surfaced as data for human verification rather than enforced constants.

**Kassenbuch** — a chronological, gapless cash-book view for designated cash-register accounts, derived from existing immutable `POSTED` postings, with two GoBD-informed guards: no posting without a voucher reference for cash accounts, and the cash balance may never go negative (enforced with row-level locking to close a same-account race). This is explicitly a GoBD foundation only — cryptographic tamper-evidence, retention enforcement, and TSE integration remain out of scope, planned for V0.5.

**Kostenstellen/cost-center accounting** — an open-ended, user-created `CostCenter` entity (unlike the fixed sphere/reserve enums) with the same create/list/deactivate lifecycle as ledger accounts, optional per-posting assignment (most routine bookings have no project association), and a minimal per-cost-center income/expense/result report. Lays the general mechanism V0.6 (Crowdfunding/Auktion) will later attach campaigns to, without building any campaign-specific logic yet.

### Changed

Dependency bumps: Kotlin 2.4.0 → 2.4.10, KSP 2.3.9 → 2.3.10, kuml 0.35.0 → 0.36.1. JVM toolchain corrected from an accidental 26 pin to 25, the actual requirement for loading Kilua RPC's published jars.

### Security

- Fixed an unmapped `IllegalArgumentException` for an out-of-range `fiscalYear` in `getAnnualFinancialStatement`, replaced with a typed `BadRequestException`.
- Closed a check-then-act race in the Kassenbuch's never-negative-balance guard by adding row-level locking (`SELECT ... FOR UPDATE`) with a deterministic lock-acquisition order, preventing both a balance-check bypass under concurrent postings and a possible deadlock when a single entry locks more than one cash account.

### Known limitations (tracked for later versions)

- No mail-merge/PDF engine or postal-mail path yet — planned for V0.4.
- No compliance bundle yet (§25 PartG donation-acceptance check, §20 GwG transparency-register reporting, full GoBD audit-log/tamper-evidence/retention/TSE, backup/restore guarantee, full GDPR build-out) — planned for V0.5.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6; cost centers (this release) lay the groundwork for attaching campaigns/auctions.
- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).

## [0.2.0] — 2026-07-18

### Added

**Governance** — committee/working-group management and meeting management (agenda, resolution register, minutes template, attendance tracking, quorum check); motion management for general assemblies and committees.

**Voting — three orthogonal modes**:
- **Meritocratic votes** — LTR-weighted voting on substantive/project questions.
- **Democratic elections** — one-member-one-vote for legally mandated personnel and constitutional decisions (board elections, bylaw amendments), including election board oversight, eligible-voter snapshots, candidacy management, secret and open ballot modes, and a configurable N-of-M tally-approval step.
- **Systemic consensus** — resistance-based decision-finding (Visotschnig/Schrotta method): each voter rates every option 0–10, the option with the lowest cumulative resistance wins, with a group-conflict index, configurable tiebreak rules, and an automatic "status quo" option.

All three modes share the same resolution register (`Resolution`) and reuse a single anonymous/open ballot infrastructure end to end.

**MDA persistence pipeline fully wired** — the kUML UML→ERM→Exposed/Flyway pipeline (ADR-0016, tracked as a known limitation in 0.1.0) is now the actual production persistence layer: all hand-written Exposed tables were deleted and replaced with kUML-generated code from versioned `.kuml.kts` domain models, and the Flyway baseline migration is generated from the same source of truth. Multiple real kUML gaps surfaced and were fixed upstream along the way (enum-to-`VARCHAR` type fidelity, Kotlin object-name overrides, KMP-safe UUID/date-time representations, explicit FK targeting via `fkEntity`/`fkAttribute`, a new `«Index»` stereotype for composite unique constraints) — see [ADR-0016](https://github.com/kuml-dev/kUML) for details. The project now depends on the real Maven Central `kuml` artifact (currently 0.35.0); the temporary `mavenLocal` bridge used during development has been retired.

### Changed

**English-only domain terminology.** The entire governance/voting domain, previously named in German, was renamed to English end to end (entities, tables, classes, DTOs, services, tests): Gremium→Committee, Sitzung→Meeting, Tagesordnungspunkt→AgendaItem, Anwesenheit→Attendance, Antrag→Motion, Beschluss→Resolution, Abstimmung→Vote, Wahl→Election, Konsensierung→SystemicConsensus. `README.adoc` and `docs/architecture/domain-model.adoc` were fully translated to English. This aligns the codebase with this project's own documented convention (English documentation and class names for all `kuml-dev`/Lapis repos).

### Known limitations (tracked for later versions)

- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).
- No accounting core yet (chart of accounts, non-profit four-sphere separation, use-of-funds statement) — planned for V0.3.
- No mail-merge/PDF engine or postal-mail path yet — planned for V0.4.
- No compliance bundle yet (PartG donation-acceptance check, transparency-register reporting, GoBD audit log, backup/restore guarantee, full GDPR build-out) — planned for V0.5.

## [0.1.0] — 2026-07-12

### Added

**Project foundation** — Gradle multi-module build (`lapis-shared`, `lapis-server`, `lapis-client`) following the Kilua RPC fullstack convention: a Kotlin Multiplatform shared module holding RPC service interfaces and domain DTOs, a Ktor JVM server, and a KVision Kotlin/JS client. CI workflow runs `./gradlew clean check` on push/PR. Persistence via Exposed ORM + Flyway migrations against PostgreSQL.

**Member management** — member master data, join/leave workflow (application → approval → active, with exit transitioning to guest status per the PZB legal-framework reference), membership tiers and roles.

**Contributions, documents, communication** — basic recurring-contribution tracking per membership tier (manual payment marking, no SEPA/dunning automation yet), a versioned document store with access tiers, and mailing-list/direct-message data models with typed Kilua RPC services.

**GDPR basics** — a self-registering `PersonalDataContributor`/`PersonalDataRegistry` mechanism so future entities opt into data-subject-access-request coverage without hand-maintaining a table list, enforced by an `information_schema`-based coverage test. Erasure requests support both anonymization (default, since accounting retention will later require it for financial records) and hard deletion where legally unconstrained, via a request → decide → execute workflow with an audit trail, exposed over both RPC and HTTP with self-or-ADMIN access control.

### Security

- Enforced the `ADMIN_ONLY` document access tier and gated version-listing/double-send paths that were previously open.
- Closed an unauthenticated member email/role leak in `listMembers()`.
- Made demo-data seeding opt-in with a guard against running against a real database.
- Fixed an ambiguous-join bug where `ErasureRequestTable`'s three separate foreign keys to `MemberTable` made Exposed's implicit join throw `IllegalStateException` at runtime; replaced with an explicit join condition.

### Known limitations (tracked for later versions)

- The kUML MDA persistence pipeline (UML → ERM → Exposed/Flyway, per [ADR-0016](https://github.com/kuml-dev/kUML) in the sibling kUML project) is not yet wired into this repo's build — Exposed tables are hand-written for now, with a kUML diagram kept as documentation only (`docs/architecture/domain-model.adoc`). Wiring the generator is tracked as follow-up work.
- Contribution management has no SEPA direct-debit or dunning automation.
- No governance layer yet (committees, meetings, motions, votes) — planned for V0.2.
