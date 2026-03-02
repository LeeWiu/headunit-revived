package com.andrerinas.headunitrevived.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.connection.NetworkDiscovery
import android.support.v4.media.session.MediaSessionCompat
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.NightModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import android.app.NotificationManager
import java.net.ServerSocket

/**
 * Top-level foreground service that manages the Android Auto connection lifecycle.
 *
 * Responsibilities:
 * - Manages the [CommManager] connection state machine (USB and WiFi)
 * - Drives [AapProjectionActivity] via intents and connection state flow
 * - Runs a [WirelessServer] for the "server" WiFi mode and coordinates [NetworkDiscovery] scans
 * - Keeps a foreground notification updated to reflect the current connection state
 * - Manages car mode, night mode, media session, and GPS location service
 *
 * Connection types:
 * - **USB**: [UsbReceiver] detects attach → [checkAlreadyConnectedUsb] → [connectUsbWithRetry]
 * - **WiFi (client)**: [NetworkDiscovery] finds a Headunit Server → [CommManager.connect]
 * - **WiFi (server)**: [WirelessServer] accepts incoming sockets from AA Wireless / Self Mode
 * - **Self Mode**: starts [WirelessServer] and launches the AA Wireless Setup Activity on-device
 */
class AapService : Service(), UsbReceiver.Listener {

    // SupervisorJob prevents a child coroutine failure from cancelling the whole scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var uiModeManager: UiModeManager
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var wirelessServer: WirelessServer? = null
    private var networkDiscovery: NetworkDiscovery? = null
    private var mediaSession: MediaSessionCompat? = null

    /**
     * Set to `true` before calling [stopSelf] or entering [onDestroy] to suppress any
     * flow observers that would otherwise update the already-dismissed notification.
     */
    private var isDestroying = false

    private val commManager get() = App.provide(this).commManager

    // Receives ACTION_REQUEST_NIGHT_MODE_UPDATE broadcasts sent by the key-binding handler
    // when the user presses the night-mode toggle key.
    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")

        startForeground(1, createNotification())
        setupCarMode()
        setupNightMode()
        observeConnectedState()
        observeDisconnectedState()
        observeTransportStartedState()
        registerReceivers()

