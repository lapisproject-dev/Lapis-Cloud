package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialCommentInput
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostErasureInput
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostRemovalNoticeDto
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostReportInput
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialTimelineQuery
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import network.lapis.cloud.shared.rpc.NotFoundException

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum, Boosts,
 * rekursive Gesamtgewichtung" -- self-contained domain ([ISocialNetworkService]) covering: Timeline
 * lesen (jetzt nach Gesamtgewicht sortiert, siehe [SocialPostDto.totalCurrentWeightLtr]), Post
 * verfassen (Inhalt + LTR-Einsatz + Sichtbarkeitsstufe), eigenen Post unsichtbar machen, sowie seit
 * Welle V1.1.2 Kommentieren, Boosten und die Thread-Ansicht (`renderSocialThreadScreen`, eigene
 * Route [Routes.SOCIAL_NETWORK_POST]). See `32-social-network.kuml.kts` file header and the vault
 * concept notes ("Soziales Netzwerk", "Meritokratisches System und Libertaler" § "Im sozialen
 * Netz") for the full fachlich model this screen surfaces.
 *
 * **Role gating** (verified against `SocialNetworkService.kt`'s actual call sites, same discipline
 * `CrowdfundingScreen.kt`'s own KDoc documents):
 * - [ISocialNetworkService.createPost]/[ISocialNetworkService.createComment]/
 *   [ISocialNetworkService.boostPost] -- MEMBER+, additionally must be `LTR_ELIGIBLE`
 *   (`requireLtrEligibleMembership` INSIDE the server transaction, not reachable as an
 *   `AccountRole` predicate -- same "not surfaced client-side, server rejects with the ordinary
 *   `guarded()` ForbiddenException toast" posture as `CrowdfundingScreen`'s `submitProject`).
 *   **Welle V1.1.4** widened this from `ORGANIZATION_MEMBER` to `MemberStatusSets.LTR_ELIGIBLE`
 *   (also admits [network.lapis.cloud.shared.domain.MemberStatus.FRIEND]) -- this screen now
 *   filters the visibility select to exclude [SocialPostVisibility.MEMBERS_ONLY] for a
 *   [MemberStatusSets.NON_MEMBER] caller (mirrors the server's own
 *   `SocialNetworkService.requireVisibilityAllowedFor` invariant) and shows a dedicated hint when
 *   the caller's free LTR balance is 0,00.
 * - [ISocialNetworkService.listTimeline]/[ISocialNetworkService.getPost]/
 *   [ISocialNetworkService.getThread] -- any authenticated member, filtered server-side by the
 *   caller's own visibility tier.
 * - [ISocialNetworkService.hideOwnPost] -- the author only (`ForbiddenException` otherwise);
 *   rendered here only as a button on the author's OWN post cards (`session.memberId` compared
 *   against [SocialPostDto.authorMemberId]), so a non-author never sees the control at all. Since
 *   Welle V1.1.2 this is deliberately NOT reflected as a visible-vs-hidden state on OTHER nodes in
 *   a rendered thread -- a suppressed descendant is simply absent from [SocialThreadDto.nodes]
 *   (K2, no cascade write, see `SocialNetworkService.hideOwnPost` KDoc).
 *
 * **D7-analogue (no refund)**: mirrors `CrowdfundingScreen.kt`'s own must-fix D7 -- there is no
 * `updatePost`/refund path anywhere in this domain; `hideOwnPost` does not return the stake either,
 * and neither does a comment's or a boost's stake (same posture, stated in both
 * [renderComposeForm]'s and [renderReplyForm]'s copy).
 *
 * **Confirm-dialog tier (D4-analogue)**: `createPost`/`createComment`/`boostPost` all use the plain
 * [confirmDialog] (Tier 1 "Kostenpflichtig", material to the author's/booster's own balance) --
 * same tier `CrowdfundingScreen`'s `submitProject` uses. `hideOwnPost` also gets a light confirm
 * (irreversible, see [SocialPostState] KDoc "S6" -- no `unhideOwnPost` exists).
 *
 * **Empty state (D10-analogue)**: zero posts renders "Noch keine Beiträge." instead of a blank list.
 */
fun renderSocialNetworkScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.h1(tr("Soziales Netzwerk"))

    // ---- Neuen Beitrag verfassen (D3-analogue: renderMyLtrBalanceInline vor jedem Eingabefeld) --
    root.h2(tr("Neuen Beitrag verfassen"))
    val composerPanel = root.vPanel(spacing = 6)
    // Welle V1.1.4: ein frisch selbst-registriertes FRIEND-Konto hat 0,00 LTR -- createPost wirft
    // dann eine technisch korrekte, aber fachlich unerklaerte ConflictException ("initialWeightLtr
    // ... exceeds free LTR balance 0.00"). Dieser Hinweis (unterhalb der Guthaben-Zeile, D3-analog)
    // erklaert die Ursache VOR dem ersten fehlgeschlagenen Versuch, statt den Fehler nur als Toast
    // nach dem Absenden zu zeigen.
    lateinit var zeroBalanceHintHolder: SimplePanel
    composerPanel.renderMyLtrBalanceInline { balance ->
        if (balance != null && balance.toDouble() == 0.0) {
            zeroBalanceHintHolder.div(
                tr(
                    "Sie haben derzeit kein LTR-Guthaben. Für einen Beitrag ist ein Einsatz von " +
                        "mindestens 0,01 LTR nötig. LTR erhalten Sie durch eine Zuwendung der " +
                        "Organisation oder durch eine Überweisung eines Mitglieds.",
                ),
            ) { addCssClasses("text-muted small") }
        }
    }
    // Erst NACH dem renderMyLtrBalanceInline-Aufruf angelegt, damit der Hinweis im DOM UNTERHALB
    // der Guthaben-Zeile steht (D3-Reihenfolge) -- der Callback oben feuert erst asynchron, nachdem
    // dieser synchrone Codeblock vollstaendig durchgelaufen ist, das lateinit ist zu dem Zeitpunkt
    // also sicher initialisiert.
    zeroBalanceHintHolder = composerPanel.div()

    // ---- Timeline (Container jetzt angelegt, befuellt durch loadTimeline()) --------------------
    root.h2(tr("Timeline"))
    val refreshRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val refreshButton = refreshRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val timelinePanel = root.vPanel(spacing = 10)

    fun loadTimeline() {
        timelinePanel.removeAll()
        timelinePanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val page = guarded { rpcService<ISocialNetworkService>().listTimeline(SocialTimelineQuery()) } ?: return@launch
            timelinePanel.removeAll()
            if (page.posts.isEmpty()) {
                timelinePanel.p(tr("Noch keine Beiträge.")) { addCssClasses("text-muted small") }
                return@launch
            }
            page.posts.forEach { post -> renderSocialPostCard(timelinePanel, post) { loadTimeline() } }
        }
    }

    refreshButton.onClick { loadTimeline() }
    renderComposeForm(composerPanel) { loadTimeline() }
    loadTimeline()

    // ---- Welle V1.1.5 (E-C, DSA Art. 17): Eigenansicht "Meine entfernten Beiträge" -------------
    val myMemberId = AppState.session?.memberId
    if (myMemberId != null) {
        root.h2(tr("Meine entfernten Beiträge"))
        val ownRemovedPanel = root.vPanel(spacing = 10)

        fun loadOwnRemoved() {
            ownRemovedPanel.removeAll()
            ownRemovedPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
            AppScope.launch {
                val page =
                    guarded {
                        rpcService<ISocialNetworkService>().listTimeline(
                            SocialTimelineQuery(includeHidden = true, authorMemberId = myMemberId, limit = 100),
                        )
                    }
                ownRemovedPanel.removeAll()
                val removed = page?.posts?.filter { it.state == SocialPostState.REMOVED_LEGAL }.orEmpty()
                if (removed.isEmpty()) {
                    ownRemovedPanel.p(tr("Keine entfernten eigenen Beiträge.")) { addCssClasses("text-muted small") }
                } else {
                    removed.forEach { post -> renderOwnRemovedPostCard(ownRemovedPanel, post) }
                }
            }
        }
        loadOwnRemoved()
    }
}

