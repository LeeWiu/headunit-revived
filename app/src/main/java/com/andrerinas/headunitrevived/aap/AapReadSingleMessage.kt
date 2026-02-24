package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.utils.AppLog

internal class AapReadSingleMessage(connection: AccessoryConnection, ssl: AapSsl, handler: AapMessageHandler)
    : AapRead.Base(connection, ssl, handler) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    private val msgBuffer = ByteArray(65535) // unsigned short max
    private val fragmentSizeBuffer = ByteArray(4)

    override fun doRead(connection: AccessoryConnection): Int {
        try {
            // Step 1: Read the encrypted header
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, 5000, true) 
            if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) {
                if (headerSize == -1) {
                    AppLog.i("AapRead: Connection closed (EOF). Disconnecting.")
                    return -1
                } else {
                    AppLog.e("AapRead: Failed to read full header. Expected ${AapMessageIncoming.EncryptedHeader.SIZE}, got $headerSize. Skipping.")
                    return 0
                }
            }

            recvHeader.decode()

            if (recvHeader.flags == 0x09) {
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, 150, true)
                if(readSize != 4) {
                    AppLog.e("AapRead: Failed to read fragment total size. Skipping.")
                    return 0
                }
            }

            // Step 2: Read the encrypted message body
            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                AppLog.e("AapRead: Invalid message size (${recvHeader.enc_len} bytes). Skipping.")
                return 0
            }
            
            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 5000, true)
            if (msgSize != recvHeader.enc_len) {
                AppLog.e("AapRead: Failed to read full message body. Expected ${recvHeader.enc_len}, got $msgSize. Skipping.")
                return 0
            }

            // Step 3: Decrypt the message
            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

            if (msg == null) {
                return 0
            }

            // Step 4: Handle the decrypted message
            handler.handle(msg)
            return 0
        } catch (e: Exception) {
            AppLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        }
    }
}
