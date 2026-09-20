package network.lapis.cloud.client

import io.kvision.utils.obj
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import network.lapis.cloud.client.livekit.BackgroundAssetPaths
import network.lapis.cloud.client.livekit.BackgroundProcessorOptions
import network.lapis.cloud.client.livekit.BackgroundProcessorWrapper
import network.lapis.cloud.client.livekit.LocalVideoTrack
import network.lapis.cloud.client.livekit.SegmenterOptions
import network.lapis.cloud.client.livekit.SwitchBackgroundProcessorOptions
import network.lapis.cloud.client.livekit.createBackgroundProcessor
import network.lapis.cloud.client.livekit.supportsBackgroundProcessors
import org.w3c.dom.Image
import org.w3c.dom.events.Event
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** Zeitdeckel fuer `stopProcessor` -- ein haengendes Aufraeumen darf weder den Fehlerpfad noch das Verlassen blockieren. */
private const val BACKGROUND_STOP_TIMEOUT_MS = 3_000L

/**
 * Zeitdeckel fuer `stopProcessor` beim VERLASSEN der Konferenz -- deutlich kuerzer als
 * [BACKGROUND_STOP_TIMEOUT_MS] (Audit-Befund B1). `stopProcessor` teilt sich mit `setProcessor` livekits
 * `trackChangeLock` (`LocalTrack.ts:548`/`:618`): laeuft gerade ein haengendes `setProcessor`, wartet jedes
 * Aufraeumen hinter diesem Lock. Beim Verlassen darf das die Trennung nie messbar verzoegern -- die
 * eigentliche Abschaltung leistet ohnehin `room.disconnect(stopTracks = true)` -> `LocalTrack.stop()` ->
 * `processor.destroy()`, das VOR [ConferenceBackgroundController.dispose] laeuft.
 */
private const val BACKGROUND_DISPOSE_STOP_TIMEOUT_MS = 500L

/**
 * Billig, kein Netzwerk: `supportsBackgroundProcessors()`, `window.isSecureContext` und der UA-String
 * (App-WebView-Erkennung, Audit-Befund M5). Eine einzige Quelle fuer den Abschnitt in `ConferenceScreen.kt`
 * (Kachel-Raster ja/nein, welcher Erklaersatz) und den Controller.
 */
internal fun conferenceBackgroundAvailabilityInThisBrowser(): ConferenceBackgroundAvailability =
    conferenceBackgroundAvailability(
        libraryReportsSupport = runCatching { supportsBackgroundProcessors() }.getOrDefault(false),
        isSecureContext = runCatching { window.asDynamic().isSecureContext == true }.getOrDefault(false),
        userAgent = runCatching { window.navigator.userAgent }.getOrDefault(""),
    )

internal fun conferenceBackgroundSupportedInThisBrowser(): Boolean =
    conferenceBackgroundAvailabilityInThisBrowser() == ConferenceBackgroundAvailability.AVAILABLE

/**
 * Test seam (review finding "Testabdeckung"): the controller talks to the LiveKit track processor ONLY through
 * these two thin interfaces, so the failure path (timeout classification, switchTo-vs-setProcessor, cleanup,
 * one message per cause) is testable without a camera, WASM or WebGL. Production adapters: [RealBackgroundProcessor]
 * and [LiveKitBackgroundTrack].
 */
internal interface BackgroundProcessorHandle {
    /** In-place effect change within one episode (no rebuild). Rejects/throws on failure. */
    suspend fun switchTo(effect: ConferenceBackgroundEffect)

    /**
     * Welches Hintergrundbild der Transformer TATSAECHLICH haelt, oder `null`. Audit-Befund M1: `init`
     * verschluckt einen Bildfehler (`BackgroundTransformer.ts:83-86`), `setProcessor` erfuellt sich also auch
     * dann, wenn die WebGL-Hintergrundtextur leer blieb -- Person vor Schwarz, ohne jede Meldung. Diese
     * Nachbedingung macht daraus wieder einen klassifizierbaren `LOAD_FAILED`.
     */
    fun appliedBackgroundImagePath(): String?
}

/** The local camera track as far as the controller needs it. Handles are compared with `==`. */
internal interface BackgroundTrack {
    suspend fun setProcessor(handle: BackgroundProcessorHandle)