/**
 * Welle V1.1.5 (E-C, DSA Art. 17) -- eine deutlich markierte Karte für den Autor selbst, mit
 * [SocialPostDto.state] + [SocialPostDto.stateReason]. Nutzt dieselbe Eigenansicht-Query wie
 * `listTimeline(includeHidden = true, authorMemberId = self)`, gefiltert clientseitig auf
 * `REMOVED_LEGAL` -- der Server liefert dort zusätzlich `VISIBLE`/`HIDDEN_BY_AUTHOR` mit, die in
 * der normalen Timeline bzw. über [renderHideOwnPostControl] bereits sichtbar sind.
 */
private fun renderOwnRemovedPostCard(
    panel: SimplePanel,
    post: SocialPostDto,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3 border-danger") }
    card.statusBadge(tr("Rechtlich entfernt"), "danger")
    card.div(gettext("Begründung: %1", post.stateReason.orEmpty())) { addCssClasses("small") }
    card.div(gettext("Veröffentlicht am %1", post.publishedAt)) { addCssClasses("text-muted small") }
}

/**
 * Welle V1.1.5 (E-B) -- Hinweiskarte aus [SocialPostRemovalNoticeDto], aufgerufen als Fallback in
 * [renderSocialThreadScreen], wenn [ISocialNetworkService.getPost] `NotFoundException` liefert.
 */
private fun renderRemovalNotice(
    panel: SimplePanel,
    notice: SocialPostRemovalNoticeDto,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3 border-danger") }
    card.div(SocialModerationUi.removalNoticeHeadline(isOwnPost = notice.isOwnPost)) { addCssClasses("fw-bold text-danger") }
    card.div(gettext("Entfernt am %1", notice.removedAt)) { addCssClasses("text-muted small") }
    card.div(notice.reason) { addCssClasses("small") }
}

// ================================================================================================
// Compose-Formular
// ================================================================================================

/**
 * Welle V1.1.4 -- reine, DOM-freie Sichtbarkeitsauswahl-Logik für den Compose-Formular, extrahiert
 * damit sie in [SocialNetworkScreenTest] ohne Rendering-Harness (existiert in diesem Modul nicht,
 * siehe [NavVisibilityTest]/[GovernanceAuthzUiTest]) unit-getestet werden kann. Spiegelt exakt die
 * serverseitige Invariante `SocialNetworkService.requireVisibilityAllowedFor` -- ein
 * [MemberStatusSets.NON_MEMBER]-Aufrufer (FRIEND) darf [SocialPostVisibility.MEMBERS_ONLY] nicht
 * wählen, weil er den entstehenden Post danach weder lesen noch je wieder unsichtbar machen könnte,
 * während sein LTR-Einsatz gebunden bliebe. Die serverseitige Prüfung bleibt die eigentliche
 * Grenze (gilt auch gegen einen direkten RPC-Aufruf) -- dies ist nur die Client-seitige Spiegelung
 * in der Auswahlliste.
 */
