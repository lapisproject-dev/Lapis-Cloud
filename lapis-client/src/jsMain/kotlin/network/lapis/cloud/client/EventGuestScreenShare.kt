package network.lapis.cloud.client

import io.kvision.i18n.gettext

/**
 * V1.9.282 -- what the call view of an event guest tells the person about the screen share. The words are the single source for the live region.
 */
internal enum class GuestShareNotice {
    /** A moderator allowed it just now. */
    ALLOWED,

    /** A moderator allowed it, but this device cannot share a screen (most phones). */
    ALLOWED_BUT_UNSUPPORTED,

    /** The permission was withdrawn while the person was NOT sharing. */
    WITHDRAWN,

    /** The permission was withdrawn while the person WAS sharing: the share was stopped. */
    STOPPED_BY_MODERATION,
}

internal fun guestShareNoticeText(notice: GuestShareNotice): String =
    when (notice) {
        GuestShareNotice.ALLOWED -> gettext("Sie dürfen jetzt Ihren Bildschirm teilen.")
        GuestShareNotice.ALLOWED_BUT_UNSUPPORTED -> gettext("Bildschirm teilen ist auf diesem Gerät nicht möglich.")
        GuestShareNotice.WITHDRAWN -> gettext("Die Freigabe zum Bildschirmteilen wurde beendet.")
        GuestShareNotice.STOPPED_BY_MODERATION -> gettext("Die Moderation hat Ihre Bildschirmfreigabe beendet.")
    }

/**
 * The pure rule of the guest's screen share notice for one permission change: [previous] -> [allowed], [sharing] now, [supported] on this device.
 * `null` when nothing is to be said (no change, or a change that needs no sentence).
 */
internal fun guestShareNoticeFor(
    previous: Boolean,
    allowed: Boolean,
    sharing: Boolean,
    supported: Boolean,
): GuestShareNotice? =
    when {
        allowed == previous -> null
        allowed && supported -> GuestShareNotice.ALLOWED
        allowed -> GuestShareNotice.ALLOWED_BUT_UNSUPPORTED
        sharing -> GuestShareNotice.STOPPED_BY_MODERATION
        else -> GuestShareNotice.WITHDRAWN
    }

/** Whether the screen share button is shown: only while it is allowed AND this device can share, or while a share is running (so it can always be ended). */
internal fun guestShareButtonVisible(
    allowed: Boolean,
    supported: Boolean,
    sharing: Boolean,
): Boolean = sharing || (allowed && supported)

/**
 * Spaces the sentences of one live region at least [gapMs] apart so a screen reader is not run over: a sentence is shown at once if the last one
 * is old enough, otherwise it is kept (only the LATEST waits) until [flush] is called after the gap. The clock is a parameter.
 */
internal class GuestNoticeThrottle(
    private val now: () -> Double,
    private val gapMs: Double = 1_000.0,
) {
    private var lastAt = Double.NEGATIVE_INFINITY
    private var pending: String? = null

    /** `true` while a sentence waits for [flush]. */
    val hasPending: Boolean get() = pending != null

    /** The sentence to show NOW, or `null` if it had to wait. */
    fun offer(text: String): String? {
        val t = now()
        if (t - lastAt >= gapMs) {
            lastAt = t
            pending = null
            return text
        }
        pending = text
        return null
    }

    /** The waiting sentence if the gap has passed, else `null`. */
    fun flush(): String? {
        val text = pending ?: return null
        val t = now()
        if (t - lastAt < gapMs) return null
        lastAt = t
        pending = null
        return text
    }

    /** Milliseconds until a waiting sentence may be shown (0 if none waits or the gap has passed). */
    fun waitMs(): Int = if (pending == null) 0 else maxOf(0.0, gapMs - (now() - lastAt)).toInt() + 1

    fun clear() {
        pending = null
    }
}
