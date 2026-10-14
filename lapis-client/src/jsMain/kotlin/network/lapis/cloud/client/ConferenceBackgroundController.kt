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
import network.lapis.cloud.client.livekit.ProcessorWrapper
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
    /**
     * V1.9.129 -- what this processor does: the segmenting processor (background effect, with the overlay hook inside) or the overlay-only
     * one. Defaults to [VideoProcessorKind.SEGMENTING], so every processor that predates the overlays keeps its meaning.
     */
    val kind: VideoProcessorKind get() = VideoProcessorKind.SEGMENTING

    /** In-place effect change within one episode (no rebuild). Rejects/throws on failure. */
    suspend fun switchTo(choice: ConferenceBackgroundChoice)

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

/**
 * V1.9.129 -- which processor sits on the camera track. [NONE]: none (raw camera). [OVERLAY_ONLY]: only the overlay drawing step -- NO
 * MediaPipe, no WASM, no WebGL (the library's `disabled` mode would still load ~19 MB). [SEGMENTING]: the background processor; the overlay
 * hook is inside it, so one pipeline serves both.
 */
internal enum class VideoProcessorKind { NONE, OVERLAY_ONLY, SEGMENTING }

/** Pure decision: no background effect and no overlay -> none; overlay without effect -> overlay only; any effect -> segmenting. */
internal fun videoProcessorKindFor(
    background: ConferenceBackgroundChoice,
    overlayActive: Boolean,
): VideoProcessorKind =
    when {
        background != CONFERENCE_BACKGROUND_OFF -> VideoProcessorKind.SEGMENTING
        overlayActive -> VideoProcessorKind.OVERLAY_ONLY
        else -> VideoProcessorKind.NONE
    }

/**
 * V1.9.129 -- what the controller needs from the overlay side: whether anything is to be drawn, an overlay-only processor, and the hook
 * for a freshly built segmenting processor. Production: [CompositorOverlayPort]; `null` in the controller means "no overlays" (and keeps
 * every pre-overlay test and call site unchanged).
 */
internal interface OverlayProcessorPort {
    val wanted: Boolean

    fun createOverlayOnlyHandle(): BackgroundProcessorHandle

    /** Puts the drawing step into [handle] (a segmenting processor) BEFORE it is initialised by `setProcessor`. */
    fun hookSegmenting(handle: BackgroundProcessorHandle)
}

/** Production handles are equal iff they wrap the same JS object -- whatever their [kind]. */
internal abstract class JsProcessorHandle(
    val raw: dynamic,
) : BackgroundProcessorHandle {
    override fun equals(other: Any?): Boolean = other is JsProcessorHandle && other.raw === raw

    /**
     * Konsistent zu [equals] (Identitaet des umhuellten JS-Objekts): Kotlin/JS vergibt pro Objekt einen
     * stabilen Identitaets-Hash, zwei Huellen um dasselbe `raw` liefern also denselben Wert. Die vorige
     * Konstante `0` war formal erlaubt, aber irrefuehrend (Audit-Befund N8).
     */
    override fun hashCode(): Int = raw.hashCode()
}

/** Production handle around the `@livekit/track-processors` wrapper; equal iff it wraps the same JS object. */
internal class RealBackgroundProcessor(
    raw: BackgroundProcessorWrapper,
) : JsProcessorHandle(raw) {
    val wrapper: BackgroundProcessorWrapper get() = raw.unsafeCast<BackgroundProcessorWrapper>()

    override suspend fun switchTo(choice: ConferenceBackgroundChoice) {
        wrapper.switchTo(switchBackgroundOptionsFor(choice)).await()
    }

    override fun appliedBackgroundImagePath(): String? = runCatching { wrapper.transformer.backgroundImageAndPath?.path }.getOrNull()
}

