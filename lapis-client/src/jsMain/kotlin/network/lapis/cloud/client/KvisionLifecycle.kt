package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.snabbdom.VNode

/**
 * Adds [widget] to this container with its lifecycle hooks registered BEFORE the widget is added.
 *
 * **Why the order matters** (found in V1.4.25 on `dataTable`, audited across the whole client in the
 * "late hooks" wave): KVision's `Widget.addAfterInsertHook`/`addAfterDestroyHook` do not just store a
 * lambda -- the FIRST hook of a widget calls `useSnabbdomDistinctKey()`, which from then on writes a
 * snabbdom `key` into every vnode of that widget. In a container that is already mounted, every `add`
 * renders synchronously, so a widget built with a factory call (`container.div { }`) has already been
 * rendered by the time the next line registers a hook. The key then changes from `undefined` to
 * `kv_widget_N` between two renders; the next patch of the enclosing `Root` no longer recognises the
 * mounted vnode, throws the DOM element away, builds a new one and runs the DESTROY hook on a widget
 * that is still alive. The insert hook registered that late does not fire for the original element
 * either -- only for the replacement, and only if some patch happens at all.
 *
 * Cleanup hooks (timers, listeners, chart instances, media sessions) are the dangerous case: they run
 * too early, once, right after the screen came up. Build the widget with its constructor, register the
 * hooks through this function and the key is stable from the very first vnode, so the destroy hook only
 * runs when the widget really leaves the document.
 *
 * A re-attach after a destroy (`I18n.language` restarts the KVision root and rebuilds every element from
 * the same widget objects) fires destroy and then insert again -- [onInsert] must therefore be safe to
 * run more than once per widget object, and [onDestroy] must leave the widget re-attachable.
 *
 * NOT for the idiom `getElement()?.let { ... } ?: addAfterInsertHook { ... }` (see `setStaticA11yLabel`
 * in `ConferenceScreen.kt`): that one registers the hook only while the widget has no element yet, i.e.
 * before its first render, which is already the correct order.
 */
internal fun <W : Widget> Container.addWithLifecycle(
    widget: W,
    onInsert: ((VNode) -> Unit)? = null,
    onDestroy: (() -> Unit)? = null,
): W {
    if (onInsert != null) widget.addAfterInsertHook { vnode -> onInsert(vnode) }
    if (onDestroy != null) widget.addAfterDestroyHook { onDestroy() }
    add(widget)
    return widget
}
