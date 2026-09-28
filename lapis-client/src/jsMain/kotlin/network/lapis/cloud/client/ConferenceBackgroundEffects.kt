package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr

/*
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- reine, DOM-freie Logik (jsTest-gedeckt): Whitelist,
 * Asset-Pfade, Persistenz-Wert, Unterstuetzungs-Gate und der Zustandsautomat. Das Anwenden auf den
 * LiveKit-Track (DOM/WebGL/WASM) steht in [ConferenceBackgroundController], die Oberflaeche in
 * `ConferenceScreen.kt`; Architektur und manueller Pruefplan: `docs/architecture/video-background-effects.adoc`.
 *
 * V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen": [ConferenceBackgroundChoice]
 * generalizes every "which background?" question from the nine-way [ConferenceBackgroundEffect]
 * whitelist to also include a member's OWN uploaded image, identified by server-issued id --
 * without ever letting an arbitrary string become a URL (see [conferenceBackgroundImagePath]'s own
 * KDoc "einzige Stelle, an der eine URL entsteht").
 *
 * **Ablageort**: bewusst direkt unter `.../client/` (flach) -- `ConferenceBackgroundI18nCatalogTest` scannt
 * dieses Verzeichnis nach Dateinamen, ohne Rekursion.
 *
 * **tr() vs. gettext()**: Texte, deren Ergebnis in ein ATTRIBUT, einen Toast oder als Argument in ein
 * weiteres `gettext(...)` fliesst, sind `gettext(...)` (`ClientTrAttributeLeakTest`). Texte, die DIREKT als
 * Widget-Inhalt an KVision gehen, sind `tr(...)` -- nur dann loest KVision den Marker im eigenen
 * Patch-Zyklus auf und uebersetzt sie bei einem Sprachwechsel zur Laufzeit neu (Audit-Befund N4). Deshalb
 * gibt es die Paare [conferenceBackgroundEffectLabel]/[conferenceBackgroundEffectLabelTr] und
 * [conferenceBackgroundToggleLabel]/[conferenceBackgroundToggleLabelTr]: gleiche Anzeige, unterschiedlicher
 * Verwendungsort.
 */

/**
 * Whitelist -- GENAU diese neun IDs; keine benutzerdefinierten URLs, keine ID ausserhalb dieser Liste
 * erreicht jemals die Bibliothek (Security: einziger Ort, an dem ein `imagePath` entsteht, ist
 * [conferenceBackgroundImagePath]).
 */
internal enum class ConferenceBackgroundEffect(
    val id: String,
) {
    OFF("off"),
    BLUR_LIGHT("blur-light"),
    BLUR_STRONG("blur-strong"),
    BG_WARM_GREY("bg-warm-grey"),
    BG_COOL_BLUE("bg-cool-blue"),
    BG_SAGE("bg-sage"),
    BG_SANDSTONE("bg-sandstone"),
    BG_MIDNIGHT("bg-midnight"),
    BG_STUDIO("bg-studio"),
}

/**
 * V1.9.4 -- generalizes the nine built-in [ConferenceBackgroundEffect]s and a member's own uploaded
 * image into one type every part of the state machine reasons about. [Custom.imageId] is ALWAYS a
 * canonical UUID string that has passed [CUSTOM_BACKGROUND_REGEX] -- see [conferenceBackgroundImagePath]
 * for the one place a URL is derived from it.
 */
internal sealed interface ConferenceBackgroundChoice {
    data class BuiltIn(
        val effect: ConferenceBackgroundEffect,
    ) : ConferenceBackgroundChoice

    data class Custom(
        val imageId: String,
    ) : ConferenceBackgroundChoice
}

internal val CONFERENCE_BACKGROUND_OFF = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.OFF)