/** V1.9.129 -- handle of the overlay-only processor (a plain `ProcessorWrapper` around a duck-typed transformer). */
internal class OverlayOnlyProcessorHandle(
    raw: ProcessorWrapper,
) : JsProcessorHandle(raw) {
    override val kind: VideoProcessorKind get() = VideoProcessorKind.OVERLAY_ONLY

    override suspend fun switchTo(choice: ConferenceBackgroundChoice) {
        error("the overlay-only processor has no background effect to switch")
    }

    override fun appliedBackgroundImagePath(): String? = null
}

/** The production [OverlayProcessorPort]: processors whose transformer hands every frame to [compositor]. */
internal class CompositorOverlayPort(
    private val compositor: VideoOverlayCompositor,
) : OverlayProcessorPort {
    override val wanted: Boolean get() = compositor.hasScene

    override fun createOverlayOnlyHandle(): BackgroundProcessorHandle {
        val transformer = createOverlayOnlyTransformer(overlayFrameProcessor(compositor))
        val wrapper = ProcessorWrapper(transformer, "lapis-video-overlay", obj<dynamic> { maxFps = CONFERENCE_BACKGROUND_MAX_FPS })
        return OverlayOnlyProcessorHandle(wrapper)
    }

    override fun hookSegmenting(handle: BackgroundProcessorHandle) {
        val real = handle as? RealBackgroundProcessor ?: return
        installTransformHook(real.wrapper.transformer, overlayFrameProcessor(compositor))
    }
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
            handle as? JsProcessorHandle
                ?: error("LiveKitBackgroundTrack erwartet einen JsProcessorHandle, bekam ${handle::class.simpleName}")
        // Positional (Kotlin verbietet benannte Argumente bei external-Aufrufen):
        // setProcessor(processor, showProcessedStreamLocally = true) -- die eigene Selbstansicht IST die
        // Vorschau, bereits attachte Elemente bekommen den bearbeiteten Track. livekit-client initialisiert den NEUEN Prozessor zuerst
        // und stoppt den bisherigen erst danach (LocalVideoTrack.setProcessor) -- ein Wechsel der Art braucht deshalb kein vorheriges Stoppen.
        track.setProcessor(real.raw.unsafeCast<BackgroundProcessorWrapper>(), true).await()
    }

    override suspend fun stopProcessor() {
        track.stopProcessor(false).await()
    }

    override fun currentProcessor(): BackgroundProcessorHandle? =
        track.getProcessor()?.let { raw ->
            // The overlay-only wrapper is a plain `ProcessorWrapper` (no `switchTo`); the segmenting one is the library's own subclass.
            if (raw.asDynamic().switchTo ==
                undefined
            ) {
                OverlayOnlyProcessorHandle(raw.unsafeCast<ProcessorWrapper>())
            } else {
                RealBackgroundProcessor(raw)
            }
        }
}

