package com.crownparkcomputing.retrodos

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/**
 * Port220 — the single app entry point.
 *
 * This is the only launcher icon. The emulator (MainActivity) is no longer a
 * separate app; it is started from here. The flow the product wants is:
 *
 *   plug in Service Module -> pick vehicle -> tap Launch -> diagnostics
 *
 * "Diagnostics" rather than "EMSAN1": that is the XJ220 program. The XJR-15
 * runs EMDAJ032 and the XJR-S runs DIAG3, so a program name on the button
 * would be wrong for two of the three vehicles.
 *
 * and the ordering constraint that shaped the whole architecture still holds:
 * the bridge's TCP socket must be listening before the emulator's serial
 * backend tries to connect. So "Launch" does not just start the emulator --
 * it starts the bridge, waits until the FT232R is actually open and the
 * socket is up, and only then launches the emulator. That removes the manual
 * two-step (and the start-order mistakes) entirely.
 */
class Port220ControlActivity : Activity() {

    companion object {
        /** Must match the filename placed in app/src/main/assets/. */
        private const val MANUAL_NAME = "XJ220_Service_Manual.pdf"
    }

    /**
     * Vehicles, matching the drop-down in the Mac and Windows builds and the
     * kPort220Vehicles table in the frontend. `key` is what gets written to
     * files/port220_vehicle; the C++ side reads it to pick the mount
     * directory and the program. Keep the two tables in step.
     */
    private data class Vehicle(val key: String, val label: String)

    private val vehicles = listOf(
        Vehicle("XJ220", "XJ220"),
        Vehicle("XJR15", "XJR-15"),
        Vehicle("XJR-S", "XJR-S")
    )
    private var selected = 0

    private lateinit var vehicleBtn: Button
    private lateinit var status: TextView
    private lateinit var launchBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var polling = true
    @Volatile private var launching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The badge carries the brand, so the wordmark below it only needs to
        // say what this app is. Repeating "Port220" under the logo would be
        // redundant.
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.port220_logo)
            adjustViewBounds = true
            // fitCenter so the badge scales to the width without distorting;
            // it is a long thin mark and any stretch is obvious.
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val title = TextView(this).apply {
            text = "Command Module"
            textSize = 20f
            setTextColor(Color.rgb(217, 193, 126))   // brand gold
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(180, 200, 180))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // Vehicle picker. A dialog rather than a Spinner: same one-tap-then-
        // choose interaction, far less adapter machinery, and it reads clearly
        // on a tablet at arm's length in a workshop.
        vehicleBtn = Button(this).apply {
            setOnClickListener { pickVehicle() }
        }

        // The one button that matters. Large, primary, does the whole flow.
        launchBtn = Button(this).apply {
            text = "Launch Diagnostics"
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
        val manualBtn = Button(this).apply {
            text = "Service manual"
            setOnClickListener { openManual() }
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
            // Black, matching the badge artwork and the launcher icon. The
            // logo PNG carries its own black field (it is cropped from a
            // photographed badge, not a cut-out), so anything other than
            // black leaves a visible rectangle behind it.
            setBackgroundColor(Color.BLACK)
            setPadding(64, 64, 64, 64)
            addView(logo, logoParams())
            addView(title)
            addView(status)
            addView(vehicleBtn, wide())
            addView(launchBtn, wide())
            addView(spacer(48))
            addView(manualBtn)
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

        loadSelection()
        startPolling()
    }

    /* ---- vehicle selection ---------------------------------------------- */

    private fun selectionFile() = File(filesDir, "port220_vehicle")

    private fun loadSelection() {
        val key = try {
            if (selectionFile().exists()) selectionFile().readText().trim() else ""
        } catch (e: Exception) { "" }
        val idx = vehicles.indexOfFirst { it.key == key }
        selected = if (idx >= 0) idx else 0
        // Written back even when defaulting, so the frontend and this screen
        // always agree on what will launch.
        saveSelection()
    }

    private fun saveSelection() {
        try {
            selectionFile().writeText(vehicles[selected].key)
        } catch (e: Exception) {
            android.util.Log.w("Port220", "Could not save vehicle selection", e)
        }
        vehicleBtn.text = "Vehicle: ${vehicles[selected].label}"
    }

    private fun pickVehicle() {
        AlertDialog.Builder(this)
            .setTitle("Select vehicle")
            .setItems(vehicles.map { it.label }.toTypedArray()) { _, which ->
                selected = which
                saveSelection()
                status.text = "${vehicles[selected].label} selected."
            }
            .show()
    }

    /* ---- service manual -------------------------------------------------- */

    /**
     * Opens the bundled PDF in whatever viewer the device has.
     *
     * The asset is copied to cache first: an APK asset has no file path a
     * separate app can open, and a content URI through FileProvider is the
     * only way to hand it over without the receiving app needing storage
     * permission. Copied once, then reused.
     */
    private fun openManual() {
        try {
            val out = File(cacheDir, MANUAL_NAME)
            if (!out.exists() || out.length() == 0L) {
                status.text = "Preparing manual\u2026"
                assets.open(MANUAL_NAME).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", out)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: ActivityNotFoundException) {
            status.text = "No PDF viewer installed on this tablet."
        } catch (e: Exception) {
            android.util.Log.e("Port220", "Could not open manual", e)
            status.text = "Could not open the manual: ${e.message}"
        }
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
                    status.text = "Service Module ready — starting ${vehicles[selected].label} diagnostics…"
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
        // Wrapped so a launch failure reports itself instead of taking the
        // whole app down. An uncaught exception here kills the process, drops
        // the user on the home screen, and leaves nothing on screen to read --
        // which is exactly what made the last two attempts guesswork.
        //
        // By class reference, not a name built from packageName: that returns
        // the applicationId (com.dosboxx.app) while the class lives in the
        // namespace (com.crownparkcomputing.retrodos). They differ here.
        try {
            startActivity(Intent(this, MainActivity::class.java))
        } catch (t: Throwable) {
            android.util.Log.e("Port220Launch", "Failed to start emulator", t)
            status.text = "Could not start diagnostics:\n${t.javaClass.simpleName}: ${t.message}"
        } finally {
            launching = false
            launchBtn.isEnabled = true
        }
    }

    private fun startBridge() {
        // startService, NOT startForegroundService.
        //
        // startForegroundService imposes a ~5 second deadline for the service
        // to call startForeground(), and the service now deliberately waits
        // past that -- it cannot legally claim the connectedDevice type until
        // the USB permission is granted, which involves a dialog the user may
        // take a while to answer. Missing that deadline is itself a crash.
        //
        // A plain startService is allowed here because this activity is
        // visible when the button is pressed, so it is not a background start.
        // The service promotes itself to foreground the moment the FT232R
        // opens, which is what keeps it alive once the emulator takes over
        // the screen.
        startService(Intent(this, Port220UsbBridgeService::class.java))
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
                    if (Port220UsbBridgeService.isBridgeReady) append("   ● Diagnostics linked")
                }
                runOnUiThread {
                    if (!launching) status.text = line
                }
                Thread.sleep(500)
            }
        }
    }

    /** Logo at 70% of screen width; height follows via adjustViewBounds. */
    private fun logoParams() = LinearLayout.LayoutParams(
        (resources.displayMetrics.widthPixels * 0.70).toInt(),
        LinearLayout.LayoutParams.WRAP_CONTENT)

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