/** Canonical lower-case UUID only -- no trim/lowercase applied anywhere ([parseStoredBackgroundChoice] KDoc "kein trim"). */
private val CUSTOM_BACKGROUND_REGEX =
    Regex("^custom:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

internal object ConferenceBackgroundAssets {
    /**
     * Muss identisch zu `mediaPipeTasksVisionVersion` in `lapis-client/build.gradle.kts` und zu
     * `MEDIAPIPE_TASKS_VISION_VERSION` in `ClientAssetRoutes.kt` (Server) sein -- `verifyMediaPipeVersion` erzwingt,
     * dass auch das installierte npm-Paket diese Version ist.
     */
    const val MEDIAPIPE_TASKS_VISION_VERSION = "0.10.14"

    /** Same-origin-Ersatz fuer `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm`. */
    const val TASKS_VISION_FILE_SET = "/assets/mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION/wasm"

    /** Same-origin-Ersatz fuer `https://storage.googleapis.com/mediapipe-models/.../selfie_segmenter.tflite`. */
    const val MODEL_ASSET_PATH = "/assets/mediapipe/selfie_segmenter.tflite"

    const val BACKGROUND_IMAGE_DIR = "/assets/video-backgrounds"
}

internal const val CONFERENCE_BACKGROUND_STORAGE_KEY = "lapis-cloud-conference-background"

/**
 * `blurRadius` ist KEIN Pixelmass: `@livekit/track-processors` rechnet intern `max(1, floor(radius / 4))`
 * (Downsample-Faktor 4), und der Shader klemmt auf `MAX_SAMPLES = 16` -- Werte >= 64 wirken nicht mehr.
 * 8 -> intern 2, 24 -> intern 6: exakt das Dreifache und damit ein echter, sichtbarer Abstand zwischen
 * "leicht" und "stark" (verifiziert in `src/webgl/index.ts` und `blurShader.ts` des Pakets).
 */
internal const val CONFERENCE_BLUR_RADIUS_LIGHT = 8
internal const val CONFERENCE_BLUR_RADIUS_STRONG = 24

/**
 * Nur im Fallback-Pfad wirksam (`canvas.captureStream`, Browser ohne `MediaStreamTrackProcessor` --
 * Firefox/Safari): Bibliotheks-Default 30, hier bewusst 15. Im modernen Pfad (Chrome/Edge) hat die
 * Bibliothek KEINE FPS-Drossel; dort folgt die Last der Kamera-Capture-Rate.
 */
internal const val CONFERENCE_BACKGROUND_MAX_FPS = 15

/**
 * Zeitdeckel fuer Laden + Setzen des Prozessors. Ein Kotlin-Coroutine-Timeout bricht das JS-Promise NICHT
 * ab -- die Aufrufstelle muss danach `stopProcessor()` aufrufen, damit ein spaet doch fertig werdender
 * Prozessor nicht verwaist.
 */
internal const val CONFERENCE_BACKGROUND_APPLY_TIMEOUT_MS = 10_000L

/**
 * Persistierter Wert -> Wahl. `null` fuer alles ausser einer exakten Whitelist-ID (leer, blank, falsche
 * Gross-/Kleinschreibung, Pfad, URL, unbekannt) UND fuer `"off"` -- der Aufrufer behandelt `null` als
 * [CONFERENCE_BACKGROUND_OFF]. Bewusst KEIN `trim()`/`lowercase()`: ein manipulierter Wert soll nie
 * "fast passen". V1.9.4: erkennt zusaetzlich `custom:<uuid>` -- ersetzt `parseStoredBackgroundEffect`.
 */
internal fun parseStoredBackgroundChoice(raw: String?): ConferenceBackgroundChoice? {
    if (raw == null) return null
    if (raw.startsWith("custom:")) {
        return if (CUSTOM_BACKGROUND_REGEX.matches(raw)) {
            ConferenceBackgroundChoice.Custom(raw.removePrefix("custom:"))
        } else {
            null
        }
    }
    val effect = ConferenceBackgroundEffect.entries.firstOrNull { it.id == raw } ?: return null
    return if (effect == ConferenceBackgroundEffect.OFF) null else ConferenceBackgroundChoice.BuiltIn(effect)
}

/**
 * Wert fuer `localStorage`: `null` == Schluessel LOESCHEN (nie `""` schreiben -- Lehre aus dem
 * `deviceId`-Bug, V1.4.19), sonst die Whitelist-ID bzw. `custom:<uuid>`.
 */
internal fun conferenceBackgroundPersistValue(choice: ConferenceBackgroundChoice): String? =
    when (choice) {
        is ConferenceBackgroundChoice.BuiltIn -> if (choice.effect == ConferenceBackgroundEffect.OFF) null else choice.effect.id
        is ConferenceBackgroundChoice.Custom -> "custom:${choice.imageId}"
    }

/**
 * Same-origin-Pfad des Hintergrundbilds. Fuer die sechs `BG_*`-Effekte ein statisches Asset; fuer
 * [ConferenceBackgroundChoice.Custom] `/api/conference-backgrounds/{uuid}/image` -- die ID wird HIER
 * erneut gegen [CUSTOM_BACKGROUND_REGEX] geprueft (Verteidigung in der Tiefe: selbst ein Aufrufer, der
 * [parseStoredBackgroundChoice] umgeht, kann keine beliebige URL erzeugen). Diese Funktion ist die
 * EINZIGE Stelle im ganzen Client, an der eine Hintergrundbild-URL entsteht.
 */
internal fun conferenceBackgroundImagePath(choice: ConferenceBackgroundChoice): String? =
    when (choice) {
        is ConferenceBackgroundChoice.Custom ->
            if (CUSTOM_BACKGROUND_REGEX.matches("custom:${choice.imageId}")) "/api/conference-backgrounds/${choice.imageId}/image" else null
        is ConferenceBackgroundChoice.BuiltIn ->
            when (choice.effect) {
                ConferenceBackgroundEffect.BG_WARM_GREY,
                ConferenceBackgroundEffect.BG_COOL_BLUE,
                ConferenceBackgroundEffect.BG_SAGE,
                ConferenceBackgroundEffect.BG_SANDSTONE,
                ConferenceBackgroundEffect.BG_MIDNIGHT,
                ConferenceBackgroundEffect.BG_STUDIO,
                -> "${ConferenceBackgroundAssets.BACKGROUND_IMAGE_DIR}/${choice.effect.id}.webp"
                ConferenceBackgroundEffect.OFF,
                ConferenceBackgroundEffect.BLUR_LIGHT,
                ConferenceBackgroundEffect.BLUR_STRONG,
                -> null
            }
    }

/** Thumbnail path for one of the member's OWN uploaded images -- only ever called with an id already validated by [CUSTOM_BACKGROUND_REGEX]. */
internal fun conferenceBackgroundThumbPath(imageId: String): String? =
    if (CUSTOM_BACKGROUND_REGEX.matches("custom:$imageId")) "/api/conference-backgrounds/$imageId/thumb" else null

internal fun conferenceBackgroundBlurRadius(choice: ConferenceBackgroundChoice): Int? =
    (choice as? ConferenceBackgroundChoice.BuiltIn)?.let { builtIn ->
        when (builtIn.effect) {
            ConferenceBackgroundEffect.BLUR_LIGHT -> CONFERENCE_BLUR_RADIUS_LIGHT
            ConferenceBackgroundEffect.BLUR_STRONG -> CONFERENCE_BLUR_RADIUS_STRONG
            else -> null
        }
    }

/**
 * Anzeigename. `gettext`, NIE `tr()` -- das Ergebnis wird von [conferenceBackgroundToggleLabel] als
 * Argument in ein weiteres `gettext(...)` gereicht (`ClientTrAttributeLeakTest`).
 */
internal fun conferenceBackgroundEffectLabel(effect: ConferenceBackgroundEffect): String =
    when (effect) {
        ConferenceBackgroundEffect.OFF -> gettext("Aus")
        ConferenceBackgroundEffect.BLUR_LIGHT -> gettext("Weichzeichnen leicht")
        ConferenceBackgroundEffect.BLUR_STRONG -> gettext("Weichzeichnen stark")
        ConferenceBackgroundEffect.BG_WARM_GREY -> gettext("Warmes Grau")
        ConferenceBackgroundEffect.BG_COOL_BLUE -> gettext("Kühles Blau")
        ConferenceBackgroundEffect.BG_SAGE -> gettext("Salbeigrün")
        ConferenceBackgroundEffect.BG_SANDSTONE -> gettext("Sandstein")
        ConferenceBackgroundEffect.BG_MIDNIGHT -> gettext("Nachtblau")
        ConferenceBackgroundEffect.BG_STUDIO -> gettext("Helles Studio")
    }

/**
 * Anzeigename fuer den SICHTBAREN Kachel-Text -- `tr(...)`, damit KVision den Marker im Patch-Zyklus
 * aufloest und bei einem Sprachwechsel neu uebersetzt (Audit-Befund N4). Identische msgids wie
 * [conferenceBackgroundEffectLabel]; NUR als Widget-Inhalt verwenden, nie in einem Attribut.
 */
internal fun conferenceBackgroundEffectLabelTr(effect: ConferenceBackgroundEffect): String =
    when (effect) {
        ConferenceBackgroundEffect.OFF -> tr("Aus")
        ConferenceBackgroundEffect.BLUR_LIGHT -> tr("Weichzeichnen leicht")
        ConferenceBackgroundEffect.BLUR_STRONG -> tr("Weichzeichnen stark")
        ConferenceBackgroundEffect.BG_WARM_GREY -> tr("Warmes Grau")
        ConferenceBackgroundEffect.BG_COOL_BLUE -> tr("Kühles Blau")
        ConferenceBackgroundEffect.BG_SAGE -> tr("Salbeigrün")
        ConferenceBackgroundEffect.BG_SANDSTONE -> tr("Sandstein")
        ConferenceBackgroundEffect.BG_MIDNIGHT -> tr("Nachtblau")
        ConferenceBackgroundEffect.BG_STUDIO -> tr("Helles Studio")
    }

/** V1.9.4 -- [conferenceBackgroundEffectLabel] generalized to [ConferenceBackgroundChoice]; a custom image is always "Eigenes Bild". */
internal fun conferenceBackgroundChoiceLabel(choice: ConferenceBackgroundChoice): String =
    when (choice) {
        is ConferenceBackgroundChoice.BuiltIn -> conferenceBackgroundEffectLabel(choice.effect)
        is ConferenceBackgroundChoice.Custom -> gettext("Eigenes Bild")
    }

/** V1.9.4 -- [conferenceBackgroundEffectLabelTr] generalized to [ConferenceBackgroundChoice]. */
internal fun conferenceBackgroundChoiceLabelTr(choice: ConferenceBackgroundChoice): String =
    when (choice) {
        is ConferenceBackgroundChoice.BuiltIn -> conferenceBackgroundEffectLabelTr(choice.effect)
        is ConferenceBackgroundChoice.Custom -> tr("Eigenes Bild")
    }

/**
 * Beschriftung der eingeklappten "Hintergrund"-Zeile -- der Zustand steht im Text (Tesler: kein unsichtbarer
 * Modus). `gettext` mit Platzhalter, deshalb NUR fuer Attribute (`title`) geeignet; der sichtbare Knopftext
 * kommt aus [conferenceBackgroundToggleLabelTr].
 */
internal fun conferenceBackgroundToggleLabel(choice: ConferenceBackgroundChoice): String =
    gettext("Hintergrund: %1", conferenceBackgroundChoiceLabel(choice))

/**
 * Dieselbe Beschriftung als `tr(...)`-Marker fuer den SICHTBAREN Knopftext (Audit-Befund N4). Bewusst zehn
 * (neun eingebaute + "Eigenes Bild") vollstaendige, eigene msgids statt einer Zusammensetzung: KVisions
 * `tr(...)` kennt keine Platzhalter, und ein zusammengesetzter `gettext`-Text wuerde bei einem
 * Sprachwechsel zur Laufzeit nie neu uebersetzt (die Aufloesung passiert ausschliesslich im
 * Patch-Zyklus, siehe `ClientTrAttributeLeakTest` KDoc). NUR als Widget-Inhalt verwenden.
 */
internal fun conferenceBackgroundToggleLabelTr(choice: ConferenceBackgroundChoice): String =
    when (choice) {
        is ConferenceBackgroundChoice.Custom -> tr("Hintergrund: Eigenes Bild")
        is ConferenceBackgroundChoice.BuiltIn ->
            when (choice.effect) {
                ConferenceBackgroundEffect.OFF -> tr("Hintergrund: Aus")
                ConferenceBackgroundEffect.BLUR_LIGHT -> tr("Hintergrund: Weichzeichnen leicht")
                ConferenceBackgroundEffect.BLUR_STRONG -> tr("Hintergrund: Weichzeichnen stark")
                ConferenceBackgroundEffect.BG_WARM_GREY -> tr("Hintergrund: Warmes Grau")
                ConferenceBackgroundEffect.BG_COOL_BLUE -> tr("Hintergrund: Kühles Blau")
                ConferenceBackgroundEffect.BG_SAGE -> tr("Hintergrund: Salbeigrün")
                ConferenceBackgroundEffect.BG_SANDSTONE -> tr("Hintergrund: Sandstein")
                ConferenceBackgroundEffect.BG_MIDNIGHT -> tr("Hintergrund: Nachtblau")
                ConferenceBackgroundEffect.BG_STUDIO -> tr("Hintergrund: Helles Studio")
            }
    }

/**
 * Sichtbarkeits-/Aktivierungs-Gate. [libraryReportsSupport] ist `supportsBackgroundProcessors()`;
 * [isSecureContext] ist `window.isSecureContext` -- ohne HTTPS gibt es weder Kamerazugriff noch einen
 * garantierten WebGL2-/WASM-Pfad, das Gate macht die Nichtunterstuetzung ehrlich statt sie erst im
 * Fehlerpfad zu zeigen.
 */
internal fun conferenceBackgroundEffectsSupported(
    libraryReportsSupport: Boolean,
    isSecureContext: Boolean,
): Boolean = libraryReportsSupport && isSecureContext

/**
 * Drei Zustaende statt eines `Boolean` (Audit-Befund M5): eine eingebettete WebView meldet
 * `supportsBackgroundProcessors() == true`, obwohl die Kombination "19 MB WASM ueber Mobilfunk +
 * MediaPipe-Segmentierung auf einem Telefon" dort nicht ueberall geprueft ist. Fuer die Android System
 * WebView der `Lapis-Cloud-Mobile`-App wurde das mit V1.4.24 nachgeholt (Messung auf einem Nokia 9), sie ist
 * deshalb [AVAILABLE]. Fuer iOS-WKWebViews und die bekannten Drittanbieter-In-App-Browser (siehe
 * [conferenceBackgroundIsInAppWebView]) gilt es weiter nicht: dort wird der Abschnitt ehrlich als "in der App
 * noch nicht unterstuetzt" gezeigt -- dieselbe Gestaltung wie die Nichtunterstuetzungs-Zeile, kein Ausblenden
 * (Norman-Ruling K4).
 */
internal enum class ConferenceBackgroundAvailability { AVAILABLE, UNSUPPORTED_BROWSER, UNSUPPORTED_IN_APP_WEBVIEW }

/**
 * Kennungen von In-App-Browsern, die sich NICHT ueber die Plattform-Marker unten verraten, weil sie ein
 * `Safari/`-Token mitschicken, obwohl sie eine eingebettete WebView sind. Bewusst nur Tokens, die in einem
 * echten Browser-UA nicht vorkommen: `FBAN`/`FBAV` (Facebook), `Instagram`, `Line/` (mit Schraegstrich, damit
 * `Linux` nicht faelschlich passt), `LinkedInApp`. Die Liste ist bewusst NICHT vollstaendig -- sie deckt die
 * haeufigsten Faelle ab, die restlichen bleiben ein bekannter blinder Fleck (siehe
 * `docs/architecture/video-background-effects.adoc`, "Known gaps").
 */
private val CONFERENCE_IN_APP_BROWSER_TOKENS = listOf("FBAN", "FBAV", "Instagram", "Line/", "LinkedInApp")

/**
 * Erkennt eine in eine App EINGEBETTETE WebView allein am UA-String -- DOM-frei und damit testbar.
 *
 * `Lapis-Cloud-Mobile` setzt WEDER einen eigenen User-Agent NOCH eine JavaScript-Bruecke (geprueft in
 * `AuthenticatedWebViewScreen.android.kt` / dem iOS-Pendant: nur `javaScriptEnabled`, `domStorageEnabled`,
 * Origin-Sperre, Medienrechte -- kein `settings.userAgentString`, kein `addJavascriptInterface`). Es gibt
 * dort also kein eigenes Signal, das ohne Aenderung am Mobil-Repo nutzbar waere; geprueft wird deshalb die
 * Plattform-Kennzeichnung selbst:
 * - **Android System WebView** (`; wv)`) wird seit V1.4.24 bewusst NICHT mehr gesperrt: auf einem Nokia 9
 *   (Android 10, WebView 153, Adreno 630) ist es gemessen worden -- WebAssembly-SIMD, WebGL2 und Insertable
 *   Streams vorhanden, ~20 Bilder/s ohne verworfene Bilder mit und ohne Effekt, Effekt in ~250 ms angewendet,
 *   GPU-Segmentierung, Kamera nach "Verlassen" in ~130 ms frei (Daily Note 2026-09-20). Ob eine WebView die
 *   Kamera freigibt, entscheidet die einbettende App ohnehin selbst (Medienrechte); die Bibliotheks-
 *   Feature-Erkennung und der sichere Kontext bleiben das Gate.
 * - **iOS WKWebView in einer App**: traegt KEIN `Safari/`-Token, waehrend jeder iOS-BROWSER eines traegt
 *   (Mobile Safari, `CriOS/` und `FxiOS/` haengen es an). Deshalb: AppleWebKit + mobiles Geraet + kein
 *   `Safari/`.
 * - **In-App-Browser grosser Apps** ([CONFERENCE_IN_APP_BROWSER_TOKENS]): die schicken ein `Safari/`-Token
 *   mit und wuerden von den zwei Regeln oben nicht erfasst.
 *
 * Bewusst konservativ: ein leerer UA-String gilt NICHT als WebView (dann greift weiter das normale
 * Fähigkeits-Gate), und Android-UAs werden vom Apple-Zweig ausgeschlossen. Die Erkennung ist damit
 * absichtlich unvollstaendig -- sie soll nie einen echten Browser aussperren, darf aber eine unbekannte
 * WebView durchlassen (dann greift der normale Fehlerpfad). Die bekannten blinden Flecken stehen unter
 * "Known gaps" in `docs/architecture/video-background-effects.adoc`.
 */
internal fun conferenceBackgroundIsInAppWebView(userAgent: String): Boolean {
    if (userAgent.isBlank()) return false
    if (CONFERENCE_IN_APP_BROWSER_TOKENS.any { userAgent.contains(it) }) return true
    val isAndroid = userAgent.contains("Android")
    val isAppleMobile =
        userAgent.contains("AppleWebKit") &&
            (userAgent.contains("iPhone") || userAgent.contains("iPad") || userAgent.contains("Mobile/"))
    return !isAndroid && isAppleMobile && !userAgent.contains("Safari/")
}

/** [conferenceBackgroundEffectsSupported] plus die App-WebView-Abgrenzung aus [conferenceBackgroundIsInAppWebView]. */
internal fun conferenceBackgroundAvailability(
    libraryReportsSupport: Boolean,
    isSecureContext: Boolean,
    userAgent: String,
): ConferenceBackgroundAvailability =
    when {
        conferenceBackgroundIsInAppWebView(userAgent) -> ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW
        conferenceBackgroundEffectsSupported(libraryReportsSupport, isSecureContext) -> ConferenceBackgroundAvailability.AVAILABLE
        else -> ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER
    }

internal enum class ConferenceBackgroundPhase { OFF, APPLYING, ACTIVE, FAILED_FALLBACK }

internal enum class ConferenceBackgroundFailure { LOAD_FAILED, APPLY_FAILED, TIMEOUT }

internal data class ConferenceBackgroundState(
    /** Absicht des Nutzers -- wird bei einem Fehlschlag NICHT ueberschrieben (Zhuo-Ruling K6). */
    val desired: ConferenceBackgroundChoice = CONFERENCE_BACKGROUND_OFF,
    /** Was tatsaechlich auf dem Track liegt. */
    val applied: ConferenceBackgroundChoice = CONFERENCE_BACKGROUND_OFF,
    val phase: ConferenceBackgroundPhase = ConferenceBackgroundPhase.OFF,
    /** Der EINE automatische Versuch pro Sitzung wurde verbraucht. */
    val autoAttemptUsed: Boolean = false,
    /** Eine Meldung pro Ursache pro Sitzung -- kein Toast-Regen. */
    val notifiedFailures: Set<ConferenceBackgroundFailure> = emptySet(),
)

internal sealed interface ConferenceBackgroundEvent {
    /** Bewusster Klick auf eine Kachel -- erlaubt einen neuen Versuch, auch wenn der automatische verbraucht ist. */
    data class UserSelected(
        val choice: ConferenceBackgroundChoice,
    ) : ConferenceBackgroundEvent

    data object ApplyStarted : ConferenceBackgroundEvent

    data class ApplySucceeded(
        val choice: ConferenceBackgroundChoice,
    ) : ConferenceBackgroundEvent

    data class ApplyFailed(
        val failure: ConferenceBackgroundFailure,
    ) : ConferenceBackgroundEvent

    /** Neuer/erneut publizierter lokaler Kamera-Track -- aendert den Zustand nie (die Entscheidung trifft [choiceToApplyForNewTrack]). */
    data object NewLocalTrack : ConferenceBackgroundEvent

    /** Der Prozessor ist nicht mehr am Track (z. B. Track gestoppt) -- `desired` bleibt unangetastet. */
    data object ProcessorLost : ConferenceBackgroundEvent
}

private fun phaseAfterSelection(choice: ConferenceBackgroundChoice): ConferenceBackgroundPhase =
    if (choice == CONFERENCE_BACKGROUND_OFF) ConferenceBackgroundPhase.OFF else ConferenceBackgroundPhase.APPLYING

private fun phaseAfterSuccess(choice: ConferenceBackgroundChoice): ConferenceBackgroundPhase =
    if (choice == CONFERENCE_BACKGROUND_OFF) ConferenceBackgroundPhase.OFF else ConferenceBackgroundPhase.ACTIVE

/**
 * Reiner Reducer, wirft fuer keine Event-/Zustands-Kombination (Tabellentest im jsTest).
 *
 * `ApplyFailed` setzt `applied = OFF`, `phase = FAILED_FALLBACK`, `autoAttemptUsed = true`, ergaenzt
 * `notifiedFailures` und laesst `desired` UNVERAENDERT -- die gespeicherte Absicht ueberlebt einen
 * Fehlschlag, nur ein bewusster Klick versucht es erneut (Jobs-K6-Regel).
 */
internal fun conferenceBackgroundReduce(
    current: ConferenceBackgroundState,
    event: ConferenceBackgroundEvent,
): ConferenceBackgroundState =
    when (event) {
        is ConferenceBackgroundEvent.UserSelected ->
            current.copy(
                desired = event.choice,
                phase = phaseAfterSelection(event.choice),
                // Ein bewusster Klick bekommt immer einen frischen Versuch.
                autoAttemptUsed = false,
            )
        ConferenceBackgroundEvent.ApplyStarted -> current.copy(phase = ConferenceBackgroundPhase.APPLYING)
        is ConferenceBackgroundEvent.ApplySucceeded ->
            current.copy(
                applied = event.choice,
                phase = phaseAfterSuccess(event.choice),
            )
        is ConferenceBackgroundEvent.ApplyFailed ->
            current.copy(
                applied = CONFERENCE_BACKGROUND_OFF,
                phase = ConferenceBackgroundPhase.FAILED_FALLBACK,
                autoAttemptUsed = true,
                notifiedFailures = current.notifiedFailures + event.failure,
            )
        ConferenceBackgroundEvent.NewLocalTrack -> current
        ConferenceBackgroundEvent.ProcessorLost -> current.copy(applied = CONFERENCE_BACKGROUND_OFF, phase = ConferenceBackgroundPhase.OFF)
    }

/**
 * Welche Wahl soll auf einem NEUEN `LocalVideoTrack` liegen? Kern der Jobs-K6-Regel:
 * - [desired] == OFF -> OFF
 * - letzter Versuch dieser Sitzung fehlgeschlagen UND automatischer Versuch verbraucht -> OFF (die
 *   gespeicherte Absicht bleibt erhalten, nur ein bewusster Klick versucht erneut)
 * - sonst -> [desired]
 *
 * V1.9.4: ersetzt `effectToApplyForNewTrack`, identische Logik ueber [ConferenceBackgroundChoice].
 */
internal fun choiceToApplyForNewTrack(
    desired: ConferenceBackgroundChoice,
    lastAttemptFailed: Boolean,
    sessionAutoRetryUsed: Boolean,
): ConferenceBackgroundChoice =
    when {
        desired == CONFERENCE_BACKGROUND_OFF -> CONFERENCE_BACKGROUND_OFF
        lastAttemptFailed && sessionAutoRetryUsed -> CONFERENCE_BACKGROUND_OFF
        else -> desired
    }

/** [choiceToApplyForNewTrack] auf den Zustandsautomaten angewandt. */
internal fun conferenceBackgroundChoiceForNewTrack(state: ConferenceBackgroundState): ConferenceBackgroundChoice =
    choiceToApplyForNewTrack(
        desired = state.desired,
        lastAttemptFailed = state.phase == ConferenceBackgroundPhase.FAILED_FALLBACK,
        sessionAutoRetryUsed = state.autoAttemptUsed,
    )

/**
 * Welche Kachel als ausgewaehlt gezeigt wird: die Absicht des Nutzers -- ausser nach einem Fehlschlag, dann
 * springt die Auswahl sichtbar auf "Aus" (die Kamera laeuft ja ohne Effekt), waehrend `desired` und der
 * gespeicherte Wert unveraendert bleiben. Waehrend des Ladens und bei ausgeschalteter Kamera zeigt sie die
 * Absicht (sofortiges Feedback; angewendet wird mit dem naechsten Track).
 */
internal fun conferenceBackgroundDisplayedChoice(state: ConferenceBackgroundState): ConferenceBackgroundChoice =
    if (state.phase == ConferenceBackgroundPhase.FAILED_FALLBACK) CONFERENCE_BACKGROUND_OFF else state.desired

/** Eine Meldung pro Ursache pro Sitzung. */
internal fun conferenceBackgroundShouldNotify(
    state: ConferenceBackgroundState,
    failure: ConferenceBackgroundFailure,
): Boolean = failure !in state.notifiedFailures

/**
 * Drei feste, uebersetzte Saetze (Nichtunterstuetzung zeigt statisch ConferenceBackgroundSection, keine Fehlerursache) -- nie ein roher Fehlertext, nie eine URL oder ein Dateiname (Logging-/
 * Datenschutz-Regel).
 */
internal fun conferenceBackgroundFailureMessage(failure: ConferenceBackgroundFailure): String =
    when (failure) {
        ConferenceBackgroundFailure.LOAD_FAILED ->
            gettext("Hintergrundeffekt konnte nicht geladen werden – die Kamera läuft ohne Effekt weiter.")
        ConferenceBackgroundFailure.APPLY_FAILED ->
            gettext("Hintergrundeffekt konnte nicht angewendet werden – die Kamera läuft ohne Effekt weiter.")
        ConferenceBackgroundFailure.TIMEOUT ->
            gettext("Hintergrundeffekt: Zeitüberschreitung – die Kamera läuft ohne Effekt weiter.")
    }
