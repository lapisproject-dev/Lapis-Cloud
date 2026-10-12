package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.Image
import io.kvision.html.Tag
import network.lapis.cloud.shared.domain.CountryCodes

/**
 * Welle V1.9.121 -- the flag of a country as a small `<img>` served from our own origin (`/assets/flag-icons-v7/4x3/<code>.svg`,
 * flag-icons 7.5.0, see `PROVENANCE.adoc`). Never an emoji (Windows does not render them) and never a third-party host.
 *
 * The flag is DECORATION: `alt=""` and `aria-hidden="true"`, the country name next to it is the accessible name. It sits to the LEFT
 * of the name and is never shown alone. A missing file (404) makes the element disappear quietly -- no console output.
 */
internal object CountryFlag {
    /** Third occurrence of the directory name (after `FLAG_ICONS_ASSET_DIR` on the server and `into("flag-icons-v7")` in Gradle); `FlagAssetsTest` keeps them together. */
    const val DIR = "flag-icons-v7"

    /** `true` for a code that has a flag file (the 250 selectable countries); `XS` / `ZZ` have none. */
    fun hasFlag(code: String): Boolean = CountryCodes.isCountry(code)

    fun url(code: String): String = "/assets/$DIR/4x3/${code.lowercase()}.svg"
}

/** The flag image for [code], or `null` for a value without a flag. Created, not yet attached. */
internal fun countryFlagImage(code: String): Image? {
    if (!CountryFlag.hasFlag(code)) return null
    return Image(CountryFlag.url(code), alt = "").apply {
        addCssClass("lapis-flag")
        setAttribute("aria-hidden", "true")
        setAttribute("width", "20")
        setAttribute("height", "15")
        setAttribute("loading", "lazy")
        setAttribute("decoding", "async")
        onEvent { error = { hide() } }
    }
}

/** A spacer of flag width, so names of flag-less entries ("staatenlos") line up with the others. */
internal fun flagSpacer(): Tag =
    Tag(io.kvision.html.TAG.SPAN, className = "lapis-flag-spacer").apply {
        setAttribute("aria-hidden", "true")
    }

fun Container.countryFlag(code: String): Image? = countryFlagImage(code)?.also { add(it) }
