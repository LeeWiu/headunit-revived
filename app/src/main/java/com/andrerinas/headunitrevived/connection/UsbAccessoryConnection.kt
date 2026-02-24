package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsbAccessoryConnection(private val usbMgr: UsbManager, private val device: UsbDevice) : AccessoryConnection {
    private var usbDeviceConnected: UsbDeviceCompat? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    // Internal buffer to mimic HUR 6.3 behavior (16KB chunks)
    private val internalBuffer = ByteArray(16384)
    private var internalBufferPos = 0
    private var internalBufferAvailable = 0

    fun isDeviceRunning(device: UsbDevice): Boolean {
        synchronized(sLock) {
            val connected = usbDeviceConnected ?: return false
            return UsbDeviceCompat.getUniqueName(device) == connected.uniqueName
        }
    }

    override suspend fun connect() = withContext(Dispatchers.Main) {
        return@withContext try {
            connect(device)
        } catch (e: UsbOpenException) {
            AppLog.e(e)
            false
        }
    }

    @Throws(UsbOpenException::class)
    private fun connect(device: UsbDevice): Boolean {
        if (usbDeviceConnection != null) {
            disconnect()
        }
        synchronized(sLock) {
            try {
                usbOpen(device)                                        // Open USB device & claim interface
            } catch (e: UsbOpenException) {
                disconnect()                                                // Ensure state is disconnected
                throw e
            }

            val ret = initEndpoint()                                  // Set Accessory mode Endpoints
            if (ret < 0) {                                                    // If error...
                disconnect()                                              // Ensure state is disconnected
                return false
            }

            usbDeviceConnected = UsbDeviceCompat(device)
            return true
        }
    }

    @Throws(UsbOpenException::class)
    private fun usbOpen(device: UsbDevice) {
        var connection: UsbDeviceConnection? = null
        var lastError: Throwable? = null
        
        // Try up to 3 times to open the device, with a small delay
        for (i in 0 until 3) {
            try {
                connection = usbMgr.openDevice(device)
                if (connection != null) break
            } catch (e: Throwable) {
                lastError = e
                AppLog.w("Attempt ${i+1} to openDevice failed: ${e.message}")
            }
            if (i < 2) try { Thread.sleep(500) } catch (e: Exception) {}
        }
        
        usbDeviceConnection = connection ?: throw UsbOpenException(lastError ?: Throwable("openDevice: connection is null"))

        AppLog.i("Established connection: " + usbDeviceConnection!!)

        try {
            val interfaceCount = device.interfaceCount
            if (interfaceCount <= 0) {
                AppLog.e("interfaceCount: $interfaceCount")
                throw UsbOpenException("No usb interfaces")
            }
            AppLog.i("interfaceCount: $interfaceCount")
            usbInterface = device.getInterface(0)

            if (!usbDeviceConnection!!.claimInterface(usbInterface, true)) {
                // Claim interface, if error...   true = take from kernel
                throw UsbOpenException("Error claiming interface")
            }
        } catch (e: Throwable) {
            AppLog.e(e)
            throw UsbOpenException(e)
        }

    }

    private fun initEndpoint(): Int {
        AppLog.i("Check accessory endpoints")
        endpointIn = null
        endpointOut = null

        for (i in 0 until usbInterface!!.endpointCount) {        // For all USB endpoints...
            val ep = usbInterface!!.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) {              // If IN
                if (endpointIn == null) {                                      // If Bulk In not set yet...
                    endpointIn = ep                                             // Set Bulk In
                }
            } else {                                                            // Else if OUT...
                if (endpointOut == null) {                                     // If Bulk Out not set yet...
                    endpointOut = ep                                            // Set Bulk Out
                }
            }
        }
        if (endpointIn == null || endpointOut == null) {
            AppLog.e("Unable to find bulk endpoints")
            return -1                                                      // Done error
        }

        AppLog.i("Connected have EPs")
        return 0                                                         // Done success
    }

    private fun resetInterface() {
        synchronized(sLock) {
            val connection = usbDeviceConnection ?: return
            val iface = usbInterface ?: return
            AppLog.w("Attempting USB interface soft-reset...")
            try {
                connection.releaseInterface(iface)
                Thread.sleep(100)
                if (connection.claimInterface(iface, true)) {
                    AppLog.i("USB interface re-claimed successfully")
                    initEndpoint()
                } else {
                    AppLog.e("Failed to re-claim USB interface")
                }
            } catch (e: Exception) {
                AppLog.e("Error during USB reset: ${e.message}")
            }
        }
    }

    override fun disconnect() {                                           // Release interface and close USB device connection. Called only by usb_disconnect()
        synchronized(sLock) {
            if (usbDeviceConnected != null) {
                AppLog.i(usbDeviceConnected!!.toString())
            }
            endpointIn = null                                               // Input  EP
            endpointOut = null                                               // Output EP

            if (usbDeviceConnection != null) {
                var bret = false
                if (usbInterface != null) {
                    bret = usbDeviceConnection!!.releaseInterface(usbInterface)
                }
                if (bret) {
                    AppLog.i("OK releaseInterface()")
                } else {
                    AppLog.e("Error releaseInterface()")
                }

                usbDeviceConnection!!.close()                                        //
            }
            usbDeviceConnection = null
            usbInterface = null
            usbDeviceConnected = null
            internalBufferPos = 0
            internalBufferAvailable = 0
        }
    }

    override val isConnected: Boolean
        get() = usbDeviceConnected != null

    override val isSingleMessage: Boolean
        get() = false

    private var consecutiveErrors = 0
    private val maxConsecutiveErrorsBeforeReset = 3

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        synchronized(sLock) {
            val connection = usbDeviceConnection ?: return -1
            val ep = endpointOut ?: return -1

            val ret = try {
                connection.bulkTransfer(ep, buf, length, timeout)
            } catch (e: Exception) {
                AppLog.e("USB Write Error: ${e.message}")
                -1
            }

            if (ret < 0) {
                consecutiveErrors++
                if (consecutiveErrors >= maxConsecutiveErrorsBeforeReset) {
                    AppLog.w("Too many write errors, attempting interface reset...")
                    resetInterface()
                    consecutiveErrors = 0
                }
            } else {
                consecutiveErrors = 0
            }
            return ret
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        synchronized(sLock) {
            val connection = usbDeviceConnection ?: return -1
            val ep = endpointIn ?: return -1

            try {
                var totalReturned = 0
                
                while (totalReturned < length) {
                    // 1. Serve from internal buffer if data is available
                    if (internalBufferAvailable > 0) {
                        val toCopy = minOf(length - totalReturned, internalBufferAvailable)
                        System.arraycopy(internalBuffer, internalBufferPos, buf, totalReturned, toCopy)
                        internalBufferPos += toCopy
                        internalBufferAvailable -= toCopy
                        totalReturned += toCopy
                        
                        // If we have enough or we are not in readFully mode, return what we have
                        if (totalReturned >= length || !readFully) break
                        continue
                    }

                    // 2. Internal buffer empty, read new 16KB chunk from USB
                    val read = try {
                        connection.bulkTransfer(ep, internalBuffer, internalBuffer.size, timeout)
                    } catch (e: Exception) {
                        AppLog.e("USB Read Error: ${e.message}")
                        -1
                    }
                    
                    if (read < 0) {
                        consecutiveErrors++
                        if (consecutiveErrors >= maxConsecutiveErrorsBeforeReset) {
                            AppLog.w("Too many read errors, attempting interface reset...")
                            resetInterface()
                            consecutiveErrors = 0
                        }
                        return if (totalReturned > 0) totalReturned else -1
                    }
                    if (read == 0) {
                        consecutiveErrors = 0
                        return totalReturned
                    }
                    
                    consecutiveErrors = 0

                    internalBufferPos = 0
                    internalBufferAvailable = read
                    // Loop will continue and serve from the new internalBuffer data
                }
                
                return totalReturned

            } catch (e: Exception) {
                AppLog.e("USB Read Error: ${e.message}")
                return -1
            }
        }
    }

    private inner class UsbOpenException : Exception {
        internal constructor(message: String) : super(message)
        internal constructor(tr: Throwable) : super(tr)
    }

    companion object {
        private val sLock = Object()
    }
}