object SocialComposerVisibility {
    fun allowedVisibilities(status: MemberStatus?): List<SocialPostVisibility> =
        if (status != null && status in MemberStatusSets.NON_MEMBER) {
            SocialPostVisibility.entries.filter { it != SocialPostVisibility.MEMBERS_ONLY }
        } else {
            SocialPostVisibility.entries
        }

    /**
     * Default für [MemberStatusSets.NON_MEMBER] ist die ENGERE der beiden ihm offenen Stufen
     * ([SocialPostVisibility.MEMBERS_AND_EXTERNAL]), NICHT [SocialPostVisibility.PUBLIC] -- eine
     * unbeabsichtigte Suchmaschinen-Indexierung darf nie die Voreinstellung sein. Für
     * [MemberStatusSets.ORGANIZATION_MEMBER] unverändert [SocialPostVisibility.MEMBERS_ONLY].
     */
    fun defaultVisibility(status: MemberStatus?): SocialPostVisibility =
        if (status != null && status in MemberStatusSets.NON_MEMBER) {
            SocialPostVisibility.MEMBERS_AND_EXTERNAL
        } else {
            SocialPostVisibility.MEMBERS_ONLY
        }
}

private fun renderComposeForm(
    root: SimplePanel,
    onCompleted: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val contentInput = panel.textArea(label = tr("Inhalt"), rows = 3)
    val weightInput = panel.text(label = tr("Einsatz (LTR)"))

    val callerStatus = AppState.session?.status
    val defaultVisibility = SocialComposerVisibility.defaultVisibility(callerStatus)
    val visibilityOptions = SocialComposerVisibility.allowedVisibilities(callerStatus).map { it.name to socialPostVisibilityLabel(it) }
    val visibilitySelect =
        panel.select(options = visibilityOptions, value = defaultVisibility.name, label = tr("Sichtbarkeit"))

    // Welle V1.1.3 (vorgezogen aus V1.1.4, siehe Implementierungsplan Stolperfalle 15): ab dieser
    // Welle ist ein PUBLIC-Beitrag tatsaechlich ueber den unauthentifizierten Lesepfad
    // (SocialPublicRoutes, GET /s) dauerhaft von Suchmaschinen indexierbar -- der Hinweis ist ab
    // jetzt eine wahre Aussage, keine hypothetische mehr. Reaktive Sichtbarkeit ueber
    // .subscribe{}, dasselbe Idiom wie DsgvoRightsScreen.kt's modeCaption.
    val publicNotice =
        panel.div(tr("Dieser Beitrag wird öffentlich sichtbar und von Suchmaschinen indexiert.")) {
            addCssClasses("text-muted small")
        }

    fun applyPublicNoticeVisibility(visibilityValue: String?) {
        if (visibilityValue == SocialPostVisibility.PUBLIC.name) publicNotice.show() else publicNotice.hide()
    }
    applyPublicNoticeVisibility(visibilitySelect.value)
    visibilitySelect.subscribe { value -> applyPublicNoticeVisibility(value) }

    // D7-analogue (kein Rueckerstattungspfad) -- siehe CrowdfundingScreen.kt's eigene Copy an
    // derselben Stelle.
    panel.div(
        tr(
            "Ihr Einsatz wird NICHT zurückerstattet -- auch nicht, wenn Sie den Beitrag später " +
                "unsichtbar machen. Es gibt in diesem System keinen Rückerstattungspfad für diesen Einsatz.",
        ),
    ) { addCssClasses("text-muted small") }
    panel.div(
        tr(
            "Ein veröffentlichter Beitrag kann nicht mehr bearbeitet werden -- Tippfehler bleiben " +
                "Tippfehler oder werden in einem neuen Beitrag richtiggestellt.",
        ),
    ) { addCssClasses("text-muted small") }

    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = panel.button(tr("Veröffentlichen"), style = ButtonStyle.PRIMARY)

    submitButton.onClick {
        errorBox.hide()
        val content = contentInput.value.orEmpty().trim()
        val weightText = weightInput.value.orEmpty().trim()
        val visibility = parseOptionalEnum<SocialPostVisibility>(visibilitySelect.value) ?: defaultVisibility

        if (!Validation.isNonBlank(content) || !Validation.isPositiveDecimal(weightText)) {
            errorBox.content = tr("Bitte Inhalt und einen positiven Einsatz (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        val weight = weightText.toDouble().toDecimal()

        // Tier 1 "Kostenpflichtig" (D4-analogue) -- siehe CrowdfundingScreen.kt's submitProject.
        confirmDialog(
            title = tr("Beitrag veröffentlichen"),
            message =
                gettext(
                    "Es werden %1 aus Ihrem freien LTR-Guthaben gebunden. Dieser Einsatz wird NICHT " +
                        "zurückerstattet, auch nicht durch späteres Unsichtbarmachen.",
                    formatLtr(weight),
                ),
            confirmLabel = tr("Veröffentlichen"),
        ) {
            submitButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<ISocialNetworkService>().createPost(
                            SocialPostInput(content = content, visibility = visibility, initialWeightLtr = weight),
                        )
                    }
                submitButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Beitrag veröffentlicht."))
                    contentInput.value = null
                    weightInput.value = null
                    onCompleted()
                }
            }
        }
    }
}

// ================================================================================================
// Timeline-Karten
// ================================================================================================

