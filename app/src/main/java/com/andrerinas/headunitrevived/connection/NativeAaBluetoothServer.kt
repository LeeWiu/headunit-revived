package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.aap.protocol.proto.Wireless
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import androidx.core.content.ContextCompat
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

    private var currentSsid: String? = null
    private var currentPsk: String? = null
    private var currentIp: String? = null
    private var currentBssid: String? = null

    companion object {
        private val AA_UUID_STATIC = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

        fun checkCompatibility(): Boolean {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!adapter.isEnabled) return false
            
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID_STATIC)
                socket.close()
                AppLog.i("NativeAA: Compatibility Check SUCCESS")
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Compatibility Check FAILED: ${e.message}")
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) {
            AppLog.d("NativeAA: Server already running, stopping first...")
            stop()
        }
        isRunning = true

        AppLog.i("NativeAA: Starting Bluetooth server listeners and WiFi Direct...")

        wifiDirectManager.createGroup { resSsid, resPsk, resIp, resBssid ->
            AppLog.i("NativeAA: WiFi Direct Group initialized. SSID=$resSsid, IP=$resIp, BSSID=$resBssid")
            currentSsid = resSsid
            currentPsk = resPsk
            currentIp = resIp
            currentBssid = resBssid
        }

        startListeners()

        val lastMac = settings.autoStartBluetoothDeviceMac
        if (lastMac.isNotEmpty()) {
            Thread {
                try { Thread.sleep(2000) } catch (e: Exception) {}
                activeConnectToPhone(lastMac)
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListeners() {
        Thread {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("Headunit Revived AA", AA_UUID)
                AppLog.i("NativeAA: AA server listening on $AA_UUID")

                while (isRunning) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: BT connection accepted from: ${socket.remoteDevice.name}")
                        handleConnection(socket)
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: AA server socket closed")
            }
        }.apply { name = "NativeAaBtServer"; start() }

        Thread {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("HFP", HFP_UUID)
                AppLog.i("NativeAA: HFP server listening on $HFP_UUID")

                while (isRunning) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: HFP connection from: ${socket.remoteDevice.name}")
                        Thread {
                            try {
                                val buf = ByteArray(1024)
                                socket.inputStream.read(buf)
                            } catch (e: Exception) {}
                            finally { try { socket.close() } catch (e: Exception) {} }
                        }.start()
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: HFP server socket closed")
            }
        }.apply { name = "NativeAaHfpServer"; start() }
    }

    @SuppressLint("MissingPermission")
    private fun activeConnectToPhone(mac: String) {
        Thread {
            AppLog.i("NativeAA: Attempting active poke to phone ($mac)...")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
            val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { null } ?: return@Thread
            
            try {
                val socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE)
                socket.connect()
                AppLog.i("NativeAA: Successfully poked phone ($mac).")
                socket.close()
            } catch (e: IOException) {
                AppLog.d("NativeAA: Active poke failed: ${e.message}")
            }
        }.apply { name = "NativeAaWakeup"; start() }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        Thread {
            try {
                AapService.isNativeHandshakeActive = true
                val input = DataInputStream(socket.inputStream)
                val output = socket.outputStream

                AppLog.i("NativeAA: BT Handshake started. Waiting for cached WiFi credentials...")

                var waitCount = 0
                while ((currentSsid == null || currentIp == null) && waitCount < 60) {
                    Thread.sleep(500)
                    waitCount++
                }

                if (currentSsid == null || currentIp == null) {
                    AppLog.e("NativeAA: Handshake failed - WiFi Direct not ready in time")
                    return@Thread
                }

                val ssid = currentSsid!!
                val ip = currentIp!!
                val psk = currentPsk ?: ""
                val bssid = currentBssid ?: "00:00:00:00:00:00"

                AppLog.i("NativeAA: Refreshing Wireless TCP Server for P2P interface...")
                val refreshIntent = Intent(context, AapService::class.java).apply {
                    action = AapService.ACTION_START_WIRELESS
                }
                ContextCompat.startForegroundService(context, refreshIntent)

                AppLog.i("NativeAA: WiFi Ready. SSID=$ssid, IP=$ip, BSSID=$bssid. Sending WifiStartRequest (Type 1)...")
                sendWifiStartRequest(output, ip, 5288)
                
                val response = readProtobuf(input)
                if (response.type == 2) {
                    AppLog.i("NativeAA: Phone requested security info (Type 2). Sending Credentials (Type 3) SSID=$ssid, PSK=$psk, BSSID=$bssid")
                    sendWifiSecurityResponse(output, ssid, psk, bssid)
                    AppLog.i("NativeAA: Handshake finished successfully. Keeping BT alive for 20s for WiFi transition...")
                }

            } catch (e: Exception) {
                AppLog.e("NativeAA: Handshake error: ${e.message}")
            } finally {
                Thread.sleep(20000) // Keep Bluetooth open much longer (20s)
                try { socket.close() } catch (e: Exception) {}
                AapService.isNativeHandshakeActive = false
                AppLog.i("NativeAA: Handshake thread finished.")
            }
        }.apply { name = "NativeAaHandshake"; start() }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()
        sendProtobuf(output, request.toByteArray(), 1)
    }

    private fun sendWifiSecurityResponse(output: OutputStream, ssid: String, key: String, bssid: String) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setBssid("") // Empty BSSID as per HUR 6.3
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
        currentSsid = null
        currentPsk = null
        currentIp = null
        currentBssid = null
        wifiDirectManager.stop()
    }
}