private fun switchBackgroundOptionsFor(choice: ConferenceBackgroundChoice): SwitchBackgroundProcessorOptions {
    val imagePath = conferenceBackgroundImagePath(choice)
    val blurRadius = conferenceBackgroundBlurRadius(choice)
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

private fun createRealBackgroundProcessor(choice: ConferenceBackgroundChoice): BackgroundProcessorHandle {
    val imagePath = conferenceBackgroundImagePath(choice)
    val blurRadius = conferenceBackgroundBlurRadius(choice)
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
    /** Test seam: builds a processor for [choice]; defaults to the real `@livekit/track-processors` wrapper. */
    private val processorFactory: (ConferenceBackgroundChoice) -> BackgroundProcessorHandle = ::createRealBackgroundProcessor,
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
    /**
     * V1.9.129 "Video-Overlays" -- the overlay side (`null` = no overlays at all, the behaviour before V1.9.129). The overlay drawing step
     * lives INSIDE the processor of this controller (ONE processor per track): [OverlayProcessorPort.hookSegmenting] puts it into every
     * segmenting processor, [OverlayProcessorPort.createOverlayOnlyHandle] builds the processor for "overlay without background effect"
     * (no MediaPipe). A failure of the overlay side never touches [state] -- the background keeps its own phase.
     */
    private val overlayPort: OverlayProcessorPort? = null,
    /** V1.9.129 -- the overlay processor itself could not be applied (the camera then runs without overlays). */
    private val onOverlayFailure: (VideoOverlayFailure) -> Unit = {},
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

    /**
     * V1.9.4 -- `true` sobald das erste `listMine()` (Erfolg ODER Fehlschlag) fuer diese Sitzung
     * zurueck ist. Solange das nicht der Fall ist, wendet [onLocalCameraTrack] eine gespeicherte
     * [ConferenceBackgroundChoice.Custom]-Wahl NICHT automatisch an -- ohne diese Bremse wuerde ein
     * inzwischen geloeschtes eigenes Bild beim Beitritt eine 404 und eine sichtbare `LOAD_FAILED`-
     * Meldung ausloesen, obwohl der Nutzer nichts falsch gemacht hat (siehe [reconcileCustomImages]
     * KDoc, Stolperfalle S3 des Umsetzungsplans).
     */
    private var customListKnown: Boolean = false

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
        val choice = parseStoredBackgroundChoice(raw) ?: CONFERENCE_BACKGROUND_OFF
        state = state.copy(desired = choice)
        onStateChanged(state)
    }

    private fun persist(choice: ConferenceBackgroundChoice) {
        try {
            val value = conferenceBackgroundPersistValue(choice)
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
        choice: ConferenceBackgroundChoice,
        track: BackgroundTrack?,
    ) {
        if (!supported || disposed) return
        mutex.withLock {
            if (disposed) return
            transition(ConferenceBackgroundEvent.UserSelected(choice))
            persist(choice)
            if (track == null) {
                // Kein Track -- Absicht steht, angewendet wird spaeter. Phase beruhigen, damit die Ladezeile nicht haengt.
                transition(ConferenceBackgroundEvent.ProcessorLost)
                return
            }
            applyLocked(track, choice)
        }
    }

    /**
     * Neuer/erneut publizierter lokaler Kamera-Track -- wendet [conferenceBackgroundChoiceForNewTrack] an.
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
            val choice = conferenceBackgroundChoiceForNewTrack(state)
            if (choice == CONFERENCE_BACKGROUND_OFF) {
                // V1.9.129: no background effect, but possibly overlays -- an idempotent (re)application of the overlay-only processor.
                if (overlayPort?.wanted == true) ensureOverlayOnlyLocked(track)
                return
            }
            // S3 (Umsetzungsplan): eine gespeicherte EIGENE Wahl wird erst angewendet, sobald
            // reconcileCustomImages() einmal gelaufen ist -- sonst wendet ein Beitritt ein
            // inzwischen geloeschtes Bild an und ergibt eine 404/LOAD_FAILED-Meldung fuer etwas,
            // das der Nutzer nicht falsch gemacht hat. Eingebaute Effekte sind davon nicht
            // betroffen (sie koennen nie "geloescht" werden).
            if (choice is ConferenceBackgroundChoice.Custom && !customListKnown) return
            val current = wrapper
            if (current != null && track.currentProcessor() == current && state.applied == choice) return
            applyLocked(track, choice)
        }
    }

    /**
     * V1.9.4 -- gleicht die Absicht mit der tatsaechlichen Liste der eigenen Bilder ab, einmal pro
     * Beitritt (siehe `ConferenceScreen.kt` Aufrufstelle direkt nach dem Controller-Aufbau).
     *
     * - [ids] `null` bedeutet: der RPC-Aufruf ist gescheitert. Die Absicht bleibt stehen (kein
     *   stiller Rueckfall auf blosse Netzwerk-Flakiness), [customListKnown] wird trotzdem gesetzt
     *   (sonst wuerde ein dauerhaft fehlschlagender Aufruf [onLocalCameraTrack] fuer immer sperren)
     *   und danach normal angewendet -- ein 404 beim eigentlichen Bildladen faellt dann in den
     *   regulaeren `LOAD_FAILED`-Pfad.
     * - Ist [ConferenceBackgroundState.desired] ein [ConferenceBackgroundChoice.Custom], dessen id
     *   NICHT in [ids] steht (geloescht, auf einem anderen Geraet oder von einem anderen Ort aus),
     *   faellt der Automat STILL auf "Aus" zurueck: kein `notifyFailure`, der `localStorage`-
     *   Schluessel wird entfernt, ein evtl. bereits gesetzter Prozessor wird abgebaut. Ein
     *   inzwischen geloeschtes Bild ist kein Fehler, den man dem Nutzer melden muesste (Tesler/
     *   Norman: ehrlich, aber nicht alarmierend).
     * - Sonst, wenn ein Track vorliegt und die Absicht (weiterhin) ein AUSSTEHENDES `Custom` ist,
     *   wird ganz normal angewendet.
     *
     * Laeuft unter [mutex] wie jede andere Anwendung, respektiert [disposed].
     */
    suspend fun reconcileCustomImages(
        ids: Set<String>?,
        track: BackgroundTrack?,
    ) {
        if (!supported || disposed) return
        mutex.withLock {
            if (disposed) return
            customListKnown = true
            val desired = state.desired
            // Review-Befund (MAJOR): NUR ein bekannt geloeschtes Bild (ids != null UND die id fehlt darin)
            // faellt still auf "Aus" zurueck. Ein gescheiterter Listenabruf (ids == null) darf NICHT
            // denselben fruehen Ausstieg nehmen -- die KDoc oben sagt ausdruecklich "normal angewendet",
            // ein spaeterer 404 beim Bildladen faellt dann in den regulaeren LOAD_FAILED-Pfad. Der vorige
            // `ids == null ||`-Zweig kehrte fuer diesen Fall aber schon hier zurueck, ohne je anzuwenden --
            // ein unsichtbarer Modus (Tesler-Regel): der Umschalter zeigte weiter "Hintergrund: Eigenes
            // Bild", waehrend die Kamera ohne jeden Effekt lief.
            if (desired is ConferenceBackgroundChoice.Custom && ids != null && desired.imageId !in ids) {
                // Bekannt geloescht (nicht nur ein gescheiterter Listenabruf) -- still zuruecksetzen.
                transition(ConferenceBackgroundEvent.UserSelected(CONFERENCE_BACKGROUND_OFF))
                try {
                    localStorage.removeItem(CONFERENCE_BACKGROUND_STORAGE_KEY)
                } catch (e: Throwable) {
                    // Storage gesperrt -- ohne Bedeutung, die Absicht ist bereits im Speicher zurueckgesetzt.
                }
                if (track != null && wrapper != null) removeProcessor(track)
                transition(ConferenceBackgroundEvent.ProcessorLost)
                if (track != null) restoreOverlayAfterBackgroundRemovedLocked(track)
                return
            }
            if (track != null && desired is ConferenceBackgroundChoice.Custom) {
                applyLocked(track, desired)
            }
        }
    }

    /**
     * V1.9.4 -- das gerade geloeschte Bild ([imageId]) ist entweder die Absicht oder tatsaechlich
     * angewendet: auf "Aus" wechseln (derselbe Pfad wie ein bewusster Klick auf die Aus-Kachel).
     * Kein-op, wenn ein ANDERES Bild geloescht wird.
     */
    suspend fun onCustomImageDeleted(
        imageId: String,
        track: BackgroundTrack?,
    ) {
        val desired = state.desired
        val applied = state.applied
        val affectsDesired = desired is ConferenceBackgroundChoice.Custom && desired.imageId == imageId
        val affectsApplied = applied is ConferenceBackgroundChoice.Custom && applied.imageId == imageId
        if (affectsDesired || affectsApplied) {
            select(choice = CONFERENCE_BACKGROUND_OFF, track = track)
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
        choice: ConferenceBackgroundChoice,
    ) {
        // Folge-Audit, Punkt 2: kein Anwendungsschritt und keine sichtbare `APPLYING`-Phase mehr, nachdem
        // [dispose] gelaufen ist -- erst damit stimmt die Zusage von [disposed]. Die Aufrufer pruefen das
        // bereits beim Betreten des Mutex; dies ist die Pruefung an der Stelle, die sie einhalten MUSS.
        if (disposed) return
        if (choice == CONFERENCE_BACKGROUND_OFF) {
            if (overlayPort?.wanted == true) {
                // V1.9.129: "no effect" but overlays are wanted -- the processor becomes the overlay-only one (no MediaPipe, no WASM).
                applyOverlayOnlyLocked(track)
            } else {
                removeProcessor(track)
            }
            if (disposed) return // removeProcessor suspendiert -- dispose kann inzwischen gelaufen sein
            transition(ConferenceBackgroundEvent.ApplySucceeded(CONFERENCE_BACKGROUND_OFF))
            onProcessedStreamSwapped()
            return
        }
        if (wrapper != null && track.currentProcessor() == wrapper && state.applied == choice) {
            transition(ConferenceBackgroundEvent.ApplySucceeded(choice)) // Klick auf den bereits aktiven Effekt
            return
        }
        transition(ConferenceBackgroundEvent.ApplyStarted)
        val attempt = ++generation
        val imagePath = conferenceBackgroundImagePath(choice)
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
                    if (existing != null && existing.kind == VideoProcessorKind.SEGMENTING && track.currentProcessor() == existing) {
                        // Effektwechsel innerhalb einer Episode: kein Neuaufbau (Modell/WASM bleiben geladen).
                        try {
                            existing.switchTo(choice)
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
                        val created = processorFactory(choice)
                        // V1.9.129: the overlay drawing step goes into the NEW segmenting processor before the library initialises it.
                        overlayPort?.hookSegmenting(created)
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
            transition(ConferenceBackgroundEvent.ApplySucceeded(choice))
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
        val timedOut = failure == ConferenceBackgroundFailure.TIMEOUT
        if (timedOut) {
            // Audit-Befund M4: nach einem Timeout laeuft das JS-Promise weiter und haelt livekits
            // `trackChangeLock` -- das gedeckelte `stopProcessor` in `removeProcessor` wartet dahinter und
            // laeuft in sein eigenes 3-s-Limit. Deshalb zusaetzlich ein UNBEGRENZTES, losgeloestes
            // Aufraeumen: wird die Anwendung spaeter doch fertig, wird der Prozessor danach abgeraeumt und
            // nie gemeldet oder behalten.
            //
            // Review-Befund: `setProcessor` und `stopProcessor` teilen sich livekits FIFO-`trackChangeLock`.
            // Wuerde der Overlay-only-Prozessor HIER sofort gesetzt, stuende er in der Warteschlange VOR dem
            // losgeloesten Stop, und der Stop risse ihn gleich wieder herunter (Kachel zeigt "So sehen andere
            // Sie", die Kamera sendet aber keine Overlays). Darum wird die Wiederherstellung erst NACH dem
            // abgeschlossenen Stop angestossen -- unter dem Mutex und mit allen Pruefungen von
            // [onOverlayChanged] (dispose, laufende Anwendung, bereits gesetzter Prozessor).
            launchDetachedCleanup {
                runCatching { track.stopProcessor() }
                runCatching { onOverlayChanged(track) }
            }
        }
        val notify = conferenceBackgroundShouldNotify(state, failure)
        transition(ConferenceBackgroundEvent.ApplyFailed(failure))
        // V1.9.129: a failed background effect must not take the overlays down with it.
        // (Bei TIMEOUT erst nach dem losgeloesten Stop, siehe oben.)
        if (!timedOut) restoreOverlayAfterBackgroundRemovedLocked(track)
        onProcessedStreamSwapped()
        if (notify) notifyFailure(conferenceBackgroundFailureMessage(failure))
    }

    /**
     * V1.9.129 -- the overlay side changed what it wants to draw (a scene appeared, changed its kind, or vanished). Called by the overlay
     * controller AFTER it handed the new scene to the compositor. [track] is `null` while the camera is off: then nothing is applied now,
     * [onLocalCameraTrack] does it with the next track. Runs under the same [mutex] as every other application.
     *
     * - background effect active: the hook is already inside the segmenting processor, the scene swap alone is enough;
     * - no effect, overlays wanted: make sure the overlay-only processor is on the track;
     * - no effect, nothing wanted: take a (now useless) overlay-only processor off the track.
     */
    suspend fun onOverlayChanged(track: BackgroundTrack?) {
        val port = overlayPort ?: return
        if (!supported || disposed || track == null) return
        mutex.withLock {
            if (disposed) return
            if (state.phase == ConferenceBackgroundPhase.APPLYING) return // that application decides the processor itself
            val kind = videoProcessorKindFor(state.applied, port.wanted)
            val current = wrapper
            when (kind) {
                VideoProcessorKind.SEGMENTING -> Unit
                VideoProcessorKind.OVERLAY_ONLY -> ensureOverlayOnlyLocked(track)
                VideoProcessorKind.NONE ->
                    if (current != null && current.kind == VideoProcessorKind.OVERLAY_ONLY) {
                        removeProcessor(track)
                        if (!disposed) onProcessedStreamSwapped()
                    }
            }
        }
    }

    /** Idempotent: the overlay-only processor is already on [track], or it is put there. */
    private suspend fun ensureOverlayOnlyLocked(track: BackgroundTrack) {
        val current = wrapper
        if (current != null && current.kind == VideoProcessorKind.OVERLAY_ONLY && track.currentProcessor() == current) return
        applyOverlayOnlyLocked(track)
        if (!disposed) onProcessedStreamSwapped()
    }

    /**
     * Builds the overlay-only processor and sets it on [track] (replacing whatever processor sat there: the library initialises the new one
     * first and stops the old one afterwards). A failure or timeout is reported ONLY to the overlay side ([onOverlayFailure]); the camera
     * keeps running without overlays and the stale processor is cleaned up.
     */
    private suspend fun applyOverlayOnlyLocked(track: BackgroundTrack) {
        val port = overlayPort ?: return
        if (disposed) return
        val attempt = ++generation
        val created = port.createOverlayOnlyHandle()
        wrapper = created
        var failed = false
        try {
            val finished = withTimeoutOrNull(applyTimeoutMs) { track.setProcessor(created) }
            if (finished == null) failed = true
        } catch (e: Throwable) {
            failed = true
        }
        if (attempt != generation) {
            // Overtaken (dispose): nothing is reported, nothing is kept.
            wrapper = null
            launchDetachedCleanup { runCatching { track.stopProcessor() } }
            return
        }
        if (failed) {
            removeProcessor(track)
            launchDetachedCleanup { runCatching { track.stopProcessor() } }
            onOverlayFailure(VideoOverlayFailure.DRAW_FAILED)
        }
    }

    /** After a background effect was removed or failed: if overlays are still wanted the camera gets the overlay-only processor. */
    private suspend fun restoreOverlayAfterBackgroundRemovedLocked(track: BackgroundTrack) {
        if (disposed || overlayPort?.wanted != true) return
        applyOverlayOnlyLocked(track)
    }

    private suspend fun removeProcessor(track: BackgroundTrack) {
        wrapper = null
        withTimeoutOrNull(BACKGROUND_STOP_TIMEOUT_MS) { runCatching { track.stopProcessor() } }
    }
}