private fun renderSocialPostCard(
    panel: SimplePanel,
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    val isAuthor = AppState.session?.memberId == post.authorMemberId
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }

    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(post.authorDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(socialPostVisibilityLabel(post.visibility), socialPostVisibilityColor(post.visibility))

    renderPostContentText(card, post)
    card.div(gettext("Veröffentlicht am %1", post.publishedAt)) { addCssClasses("text-muted small") }

    // Drei Kennzahlen nebeneinander, nie zu einer verschmolzen (Meritokratie-Konzept "Anzeige in
    // der Timeline"): das GESAMTgewicht (Sortierkriterium der Timeline seit Welle V1.1.2, siehe
    // [SocialPostDto.totalCurrentWeightLtr] KDoc), das Eigengewicht (Einsatz + eigene Boosts) und
    // das Gewicht (freier LTR-Bestand) des Autors -- macht ökonomisches Gewicht direkt sichtbar
    // statt Eitelkeits-Metriken wie Followerzahlen.
    //
    // Security-Audit-Fund S-1 (2026-08-18): [SocialPostDto.authorFreeBalanceLtr] ist `null`, wenn
    // der Server den Betrachter nicht als ORGANIZATION_MEMBER einstuft (z. B. ein selbst-
    // registriertes FRIEND-Konto) -- die dritte Kennzahl wird dann schlicht weggelassen, statt
    // einen falschen Platzhalterwert (z. B. 0,00 LTR) vorzutäuschen.
    val weightRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val totalWeightCell = weightRow.vPanel(spacing = 2)
    totalWeightCell.div(tr("Gesamtgewicht")) { addCssClasses("text-muted small") }
    totalWeightCell.ltrSpan(post.totalCurrentWeightLtr)
    val ownWeightCell = weightRow.vPanel(spacing = 2)
    ownWeightCell.div(tr("Eigengewicht")) { addCssClasses("text-muted small") }
    ownWeightCell.ltrSpan(post.ownCurrentWeightLtr)
    val authorFreeBalance = post.authorFreeBalanceLtr
    if (authorFreeBalance != null) {
        val authorWeightCell = weightRow.vPanel(spacing = 2)
        authorWeightCell.div(tr("Gewicht des Autors")) { addCssClasses("text-muted small") }
        authorWeightCell.ltrSpan(authorFreeBalance)
    }

    // NEU Welle V1.1.2: Antwort-/Boost-Zähler, direkt unter den Gewichts-Kennzahlen -- macht die
    // Diskussionsaktivität sichtbar, ohne selbst ins Sortierkriterium einzufließen (das bleibt
    // ausschließlich [SocialPostDto.totalCurrentWeightLtr]).
    card.div(
        gettext("%1 Antworten · %2 Boosts", post.totalDescendantCount.toString(), post.boostCount.toString()),
    ) { addCssClasses("text-muted small") }

    val actionsRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    actionsRow.button(tr("Thread öffnen"), style = ButtonStyle.OUTLINESECONDARY).onClick {
        navigateTo("${Routes.SOCIAL_NETWORK}/post/${post.id}")
    }
    // "Antworten" fuehrt -- wie "Thread öffnen" -- in die Thread-Ansicht: das Antwort-Formular lebt
    // dort inline unter jedem Knoten (inkl. der Wurzel), nicht als Duplikat auf der Timeline-Karte.
    actionsRow.button(tr("Antworten"), style = ButtonStyle.OUTLINESECONDARY).onClick {
        navigateTo("${Routes.SOCIAL_NETWORK}/post/${post.id}")
    }
    renderBoostControl(actionsRow, post, onChanged)
    renderReportControl(actionsRow, post, isAuthor, onChanged)
    renderRequestErasureControl(actionsRow, post, onChanged)

    if (isAuthor && post.state == SocialPostState.VISIBLE) {
        renderHideOwnPostControl(card, post, onChanged)
    }
    renderRemoveForLegalReasonControl(card, post, onChanged)
}

/** Welle V1.1.5 -- gedämpfte Kursivschrift für einen getombstoneten Post (`content` ist dann der Marker-Text), sonst normale Anzeige. */
private fun renderPostContentText(
    panel: SimplePanel,
    post: SocialPostDto,
) {
    panel.div(post.content) { addCssClasses(if (post.contentErasedAt != null) "small fst-italic text-muted" else "small") }
}

/**
 * "Boosten" -- monetäres Like, siehe [ISocialNetworkService.boostPost] KDoc (S3/S4/E6/K5). Der
 * Betrag wird -- wie der Einsatz im Compose-Formular ([renderComposeForm]) -- ERST inline erfasst
 * und validiert, DANN per [confirmDialog] bestätigt (Tier 1 "Kostenpflichtig", derselbe
 * zweistufige Ablauf wie `AuctionScreen.kt`'s Gebotsabgabe): keine Betragseingabe im Modal selbst.
 */
