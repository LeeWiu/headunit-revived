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
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.connection.NetworkDiscovery
import com.andrerinas.headunitrevived.connection.SocketAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.NightModeManager
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class AapService : Service(), UsbReceiver.Listener {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var uiModeManager: UiModeManager
    private var accessoryConnection: AccessoryConnection? = null
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var wirelessServer: WirelessServer? = null
    private var nativeAaServer: com.andrerinas.headunitrevived.connection.NativeAaBluetoothServer? = null

    private var pendingConnectionType: String = ""
    private var pendingConnectionIp: String = ""
    private var pendingConnectionUsbDevice: String = ""
    private val connectionAttemptId = AtomicInteger(0)
    private val isConnecting = AtomicBoolean(false)

    private val transport: AapTransport
        get() = App.provide(this).transport

    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DisconnectIntent.action) {
                val isClean = intent.getBooleanExtra(DisconnectIntent.EXTRA_CLEAN, false)
                if (isConnected) {
                    AppLog.i("AapService received disconnect intent (clean=$isClean) -> closing connection")
                    onDisconnect(isClean)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...");

        startForeground(1, createNotification());

        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager;
        uiModeManager.enableCarMode(0);

        usbReceiver = UsbReceiver(this);
        
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            App.provide(this).transport.send(NightModeEvent(isNight))
        }
        nightModeManager?.start()

        val nightModeFilter = IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE)
        val disconnectFilter = IntentFilter(DisconnectIntent.action)
        val usbFilter = UsbReceiver.createFilter()

        ContextCompat.registerReceiver(this, nightModeUpdateReceiver, nightModeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, disconnectReceiver, disconnectFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, usbReceiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        startService(GpsLocationService.intent(this));

        val mode = App.provide(this).settings.wifiConnectionMode
        if (mode == 2) {
            startWirelessServer();
        } else if (mode == 3) {
            startNativeAaServer();
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AapService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            0, 
            stopIntent, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Headunit Revived")
            .setContentText("Headunit Revived is running")
            .setContentIntent(PendingIntent.getActivity(this, 0, 
                Intent(this, com.andrerinas.headunitrevived.main.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build();
    }

    override fun onDestroy() {
        AppLog.i("AapService destroying...");
        stopWirelessServer();
        stopNativeAaServer();
        serviceJob.cancel();
        onDisconnect();
        nightModeManager?.stop()
        unregisterReceiver(nightModeUpdateReceiver)
        unregisterReceiver(disconnectReceiver)
        unregisterReceiver(usbReceiver);
        uiModeManager.disableCarMode(0);
        super.onDestroy();
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification());
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                AppLog.i("Stop action received.");
                stopSelf();
                return START_NOT_STICKY;
            }
            ACTION_START_SELF_MODE -> {
                startSelfMode();
            }
            ACTION_START_WIRELESS -> {
                startWirelessServer();
            }
            ACTION_START_NATIVE_AA -> {
                startNativeAaServer();
            }
            ACTION_START_WIRELESS_SCAN -> {
                val mode = App.provide(this).settings.wifiConnectionMode
                if (mode != 3) {
                    startDiscovery(oneShot = (mode != 2))
                }
            }
            ACTION_STOP_WIRELESS -> {
                stopWirelessServer();
            }
            ACTION_STOP_NATIVE_AA -> {
                stopNativeAaServer();
            }
            else -> {
                handleConnectionIntent(intent);
            }
        }
        return START_STICKY;
    }

    private fun handleConnectionIntent(intent: Intent?) {
        val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0;
        if (connectionType == 0) return ;

        // Ensure old connection is closed!
        accessoryConnection?.disconnect()
        accessoryConnection = connectionFactory(intent, this);
        
        if (accessoryConnection == null) {
            AppLog.e("Cannot create connection from intent");
            return;
        }

        when (connectionType) {
            TYPE_USB -> {
                val device = DeviceIntent(intent).device;
                pendingConnectionType = Settings.CONNECTION_TYPE_USB;
                pendingConnectionIp = "";
                pendingConnectionUsbDevice = if (device != null) UsbDeviceCompat.getUniqueName(device) else "";
            }
            TYPE_WIFI -> {
                pendingConnectionType = Settings.CONNECTION_TYPE_WIFI;
                pendingConnectionIp = intent?.getStringExtra(EXTRA_IP) ?: "";
                pendingConnectionUsbDevice = "";
            }
        }

        // Capture the connection instance so we pass the exact object to the result handler
        val conn = accessoryConnection;
        val attemptId = connectionAttemptId.incrementAndGet();

        serviceScope.launch {
            val connectionResult = withContext(Dispatchers.IO) {
                conn?.connect() ?: false;
            }
            onConnectionResult(connectionResult, attemptId, conn);
        }
    }

    private var networkDiscovery: NetworkDiscovery? = null

    private fun startNativeAaServer() {
        if (nativeAaServer != null) return
        nativeAaServer = com.andrerinas.headunitrevived.connection.NativeAaBluetoothServer(this).apply { start() }
        
        // Native AA also requires the Wireless TCP server to be listening
        startWirelessServer()
    }

    private fun stopNativeAaServer() {
        nativeAaServer?.stop()
        nativeAaServer = null
    }

    private fun startWirelessServer() {
        if (wirelessServer != null) return
        wirelessServer = WirelessServer().apply { start() }
        
        val mode = App.provide(this).settings.wifiConnectionMode
        if (mode != 3) {
            startDiscovery()
        }
    }

    private fun startDiscovery(oneShot: Boolean = false) {
        if (isConnected || isConnecting.get() || (wirelessServer == null && !oneShot)) return

        // Ensure old discovery is stopped/cleaned
        networkDiscovery?.stop()
        isScanning = true
        sendBroadcast(Intent(ACTION_SCAN_STARTED))

        networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
            override fun onServiceFound(ip: String, port: Int, socket: java.net.Socket?) {
                if (isConnected) {
                    try { socket?.close() } catch (e: Exception) {}
                    return
                }

                if (port == 5277) {
                    // Headunit Server detected -> We must connect actively
                    AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                    pendingSocket = socket
                    val intent = Intent(this@AapService, AapService::class.java).apply {
                        putExtra(EXTRA_IP, ip)
                        putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI)
                    }
                    handleConnectionIntent(intent)
                } else if (port == 5289) {
                    // Wifi Launcher detected -> Triggered
                    AppLog.i("Triggered Wifi Launcher at $ip:$port.")
                }
            }

            override fun onScanFinished() {
                isScanning = false
                sendBroadcast(Intent(ACTION_SCAN_FINISHED))
                if (oneShot) {
                    AppLog.i("One-shot scan finished.")
                    return
                }
                // Schedule next scan in 10s
                serviceScope.launch {
                    delay(10000)
                    if (wirelessServer != null && !isConnected) {
                        startDiscovery()
                    }
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

        isScanning = false
        sendBroadcast(Intent(ACTION_SCAN_FINISHED))
    }

    private inner class WirelessServer : Thread() {
        private var serverSocket: ServerSocket? = null;
        private var nsdManager: NsdManager? = null;
        private var registrationListener: NsdManager.RegistrationListener? = null;
        private var running = true;

        override fun run() {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager;
            registerNsd();

            try {
                serverSocket = ServerSocket(5288).apply { reuseAddress = true };
                AppLog.i("Wireless Server listening on port 5288");
                
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

                while (running) {
                    val clientSocket = serverSocket?.accept();
                    if (clientSocket != null) {
                        AppLog.i("Wireless client connected: ${clientSocket.inetAddress}");
                        
                        serviceScope.launch {
                            if (isConnected) {
                                AppLog.w("Already connected, dropping wireless client");
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else {
                                AppLog.i("Wireless client accepted from ${clientSocket.inetAddress}. Initializing connection...");
                                pendingConnectionType = Settings.CONNECTION_TYPE_WIFI;
                                pendingConnectionIp = clientSocket.inetAddress.hostAddress ?:"";
                                pendingConnectionUsbDevice = "";

                                // Prepare and capture the connection instance
                                val conn = SocketAccessoryConnection(clientSocket, this@AapService);
                                accessoryConnection = conn;

                                // mark this attempt before starting the blocking connect
                                val attemptId = connectionAttemptId.incrementAndGet();
                                
                                val success = withContext(Dispatchers.IO) {
                                    conn.connect()
                                }
                                AppLog.i("Wireless Socket connect() result: $success");

                                onConnectionResult(success, attemptId, conn);
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) AppLog.e("Wireless server error", e);
            } finally {
                unregisterNsd();
            }
        }

        private fun registerNsd() {
            val mode = App.provide(this@AapService).settings.wifiConnectionMode
            if (mode == 3) {
                AppLog.i("NativeAA: Skipping NSD registration (not needed)")
                return
            }

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless";
                serviceType = "_aawireless._tcp";
                port = 5288;
            };
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}");
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err");
                override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered");
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err");
            };
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        }

        private fun unregisterNsd() {
            registrationListener?.let { nsdManager?.unregisterService(it) };
            registrationListener = null;
        }

        fun stopServer() {
            running = false;
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun startSelfMode() {
        startWirelessServer();

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager;
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.activeNetwork else null;
        val networkToUse = activeNetwork ?: createFakeNetwork(99999);
        val fakeWifiInfo = createFakeWifiInfo();

        val magicalIntent = Intent().apply {
            setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity");
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1");
            putExtra("PARAM_SERVICE_PORT", 5288);
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) };
            fakeWifiInfo?.let { putExtra("wifi_info", it) };
        };

        try {
            AppLog.i("Launching AA Wireless Startup...");
            startActivity(magicalIntent);
        } catch (e: Exception) {
            AppLog.e("Failed to launch AA", e);
            Toast.makeText(this, getString(R.string.failed_start_android_auto), Toast.LENGTH_SHORT).show();
        }
    }

    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain();
        return try {
            parcel.writeInt(netId);
            parcel.setDataPosition(0);
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>;
            creator.createFromParcel(parcel) as Parcelable;
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo");
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Parcelable;
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }.set(wifiInfo, "\"Headunit-Fake-Wifi\"");
            } catch (e: Exception) {}
            wifiInfo;
        } catch (e: Exception) { null }
    }

    private suspend fun onConnectionResult(success: Boolean, attemptId: Int, connection: AccessoryConnection?) {
        try {
            if (attemptId != connectionAttemptId.get()) {
                AppLog.w("onConnectionResult: stale attempt $attemptId, current ${connectionAttemptId.get()}");
                return;
            }
            val activeConnection = connection ?: run {
                AppLog.w("onConnectionResult: accessoryConnection cleared before transport start (attempt $attemptId)");
                return;
            }

            if (success) {
                reset();
                val transportStarted = withContext(Dispatchers.IO) {
                    transport.start(activeConnection);
                }

                if (transportStarted) {
                    isConnected = true;
                    sendBroadcast(ConnectedIntent());
                    
                    // Sync current night mode state immediately after connection
                    nightModeManager?.resendCurrentState()
                    
                    if (pendingConnectionType.isNotEmpty()) {
                        val settings = App.provide(this).settings;
                        settings.saveLastConnection(
                            type = pendingConnectionType,
                            ip = pendingConnectionIp,
                            usbDevice = pendingConnectionUsbDevice
                        );
                        AppLog.i("Saved last connection: type=$pendingConnectionType, ip=$pendingConnectionIp, usb=$pendingConnectionUsbDevice");
                        pendingConnectionType = "";
                        pendingConnectionIp = "";
                        pendingConnectionUsbDevice = "";
                    }

                    val aapIntent = AapProjectionActivity.intent(this@AapService).apply {
                        putExtra(AapProjectionActivity.EXTRA_FOCUS, true);
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    };
                    startActivity(aapIntent);
                } else {
                    stopSelf();
                }
            }
        } finally {
            isConnecting.set(false)
        }
    }

    private fun onDisconnect(isClean: Boolean = false) {
        isConnected = false;
        sendBroadcast(DisconnectIntent(isClean));
        reset();
        accessoryConnection?.disconnect();
        accessoryConnection = null;
        // Invalidate any in-flight attempts
        connectionAttemptId.incrementAndGet();

        if (wirelessServer != null) {
            AppLog.i("AapService: Disconnected. Restarting discovery loop in 2s...");
            serviceScope.launch {
                delay(2000);
                if (!isConnected) startDiscovery();
            }
        } else if (!isClean) {
             val mode = App.provide(this).settings.wifiConnectionMode
             if (mode == 1) { // Auto Mode
                 AppLog.i("AapService: Unclean disconnect in Auto Mode. Retrying discovery in 2s...");
                 serviceScope.launch {
                     delay(2000);
                     if (!isConnected) startDiscovery(oneShot = true);
                 }
             }
        }
    }

    private fun reset() {
        App.provide(this).resetTransport();
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop();
            App.provide(this@AapService).videoDecoder.stop("AapService::reset");
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (accessoryConnection is UsbAccessoryConnection) {
            if ((accessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                onDisconnect();
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {}
    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {}

    companion object {
        var isConnected = false;
        var selfMode = false;
        var isScanning = false;
        var pendingSocket: java.net.Socket? = null;
        const val ACTION_START_SELF_MODE = "com.andrerinas.headunitrevived.ACTION_START_SELF_MODE";
        const val ACTION_START_WIRELESS = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS";
        const val ACTION_START_NATIVE_AA = "com.andrerinas.headunitrevived.ACTION_START_NATIVE_AA";
        const val ACTION_STOP_WIRELESS = "com.andrerinas.headunitrevived.ACTION_STOP_WIRELESS";
        const val ACTION_STOP_NATIVE_AA = "com.andrerinas.headunitrevived.ACTION_STOP_NATIVE_AA";
        const val ACTION_START_WIRELESS_SCAN = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS_SCAN";
        const val ACTION_SCAN_STARTED = "com.andrerinas.headunitrevived.ACTION_SCAN_STARTED"
        const val ACTION_SCAN_FINISHED = "com.andrerinas.headunitrevived.ACTION_SCAN_FINISHED"
        const val ACTION_STOP_SERVICE = "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE";
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.headunitrevived.ACTION_REQUEST_NIGHT_MODE_UPDATE"
        private const val TYPE_USB = 1;
        private const val TYPE_WIFI = 2;
        private const val EXTRA_CONNECTION_TYPE = "extra_connection_type";
        private const val EXTRA_IP = "extra_ip";

        fun createIntent(device: UsbDevice, context: Context): Intent {
            return Intent(context, AapService::class.java).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device);
                putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB);
            };
        }

        fun createIntent(ip: String, context: Context): Intent {
            return Intent(context, AapService::class.java).apply {
                putExtra(EXTRA_IP, ip);
                putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI);
            };
        }

        private fun connectionFactory(intent: Intent?, context: Context): AccessoryConnection? {
            val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0;
            if (connectionType == TYPE_USB) {
                val device = DeviceIntent(intent).device ?: return null;
                return UsbAccessoryConnection(context.getSystemService(Context.USB_SERVICE) as UsbManager, device);
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: "";

                val socket = pendingSocket
                pendingSocket = null // consume
                if (socket != null && socket.isConnected && socket.inetAddress.hostAddress == ip) {
                    AppLog.i("Reusing discovery socket for connection to $ip")
                    return SocketAccessoryConnection(socket, context)
                }

                return SocketAccessoryConnection(ip, 5277, context);
            }
            return null;
        }
    }
}
