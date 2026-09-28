package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.core.onClick
import io.kvision.form.text.text
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.BoardMemberMapResponse
import network.lapis.cloud.shared.domain.MemberMapEntryDto
import network.lapis.cloud.shared.domain.MemberMapPlaceDto
import network.lapis.cloud.shared.rpc.IBoardMemberMapService
import org.w3c.dom.HTMLElement

/**
 * Welle V1.9.6 "Vorstands-Karte" (member map, client half) -- fortsetzt V1.9.5 (server+shared,
 * `c1b0d7d3`). See `docs/architecture/member-map.adoc` for the full design; this KDoc records only
 * the client-specific decisions.
 *
 * **Two synchronized views of the same numbers, always.** The map (MapLibre/PMTiles, self-hosted
 * basemap) is a spatial overview; the table underneath is the exact, exhaustive, always-visible
 * ledger the board can audit even when the map itself cannot be shown (missing WebGL, missing
 * basemap file) -- see [memberMapDegradation]. No number is EVER shown only on the map.
 *
 * **No k-anonymity, no rounding.** A postal code with a single resident member is shown as exactly
 * `1`, never suppressed or bucketed -- this is a BOARD/ADMIN-only aggregate reporting view server-side
 * ([network.lapis.cloud.shared.rpc.IBoardMemberMapService] KDoc), not a public-facing one.
 *
 * **Persistent privacy notice** (not a dismissible banner): "Nur für Vorstand und Administration...".
 * **No deep link, no URL parameter carries a postal code** (Security-Checkliste, Q "no export/share
 * link" in the architecture doc) -- unlike [network.lapis.cloud.client.Routes.MEMBER_FINANCES]'s
 * `?member=` pattern, this screen deliberately has none.
 *
 * **Map-unusable degradation ([memberMapDegradation]) covers the full-panel `.lapis-member-map-notice`
 * overlay** for the three cases where the map genuinely cannot show anything meaningful: no WebGL, no
 * basemap file, or neither. The remaining case -- basemap present but postal-code coordinates
 * unresolved ([MemberMapDegradation.GEODATA_UNAVAILABLE]) -- still renders a real, empty-of-points map
 * (the basemap itself is still useful context), with a small inline hint instead of covering it; this
 * is a deliberate implementation call this wave makes (the architecture doc's degradation matrix
 * describes the DATA outcome, not the exact pixel layout of the hint).
 */
