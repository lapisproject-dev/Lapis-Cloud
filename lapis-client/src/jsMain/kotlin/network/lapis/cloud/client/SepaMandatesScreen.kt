package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaMandateDto
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.ISepaService

/**
 * V1.2.2 SEPA-Client-UI wave -- Plan §2.6/§4.2. Route-gated TREASURER/BOARD/ADMIN (see
 * `Routes.SEPA_MANDATES` KDoc); the narrower TREASURER/ADMIN on-behalf-grant tier is gated
 * in-screen via [SepaAuthzUi.canGrantOnBehalf].
 */
fun renderSepaMandatesScreen(container: SimplePanel) {
    val root = container.dataScreenRoot()
    root.h1(tr("SEPA-Mandate"))

    val canGrantOnBehalf = SepaAuthzUi.canGrantOnBehalf(AppState.session?.role)

    root.h2(tr("Mandate")) { addCssClass("h5") }
    // Welle V1.4.26 (W2): Filterleiste nach Richtlinie 2.4 -- Suchfeld, Status-Segment, `Aktualisieren`
    // rechts. Der Status-`select` + `Filtern`-Knopf sind einem `segmentedControl` gewichen: vier
    // gegenseitig ausschliessende Werte, die ihren Aktivzustand jetzt selbst tragen (`aria-pressed`, R48),
    // und die Auswahl laedt sofort -- ein separater `Filtern`-Klick war der einzige Zweck des Knopfes.
    val filterRow = root.hPanel(spacing = 12) { addCssClasses("align-items-end flex-wrap") }
    val searchInput = filterRow.text(label = tr("Suche nach Mitglied oder Mandatsreferenz"))
    val statusSegmentHost = filterRow.simplePanel()
    val refreshButton = filterRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)

    val countsLabel = root.div().apply { addCssClasses("text-muted small") }
    val statusRegion = root.dataStatusRegion()
    val listPanel = root.vPanel(spacing = 6)
    val loadMoreButton = root.button(tr("Mehr laden"), style = ButtonStyle.OUTLINESECONDARY) { hide() }

    var lastGrantedAt: LocalDateTime? = null
    var statusFilter: SepaMandateStatus? = null
    var searchTerm = ""
    var hasMore = false
    var generation = 0
    val loaded = mutableListOf<SepaMandateDto>()
    var loading = false
    // Fehlerzustand des Erstabrufs: solange er steht, darf weder Suche noch Filter ihn wegzeichnen.
    var failed = false

    lateinit var loadPage: (Boolean) -> Unit

    /**
     * Rendert die geladenen Zeilen. Die Suche wirkt clientseitig auf die geladene Teilmenge (`listMandates`
     * hat keinen Suchparameter) -- deshalb sagt der Zaehler ueber der Tabelle ausdruecklich, dass gezaehlt
     * wird, was geladen ist, und der Leertext nennt das Nachladen als Ausweg.
     */
    fun renderList() {
        // Genau EIN ablesbarer Zustand (Richtlinie P7): solange der erste Ladevorgang läuft, sagt allein
        // die Live-Region „Wird geladen …". Ohne diesen Riegel malte ein Tastendruck im Suchfeld während
        // des Ladens den Leertext über den Ladehinweis -- „lädt" und „keine Daten" gleichzeitig.
        if (loading && loaded.isEmpty()) return
        // Der Fehlerkasten mit `Erneut versuchen` bleibt stehen -- ein Tastendruck im Suchfeld darf ihn nicht
        // durch „Noch keine …" ersetzen.
        if (failed) return
        listPanel.removeAll()
        if (loaded.isEmpty()) {
            countsLabel.content = ""
            listPanel.p(sepaMandatesEmptyText(statusFilter)) { addCssClasses("text-muted") }
            return
        }
        val visible = filterSepaMandates(loaded, searchTerm)
        countsLabel.content =
            dataCountText(shown = visible.size, loaded = loaded.size, hasMore = hasMore, filtered = searchTerm.isNotBlank())
        if (visible.isEmpty()) {
            listPanel.p(loadedSubsetNoMatchText(term = searchTerm.trim(), hasMore = hasMore)) { addCssClasses("text-muted") }
            return
        }
        listPanel.dataTable(
            columns = sepaMandateColumns(),
            rows = visible,
            actions = { actions, mandate -> actions.renderSepaMandateActions(mandate) { loadPage(true) } },
        )
    }

    loadPage = { reset ->
        if (reset) {
            generation++
            loaded.clear()
            lastGrantedAt = null
            hasMore = false
            listPanel.removeAll()
            countsLabel.content = ""
            loadMoreButton.hide()
            statusRegion.showLoading()
            loading = true
            failed = false
        }
        val mine = generation
        val cursor = if (reset) null else lastGrantedAt
        // Zusammen mit dem Cursor festgehalten: der Rumpf unten läuft erst beim nächsten Dispatch, bis
        // dahin kann ein Segment-Klick `statusFilter` schon verändert haben.
        val requestStatus = statusFilter
        loadMoreButton.disabled = true
        AppScope.launch {
            val mandates =
                sepaGuarded(tr(SEPA_READ_CONFLICT_MESSAGE)) {
                    rpcService<ISepaService>().listMandates(status = requestStatus, beforeGrantedAt = cursor)
                }
            if (mine != generation) return@launch // ein neuerer Ladevorgang hat uebernommen
            statusRegion.clearStatus()
            loading = false
            loadMoreButton.disabled = false
            if (mandates == null) {
                loadMoreButton.hide()
                // `sepaGuarded` hat den Toast schon gezeigt. Beim Neuladen ersetzt der Fehlerzustand mit
                // `Erneut versuchen` das vorher stumm leere Panel; beim Nachladen bleibt die bestehende
                // Ansicht stehen, dort genuegt der Toast (gleiche Abwaegung wie in `OpenItemsScreen`).
                if (reset) {
                    failed = true
                    listPanel.dataErrorState(onRetry = { loadPage(true) })
                }
                return@launch
            }
            loaded += mandates
            mandates.lastOrNull()?.let { lastGrantedAt = it.grantedAt }
            hasMore = mandates.size >= SEPA_MANDATES_PAGE_SIZE
            if (hasMore) loadMoreButton.show() else loadMoreButton.hide()
            renderList()
        }
    }

    statusSegmentHost.segmentedControl(
        options =
            listOf(
                null to tr("Alle"),
                SepaMandateStatus.ACTIVE to sepaMandateStatusLabel(SepaMandateStatus.ACTIVE),
                SepaMandateStatus.REVOKED to sepaMandateStatusLabel(SepaMandateStatus.REVOKED),
                SepaMandateStatus.EXPIRED to sepaMandateStatusLabel(SepaMandateStatus.EXPIRED),
            ),
        selected = statusFilter,
        ariaLabel = tr("Status"),
    ) { status ->
        statusFilter = status
        loadPage(true)
    }
    refreshButton.onClick { loadPage(true) }
    loadMoreButton.onClick { loadPage(false) }

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
                renderList()
            }, 300)
    }
    loadPage(true)

    if (canGrantOnBehalf) {
        root.h2(tr("Mandat im Namen eines Mitglieds erfassen")) { addCssClass("h5") }
        val formHost = root.vPanel(spacing = 4)
        AppScope.launch {
            val members = guarded { rpcService<IMemberService>().listMembers() } ?: return@launch
            val memberOptions = members.map { it.id to it.displayName }
            renderSepaMandateForm(
                container = formHost,
                onBehalf = true,
                defaultDebtorName = "",
                memberOptions = memberOptions,
            ) {
                loadPage(true)
            }
        }
        // Die Grenze steht als Platzhalter im Satz, nie im msgid (W4c): eine Konstante im msgid sagte sie in acht Katalogen.
        root.p(gettext("Es können höchstens %1 Mandate pro Minute erfasst werden.", SEPA_MANDATES_PER_MINUTE_LIMIT)) {
            addCssClasses("text-muted small")
        }
    }
}

