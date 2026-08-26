package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import kotlin.js.Promise

/**
 * V1.2.9 Vollbildmodus -- minimal Fullscreen-API-Externals. Kotlin-stdlib-js' `org.w3c.dom.*`-
 * Bindings kennen weder `Element.requestFullscreen()`/`webkitRequestFullscreen()` noch
 * `Document.exitFullscreen()`/`fullscreenElement` -- die Fullscreen-API-Spec postdatiert diese
 * Bindings, exakt dieselbe Lücke, die `livekit/LiveKitJs.kt` für `livekit-client` bereits
 * dokumentiert. Gereicht über [kotlin.js.unsafeCast], niemals `as`/`as?` -- ein
 * `external interface` trägt in Kotlin/JS keine Laufzeit-Typinformation (siehe `LiveKitJs.kt`
 * Datei-KDoc).
 *
 * [FullscreenElement]/[FullscreenDocument] unten sind bewusst OHNE `: Element`/`: Document` --
 * beide sind in Kotlin/JS' `org.w3c.dom`-Bindings echte `external class`es, keine Interfaces; ein
 * `external interface` kann keine Klasse erweitern (nur andere Interfaces). Genau wie
 * `LiveKitJs.kt`'s eigene Externals (`Track`/`TrackPublication`/`ActiveSpeaker`) bleiben diese
 * beiden bewusst freistehend -- der Zugriff geschieht ausschließlich über [kotlin.js.unsafeCast],
 * das keine tatsächliche Vererbungsbeziehung voraussetzt.
 *
 * Verfügbarkeits-Check bleibt bewusst `asDynamic()`-basiert (siehe [fullscreenApiAvailable]) --
 * ein unconditional `unsafeCast` + Aufruf einer auf Safari/iOS fehlenden Methode würfe eine
 * `TypeError` zur Laufzeit. Alle vier request/exit-Aufrufe sind in diesem File gebündelt
 * (`requestFullscreenOn`/`exitBrowserFullscreen`), damit Aufrufer in `ConferenceScreen.kt` nie
 * selbst mit `asDynamic()`/`unsafeCast` hantieren müssen.
 */
internal external interface FullscreenElement {
    fun requestFullscreen(): Promise<dynamic>

    fun webkitRequestFullscreen(): Promise<dynamic>
}

internal external interface FullscreenDocument {
    val fullscreenElement: Element?
    val webkitFullscreenElement: Element?

    fun exitFullscreen(): Promise<dynamic>

    fun webkitExitFullscreen(): Promise<dynamic>
}

/** D14 -- true nur wenn mindestens eine der beiden (Standard/-webkit-) Requestmethoden am
 * `document.documentElement` real existiert. Bewusst gegen `documentElement`, nicht gegen das
 * spätere Fullscreen-Zielelement geprüft -- die Methode existiert (oder nicht) am Prototyp,
 * unabhängig vom konkreten Zielelement. */
internal fun fullscreenApiAvailable(): Boolean {
    val el = document.documentElement?.asDynamic() ?: return false
    return el.requestFullscreen != undefined || el.webkitRequestFullscreen != undefined
}

/** D5 -- reine Wahrheitsquelle, gelesen NUR im `fullscreenchange`/`webkitfullscreenchange`-Handler,
 * niemals im Klick-Handler selbst. */
internal fun currentFullscreenElement(): Element? {
    val doc = document.unsafeCast<FullscreenDocument>()
    return doc.fullscreenElement ?: doc.webkitFullscreenElement
}

/** Fordert den Vollbildmodus für [el] an -- wählt Standard- oder `-webkit-`-Methode je nachdem,
 * welche am Element tatsächlich existiert. Nur aufrufen, nachdem [fullscreenApiAvailable] `true`
 * ergeben hat. */
internal fun requestFullscreenOn(el: Element): Promise<dynamic> {
    val dynEl = el.asDynamic()
    val fsEl = el.unsafeCast<FullscreenElement>()
    return if (dynEl.requestFullscreen != undefined) fsEl.requestFullscreen() else fsEl.webkitRequestFullscreen()
}

/** Verlässt den Vollbildmodus, sofern der Browser gerade in einem ist -- Aufrufer prüft
 * [currentFullscreenElement] vorher, diese Funktion wählt nur die passende Exit-Methode. */
internal fun exitBrowserFullscreen(): Promise<dynamic> {
    val dynDoc = document.asDynamic()
    val doc = document.unsafeCast<FullscreenDocument>()
    return if (dynDoc.exitFullscreen != undefined) doc.exitFullscreen() else doc.webkitExitFullscreen()
}

/** V1.2.10 -- wörtliches Gegenstück zu [fullscreenApiAvailable]: true nur wenn `getDisplayMedia` am
 * `navigator.mediaDevices` real existiert. iOS Safari und die meisten mobilen Browser haben das
 * nicht -- der Bildschirmteilen-Button wird dort gar nicht erst gerendert statt in einen
 * Fehler-Toast zu laufen (Forstall, Design-Review V1.2.10). */
internal fun screenShareAvailable(): Boolean {
    val md = window.navigator.asDynamic().mediaDevices ?: return false
    return md.getDisplayMedia != undefined
}
