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
import android.os.Process
import android.util.Log
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Port220 USB<->TCP nullmodem bridge.
 *
 * Role: identical to the companion process on the Mac/Windows builds -- opens
 * the Service Module's FT232R and forwards bytes 1:1 between it and a local
 * TCP socket. DOSBox-X's [serial] nullmodem client connects to that socket;
 * this service never touches DOS or EMSAN1 directly.
 *
 * Baud: 10400 established, 9600 N81 a known-working fallback. Framing assumed
 * 8N1 (confirmed for 9600, assumed for 10400).
 *
 * Revision note -- the first version of this class could open the FT232R more
 * than once. onCreate() -> findAndRequestDevice() -> openDevice() runs when
 * permission is already held, and the permission receiver could call
 * openDevice() as well; repeated startForegroundService() added further paths.
 * Two SerialInputOutputManagers on one endpoint produce exactly the errors
 * seen in the field: "Connection closed" from one reader while the other holds
 * the transfer, then "USB get_status request failed" once the handle is stale.
 * Opening is now guarded and idempotent, and a read error tears the port down
 * rather than leaving a dead manager attached.
 */
class Port220UsbBridgeService : Service() {

    companion object {
        private const val TAG = "Port220Bridge"

        private const val VENDOR_ID = 0x0403
        private const val PRODUCT_ID = 0x6001

        /** Must match `port=` in the Port220 game's dosbox.conf. */
        const val NULLMODEM_PORT = 6403

        /* Switchable at runtime from the control activity rather than fixed.
         *
         * Which rate is correct is not settled: 10400 is the established
         * figure, but EMSAN1 asks DOSBox for 9600, and on the Windows build
         * (directserial) the guest's rate reaches the physical port directly
         * -- so the wire there is 9600. With nullmodem on Mac the guest's
         * rate is decoupled and the bridge sets the wire rate, so the two
         * platforms may genuinely differ. A toggle beats guessing, and
         * beats a rebuild per experiment. */
        val BAUD_OPTIONS = intArrayOf(10400, 9600)

        @Volatile
        var baudRate: Int = 10400
            private set

        /** Takes effect on the next open; the FT232R is configured at open time. */
        fun setBaudRate(b: Int) { baudRate = b }
        /**
         * FT232R latency timer, milliseconds.
         *
         * This is the single most consequential setting in this file. The
         * FT232R holds received bytes until either its buffer fills or this
         * timer expires, and only then sends a USB packet. Default is 16ms.
         * On a half-duplex K-line where EMSAN1 sends one byte and waits for
         * its echo before sending the next, every byte pays that timer -- the
         * ~14ms round trips measured in the loopback test are this timer,
         * not the USB stack.
         *
         * The Windows build tunes it via an FTDI registry key (which needed
         * UAC). The iPad DriverKit design uses 2ms. Left at 16ms here would
         * make Android roughly an order of magnitude slower per byte than
         * either working platform, for no reason. 1ms is the minimum the
         * chip accepts. */
        private const val FTDI_LATENCY_MS = 1

        private const val DATA_BITS = UsbSerialPort.DATABITS_8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        private const val ACTION_USB_PERMISSION = "com.crownparkcomputing.retrodos.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL_ID = "port220_bridge"
        private const val NOTIFICATION_ID = 1

        /**
         * Cap on the capture file. A stalled session can otherwise fill
         * storage; 4MB is far more than any EMSAN1 exchange produces.
         */
        private const val CAPTURE_LIMIT_BYTES = 4L * 1024 * 1024

        @Volatile
        var isBridgeReady: Boolean = false
            private set

        /**
         * Whether the FT232R is open. Distinct from isBridgeReady, which is
         * about DOSBox. Without this the control activity cannot tell a
         * successful open from a failed one -- both look like "waiting".
         */
        @Volatile
        var isPortOpen: Boolean = false
            private set

        /** Last thing that happened, in words, for display. */
        @Volatile
        var lastStatus: String = "Idle"
            private set

        @Volatile
        var bytesUsbToTcp: Long = 0
            private set

        @Volatile
        var bytesTcpToUsb: Long = 0
            private set

        /**
         * Inbound bytes seen while no DOSBox client is attached. Nonzero here
         * with nothing transmitted means the FT232R is producing bytes on its
         * own -- noise or framing garbage, not an echo -- which is worth
         * distinguishing from real traffic.
         */
        @Volatile
        var bytesUsbWithNoClient: Long = 0
            private set
    }

    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val executor = Executors.newCachedThreadPool()

