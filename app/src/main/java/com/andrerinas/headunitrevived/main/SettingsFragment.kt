package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.BuildConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Local state to hold changes before saving
    private var pendingNightMode: Settings.NightMode? = null
    private var pendingMicSampleRate: Int? = null
    private var pendingUseGps: Boolean? = null
    private var pendingResolution: Int? = null
    private var pendingDpi: Int? = null
    private var pendingFullscreen: Boolean? = null
    private var pendingViewMode: Settings.ViewMode? = null
    private var pendingForceSoftware: Boolean? = null
    private var pendingRightHandDrive: Boolean? = null
    private var pendingWifiConnectionMode: Int? = null
    private var pendingAutoConnectLastSession: Boolean? = null
    private var pendingVideoCodec: String? = null
    private var pendingFpsLimit: Int? = null
    private var pendingDebugMode: Boolean? = null
    private var pendingBluetoothAddress: String? = null
    private var pendingEnableAudioSink: Boolean? = null
    private var pendingUseAacAudio: Boolean? = null
    private var pendingMicInputSource: Int? = null
    private var pendingUseNativeSsl: Boolean? = null
    private var pendingAutoStartSelfMode: Boolean? = null
    private var pendingAutoStartBtName: String? = null
    private var pendingAutoStartBtMac: String? = null
    private var pendingScreenOrientation: Settings.ScreenOrientation? = null
    private var pendingAppLanguage: String? = null
    private var pendingThresholdLux: Int? = null
    private var pendingThresholdBrightness: Int? = null
    private var pendingManualStart: Int? = null
    private var pendingManualEnd: Int? = null
    // Custom Insets
    private var pendingInsetLeft: Int? = null
    private var pendingInsetTop: Int? = null
    private var pendingInsetRight: Int? = null
    private var pendingInsetBottom: Int? = null

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize local state with current values
        pendingNightMode = settings.nightMode
        pendingThresholdLux = settings.nightModeThresholdLux
        pendingThresholdBrightness = settings.nightModeThresholdBrightness
        pendingManualStart = settings.nightModeManualStart
        pendingManualEnd = settings.nightModeManualEnd
        pendingMicSampleRate = settings.micSampleRate
        pendingUseGps = settings.useGpsForNavigation
        pendingResolution = settings.resolutionId
        pendingDpi = settings.dpiPixelDensity
        pendingFullscreen = settings.startInFullscreenMode
        pendingViewMode = settings.viewMode
        pendingForceSoftware = settings.forceSoftwareDecoding
        pendingRightHandDrive = settings.rightHandDrive
        pendingWifiConnectionMode = settings.wifiConnectionMode
        pendingAutoConnectLastSession = settings.autoConnectLastSession
        pendingVideoCodec = settings.videoCodec
        pendingFpsLimit = settings.fpsLimit
        pendingDebugMode = settings.debugMode
        pendingBluetoothAddress = settings.bluetoothAddress
        pendingEnableAudioSink = settings.enableAudioSink
        pendingUseAacAudio = settings.useAacAudio
        pendingMicInputSource = settings.micInputSource
        pendingUseNativeSsl = settings.useNativeSsl
        pendingAutoStartSelfMode = settings.autoStartSelfMode
        pendingAutoStartBtName = settings.autoStartBluetoothDeviceName
        pendingAutoStartBtMac = settings.autoStartBluetoothDeviceMac
        pendingScreenOrientation = settings.screenOrientation
        pendingAppLanguage = settings.appLanguage
        
        pendingInsetLeft = settings.insetLeft
        pendingInsetTop = settings.insetTop
        pendingInsetRight = settings.insetRight
        pendingInsetBottom = settings.insetBottom

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
        
        // Add the Save item with custom layout
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        // Get the button from the action view
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        pendingNightMode?.let { settings.nightMode = it }
        pendingThresholdLux?.let { settings.nightModeThresholdLux = it }
        pendingThresholdBrightness?.let { settings.nightModeThresholdBrightness = it }
        pendingManualStart?.let { settings.nightModeManualStart = it }
        pendingManualEnd?.let { settings.nightModeManualEnd = it }
        pendingMicSampleRate?.let { settings.micSampleRate = it }
        pendingUseGps?.let { settings.useGpsForNavigation = it }
        pendingResolution?.let { settings.resolutionId = it }
        pendingDpi?.let { settings.dpiPixelDensity = it }
        pendingFullscreen?.let { settings.startInFullscreenMode = it }
        pendingViewMode?.let { settings.viewMode = it }
        pendingForceSoftware?.let { settings.forceSoftwareDecoding = it }
        pendingRightHandDrive?.let { settings.rightHandDrive = it }
        pendingVideoCodec?.let { settings.videoCodec = it }
        pendingFpsLimit?.let { settings.fpsLimit = it }
        pendingDebugMode?.let { settings.debugMode = it }
        pendingBluetoothAddress?.let { settings.bluetoothAddress = it }
        pendingEnableAudioSink?.let { settings.enableAudioSink = it }
        pendingUseAacAudio?.let { settings.useAacAudio = it }
        pendingMicInputSource?.let { settings.micInputSource = it }
        pendingUseNativeSsl?.let { settings.useNativeSsl = it }
        pendingAutoStartSelfMode?.let { settings.autoStartSelfMode = it }
        pendingAutoStartBtName?.let { settings.autoStartBluetoothDeviceName = it }
        pendingAutoStartBtMac?.let { settings.autoStartBluetoothDeviceMac = it }
        pendingScreenOrientation?.let { settings.screenOrientation = it }

        val languageChanged = pendingAppLanguage != settings.appLanguage
        pendingAppLanguage?.let { settings.appLanguage = it }
        
        pendingInsetLeft?.let { settings.insetLeft = it }
        pendingInsetTop?.let { settings.insetTop = it }
        pendingInsetRight?.let { settings.insetRight = it }
        pendingInsetBottom?.let { settings.insetBottom = it }

        pendingWifiConnectionMode?.let { mode ->
            settings.wifiConnectionMode = mode
            val intent = Intent(requireContext(), AapService::class.java).apply {
                action = when (mode) {
                    2 -> AapService.ACTION_START_WIRELESS
                    3 -> AapService.ACTION_START_NATIVE_AA
                    else -> AapService.ACTION_STOP_WIRELESS // This also stops native AA if we implement stop properly
                }
            }
            if (mode != 3) {
                // Ensure native AA is stopped if switching away from it
                val stopNativeIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_NATIVE_AA
                }
                ContextCompat.startForegroundService(requireContext(), stopNativeIntent)
            }
            ContextCompat.startForegroundService(requireContext(), intent)
        }

        pendingAutoConnectLastSession?.let { settings.autoConnectLastSession = it }

        // Notify Service about Night Mode changes immediately
        val nightModeUpdateIntent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
        requireContext().sendBroadcast(nightModeUpdateIntent)

        if (requiresRestart) {
            if (AapService.isConnected) {
                Toast.makeText(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT).show()
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }
        
        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()

        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()

        // Restart activity if language changed to apply new locale
        if (languageChanged) {
            requireActivity().recreate()
        }
    }

    private fun checkChanges() {
        // Check for any changes
        val anyChange = pendingNightMode != settings.nightMode ||
                        pendingThresholdLux != settings.nightModeThresholdLux ||
                        pendingThresholdBrightness != settings.nightModeThresholdBrightness ||
                        pendingManualStart != settings.nightModeManualStart ||
                        pendingManualEnd != settings.nightModeManualEnd ||
                        pendingMicSampleRate != settings.micSampleRate ||
                        pendingUseGps != settings.useGpsForNavigation ||
                        pendingResolution != settings.resolutionId ||
                        pendingDpi != settings.dpiPixelDensity ||
                        pendingFullscreen != settings.startInFullscreenMode ||
                        pendingViewMode != settings.viewMode ||
                        pendingForceSoftware != settings.forceSoftwareDecoding ||
                        pendingRightHandDrive != settings.rightHandDrive ||
                        pendingWifiConnectionMode != settings.wifiConnectionMode ||
                        pendingAutoConnectLastSession != settings.autoConnectLastSession ||
                        pendingVideoCodec != settings.videoCodec ||
                        pendingFpsLimit != settings.fpsLimit ||
                        pendingDebugMode != settings.debugMode ||
                        pendingBluetoothAddress != settings.bluetoothAddress ||
                        pendingEnableAudioSink != settings.enableAudioSink ||
                        pendingUseAacAudio != settings.useAacAudio ||
                        pendingMicInputSource != settings.micInputSource ||
                        pendingUseNativeSsl != settings.useNativeSsl ||
                        pendingAutoStartSelfMode != settings.autoStartSelfMode ||
                        pendingAutoStartBtMac != settings.autoStartBluetoothDeviceMac ||
                        pendingScreenOrientation != settings.screenOrientation ||
                        pendingAppLanguage != settings.appLanguage ||
                        pendingInsetLeft != settings.insetLeft ||
                        pendingInsetTop != settings.insetTop ||
                        pendingInsetRight != settings.insetRight ||
                        pendingInsetBottom != settings.insetBottom

        hasChanges = anyChange

        // Check for restart requirement
        requiresRestart = pendingResolution != settings.resolutionId ||
                          pendingVideoCodec != settings.videoCodec ||
                          pendingFpsLimit != settings.fpsLimit ||
                          pendingDpi != settings.dpiPixelDensity ||
                          pendingForceSoftware != settings.forceSoftwareDecoding ||
                          pendingRightHandDrive != settings.rightHandDrive ||
                          pendingEnableAudioSink != settings.enableAudioSink ||
                          pendingUseAacAudio != settings.useAacAudio ||
                          pendingUseNativeSsl != settings.useNativeSsl ||
                          pendingInsetLeft != settings.insetLeft ||
                          pendingInsetTop != settings.insetTop ||
                          pendingInsetRight != settings.insetRight ||
                          pendingInsetBottom != settings.insetBottom

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        // --- General Settings ---
        items.add(SettingItem.CategoryHeader("general", R.string.category_general))

        // Language Selector
        val availableLocales = LocaleHelper.getAvailableLocales(requireContext())
        val currentLocale = LocaleHelper.stringToLocale(pendingAppLanguage ?: "")
        val currentLanguageDisplay = if (currentLocale != null) {
            LocaleHelper.getDisplayName(currentLocale)
        } else {
            getString(R.string.system_default)
        }

        items.add(SettingItem.SettingEntry(
            stableId = "appLanguage",
            nameResId = R.string.app_language,
            value = currentLanguageDisplay,
            onClick = { _ ->
                val languageNames = mutableListOf(getString(R.string.system_default))
                val localeCodes = mutableListOf("")

                availableLocales.forEach { locale ->
                    languageNames.add(LocaleHelper.getDisplayName(locale))
                    localeCodes.add(LocaleHelper.localeToString(locale))
                }

                val currentIndex = localeCodes.indexOf(pendingAppLanguage ?: "").coerceAtLeast(0)

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_language)
                    .setSingleChoiceItems(languageNames.toTypedArray(), currentIndex) { dialog, which ->
                        pendingAppLanguage = localeCodes[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = run {
                val base = resources.getStringArray(R.array.night_mode)[pendingNightMode!!.value]
                if (pendingNightMode == Settings.NightMode.AUTO) {
                    val info = com.andrerinas.headunitrevived.utils.NightMode(settings, true).getCalculationInfo()
                    "$base ($info)"
                } else {
                    base
                }
            },
            onClick = { _ ->
                val nightModeTitles = resources.getStringArray(R.array.night_mode)
                
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.night_mode)
                    .setSingleChoiceItems(nightModeTitles, pendingNightMode!!.value) { dialog, which ->
                        pendingNightMode = Settings.NightMode.fromInt(which)!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        if (pendingNightMode == Settings.NightMode.LIGHT_SENSOR || pendingNightMode == Settings.NightMode.SCREEN_BRIGHTNESS) {
            val isSensor = pendingNightMode == Settings.NightMode.LIGHT_SENSOR
            val unit = if (isSensor) "Lux" else "/ 255"
            val desc = if (isSensor) getString(R.string.threshold_lux_desc) else getString(R.string.threshold_brightness_desc)
            val currentValue = if (isSensor) pendingThresholdLux else pendingThresholdBrightness
            
            items.add(SettingItem.SettingEntry(
                stableId = "nightModeThreshold",
                nameResId = R.string.night_mode_threshold,
                value = "$currentValue $unit",
                onClick = { _ ->
                    val editView = EditText(requireContext())
                    editView.inputType = InputType.TYPE_CLASS_NUMBER
                    editView.setText(currentValue.toString())
                    
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.enter_threshold_value)
                        .setMessage(desc)
                        .setView(editView)
                        .setPositiveButton(android.R.string.ok) { dialog, _ ->
                            val newVal = editView.text.toString().toIntOrNull()
                            if (newVal != null && newVal >= 0) {
                                if (isSensor) {
                                    pendingThresholdLux = newVal
                                } else {
                                    pendingThresholdBrightness = newVal
                                }
                                checkChanges()
                                updateSettingsList()
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            ))
        }

        if (pendingNightMode == Settings.NightMode.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(pendingManualStart!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingManualStart = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingManualStart!! / 60, pendingManualStart!! % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(pendingManualEnd!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingManualEnd = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingManualEnd!! / 60, pendingManualEnd!! % 60, true).show()
                }
            ))
        }

        items.add(SettingItem.SettingEntry(
            stableId = "keymap",
            nameResId = R.string.keymap,
            value = getString(R.string.keymap_description),
            onClick = { _ ->
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_keymapFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "gpsNavigation",
            nameResId = R.string.gps_for_navigation,
            descriptionResId = R.string.gps_for_navigation_description,
            isChecked = pendingUseGps!!,
            onCheckedChanged = { isChecked ->
                pendingUseGps = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "rightHandDrive",
            nameResId = R.string.right_hand_drive,
            descriptionResId = R.string.right_hand_drive_description,
            isChecked = pendingRightHandDrive!!,
            onCheckedChanged = { isChecked ->
                pendingRightHandDrive = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        val wifiModes = resources.getStringArray(R.array.wireless_connection_modes)
        items.add(SettingItem.SettingEntry(
            stableId = "wifiConnectionMode",
            nameResId = R.string.wireless_mode,
            value = wifiModes.getOrElse(pendingWifiConnectionMode!!) { "" },
            onClick = { _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.wireless_mode)
                    .setSingleChoiceItems(wifiModes, pendingWifiConnectionMode!!) { dialog, which ->
                        pendingWifiConnectionMode = which
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartSelfMode",
            nameResId = R.string.auto_start_self_mode,
            descriptionResId = R.string.auto_start_self_mode_description,
            isChecked = pendingAutoStartSelfMode!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartSelfMode = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartBt",
            nameResId = R.string.auto_start_bt_label,
            value = if (pendingAutoStartBtName.isNullOrEmpty()) getString(R.string.bt_device_not_set) else pendingAutoStartBtName!!,
            onClick = {
                showBluetoothDeviceSelector()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoConnectLastSession",
            nameResId = R.string.auto_connect_last_session,
            descriptionResId = R.string.auto_connect_last_session_description,
            isChecked = pendingAutoConnectLastSession!!,
            onCheckedChanged = { isChecked ->
                pendingAutoConnectLastSession = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Graphic Settings ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))
        
        items.add(SettingItem.SettingEntry(
            stableId = "resolution",
            nameResId = R.string.resolution,
            value = Settings.Resolution.fromId(pendingResolution!!)?.resName ?: "",
            onClick = { _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_resolution)
                    .setSingleChoiceItems(Settings.Resolution.allRes, pendingResolution!!) { dialog, which ->
                        pendingResolution = which
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "dpiPixelDensity",
            nameResId = R.string.dpi,
            value = if (pendingDpi == 0) getString(R.string.auto) else pendingDpi.toString(),
            onClick = { _ ->
                val editView = EditText(requireContext())
                editView.inputType = InputType.TYPE_CLASS_NUMBER
                if (pendingDpi != 0) {
                    editView.setText(pendingDpi.toString())
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.enter_dpi_value)
                    .setView(editView)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        val inputText = editView.text.toString().trim()
                        val newDpi = inputText.toIntOrNull()
                        if (newDpi != null && newDpi >= 0) {
                            pendingDpi = newDpi
                        } else {
                            pendingDpi = 0
                        }
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                                    .setNegativeButton(R.string.cancel) { dialog, _ ->
                                        dialog.cancel()
                                    }                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "customInsets",
            nameResId = R.string.custom_insets,
            value = "${pendingInsetLeft ?: 0}, ${pendingInsetTop ?: 0}, ${pendingInsetRight ?: 0}, ${pendingInsetBottom ?: 0}",
            onClick = {
                showCustomInsetsDialog()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "startInFullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            descriptionResId = R.string.start_in_fullscreen_mode_description,
            isChecked = pendingFullscreen!!,
            onCheckedChanged = { isChecked ->
                pendingFullscreen = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = when (pendingViewMode) {
                Settings.ViewMode.SURFACE -> getString(R.string.surface_view)
                Settings.ViewMode.TEXTURE -> getString(R.string.texture_view)
                Settings.ViewMode.GLES -> getString(R.string.gles_view)
                else -> getString(R.string.surface_view)
            },
            onClick = { _ ->
                val viewModes = arrayOf(getString(R.string.surface_view), getString(R.string.texture_view), getString(R.string.gles_view))
                val currentIdx = pendingViewMode!!.value
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_view_mode)
                    .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                        pendingViewMode = Settings.ViewMode.fromInt(which)!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "screenOrientation",
            nameResId = R.string.screen_orientation,
            value = resources.getStringArray(R.array.screen_orientation)[pendingScreenOrientation!!.value],
            onClick = { _ ->
                val orientationOptions = resources.getStringArray(R.array.screen_orientation)
                val currentIdx = pendingScreenOrientation!!.value
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_screen_orientation)
                    .setSingleChoiceItems(orientationOptions, currentIdx) { dialog, whiches ->
                        pendingScreenOrientation = Settings.ScreenOrientation.fromInt(whiches)
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Video Settings ---
        items.add(SettingItem.CategoryHeader("video", R.string.category_video))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "forceSoftwareDecoding",
            nameResId = R.string.force_software_decoding,
            descriptionResId = R.string.force_software_decoding_description,
            isChecked = pendingForceSoftware!!,
            onCheckedChanged = { isChecked ->
                pendingForceSoftware = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "videoCodec",
            nameResId = R.string.video_codec,
            value = pendingVideoCodec!!,
            onClick = { _ ->
                val codecs = arrayOf("Auto", "H.264", "H.265")
                val currentCodecIndex = codecs.indexOf(pendingVideoCodec)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.video_codec)
                    .setSingleChoiceItems(codecs, currentCodecIndex) { dialog, which ->
                        pendingVideoCodec = codecs[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "fpsLimit",
            nameResId = R.string.fps_limit,
            value = "${pendingFpsLimit} FPS",
            onClick = { _ ->
                val fpsOptions = arrayOf("30", "60")
                val currentFpsIndex = fpsOptions.indexOf(pendingFpsLimit.toString())
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fps_limit)
                    .setSingleChoiceItems(fpsOptions, currentFpsIndex) { dialog, which ->
                        pendingFpsLimit = fpsOptions[which].toInt()
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Audio Settings ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableAudioSink",
            nameResId = R.string.enable_audio_sink,
            descriptionResId = R.string.enable_audio_sink_description,
            isChecked = pendingEnableAudioSink!!,
            onCheckedChanged = { isChecked ->
                pendingEnableAudioSink = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useAacAudio",
            nameResId = R.string.use_aac_audio,
            descriptionResId = R.string.use_aac_audio_description,
            isChecked = pendingUseAacAudio!!,
            onCheckedChanged = { isChecked ->
                pendingUseAacAudio = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "micSampleRate",
            nameResId = R.string.mic_sample_rate,
            value = "${pendingMicSampleRate!! / 1000}kHz",
            onClick = { _ ->
                val currentSampleRateIndex = Settings.MicSampleRates.indexOf(pendingMicSampleRate!!)
                val sampleRateNames = Settings.MicSampleRates.map { "${it / 1000}kHz" }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mic_sample_rate)
                    .setSingleChoiceItems(sampleRateNames, currentSampleRateIndex) { dialog, which ->
                        val newValue = Settings.MicSampleRates.elementAt(which)
                        pendingMicSampleRate = newValue
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        val micSources = resources.getStringArray(R.array.mic_input_sources)
        val micSourceValues = intArrayOf(0, 1, 6, 7) // DEFAULT, MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION
        val currentSourceIndex = micSourceValues.indexOf(pendingMicInputSource ?: 0).coerceAtLeast(0)

        items.add(SettingItem.SettingEntry(
            stableId = "micInputSource",
            nameResId = R.string.mic_input_source,
            value = micSources[currentSourceIndex],
            onClick = {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.mic_input_source)
                    .setSingleChoiceItems(micSources, currentSourceIndex) { dialog, which ->
                        pendingMicInputSource = micSourceValues[which]
                        checkChanges()
                        updateSettingsList()
                        dialog.dismiss()
                    }
                    .show()
            }
        ))

        // --- Debug Settings ---
        items.add(SettingItem.CategoryHeader("debug", R.string.category_debug))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "debugMode",
            nameResId = R.string.debug_mode,
            descriptionResId = R.string.debug_mode_description,
            isChecked = pendingDebugMode!!,
            onCheckedChanged = { isChecked ->
                pendingDebugMode = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = {
                val context = requireContext()
                val logFile = com.andrerinas.headunitrevived.utils.LogExporter.saveLogToPublicFile(context)

                if (logFile != null) {
                    MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                        .setTitle(R.string.logs_exported)
                        .setMessage(getString(R.string.log_saved_to, logFile.absolutePath))
                        .setPositiveButton(R.string.share) { _, _ ->
                            com.andrerinas.headunitrevived.utils.LogExporter.shareLogFile(context, logFile)
                        }
                        .setNegativeButton(R.string.close) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    Toast.makeText(context, getString(R.string.failed_export_logs), Toast.LENGTH_SHORT).show()
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useNativeSsl",
            nameResId = R.string.use_native_ssl,
            descriptionResId = R.string.use_native_ssl_description,
            isChecked = pendingUseNativeSsl!!,
            onCheckedChanged = { isChecked ->
                pendingUseNativeSsl = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Info Settings ---
        items.add(SettingItem.CategoryHeader("info", R.string.category_info))

        items.add(SettingItem.SettingEntry(
            stableId = "version",
            nameResId = R.string.version,
            value = BuildConfig.VERSION_NAME,
            onClick = { /* Read only */ }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "about",
            nameResId = R.string.about,
            value = getString(R.string.about_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        settingsAdapter.submitList(items)
    }

    private fun showCustomInsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_insets, null)
        
        val inputLeft = dialogView.findViewById<EditText>(R.id.input_left)
        val inputTop = dialogView.findViewById<EditText>(R.id.input_top)
        val inputRight = dialogView.findViewById<EditText>(R.id.input_right)
        val inputBottom = dialogView.findViewById<EditText>(R.id.input_bottom)

        // Set initial values from pending state
        inputLeft.setText((pendingInsetLeft ?: 0).toString())
        inputTop.setText((pendingInsetTop ?: 0).toString())
        inputRight.setText((pendingInsetRight ?: 0).toString())
        inputBottom.setText((pendingInsetBottom ?: 0).toString())

        // Helper to update pending values and UI preview
        fun updatePreview() {
            val l = inputLeft.text.toString().toIntOrNull() ?: 0
            val t = inputTop.text.toString().toIntOrNull() ?: 0
            val r = inputRight.text.toString().toIntOrNull() ?: 0
            val b = inputBottom.text.toString().toIntOrNull() ?: 0
            
            pendingInsetLeft = l
            pendingInsetTop = t
            pendingInsetRight = r
            pendingInsetBottom = b
            
            // Live Preview: Set padding on the root view of the Activity
            val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
            root?.setPadding(l, t, r, b)
        }

        // Helper to bind buttons
        fun bindButton(btnId: Int, input: EditText, delta: Int) {
            dialogView.findViewById<View>(btnId).setOnClickListener {
                val current = input.text.toString().toIntOrNull() ?: 0
                val newVal = (current + delta).coerceAtLeast(0)
                input.setText(newVal.toString())
                updatePreview()
            }
        }

        bindButton(R.id.btn_left_minus, inputLeft, -10)
        bindButton(R.id.btn_left_plus, inputLeft, 10)
        bindButton(R.id.btn_top_minus, inputTop, -10)
        bindButton(R.id.btn_top_plus, inputTop, 10)
        bindButton(R.id.btn_right_minus, inputRight, -10)
        bindButton(R.id.btn_right_plus, inputRight, 10)
        bindButton(R.id.btn_bottom_minus, inputBottom, -10)
        bindButton(R.id.btn_bottom_plus, inputBottom, 10)

        // Text Watchers? Maybe overkill, buttons are safer.
        // Let's add simple focus change listener to update preview on manual entry
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updatePreview()
        }
        inputLeft.onFocusChangeListener = focusListener
        inputTop.onFocusChangeListener = focusListener
        inputRight.onFocusChangeListener = focusListener
        inputBottom.onFocusChangeListener = focusListener

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.custom_insets)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val l = inputLeft.text.toString().toIntOrNull() ?: 0
                val t = inputTop.text.toString().toIntOrNull() ?: 0
                val r = inputRight.text.toString().toIntOrNull() ?: 0
                val b = inputBottom.text.toString().toIntOrNull() ?: 0
                
                // PERSIST IMMEDIATELY to prevent revert on focus change
                settings.insetLeft = l
                settings.insetTop = t
                settings.insetRight = r
                settings.insetBottom = b
                
                // Update pending to keep UI in sync
                pendingInsetLeft = l
                pendingInsetTop = t
                pendingInsetRight = r
                pendingInsetBottom = b
                
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                // Revert Preview immediately
                val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
                root?.setPadding(
                    settings.insetLeft, settings.insetTop, 
                    settings.insetRight, settings.insetBottom
                )
                // Reset pending to old values
                pendingInsetLeft = settings.insetLeft
                pendingInsetTop = settings.insetTop
                pendingInsetRight = settings.insetRight
                pendingInsetBottom = settings.insetBottom
                
                dialog.dismiss()
            }
            .show()
    }

    private fun showBluetoothDeviceSelector() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), BT_CONNECT_PERMISSION_CODE)
            return
        }

        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        val bondedDevices = adapter.bondedDevices.toList()

        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                pendingAutoStartBtMac = device.address
                pendingAutoStartBtName = device.name
                checkChanges()
                updateSettingsList()
            }
            .setNeutralButton(R.string.remove) { _, _ ->
                pendingAutoStartBtMac = ""
                pendingAutoStartBtName = ""
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == BT_CONNECT_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showBluetoothDeviceSelector()
            } else {
                Toast.makeText(requireContext(), "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val BT_CONNECT_PERMISSION_CODE = 101
        private val SAVE_ITEM_ID = 1001
    }
}
