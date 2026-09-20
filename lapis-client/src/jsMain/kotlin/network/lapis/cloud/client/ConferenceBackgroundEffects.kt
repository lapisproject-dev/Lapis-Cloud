package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr

/*
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- reine, DOM-freie Logik (jsTest-gedeckt): Whitelist,
 * Asset-Pfade, Persistenz-Wert, Unterstuetzungs-Gate und der Zustandsautomat. Das Anwenden auf den
 * LiveKit-Track (DOM/WebGL/WASM) steht in [ConferenceBackgroundController], die Oberflaeche in
 * `ConferenceScreen.kt`; Architektur und manueller Pruefplan: `docs/architecture/video-background-effects.adoc`.
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
 * Persistierter Wert -> Effekt. `null` fuer alles ausser einer exakten Whitelist-ID (leer, blank, falsche
 * Gross-/Kleinschreibung, Pfad, URL, unbekannt) UND fuer `"off"` -- der Aufrufer behandelt `null` als
 * [ConferenceBackgroundEffect.OFF]. Bewusst KEIN `trim()`/`lowercase()`: ein manipulierter Wert soll nie
 * "fast passen".
 */
internal fun parseStoredBackgroundEffect(raw: String?): ConferenceBackgroundEffect? {
    if (raw == null) return null
    val effect = ConferenceBackgroundEffect.entries.firstOrNull { it.id == raw } ?: return null
    return if (effect == ConferenceBackgroundEffect.OFF) null else effect
}

/**
 * Wert fuer `localStorage`: `null` == Schluessel LOESCHEN (nie `""` schreiben -- Lehre aus dem
 * `deviceId`-Bug, V1.4.19), sonst die Whitelist-ID.
 */
internal fun conferenceBackgroundPersistValue(effect: ConferenceBackgroundEffect): String? =
    if (effect == ConferenceBackgroundEffect.OFF) null else effect.id

/** Same-origin-Pfad des Hintergrundbilds, nur fuer die sechs `BG_*`-Effekte, sonst `null`. */
internal fun conferenceBackgroundImagePath(effect: ConferenceBackgroundEffect): String? =
    when (effect) {
        ConferenceBackgroundEffect.BG_WARM_GREY,
        ConferenceBackgroundEffect.BG_COOL_BLUE,
        ConferenceBackgroundEffect.BG_SAGE,
        ConferenceBackgroundEffect.BG_SANDSTONE,
        ConferenceBackgroundEffect.BG_MIDNIGHT,
        ConferenceBackgroundEffect.BG_STUDIO,
        -> "${ConferenceBackgroundAssets.BACKGROUND_IMAGE_DIR}/${effect.id}.webp"
        ConferenceBackgroundEffect.OFF,
        ConferenceBackgroundEffect.BLUR_LIGHT,
        ConferenceBackgroundEffect.BLUR_STRONG,
        -> null
    }

