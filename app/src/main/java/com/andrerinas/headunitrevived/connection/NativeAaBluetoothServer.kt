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
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.*

class NativeAaBluetoothServer(private val context: Context) {
    private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    private var serverSocket: BluetoothServerSocket? = null
    private var thread: Thread? = null
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
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
                        AppLog.i("Bluetooth client connected: ${socket.remoteDevice.name} (${socket.remoteDevice.address})")
                        handleConnection(socket)
                    }
                }
            } catch (e: IOException) {
                AppLog.e("Native AA Bluetooth server error: ${e.message}")
            } finally {
                stop()
            }
        }.apply { 
            name = "NativeAaBtServer"
            start() 
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        thread = null
    }

    private fun handleConnection(socket: BluetoothSocket) {
        Thread {
            try {
                val input = DataInputStream(socket.inputStream)
                val output = socket.outputStream

                val myIp = getLocalIpAddress()
                if (myIp == null) {
                    AppLog.e("Could not determine local IP address for Bluetooth handshake")
                    return@Thread
                }

                AppLog.i("Starting Bluetooth handshake. Local IP: $myIp")
                
                // 1. Send WifiStartRequest (our IP and port 5288)
                sendWifiStartRequest(output, myIp, 5288)
                
                // 2. Read response and handle security info if needed
                readAndHandleResponse(input, output)

            } catch (e: Exception) {
                AppLog.e("Error in Bluetooth handshake: ${e.message}")
            } finally {
                try {
                    // Give it some time to finish sending if needed
                    Thread.sleep(1000)
                    socket.close() 
                } catch (e: Exception) {}
            }
        }.apply {
            name = "NativeAaBtHandshake"
            start()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        // Prefer 192.168.x.x (hotspot) or other private IPs
                        val ip = addr.hostAddress
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Error getting local IP", e)
        }
        return null
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .build()
        
        sendProtobuf(output, request.toByteArray(), 1) // Type 1: WifiStartRequest
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

    private fun readAndHandleResponse(input: DataInputStream, output: OutputStream) {
        val header = ByteArray(4)
        input.readFully(header)
        
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        
        AppLog.i("Received Bluetooth Protobuf type $type, size $size")
        
        if (size > 0) {
            val payload = ByteArray(size)
            input.readFully(payload)
            
            if (type == 2) { // Phone wants security credentials?
                // In some cases, the phone might want to know OUR hotspot credentials.
                // For now, we assume the phone is connecting to us.
                AppLog.i("Phone requested security info (Type 2)")
                // TODO: If we start a hotspot, send credentials here (Type 3)
            }
        }
    }
}