private fun renderBoostControl(
    row: SimplePanel,
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    val amountInput = row.text(label = tr("Boost-Betrag (LTR)")) { width = 140.px }
    val errorBox =
        row.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val boostButton = row.button(tr("Boosten"), style = ButtonStyle.OUTLINEPRIMARY)
    boostButton.onClick {
        errorBox.hide()
        val amountText = amountInput.value.orEmpty().trim()
        if (!Validation.isPositiveDecimal(amountText)) {
            errorBox.content = tr("Bitte einen positiven Betrag (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        // Stolperfalle 15 (Review Runde 1, 2026-08-18): round to 2 decimal places client-side BEFORE
        // sending -- otherwise a stray third digit (e.g. "1.005") only fails after the round trip
        // with a server ConflictException that reads like a bug.
        val amount = Validation.roundToTwoDecimalPlaces(amountText.toDouble()).toDecimal()
        confirmDialog(
            title = tr("Beitrag boosten"),
            message =
                gettext(
                    "Sie zahlen %1 aus Ihrem freien LTR-Guthaben. Dieser Betrag wird NICHT zurückerstattet.",
                    formatLtr(amount),
                ),
            confirmLabel = tr("Boosten"),
        ) {
            boostButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<ISocialNetworkService>().boostPost(post.id, amount) }
                boostButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Beitrag geboostet."))
                    amountInput.value = null
                    onChanged()
                }
            }
        }
    }
}

/**
 * Irreversibel (S6, siehe [SocialPostState] KDoc) -- kein `unhideOwnPost` existiert. Der Confirm-
 * Dialog nennt das ausdrücklich, damit niemand versehentlich einen Beitrag dauerhaft aus der
 * Timeline nimmt.
 */
private fun renderHideOwnPostControl(
    card: SimplePanel,
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    val row = card.hPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1") }
    val hideButton = row.button(tr("Unsichtbar machen"), style = ButtonStyle.OUTLINEDANGER)
    hideButton.onClick {
        confirmDialog(
            title = tr("Beitrag unsichtbar machen"),
            message =
                tr(
                    "Der Beitrag verschwindet aus der Timeline, bleibt aber über seine ID erreichbar " +
                        "(z. B. über den Kontoauszug). Das ist NICHT umkehrbar und erstattet keinen LTR-Einsatz.",
                ),
            confirmLabel = tr("Unsichtbar machen"),
        ) {
            hideButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<ISocialNetworkService>().hideOwnPost(post.id) }
                hideButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Beitrag unsichtbar gemacht."))
                    onChanged()
                }
            }
        }
    }
}

// ================================================================================================
// Welle V1.1.5 -- Moderation (DSA Art. 16/6), DSGVO-Content-Löschantrag
// ================================================================================================

/**
 * Reine, DOM-freie Prädikate für die Rollen-/Zustands-Gates dieser Welle -- extrahiert nach dem
 * Muster [SocialComposerVisibility], testbar ohne Rendering-Harness (existiert in diesem Modul
 * nicht).
 */
object SocialModerationUi {
    /** Spiegelt `SocialNetworkService.SOCIAL_MODERATION_ROLES` (server-seitige Wahrheit bleibt maßgeblich). */
    fun canRemove(role: AccountRole?): Boolean = role == AccountRole.BOARD || role == AccountRole.ADMIN

    /** Der Autor darf seinen eigenen Post nicht melden; ein bereits nicht-`VISIBLE`r Post hat nichts mehr zu melden. */
    fun canReport(
        isAuthor: Boolean,
        state: SocialPostState,
    ): Boolean = !isAuthor && state == SocialPostState.VISIBLE

    /** Welle V1.1.5 (E-B) -- die Überschrift der Hinweiskarte in [renderRemovalNotice], je nachdem ob der Betrachter selbst der Autor ist. */
    fun removalNoticeHeadline(isOwnPost: Boolean): String =
        if (isOwnPost) {
            gettext("Ihr Beitrag wurde aus rechtlichen Gründen entfernt.")
        } else {
            gettext("Dieser Beitrag wurde aus rechtlichen Gründen entfernt.")
        }
}

/** "Melden" -- DSA Art. 16, siehe [ISocialNetworkService.reportPost] KDoc. Nicht sichtbar für den Autor oder einen nicht-`VISIBLE`n Post. */
private fun renderReportControl(
    row: SimplePanel,
    post: SocialPostDto,
    isAuthor: Boolean,
    onChanged: () -> Unit,
) {
    if (!SocialModerationUi.canReport(isAuthor = isAuthor, state = post.state)) return
    val reportButton = row.button(tr("Melden"), style = ButtonStyle.OUTLINESECONDARY)
    reportButton.onClick { openReportDialog(post, onChanged) }
}

private fun openReportDialog(
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = tr("Beitrag melden"))
    modal.div(
        tr(
            "Bitte begründen Sie, warum dieser Beitrag rechtswidrig ist (Digital Services Act, " +
                "Verordnung (EU) 2022/2065, Art. 16).",
        ),
    ) { addCssClasses("small text-muted") }
    val categoryOptions = SocialPostReportCategory.entries.map { it.name to socialPostReportCategoryLabel(it) }
    val categorySelect = modal.select(options = categoryOptions, label = tr("Kategorie"))
    val descriptionInput = modal.textArea(label = tr("Begründung"), rows = 3)
    val goodFaithCheck =
        modal.checkBox(
            label = tr("Ich erkläre, dass diese Meldung nach bestem Wissen zutreffend und in gutem Glauben abgegeben wird."),
        )
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val submitButton = Button(tr("Melden"), style = ButtonStyle.PRIMARY)
    submitButton.onClick {
        errorBox.hide()
        val description = descriptionInput.value.orEmpty().trim()
        val category = parseOptionalEnum<SocialPostReportCategory>(categorySelect.value)
        if (!Validation.isNonBlank(description) || category == null || goodFaithCheck.value != true) {
            errorBox.content = tr("Bitte Kategorie und Begründung angeben und die Gutgläubigkeitserklärung bestätigen.")
            errorBox.show()
            return@onClick
        }
        submitButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ISocialNetworkService>().reportPost(
                        SocialPostReportInput(
                            postId = post.id,
                            category = category,
                            description = description,
                            goodFaithConfirmed = true,
                        ),
                    )
                }
            submitButton.disabled = false
            if (result != null) {
                modal.hide()
                notifySuccess(tr("Meldung übermittelt."))
                onChanged()
            }
        }
    }
    modal.addButton(submitButton)
    modal.show()
}