internal fun conferenceBackgroundBlurRadius(effect: ConferenceBackgroundEffect): Int? =
    when (effect) {
        ConferenceBackgroundEffect.BLUR_LIGHT -> CONFERENCE_BLUR_RADIUS_LIGHT
        ConferenceBackgroundEffect.BLUR_STRONG -> CONFERENCE_BLUR_RADIUS_STRONG
        else -> null
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

/**
 * Beschriftung der eingeklappten "Hintergrund"-Zeile -- der Zustand steht im Text (Tesler: kein unsichtbarer
 * Modus). `gettext` mit Platzhalter, deshalb NUR fuer Attribute (`title`) geeignet; der sichtbare Knopftext
 * kommt aus [conferenceBackgroundToggleLabelTr].
 */
internal fun conferenceBackgroundToggleLabel(effect: ConferenceBackgroundEffect): String =
    gettext("Hintergrund: %1", conferenceBackgroundEffectLabel(effect))

/**
 * Dieselbe Beschriftung als `tr(...)`-Marker fuer den SICHTBAREN Knopftext (Audit-Befund N4). Bewusst neun
 * vollstaendige, eigene msgids statt einer Zusammensetzung: KVisions `tr(...)` kennt keine Platzhalter, und
 * ein zusammengesetzter `gettext`-Text wuerde bei einem Sprachwechsel zur Laufzeit nie neu uebersetzt (die
 * Aufloesung passiert ausschliesslich im Patch-Zyklus, siehe `ClientTrAttributeLeakTest` KDoc). NUR als
 * Widget-Inhalt verwenden.
 */
internal fun conferenceBackgroundToggleLabelTr(effect: ConferenceBackgroundEffect): String =
    when (effect) {
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
 * Drei Zustaende statt eines `Boolean` (Audit-Befund M5): eine eingebettete App-WebView meldet
 * `supportsBackgroundProcessors() == true`, obwohl die Kombination "19 MB WASM ueber Mobilfunk +
 * MediaPipe-Segmentierung auf einem Telefon" auf dieser Plattform noch NIE geprueft wurde
 * (`Lapis-Cloud-Mobile` bettet die Web-Meeting-Oberflaeche seit V1.5.1 in eine WebView ein). Bis das
 * nachgeholt ist, wird der Abschnitt dort ehrlich als "in der App noch nicht unterstuetzt" gezeigt --
 * dieselbe Gestaltung wie die Nichtunterstuetzungs-Zeile, kein Ausblenden (Norman-Ruling K4).
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
 * - **Android System WebView**: Chromes UA traegt in einer WebView das Token `; wv)` -- eindeutig und
 *   genau die Kennzeichnung, die Google fuer diesen Zweck vergibt.
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
    if (userAgent.contains("; wv)")) return true
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
    val desired: ConferenceBackgroundEffect = ConferenceBackgroundEffect.OFF,
    /** Was tatsaechlich auf dem Track liegt. */
    val applied: ConferenceBackgroundEffect = ConferenceBackgroundEffect.OFF,
    val phase: ConferenceBackgroundPhase = ConferenceBackgroundPhase.OFF,
    /** Der EINE automatische Versuch pro Sitzung wurde verbraucht. */
    val autoAttemptUsed: Boolean = false,
    /** Eine Meldung pro Ursache pro Sitzung -- kein Toast-Regen. */
    val notifiedFailures: Set<ConferenceBackgroundFailure> = emptySet(),
)

internal sealed interface ConferenceBackgroundEvent {
    /** Bewusster Klick auf eine Kachel -- erlaubt einen neuen Versuch, auch wenn der automatische verbraucht ist. */
    data class UserSelected(
        val effect: ConferenceBackgroundEffect,
    ) : ConferenceBackgroundEvent

    data object ApplyStarted : ConferenceBackgroundEvent

    data class ApplySucceeded(
        val effect: ConferenceBackgroundEffect,
    ) : ConferenceBackgroundEvent

    data class ApplyFailed(
        val failure: ConferenceBackgroundFailure,
    ) : ConferenceBackgroundEvent

    /** Neuer/erneut publizierter lokaler Kamera-Track -- aendert den Zustand nie (die Entscheidung trifft [effectToApplyForNewTrack]). */
    data object NewLocalTrack : ConferenceBackgroundEvent

    /** Der Prozessor ist nicht mehr am Track (z. B. Track gestoppt) -- `desired` bleibt unangetastet. */
    data object ProcessorLost : ConferenceBackgroundEvent
}

private fun phaseAfterSelection(effect: ConferenceBackgroundEffect): ConferenceBackgroundPhase =
    if (effect == ConferenceBackgroundEffect.OFF) ConferenceBackgroundPhase.OFF else ConferenceBackgroundPhase.APPLYING

private fun phaseAfterSuccess(effect: ConferenceBackgroundEffect): ConferenceBackgroundPhase =
    if (effect == ConferenceBackgroundEffect.OFF) ConferenceBackgroundPhase.OFF else ConferenceBackgroundPhase.ACTIVE

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
                desired = event.effect,
                phase = phaseAfterSelection(event.effect),
                // Ein bewusster Klick bekommt immer einen frischen Versuch.
                autoAttemptUsed = false,
            )
        ConferenceBackgroundEvent.ApplyStarted -> current.copy(phase = ConferenceBackgroundPhase.APPLYING)
        is ConferenceBackgroundEvent.ApplySucceeded ->
            current.copy(
                applied = event.effect,
                phase = phaseAfterSuccess(event.effect),
            )
        is ConferenceBackgroundEvent.ApplyFailed ->
            current.copy(
                applied = ConferenceBackgroundEffect.OFF,
                phase = ConferenceBackgroundPhase.FAILED_FALLBACK,
                autoAttemptUsed = true,
                notifiedFailures = current.notifiedFailures + event.failure,
            )
        ConferenceBackgroundEvent.NewLocalTrack -> current
        ConferenceBackgroundEvent.ProcessorLost ->
            current.copy(applied = ConferenceBackgroundEffect.OFF, phase = ConferenceBackgroundPhase.OFF)
    }

/**
 * Welcher Effekt soll auf einem NEUEN `LocalVideoTrack` liegen? Kern der Jobs-K6-Regel:
 * - [desired] == OFF -> OFF
 * - letzter Versuch dieser Sitzung fehlgeschlagen UND automatischer Versuch verbraucht -> OFF (die
 *   gespeicherte Absicht bleibt erhalten, nur ein bewusster Klick versucht erneut)
 * - sonst -> [desired]
 */
internal fun effectToApplyForNewTrack(
    desired: ConferenceBackgroundEffect,
    lastAttemptFailed: Boolean,
    sessionAutoRetryUsed: Boolean,
): ConferenceBackgroundEffect =
    when {
        desired == ConferenceBackgroundEffect.OFF -> ConferenceBackgroundEffect.OFF
        lastAttemptFailed && sessionAutoRetryUsed -> ConferenceBackgroundEffect.OFF
        else -> desired
    }

/** [effectToApplyForNewTrack] auf den Zustandsautomaten angewandt. */
internal fun conferenceBackgroundEffectForNewTrack(state: ConferenceBackgroundState): ConferenceBackgroundEffect =
    effectToApplyForNewTrack(
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
internal fun conferenceBackgroundDisplayedEffect(state: ConferenceBackgroundState): ConferenceBackgroundEffect =
    if (state.phase == ConferenceBackgroundPhase.FAILED_FALLBACK) ConferenceBackgroundEffect.OFF else state.desired

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