        startService(GpsLocationService.intent(this))
        initWifiMode()
        checkAlreadyConnectedUsb()
    }

    /** Enables Android Automotive UI mode so the system uses car-optimised layouts. */
    private fun setupCarMode() {
        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiModeManager.enableCarMode(0)
    }

    /** Initialises [NightModeManager] and forwards night-mode changes to Android Auto via AAP. */
    private fun setupNightMode() {
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            commManager.send(NightModeEvent(isNight))
        }
        nightModeManager?.start()
    }

    /**
     * Observes [CommManager.ConnectionState.Connected] and calls onConnected
     */
    private fun observeConnectedState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.Connected>()
                .collect { onConnected() }
        }
    }

    /**
     * Observes [CommManager.ConnectionState.TransportStarted] and syncs night mode.
     * Night mode must be sent after the transport is ready, not at connection time.
     */
    private fun observeTransportStartedState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.TransportStarted>()
                .collect {
                    sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
                        setPackage(packageName)
                    })
                }
        }
    }

    /**
     * Observes [CommManager.ConnectionState.Disconnected] and calls onDisconnect
     * `drop(1)` skips the initial `Disconnected` value that `StateFlow` replays on subscribe.
     */
    private fun observeDisconnectedState() {
        serviceScope.launch {
            commManager.connectionState
                .filterIsInstance<CommManager.ConnectionState.Disconnected>()
                .drop(1) // skip the StateFlow's initial Disconnected emission on startup
                .collect { state -> onDisconnected(state) }
        }
    }

    /**
     * Called by [CommManager.ConnectionState.Connected] observer:
     * 1. Refreshing the foreground notification
     * 2. Activating a [MediaSessionCompat] so media keys are routed to Android Auto
     * 3. Syncing the current night-mode state to the newly connected phone
     * 4. Launching [AapProjectionActivity] to display the AA video surface
     */
    private fun onConnected() {
        updateNotification()
        mediaSession = MediaSessionCompat(this, "HeadunitRevived").apply { isActive = true }
        // Start the AAP handshake immediately, before the projection activity even opens.
        // This hides handshake latency (especially the multi-second USB SSL negotiation) behind
        // the activity startup time rather than adding it on top.
        serviceScope.launch { commManager.startTransport() }
        startActivity(AapProjectionActivity.intent(this).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    /**
     * Called by [CommManager.ConnectionState.Disconnected] observer:
     * 1. Refreshing the notification (unless we are already tearing down)
     * 2. Releasing the [MediaSessionCompat]
     * 3. Stopping audio/video decoders on the IO thread
     * 4. Scheduling a reconnect attempt if applicable (see [scheduleReconnectIfNeeded])
     */
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        if (!isDestroying) updateNotification()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDisconnect")
        }
        scheduleReconnectIfNeeded(state)
    }

    /**
     * Schedules a reconnect attempt 2 seconds after an unexpected disconnect:
     * - **Server mode** ([wirelessServer] != null): always restarts the discovery loop.
     * - **Auto WiFi mode** (mode == 1): triggers a one-shot scan on unclean disconnect only.
     *
     * [CommManager.ConnectionState.Disconnected.isClean] is `true` only when the phone
     * explicitly sends a `ByeByeRequest`. All other causes (USB detach, read error, explicit
     * disconnect) produce `isClean = false`.
     */
    private fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) {
        if (wirelessServer != null) {
            AppLog.i("AapService: Disconnected. Restarting discovery loop in 2s...")
            serviceScope.launch {
                delay(2000)
                if (!commManager.isConnected) startDiscovery()
            }
        } else if (!state.isClean) {
            val mode = App.provide(this).settings.wifiConnectionMode
            if (mode == 1) {
                AppLog.i("AapService: Unclean disconnect in Auto Mode. Retrying discovery in 2s...")
                serviceScope.launch {
                    delay(2000)
                    if (!commManager.isConnected) startDiscovery(oneShot = true)
                }
            }
        }
    }

    private fun registerReceivers() {
        usbReceiver = UsbReceiver(this)
        ContextCompat.registerReceiver(
            this, nightModeUpdateReceiver,
            IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, usbReceiver,
            UsbReceiver.createFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Starts [WirelessServer] if the user has configured server WiFi mode (mode == 2). */
    private fun initWifiMode() {
        if (App.provide(this).settings.wifiConnectionMode == 2) startWirelessServer()
    }

    override fun onDestroy() {
        AppLog.i("AapService destroying...")
        isDestroying = true
        stopForeground(true)
        stopWirelessServer()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        commManager.disconnect()
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDestroy")
        }
        nightModeManager?.stop()
        unregisterReceiver(nightModeUpdateReceiver)
        unregisterReceiver(usbReceiver)
        uiModeManager.disableCarMode(0)
        serviceScope.cancel() // cancel last so the IO decoder-stop launch above can complete
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop before re-posting the notification to avoid a flash
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received.")
            isDestroying = true
            if (commManager.isConnected) commManager.disconnect()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, createNotification())
        when (intent?.action) {
            ACTION_START_SELF_MODE       -> startSelfMode()
            ACTION_START_WIRELESS        -> startWirelessServer()
            ACTION_START_WIRELESS_SCAN   -> {
                val mode = App.provide(this).settings.wifiConnectionMode
                startDiscovery(oneShot = (mode != 2))
            }
            ACTION_STOP_WIRELESS         -> stopWirelessServer()
            ACTION_DISCONNECT            -> {
                AppLog.i("Disconnect action received.")
                if (commManager.isConnected) commManager.disconnect()
            }
            ACTION_CONNECT_SOCKET        -> {
                // Caller already invoked commManager.connect(socket); the connectionState
                // observer in observeConnectedState() handles the rest — nothing to do here.
            }
            ACTION_CHECK_USB             -> checkAlreadyConnectedUsb(force = true)
            else                         -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    checkAlreadyConnectedUsb()
                }
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // USB
    // -------------------------------------------------------------------------

    override fun onUsbAttach(device: UsbDevice) {
        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            checkAlreadyConnectedUsb(force = true)
        }
        // Normal-mode devices are handled exclusively by UsbAttachedActivity (manifest intent
        // filter). Calling connectAndSwitch() here would race with UsbAttachedActivity and
        // cause openDevice() to return null in one of the two callers → "Failed" toast.
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (commManager.isConnectedToUsbDevice(device)) {
            commManager.disconnect()
        }
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {}

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     */
    private fun checkAlreadyConnectedUsb(force: Boolean = false) {
        val settings = App.provide(this).settings
        if (!force && !settings.autoConnectLastSession) return
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting) return

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usbManager.deviceList.values) {
            val deviceCompat = UsbDeviceCompat(device)
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.i("Found device already in accessory mode: ${deviceCompat.uniqueName}")
                serviceScope.launch { connectUsbWithRetry(device) }
                return
            }
            if (settings.isConnectingDevice(deviceCompat)) {
                if (usbManager.hasPermission(device)) {
                    AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                    val usbMode = UsbAccessoryMode(usbManager)
                    if (usbMode.connectAndSwitch(device)) {
                        AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                        // The device will detach and re-attach as an accessory,
                        // triggering UsbAttachedActivity or UsbReceiver again
                        return
                    }
                } else {
                    AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    private suspend fun connectUsbWithRetry(device: UsbDevice, maxRetries: Int = 3) {
        var retryCount = 0
        var success = false
        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            commManager.connect(device)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }
    }

    // -------------------------------------------------------------------------
    // Wireless
    // -------------------------------------------------------------------------

    /**
     * Starts the [WirelessServer] (TCP on port 5288) and kicks off the initial NSD scan.
     * No-op if the server is already running.
     */
    private fun startWirelessServer() {
        if (wirelessServer != null) return
        wirelessServer = WirelessServer().apply { start() }
        startDiscovery()
    }

    /**
     * Starts an NSD (mDNS) scan for Android Auto Wireless services on the local network.
     *
     * @param oneShot if `true`, does not reschedule after the scan finishes —
     *                used for the "auto WiFi" reconnect case.
     */
    private fun startDiscovery(oneShot: Boolean = false) {
        if (commManager.isConnected || (wirelessServer == null && !oneShot)) return

        networkDiscovery?.stop()
        scanningState.value = true

        networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
            override fun onServiceFound(ip: String, port: Int, socket: java.net.Socket?) {
                if (commManager.isConnected) {
                    // Already connected by the time this callback fired; discard the socket
                    try { socket?.close() } catch (e: Exception) {}
                    return
                }
                when (port) {
                    5277 -> {
                        // Headunit Server detected — reuse the pre-opened socket when possible
                        AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                        serviceScope.launch {
                            if (socket != null && socket.isConnected)
                                commManager.connect(socket)
                            else
                                commManager.connect(ip, 5277)
                        }
                    }
                    5289 -> {
                        // WiFi Launcher detected — no connection needed, just log
                        AppLog.i("Triggered Wifi Launcher at $ip:$port.")
                    }
                }
            }

            override fun onScanFinished() {
                scanningState.value = false
                if (oneShot) {
                    AppLog.i("One-shot scan finished.")
                    return
                }
                // Reschedule the next scan after 10 s to avoid hammering the network
                serviceScope.launch {
                    delay(10000)
                    if (wirelessServer != null && !commManager.isConnected) startDiscovery()
                }
            }
        })
        networkDiscovery?.startScan()
    }

    private fun stopWirelessServer() {
        networkDiscovery?.stop()
        networkDiscovery = null
        wirelessServer?.stopServer()
        wirelessServer = null
        scanningState.value = false
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AapService::class.java).apply { action = ACTION_STOP_SERVICE },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Tap the notification to go back to the projection screen (if connected) or home
        val (notificationIntent, requestCode) = if (commManager.isConnected) {
            AapProjectionActivity.intent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } to 100
        } else {
            Intent(this, com.andrerinas.headunitrevived.main.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } to 101
        }

        val contentText = if (commManager.isConnected)
            getString(R.string.notification_projection_active)
        else
            getString(R.string.notification_service_running)

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Headunit Revived")
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(
                this, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    // -------------------------------------------------------------------------
    // Self Mode
    // -------------------------------------------------------------------------

    /**
     * "Self Mode" connects the device to itself over the loopback interface.
     *
     * Starts [WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    private fun startSelfMode() {
        startWirelessServer()

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            connectivityManager.activeNetwork else null
        val networkToUse = activeNetwork ?: createFakeNetwork(99999)
        val fakeWifiInfo = createFakeWifiInfo()

        val magicalIntent = Intent().apply {
            setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            putExtra("PARAM_SERVICE_PORT", 5288)
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            fakeWifiInfo?.let { putExtra("wifi_info", it) }
        }

        try {
            AppLog.i("Launching AA Wireless Startup...")
            startActivity(magicalIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to launch AA", e)
            Toast.makeText(this, getString(R.string.failed_start_android_auto), Toast.LENGTH_SHORT).show()
        }
    }

    /** Reflectively constructs an `android.net.Network` from a raw network ID integer. */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    /** Reflectively constructs a `WifiInfo` with a fake SSID for the Self Mode intent. */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID")
                    .apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (e: Exception) {}
            wifiInfo
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // WirelessServer
    // -------------------------------------------------------------------------

    /**
     * Coroutine-based server that listens for incoming TCP connections on port 5288.
     *
     * Registers the service over mDNS (NSD) as `_aawireless._tcp` so Android Auto
     * Wireless clients can discover it automatically. Each accepted socket is handed
     * off to [CommManager.connect] on the service coroutine scope. Only one connection
     * is allowed at a time; subsequent sockets are closed immediately.
     *
     * Uses [isActive] for cooperative cancellation. [stopServer] cancels the job and
     * closes the server socket to unblock the blocking [ServerSocket.accept] call.
     */
    private inner class WirelessServer {
        private var serverSocket: ServerSocket? = null
        private var nsdManager: NsdManager? = null
        private var registrationListener: NsdManager.RegistrationListener? = null
        private var job: Job? = null

        fun start() {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            registerNsd()

            job = serviceScope.launch(Dispatchers.IO) {
                try {
                    serverSocket = ServerSocket(5288).apply { reuseAddress = true }
                    AppLog.i("Wireless Server listening on port 5288")
                    logLocalNetworkInterfaces()

                    while (isActive) {
                        val clientSocket = serverSocket?.accept() ?: break
                        AppLog.i("Wireless client connected: ${clientSocket.inetAddress}")
                        serviceScope.launch {
                            if (commManager.isConnected) {
                                AppLog.w("Already connected, dropping wireless client")
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else {
                                AppLog.i("Wireless client accepted from ${clientSocket.inetAddress}. Initializing connection...")
                                commManager.connect(clientSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) AppLog.e("Wireless server error", e)
                } finally {
                    unregisterNsd()
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
        }

        /** Logs all non-loopback IPv4 addresses; useful for debugging connectivity issues. */
        private fun logLocalNetworkInterfaces() {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            AppLog.i("Interface: ${iface.name}, IP: ${addr.hostAddress}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Error logging interfaces", e)
            }
        }

        private fun registerNsd() {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = 5288
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }

        private fun unregisterNsd() {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            registrationListener = null
        }

        fun stopServer() {
            job?.cancel()
            job = null
            // Close the socket to unblock the accept() call in the coroutine.
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        /** `true` while a Self Mode session is active. */
        var selfMode = false

        /**
         * Emits `true` while a WiFi NSD scan is in progress.
         * Observed by `HomeFragment` via a lifecycle-aware flow collector.
         */
        val scanningState = MutableStateFlow(false)

        // Service action strings used with startService() and sendBroadcast()
        const val ACTION_START_SELF_MODE           = "com.andrerinas.headunitrevived.ACTION_START_SELF_MODE"
        const val ACTION_START_WIRELESS            = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS"
        const val ACTION_START_WIRELESS_SCAN       = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS_SCAN"
        const val ACTION_STOP_WIRELESS             = "com.andrerinas.headunitrevived.ACTION_STOP_WIRELESS"
        const val ACTION_CHECK_USB                 = "com.andrerinas.headunitrevived.ACTION_CHECK_USB"
        const val ACTION_STOP_SERVICE              = "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE"
        const val ACTION_DISCONNECT                = "com.andrerinas.headunitrevived.ACTION_DISCONNECT"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.headunitrevived.ACTION_REQUEST_NIGHT_MODE_UPDATE"
        /**
         * Sent after the caller has already invoked [CommManager.connect(socket)].
         * The [observeConnectedState] flow observer handles the result — [onStartCommand]
         * does nothing for this action.
         */
        const val ACTION_CONNECT_SOCKET            = "com.andrerinas.headunitrevived.ACTION_CONNECT_SOCKET"

    }
}
