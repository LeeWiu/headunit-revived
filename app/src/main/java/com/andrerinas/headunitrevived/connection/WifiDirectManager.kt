package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import com.andrerinas.headunitrevived.utils.AppLog
import java.net.InetAddress
import java.net.NetworkInterface

class WifiDirectManager(private val context: Context) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.let { 
        AppLog.i("WifiDirect: Initializing P2P Manager...")
        it.initialize(context, context.mainLooper, null) 
    }
    
    private var currentGroup: WifiP2pGroup? = null

    @SuppressLint("MissingPermission")
    fun createGroup(callback: (ssid: String, psk: String, ip: String, bssid: String) -> Unit) {
        if (manager == null || channel == null) {
            AppLog.e("WifiDirect: Hardware or Service not available on this device")
            return
        }

        AppLog.i("WifiDirect: Attempting to create group...")
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirect: Old group removed")
                doCreateGroup(callback)
            }
            override fun onFailure(reason: Int) {
                // If 2 (BUSY), it might already be gone or we are in a transition. Try anyway.
                doCreateGroup(callback)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun doCreateGroup(callback: (ssid: String, psk: String, ip: String, bssid: String) -> Unit) {
        // We could use config here to set SSID/Pass, but createGroup() usually auto-generates.
        // On many older devices, we cannot easily force a password for P2P groups.
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirect: Group created successfully. Waiting for group info...")
                requestGroupInfo(callback)
            }
            override fun onFailure(reason: Int) {
                val reasonStr = when(reason) {
                    0 -> "ERROR"
                    1 -> "P2P_UNSUPPORTED"
                    2 -> "BUSY"
                    else -> "UNKNOWN ($reason)"
                }
                AppLog.e("WifiDirect: Failed to create group (reason: $reasonStr). Ensure GPS/Location is ON and permissions are granted.")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo(callback: (ssid: String, psk: String, ip: String, bssid: String) -> Unit, retryCount: Int = 0) {
        manager?.requestGroupInfo(channel) { group ->
            if (group != null) {
                currentGroup = group
                val ssid = group.networkName
                val psk = group.passphrase
                val bssid = getWifiDirectMac(group.`interface`)
                
                // Wait for IP with retries
                Thread {
                    var ip = getLocalIpAddress()
                    var retries = 0
                    while (ip == null && retries < 10) {
                        AppLog.i("WifiDirect: Waiting for IP on interface ${group.`interface`}...")
                        Thread.sleep(500)
                        ip = getLocalIpAddress()
                        retries++
                    }
                    
                    val finalIp = ip ?: "192.168.49.1"
                    AppLog.i("WifiDirect: Group Ready. SSID=$ssid, IP=$finalIp, BSSID=$bssid")
                    
                    callback(ssid, psk ?: "", finalIp, bssid)
                }.start()

            } else {
                if (retryCount < 10) {
                    AppLog.w("WifiDirect: Group info is null, retrying ($retryCount/10) in 1s...")
                    Thread {
                        Thread.sleep(1000)
                        requestGroupInfo(callback, retryCount + 1)
                    }.start()
                } else {
                    AppLog.e("WifiDirect: Group info is still null after 10 retries. Ensure Location is ON.")
                }
            }
        }
    }

    private fun getWifiDirectMac(ifaceName: String?): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (ifaceName != null && iface.name != ifaceName) continue
                if (ifaceName == null && !iface.name.contains("p2p")) continue
                
                val mac = iface.hardwareAddress
                if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    return sb.toString()
                }
            }
        } catch (e: Exception) {}
        return "00:00:00:00:00:00"
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        AppLog.d("WifiDirect: Found local IP ${addr.hostAddress} on interface ${iface.name}")
                        // Prioritize p2p interface
                        if (iface.name.contains("p2p")) return addr.hostAddress
                    }
                }
            }
            
            // Fallback: second pass for any non-loopback if no p2p found yet
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val iface = interfaces2.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirect: Error getting local IP", e)
        }
        return null
    }

    fun stop() {
        manager?.removeGroup(channel, null)
        currentGroup = null
    }
}