    suspend fun stopProcessor()

    fun currentProcessor(): BackgroundProcessorHandle?
}

/** Production handle around the `@livekit/track-processors` wrapper; equal iff it wraps the same JS object. */
internal class RealBackgroundProcessor(
    val raw: BackgroundProcessorWrapper,
) : BackgroundProcessorHandle {
    override suspend fun switchTo(effect: ConferenceBackgroundEffect) {
        raw.switchTo(switchBackgroundOptionsFor(effect)).await()
    }

    override fun appliedBackgroundImagePath(): String? = runCatching { raw.transformer.backgroundImageAndPath?.path }.getOrNull()

    override fun equals(other: Any?): Boolean = other is RealBackgroundProcessor && other.raw === raw

    /**
     * Konsistent zu [equals] (Identitaet des umhuellten JS-Objekts): Kotlin/JS vergibt pro Objekt einen
     * stabilen Identitaets-Hash, zwei Huellen um dasselbe `raw` liefern also denselben Wert. Die vorige
     * Konstante `0` war formal erlaubt, aber irrefuehrend (Audit-Befund N8).
     */
    override fun hashCode(): Int = raw.hashCode()
}

/** Thin production adapter from LiveKit's [LocalVideoTrack] to [BackgroundTrack]. */
internal class LiveKitBackgroundTrack(
    private val track: LocalVideoTrack,
) : BackgroundTrack {
    override suspend fun setProcessor(handle: BackgroundProcessorHandle) {
        // Sicherer Cast mit klarer Fehlermeldung statt hartem `as` (Audit-Befund N8): eine fremde
        // Handle-Implementierung ist ein Programmierfehler, kein stiller ClassCastException-Absturz. Die
        // Meldung enthaelt nur einen Typnamen, nie Nutzerdaten (Logging-Regel).
        val real =
            handle as? RealBackgroundProcessor
                ?: error("LiveKitBackgroundTrack erwartet einen RealBackgroundProcessor, bekam ${handle::class.simpleName}")
        // Positional (Kotlin verbietet benannte Argumente bei external-Aufrufen):
        // setProcessor(processor, showProcessedStreamLocally = true) -- die eigene Selbstansicht IST die
        // Vorschau, bereits attachte Elemente bekommen den bearbeiteten Track.
        track.setProcessor(real.raw, true).await()
    }

    override suspend fun stopProcessor() {
        track.stopProcessor(false).await()
    }

    override fun currentProcessor(): BackgroundProcessorHandle? = track.getProcessor()?.let { RealBackgroundProcessor(it) }
}

private fun switchBackgroundOptionsFor(effect: ConferenceBackgroundEffect): SwitchBackgroundProcessorOptions {
    val imagePath = conferenceBackgroundImagePath(effect)
    val blurRadius = conferenceBackgroundBlurRadius(effect)
    return obj<SwitchBackgroundProcessorOptions> {
        if (imagePath != null) {
            mode = "virtual-background"
            this.imagePath = imagePath
        } else {
            mode = "background-blur"
            this.blurRadius = blurRadius
        }
    }
}

private fun createRealBackgroundProcessor(effect: ConferenceBackgroundEffect): BackgroundProcessorHandle {
    val imagePath = conferenceBackgroundImagePath(effect)
    val blurRadius = conferenceBackgroundBlurRadius(effect)
    val options =
        obj<BackgroundProcessorOptions> {
            if (imagePath != null) {
                mode = "virtual-background"
                this.imagePath = imagePath
            } else {
                mode = "background-blur"
                this.blurRadius = blurRadius
            }
            // NUR delegate -- `modelAssetPath` hier wuerde den same-origin-Pfad unten UEBERSCHREIBEN
            // (segmenterOptions wird in BackgroundTransformer.init NACH modelAssetPath gespreadet).
            segmenterOptions = obj<SegmenterOptions> { delegate = "GPU" }
            // Beide Bibliotheks-Defaults (cdn.jsdelivr.net, storage.googleapis.com) werden ueberschrieben.
            assetPaths =
                obj<BackgroundAssetPaths> {
                    tasksVisionFileSet = ConferenceBackgroundAssets.TASKS_VISION_FILE_SET
                    modelAssetPath = ConferenceBackgroundAssets.MODEL_ASSET_PATH
                }
            maxFps = CONFERENCE_BACKGROUND_MAX_FPS
        }
    return RealBackgroundProcessor(createBackgroundProcessor(options))
}

