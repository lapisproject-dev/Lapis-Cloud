package network.lapis.cloud.shared.branding

/**
 * V1.9.214 -- single source of the Lapis Cloud brand mark geometry (cloud and lapis stone, copied from
 * `cloud.lapisproject.dev`'s `Logo.astro`). The client assembles its two-variant inline SVG from these constants
 * (colours there live in `theme.css`), the server renders the dark variant into the public chrome with
 * [DarkPalette] as presentation attributes (CSP: no `style=`). The dark palette mirrors the `.lapis-brand-mark--dark`
 * rules in `theme.css`.
 */
object LapisCloudMark {
    const val VIEW_BOX = "128 193 340 340"
    const val CLOUD_D =
        "M180 410 C154.061 403.107 136.886 378.499 139.36 351.774 C141.835 325.05 163.237 304.013 " +
            "190 302 C192.78 266.701 218.433 237.428 253.062 230.041 C287.691 222.653 323.057 238.908 " +
            "340 270 C360.809 256.583 387.433 256.154 408.664 268.893 C429.895 281.631 442.046 305.325 " +
            "440 330 C452.038 340.208 458.55 355.501 457.565 371.254 C456.581 387.007 448.216 401.37 " +
            "435 410 Z"
    const val F1_D = "M226 395 L256 350 L281 395 Z"
    const val F2_D = "M256 350 L356 350 L331 395 L281 395 Z"
    const val F3_D = "M356 350 L386 395 L331 395 Z"
    const val F4_D = "M226 395 L281 395 L306 500 Z"
    const val F5_D = "M281 395 L331 395 L306 500 Z"
    const val F6_D = "M331 395 L386 395 L306 500 Z"
    const val SPARK_D =
        "M395 331 L397.828 342.172 L409 345 L397.828 347.828 L395 359 L392.172 347.828 L381 345 L392.172 342.172 Z"
    const val STROKE_CLOUD = "12"
    const val STROKE_EDGE = "5"

    /** Dark-variant colours; must equal the `.lapis-brand-mark--dark .bm-*` rules in `theme.css`. */
    object DarkPalette {
        const val CLOUD_FILL = "#16284b"
        const val CLOUD_STROKE = "#8fb0e3"
        const val EDGE = "#0b1630"
        const val F1 = "#7ea6e6"
        const val F2 = "#bcd3f5"
        const val F3 = "#4f84d2"
        const val F4 = "#3f6fbd"
        const val F5 = "#5d8cd8"
        const val F6 = "#2b55a3"
        const val SPARK = "#e6bc3a"
    }
}
