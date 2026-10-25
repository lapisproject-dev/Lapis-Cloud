package network.lapis.cloud.server.theme

/**
 * The compiled-in default colours -- the runtime source of the default theme (no file read, nothing that can fail at startup).
 * `ThemeDefaultsConsistencyTest` pins these values to `theme.css` (client), the `lapis-default.theme.json` resource and the preset.
 */
object ThemeDefaults {
    val LIGHT: Map<String, String> =
        linkedMapOf(
            "bg" to "#FFFFFF",
            "surface" to "#FFFFFF",
            "surface-sunken" to "#F6F7F9",
            "text" to "#14181E",
            "muted" to "#5A626E",
            "border" to "#E3E6EB",
            "border-strong" to "#C9CFD8",
            "nav" to "#14181E",
            "nav-hover-bg" to "#1E242E",
            "nav-text" to "#EDEAE3",
            "nav-muted" to "#A8AEB8",
            "accent" to "#1E56C8",
            "accent-strong" to "#17429B",
            "accent-soft" to "#E7EDFB",
            "accent-contrast" to "#FFFFFF",
            "invalid" to "#A52834",
        )

    val DARK: Map<String, String> =
        linkedMapOf(
            "bg" to "#14181E",
            "surface" to "#1B2028",
            "surface-sunken" to "#232933",
            "text" to "#E8E6E0",
            "muted" to "#9AA1AC",
            "border" to "#333A44",
            "border-strong" to "#454D59",
            "nav" to "#0B0E12",
            "nav-hover-bg" to "#1E242E",
            "nav-text" to "#EDEAE3",
            "nav-muted" to "#A8AEB8",
            "accent" to "#5B8FF0",
            "accent-strong" to "#7CA6F3",
            "accent-soft" to "#1B2A44",
            "accent-contrast" to "#0B0E12",
            "invalid" to "#EA868F",
        )
}