    /** Guards against the multiple-open path that caused the endpoint fight. */
    @Volatile private var portOpen = false
    @Volatile private var serverStarted = false
    /** True once startForeground has succeeded; see promoteToForeground(). */
    @Volatile private var isForeground = false

    /** Bidirectional capture, for offline analysis of a failed session. */
    private var capture: java.io.BufferedWriter? = null
    private var captureBytes = 0L
    private var captureStartMs = 0L

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                Log.i(TAG, "USB permission granted")
                openDevice(device)
            } else {
                Log.e(TAG, "USB permission denied for Service Module")
                updateNotification("USB permission denied")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()

        // NOT startForeground() here.
        //
        // Android 15+ (targetSDK 35/36) validates a connectedDevice FGS
        // against two conditions: the app must hold
        // FOREGROUND_SERVICE_CONNECTED_DEVICE (we declare it), AND it must
        // actually have a connected device at that instant -- for USB, a
        // granted UsbDevice permission. In onCreate we have not requested
        // permission yet, so the second condition fails and the system throws
        // SecurityException, killing the process. That is the crash on
        // "Launch EMSAN1": it was never the emulator, it was this service
        // taking the app down as it started.
        //
        // So the foreground promotion is deferred until openDevice()
        // succeeds, by which point the USB permission is held and the
        // connectedDevice type is legitimate.
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    /**
     * Promote to foreground, now that the FT232R is open and the USB
     * permission grant makes the connectedDevice type valid.
     *
     * Guarded: calling startForeground twice is harmless but pointless, and
     * a failure here must not kill the app -- the bridge is still perfectly
     * usable as a started service while the activity is in front, which is
     * the normal case for this tool.
     */
    private fun promoteToForeground(text: String) {
        if (isForeground) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification(text))
            isForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground service: ${e.message}")
        }
    }

    /**
     * Repeated starts are expected -- the control activity's button, a USB
     * attach, and adb can all land here. Attempting to open an already-open
     * port is what broke this before, so each start is a no-op once open.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (portOpen) {
            Log.i(TAG, "Start ignored: port already open")
            lastStatus = "Already running"
        } else {
            findAndRequestDevice()
        }
        return START_NOT_STICKY
    }

    private fun findAndRequestDevice() {
        val manager = usbManager ?: return
        val drivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = drivers.firstOrNull {
            it.device.vendorId == VENDOR_ID && it.device.productId == PRODUCT_ID
        }
        if (driver == null) {
            Log.w(TAG, "Service Module not found among ${drivers.size} USB serial device(s)")
            lastStatus = "Service Module not found (${drivers.size} serial devices seen)"
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
        lastStatus = "Requesting USB permission\u2026"
        manager.requestPermission(device, permissionIntent)
    }

    @Synchronized
    private fun openDevice(device: UsbDevice) {
        if (portOpen) {
            Log.i(TAG, "openDevice ignored: already open")
            return
        }
        val manager = usbManager ?: return
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            Log.e(TAG, "No driver matched a granted device")
            return
        }
        val connection = manager.openDevice(driver.device) ?: run {
            Log.e(TAG, "openDevice() failed -- charge-only cable, or another app holds it")
            lastStatus = "USB open failed - check the cable"
            updateNotification("USB open failed \u2014 check cable")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(baudRate, DATA_BITS, STOP_BITS, PARITY)

            // Latency timer: see FTDI_LATENCY_MS. Read back to prove it took;
            // a silently ignored control transfer would leave the default and
            // there would be no other sign.
            if (port is FtdiSerialDriver.FtdiSerialPort) {
                port.setLatencyTimer(FTDI_LATENCY_MS)
                val actual = port.getLatencyTimer()
                Log.i(TAG, "FTDI latency timer: requested ${FTDI_LATENCY_MS}ms, device reports ${actual}ms")
                if (actual != FTDI_LATENCY_MS)
                    Log.w(TAG, "Latency timer did not take -- per-byte round trip will be ~${actual}ms")
            } else {
                Log.w(TAG, "Port is not an FTDI port; latency timer not set")
            }

            // Stale bytes from a previous session, or noise while the line
            // settled, must not be delivered as if the ECU had just sent them.
            port.purgeHwBuffers(true, true)

            // Assert the modem control lines.
            //
            // On the Windows build, directserial passes EMSAN1's DTR/RTS
            // straight to the physical port, and DOS programs routinely raise
            // both on open. Our DOSBox backend deliberately does not forward
            // those (there is no side channel for it, and forwarding as data
            // would corrupt the stream), so assert them here once. If the
            // Service Module's line driver or inversion logic gates on either
            // line, this is what makes it live.
            port.setDTR(true)
            port.setRTS(true)
            Log.i(TAG, "DTR and RTS asserted")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open/configure serial port", e)
            lastStatus = "Serial config failed: ${e.message}"
            updateNotification("Serial config failed: ${e.message}")
            try { port.close() } catch (_: IOException) {}
            return
        }
        serialPort = port
        portOpen = true
        isPortOpen = true
        // Safe to claim connectedDevice now: the USB permission is held.
        promoteToForeground("Service Module connected")
        lastStatus = "FT232R open at $baudRate baud"
        openCapture()

        // Bytes go straight out to the TCP socket as they arrive. The latency
        // budget is the point of this class: no queue, no batching, no delay.
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                bytesUsbToTcp += data.size
                record("ECU->EMSAN1", data, data.size)
                val sock = clientSocket
                if (sock == null) {
                    // Inbound bytes with nobody listening. Counted separately
                    // because with a loopback jumper and nothing transmitted,
                    // this means the line is producing bytes by itself.
                    bytesUsbWithNoClient += data.size
                    return
                }
                try {
                    sock.getOutputStream().write(data)
                } catch (e: IOException) {
                    Log.w(TAG, "TCP write failed: ${e.message}")
                }
            }

            override fun onRunError(e: Exception) {
                // Previously this only logged, leaving a dead manager attached
                // and the port half-usable. Tear down so a later start can
                // cleanly reopen.
                Log.e(TAG, "USB read error, closing port", e)
                lastStatus = "USB read error: " + e.message
                updateNotification("USB error: ${e.message}")
                closePort()
            }
        }).also {
            // The read thread is the one thing between an incoming byte and
            // the TCP write. Audio priority keeps it from being descheduled
            // behind UI work; on a latency budget that is worth having.
            it.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            it.start()
        }

        Log.i(TAG, "Service Module open at $baudRate baud")
        startNullmodemServer()
    }

    /**
     * Opens the capture file. Truncated on each open so a session's capture
     * is that session's, not an accumulation across attempts.
     *
     * Format is one line per chunk: milliseconds since capture start, the
     * direction, the byte count, then hex. Deliberately plain text rather
     * than binary so it can be read without tooling and pasted into a
     * conversation, and so the inter-chunk timing is visible -- with a
     * protocol this timing-sensitive, when bytes arrive matters as much as
     * which bytes arrive.
     */
    private fun openCapture() {
        try {
            val f = java.io.File(filesDir, "port220_capture.log")
            capture = java.io.BufferedWriter(java.io.FileWriter(f, false))
            captureBytes = 0
            captureStartMs = System.currentTimeMillis()
            capture?.write("# Port220 capture, baud $baudRate 8N1\n")
            capture?.write("# ms_since_start direction count hex\n")
            capture?.flush()
            Log.i(TAG, "Capturing to ${f.absolutePath}")
        } catch (e: IOException) {
            Log.w(TAG, "Could not open capture file: ${e.message}")
            capture = null
        }
    }

    @Synchronized
    private fun record(direction: String, data: ByteArray, len: Int) {
        val w = capture ?: return
        if (captureBytes >= CAPTURE_LIMIT_BYTES) return
        try {
            val ms = System.currentTimeMillis() - captureStartMs
            val hex = StringBuilder(len * 3)
            for (i in 0 until len) hex.append("%02X ".format(data[i]))
            val line = "$ms $direction $len ${hex.toString().trim()}\n"
            w.write(line)
            // Flushed per chunk: a session that ends in a crash or an unplug
            // still leaves a usable capture, which is exactly the case we
            // most need it for.
            w.flush()
            captureBytes += line.length
        } catch (e: IOException) {
            Log.w(TAG, "Capture write failed: ${e.message}")
        }
    }

    @Synchronized
    private fun closePort() {
        portOpen = false
        isPortOpen = false
        isBridgeReady = false
        try { capture?.flush(); capture?.close() } catch (_: IOException) {}
        capture = null
        ioManager?.stop()
        ioManager = null
        try { serialPort?.close() } catch (_: IOException) {}
        serialPort = null
    }

    private fun startNullmodemServer() {
        if (serverStarted) {
            Log.i(TAG, "Nullmodem server already listening on $NULLMODEM_PORT")
            return
        }
        serverStarted = true
        executor.execute {
            try {
                // Unbound first so SO_REUSEADDR can be set before bind --
                // otherwise a restart can hit TIME_WAIT and fail to listen.
                val socket = ServerSocket()
                socket.reuseAddress = true
                // Pin to IPv4 127.0.0.1 explicitly. getLoopbackAddress() can
                // return the IPv6 loopback ::1 on a dual-stack device, which
                // binds an IPv6-only socket -- and the C++ backend connects
                // with inet_pton(AF_INET) to 127.0.0.1, i.e. IPv4. An IPv4
                // client cannot reach an IPv6 listener, so every connect is
                // refused forever even though both sides are "correct". This
                // is the same v4/v6 split that broke apt in WSL earlier.
                val loopbackV4 = InetAddress.getByName("127.0.0.1")
                socket.bind(InetSocketAddress(loopbackV4, NULLMODEM_PORT), 1)
                serverSocket = socket
                updateNotification("Waiting for DOSBox on port $NULLMODEM_PORT\u2026")
                Log.i(TAG, "Listening on 127.0.0.1:$NULLMODEM_PORT")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.tcpNoDelay = true   // Nagle would add latency
                    clientSocket = client
                    isBridgeReady = true
                    Log.i(TAG, "DOSBox connected")
                    lastStatus = "DOSBox connected"
                    updateNotification("Bridged: Service Module \u2194 DOSBox")
                    pumpTcpToUsb(client)
                }
            } catch (e: IOException) {
                serverStarted = false
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
                record("EMSAN1->ECU", buf, n)
                serialPort?.write(buf.copyOf(n), 1000)
            }
        } catch (e: IOException) {
            Log.i(TAG, "DOSBox disconnected: ${e.message}")
        } finally {
            isBridgeReady = false
            try { client.close() } catch (_: IOException) {}
            clientSocket = null
            updateNotification("DOSBox disconnected \u2014 waiting again\u2026")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: IllegalArgumentException) {}
        closePort()
        try { serverSocket?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        serverStarted = false
        isForeground = false
        executor.shutdownNow()
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
        // Only meaningful once we are a foreground service with a live
        // notification; before that there is nothing on screen to update.
        if (!isForeground) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
