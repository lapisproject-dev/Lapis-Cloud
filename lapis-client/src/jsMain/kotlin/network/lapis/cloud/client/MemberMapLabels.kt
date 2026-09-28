package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Welle V1.9.8 "Vorstands-Karte: Orientierung" -- the pure, DOM-free half of the map's orientation
 * layer (Bundesland-/Nachbarland-Beschriftungen, Zoom-Band, Hover-Tooltip-Texte). No import of
 * `kotlinx.browser`/`org.w3c.dom` here, on purpose -- [network.lapis.cloud.client.MemberMapMapController]
 * is the only place that turns this data into actual DOM markers/CSS attributes.
 *
 * **Why DOM markers instead of a PMTiles text layer.** The bundled basemap style
 * ([MemberMapBasemapStyle.buildBasemapStyleJson]) has no `glyphs` URL -- MapLibre cannot render a
 * `symbol` text layer without one, and standing up a glyph-serving pipeline just for ~24 static
 * labels is disproportionate (Rams: "as little design as possible"). 24 MapLibre `Marker`s with a
 * plain `<div>` label element cost nothing extra to fetch and are exactly as themeable as the rest
 * of this screen's CSS.
 *
 * **Why hover tooltips instead of a table sync.** [network.lapis.cloud.client.MemberMapScreen]
 * already keeps an always-visible, exhaustive table underneath the map -- but matching a circle on
 * the map back to its table row required either memorizing coordinates or scanning the whole list.
 * A tooltip that names the postal code and place directly under the cursor closes that loop without
 * adding a second synchronized view to maintain (Forstall).
 */
internal enum class MemberMapLabelKind { STATE, SMALL_STATE, NEIGHBOR }

/** `state` / `small-state` / `neighbor` -- the `.lapis-member-map-label--*` CSS modifier suffix, see `theme.css`. */
internal fun MemberMapLabelKind.cssSuffix(): String =
    when (this) {
        MemberMapLabelKind.STATE -> "state"
        MemberMapLabelKind.SMALL_STATE -> "small-state"
        MemberMapLabelKind.NEIGHBOR -> "neighbor"
    }

/**
 * One orientation label. [name] is the canonical GERMAN raw string -- identical to the `msgid` in
 * the i18n catalogs, NOT already translated (see this file's KDoc "why not eager `gettext`" on
 * [MEMBER_MAP_LABELS]). [lon]/[lat] are verified to lie inside [network.lapis.cloud.shared.domain.MemberMapRules.MAX_BOUNDS].
 */
internal data class MemberMapLabel(
    val name: String,
    val lon: Double,
    val lat: Double,
    val kind: MemberMapLabelKind,
)

/**
 * The 16 German Bundesländer (12 [MemberMapLabelKind.STATE], always shown; 4 [MemberMapLabelKind.SMALL_STATE]
 * -- the three city-states plus Saarland, the smallest area states, shown only from zoom band "mid"
 * onward to avoid crowding the low-zoom overview) and 8 of Germany's 9 land neighbours
 * ([MemberMapLabelKind.NEIGHBOR]). Luxembourg is deliberately omitted: at this map's scale its label
 * would sit within a few pixels of both Belgium's and France's, and of the small strip of German
 * territory itself -- one fewer, correctly placed label beats three overlapping ones.
 *
 * **Deliberately a `val`, not `gettext(...)`-translated eagerly.** A top-level `val` initializer
 * runs once, at module load, which can run before [io.kvision.i18n.I18n] has loaded the active
 * catalog -- translating at the call site ([network.lapis.cloud.client.MemberMapMapController.addRegionLabels],
 * via [memberMapLabelText]) instead avoids that ordering hazard entirely.
 *
 * Coordinates are state/country centroids (or, where a centroid would sit too close to a
 * neighbouring label, a nearby representative point) -- all verified inside
 * [network.lapis.cloud.shared.domain.MemberMapRules.MAX_BOUNDS] `[5.5, 47.0, 15.6, 55.2]`; Denmark
 * at `lat=55.05` is the closest to the north edge (`55.2`).
 */