/**
 * Audit-Befund M1, erste Haelfte: das Hintergrundbild wird im EIGENEN Code geladen, BEVOR ein Prozessor
 * gebaut oder umgeschaltet wird. Grund: `BackgroundTransformer.init` faengt einen Bildfehler ab und
 * protokolliert ihn nur (`BackgroundTransformer.ts:83-86`), `setProcessor` erfuellt sich also trotzdem --
 * die dokumentierte Zusage "Asset-404 -> Kamera laeuft ohne Effekt weiter" hielt fuer die ERSTE Anwendung
 * eines `BG_*`-Effekts deshalb nicht; stattdessen sah man sich vor Schwarz. Ausserdem ruft der
 * `BackgroundTransformer`-Konstruktor `this.update(opts)` OHNE `await` (`:54`), ein fehlschlagendes Bild
 * wuerde dort zu einer unbehandelten Promise-Ablehnung.
 *
 * Bewusst genau der Ladeweg der Bibliothek (`Image` + `onload`/`onerror`, danach `createImageBitmap`,
 * `loadAndSetBackground`, `:103-117`): gelingt er hier, gelingt er dort auch -- und das Bild liegt danach im
 * Browser-Cache, sodass das Zeitfenster fuer die unbehandelte Ablehnung praktisch geschlossen ist. Wirft bei
 * 404, blockierter Anfrage oder nicht dekodierbarem Bild; die Aufrufstelle klassifiziert das als
 * [ConferenceBackgroundFailure.LOAD_FAILED].
 *
 * **Raeumt hinter sich auf** (Folge-Audit, Punkt 4): EIN Handler fuer `load` und `error`, in einem `finally`
 * wieder abgemeldet (auch wenn das Zeitlimit die Coroutine abbricht) -- und das nur als Dekodier-Beweis
 * erzeugte `ImageBitmap` wird sofort geschlossen. Ohne `close()` haelt jeder Effektwechsel eine weitere
 * entkoppelte Bitmap-Kopie im Speicher; die Bibliothek baut fuer das Rendern ihre eigene.
 */
