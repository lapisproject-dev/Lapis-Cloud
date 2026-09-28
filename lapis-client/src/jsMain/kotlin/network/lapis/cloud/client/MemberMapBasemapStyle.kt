package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import network.lapis.cloud.shared.domain.MemberMapRules
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Welle V1.9.6 "Vorstands-Karte" (member map, client half) -- the pure, DOM-free half of the map
 * build: theme colors, the static vector basemap style, the clustered points source/layers, and the
 * radius expression both the map paint AND [network.lapis.cloud.client.MemberMapMapControllerTest]'s
 * parity check against [MemberMapRules.radiusPx] evaluate.
 *
 * **Same-origin PMTiles URL.** [MEMBER_MAP_BASEMAP_PMTILES_URL] is `pmtiles:///api/board/member-map/basemap.pmtiles`
 * -- no host, so the browser's own `fetch`/Range requests stay same-origin and the session cookie is
 * sent automatically (`credentials: "same-origin"`, the browser default). Never a cross-origin CDN
 * URL (Security-Checkliste "SSRF/DoS").
 *
 * **PMTiles vector-layer names and `kind` values are verified against the real test fixture**
 * (`lapis-server/src/test/resources/member-map/germany-test-fixture.pmtiles`, a Protomaps Basemap
 * v4.15.2 schema build), not guessed. The fixture carries NINE vector layers (`boundaries`,
 * `buildings`, `earth`, `landcover`, `landuse`, `places`, `pois`, `roads`, `water`) -- this style
 * uses only four of them: `earth`/`water`/`boundaries`/`roads`, with `boundaries.kind`
 * `"country"`/`"unrecognized_country"`/`"region"` (region = state border) and `roads.kind =
 * "highway"`. `places` (which carries `name`/`name:de`) exists but this style still has no `glyphs`
 * URL and no MapLibre `symbol` text layer -- standing up a glyph-serving pipeline (a new server route
 * plus vendored third-party font assets) for what would still only be a fixed handful of labels
 * remains disproportionate. V1.9.8 "Orientierung" renders 24 Bundesland-/Nachbarland-labels as plain
 * DOM markers, V1.9.9 adds Landeshauptstädte the same way (`MEMBER_MAP_CAPITALS`, see
 * `MemberMapLabels.kt`'s own KDoc) -- and, new this wave, reads `places` WITHOUT ever adding a style
 * layer for it at all: `MemberMapMapController.updatePlaceLabels` calls
 * `map.querySourceFeatures("member-map-basemap", {sourceLayer: "places"})`, a query against
 * already-fetched vector-tile bytes that needs no `glyphs` URL, and turns a capped set of nearby
 * small-locality names into DOM markers exactly like the other two label kinds. See that method's own
 * KDoc, and `docs/architecture/member-map.adoc` §Q8/§Q11.
 */
internal const val MEMBER_MAP_BASEMAP_PMTILES_URL = "pmtiles:///api/board/member-map/basemap.pmtiles"

/** GeoJSON source id shared by the points and clusters layers (one source, two rendered layers -- see [buildPointsSourceJson]). */
internal const val MEMBER_MAP_SOURCE_ID = "member-map-points"
internal const val MEMBER_MAP_POINTS_LAYER_ID = "member-map-points-layer"
internal const val MEMBER_MAP_CLUSTERS_LAYER_ID = "member-map-clusters-layer"

/**
 * The supercluster grid cell size (pixels) new postal-code points are grouped within -- a rendering/
 * performance knob, distinct from [MemberMapRules.POINT_RADIUS_CAP_PX]/[MemberMapRules.CLUSTER_RADIUS_CAP_PX]
 * (how big a rendered circle is drawn), MapLibre's own default.
 */
private const val CLUSTER_GRID_RADIUS_PX = 50

/**
 * Below this zoom, MapLibre's supercluster clusters postal-code points together; at and above it,
 * every point renders individually. **Must stay below [MemberMapRules.FLY_TO_ZOOM]** (Atkinson's find,
 * Design-Team-Sitzung 3): [network.lapis.cloud.client.MemberMapMapController]'s cluster-click handler
 * flies to `getClusterExpansionZoom()`, capped at [MemberMapRules.MAX_ZOOM] -- if this constant were
 * NOT below [MemberMapRules.FLY_TO_ZOOM], a table-row click on an entry still hidden inside a cluster
 * at [MemberMapRules.FLY_TO_ZOOM] would land the view exactly on the cluster bubble, not the individual
 * point. See `MemberMapScreenTest.clusterMaxZoom_isBelowFlyToZoom` -- a pinned regression, not just a
 * comment.
 */
internal const val CLUSTER_MAX_ZOOM = 8

/** Only the nine custom properties the map paints with -- see [readMemberMapColors] KDoc "no hardcoded hex". */
internal data class MemberMapColors(
    val land: String,
    val water: String,
    val countryBorder: String,
    val stateBorder: String,
    val highway: String,
    val pointFill: String,
    val pointStroke: String,
    val clusterFill: String,
    val clusterStroke: String,
)

/**
 * `getComputedStyle(...)` values can carry leading/trailing whitespace -- `.trim()` is required, not
 * cosmetic, same reasoning as `PriceOracleScreen.readPriceChartColors` (MapLibre silently ignores an
 * untrimmed CSS color string in a paint expression). `--lapis-map-water` is the one NEW token this
 * wave adds to `theme.css` (all three blocks); every other color reuses an existing token so the map
 * never invents a second palette next to the rest of the app.
 */
internal fun readMemberMapColors(): MemberMapColors {
    val style = window.getComputedStyle(document.documentElement!!)

    fun token(name: String) = style.getPropertyValue(name).trim()
    return MemberMapColors(
        land = token("--lapis-surface-sunken"),
        water = token("--lapis-map-water"),
        // V1.9.9: dedicated, higher-contrast map-only tokens -- see theme.css KDoc comment at
        // `--lapis-map-border-country`. The three reused tokens above (`--lapis-border-strong`/
        // `--lapis-border`/`--lapis-muted`) are calibrated for hairline UI dividers on WHITE, not
        // for a fill-colored map background; borders were barely visible against
        // `--lapis-surface-sunken` (Nutzer-Feedback 2026-09-28).
        countryBorder = token("--lapis-map-border-country"),
        stateBorder = token("--lapis-map-border-state"),
        highway = token("--lapis-map-road"),
        pointFill = token("--lapis-accent"),
        pointStroke = token("--lapis-accent-contrast"),
        clusterFill = token("--lapis-accent-strong"),
        clusterStroke = token("--lapis-accent-contrast"),
    )
}

/**
 * The static vector-tile basemap style (land/water/borders/highway), as a JSON string parsed via
 * `JSON.parse` at the `MemberMapMapController` call site. Colors are embedded ONCE at map creation; a
 * theme switch patches them in place via `map.setPaintProperty` instead of rebuilding the whole style
 * (Design-Team decision, Q "Duarte" -- rebuilding the style tears down and re-adds the points/clusters
 * source too, which would lose in-flight interaction state for no visual benefit).
 *
 * **No TileJSON `url`, no embedded-metadata attribution** -- the vector source's `tiles` array points
 * directly at [MEMBER_MAP_BASEMAP_PMTILES_URL]; MapLibre never reads the PMTiles file's own embedded
 * attribution HTML this way (Q2 in `docs/architecture/member-map.adoc`: that HTML is missing the word
 * "contributors" in the bundled build, and trusting it would be an operator-controlled-HTML-into-DOM
 * path). The screen's own [AttributionControl] supplies the hardcoded, XSS-safe attribution text
 * instead.
 */
internal fun buildBasemapStyleJson(colors: MemberMapColors): String {
    val bounds = MemberMapRules.MAX_BOUNDS.joinToString(",")
    // language=JSON
    return """
        {
          "version": 8,
          "sources": {
            "member-map-basemap": {
              "type": "vector",
              "tiles": ["$MEMBER_MAP_BASEMAP_PMTILES_URL/{z}/{x}/{y}"],
              "bounds": [$bounds],
              "minzoom": ${MemberMapRules.MIN_ZOOM},
              "maxzoom": ${MemberMapRules.BASEMAP_TILE_MAX_ZOOM}
            }
          },
          "layers": [
            {
              "id": "member-map-land",
              "type": "fill",
              "source": "member-map-basemap",
              "source-layer": "earth",
              "paint": { "fill-color": "${colors.land}" }
            },
            {
              "id": "member-map-water",
              "type": "fill",
              "source": "member-map-basemap",
              "source-layer": "water",
              "paint": { "fill-color": "${colors.water}" }
            },
            {
              "id": "member-map-highways",
              "type": "line",
              "source": "member-map-basemap",
              "source-layer": "roads",
              "filter": ["==", ["get", "kind"], "highway"],
              "minzoom": 6,
              "paint": {
                "line-color": "${colors.highway}",
                "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.5, 9, 1.0, 12, 1.75]
              }
            },
            {
              "id": "member-map-state-borders",
              "type": "line",
              "source": "member-map-basemap",
              "source-layer": "boundaries",
              "filter": ["==", ["get", "kind"], "region"],
              "paint": {
                "line-color": "${colors.stateBorder}",
                "line-dasharray": [3, 2],
                "line-width": ["interpolate", ["linear"], ["zoom"], 4, 0.8, 8, 1.3, 12, 1.8]
              }
            },
            {
              "id": "member-map-country-borders-halo",
              "type": "line",
              "source": "member-map-basemap",
              "source-layer": "boundaries",
              "filter": ["in", ["get", "kind"], ["literal", ["country", "unrecognized_country"]]],
              "paint": {
                "line-color": "${colors.countryBorder}",
                "line-opacity": 0.2,
                "line-blur": 1,
                "line-width": ["interpolate", ["linear"], ["zoom"], 4, 4, 8, 6, 12, 8]
              }
            },
            {
              "id": "member-map-country-borders",
              "type": "line",
              "source": "member-map-basemap",
              "source-layer": "boundaries",
              "filter": ["in", ["get", "kind"], ["literal", ["country", "unrecognized_country"]]],
              "paint": {
                "line-color": "${colors.countryBorder}",
                "line-width": ["interpolate", ["linear"], ["zoom"], 4, 1.5, 8, 2.25, 12, 3.0]
              }
            }
          ]
        }
        """.trimIndent()
}

/**
 * `["min", capPx, ["+", 4.0, ["*", 3.0, ["sqrt", ["get", "memberSum"]]]]]` as MapLibre expression JSON
 * (`List<Any>`, JSON-serializable via `JSON.stringify`/`JSON.parse` at the call site) -- the exact same
 * shape as [MemberMapRules.radiusPx] (`min(cap, 4 + 3*sqrt(n))`), evaluated by MapLibre's own paint
 * engine instead of Kotlin so the RENDERED circle radius always matches the number the table/popup
 * shows. [evaluateRadiusExpression] evaluates this expression in pure Kotlin for the parity test
 * against [MemberMapRules.radiusPx] (`MemberMapScreenTest`).
 */
internal fun clusterRadiusExpression(capPx: Double): List<Any> =
    listOf(
        "min",
        capPx,
        listOf("+", 4.0, listOf("*", 3.0, listOf("sqrt", listOf("get", "memberSum")))),
    )

/**
 * Evaluates a [clusterRadiusExpression]-shaped list purely in Kotlin -- ONLY the five operators that
 * expression ever uses (`min`/`+`/`*`/`sqrt`/`get`). Not a general MapLibre-expression interpreter;
 * throws on anything else, which is the point -- if [clusterRadiusExpression]'s shape ever changes,
 * this must change with it, not silently evaluate something else.
 */
internal fun evaluateRadiusExpression(
    expr: List<Any>,
    properties: Map<String, Double>,
): Double {
    fun eval(node: Any?): Double =
        when (node) {
            is Double -> node
            is Int -> node.toDouble()
            is List<*> -> {
                val op = node.first() as String
                val args = node.drop(1)
                when (op) {
                    "min" -> min(eval(args[0]), eval(args[1]))
                    "+" -> eval(args[0]) + eval(args[1])
                    "*" -> eval(args[0]) * eval(args[1])
                    "sqrt" -> sqrt(eval(args[0]))
                    "get" -> properties.getValue(args[0] as String)
                    else -> error("evaluateRadiusExpression: unsupported operator '$op'")
                }
            }
            else -> error("evaluateRadiusExpression: unsupported node $node")
        }
    return eval(expr)
}

/**
 * The GeoJSON `FeatureCollection` of individual postal-code points -- only entries with resolved
 * coordinates ([MemberMapEntryDto.lat]/[MemberMapEntryDto.lon] both non-null, see that DTO's own
 * KDoc "both null or both set"). Built via `kotlinx.serialization.json` (`buildJsonObject`/
 * `buildJsonArray`), never manual string concatenation -- [MemberMapEntryDto.placeName] is
 * GeoNames free text an attacker-influenced upstream import could contain markup in (see the wave's
 * security checklist "XSS"); the JSON serializer escapes it correctly as a JSON string value no
 * matter its content, so nothing downstream (the popup's `setDOMContent`, the table's `span`) ever
 * sees it as anything but inert text.
 *
 * `r` (the pre-computed [MemberMapRules.radiusPx] for [MemberMapRules.POINT_RADIUS_CAP_PX]) is set as
 * a property on every feature so the individual-point circle layer can paint a per-feature radius
 * without a second MapLibre expression evaluating `count` again.
 */
internal fun buildPointsGeoJson(entries: List<MemberMapEntryDto>): String {
    val features =
        buildJsonArray {
            entries
                .filter { it.lat != null && it.lon != null }
                .forEach { entry ->
                    add(
                        buildJsonObject {
                            put("type", "Feature")
                            put(
                                "geometry",
                                buildJsonObject {
                                    put("type", "Point")
                                    put(
                                        "coordinates",
                                        buildJsonArray {
                                            add(entry.lon!!)
                                            add(entry.lat!!)
                                        },
                                    )
                                },
                            )
                            put(
                                "properties",
                                buildJsonObject {
                                    put("postalCode", entry.postalCode)
                                    put("placeName", entry.placeName ?: entry.postalCode)
                                    put("count", entry.count)
                                    put("r", MemberMapRules.radiusPx(entry.count, MemberMapRules.POINT_RADIUS_CAP_PX))
                                },
                            )
                        },
                    )
                }
        }
    return buildJsonObject {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

/**
 * The clustered GeoJSON source config -- `cluster: true` plus `clusterProperties.memberSum` (a SUM of
 * each grouped point's own `count`, computed by supercluster ITSELF as points are grouped). This is
 * why the cluster radius expression ([clusterRadiusExpression]) must read `memberSum`, never the
 * built-in `point_count` (supercluster's own "how many postal-code POINTS are in this cluster", which
 * would undercount a cluster of few but large postal codes) -- see the wave's testplan "Pflichttest"
 * #6.
 */
internal fun buildPointsSourceJson(): String =
    """
    {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] },
      "cluster": true,
      "clusterRadius": $CLUSTER_GRID_RADIUS_PX,
      "clusterMaxZoom": $CLUSTER_MAX_ZOOM,
      "clusterProperties": { "memberSum": ["+", ["get", "count"]] }
    }
    """.trimIndent()

/**
 * The individual (non-clustered) postal-code point circles -- filtered on the ABSENCE of the built-in
 * `point_count`. Radius reads the per-feature `r` property [buildPointsGeoJson] already pre-computed
 * (via [MemberMapRules.radiusPx]) rather than re-evaluating [clusterRadiusExpression] a second time --
 * the clusters layer below is the one that needs a live MapLibre expression, since a cluster's
 * `memberSum` only exists once supercluster has grouped the underlying points.
 */
internal fun buildPointsLayerJson(colors: MemberMapColors): String =
    """
    {
      "id": "$MEMBER_MAP_POINTS_LAYER_ID",
      "type": "circle",
      "source": "$MEMBER_MAP_SOURCE_ID",
      "filter": ["!", ["has", "point_count"]],
      "paint": {
        "circle-radius": ["get", "r"],
        "circle-color": "${colors.pointFill}",
        "circle-stroke-color": "${colors.pointStroke}",
        "circle-stroke-width": 1.5,
        "circle-opacity": 0.85
      }
    }
    """.trimIndent()

/** The cluster bubbles -- filtered on the PRESENCE of the built-in `point_count`, radius driven by [clusterRadiusExpression] over `memberSum`. */
internal fun buildClustersLayerJson(colors: MemberMapColors): String {
    val radiusExprJson = jsonExpressionToString(clusterRadiusExpression(MemberMapRules.CLUSTER_RADIUS_CAP_PX))
    return """
        {
          "id": "$MEMBER_MAP_CLUSTERS_LAYER_ID",
          "type": "circle",
          "source": "$MEMBER_MAP_SOURCE_ID",
          "filter": ["has", "point_count"],
          "paint": {
            "circle-radius": $radiusExprJson,
            "circle-color": "${colors.clusterFill}",
            "circle-stroke-color": "${colors.clusterStroke}",
            "circle-stroke-width": 1.5,
            "circle-opacity": 0.85
          }
        }
        """.trimIndent()
}

/** Renders a [clusterRadiusExpression]-shaped `List<Any>` as MapLibre-expression JSON text (numbers/strings/nested lists only). */
private fun jsonExpressionToString(node: Any?): String =
    when (node) {
        is String -> "\"$node\""
        is Double -> node.toString()
        is Int -> node.toString()
        is List<*> -> node.joinToString(prefix = "[", postfix = "]") { jsonExpressionToString(it) }
        else -> error("jsonExpressionToString: unsupported node $node")
    }
