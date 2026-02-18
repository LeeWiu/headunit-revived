package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.headunitrevived.aap.protocol.proto.Wireless
import com.andrerinas.headunitrevived.utils.AppLog
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*

class NativeAaBluetoothServer(private val context: Context) {
    private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    private var serverSocket: BluetoothServerSocket? = null
    private var thread: Thread? = null
    private var isRunning = false
    private val wifiDirectManager = WifiDirectManager(context)

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        // Preliminary permission check for logging
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            AppLog.w("NativeAA: WARNING - ACCESS_FINE_LOCATION not granted. WiFi Direct will likely fail.")
        }

        isRunning = true
        thread = Thread {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                AppLog.e("Bluetooth adapter not available or disabled")
                isRunning = false
                return@Thread
            }

            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("Headunit Revived AA", AA_UUID)
                AppLog.i("Native AA Bluetooth server listening on $AA_UUID")

                while (isRunning) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        if (isRunning) AppLog.e("Error accepting Bluetooth connection: ${e.message}")
                        break
                    }

                    if (socket != null) {
                        AppLog.i("NativeAA: Bluetooth client connected: ${socket.remoteDevice.name} (${socket.remoteDevice.address})")
                        handleConnection(socket)
                    }
                }
            } catch (e: IOException) {
                AppLog.e("NativeAA: Bluetooth server error: ${e.message}")
            } finally {
                stop()
            }
        }.apply { 
            name = "NativeAaBtServer"
            start() 
        }
    }

    fun stop() {
        AppLog.i("NativeAA: Stopping Bluetooth server...")
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        thread = null
        wifiDirectManager.stop()
    }

    private fun handleConnection(socket: BluetoothSocket) {
        Thread {
            try {
                val input = DataInputStream(socket.inputStream)
                val output = socket.outputStream

                AppLog.i("NativeAA: Starting Bluetooth handshake thread...")

                // 1. Create Wifi Direct Group to get credentials
                wifiDirectManager.createGroup { ssid, psk, ip ->
                    AppLog.i("NativeAA: WifiDirect Group ready. Credentials: SSID=$ssid, IP=$ip")
                    
                    try {
                        // 2. Send WifiStartRequest (our IP and port 5288)
                        sendWifiStartRequest(output, ip, 5288)
                        AppLog.i("NativeAA: WifiStartRequest sent. Waiting for response...")
                        
                        // 3. Wait for Phone to request security info (Type 2)
                        val response = readProtobuf(input)
                        if (response.type == 2) {
                            AppLog.i("NativeAA: Phone requested security info (Type 2). Sending SSID/PSK...")
                            sendWifiSecurityResponse(output, ssid, psk)
                            AppLog.i("NativeAA: Security response sent.")
                        } else {
                            AppLog.w("NativeAA: Unexpected message type from phone: ${response.type}. Payload size: ${response.payload.size}")
                        }
                    } catch (e: Exception) {
                        AppLog.e("NativeAA: Error during Bluetooth handshake steps: ${e.message}", e)
                    }
                }

            } catch (e: Exception) {
                AppLog.e("NativeAA: Error initiating handshake: ${e.message}")
            } finally {
                // Keep socket open for a bit to ensure messages are sent
                serviceScopeLaunch {
                    kotlinx.coroutines.delay(5000)
                    try { socket.close() } catch (e: Exception) {}
                }
            }
        }.apply {
            name = "NativeAaBtHandshake"
            start()
        }
    }

    // Small helper since we don't have serviceScope here easily without passing it
    private fun serviceScopeLaunch(block: suspend () -> Unit) {
        Thread {
            kotlinx.coroutines.runBlocking { block() }
        }.start()
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .build()
        
        sendProtobuf(output, request.toByteArray(), 1) // Type 1: WifiStartRequest
    }

    private fun sendWifiSecurityResponse(output: OutputStream, ssid: String, key: String) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setBssid("00:00:00:00:00:00") // Phone usually doesn't care about BSSID
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(Wireless.AccessPointType.STATIC)
            .build()
        
        sendProtobuf(output, response.toByteArray(), 3) // Type 3: WifiInfoResponse
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Short) {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put((data.size shr 8).toByte())
        buffer.put((data.size and 0xFF).toByte())
        buffer.putShort(type)
        buffer.put(data)
        output.write(buffer.array())
        output.flush()
        AppLog.i("Sent Bluetooth Protobuf type $type, size ${data.size}")
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(4)
        input.readFully(header)
        
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        
        AppLog.i("Received Bluetooth Protobuf type $type, size $size")
        
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        
        return ProtobufMessage(type, payload)
    }
}