private suspend fun probeBackgroundImage(path: String) {
    val image = Image()
    image.crossOrigin = "Anonymous" // wie die Bibliothek, damit der Cache-Eintrag derselbe ist
    var registered: ((Event) -> Unit)? = null
    try {
        suspendCancellableCoroutine { continuation ->
            val handler: (Event) -> Unit = { event ->
                if (continuation.isActive) {
                    if (event.type == "load") {
                        continuation.resume(Unit)
                    } else {
                        // Keine URL, kein roher Fehlertext in der Meldung (Logging-/Datenschutzregel).
                        continuation.resumeWithException(IllegalStateException("Hintergrundbild konnte nicht geladen werden"))
                    }
                }
            }
            registered = handler
            image.addEventListener("load", handler)
            image.addEventListener("error", handler)
            image.src = path
        }
    } finally {
        registered?.let { handler ->
            image.removeEventListener("load", handler)
            image.removeEventListener("error", handler)
        }
    }
    val bitmapPromise = window.asDynamic().createImageBitmap(image)
    val bitmap = bitmapPromise.unsafeCast<Promise<Any?>>().await()
    runCatching { bitmap.asDynamic().close() }
}

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- der DOM-/LiveKit-nahe Teil: haelt hoechstens EINEN
 * `BackgroundProcessorWrapper` pro Effekt-Episode und setzt ihn auf den lokalen Kamera-Track. Die reine
 * Entscheidungslogik (Whitelist, Zustandsautomat, K6-Regel) steht in `ConferenceBackgroundEffects.kt`;
 * Architektur und manueller Pruefplan in `docs/architecture/video-background-effects.adoc`.
 *
 * **Alles laeuft lokal im Browser** -- kein Kamerabild geht an den Server; der Wrapper laedt WASM, Modell
 * und Hintergrundbild ausschliesslich vom eigenen Origin ([ConferenceBackgroundAssets]).
 *
 * **Lazy** (Forstall-Ruling K3): der Wrapper wird erst beim ersten echten Umschalten gebaut, nicht beim
 * Aufklappen des Abschnitts.
 *
 * **Serialisierung**: alle Anwendungen laufen unter einem [Mutex] -- ein Klick waehrend der Wiederherstellung
 * beim Beitritt darf keine zwei Prozessoren gleichzeitig aufbauen.
 *
 * **"Aus" ist `stopProcessor(false)`, nicht `switchTo({mode:'disabled'})`**: `disabled` liesse die komplette
 * Frame-Pipeline (MediaStreamTrackProcessor/TrackGenerator bzw. Canvas + Blob-Worker) weiterlaufen und den
 * bearbeiteten Track weiter veroeffentlichen, nur ohne Effekt -- sinnlose CPU-Last fuer jemanden, der "Aus"
 * gewaehlt hat. `stopProcessor` ruft intern `processor.destroy()` auf und setzt den Wrapper auf
 * `destroyed`; ein Neuaufbau beim naechsten Einschalten ist deshalb UNVERMEIDBAR (WASM kommt dann aus dem
 * Browser-Cache, siehe die Cache-Header-Routen in `ClientAssetRoutes.kt`).
 *
 * **Fehlerpfad -- die wichtigste Stelle der Welle.** Bei Exception oder Timeout: `stopProcessor` (raeumt
 * auch einen spaet doch fertig gewordenen Prozessor ab; ohne gesetzten Prozessor ein no-op), Wrapper
 * verwerfen, `ApplyFailed` in den Automaten, `onProcessedStreamSwapped`, hoechstens EINE Meldung je Ursache
 * je Sitzung; die Auswahl springt sichtbar auf "Aus", `desired` und der `localStorage`-Wert bleiben
 * UNVERAENDERT. Auch ein gescheitertes `switchTo` faellt GANZ auf "Aus" zurueck und verweilt nie in einem
 * halb gesetzten Zustand: `BackgroundTransformer.update` setzt seine `options` VOR dem `await` des
 * Bildladens; scheitert das Laden, bleibt ein Transformer mit `blurRadius = null` und gesetztem `imagePath`
 * zurueck, dessen `transform()` NICHT ueberspringt -- sichtbarer Renderfehler. Ausserdem raeumt
 * `ProcessorWrapper.destroy()` nach einem `init()`-Fehler (Zustand `initializing`) laut Quelle nichts auf --
 * pro fehlgeschlagenem Versuch bleibt ein OffscreenCanvas + MediaStreamTrackProcessor liegen. Genau deshalb
 * ist "hoechstens ein automatischer Versuch pro Sitzung" nicht nur UX, sondern auch Ressourcenschutz, und
 * ein gescheiterter Wrapper wird nie wiederverwendet.
 *
 * Nie wird eine ID ausserhalb der Whitelist zu einem `imagePath`: [conferenceBackgroundImagePath] ist der
 * einzige Ort, an dem eine URL entsteht, und sie ist immer relativ und same-origin.
 *
 * **[dispose] wartet NIE (Audit-Befund B1).** Fruehere Fassung: `dispose` nahm denselben [Mutex] wie eine
 * laufende Anwendung und wartete damit bis zu `applyTimeoutMs` (10 s) plus [BACKGROUND_STOP_TIMEOUT_MS]
 * (3 s). Weil `ConferenceScreen` `disposeBackgroundEffects()` VOR `session.disconnect()` aufrief und
 * `await`ete, blieb ein Klick auf "Verlassen"/"Fuer alle beenden"/"Zurueck zum Hauptraum" waehrend eines
 * haengenden Effektladens bis zu ~13 s mit AKTIVER Kamera und AKTIVEM Mikrofon verbunden, obwohl die
 * Oberflaeche schon "verlassen" sagte -- genau die Fehlerklasse, die ein frueheres Security-Audit als
 * "verwaiste Live-Session mit aktiver Kamera/Mikrofon" behoben hat. Jetzt gilt: die Aufrufstelle trennt
 * zuerst (`room.disconnect(stopTracks = true)` raeumt den Prozessor ohnehin ab), `dispose` nimmt den Mutex
 * nicht, deckelt sein `stopProcessor` auf [BACKGROUND_DISPOSE_STOP_TIMEOUT_MS] und zieht ein gedeckeltes
 * Aufraeumen losgeloest und unbegrenzt nach.
 *
 * **Generationszaehler (Audit-Befund M4).** Ein Kotlin-Timeout bricht das JS-Promise nicht ab, und
 * `setProcessor`/`stopProcessor` teilen sich livekits `trackChangeLock`: nach einem Timeout haelt das noch
 * laufende `setProcessor` diesen Lock, das gedeckelte `stopProcessor` des Fehlerpfads lief in sein eigenes
 * 3-s-Limit -- wurde die Anwendung danach doch fertig, LAG der Prozessor am Track und veroeffentlichte den
 * bearbeiteten Track, waehrend der Controller `FAILED_FALLBACK`/`applied = OFF` meldete und "die Kamera
 * laeuft ohne Effekt weiter" sagte. Dagegen zwei Mittel: bei `TIMEOUT` (und bei jedem durch [dispose] oder
 * eine neuere Anwendung ueberholten Versuch) wird ein UNBEGRENZTES, losgeloestes `stopProcessor` geplant,
 * und ein ueberholter Versuch meldet seinen Erfolg nicht mehr.
 */