private const val SEPA_MANDATES_PAGE_SIZE = 50

/** Spiegel der Server-Ratenbegrenzung für das Erfassen von Mandaten im Namen eines Mitglieds (loser Spiegel, der Server bleibt Autorität). */
private const val SEPA_MANDATES_PER_MINUTE_LIMIT = 10

internal const val SEPA_READ_CONFLICT_MESSAGE = "SEPA-Lastschrift ist für diese Organisation nicht aktiviert."

/**
 * Clientseitige Suche über die geladene Teilmenge (pur, siehe `SepaMandatesScreenTest`): Mitgliedsname
 * ODER Mandatsreferenz, ohne Gross-/Kleinschreibung. Die IBAN ist bewusst **nicht** durchsuchbar -- im DTO
 * stehen nur die letzten vier Stellen, eine Suche darüber würde mehr versprechen als sie halten kann.
 */
internal fun filterSepaMandates(
    mandates: List<SepaMandateDto>,
    search: String,
): List<SepaMandateDto> {
    val term = search.trim()
    if (term.isEmpty()) return mandates
    return mandates.filter {
        it.memberDisplayName.contains(term, ignoreCase = true) || it.mandateReference.contains(term, ignoreCase = true)
    }
}

/** „Noch keine Daten" -- und zwar für den gewählten Status, nicht als ein Satz für alle vier Fälle (R41). */
internal fun sepaMandatesEmptyText(status: SepaMandateStatus?): String =
    if (status == null) {
        gettext("Noch keine SEPA-Mandate erfasst.")
    } else {
        gettext("Kein Mandat mit dem Status \"%1\".", sepaMandateStatusLabel(status))
    }

