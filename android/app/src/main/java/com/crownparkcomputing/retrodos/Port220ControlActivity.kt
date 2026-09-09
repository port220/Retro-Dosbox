package com.crownparkcomputing.retrodos

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Port220 bridge control.
 *
 * Exists because of two Android constraints that together rule out starting
 * the bridge service directly from adb:
 *
 *   1. The service is not exported (and should not be -- exporting it would
 *      let any installed app drive the Service Module). adb runs as the shell
 *      uid, so it cannot start a non-exported component.
 *   2. Since Android 12, a foreground service cannot be started from the
 *      background. An adb-initiated start with the app not in the foreground
 *      counts as background and throws ForegroundServiceStartNotAllowedException.
 *
 * An activity solves both: adb may launch an exported activity, and a service
 * started from a visible activity is a foreground start, which is permitted.
 *
 * Launch from adb:
 *   adb shell am start -n com.dosboxx.app/com.crownparkcomputing.retrodos.Port220ControlActivity
 *
 * Or tap "Port220 Bridge" in the app drawer.
 */
class Port220ControlActivity : Activity() {

    private lateinit var status: TextView
    @Volatile private var polling = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }

        val startBtn = Button(this).apply {
            text = "Start bridge"
            setOnClickListener { startBridge() }
        }
        val stopBtn = Button(this).apply {
            text = "Stop bridge"
            setOnClickListener { stopBridge() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(startBtn)
            addView(stopBtn)
        })

        // Launched by the USB_DEVICE_ATTACHED filter means the Service Module
        // was just plugged in, so starting immediately is what the user wants.
        // Launched from the drawer or adb, wait for an explicit tap -- during
        // bring-up an implicit start is harder to reason about than a manual one.
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            startBridge()
        }

        startPolling()
    }

    private fun startBridge() {
        startForegroundService(Intent(this, Port220UsbBridgeService::class.java))
    }

    private fun stopBridge() {
        stopService(Intent(this, Port220UsbBridgeService::class.java))
    }

    private fun startPolling() {
        thread {
            while (polling) {
                val text = buildString {
                    append("USB port: ")
                    append(if (Port220UsbBridgeService.isPortOpen) "OPEN" else "closed")
                    append("\nDOSBox: ")
                    append(if (Port220UsbBridgeService.isBridgeReady) "CONNECTED" else "not connected")
                    append("\n\n")
                    append(Port220UsbBridgeService.lastStatus)
                    append("\n\nTCP port: ")
                    append(Port220UsbBridgeService.NULLMODEM_PORT)
                    append("\nUSB -> TCP: ")
                    append(Port220UsbBridgeService.bytesUsbToTcp)
                    append(" bytes\nTCP -> USB: ")
                    append(Port220UsbBridgeService.bytesTcpToUsb)
                    append(" bytes\n\nUSB in, no client: ")
                    append(Port220UsbBridgeService.bytesUsbWithNoClient)
                    append(" bytes")
                }
                runOnUiThread { status.text = text }
                Thread.sleep(500)
            }
        }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