fun renderMemberMapScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    // Registered FIRST, before any other widget is added to `root` -- see `DataTable.kt`'s own KDoc
    // "Why the insert/destroy hooks are registered BEFORE the panel is added": a hook added on an
    // already-rendered widget changes its snabbdom key on the NEXT patch, which can fire the destroy
    // hook on a widget that is still alive. Registering it here, before `root` has any child, avoids
    // the hazard entirely rather than relying on a synchronous-patch coincidence (contrast
    // `PriceOracleScreen.renderPriceHistoryChart`'s own KDoc "Late hooks (audited, left as is)").
    var mapController: MemberMapMapController? = null
    root.addAfterDestroyHook {
        mapController?.destroy()
        mapController = null
    }
    root.pageHeader(tr("Mitgliederkarte"))
    root.div(
        tr(
            "Nur für Vorstand und Administration. Genauigkeit: PLZ-Mittelpunkt, keine Adressen. " +
                "Bitte nicht weitergeben.",
        ),
    ) { addCssClasses("text-muted small") }

    val summaryLine = root.div(className = "lapis-member-map-summary")
    val grid = root.div(className = "lapis-member-map-grid")
    val mapPanel = grid.div(className = "lapis-member-map-panel")
    mapPanel.setAttribute("role", "region")
    mapPanel.setAttribute(
        "aria-label",
        resolvedAttributeText(tr("Karte der Mitgliederverteilung nach PLZ. Alle Werte stehen auch in der Tabelle.")),
    )
    val tablePanel = grid.div(className = "lapis-member-map-table-panel")

    // V1.9.9: renamed from "PLZ oder Ort" -- this field filters the TABLE below; the new map-overlay
    // Ortssuche field (built inside the `render` lambda, once `mapPanel` exists) is a SEPARATE search
    // over ALL known German localities, not just postal codes with a member on them. Distinct enough
    // labels matter once both fields are visible on the same screen at once.
    val searchInput = tablePanel.text(label = tr("Tabelle filtern (PLZ oder Ort)"))
    val hitLine = tablePanel.div(className = "text-muted small")
    val tableHost = tablePanel.simplePanel()
    val liveRegion = tablePanel.div(className = "visually-hidden")
    liveRegion.setAttribute("role", "status")
    liveRegion.setAttribute("aria-live", "polite")

    var loadedDto: BoardMemberMapResponse? = null
    var searchTerm = ""
    var selectedPostalCode: String? = null
    val deps = MemberMapDeps()

    fun renderTable() {
        val dto = loadedDto ?: return
        tableHost.removeAll()
        val visible = filterMemberMapEntries(dto.entries, searchTerm)
        hitLine.content = memberMapSearchHitText(visible, dto.entries)
        if (visible.isEmpty()) {
            tableHost.div(gettext("Kein Eintrag passt zu \"%1\".", searchTerm.trim())) { addCssClasses("text-muted") }
            return
        }
        val table =
            tableHost.standardTable(
                headers =
                    listOf(
                        TableHeader(title = tr("PLZ")),
                        TableHeader(title = tr("Ort")),
                        TableHeader(title = tr("Mitglieder"), numeric = true),
                    ),
            )
        table.setAttribute(
            "aria-label",
            resolvedAttributeText(tr("Mitgliederverteilung nach Postleitzahl")),
        )
        visible.forEach { entry ->
            val interactive = entry.lat != null && entry.lon != null
            table.row {
                if (entry.postalCode == selectedPostalCode) addCssClass("table-active")
                if (!interactive) addCssClasses("text-muted")
                val plzCell = cell()
                if (interactive) {
                    val button = plzCell.tag(TAG.BUTTON, content = entry.postalCode)
                    button.setAttribute("type", "button")
                    button.onClick {
                        selectedPostalCode = entry.postalCode
                        mapController?.flyToEntry(entry)
                        announceSelection(liveRegion, entry)
                        renderTable()
                    }
                    if (entry.postalCode == selectedPostalCode) {
                        button.addAfterInsertHook { button.focus() }
                    }
                } else {
                    // `untrustedSpan`, not `span`, even though `postalCode` is server-regex-validated
                    // (`^[0-9]{5}$`, see `MemberMapEntryDto` KDoc) -- defense in depth, and it keeps this
                    // call site out of `ClientUntrustedWidgetTextTripwireTest`'s raw-dotted-field ledger
                    // without relying on that scanner's documented "local val" blind spot.
                    plzCell.untrustedSpan(entry.postalCode)
                }
                val placeCell = cell()
                val placeName = entry.placeName
                if (placeName != null) {
                    placeCell.untrustedSpan(placeName)
                } else {
                    placeCell.span("–")
                }
                numCell(formatMemberCount(entry.count))
            }
        }
    }

    root
        .dataSection<BoardMemberMapResponse>(
            isEmpty = { it.total == 0 },
            emptyText = tr("Keine aktiven Mitglieder."),
            load = {
                loadedDto = null
                guarded { rpcService<IBoardMemberMapService>().getMemberMap() }
            },
            render = { _, dto ->
                loadedDto = dto
                checkMemberMapTotalInvariant(dto)
                summaryLine.content = memberMapSummaryText(dto)

                // Tears down any map from a PREVIOUS render pass first (defensive: this screen's only
                // `section.reload()` call happens once at the bottom of this function today, so `render`
                // fires at most once per mount in practice -- but a future reload trigger must never leak
                // an old map/observer pair, same discipline as `PriceOracleScreen.renderChart`'s own
                // `teardownChart()` call at its top).
                mapController?.destroy()
                mapController = null
                val degradation = memberMapDegradation(dto, deps.webglAvailable())
                mapPanel.removeAll()
                if (degradation == MemberMapDegradation.WEBGL_MISSING ||
                    degradation == MemberMapDegradation.TILES_UNAVAILABLE ||
                    degradation == MemberMapDegradation.BOTH_UNAVAILABLE
                ) {
                    mapPanel.div(memberMapDegradationText(degradation)) { addCssClass("lapis-member-map-notice") }
                } else {
                    val canvasHost = mapPanel.div(className = "lapis-member-map-canvas-host")
                    canvasHost.addAfterInsertHook { vnode ->
                        val element = vnode.elm as? HTMLElement ?: return@addAfterInsertHook
                        val controller =
                            MemberMapMapController(
                                container = element,
                                deps = deps,
                                onFeatureClicked = { postalCode ->
                                    selectedPostalCode = postalCode
                                    dto.entries.find { it.postalCode == postalCode }?.let { announceSelection(liveRegion, it) }
                                    renderTable()
                                },
                            )
                        if (controller.init() == MemberMapInitResult.CREATED) {
                            controller.setPoints(dto.entries)
                            mapController = controller
                        }
                    }
                    if (degradation == MemberMapDegradation.GEODATA_UNAVAILABLE) {
                        mapPanel.div(memberMapDegradationText(degradation)) {
                            addCssClasses("text-muted small position-absolute bottom-0 start-0 m-2")
                        }
                    }
                    wirePlaceSearchOverlay(mapPanel = mapPanel, dto = dto, mapController = { mapController })
                }
                renderTable()
            },
        ).also { section ->
            var isInitialSearchEvent = true
            var debounceHandle: Int? = null
            searchInput.subscribe { value ->
                if (isInitialSearchEvent) {
                    isInitialSearchEvent = false
                    return@subscribe
                }
                debounceHandle?.let { window.clearTimeout(it) }
                debounceHandle =
                    window.setTimeout({
                        searchTerm = value.orEmpty()
                        renderTable()
                    }, 300)
            }
            section.reload()
        }
}

