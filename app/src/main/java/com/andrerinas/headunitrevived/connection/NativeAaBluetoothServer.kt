package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.headunitrevived.aap.protocol.proto.Wireless
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*

class NativeAaBluetoothServer(private val context: Context) {
    private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    private val A2DP_SOURCE = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")
    
    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    private var isRunning = false
    private val wifiDirectManager = WifiDirectManager(context)
    private val settings = Settings(context)

    // Cached credentials
    private var currentSsid: String? = null
    private var currentPsk: String? = null
    private var currentIp: String? = null

    companion object {
        private val AA_UUID_STATIC = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

        fun checkCompatibility(): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                AppLog.w("NativeAA: Android 11+ detected. Native Wireless is not supported due to system restrictions.")
                return false
            }
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!adapter.isEnabled) return false
            
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID_STATIC)
                socket.close()
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Device is NOT compatible with Native Wireless (RFCOMM error)")
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true

        AppLog.i("NativeAA: Starting services...")

        // 1. Start WiFi Direct immediately to have credentials ready
        wifiDirectManager.createGroup { ssid, psk, ip ->
            AppLog.i("NativeAA: WiFi Direct Ready. SSID=$ssid, IP=$ip")
            currentSsid = ssid
            currentPsk = psk
            currentIp = ip
        }

        // 2. Start RFCOMM Listeners
        startListeners()

        // 3. Optional: Try to wake up last known phone
        val lastMac = settings.autoStartBluetoothDeviceMac
        if (lastMac.isNotEmpty()) {
            activeConnectToPhone(lastMac)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListeners() {
        Thread {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("Headunit Revived AA", AA_UUID)
                AppLog.i("NativeAA: AA Bluetooth server listening on $AA_UUID")

                while (isRunning) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: BT client connected to AA profile: ${socket.remoteDevice.name}")
                        handleConnection(socket)
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: AA server socket closed: ${e.message}")
            }
        }.apply { name = "NativeAaBtServer"; start() }

        Thread {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("HFP", HFP_UUID)
                AppLog.i("NativeAA: HFP Bluetooth server listening on $HFP_UUID")

                while (isRunning) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: BT client connected to HFP profile: ${socket.remoteDevice.name}")
                        // HUR just accepts and closes or keeps open. Some phones need this to proceed.
                        Thread {
                            try { socket.inputStream.read(ByteArray(1024)) } catch (e: Exception) {}
                            finally { try { socket.close() } catch (e: Exception) {} }
                        }.start()
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: HFP server socket closed: ${e.message}")
            }
        }.apply { name = "NativeAaHfpServer"; start() }
    }

    @SuppressLint("MissingPermission")
    private fun activeConnectToPhone(mac: String) {
        Thread {
            AppLog.i("NativeAA: Attempting active wakeup connect to $mac...")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
            val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { null } ?: return@Thread
            
            try {
                // Try connecting to A2DP Source/Headset AG to trigger AA on phone
                val socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE)
                socket.connect()
                AppLog.i("NativeAA: Successfully poked phone ($mac) via A2DP/HFP profile")
                socket.close()
            } catch (e: IOException) {
                AppLog.d("NativeAA: Active poke to $mac failed (phone might be busy or offline): ${e.message}")
            }
        }.apply { name = "NativeAaWakeup"; start() }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        Thread {
            try {
                val input = DataInputStream(socket.inputStream)
                val output = socket.outputStream

                AppLog.i("NativeAA: Starting Bluetooth handshake...")

                // Wait up to 30s for WiFi credentials if they aren't ready yet
                var waitCount = 0
                while ((currentSsid == null || currentIp == null) && waitCount < 60) {
                    Thread.sleep(500)
                    waitCount++
                }

                val ssid = currentSsid
                val psk = currentPsk
                val ip = currentIp

                if (ssid == null || ip == null) {
                    AppLog.e("NativeAA: Handshake failed - WiFi Direct not ready in time")
                    return@Thread
                }

                // Step 1: Send WifiStartRequest (Type 1)
                sendWifiStartRequest(output, ip, 5288)
                
                // Step 2: Read response
                val response = readProtobuf(input)
                if (response.type == 2) {
                    AppLog.i("NativeAA: Phone requested security info (Type 2). Sending credentials...")
                    sendWifiSecurityResponse(output, ssid, psk ?: "")
                } else {
                    AppLog.w("NativeAA: Unexpected message type ${response.type}")
                }

            } catch (e: Exception) {
                AppLog.e("NativeAA: Handshake error: ${e.message}")
            } finally {
                Thread.sleep(2000) // Keep socket alive briefly
                try { socket.close() } catch (e: Exception) {}
            }
        }.apply { name = "NativeAaHandshake"; start() }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0) // Status 0 = Success
            .build()
        sendProtobuf(output, request.toByteArray(), 1)
    }

    private fun sendWifiSecurityResponse(output: OutputStream, ssid: String, key: String) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setBssid("00:00:00:00:00:00")
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(Wireless.AccessPointType.STATIC)
            .build()
        sendProtobuf(output, response.toByteArray(), 3)
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Short) {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put((data.size shr 8).toByte())
        buffer.put((data.size and 0xFF).toByte())
        buffer.putShort(type)
        buffer.put(data)
        output.write(buffer.array())
        output.flush()
        AppLog.i("NativeAA: Sent Protobuf type $type, size ${data.size}")
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(4)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    fun stop() {
        isRunning = false
        try { aaServerSocket?.close() } catch (e: Exception) {}
        try { hfpServerSocket?.close() } catch (e: Exception) {}
        aaServerSocket = null
        hfpServerSocket = null
        wifiDirectManager.stop()
    }
}
