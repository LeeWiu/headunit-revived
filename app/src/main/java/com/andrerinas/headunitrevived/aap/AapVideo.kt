package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.LegacyOptimizer
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings) {

    private val messageBuffer = ByteBuffer.allocate(Messages.DEF_BUFFER_LENGTH * 8)
    private var legacyAssembledBuffer: ByteArray? = null

    fun process(message: AapMessage): Boolean {

        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> {
                messageBuffer.clear()
                // Timestamp Indication (Offset 10)
                if (len > 14 && buf[10].toInt() == 0 && buf[11].toInt() == 0 && buf[12].toInt() == 0 && buf[13].toInt() == 1) {
                    videoDecoder.decode(buf, 10, len - 10, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }
                // Media Indication or Config (Offset 2)
                if (len > 6 && buf[2].toInt() == 0 && buf[3].toInt() == 0 && buf[4].toInt() == 0 && buf[5].toInt() == 1) {
                    videoDecoder.decode(buf, 2, len - 2, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }
                AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len, buf[10]=${if (len > 10) buf[10] else "?"}")
            }
            9 -> {
                // Timestamp Indication (Offset 10)
                if (len > 14 && buf[10].toInt() == 0 && buf[11].toInt() == 0 && buf[12].toInt() == 0 && buf[13].toInt() == 1) {
                    messageBuffer.clear()
                    messageBuffer.put(message.data, 10, message.size - 10)
                    return true
                }
                // Media Indication (Offset 2)
                if (len > 6 && buf[2].toInt() == 0 && buf[3].toInt() == 0 && buf[4].toInt() == 0 && buf[5].toInt() == 1) {
                    messageBuffer.clear()
                    messageBuffer.put(message.data, 2, message.size - 2)
                    return true
                }
            }
            8 -> {
                messageBuffer.put(message.data, 0, message.size)// If Middle fragment Video
                return true
            }
            10 -> {
                messageBuffer.put(message.data, 0, message.size)
                messageBuffer.flip()
                
                val assembledSize = messageBuffer.limit()
                
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    // For legacy devices, use recycled buffer if possible to avoid GC
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    videoDecoder.decode(legacyAssembledBuffer!!, 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                } else {
                    // Modern devices handle short-lived allocations well
                    videoDecoder.decode(messageBuffer.array(), 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                }
                
                messageBuffer.clear()
                return true
            }
        }

        return false
    }
}
