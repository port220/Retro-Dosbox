package com.crownparkcomputing.retrodos

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small always-on-top status readout.
 *
 * Retro-DOS runs fullscreen, so during a session the bridge's counters are
 * invisible exactly when they matter most -- the moment EMSAN1 opens the port
 * is the moment you want to see whether anything crossed the wire. Switching
 * apps to look is both disruptive and a race: pausing the emulator changes
 * the thing being measured.
 *
 * Drawn through WindowManager as a TYPE_APPLICATION_OVERLAY, which floats
 * above other apps. Requires the "Display over other apps" permission, which
 * the user must grant in Settings -- Android does not allow it to be granted
 * from code.
 *
 * FLAG_NOT_TOUCHABLE matters: without it this view would swallow taps meant
 * for DOSBox, which on a touch-only device would make the emulator unusable.
 * The trade is that the overlay cannot be moved or dismissed by touch, so it
 * sits top-left where DOS text mode has margin, and is toggled from the
 * control activity instead.
 */
object Port220Overlay {

    private var view: TextView? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false

    /** Whether the user has granted the draw-over-other-apps permission. */
    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    val isShowing: Boolean get() = view != null

    fun show(ctx: Context) {
        if (view != null) return
        if (!hasPermission(ctx)) return

        val tv = TextView(ctx).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setTextColor(Color.argb(255, 120, 255, 120))
            textSize = 11f
            setPadding(16, 10, 16, 10)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 8
        lp.y = 8

        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        manager.addView(tv, lp)
        wm = manager
        view = tv

        ticking = true
        tick()
    }

    fun hide() {
        ticking = false
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: IllegalArgumentException) {}
        view = null
        wm = null
    }

    /**
     * Refreshed twice a second. Deliberately terse: this sits on top of a DOS
     * screen the user is trying to read, so it shows the four numbers that
     * distinguish the failure modes and nothing else.
     */
    private fun tick() {
        if (!ticking) return
        view?.text = buildString {
            append(if (Port220UsbBridgeService.isPortOpen) "USB:OPEN" else "USB:closed")
            append("  ")
            append(if (Port220UsbBridgeService.isBridgeReady) "DOS:CONN" else "DOS:--")
            append("\nout ")
            append(Port220UsbBridgeService.bytesTcpToUsb)
            append("  in ")
            append(Port220UsbBridgeService.bytesUsbToTcp)
            append("  noclient ")
            append(Port220UsbBridgeService.bytesUsbWithNoClient)
        }
        handler.postDelayed({ tick() }, 500)
    }
}
