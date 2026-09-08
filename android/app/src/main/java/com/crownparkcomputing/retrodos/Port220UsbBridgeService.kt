package com.crownparkcomputing.retrodos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Port220 USB<->TCP nullmodem bridge.
 *
 * Role: identical to the companion process on the Mac/Windows builds -- opens
 * the Service Module's FT232R, and forwards bytes 1:1 between it and a local
 * TCP socket. DOSBox-X's own [serial] nullmodem client connects to that
 * socket; this service never touches DOS or EMSAN1 directly.
 *
 * Kept as a standalone service (not started by DOSBox, not owned by it) so it
 * can be brought up before the emulator, and so Port220LatencyTestActivity can
 * exercise the same USB path without any DOSBox involvement at all.
 *
 * Baud confirmed by Tom: 10400 (established), 9600 N81 also known to work as
 * a fallback. Data bits/parity/stop bits assumed 8N1 to match the 9600
 * fallback description -- if 10400 uses different framing, update DATA_BITS/
 * STOP_BITS/PARITY below accordingly.
 */
class Port220UsbBridgeService : Service() {

    companion object {
        private const val TAG = "Port220Bridge"

        // FT232R identity (FTDI).
        private const val VENDOR_ID = 0x0403
        private const val PRODUCT_ID = 0x6001

        // Loopback port DOSBox-X's [serial] nullmodem section connects to.
        // Arbitrary but fixed -- must match `port=` in the Port220 game's
        // dosbox.conf (see port220_dosbox.conf.template).
        const val NULLMODEM_PORT = 6403

        // Confirmed by Tom: 10400 baud is the established rate for this ECU
        // link (consistent with the ISO 9141-2/KWP2000 diagnostic standard
        // rate). 9600 N81 has also worked as a fallback -- if 10400 gives
        // trouble specifically on Android's USB stack, try switching this
        // constant to 9600 before assuming the platform itself is at fault.
        private const val BAUD_RATE = 10400
        private const val BAUD_RATE_FALLBACK = 9600
        private const val DATA_BITS = UsbSerialPort.DATABITS_8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        private const val ACTION_USB_PERMISSION = "com.crownparkcomputing.retrodos.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL_ID = "port220_bridge"
        private const val NOTIFICATION_ID = 1

        /** True once a client (DOSBox, or the latency test) can talk to the bridge. */
        @Volatile
        var isBridgeReady: Boolean = false
            private set

        /** Bytes moved in each direction, for the latency test and for on-screen diagnostics. */
        @Volatile
        var bytesUsbToTcp: Long = 0
            private set

        @Volatile
        var bytesTcpToUsb: Long = 0
            private set
    }

    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val executor = Executors.newCachedThreadPool()

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            synchronized(this) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for ${device.deviceName}")
                    openDevice(device)
                } else {
                    Log.e(TAG, "USB permission denied for Service Module")
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for Service Module\u2026"))

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        findAndRequestDevice()
    }

    private fun findAndRequestDevice() {
        val manager = usbManager ?: return
        val drivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = drivers.firstOrNull {
            it.device.vendorId == VENDOR_ID && it.device.productId == PRODUCT_ID
        }
        if (driver == null) {
            Log.w(TAG, "Service Module not found among ${drivers.size} USB serial device(s)")
            updateNotification("Service Module not found")
            return
        }

        val device = driver.device
        if (manager.hasPermission(device)) {
            openDevice(device)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        manager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            Log.e(TAG, "No driver matched a granted device -- should not happen")
            return
        }
        val connection = manager.openDevice(driver.device) ?: run {
            Log.e(TAG, "openDevice() failed -- cable may be charge-only, or another app holds it")
            updateNotification("USB open failed -- check cable")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open/configure serial port", e)
            updateNotification("Serial config failed: ${e.message}")
            return
        }
        serialPort = port

        // Every byte the ECU sends goes straight to the TCP socket, the
        // instant it arrives -- no buffering delay added here. Latency
        // budget is the whole point of this class; do not add a queue,
        // a delay, or a batch size "for efficiency" without re-measuring.
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                bytesUsbToTcp += data.size
                try {
                    clientSocket?.getOutputStream()?.write(data)
                } catch (e: IOException) {
                    Log.w(TAG, "TCP write failed (no client yet?): ${e.message}")
                }
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "USB IO manager error", e)
                updateNotification("USB error: ${e.message}")
            }
        }).also { it.start() }

        Log.i(TAG, "Service Module open at $BAUD_RATE baud; starting nullmodem socket")
        startNullmodemServer()
    }

    private fun startNullmodemServer() {
        executor.execute {
            try {
                val socket = ServerSocket(NULLMODEM_PORT, 1, InetAddress.getLoopbackAddress())
                serverSocket = socket
                updateNotification("Waiting for DOSBox on port $NULLMODEM_PORT\u2026")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.tcpNoDelay = true // do not let Nagle's algorithm add latency
                    clientSocket = client
                    isBridgeReady = true
                    updateNotification("Bridged: Service Module \u2194 DOSBox")
                    pumpTcpToUsb(client)
                }
            } catch (e: IOException) {
                if (!(serverSocket?.isClosed ?: true)) {
                    Log.e(TAG, "Nullmodem server socket failed", e)
                }
            }
        }
    }

    private fun pumpTcpToUsb(client: Socket) {
        try {
            val input = client.getInputStream()
            val buf = ByteArray(256)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bytesTcpToUsb += n
                serialPort?.write(buf.copyOf(n), 1000)
            }
        } catch (e: IOException) {
            Log.i(TAG, "DOSBox nullmodem client disconnected: ${e.message}")
        } finally {
            isBridgeReady = false
            updateNotification("DOSBox disconnected \u2014 waiting again\u2026")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: IllegalArgumentException) {}
        ioManager?.stop()
        try { serialPort?.close() } catch (_: IOException) {}
        try { serverSocket?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
        isBridgeReady = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Port220 Service Module",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Port220")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