/**
 * "Löschung beantragen (DSGVO)" -- der post-bezogene Art.-17-Antrag
 * ([ISocialNetworkService.requestContentErasure]). Sichtbar für JEDEN authentifizierten Aufrufer --
 * die betroffene Person ist nicht notwendig der Autor.
 */
private fun renderRequestErasureControl(
    row: SimplePanel,
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    val button = row.button(tr("Löschung beantragen (DSGVO)"), style = ButtonStyle.OUTLINESECONDARY)
    button.onClick { openRequestErasureDialog(post, onChanged) }
}

private fun openRequestErasureDialog(
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = tr("Löschung des Beitragsinhalts beantragen (Art. 17 DSGVO)"))
    modal.div(
        tr(
            "Beantragt die Löschung NUR des Beitragsinhalts (nicht des gesamten Beitrags) -- über " +
                "den Antrag entscheidet eine Administratorin oder ein Administrator gesondert.",
        ),
    ) { addCssClasses("small text-muted") }
    val reasonInput = modal.textArea(label = tr("Begründung"), rows = 3)
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val submitButton = Button(tr("Beantragen"), style = ButtonStyle.PRIMARY)
    submitButton.onClick {
        errorBox.hide()
        val reason = reasonInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(reason)) {
            errorBox.content = tr("Bitte eine Begründung angeben.")
            errorBox.show()
            return@onClick
        }
        submitButton.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    rpcService<ISocialNetworkService>().requestContentErasure(SocialPostErasureInput(postId = post.id, reason = reason))
                }
            submitButton.disabled = false
            if (result != null) {
                modal.hide()
                notifySuccess(tr("Löschantrag übermittelt."))
                onChanged()
            }
        }
    }
    modal.addButton(submitButton)
    modal.show()
}

/**
 * "Rechtlich entfernen" -- [ISocialNetworkService.removePostForLegalReason], BOARD/ADMIN. Die
 * Pflicht-`textArea` bekommt einen unübersehbaren Warnhinweis: die Begründung wird ab Welle V1.1.5
 * öffentlich sichtbar (Entscheidungspunkt E-B). Client-seitige Nichtleer-Vorprüfung + `errorBox` VOR
 * dem [confirmDialog] (Muster `CrowdfundingScreen.renderBoardDecidePanel`).
 */
private fun renderRemoveForLegalReasonControl(
    card: SimplePanel,
    post: SocialPostDto,
    onChanged: () -> Unit,
) {
    if (!SocialModerationUi.canRemove(AppState.session?.role)) return
    if (post.state == SocialPostState.REMOVED_LEGAL) return
    val row = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    val reasonInput = row.textArea(label = tr("Begründung"), rows = 2)
    row.div(tr("Diese Begründung wird öffentlich sichtbar -- auch für nicht angemeldete Besucher.")) {
        addCssClasses("text-danger small fw-bold")
    }
    val errorBox =
        row.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val removeButton = row.button(tr("Rechtlich entfernen"), style = ButtonStyle.OUTLINEDANGER)
    removeButton.onClick {
        errorBox.hide()
        val reason = reasonInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(reason)) {
            errorBox.content = tr("Bitte eine Begründung angeben.")
            errorBox.show()
            return@onClick
        }
        confirmDialog(
            title = tr("Beitrag rechtlich entfernen"),
            message =
                tr(
                    "Diese Begründung wird öffentlich sichtbar -- auch für nicht angemeldete Besucher. " +
                        "Es gibt keine LTR-Rückerstattung.",
                ),
            confirmLabel = tr("Entfernen"),
        ) {
            removeButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<ISocialNetworkService>().removePostForLegalReason(post.id, reason) }
                removeButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Beitrag entfernt."))
                    onChanged()
                }
            }
        }
    }
}

// ================================================================================================
// German label/badge-color tables
// ================================================================================================

/** [typeBadge]-Grammatik (`StatusBadge.kt`): die vom Autor gewählte Sichtbarkeitsstufe ist eine feste Klassifikation, kein fortschreitender Status. */
fun socialPostVisibilityLabel(visibility: SocialPostVisibility): String =
    when (visibility) {
        SocialPostVisibility.PUBLIC -> gettext("Öffentlich")
        SocialPostVisibility.MEMBERS_ONLY -> gettext("Angemeldete Nutzer")
        SocialPostVisibility.MEMBERS_AND_EXTERNAL -> gettext("Angemeldete + externe Nutzer")
    }

fun socialPostVisibilityColor(visibility: SocialPostVisibility): String =
    when (visibility) {
        SocialPostVisibility.PUBLIC -> "success"
        SocialPostVisibility.MEMBERS_ONLY -> "primary"
        SocialPostVisibility.MEMBERS_AND_EXTERNAL -> "info"
    }

// ================================================================================================
// Thread-Ansicht (Welle V1.1.2)
// ================================================================================================

