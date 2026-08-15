package network.lapis.cloud.client

import io.kvision.i18n.tr
import io.kvision.toast.ToastContainer
import io.kvision.toast.ToastContainerPosition

/**
 * Thin wrapper around a single Bootstrap [ToastContainer] shared by every screen. [ToastContainer]
 * attaches itself directly to the KVision root (`Root.getFirstRoot()`, see its own KDoc) rather
 * than being added to any particular screen's panel, so one instance, created once after the root
 * panel exists (see `App.start()`), is enough for the whole app's lifetime.
 */
private var toastContainer: ToastContainer? = null

/** Must be called exactly once, after the KVision root panel has been created. */
fun initNotifications() {
    if (toastContainer == null) {
        toastContainer = ToastContainer(ToastContainerPosition.TOPRIGHT)
    }
}

fun notifyError(message: String) {
    toastContainer?.showToast(message, title = tr("Fehler"), className = "text-bg-danger")
}

fun notifySuccess(message: String) {
    toastContainer?.showToast(message, title = tr("Erfolgreich"), className = "text-bg-success")
}

fun notifyInfo(message: String) {
    toastContainer?.showToast(message, className = "text-bg-info")
}
