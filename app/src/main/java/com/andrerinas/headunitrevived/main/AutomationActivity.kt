package com.andrerinas.headunitrevived.main

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A transparent activity that handles App Shortcuts and Deep Links.
 * It translates incoming intents into service actions for AapService.
 */
class AutomationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Invisible activity
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val data = intent.data
        val action = intent.action
        
        AppLog.i("AutomationActivity: Received intent. Action: $action, Data: $data")
        
        if (data?.scheme == "headunit") {
            handleUri(data)
        } else {
            val state = intent.getStringExtra("state")
            handleAction(action, state)
        }
        
        finish()
    }

    private fun handleUri(data: android.net.Uri) {
        when (data.host) {
            "connect" -> {
                val ip = data.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@AutomationActivity).commManager.connect(ip, 5277) }
                } else {
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        this.action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            }
            "disconnect" -> {
                val stopIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            }
            "exit" -> {
                val exitIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                // Broadcast a finish request to close MainActivity if it's open
                sendBroadcast(Intent("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"))
            }
            "nightmode" -> {
                val state = data.getQueryParameter("state")
                applyNightMode(state)
            }
        }
    }

    private fun handleAction(incomingAction: String?, incomingState: String?) {
        when (incomingAction) {
            "com.andrerinas.headunitrevived.ACTION_SET_NIGHT_MODE" -> applyNightMode(incomingState)
            "com.andrerinas.headunitrevived.ACTION_CONNECT" -> {
                val autoIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_CHECK_USB
                }
                ContextCompat.startForegroundService(this, autoIntent)
            }
            "com.andrerinas.headunitrevived.ACTION_DISCONNECT" -> {
                val stopIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            }
            "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE" -> {
                val exitIntent = Intent(this, AapService::class.java).apply {
                    this.action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                sendBroadcast(Intent("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"))
            }
        }
    }

    private fun applyNightMode(state: String?) {
        val appSettings = App.provide(this).settings
        when (state?.lowercase()) {
            "day" -> appSettings.nightMode = Settings.NightMode.DAY
            "night" -> appSettings.nightMode = Settings.NightMode.NIGHT
            "auto" -> appSettings.nightMode = Settings.NightMode.AUTO
        }
        val updateIntent = Intent(this, AapService::class.java).apply {
            this.action = AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE
        }
        ContextCompat.startForegroundService(this, updateIntent)
    }
}