/**
 * Thread-Ansicht, Welle V1.1.2 -- rendert den vollständigen Kommentarbaum ab der Wurzel des über
 * [postId] erreichten Knotens. [postId] darf ein BELIEBIGER Knoten sein, nicht nur die Wurzel
 * (siehe [Routes.SOCIAL_NETWORK_POST] KDoc) -- [ISocialNetworkService.getThread] selbst verlangt
 * zwingend eine Wurzel-Id (K4), deshalb wird zuerst [ISocialNetworkService.getPost] aufgerufen, um
 * die kanonische `rootId` aufzulösen, genau der client-seitige Auflösungsweg, den
 * `ISocialNetworkService.getThread`s eigene KDoc für eine Nicht-Wurzel-Id vorschreibt.
 *
 * `requireAuth`-gated wie [renderSocialNetworkScreen] -- kein separater Rollen-Split.
 */
fun renderSocialThreadScreen(
    container: SimplePanel,
    postId: String,
) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 900.px
            marginTop = 24.px
        }
    root.link(tr("← Zurück zur Timeline"), url = "#${Routes.SOCIAL_NETWORK}") { addCssClasses("small") }
    root.h1(tr("Thread"))
    val truncatedNotice = root.div()
    truncatedNotice.hide()
    val nodesPanel = root.vPanel(spacing = 10)
    nodesPanel.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadThread(rootId: String) {
        nodesPanel.removeAll()
        truncatedNotice.hide()
        AppScope.launch {
            val thread = guarded { rpcService<ISocialNetworkService>().getThread(rootId) } ?: return@launch
            nodesPanel.removeAll()
            if (thread.truncated) {
                // D10-analogue "sichtbarer Hinweis, nie stilles Abschneiden" (Implementierungsplan
                // § 5.2) -- [SocialThreadDto.totalNodeCount] macht die Deckelung fuer den Nutzer
                // konkret statt nur "es fehlt etwas" zu sagen.
                truncatedNotice.content =
                    gettext(
                        "Dieser Thread hat %1 Beiträge -- nur die ersten 5 000 werden angezeigt.",
                        thread.totalNodeCount.toString(),
                    )
                truncatedNotice.addCssClasses("text-warning small")
                truncatedNotice.show()
            }
            if (thread.nodes.isEmpty()) {
                nodesPanel.p(tr("Dieser Beitrag ist nicht (mehr) verfügbar.")) { addCssClasses("text-muted small") }
                return@launch
            }
            // Fund #12 (Review Runde 1, 2026-08-18): [thread.nodes] is the flat preorder, root first
            // (K1) -- [rootVisibility] is threaded down to every node's reply form so it can show
            // the WURZEL's visibility (S5), not the visibility of whichever direct node the form
            // happens to be attached to. See [renderReplyForm] KDoc for why that distinction matters.
            val rootVisibility = thread.nodes.first().visibility
            thread.nodes.forEach { node -> renderThreadNode(nodesPanel, node, rootVisibility) { loadThread(rootId) } }
        }
    }

    AppScope.launch {
        // getPost zuerst -- liefert fuer JEDE gueltige Id (Wurzel oder Nachfahre) die kanonische
        // rootId; getThread selbst wuerde eine Nicht-Wurzel-Id mit NotFoundException ablehnen (K4).
        //
        // Welle V1.1.5 (E-B): NotFoundException wird HIER, ausserhalb von guarded(), abgefangen --
        // guarded() wuerde sofort einen "Nicht gefunden."-Toast zeigen, bevor der explizite
        // getRemovalNotice-Fallback ueberhaupt eine Chance hat. Jede andere Exception (inkl.
        // CancellationException) laeuft weiterhin durch guarded()'s normale Fehlerbehandlung.
        val post =
            try {
                rpcService<ISocialNetworkService>().getPost(postId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotFoundException) {
                null
            } catch (e: Throwable) {
                // Jede andere Exception: dieselbe Fehlerbehandlung wie ueberall sonst (Toast +
                // null), realisiert durch einen zweiten Aufruf ueber guarded() -- getPost ist rein
                // lesend, ein Zweitaufruf ist folgenlos.
                guarded { rpcService<ISocialNetworkService>().getPost(postId) }
            }
        if (post == null) {
            nodesPanel.removeAll()
            val notice = guarded { rpcService<ISocialNetworkService>().getRemovalNotice(postId) }
            nodesPanel.removeAll()
            if (notice != null) {
                renderRemovalNotice(nodesPanel, notice)
            } else {
                nodesPanel.p(tr("Dieser Beitrag ist nicht (mehr) verfügbar.")) { addCssClasses("text-muted small") }
            }
            return@launch
        }
        loadThread(post.rootId)
    }
}

/**
 * Ein einzelner Thread-Knoten -- Einrückung nach [SocialPostDto.depth], visuell gedeckelt bei 8
 * Ebenen (Implementierungsplan § 5.2: "64 Ebenen Einrückung sind kein Layout"), darüber ein
 * "↳ Fortsetzung"-Hinweis statt weiter einzurücken. [thread.nodes] liefert bereits die vollständige
 * Präorder-Reihenfolge (K1: Geschwister nach Gesamtgewicht absteigend) -- diese Funktion rendert
 * nur noch flach in dieser Reihenfolge, baut selbst keinen Baum auf.
 */
