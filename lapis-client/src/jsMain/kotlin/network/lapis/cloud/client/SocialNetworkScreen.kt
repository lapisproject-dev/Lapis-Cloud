package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
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
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialTimelineQuery
import network.lapis.cloud.shared.rpc.ISocialNetworkService

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" -- self-contained domain
 * ([ISocialNetworkService]) covering the Welle-1 read/write surface: Timeline lesen, Post
 * verfassen (Inhalt + LTR-Einsatz + Sichtbarkeitsstufe), und den eigenen Post unsichtbar machen.
 * See `32-social-network.kuml.kts` file header and the vault concept notes ("Soziales Netzwerk",
 * "Meritokratisches System und Libertaler" § "Im sozialen Netz") for the full fachlich model this
 * screen surfaces.
 *
 * **Role gating** (verified against `SocialNetworkService.kt`'s actual call sites, same discipline
 * `CrowdfundingScreen.kt`'s own KDoc documents):
 * - [ISocialNetworkService.createPost] -- MEMBER+, additionally must be `ACTIVE`
 *   (`requireActiveMembership` INSIDE the server transaction, not reachable as an `AccountRole`
 *   predicate -- same "not surfaced client-side, server rejects with the ordinary `guarded()`
 *   ForbiddenException toast" posture as `CrowdfundingScreen`'s `submitProject`). Welle V1.1.4
 *   widens this to `LTR_ELIGIBLE` (also admits FRIEND) -- not yet the case in this wave.
 * - [ISocialNetworkService.listTimeline]/[ISocialNetworkService.getPost] -- any authenticated
 *   member, filtered server-side by the caller's own visibility tier.
 * - [ISocialNetworkService.hideOwnPost] -- the author only (`ForbiddenException` otherwise);
 *   rendered here only as a button on the author's OWN post cards (`session.memberId` compared
 *   against [SocialPostDto.authorMemberId]), so a non-author never sees the control at all.
 *
 * **D7-analogue (no refund)**: mirrors `CrowdfundingScreen.kt`'s own must-fix D7 -- there is no
 * `updatePost`/refund path anywhere in this domain; `hideOwnPost` does not return the stake either.
 * Stated plainly under the weight input, same placement `CrowdfundingScreen`'s own copy uses.
 *
 * **Confirm-dialog tier (D4-analogue)**: `createPost` uses the plain [confirmDialog] (Tier 1
 * "Kostenpflichtig", material to the author's own balance) -- same tier `CrowdfundingScreen`'s
 * `submitProject` uses. `hideOwnPost` also gets a light confirm (irreversible, see [SocialPostState]
 * KDoc "S6" -- no `unhideOwnPost` exists).
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
    composerPanel.renderMyLtrBalanceInline()

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
}

// ================================================================================================
// Compose-Formular
// ================================================================================================

private fun renderComposeForm(
    root: SimplePanel,
    onCompleted: () -> Unit,
) {
    val panel = root.vPanel(spacing = 6)
    val contentInput = panel.textArea(label = tr("Inhalt"), rows = 3)
    val weightInput = panel.text(label = tr("Einsatz (LTR)"))
    val visibilityOptions = SocialPostVisibility.entries.map { it.name to socialPostVisibilityLabel(it) }
    val visibilitySelect =
        panel.select(options = visibilityOptions, value = SocialPostVisibility.MEMBERS_ONLY.name, label = tr("Sichtbarkeit"))

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
        val visibility = parseOptionalEnum<SocialPostVisibility>(visibilitySelect.value) ?: SocialPostVisibility.MEMBERS_ONLY

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

    card.div(post.content) { addCssClasses("small") }
    card.div(gettext("Veröffentlicht am %1", post.publishedAt)) { addCssClasses("text-muted small") }

    // Zwei Kennzahlen nebeneinander, nie zu einer verschmolzen (Meritokratie-Konzept "Anzeige in
    // der Timeline"): das aktuelle Gewicht des Beitrags UND das Gewicht (freier LTR-Bestand) des
    // Autors -- macht ökonomisches Gewicht direkt sichtbar statt Eitelkeits-Metriken wie
    // Followerzahlen.
    //
    // Security-Audit-Fund S-1 (2026-08-18): [SocialPostDto.authorFreeBalanceLtr] ist jetzt `null`,
    // wenn der Server den Betrachter nicht als ORGANIZATION_MEMBER einstuft (z. B. ein
    // selbst-registriertes FRIEND-Konto) -- die zweite Kennzahl wird dann schlicht weggelassen,
    // statt einen falschen Platzhalterwert (z. B. 0,00 LTR) vorzutäuschen.
    val weightRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val postWeightCell = weightRow.vPanel(spacing = 2)
    postWeightCell.div(tr("Aktuelles Gewicht des Beitrags")) { addCssClasses("text-muted small") }
    postWeightCell.ltrSpan(post.ownCurrentWeightLtr)
    val authorFreeBalance = post.authorFreeBalanceLtr
    if (authorFreeBalance != null) {
        val authorWeightCell = weightRow.vPanel(spacing = 2)
        authorWeightCell.div(tr("Gewicht des Autors")) { addCssClasses("text-muted small") }
        authorWeightCell.ltrSpan(authorFreeBalance)
    }

    if (isAuthor && post.state == SocialPostState.VISIBLE) {
        renderHideOwnPostControl(card, post, onChanged)
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