private fun announceSelection(
    liveRegion: Div,
    entry: MemberMapEntryDto,
) {
    val place = entry.placeName ?: entry.postalCode
    untrustedContent(liveRegion, gettext("%1, %2: %3", entry.postalCode, place, memberMapPopupCountText(entry.count)))
}

/** V1.9.9 Ortssuche: how far a "N Mitglieder innerhalb von X km" ring reaches -- see [memberCountsWithinRadii]. */
private val MEMBER_MAP_SEARCH_RADII_KM = listOf(5, 10, 25)

/** V1.9.9 Ortssuche: the map-panel overlay search box + results dropdown, wired once per `render` pass (see the `dataSection` `render` lambda's own call site). [mapController] is a getter, not the controller itself -- by the time a board member CLICKS a result, `MemberMapMapController.init()`'s async `"load"` handshake may only just have finished, so this must always read the LATEST controller, never one captured at wiring time (`null` at that instant). */
private fun wirePlaceSearchOverlay(
    mapPanel: Div,
    dto: BoardMemberMapResponse,
    mapController: () -> MemberMapMapController?,
) {
    val overlay = mapPanel.div(className = "lapis-member-map-search")
    val field = overlay.text(label = tr("Ort suchen"))
    val resultsHost = overlay.div(className = "lapis-member-map-search-results")
    resultsHost.hide()

    var sequence = 0
    var debounceHandle: Int? = null

    fun clearResults() {
        resultsHost.removeAll()
        resultsHost.hide()
    }

    fun selectPlace(place: MemberMapPlaceDto) {
        clearResults()
        val controller = mapController() ?: return
        controller.flyToPlace(place.lon, place.lat)
        controller.showSearchPin(place.lon, place.lat, buildSearchPinPopupContent(dto.entries, place))
    }

    fun renderResults(results: List<MemberMapPlaceDto>) {
        resultsHost.removeAll()
        if (results.isEmpty()) {
            resultsHost.hide()
            return
        }
        results.forEach { place ->
            val button = resultsHost.tag(TAG.BUTTON, className = "lapis-member-map-search-result")
            button.setAttribute("type", "button")
            untrustedContent(button, gettext("%1 (%2 PLZ)", place.placeName, place.postalCodes.size))
            button.onClick { selectPlace(place) }
        }
        resultsHost.show()
    }

    field.subscribe { value ->
        val query = value.orEmpty()
        debounceHandle?.let { window.clearTimeout(it) }
        if (query.trim().length < 2) {
            clearResults()
            return@subscribe
        }
        debounceHandle =
            window.setTimeout({
                val thisSequence = ++sequence
                AppScope.launch {
                    val results = guarded { rpcService<IBoardMemberMapService>().searchPlaces(query.trim()) }.orEmpty()
                    // Discard a response that is no longer the LATEST outgoing request -- see this
                    // function's own KDoc "why a getter, not the controller" for the analogous
                    // staleness hazard on the controller reference; here the hazard is two in-flight
                    // RPC calls resolving out of order (a fast connection answering a LATER keystroke
                    // before a slow one answers an EARLIER keystroke).
                    if (thisSequence != sequence) return@launch
                    renderResults(results)
                }
            }, PLACE_SEARCH_DEBOUNCE_MS)
    }
}