internal val MEMBER_MAP_LABELS: List<MemberMapLabel> =
    listOf(
        // -- 12 STATE (larger-area Bundesländer, shown at every zoom band) --
        MemberMapLabel("Baden-Württemberg", 9.05, 48.55, MemberMapLabelKind.STATE),
        MemberMapLabel("Bayern", 11.55, 49.0, MemberMapLabelKind.STATE),
        MemberMapLabel("Brandenburg", 13.6, 52.7, MemberMapLabelKind.STATE),
        MemberMapLabel("Hessen", 9.05, 50.6, MemberMapLabelKind.STATE),
        MemberMapLabel("Mecklenburg-Vorpommern", 12.6, 53.75, MemberMapLabelKind.STATE),
        MemberMapLabel("Niedersachsen", 9.4, 52.85, MemberMapLabelKind.STATE),
        MemberMapLabel("Nordrhein-Westfalen", 7.6, 51.45, MemberMapLabelKind.STATE),
        MemberMapLabel("Rheinland-Pfalz", 7.4, 49.9, MemberMapLabelKind.STATE),
        MemberMapLabel("Sachsen", 13.4, 51.05, MemberMapLabelKind.STATE),
        MemberMapLabel("Sachsen-Anhalt", 11.6, 52.05, MemberMapLabelKind.STATE),
        MemberMapLabel("Schleswig-Holstein", 9.8, 54.3, MemberMapLabelKind.STATE),
        MemberMapLabel("Thüringen", 11.0, 50.85, MemberMapLabelKind.STATE),
        // -- 4 SMALL_STATE (city-states + Saarland, shown from zoom band "mid" onward) --
        MemberMapLabel("Berlin", 13.405, 52.52, MemberMapLabelKind.SMALL_STATE),
        MemberMapLabel("Hamburg", 10.0, 53.55, MemberMapLabelKind.SMALL_STATE),
        MemberMapLabel("Bremen", 8.8, 53.08, MemberMapLabelKind.SMALL_STATE),
        MemberMapLabel("Saarland", 6.9, 49.38, MemberMapLabelKind.SMALL_STATE),
        // -- 8 NEIGHBOR (of Germany's 9 land neighbours; Luxembourg omitted, see class KDoc) --
        MemberMapLabel("Dänemark", 9.5, 55.05, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Niederlande", 5.9, 52.05, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Belgien", 5.7, 50.5, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Frankreich", 6.15, 48.35, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Schweiz", 8.3, 47.15, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Österreich", 13.05, 47.25, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Tschechien", 14.6, 49.85, MemberMapLabelKind.NEIGHBOR),
        MemberMapLabel("Polen", 15.35, 51.5, MemberMapLabelKind.NEIGHBOR),
    )

/**
 * Inserts one line break after the first hyphen of a translated label so a long, hyphenated name
 * (e.g. "Nordrhein-Westfalen") wraps onto two short lines instead of one wide one inside its small
 * marker `<div>` -- the CSS (`.lapis-member-map-label`, `theme.css`) sets `white-space: pre-line`
 * specifically to honour this. Short/non-hyphenated names (most neighbour countries) pass through
 * unchanged. [translatedName] is already the RESULT of `gettext(label.name)` at the call site --
 * this function itself never calls `gettext` (see [MEMBER_MAP_LABELS] KDoc), so it stays testable
 * with plain literal strings.
 */
internal fun memberMapLabelText(translatedName: String): String {
    if (translatedName.length <= 16) return translatedName
    val hyphenIndex = translatedName.indexOf('-')
    if (hyphenIndex <= 0 || hyphenIndex >= translatedName.length - 1) return translatedName
    return translatedName.substring(0, hyphenIndex + 1) + "\n" + translatedName.substring(hyphenIndex + 1)
}

/**
 * Which orientation labels are visible right now -- `"low"` (large-area [MemberMapLabelKind.STATE]
 * labels only, the initial/zoomed-out view), `"mid"` (adds [MemberMapLabelKind.SMALL_STATE] and
 * [MemberMapLabelKind.NEIGHBOR]) or `"high"` (drops [MemberMapLabelKind.NEIGHBOR] again -- once
 * zoomed in this far the board is looking at individual postal codes, not country context). Applied
 * as `data-zoom-band` on `.lapis-member-map-canvas-host`; the CSS in `theme.css` does the actual
 * hide/show per band+kind combination.
 */
internal fun memberMapZoomBand(zoom: Double): String =
    when {
        zoom < 6.0 -> "low"
        zoom < 8.0 -> "mid"
        else -> "high"
    }

// ── V1.9.9 "Vorstands-Karte: Details & Suche" ─────────────────────────────────────────────────

/**
 * One Landeshauptstadt. [name] is the canonical GERMAN raw string, same "not eagerly translated"
 * discipline as [MemberMapLabel.name] (see [MEMBER_MAP_LABELS] KDoc) -- most of these 16 city names
 * pass through `gettext` unchanged (only e.g. "München"/"Hannover" have an EN catalog entry).
 */
internal data class MemberMapCapital(
    val name: String,
    val lon: Double,
    val lat: Double,
)

/**
 * The 16 Landeshauptstädte -- one per Bundesland, including the three city-states (whose capital IS
 * the state itself: Berlin/Hamburg/Bremen). Added because the 12/4/8 [MEMBER_MAP_LABELS] name only
 * the STATE, not its seat of government -- direct user feedback named Magdeburg (Sachsen-Anhalt) and
 * Dresden (Sachsen) as missing, both genuinely absent from the label set before this wave. Rendered
 * as a separate, always-visible marker kind (dot + name, `MemberMapMapController.addCapitalMarkers`)
 * rather than folded into [MEMBER_MAP_LABELS] itself -- a capital is useful context at every zoom
 * band, unlike [MemberMapLabelKind.NEIGHBOR], which V1.9.8 deliberately fades out once zoomed in.
 * Coordinates verified inside [network.lapis.cloud.shared.domain.MemberMapRules.MAX_BOUNDS].
 */
