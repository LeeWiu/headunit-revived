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
    fun createGroup(callback: (ssid: String, psk: String, ip: String) -> Unit) {
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
    private fun doCreateGroup(callback: (ssid: String, psk: String, ip: String) -> Unit) {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirect: Group created successfully")
                requestGroupInfo(callback)
            }
            override fun onFailure(reason: Int) {
                AppLog.e("WifiDirect: Failed to create group (reason: $reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo(callback: (ssid: String, psk: String, ip: String) -> Unit) {
        manager?.requestGroupInfo(channel) { group ->
            if (group != null) {
                currentGroup = group
                val ssid = group.networkName
                val psk = group.passphrase
                
                // Wait for IP with retries
                Thread {
                    var ip = getWifiDirectIp(group.`interface`)
                    var retries = 0
                    while (ip == null && retries < 10) {
                        AppLog.i("WifiDirect: Waiting for IP on interface ${group.`interface`}...")
                        Thread.sleep(500)
                        ip = getWifiDirectIp(group.`interface`)
                        retries++
                    }
                    
                    val finalIp = ip ?: "192.168.49.1"
                    AppLog.i("WifiDirect: Group Ready. SSID=$ssid, PSK=$psk, Interface=${group.`interface`}, IP=$finalIp")
                    
                    // Callback on original thread or worker? NativeAaBluetoothServer handles threads, so this is fine.
                    callback(ssid, psk, finalIp)
                }.start()

            } else {
                AppLog.e("WifiDirect: Group info is null")
            }
        }
    }

    private fun getWifiDirectIp(ifaceName: String?): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (ifaceName != null && iface.name != ifaceName) continue
                
                // If no name, check for p2p-wlan or similar
                if (ifaceName == null && !iface.name.contains("p2p")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirect: Error getting IP", e)
        }
        return null
    }

    fun stop() {
        manager?.removeGroup(channel, null)
        currentGroup = null
    }
}