private const val PLACE_SEARCH_DEBOUNCE_MS = 250

/** Builds the Ortssuche pin popup's DOM content -- `document.createTextNode` per line, never `innerHTML` (same XSS discipline as `MemberMapMapController.showPopup`/`buildTooltipContent`; [place] is server-returned, GeoNames-derived free text). */
private fun buildSearchPinPopupContent(
    entries: List<MemberMapEntryDto>,
    place: MemberMapPlaceDto,
): HTMLElement {
    val counts = memberCountsWithinRadii(entries, place.lat, place.lon, MEMBER_MAP_SEARCH_RADII_KM)
    val content = document.createElement("div") as HTMLElement
    content.className = "lapis-member-map-search-pin-popup"
    val title = document.createElement("div") as HTMLElement
    title.appendChild(document.createTextNode(place.placeName))
    content.appendChild(title)

    val list = document.createElement("dl") as HTMLElement
    MEMBER_MAP_SEARCH_RADII_KM.forEach { radiusKm ->
        val term = document.createElement("dt") as HTMLElement
        term.appendChild(document.createTextNode(gettext("innerhalb %1 km", radiusKm)))
        val value = document.createElement("dd") as HTMLElement
        value.appendChild(document.createTextNode(formatMemberCount(counts.countsByRadiusKm[radiusKm] ?: 0)))
        list.appendChild(term)
        list.appendChild(value)
    }
    content.appendChild(list)

    if (counts.skippedWithoutCoordinates > 0) {
        val note = document.createElement("div") as HTMLElement
        note.className = "text-muted small"
        note.appendChild(
            document.createTextNode(gettext("%1 Mitglieder ohne Ortszuordnung", counts.skippedWithoutCoordinates)),
        )
        content.appendChild(note)
    }
    return content
}

/** One [memberCountsWithinRadii] result: how many members fall within each requested radius (cumulative, not banded -- a member within 5km is ALSO counted in the 10km and 25km figures), plus how many entries had no resolved coordinates at all and could not be judged either way. */
internal data class RadiusCounts(
    val countsByRadiusKm: Map<Int, Int>,
    val skippedWithoutCoordinates: Int,
)

/**
 * V1.9.9 Ortssuche: pure, DOM-free (see this file's own scope posture, same as [filterMemberMapEntries])
 * -- sums [MemberMapEntryDto.count] for every entry whose resolved coordinates lie within each of
 * [radiiKm] of ([lat], [lon]) (great-circle distance, [haversineKm] -- the same formula
 * `PlaceSearchIndex`'s server-side twin uses, duplicated for the same reason that class's own KDoc
 * gives: no natural shared home for a helper this small). An entry with unresolved coordinates
 * (`lat`/`lon` both `null`, see [MemberMapEntryDto] KDoc) can be neither included nor excluded by
 * distance -- its members are counted separately in [RadiusCounts.skippedWithoutCoordinates] instead
 * of silently vanishing from the popup's own numbers.
 */
internal fun memberCountsWithinRadii(
    entries: List<MemberMapEntryDto>,
    lat: Double,
    lon: Double,
    radiiKm: List<Int>,
): RadiusCounts {
    val sortedRadii = radiiKm.sorted()
    val counts = sortedRadii.associateWith { 0 }.toMutableMap()
    var skipped = 0
    entries.forEach { entry ->
        val entryLat = entry.lat
        val entryLon = entry.lon
        if (entryLat == null || entryLon == null) {
            skipped += entry.count
            return@forEach
        }
        val distanceKm = haversineKm(lat, lon, entryLat, entryLon)
        sortedRadii.forEach { radiusKm ->
            if (distanceKm <= radiusKm) counts[radiusKm] = (counts[radiusKm] ?: 0) + entry.count
        }
    }
    return RadiusCounts(countsByRadiusKm = counts, skippedWithoutCoordinates = skipped)
}