internal val MEMBER_MAP_CAPITALS: List<MemberMapCapital> =
    listOf(
        MemberMapCapital("Stuttgart", 9.18, 48.78),
        MemberMapCapital("München", 11.58, 48.14),
        MemberMapCapital("Berlin", 13.405, 52.52),
        MemberMapCapital("Potsdam", 13.07, 52.40),
        MemberMapCapital("Bremen", 8.80, 53.08),
        MemberMapCapital("Hamburg", 10.00, 53.55),
        MemberMapCapital("Wiesbaden", 8.24, 50.08),
        MemberMapCapital("Schwerin", 11.42, 53.63),
        MemberMapCapital("Hannover", 9.73, 52.37),
        MemberMapCapital("Düsseldorf", 6.77, 51.23),
        MemberMapCapital("Mainz", 8.27, 50.00),
        MemberMapCapital("Saarbrücken", 7.00, 49.23),
        MemberMapCapital("Dresden", 13.74, 51.05),
        MemberMapCapital("Magdeburg", 11.63, 52.13),
        MemberMapCapital("Kiel", 10.14, 54.32),
        MemberMapCapital("Erfurt", 11.03, 50.98),
    )

/** One feature MapLibre's `querySourceFeatures` returned from the `places` vector source-layer -- raw name + coordinates, before [selectPlaceLabels] dedupes/caps/excludes. */
internal data class PlaceLabelCandidate(
    val name: String,
    val lon: Double,
    val lat: Double,
)

/**
 * Picks which small-locality names [network.lapis.cloud.client.MemberMapMapController.updatePlaceLabels]
 * turns into DOM markers -- pure and unit-testable on its own, independent of the MapLibre query that
 * produces [candidates] (real `querySourceFeatures` results can contain many duplicate features for
 * the SAME place across adjacent tiles at a shared tile boundary; blank/whitespace-only names some
 * vector-tile builds emit for unnamed features; and the [MEMBER_MAP_CAPITALS]/[MEMBER_MAP_LABELS]
 * names already shown by the other two marker layers).
 *
 * Ordering is insertion order of [candidates] (already whatever order MapLibre returned, typically
 * tile-scan order) -- there is no reliable `population_rank`-equivalent property confirmed present in
 * every PMTiles build this codebase might be pointed at, so this deliberately does not attempt to
 * rank by "importance"; [maxCount] alone keeps the marker count bounded.
 */
internal fun selectPlaceLabels(
    candidates: List<PlaceLabelCandidate>,
    excludedNames: Set<String>,
    maxCount: Int,
): List<PlaceLabelCandidate> {
    val excludedNormalized = excludedNames.map { it.trim().lowercase() }.toSet()
    val seen = mutableSetOf<String>()
    val result = mutableListOf<PlaceLabelCandidate>()
    for (candidate in candidates) {
        if (result.size >= maxCount) break
        val trimmed = candidate.name.trim()
        if (trimmed.isEmpty()) continue
        val key = trimmed.lowercase()
        if (key in excludedNormalized) continue
        if (!seen.add(key)) continue
        result += candidate.copy(name = trimmed)
    }
    return result
}

/**
 * Great-circle distance in kilometers (mean Earth radius 6371 km) -- used by
 * [network.lapis.cloud.client.memberCountsWithinRadii] for the Ortssuche popup's "N Mitglieder
 * innerhalb von X km" lines. Accurate enough at Germany's scale (a few hundred km at most) that the
 * spherical-Earth simplification's error is negligible for this purpose.
 */
internal fun haversineKm(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earthRadiusKm = 6371.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusKm * c
}

/** Line 1 ("PLZ 38100 · Braunschweig", or just "PLZ 38100" when [placeName] is unresolved) + line 2 (member count, reusing [memberMapPopupCountText]). */
internal fun memberMapPointTooltipLines(
    placeName: String?,
    postalCode: String,
    count: Int,
): List<String> {
    val firstLine =
        if (placeName != null) {
            gettext("PLZ %1 · %2", postalCode, placeName)
        } else {
            gettext("PLZ") + " " + postalCode
        }
    return listOf(firstLine, memberMapPopupCountText(count))
}

/** Line 1 (how many postal codes are grouped), line 2 (their summed member count), line 3 (the click affordance). */
internal fun memberMapClusterTooltipLines(
    pointCount: Int,
    memberSum: Int,
): List<String> =
    listOf(
        gettext("%1 PLZ in diesem Bereich", pointCount),
        memberMapPopupCountText(memberSum),
        gettext("Klicken zum Vergrößern"),
    )
