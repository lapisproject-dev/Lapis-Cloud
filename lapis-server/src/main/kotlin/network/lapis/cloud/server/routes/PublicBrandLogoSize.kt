package network.lapis.cloud.server.routes

/**
 * V1.9.267 -- the public pages' header logo size. Must equal `--lapis-brand-logo-size`, the `.lapis-brand-logo img`
 * max-widths and the 767.98px breakpoint in `lapis-client/src/jsMain/resources/theme.css` (V1.9.210). Drift is caught by
 * `PublicBrandLogoSizeDriftTest`.
 */
internal object PublicBrandLogoSize {
    const val SIZE = "2rem"

    /** For the mark SVG attributes and the budget test. */
    const val SIZE_PX = 32
    const val MAX_WIDTH_PX = 240
    const val PHONE_MAX_WIDTH_PX = 160
    const val PHONE_BREAKPOINT = "767.98px"
}
