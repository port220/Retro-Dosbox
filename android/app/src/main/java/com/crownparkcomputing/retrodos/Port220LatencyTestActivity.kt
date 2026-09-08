package com.crownparkcomputing.retrodos

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Port220 latency smoke test -- PROCEDURES.md, Test 1.
 *
 * Purpose: answer exactly one question before anything else is built on this
 * tablet: does Android's USB Host stack round-trip the FT232R fast enough for
 * EMSAN1 (must stay well under the ~150ms ceiling that already ruled out
 * wireless Service Module variants). This does not start DOSBox, the bridge
 * service, or anything else -- a failure here is unambiguous.
 *
 * How to read the result: this measures write-to-first-byte-back latency.
 *   - With a physical TX/RX loopback jumper on the Service Module: a pure
 *     USB-stack number, comparable to what Windows/Mac scheduling gives you.
 *   - With the real ECU connected: a real-world number, but includes however
 *     long the ECU itself takes to answer, so a high number here does not by
 *     itself indict Android -- cross-check against a known-good capture from
 *     the Mac/Windows build for the same query if this number looks bad.
 *
 * Tests both of Tom's confirmed-working baud rates (10400 established, 9600
 * N81 fallback) back to back, so we know immediately if one behaves better
 * than the other on Android's USB stack specifically.
 *
 * Results are logged to Logcat (tag Port220Latency) AND written to a CSV in
 * external files so they can be pulled with:
 *   adb pull /sdcard/Android/data/com.dosboxx.app/files/port220_latency.csv
 */
class Port220LatencyTestActivity : Activity() {

    companion object {
        private const val TAG = "Port220Latency"
        private const val VENDOR_ID = 0x0403
        private const val PRODUCT_ID = 0x6001
        private const val ACTION_USB_PERMISSION = "com.crownparkcomputing.retrodos.LATENCY_USB_PERMISSION"
        private const val ITERATIONS = 100
        private const val PER_TRY_TIMEOUT_MS = 500

        // Confirmed by Tom: 10400 is the established rate, 9600 N81 also
        // known to work. Testing both today costs nothing and tells us
        // whether Android's USB stack behaves differently at the two rates
        // -- useful to know before picking one for the real bridge.
        private val BAUD_RATES_TO_TEST = listOf(10400, 9600)
    }

    private lateinit var logView: TextView
    private var serialPort: UsbSerialPort? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                log("Permission granted. Opening port\u2026")
                openAndRun(device)
            } else {
                log("Permission DENIED. Cannot test.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(logView) })

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }

        log("Port220 latency test starting\u2026")
        findDevice()
    }

    private fun findDevice() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull {
            it.device.vendorId == VENDOR_ID && it.device.productId == PRODUCT_ID
        }
        if (driver == null) {
            log("FAIL: no FT232R found. Check the cable supports data, not just charging.")
            return
        }
        log("Found Service Module: ${driver.device.deviceName}")
        if (manager.hasPermission(driver.device)) {
            openAndRun(driver.device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        manager.requestPermission(driver.device, pi)
    }

    private fun openAndRun(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
        val connection = manager.openDevice(driver.device) ?: run {
            log("FAIL: openDevice() returned null. Try a different cable.")
            return
        }
        val port = driver.ports[0]
        try {
            port.open(connection)
        } catch (e: IOException) {
            log("FAIL: could not open port: ${e.message}")
            return
        }
        serialPort = port
        log("Port opened (unconfigured). Testing ${BAUD_RATES_TO_TEST.size} baud rate(s)\u2026")
        thread {
            val allResults = mutableMapOf<Int, List<Long>>()
            for (baud in BAUD_RATES_TO_TEST) {
                log("\n=== Testing $baud baud, 8N1 ===")
                try {
                    port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                } catch (e: IOException) {
                    log("FAIL: could not set $baud baud: ${e.message}")
                    continue
                }
                allResults[baud] = runLatencyLoop(port, baud)
            }
            writeCsv(allResults)

            log("\n=== SUMMARY ===")
            for ((baud, results) in allResults) {
                if (results.isEmpty()) {
                    log("$baud baud: no responses")
                } else {
                    val verdict = if (results.max() < 150) "PASS" else "MARGINAL/FAIL"
                    log("$baud baud: min=${results.min()}ms max=${results.max()}ms avg=${"%.1f".format(results.average())}ms  [$verdict]")
                }
            }
        }
    }

    private fun runLatencyLoop(port: UsbSerialPort, baud: Int): List<Long> {
        val results = mutableListOf<Long>()
        val probe = byteArrayOf(0x00)
        val readBuf = ByteArray(64)

        for (i in 1..ITERATIONS) {
            try {
                port.purgeHwBuffers(true, true)
                val start = System.nanoTime()
                port.write(probe, PER_TRY_TIMEOUT_MS)
                val n = port.read(readBuf, PER_TRY_TIMEOUT_MS)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (n > 0) {
                    results.add(elapsedMs)
                    if (i % 20 == 0) log("  [$baud baud $i/$ITERATIONS] ${elapsedMs}ms")
                } else if (i == 1) {
                    // Only log the first miss verbosely; if the probe byte
                    // gets no response at all this rate is likely a dead end.
                    log("  [$baud baud] no response within ${PER_TRY_TIMEOUT_MS}ms -- continuing to confirm")
                }
            } catch (e: IOException) {
                log("  [$baud baud $i/$ITERATIONS] IO error: ${e.message}")
            }
        }

        if (results.isEmpty()) {
            log("$baud baud: zero responses across $ITERATIONS tries.")
            log("If using the real ECU, it may need a different query byte than 0x00 --")
            log("a loopback jumper on TX/RX is the simplest way to test the USB stack alone.")
        }
        return results
    }

    private fun writeCsv(allResults: Map<Int, List<Long>>) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val file = File(dir, "port220_latency.csv")
            val lines = mutableListOf("baud,iteration,latency_ms")
            for ((baud, results) in allResults) {
                results.forEachIndexed { i, v -> lines.add("$baud,$i,$v") }
            }
            file.writeText(lines.joinToString("\n"))
            log("\nWritten to: ${file.absolutePath}")
        } catch (e: IOException) {
            log("Could not write CSV: ${e.message}")
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { logView.append("$msg\n") }
    }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        try { serialPort?.close() } catch (_: IOException) {}
        super.onDestroy()
    }
}
