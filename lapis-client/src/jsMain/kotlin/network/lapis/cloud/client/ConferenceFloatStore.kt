package network.lapis.cloud.client

import kotlinx.browser.window

/*
 * V1.9.71 -- the ONLY `localStorage` access of the floating conference window. It stores one short string: the wish (window or bar) and
 * the anchor (corner, two distances, width). No room, no person, no identifier -- so it is deliberately NOT cleared on sign-out or on an
 * account switch (it is a per-device display preference, like the theme). The running state of the dock is cleared as in V1.9.70.
 *
 * Every access is wrapped: storage can be missing or throw (private window, blocked site data), and the window must render correctly
 * without it. A value that does not match the whitelist codec is removed and replaced by the default.
 */

/** The three calls the store needs; a fake in tests, `window.localStorage` in the browser. */
internal interface StorageLike {
    fun getItem(key: String): String?

    fun setItem(
        key: String,
        value: String,
    )

    fun removeItem(key: String)
}

private object BrowserStorage : StorageLike {
    override fun getItem(key: String): String? = window.localStorage.getItem(key)

    override fun setItem(
        key: String,
        value: String,
    ) = window.localStorage.setItem(key, value)

    override fun removeItem(key: String) = window.localStorage.removeItem(key)
}

internal object ConferenceFloatStore {
    internal var storageForTest: StorageLike? = null

    private fun storage(): StorageLike = storageForTest ?: BrowserStorage

    fun load(): FloatPreference {
        val raw = runCatching { storage().getItem(FLOAT_STORAGE_KEY) }.getOrNull()
        return when (val decoded = decodeFloatPreference(raw)) {
            is FloatDecode.Ok -> decoded.pref
            FloatDecode.Absent -> DEFAULT_FLOAT_PREFERENCE
            FloatDecode.Invalid -> {
                runCatching { storage().removeItem(FLOAT_STORAGE_KEY) }
                DEFAULT_FLOAT_PREFERENCE
            }
        }
    }

    fun save(p: FloatPreference) {
        runCatching { storage().setItem(FLOAT_STORAGE_KEY, encodeFloatPreference(p)) }
    }
}