/** The gleichung "mappedTotal + unresolvableGermanPostalCode + noPostalCode + foreign = total" as a single always-visible line. */
internal fun memberMapSummaryText(dto: BoardMemberMapResponse): String =
    gettext(
        "%1 Mitglieder gesamt -- %2 zugeordnet, %3 PLZ ohne Geodaten, %4 ohne PLZ, %5 im Ausland.",
        formatMemberCount(dto.total),
        formatMemberCount(dto.mappedTotal),
        formatMemberCount(dto.unresolvableGermanPostalCode),
        formatMemberCount(dto.noPostalCode),
        formatMemberCount(dto.foreign),
    )

/**
 * The two client-side filters of this screen -- a PLZ prefix or a place-name prefix match, case-
 * insensitive. Pure (see `MemberMapScreenTest`): a blank [search] returns [entries] unchanged.
 */
internal fun filterMemberMapEntries(
    entries: List<MemberMapEntryDto>,
    search: String,
): List<MemberMapEntryDto> {
    val term = search.trim()
    if (term.isEmpty()) return entries
    return entries.filter { entry ->
        entry.postalCode.startsWith(term) || (entry.placeName?.startsWith(term, ignoreCase = true) == true)
    }
}

/**
 * The invariant `total == mappedTotal + unresolvableGermanPostalCode + noPostalCode + foreign` is
 * already enforced server-side (`aggregateMemberMap`) -- this is a client-side belt-and-suspenders
 * check: logs, never throws, never shows anything to the board (a violation here would mean the RPC
 * contract itself drifted, not a user-actionable state).
 */
internal fun checkMemberMapTotalInvariant(dto: BoardMemberMapResponse): Boolean {
    val sum = dto.mappedTotal + dto.unresolvableGermanPostalCode + dto.noPostalCode + dto.foreign
    val holds = sum == dto.total
    if (!holds) {
        kotlin.js.console.error("MemberMap total invariant violated: total=${dto.total} sum=$sum")
    }
    return holds
}

/** Which of the map-unusable/degraded states applies -- see this file's KDoc "Map-unusable degradation". */
internal enum class MemberMapDegradation { NONE, TILES_UNAVAILABLE, GEODATA_UNAVAILABLE, BOTH_UNAVAILABLE, WEBGL_MISSING }

internal fun memberMapDegradation(
    dto: BoardMemberMapResponse,
    webglAvailable: Boolean,
): MemberMapDegradation =
    when {
        !webglAvailable -> MemberMapDegradation.WEBGL_MISSING
        !dto.tilesAvailable && !dto.geodataAvailable -> MemberMapDegradation.BOTH_UNAVAILABLE
        !dto.tilesAvailable -> MemberMapDegradation.TILES_UNAVAILABLE
        !dto.geodataAvailable -> MemberMapDegradation.GEODATA_UNAVAILABLE
        else -> MemberMapDegradation.NONE
    }

internal fun memberMapDegradationText(state: MemberMapDegradation): String =
    when (state) {
        MemberMapDegradation.NONE -> ""
        MemberMapDegradation.WEBGL_MISSING ->
            gettext("Die Karte kann in diesem Browser nicht angezeigt werden. Alle Zahlen stehen in der Tabelle.")
        MemberMapDegradation.TILES_UNAVAILABLE ->
            gettext("Keine Kartenbasis konfiguriert. Alle Zahlen stehen in der Tabelle.")
        MemberMapDegradation.GEODATA_UNAVAILABLE ->
            gettext("Postleitzahlen konnten nicht aufgelöst werden -- die Karte zeigt keine Punkte.")
        MemberMapDegradation.BOTH_UNAVAILABLE ->
            gettext("Weder Kartenbasis noch Postleitzahlen-Auflösung sind verfügbar. Alle Zahlen stehen in der Tabelle.")
    }

/** "12 von 340 PLZ · 57 Mitglieder" -- Suchtreffer-Zeile (Design-Team, Punkt 18). */
internal fun memberMapSearchHitText(
    shown: List<MemberMapEntryDto>,
    total: List<MemberMapEntryDto>,
): String {
    val shownMembers = shown.sumOf { it.count }
    return gettext("%1 von %2 PLZ · %3 Mitglieder", shown.size, total.size, shownMembers)
}

/** Popup-Zeile 2 -- gettext-Plural wie `anniversaryRowLabel`. */
internal fun memberMapPopupCountText(count: Int): String = if (count == 1) gettext("1 Mitglied") else gettext("%1 Mitglieder", count)

internal fun formatMemberCount(count: Int): String = formatCountIn(I18n.language, count.toDouble().toDecimal())
