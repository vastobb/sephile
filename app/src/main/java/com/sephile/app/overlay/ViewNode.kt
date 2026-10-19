package com.sephile.app.overlay

import android.graphics.Rect

/**
 * One element captured from the foreground app's view tree.
 *
 * Bounds are in *screen* coordinates (what the accessibility service reports via
 * `getBoundsInScreen`). These are NOT memory addresses — there is no pokeable
 * address for a UI element; it's a live object inside the target process.
 * See the class doc of [InspectorService] for the honest capability boundary.
 */
data class ViewNode(
    val id: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val resourceId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val depth: Int,
    /** Human-readable path, e.g. `FrameLayout > LinearLayout > Button`. */
    val path: String
) {
    /** Short label for list rows. */
    val label: String get() = when {
        text.isNotBlank() -> text.take(40)
        contentDesc.isNotBlank() -> contentDesc.take(40)
        resourceId != null -> resourceId.substringAfterLast('/').take(40)
        else -> className.substringAfterLast('.').take(40)
    }

    val isZeroSize: Boolean get() = bounds.width() <= 0 || bounds.height() <= 0
}
