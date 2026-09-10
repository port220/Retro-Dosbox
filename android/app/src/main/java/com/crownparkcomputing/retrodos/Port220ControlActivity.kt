package com.crownparkcomputing.retrodos

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Port220 — the single app entry point.
 *
 * This is the only launcher icon. The emulator (MainActivity) is no longer a
 * separate app; it is started from here. The flow the product wants is:
 *
 *   plug in Service Module -> open Port220 -> tap Launch -> EMSAN1
 *
 * and the ordering constraint that shaped the whole architecture still holds:
 * the bridge's TCP socket must be listening before the emulator's serial
 * backend tries to connect. So "Launch" does not just start the emulator --
 * it starts the bridge, waits until the FT232R is actually open and the
 * socket is up, and only then launches the emulator. That removes the manual
 * two-step (and the start-order mistakes) entirely.
 */
class Port220ControlActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var launchBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var polling = true
    @Volatile private var launching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Port220 Command Module"
            textSize = 22f
            setTextColor(Color.rgb(217, 193, 126))   // brand gold
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(180, 200, 180))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // The one button that matters. Large, primary, does the whole flow.
        launchBtn = Button(this).apply {
            text = "Launch EMSAN1"
            textSize = 18f
            setOnClickListener { beginLaunch() }
        }

        // Secondary controls, deliberately smaller and below.
        val overlayBtn = Button(this).apply {
            text = "Status overlay"
            setOnClickListener { toggleOverlay() }
        }
        val baudBtn = Button(this).apply {
            text = "Baud: ${Port220UsbBridgeService.baudRate}"
            setOnClickListener {
                val opts = Port220UsbBridgeService.BAUD_OPTIONS
                val next = opts[(opts.indexOf(Port220UsbBridgeService.baudRate) + 1) % opts.size]
                Port220UsbBridgeService.setBaudRate(next)
                text = "Baud: $next"
                if (Port220UsbBridgeService.isPortOpen)
                    status.text = "Baud set to $next — reconnect the module to apply."
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop service"
            setOnClickListener {
                stopService(Intent(this@Port220ControlActivity, Port220UsbBridgeService::class.java))
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(11, 17, 25))   // brand dark
            setPadding(64, 64, 64, 64)
            addView(title)
            addView(status)
            addView(launchBtn, wide())
            addView(spacer(48))
            addView(overlayBtn)
            addView(baudBtn)
            addView(stopBtn)
        })

        // Auto-start the bridge when opened because the module was plugged in,
        // so by the time the user reaches for Launch the port is already up.
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED ||
            intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startBridge()
        }

        startPolling()
    }

    /** Start bridge, wait for the socket, then launch the emulator. */
    private fun beginLaunch() {
        if (launching) return
        launching = true
        launchBtn.isEnabled = false
        startBridge()

        // Poll for readiness rather than assuming a fixed delay: the USB
        // permission dialog may sit in front of the user for a few seconds.
        val start = System.currentTimeMillis()
        val timeoutMs = 20_000L
        fun waitForBridge() {
            when {
                Port220UsbBridgeService.isPortOpen -> {
                    status.text = "Service Module ready — starting EMSAN1…"
                    handler.postDelayed({ launchEmulator() }, 300)
                }
                System.currentTimeMillis() - start > timeoutMs -> {
                    status.text = "Service Module not detected.\nCheck the cable and USB permission, then try again."
                    launching = false
                    launchBtn.isEnabled = true
                }
                else -> {
                    status.text = "Connecting to Service Module…"
                    handler.postDelayed({ waitForBridge() }, 250)
                }
            }
        }
        waitForBridge()
    }

    private fun launchEmulator() {
        startActivity(Intent().apply {
            component = ComponentName(packageName, "$packageName.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        // Re-enable for next time; MainActivity comes to the foreground over us.
        launching = false
        launchBtn.isEnabled = true
    }

    private fun startBridge() {
        startForegroundService(Intent(this, Port220UsbBridgeService::class.java))
    }

    private fun toggleOverlay() {
        if (!Port220Overlay.hasPermission(this)) {
            status.text = "Grant \"Display over other apps\", then tap again."
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
        } else if (Port220Overlay.isShowing) {
            Port220Overlay.hide()
        } else {
            Port220Overlay.show(this)
        }
    }

    private fun startPolling() {
        thread {
            while (polling) {
                val line = buildString {
                    append(if (Port220UsbBridgeService.isPortOpen) "● Service Module connected"
                           else "○ Service Module not connected")
                    if (Port220UsbBridgeService.isBridgeReady) append("   ● EMSAN1 linked")
                }
                runOnUiThread {
                    if (!launching) status.text = line
                }
                Thread.sleep(500)
            }
        }
    }

    private fun wide() = LinearLayout.LayoutParams(
        (resources.displayMetrics.widthPixels * 0.6).toInt(),
        LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun spacer(px: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
