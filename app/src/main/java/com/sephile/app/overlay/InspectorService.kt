package com.sephile.app.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "layout interceptor".
 *
 * This is a standard Android [AccessibilityService] — the same mechanism screen
 * readers (TalkBack) and UI-testing tools use. When the user enables it in
 * Settings ▸ Accessibility, the system delivers it the foreground app's *view
 * tree*, which it can read (class, text, resource id, bounds) and act on
 * (click, set text on editable fields).
 *
 * ── What this can and cannot do ──────────────────────────────────────────
 *  • Draws boundaries on every element the target exposes as a view. Works for
 *    normal Android apps (Settings, browsers, most non-game apps).
 *  • Does NOT work inside games that draw their own UI with OpenGL/Vulkan
 *    (Minecraft, most games). There is no view tree there — the whole screen is
 *    a single surface, so you'd see one rectangle and no "Play" button object.
 *  • "Address" here is a resource id + screen coordinates. It is NOT a memory
 *    address you can poke; a view is a live object inside the target process.
 *  • Runtime edits are limited to what accessibility permits: ACTION_CLICK and
 *    ACTION_SET_TEXT on editable fields. Renaming arbitrary buttons / resizing
 *    views of another app is not possible from outside its process.
 * ─────────────────────────────────────────────────────────────────────────
 */
class InspectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We snapshot on demand from the overlay (see captureNow); no need to
        // react to every window-changed event here.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Walk the current window's view tree and return a flat list of [ViewNode].
     * Runs on the caller's thread; keep it short. Returns empty if the service
     * isn't enabled.
     */
    fun captureNow(): List<ViewNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<ViewNode>()
        val rect = Rect()
        walk(root, out, rect, depth = 0, path = StringBuilder())
        root.recycle()
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: ArrayList<ViewNode>,
        rect: Rect,
        depth: Int,
        path: StringBuilder
    ) {
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val cls = node.className?.toString() ?: "View"
            val marker = path.length
            if (marker > 0) path.append(" > ")
            path.append(cls.substringAfterLast('.'))
            out.add(
                ViewNode(
                    id = out.size,
                    className = cls,
                    text = node.text?.toString() ?: "",
                    contentDesc = node.contentDescription?.toString() ?: "",
                    resourceId = node.viewIdResourceName,
                    bounds = Rect(rect),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    editable = node.isEditable,
                    focusable = node.isFocusable,
                    depth = depth,
                    path = path.toString()
                )
            )
            path.setLength(marker)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, rect, depth + 1, path)
            child.recycle()
        }
    }

    /** Click the element captured at [id]. Returns true if the action was sent. */
    fun clickNode(id: Int): Boolean {
        val node = nodeAt(id) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Replace the text of an editable element at [id]. */
    fun setText(id: Int, text: String): Boolean {
        val node = nodeAt(id) ?: return false
        if (!node.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun nodeAt(id: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val out = ArrayList<AccessibilityNodeInfo>()
        collect(root, out)
        return out.getOrNull(id)
    }

    private fun collect(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>) {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out)
        }
    }

    companion object {
        @Volatile private var instance: InspectorService? = null
        val isRunning: Boolean get() = instance != null

        private val _nodes = MutableStateFlow<List<ViewNode>>(emptyList())
        /** Latest captured tree, exposed for the overlay UI. */
        val nodes: StateFlow<List<ViewNode>> = _nodes

        /** Snapshot the foreground tree. Returns the new list. */
        fun capture(): List<ViewNode> {
            val list = instance?.captureNow() ?: emptyList()
            _nodes.value = list
            return list
        }

        fun click(id: Int): Boolean = instance?.clickNode(id) ?: false
        fun setText(id: Int, text: String): Boolean = instance?.setText(id, text) ?: false
    }
}
