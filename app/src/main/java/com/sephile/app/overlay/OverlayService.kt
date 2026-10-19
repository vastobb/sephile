package com.sephile.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.sephile.app.R
import com.sephile.app.ui.MainActivity
import kotlin.math.abs

/**
 * Floating overlay: a draggable bubble that expands into a control panel.
 *
 * Over the target app it can draw **boundary rectangles** on every element the
 * accessibility service exposes, and enter **pick mode** where the next tap
 * selects an element and shows its info + supported actions (click / set text).
 *
 * Requires two grants the user must give once:
 *   1. "Display over other apps"  (Settings ▸ Apps ▸ Sephile ▸ Display over other apps)
 *   2. The accessibility service  (Settings ▸ Accessibility ▸ Sepphile Layout)
 *
 * The honest boundary is documented on [InspectorService]: this reads the
 * Android view tree, so it works on normal apps but NOT inside OpenGL games
 * (Minecraft etc.) that render their own UI with no view objects.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager

    // Bubble (draggable icon)
    private var bubbleView: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    // Panel (controls + element detail)
    private var panelView: View? = null
    private var panelLp: WindowManager.LayoutParams? = null

    // Full-screen boundary overlay
    private var boundsView: View? = null
    private var boundsLp: WindowManager.LayoutParams? = null

    private var boundsOn = false
    private val captured = ArrayList<ViewNode>()

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant \"Display over other apps\" first", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        addBubble()
        addBoundsOverlay()
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    // ── Foreground notification (Android 14 needs a declared type) ──────────
    private fun startInForeground() {
        val channelId = "sephile_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Sephile overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sephile overlay active")
            .setContentText("Floating bubble is showing over other apps")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires an explicit type at startForeground().
            startForeground(42, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(42, notif)
        }
    }

    // ── Bubble ──────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun addBubble() {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var startX = 0; var startY = 0; var startTouchX = 0f; var startTouchY = 0f
        var moved = false
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    startTouchX = ev.rawX; startTouchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startTouchX).toInt()
                    val dy = (ev.rawY - startTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    wm.updateViewLayout(view, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        wm.addView(v, lp)
        bubbleView = v
        bubbleLp = lp
    }

    // ── Boundary overlay (draws rects, does not consume touches) ────────────
    private fun addBoundsOverlay() {
        val v = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                if (!boundsOn) return
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = Color.parseColor("#3DDC84")
                }
                val fill = Paint().apply {
                    style = Paint.Style.FILL
                    color = 0x223DDC84
                }
                val label = Paint().apply {
                    color = Color.WHITE
                    textSize = 28f
                    setShadowLayer(3f, 0f, 0f, Color.BLACK)
                }
                for (n in captured) {
                    if (n.isZeroSize) continue
                    canvas.drawRect(n.bounds, fill)
                    canvas.drawRect(n.bounds, p)
                    val t = n.label
                    if (t.isNotEmpty()) {
                        canvas.drawText(t, n.bounds.left + 4f, n.bounds.top + 30f, label)
                    }
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        v.visibility = View.GONE
        wm.addView(v, lp)
        boundsView = v
        boundsLp = lp
    }

    // ── Panel ───────────────────────────────────────────────────────────────
    @SuppressLint("InflateParams")
    private fun showPanel() {
        if (panelView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val lp = WindowManager.LayoutParams(
            (620 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = 220
        }

        v.findViewById<Button>(R.id.btn_bounds).setOnClickListener {
            boundsOn = !boundsOn
            if (boundsOn) refreshCapture()
            boundsView?.visibility = if (boundsOn) View.VISIBLE else View.GONE
            boundsView?.invalidate()
            it as Button
            it.text = if (boundsOn) "Boundaries: ON" else "Boundaries: OFF"
        }
        v.findViewById<Button>(R.id.btn_pick).setOnClickListener {
            enterPickMode()
        }
        v.findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            refreshCapture()
            Toast.makeText(this, "Captured ${captured.size} elements", Toast.LENGTH_SHORT).show()
        }
        v.findViewById<Button>(R.id.btn_close).setOnClickListener { stopSelf() }

        wm.addView(v, lp)
        panelView = v
        panelLp = lp
        renderDetail(null)
    }

    private fun togglePanel() {
        val v = panelView ?: return
        v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun refreshCapture() {
        captured.clear()
        captured.addAll(InspectorService.capture())
        boundsView?.invalidate()
        if (panelView?.visibility == View.VISIBLE) {
            val tv = panelView?.findViewById<TextView>(R.id.detail) ?: return
            if (captured.isEmpty()) tv.text = "No elements captured.\n\nEnable the accessibility service:\nSettings ▸ Accessibility ▸ Sephile Layout"
            else tv.text = "Captured ${captured.size} elements. Tap Pick, then tap an element on screen."
        }
    }

    // ── Pick mode: intercept the next tap, hit-test, show detail ────────────
    private fun enterPickMode() {
        Toast.makeText(this, "Tap an element on screen", Toast.LENGTH_SHORT).show()
        panelView?.visibility = View.GONE
        boundsOn = true
        boundsView?.visibility = View.VISIBLE
        boundsView?.invalidate()

        val pick = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        pick.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val hit = hitTest(ev.rawX.toInt(), ev.rawY.toInt())
                wm.removeView(pick)
                panelView?.visibility = View.VISIBLE
                renderDetail(hit)
                if (hit == null) {
                    Toast.makeText(this, "Nothing hit there", Toast.LENGTH_SHORT).show()
                }
            }
            true // consume the tap so it doesn't reach the app
        }
        wm.addView(pick, lp)
    }

    private fun hitTest(x: Int, y: Int): ViewNode? {
        // Smallest element containing the point wins (most specific).
        var best: ViewNode? = null
        var bestArea = Int.MAX_VALUE
        for (n in captured) {
            val r: Rect = n.bounds
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                val area = r.width() * r.height()
                if (area < bestArea) { bestArea = area; best = n }
            }
        }
        return best
    }

    private fun renderDetail(node: ViewNode?) {
        val v = panelView ?: return
        val tv = v.findViewById<TextView>(R.id.detail) ?: return
        val actions = v.findViewById<LinearLayout>(R.id.actions) ?: return
        actions.removeAllViews()

        if (node == null) {
            tv.text = if (!InspectorService.isRunning)
                "Enable accessibility service to inspect layouts.\n\nSettings ▸ Accessibility ▸ Sephile Layout"
            else
                "Captured ${captured.size} elements.\n\n• Boundaries — draw boxes on every element\n• Pick — tap an element to inspect it"
            return
        }

        val sb = StringBuilder()
        sb.append("Element\n")
        sb.append("  ${node.className}\n\n")
        sb.append("Text\n  ${node.text.ifEmpty { "—" }}\n\n")
        sb.append("Content-desc\n  ${node.contentDesc.ifEmpty { "—" }}\n\n")
        sb.append("Resource id\n  ${node.resourceId ?: "—"}\n\n")
        sb.append("Bounds (screen px)\n")
        sb.append("  L ${node.bounds.left}  T ${node.bounds.top}\n")
        sb.append("  R ${node.bounds.right}  B ${node.bounds.bottom}\n")
        sb.append("  ${node.bounds.width()}×${node.bounds.height()}\n\n")
        sb.append("Flags\n")
        sb.append("  clickable=${node.clickable} enabled=${node.enabled}\n")
        sb.append("  editable=${node.editable} focusable=${node.focusable}\n\n")
        sb.append("Path\n  ${node.path}")
        tv.text = sb.toString()

        // Supported accessibility actions only (honest limit — see InspectorService).
        if (node.clickable) {
            actions.addView(actionButton("Click") {
                val ok = InspectorService.click(node.id)
                Toast.makeText(this, if (ok) "Clicked" else "Click failed", Toast.LENGTH_SHORT).show()
            })
        }
        if (node.editable) {
            actions.addView(actionButton("Set text…") {
                promptSetText(node)
            })
        }
        if (!node.clickable && !node.editable) {
            val note = TextView(this).apply {
                text = "No writable action for this type.\nAccessibility can read any view and click/edit where the app allows, but cannot rename or resize arbitrary views of another app."
                setPadding(0, 16, 0, 0)
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 12f
            }
            actions.addView(note)
        }
    }

    private fun promptSetText(node: ViewNode) {
        val input = android.widget.EditText(this).apply {
            setText(node.text)
            hint = "New text"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Set text")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val ok = InspectorService.setText(node.id, input.text.toString())
                Toast.makeText(this, if (ok) "Applied" else "Failed", Toast.LENGTH_SHORT).show()
                refreshCapture()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 }
        b.layoutParams = lp
        return b
    }

    override fun onDestroy() {
        listOfNotNull(bubbleView, panelView, boundsView).forEach {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        bubbleView = null; panelView = null; boundsView = null
        super.onDestroy()
    }
}