/** Spalten der Mandatstabelle / Kartenliste; das Mitglied ist die Identität der Zeile, also der Kartentitel. */
private fun sepaMandateColumns(): List<DataColumn<SepaMandateDto>> =
    listOf(
        textColumn(title = tr("Mitglied"), primary = true) { mandate: SepaMandateDto -> mandate.memberDisplayName },
        textColumn(title = tr("Mandatsreferenz")) { mandate: SepaMandateDto -> mandate.mandateReference },
        textColumn(title = tr("IBAN"), numeric = true) { mandate: SepaMandateDto -> formatIbanLast4(mandate.debtorIbanLast4) },
        DataColumn(
            title = tr("Status"),
            cell = { container, mandate ->
                container.statusBadge(sepaMandateStatusLabel(mandate.status), sepaMandateStatusColor(mandate.status))
            },
        ),
        textColumn(title = tr("Erteilt am"), numeric = true) { mandate: SepaMandateDto -> mandate.grantedAt.toString() },
        textColumn(title = tr("Erfasst von")) { mandate: SepaMandateDto ->
            if (mandate.createdBySelf) tr("Selbst") else mandate.createdByDisplayName
        },
    )

/**
 * Zeilenaktion. Die Rollen-/Statusprüfung ([SepaAuthzUi.canRevokeMandateOf]) und der
 * Begründungs-Dialog dahinter sind gegenüber V1.2.2 unverändert -- er ist die eigentliche Sicherung gegen
 * einen versehentlichen Widerruf, nicht die Knopfbreite.
 */
private fun Container.renderSepaMandateActions(
    mandate: SepaMandateDto,
    onChanged: () -> Unit,
) {
    val ownMandate = mandate.memberId == AppState.session?.memberId
    if (!SepaAuthzUi.canRevokeMandateOf(AppState.session?.role, ownMandate, mandate.status)) return
    val revokeButton = tableActionButton("fas fa-ban", tr("Widerrufen"), ButtonStyle.OUTLINEDANGER)
    revokeButton.onClick {
        confirmWithReasonDialog(
            title = tr("Mandat widerrufen"),
            message = tr("Mandat wirklich widerrufen?"),
            reasonLabel = tr("Grund"),
            reasonRequired = false,
            confirmLabel = tr("Widerrufen"),
        ) { reason ->
            runGuardedAction(revokeButton) {
                val result = guarded { rpcService<ISepaService>().revokeMandate(mandate.id, reason) }
                if (result != null) {
                    notifySuccess(tr("Mandat widerrufen."))
                    onChanged()
                }
            }
        }
    }
}
