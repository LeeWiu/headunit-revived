package com.andrerinas.headunitrevived.connection
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.headunitrevived.aap.AapTransport
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import android.media.AudioManager
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.messages.SensorEvent
import java.net.Socket

/**
 * Central connection and transport lifecycle manager.
 *
 * CommManager owns the full lifecycle of both the physical connection ([AccessoryConnection])
 * and the AAP protocol layer ([AapTransport]). It exposes a single [connectionState] flow as
 * the source of truth; all consumers (AapService, AapProjectionActivity, UI fragments) observe
 * this flow reactively instead of being called imperatively.
 *
 * ## State machine
 * ```
 *   Disconnected ──connect()──► Connecting ──success──► Connected
 *                                                            │
 *                                                    startTransport()
 *                                                            │
 *                                                    StartingTransport
 *                                                            │
 *                                                     handshake OK
 *                                                            │
 *                                                    TransportStarted
 *                                                            │
 *                                      disconnect() / read error / phone bye-bye
 *                                                            │
 *                                                      Disconnected
 * ```
 *
 * ## Thread safety
 * [_transport] and [_connection] are `@Volatile`. All state mutations go through
 * [_connectionState] (a [MutableStateFlow]) which is thread-safe. The internal [_scope] uses
 * [Dispatchers.IO] with a [SupervisorJob] so individual coroutine failures do not cancel the
 * entire scope.
 */
