package network.lapis.cloud.server.share

import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul
import network.lapis.cloud.server.routes.PublicUiStrings

/**
 * Everything the renderers and the route need for ONE share bar (V1.9.217). The route builds it (or gets `null`), and derives BOTH the
 * markup AND the `script-src 'self'` CSP delta from it, so bar and policy can never disagree.
 */
internal data class ShareBarModel(
    val url: String,
    val title: String,
    val text: String,
    val networks: List<ShareNetwork>,
    val needsScript: Boolean,
)

/** `null` unless the configuration renders a bar. [rawTitle]/[rawText] are normalized and cut here. */
internal fun ShareConfig.barFor(
    url: String,
    rawTitle: String,
    rawText: String,
): ShareBarModel? {
    if (!rendersBar) return null
    val title = ShareLinks.truncateCodepoints(value = ShareLinks.normalize(rawTitle), max = ShareLinks.TITLE_MAX_CODEPOINTS)
    val text = ShareLinks.truncateCodepoints(value = ShareLinks.normalize(rawText), max = ShareLinks.TEXT_MAX_CODEPOINTS)
    return ShareBarModel(url = url, title = title, text = text, networks = orderedNetworks, needsScript = needsScript)
}

private fun labelOf(
    network: ShareNetwork,
    strings: PublicUiStrings,
): String =
    when (network) {
        ShareNetwork.NATIVE -> strings.shareNative
        ShareNetwork.COPY -> strings.shareCopy
        ShareNetwork.EMAIL -> strings.shareEmail
        else -> strings.shareOnFormat.format(network.brandName)
    }

/** The share bar. Place it OUTSIDE any `lang="de"` wrapper so the labels inherit the page's UI language. */
internal fun FlowContent.renderShareBar(
    model: ShareBarModel,
    strings: PublicUiStrings,
) {
    val targets = ShareLinks.targets(url = model.url, title = model.title, text = model.text, networks = model.networks)
    section(classes = "share") {
        attributes["aria-labelledby"] = "share-h"
        attributes["data-share-url"] = model.url
        attributes["data-share-title"] = model.title
        attributes["data-share-text"] = model.text
        attributes["data-share-copied"] = strings.shareCopied
        attributes["data-share-copy-failed"] = strings.shareCopyFailed
        h2 {
            id = "share-h"
            +strings.shareHeading
        }
        ul(classes = "share-list") {
            if (ShareNetwork.NATIVE in model.networks) {
                val label = labelOf(network = ShareNetwork.NATIVE, strings = strings)
                li(classes = "share-item share-item--native") {
                    attributes["hidden"] = "hidden"
                    button(type = kotlinx.html.ButtonType.button, classes = "share-native") {
                        attributes["aria-label"] = label
                        attributes["title"] = label
                        span(classes = "share-ico share-ico--native") { attributes["aria-hidden"] = "true" }
                    }
                }
            }
            for (entry in targets) {
                val label = labelOf(network = entry.network, strings = strings)
                li(classes = "share-item") {
                    a(href = entry.href) {
                        attributes["aria-label"] = label
                        attributes["title"] = label
                        when (entry.network) {
                            ShareNetwork.COPY -> attributes["data-share-copy"] = "data-share-copy"
                            ShareNetwork.EMAIL -> Unit
                            else -> {
                                this.target = "_blank"
                                this.rel = "noopener noreferrer"
                            }
                        }
                        span(classes = "share-ico share-ico--${entry.network.configName}") { attributes["aria-hidden"] = "true" }
                    }
                }
            }
        }
        p(classes = "share-status") {
            attributes["role"] = "status"
            attributes["aria-live"] = "polite"
        }
    }
}

/** `<script src="/s/assets/share.js" defer>` iff the bar needs it. */
internal fun HEAD.renderShareScript(model: ShareBarModel?) {
    if (model == null || !model.needsScript) return
    script(src = SHARE_SCRIPT_PATH) { defer = true }
}