internal class ConferenceBackgroundController(
    private val notifyFailure: (String) -> Unit,
    /** Nach jedem `setProcessor`/`stopProcessor` aufrufen: ein `srcObject`-Tausch pausiert `<video>`
     * (V1.4.19-Watchdog-Ursache). Die Aufrufstelle uebergibt `resumeStalledVideos` ueber das Call-Panel. */
    private val onProcessedStreamSwapped: () -> Unit,
    /** Sichtbaren Zustand nachziehen (Kachel-Haekchen, aria-checked, Knopfbeschriftung, Ladezeile). */
    private val onStateChanged: (ConferenceBackgroundState) -> Unit,
    /** Test seam: defaults to the real browser gate. */
    val supported: Boolean = conferenceBackgroundSupportedInThisBrowser(),
    /** Test seam: builds a processor for [effect]; defaults to the real `@livekit/track-processors` wrapper. */
    private val processorFactory: (ConferenceBackgroundEffect) -> BackgroundProcessorHandle = ::createRealBackgroundProcessor,
    /** Test seam: defaults to [CONFERENCE_BACKGROUND_APPLY_TIMEOUT_MS]. */
    private val applyTimeoutMs: Long = CONFERENCE_BACKGROUND_APPLY_TIMEOUT_MS,
    /** Test seam: laedt das Hintergrundbild vorab, wirft bei Misserfolg (siehe [probeBackgroundImage]). */
    private val imageProbe: suspend (String) -> Unit = ::probeBackgroundImage,
    /**
     * Test seam: startet ein UNBEGRENZTES, losgeloestes Aufraeumen (Audit-Befund M4/B1). Produktion:
     * [AppScope] -- bewusst nicht der Aufrufer-Scope, weil genau die Faelle abgedeckt werden, in denen der
     * Aufrufer schon weitergegangen ist (Timeout, Verlassen).
     */
    private val launchDetachedCleanup: (suspend () -> Unit) -> Unit = { block -> AppScope.launch { block() } },
    /** Test seam: defaults to [BACKGROUND_DISPOSE_STOP_TIMEOUT_MS]. */
    private val disposeStopTimeoutMs: Long = BACKGROUND_DISPOSE_STOP_TIMEOUT_MS,
) {
    var state: ConferenceBackgroundState = ConferenceBackgroundState()
        private set

    private val mutex = Mutex()
    private var wrapper: BackgroundProcessorHandle? = null

    /**
     * Wird bei jedem Anwendungsversuch UND bei [dispose] erhoeht. Ein Versuch, dessen Generation beim
     * Fertigwerden nicht mehr die aktuelle ist, wurde ueberholt: sein Ergebnis wird weder gemeldet noch
     * behalten, sein (moeglicherweise spaet doch gesetzter) Prozessor wird losgeloest abgeraeumt.
     */
    private var generation: Int = 0

    /**
     * Ab [dispose] endgueltig: keine neue Anwendung mehr auf einem Track dieser Sitzung. Die Zusage wird an
     * DREI Stellen eingehalten, weil jede davon hinter einem Aussetzungspunkt liegen kann -- beim Betreten
     * des Mutex in [select]/[onLocalCameraTrack], am Anfang von [applyLocked] (dort auch nach dem
     * suspendierenden `removeProcessor` des "Aus"-Zweigs) und nach dem Bildladen, bevor ein Prozessor gebaut
     * oder angehaengt wird.
     */
    private var disposed: Boolean = false

    private fun transition(event: ConferenceBackgroundEvent) {
        state = conferenceBackgroundReduce(state, event)
        onStateChanged(state)
    }

    /** Liest `localStorage` (try/catch), parst gegen die Whitelist, setzt `desired`. Wendet NICHTS an. */
    fun restoreDesiredFromStorage() {
        // Nicht unterstuetzt: keine Absicht wiederherstellen -- sonst zeigte die deaktivierte "Mehr"-Zeile
        // einen Effekt, den der Nutzer weder bedienen noch loeschen kann.
        if (!supported) return
        val raw =
            try {
                localStorage[CONFERENCE_BACKGROUND_STORAGE_KEY]
            } catch (e: Throwable) {
                null
            }
        val effect = parseStoredBackgroundEffect(raw) ?: ConferenceBackgroundEffect.OFF
        state = state.copy(desired = effect)
        onStateChanged(state)
    }

    private fun persist(effect: ConferenceBackgroundEffect) {
        try {
            val value = conferenceBackgroundPersistValue(effect)
            if (value == null) {
                localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
            } else {
                localStorage[CONFERENCE_BACKGROUND_STORAGE_KEY] = value
            }
        } catch (e: Throwable) {
            // Storage gesperrt -- der Effekt gilt dann nur fuer diese Sitzung.
        }
    }

    /**
     * Nutzerklick auf eine Kachel. Persistiert die Absicht (OFF -> `removeItem`) und wendet an. [track] ist
     * `null`, solange die Kamera aus ist oder noch nichts publiziert wurde -- dann bleibt nur die Absicht
     * gespeichert und der Effekt kommt mit dem naechsten Kamera-Track ([onLocalCameraTrack]).
     */
    suspend fun select(
        effect: ConferenceBackgroundEffect,
        track: BackgroundTrack?,
    ) {
        if (!supported || disposed) return
        mutex.withLock {
            if (disposed) return
            transition(ConferenceBackgroundEvent.UserSelected(effect))
            persist(effect)
            if (track == null) {
                // Kein Track -- Absicht steht, angewendet wird spaeter. Phase beruhigen, damit die Ladezeile nicht haengt.
                transition(ConferenceBackgroundEvent.ProcessorLost)
                return
            }
            applyLocked(track, effect)
        }
    }

    /**
     * Neuer/erneut publizierter lokaler Kamera-Track -- wendet [conferenceBackgroundEffectForNewTrack] an.
     * Idempotent: liegt unser Wrapper bereits am Track und entspricht der angewendete Effekt dem
     * gewuenschten, passiert nichts. (Ein gesetzter Prozessor ueberlebt `switchActiveDevice`/`restartTrack`/
     * Kamera-Aus-An ohnehin von selbst -- `setMediaStreamTrack` ruft `processor.restart()`; dieser Pfad ist
     * Guertel und Hosentraeger, kein Hauptmechanismus.)
     */
    suspend fun onLocalCameraTrack(track: BackgroundTrack) {
        if (!supported || disposed) return
        mutex.withLock {
            if (disposed) return
            transition(ConferenceBackgroundEvent.NewLocalTrack)
            val effect = conferenceBackgroundEffectForNewTrack(state)
            if (effect == ConferenceBackgroundEffect.OFF) return
            val current = wrapper
            if (current != null && track.currentProcessor() == current && state.applied == effect) return
            applyLocked(track, effect)
        }
    }

    /**
     * Beim Verlassen der Konferenz: Wrapper verwerfen, laufende/spaet fertig werdende Anwendungen
     * entwerten, `stopProcessor(false)` versuchen.
     *
     * **Nimmt bewusst den [mutex] NICHT** (Audit-Befund B1, siehe Klassen-KDoc): eine laufende Anwendung
     * haelt ihn bis zu `applyTimeoutMs`; ein Verlassen darf darauf niemals warten, weil Kamera und Mikrofon
     * bis dahin weiter senden. Die Entwertung ueber [generation] ersetzt die Serialisierung: der laufende
     * Versuch darf seinen Erfolg danach weder melden noch behalten. Erreicht das gedeckelte `stopProcessor`
     * (hinter livekits `trackChangeLock`) sein Ziel nicht rechtzeitig, wird es losgeloest und unbegrenzt
     * nachgezogen -- kein Zombie-Prozessor.
     */
    suspend fun dispose(track: BackgroundTrack?) {
        disposed = true
        generation++
        wrapper = null
        if (track != null) {
            val stopped = withTimeoutOrNull(disposeStopTimeoutMs) { runCatching { track.stopProcessor() } }
            if (stopped == null) launchDetachedCleanup { runCatching { track.stopProcessor() } }
        }
        transition(ConferenceBackgroundEvent.ProcessorLost)
    }

    private suspend fun applyLocked(
        track: BackgroundTrack,
        effect: ConferenceBackgroundEffect,
    ) {
        // Folge-Audit, Punkt 2: kein Anwendungsschritt und keine sichtbare `APPLYING`-Phase mehr, nachdem
        // [dispose] gelaufen ist -- erst damit stimmt die Zusage von [disposed]. Die Aufrufer pruefen das
        // bereits beim Betreten des Mutex; dies ist die Pruefung an der Stelle, die sie einhalten MUSS.
        if (disposed) return
        if (effect == ConferenceBackgroundEffect.OFF) {
            removeProcessor(track)
            if (disposed) return // removeProcessor suspendiert -- dispose kann inzwischen gelaufen sein
            transition(ConferenceBackgroundEvent.ApplySucceeded(ConferenceBackgroundEffect.OFF))
            onProcessedStreamSwapped()
            return
        }
        if (wrapper != null && track.currentProcessor() == wrapper && state.applied == effect) {
            transition(ConferenceBackgroundEvent.ApplySucceeded(effect)) // Klick auf den bereits aktiven Effekt
            return
        }
        transition(ConferenceBackgroundEvent.ApplyStarted)
        val attempt = ++generation
        val imagePath = conferenceBackgroundImagePath(effect)
        var failure: ConferenceBackgroundFailure? = null
        try {
            val existing = wrapper
            val finished =
                withTimeoutOrNull(applyTimeoutMs) {
                    // Audit-Befund M1: Bild ZUERST im eigenen Code laden. Die Bibliothek verschluckt einen
                    // Bildfehler in `init` und meldet Erfolg -- ein 404 wuerde sonst als schwarzer
                    // Hintergrund erscheinen statt als Rueckfall auf "Aus".
                    if (imagePath != null) {
                        try {
                            imageProbe(imagePath)
                        } catch (e: Throwable) {
                            failure = ConferenceBackgroundFailure.LOAD_FAILED
                            throw e
                        }
                    }
                    // Folge-Audit, Punkt 1: das Bildladen ist ein Aussetzungspunkt -- ist waehrenddessen
                    // [dispose] gelaufen, darf hier NICHTS mehr gebaut oder an den Track gehaengt werden.
                    // Vorher wurde erst nach dem Anhaengen geprueft und der Prozessor anschliessend wieder
                    // abgeraeumt; das ist unnoetig (WASM-Aufbau, Kamera-Umschaltung) und laesst ein
                    // Zeitfenster, in dem der bearbeitete Track nach dem Verlassen noch veroeffentlicht wird.
                    // Der Ausstieg faellt in die Ueberholt-Pruefung unten, die nichts meldet und nichts
                    // behaelt. `generation` kann hier nur durch [dispose] abweichen: eine neuere Anwendung
                    // braeuchte den Mutex, den dieser Aufruf noch haelt.
                    if (attempt != generation) return@withTimeoutOrNull true
                    if (existing != null && track.currentProcessor() == existing) {
                        // Effektwechsel innerhalb einer Episode: kein Neuaufbau (Modell/WASM bleiben geladen).
                        try {
                            existing.switchTo(effect)
                        } catch (e: Throwable) {
                            failure =
                                if (imagePath != null) {
                                    ConferenceBackgroundFailure.LOAD_FAILED
                                } else {
                                    ConferenceBackgroundFailure.APPLY_FAILED
                                }
                            throw e
                        }
                        if (backgroundImageMissing(existing, imagePath)) {
                            failure = ConferenceBackgroundFailure.LOAD_FAILED
                            throw IllegalStateException("Hintergrundbild liegt nach dem Umschalten nicht am Transformer")
                        }
                    } else {
                        val created = processorFactory(effect)
                        wrapper = created
                        try {
                            track.setProcessor(created)
                        } catch (e: Throwable) {
                            failure = ConferenceBackgroundFailure.LOAD_FAILED
                            throw e
                        }
                        if (backgroundImageMissing(created, imagePath)) {
                            failure = ConferenceBackgroundFailure.LOAD_FAILED
                            throw IllegalStateException("Hintergrundbild liegt nach setProcessor nicht am Transformer")
                        }
                    }
                    true
                }
            if (finished == null) failure = ConferenceBackgroundFailure.TIMEOUT
        } catch (e: Throwable) {
            if (failure == null) failure = ConferenceBackgroundFailure.APPLY_FAILED
        }
        // Audit-Befund M4/B1: ueberholt (dispose -- eine neuere Anwendung braeuchte den noch gehaltenen
        // Mutex)? Dann NICHTS melden, nichts behalten -- und unbegrenzt aufraeumen, weil ein spaet doch
        // fertig gewordener Prozessor sonst am Track haengen und den bearbeiteten Track weiter
        // veroeffentlichen wuerde. Greift auch fuer den frueh ausgestiegenen Fall oben (dann ist das
        // `stopProcessor` ein no-op, weil nie etwas gesetzt wurde).
        if (attempt != generation) {
            wrapper = null
            launchDetachedCleanup { runCatching { track.stopProcessor() } }
            return
        }
        val failed = failure
        if (failed != null) {
            handleFailure(track, failed)
        } else {
            transition(ConferenceBackgroundEvent.ApplySucceeded(effect))
            onProcessedStreamSwapped()
        }
    }

    /**
     * Nachbedingung fuer die sechs `BG_*`-Effekte (Audit-Befund M1, zweite Haelfte): haelt der Transformer
     * wirklich das angeforderte Bild? `null`/abweichender Pfad bedeutet, dass `BackgroundTransformer.init`
     * den Ladefehler verschluckt hat. Fuer Weichzeichnen und "Aus" ([imagePath] `null`) gibt es nichts zu
     * pruefen -- `backgroundImageAndPath` wird beim Umschalten auf Weichzeichnen bewusst nicht geleert.
     */
    private fun backgroundImageMissing(
        handle: BackgroundProcessorHandle,
        imagePath: String?,
    ): Boolean = imagePath != null && handle.appliedBackgroundImagePath() != imagePath

    private suspend fun handleFailure(
        track: BackgroundTrack,
        failure: ConferenceBackgroundFailure,
    ) {
        // Auch nach einem setProcessor-Reject: raeumt einen spaet doch fertig gewordenen Prozessor ab;
        // ohne gesetzten Prozessor ist stopProcessor ein no-op. Nie wiederverwenden, immer neu bauen.
        removeProcessor(track)
        if (failure == ConferenceBackgroundFailure.TIMEOUT) {
            // Audit-Befund M4: nach einem Timeout laeuft das JS-Promise weiter und haelt livekits
            // `trackChangeLock` -- das gedeckelte `stopProcessor` in `removeProcessor` wartet dahinter und
            // laeuft in sein eigenes 3-s-Limit. Deshalb zusaetzlich ein UNBEGRENZTES, losgeloestes
            // Aufraeumen: wird die Anwendung spaeter doch fertig, wird der Prozessor danach abgeraeumt und
            // nie gemeldet oder behalten.
            launchDetachedCleanup { runCatching { track.stopProcessor() } }
        }
        val notify = conferenceBackgroundShouldNotify(state, failure)
        transition(ConferenceBackgroundEvent.ApplyFailed(failure))
        onProcessedStreamSwapped()
        if (notify) notifyFailure(conferenceBackgroundFailureMessage(failure))
    }

    private suspend fun removeProcessor(track: BackgroundTrack) {
        wrapper = null
        withTimeoutOrNull(BACKGROUND_STOP_TIMEOUT_MS) { runCatching { track.stopProcessor() } }
    }
}