class CommManager(
    private val context: Context,
    private val settings: Settings,
    private val audioDecoder: AudioDecoder,
    private val videoDecoder: VideoDecoder) {

    /**
     * Represents the lifecycle state of the Android Auto connection.
     *
     * State transitions are strictly sequential — see the class-level diagram.
     */
    sealed class ConnectionState {
        /**
         * No active connection.
         * @param isClean `true` if the phone sent a graceful `ByeByeRequest` before closing;
         *                `false` for all other disconnect causes (USB detach, read error,
         *                socket timeout, explicit user disconnect).
         */
        data class Disconnected(val isClean: Boolean = false) : ConnectionState()

        /** Physical connection handshake in progress (USB open or TCP connect). */
        object Connecting : ConnectionState()

        /** Physical connection established; AAP handshake not yet started. */
        object Connected : ConnectionState()

        /** AAP handshake started; waiting for SSL and service-discovery to complete. */
        object StartingTransport : ConnectionState()

        /** AAP handshake complete; the transport is ready to send and receive messages. */
        object TransportStarted : ConnectionState()

        /** A non-fatal error occurred. The manager transitions to [Disconnected] immediately after. */
        data class Error(val message: String) : ConnectionState()
    }

    /** IO-bound coroutine scope for all async connection work. SupervisorJob prevents one
     *  failing child from cancelling the rest. */
    private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())

    /** @Volatile: written on IO thread, read on Main and IO threads. */
    @Volatile private var _transport: AapTransport? = null
    @Volatile private var _connection: AccessoryConnection? = null

    private val _backgroundNotification = BackgroundNotification(context)

    /** Public read-only view of [_connectionState]. */
    val connectionState = _connectionState.asStateFlow()

    /**
     * `true` while a physical connection exists, regardless of whether the AAP transport
     * handshake has completed. Covers [ConnectionState.Connected], [ConnectionState.StartingTransport],
     * and [ConnectionState.TransportStarted].
     */
    val isConnected: Boolean
        get() = connectionState.value.let {
            it is ConnectionState.Connected ||
            it is ConnectionState.StartingTransport ||
            it is ConnectionState.TransportStarted
        }

    /**
     * Returns `true` if the current USB connection is to [device].
     * Used by AapService to decide whether a USB detach event should trigger a disconnect.
     */
    fun isConnectedToUsbDevice(device: UsbDevice): Boolean =
        (_connection as? UsbAccessoryConnection)?.isDeviceRunning(device) == true

    // -----------------------------------------------------------------------------------------
    // connect() overloads — one for each transport type
    // -----------------------------------------------------------------------------------------

    /**
     * Opens a USB accessory connection to [device].
     *
     * On success emits [ConnectionState.Connected] and persists the device as the last-used
     * connection so it can be auto-reconnected on the next launch.
     */
    suspend fun connect(device: UsbDevice) = withContext(Dispatchers.IO) {
        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = UsbAccessoryConnection(context.getSystemService(Context.USB_SERVICE) as UsbManager, device)

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_USB, usbDevice = UsbDeviceCompat.getUniqueName(device))
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Wraps an already-connected [Socket] (e.g. accepted by `WirelessServer`) in a
     * [SocketAccessoryConnection] and advances to [ConnectionState.Connected].
     *
     * The socket must already be connected; this overload skips the TCP handshake and only
     * sets up the AAP framing layer.
     */
    suspend fun connect(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = SocketAccessoryConnection(socket, context)

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = socket.inetAddress?.hostAddress ?: "")
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Opens a TCP connection to [ip]:[port] and advances to [ConnectionState.Connected].
     *
     * Used by the manual IP entry flow and the NSD-discovered device list.
     */
    suspend fun connect(ip: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = SocketAccessoryConnection(ip, port, context)

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = ip)
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Transport lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Runs the AAP handshake (SSL + service discovery) over the current connection.
     *
     * Must only be called when state is [ConnectionState.Connected]. On success:
     * 1. Emits [ConnectionState.TransportStarted].
     * 2. Claims audio focus for `STREAM_MUSIC` so Android Auto audio can play.
     *
     * The [AapTransport.onQuit] callback is wired here; it fires whenever the transport
     * stops (read error, phone bye-bye, timeout) and triggers [transportedQuited].
     */
    suspend fun startTransport() = withContext(Dispatchers.IO) {
        try {
            if (_connectionState.value is ConnectionState.Connected)
            {
                _connectionState.emit(ConnectionState.StartingTransport)

                if (_transport == null) {
                    val audioManager = context.getSystemService(Application.AUDIO_SERVICE) as AudioManager
                    _transport = AapTransport(audioDecoder, videoDecoder, audioManager, settings, _backgroundNotification, context)
                    _transport!!.onQuit = { isClean -> transportedQuited(isClean) }
                }
                if (_transport?.start(_connection!!) == true) {
                    _transport?.aapAudio?.requestFocusChange(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN,
                        AudioManager.OnAudioFocusChangeListener { }
                    )
                    _connectionState.emit(ConnectionState.TransportStarted)
                }
            }
            else
                _connectionState.emit(ConnectionState.Error("Starting transport without connection"))
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Called by `AapTransport.onQuit` when the transport stops itself (read error, socket
     * timeout, or phone-initiated graceful close).
     *
     * Sets state to [ConnectionState.Disconnected] synchronously (so [isConnected] returns
     * `false` immediately) then schedules cleanup. `sendByeBye` is `false` because the
     * connection is already dead — there is no point sending a `ByeByeRequest`.
     */
    private fun transportedQuited(isClean: Boolean) {
        _connectionState.value = ConnectionState.Disconnected(isClean)
        // Transport already quit on its own — no ByeByeRequest needed (connection is dead).
        _scope.launch { doDisconnect(sendByeBye = false) }
    }

    // -----------------------------------------------------------------------------------------
    // send() overloads — fire-and-forget; silently dropped if not TransportStarted
    // -----------------------------------------------------------------------------------------

    /** Sends a key press or release event to the phone. */
    fun send(keyCode: Int, isPress: Boolean) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            try {
                _scope.launch {
                    _transport?.send(keyCode, isPress)
                }
            } catch (e: Exception) {
                _scope.launch {
                    _connectionState.emit(ConnectionState.Error("Send failed"))
                    disconnect()
                }
            }
        }
    }

    /** Sends a sensor event (e.g. driving status, night mode) to the phone. */
    fun send(sensor: SensorEvent) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            try {
                _scope.launch {
                    _transport?.send(sensor)
                }
            } catch (e: Exception) {
                _scope.launch {
                    _connectionState.emit(ConnectionState.Error("Send failed"))
                    disconnect()
                }
            }
        }
    }

    /** Sends a raw [AapMessage] (e.g. touch event, video focus request) to the phone. */
    fun send(message: AapMessage) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            try {
                _scope.launch {
                    _transport?.send(message)
                }
            } catch (e: Exception) {
                _scope.launch {
                    _connectionState.emit(ConnectionState.Error("Send failed"))
                    disconnect()
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Disconnect
    // -----------------------------------------------------------------------------------------

    /**
     * Initiates a user-requested disconnect.
     *
     * Sets state to [ConnectionState.Disconnected] synchronously so callers see the change
     * immediately, then schedules async cleanup via [doDisconnect]. A ByeByeRequest is sent
     * to the phone before closing the connection.
     */
    fun disconnect() {
        if (_connectionState.value is ConnectionState.Disconnected) return

        _connectionState.value = ConnectionState.Disconnected()
        _scope.launch { doDisconnect() }
    }

    /**
     * Tears down the transport and physical connection.
     *
     * **Null-first pattern**: [_transport] and [_connection] are captured and nulled at the
     * very start. This prevents re-entrant double-cleanup: `AapTransport.stop()` fires `onQuit`
     * → [transportedQuited] → a second [doDisconnect] call — which now finds both fields null
     * and exits cleanly.
     *
     * @param sendByeBye `true` (default) when the user initiates the disconnect — calls
     *   `AapTransport.stop()`, which sends a `ByeByeRequest` to the phone and waits ~150 ms
     *   for acknowledgement. `false` when the transport self-quit (read error, socket timeout):
     *   the connection is already dead, so `AapTransport.quit()` is called directly to skip
     *   the send and the sleep.
     */
    private fun doDisconnect(sendByeBye: Boolean = true) {
        // Capture and null out immediately to prevent a second doDisconnect() call
        // (from transportedQuited firing onQuit during stop()) from double-stopping.
        val transport = _transport
        val connection = _connection
        _transport = null
        _connection = null
        try {
            // Only send ByeByeRequest when we are initiating the disconnect (e.g. user pressed
            // disconnect). When the transport self-quit (read error, soTimeout), the connection
            // is already dead — skip the send and the 150 ms sleep inside stop().
            if (sendByeBye) transport?.stop() else transport?.quit()
            connection?.disconnect()
        } catch (e: Exception) {
            AppLog.e("doDisconnect error: ${e.message}")
        } finally {
            if (_connectionState.value !is ConnectionState.Disconnected) {
                _connectionState.value = ConnectionState.Disconnected()
            }
        }
    }

    /**
     * Performs a final disconnect and cancels the internal coroutine scope.
     *
     * Call this when the owning component (e.g. the foreground service) is destroyed.
     * After [destroy], the CommManager instance must not be used again.
     */
    fun destroy() {
        _scope.launch {
            withContext(Dispatchers.IO) {
                doDisconnect()
            }
        }
        _scope.cancel()
    }
}
