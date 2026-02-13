package com.andrerinas.headunitrevived.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val settings = Settings(context)
        val targetMac = settings.autoStartBluetoothDeviceMac

        if (targetMac.isEmpty()) return

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            AppLog.i("BT Device connected: ${device?.name} (${device?.address})")

            if (device?.address == targetMac) {
                AppLog.i("MATCH! Starting AapService via Bluetooth Auto-start...")

                // Start the service to make the app alive
                val serviceIntent = Intent(context, AapService::class.java)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // Also attempt to start the UI (might be blocked on Android 10+ without special permission)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    AppLog.w("Could not start UI from background (expected on Android 10+): ${e.message}")
                }
            }
        }
    }
}