private fun renderThreadNode(
    panel: SimplePanel,
    node: SocialPostDto,
    rootVisibility: SocialPostVisibility,
    onChanged: () -> Unit,
) {
    val isAuthor = AppState.session?.memberId == node.authorMemberId
    val cappedDepth = minOf(node.depth, 8)
    val card =
        panel.vPanel(spacing = 6) {
            addCssClasses("border rounded p-3")
            marginLeft = (cappedDepth * 16).px
        }
    if (node.depth > 8) {
        card.div(tr("↳ Fortsetzung")) { addCssClasses("text-muted small") }
    }

    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.div(node.authorDisplayName) { addCssClasses("flex-grow-1 fw-bold") }
    headerRow.statusBadge(socialPostVisibilityLabel(node.visibility), socialPostVisibilityColor(node.visibility))

    renderPostContentText(card, node)
    card.div(gettext("Veröffentlicht am %1", node.publishedAt)) { addCssClasses("text-muted small") }

    val weightRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val totalWeightCell = weightRow.vPanel(spacing = 2)
    totalWeightCell.div(tr("Gesamtgewicht")) { addCssClasses("text-muted small") }
    totalWeightCell.ltrSpan(node.totalCurrentWeightLtr)
    val ownWeightCell = weightRow.vPanel(spacing = 2)
    ownWeightCell.div(tr("Eigengewicht")) { addCssClasses("text-muted small") }
    ownWeightCell.ltrSpan(node.ownCurrentWeightLtr)

    card.div(
        gettext("%1 Antworten · %2 Boosts", node.totalDescendantCount.toString(), node.boostCount.toString()),
    ) { addCssClasses("text-muted small") }

    val actionsRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    renderBoostControl(actionsRow, node, onChanged)
    val replyPanel = card.vPanel(spacing = 6) { hide() }
    val replyButton = actionsRow.button(tr("Antworten"), style = ButtonStyle.OUTLINESECONDARY)
    replyButton.onClick { if (replyPanel.visible) replyPanel.hide() else replyPanel.show() }
    renderReplyForm(replyPanel, node, rootVisibility) { onChanged() }
    renderReportControl(actionsRow, node, isAuthor, onChanged)
    renderRequestErasureControl(actionsRow, node, onChanged)

    if (isAuthor && node.state == SocialPostState.VISIBLE) {
        renderHideOwnPostControl(card, node, onChanged)
    }
    renderRemoveForLegalReasonControl(card, node, onChanged)
}

/**
 * Antwort-Formular, inline unter jedem Knoten (Implementierungsplan § 5.2). **Kein Sichtbarkeits-
 * Auswahlfeld** (S5) -- stattdessen ein statischer Hinweis, welche Sichtbarkeitsstufe die Antwort
 * erben wird (vom WURZEL-Post, siehe [SocialCommentInput] KDoc), damit S5 für den Nutzer sichtbar
 * ist statt eine Auswahl anzubieten, die der Server ohnehin ignoriert.
 *
 * Fund #12 (Review Runde 1, 2026-08-18): der Hinweis zeigt [rootVisibility] -- die Sichtbarkeit des
 * WURZEL-Posts des Threads --, NICHT [parent].visibility (die Sichtbarkeit des direkten Knotens,
 * unter dem dieses Formular hängt). Genau diese Verwechslung (direkter Elternteil statt Wurzel) ist
 * server-seitig bereits die Ursache von S5 -- eine Antwort erbt IMMER von der Wurzel, nie vom
 * direkten Elternteil (siehe `SocialNetworkService.rootVisibilityOf` KDoc), also darf auch die
 * Client-Anzeige nicht suggerieren, der direkte Elternteil sei die maßgebliche Quelle.
 */
private fun renderReplyForm(
    root: SimplePanel,
    parent: SocialPostDto,
    rootVisibility: SocialPostVisibility,
    onCompleted: () -> Unit,
) {
    val contentInput = root.textArea(label = tr("Antwort"), rows = 2)
    val weightInput = root.text(label = tr("Einsatz (LTR)"))
    root.div(
        gettext("Ihre Antwort erbt die Sichtbarkeit des Ursprungsbeitrags: %1.", socialPostVisibilityLabel(rootVisibility)),
    ) { addCssClasses("text-muted small") }
    // D7-analogue (kein Rueckerstattungspfad) -- identisch zu renderComposeForm's eigener Copy.
    root.div(
        tr(
            "Ihr Einsatz wird NICHT zurückerstattet -- auch nicht, wenn Sie die Antwort später " +
                "unsichtbar machen.",
        ),
    ) { addCssClasses("text-muted small") }
    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = root.button(tr("Antworten"), style = ButtonStyle.PRIMARY)
    submitButton.onClick {
        errorBox.hide()
        val content = contentInput.value.orEmpty().trim()
        val weightText = weightInput.value.orEmpty().trim()
        if (!Validation.isNonBlank(content) || !Validation.isPositiveDecimal(weightText)) {
            errorBox.content = tr("Bitte Inhalt und einen positiven Einsatz (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        // Stolperfalle 15 (Review Runde 1, 2026-08-18): round to 2 decimal places client-side, same
        // as renderBoostControl.
        val weight = Validation.roundToTwoDecimalPlaces(weightText.toDouble()).toDecimal()
        confirmDialog(
            title = tr("Antwort veröffentlichen"),
            message =
                gettext(
                    "Es werden %1 aus Ihrem freien LTR-Guthaben gebunden. Dieser Einsatz wird NICHT " +
                        "zurückerstattet.",
                    formatLtr(weight),
                ),
            confirmLabel = tr("Antworten"),
        ) {
            submitButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<ISocialNetworkService>().createComment(
                            SocialCommentInput(parentId = parent.id, content = content, initialWeightLtr = weight),
                        )
                    }
                submitButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Antwort veröffentlicht."))
                    contentInput.value = null
                    weightInput.value = null
                    onCompleted()
                }
            }
        }
    }
}
